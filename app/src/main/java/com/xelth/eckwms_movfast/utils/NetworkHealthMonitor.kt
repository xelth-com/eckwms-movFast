package com.xelth.eckwms_movfast.utils

import android.util.Log
import com.xelth.eckwms_movfast.ui.data.NetworkHealthState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Network Health Monitor
 * Performs independent health checks on local and global servers
 * Determines the overall network health state based on connectivity status
 */
object NetworkHealthMonitor {
    private const val TAG = "NetworkHealthMonitor"
    private const val HEALTH_CHECK_TIMEOUT_MS = 5000L // 5 seconds per server

    /**
     * Result of a server health check
     */
    data class ServerHealthResult(
        val url: String,
        val isReachable: Boolean,
        val responseTimeMs: Long,
        val error: String? = null
    )

    /**
     * Checks the health of both local and global servers independently
     * Returns the appropriate NetworkHealthState based on the results
     */
    suspend fun checkNetworkHealth(
        localServerUrl: String?,
        globalServerUrl: String?
    ): NetworkHealthState = withContext(Dispatchers.IO) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "Starting network health check")
        Log.d(TAG, "Local server: ${localServerUrl ?: "not configured"}")
        Log.d(TAG, "Global server: ${globalServerUrl ?: "not configured"}")
        Log.d(TAG, "========================================")

        // If no servers configured, return offline
        if (localServerUrl.isNullOrBlank() && globalServerUrl.isNullOrBlank()) {
            Log.w(TAG, "No servers configured - returning OFFLINE")
            SettingsManager.saveLocalServerReachable(false)
            SettingsManager.saveGlobalServerReachable(false)
            return@withContext NetworkHealthState.Offline
        }

        // Check both servers in parallel
        val localHealthDeferred = async {
            localServerUrl?.let { checkServerHealth(it, "LOCAL") }
        }
        val globalHealthDeferred = async {
            globalServerUrl?.let { checkServerHealth(it, "GLOBAL") }
        }

        val localHealth = localHealthDeferred.await()
        val globalHealth = globalHealthDeferred.await()

        // Persist reachability status
        SettingsManager.saveLocalServerReachable(localHealth?.isReachable ?: false)
        SettingsManager.saveGlobalServerReachable(globalHealth?.isReachable ?: false)

        // Update last working URLs if reachable and add to history
        if (localHealth?.isReachable == true) {
            SettingsManager.saveLastWorkingLocalUrl(localServerUrl!!)
            // Add successful local URL to connection history
            SettingsManager.addToConnectionHistory(localServerUrl)
            Log.d(TAG, "Added to connection history: $localServerUrl")
        }
        if (globalHealth?.isReachable == true) {
            SettingsManager.saveLastWorkingGlobalUrl(globalServerUrl!!)
        }

        // Determine overall health state
        val healthState = determineHealthState(localHealth, globalHealth)

        Log.d(TAG, "========================================")
        Log.d(TAG, "Health check complete: ${healthState.displayName}")
        Log.d(TAG, "========================================")

        // Persist health state
        SettingsManager.saveLastHealthState(healthState.displayName)
        SettingsManager.saveLastHealthCheckTimestamp(System.currentTimeMillis())

        return@withContext healthState
    }

    /**
     * Checks the health of a single server
     */
    private suspend fun checkServerHealth(
        serverUrl: String,
        serverType: String
    ): ServerHealthResult {
        val start = System.currentTimeMillis()
        return try {
            withTimeoutOrNull(HEALTH_CHECK_TIMEOUT_MS) {
                val healthPath = if (serverUrl.contains("PROXY", ignoreCase = true)) "/HEALTH" else "/health"
                Log.d(TAG, "[$serverType] Checking: $serverUrl$healthPath")

                val url = URL("$serverUrl$healthPath")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = HEALTH_CHECK_TIMEOUT_MS.toInt()
                connection.readTimeout = HEALTH_CHECK_TIMEOUT_MS.toInt()
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "eckWMS-Android/HealthCheck")
                // Follow redirects for HTTPS URLs
                connection.instanceFollowRedirects = serverUrl.startsWith("https://", ignoreCase = true)

                val responseCode = connection.responseCode
                val timeTaken = System.currentTimeMillis() - start
                connection.disconnect()

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Log.i(TAG, "[$serverType] ✅ REACHABLE: $serverUrl (${timeTaken}ms)")
                    ServerHealthResult(
                        url = serverUrl,
                        isReachable = true,
                        responseTimeMs = timeTaken
                    )
                } else {
                    Log.w(TAG, "[$serverType] ❌ FAILED: $serverUrl (HTTP $responseCode, ${timeTaken}ms)")
                    ServerHealthResult(
                        url = serverUrl,
                        isReachable = false,
                        responseTimeMs = timeTaken,
                        error = "HTTP $responseCode"
                    )
                }
            } ?: run {
                val timeTaken = System.currentTimeMillis() - start
                Log.w(TAG, "[$serverType] ⏱️ TIMEOUT: $serverUrl (${timeTaken}ms)")
                ServerHealthResult(
                    url = serverUrl,
                    isReachable = false,
                    responseTimeMs = timeTaken,
                    error = "Timeout"
                )
            }
        } catch (e: Exception) {
            val timeTaken = System.currentTimeMillis() - start
            Log.w(TAG, "[$serverType] ❌ ERROR: $serverUrl (${timeTaken}ms) - ${e.javaClass.simpleName}: ${e.message}")
            ServerHealthResult(
                url = serverUrl,
                isReachable = false,
                responseTimeMs = timeTaken,
                error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Determines the overall health state based on local and global server health
     */
    private fun determineHealthState(
        localHealth: ServerHealthResult?,
        globalHealth: ServerHealthResult?
    ): NetworkHealthState {
        val localReachable = localHealth?.isReachable ?: false
        val globalReachable = globalHealth?.isReachable ?: false

        return when {
            // Scenario 1: Direct Local (Green) - Best case
            localReachable && globalReachable -> {
                Log.i(TAG, "State: DIRECT_LOCAL (Both servers reachable)")
                NetworkHealthState.DirectLocal(
                    serverUrl = localHealth!!.url,
                    latencyMs = localHealth.responseTimeMs
                )
            }

            // Scenario 2: Local Only (Orange) - Local OK, no internet
            localReachable && !globalReachable -> {
                Log.i(TAG, "State: LOCAL_ONLY_NO_INTERNET (Local reachable, global not)")
                NetworkHealthState.LocalOnlyNoInternet(
                    serverUrl = localHealth!!.url,
                    latencyMs = localHealth.responseTimeMs
                )
            }

            // Scenario 3: Proxy Global (Yellow) - Only global reachable, using cache
            !localReachable && globalReachable -> {
                Log.i(TAG, "State: PROXY_GLOBAL (Only global reachable)")
                NetworkHealthState.ProxyGlobal(
                    serverUrl = globalHealth!!.url,
                    latencyMs = globalHealth.responseTimeMs
                )
            }

            // Scenario 4: Offline (Red) - Nothing reachable
            else -> {
                Log.i(TAG, "State: OFFLINE (No servers reachable)")
                NetworkHealthState.Offline
            }
        }
    }

    /**
     * Performs a quick connectivity check (used for initial state before full health check)
     */
    suspend fun quickConnectivityCheck(): Boolean = withContext(Dispatchers.IO) {
        try {
            val localUrl = SettingsManager.getServerUrl()
            val result = withTimeoutOrNull(2000L) {
                checkServerHealth(localUrl, "QUICK")
            }
            result?.isReachable ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Quick connectivity check failed: ${e.message}")
            false
        }
    }

    /**
     * Smart Recovery: Attempts to restore connection using history
     * If current URLs fail, tries previously successful URLs from history
     * Returns the health state after recovery attempt
     */
    suspend fun performSmartRecovery(
        onStateChange: (NetworkHealthState) -> Unit
    ): NetworkHealthState = withContext(Dispatchers.IO) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "SMART RECOVERY INITIATED")
        Log.d(TAG, "========================================")

        // Get current configuration
        val currentLocalUrl = SettingsManager.getServerUrl()
        val currentGlobalUrl = SettingsManager.getGlobalServerUrl()

        // Try current configuration first
        Log.d(TAG, "Step 1: Testing current configuration")
        onStateChange(NetworkHealthState.Checking)
        val currentHealth = checkNetworkHealth(currentLocalUrl, currentGlobalUrl)

        if (currentHealth.isConnected()) {
            Log.i(TAG, "✅ Current configuration works: ${currentHealth.displayName}")
            // Add to history if it's a successful local connection
            if (currentHealth is NetworkHealthState.DirectLocal ||
                currentHealth is NetworkHealthState.LocalOnlyNoInternet) {
                SettingsManager.addToConnectionHistory(currentLocalUrl)
            }
            return@withContext currentHealth
        }

        Log.w(TAG, "❌ Current configuration failed: ${currentHealth.displayName}")
        Log.d(TAG, "Step 2: Attempting recovery from history")

        // Get connection history
        val history = SettingsManager.getConnectionHistory()
        Log.d(TAG, "Found ${history.size} URLs in history: $history")

        if (history.isEmpty()) {
            Log.w(TAG, "No history available for recovery")
            return@withContext currentHealth // Return offline or whatever the current state is
        }

        // Try each URL in history
        onStateChange(NetworkHealthState.Restoring)

        for ((index, historicUrl) in history.withIndex()) {
            Log.d(TAG, "Attempting recovery [${index + 1}/${history.size}]: $historicUrl")

            // Skip if it's the same as current (already tested)
            if (historicUrl == currentLocalUrl) {
                Log.d(TAG, "  Skipping (same as current)")
                continue
            }

            // Test this historic URL
            val result = checkServerHealth(historicUrl, "RECOVERY")

            if (result.isReachable) {
                Log.i(TAG, "========================================")
                Log.i(TAG, "✅ RECOVERY SUCCESSFUL!")
                Log.i(TAG, "Restored connection to: $historicUrl")
                Log.i(TAG, "Response time: ${result.responseTimeMs}ms")
                Log.i(TAG, "========================================")

                // Update active server URL to the working historic URL
                SettingsManager.saveServerUrl(historicUrl)
                SettingsManager.saveLastWorkingLocalUrl(historicUrl)

                // Move this URL to the front of history
                SettingsManager.addToConnectionHistory(historicUrl)

                // Re-check health with new configuration
                val recoveredHealth = checkNetworkHealth(historicUrl, currentGlobalUrl)
                return@withContext recoveredHealth
            } else {
                Log.d(TAG, "  Failed: ${result.error}")
            }
        }

        // All recovery attempts failed
        Log.e(TAG, "========================================")
        Log.e(TAG, "❌ RECOVERY FAILED")
        Log.e(TAG, "All ${history.size} historic URLs failed")
        Log.e(TAG, "========================================")

        return@withContext NetworkHealthState.Offline
    }
}
