package com.xelth.eckwms_movfast.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

object ConnectivityTester {
    private const val TAG = "ConnectivityTester"
    private const val TIMEOUT_MS = 30000L // 30 seconds timeout

    suspend fun findReachableUrl(urls: List<String>): String? = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) {
            Log.w(TAG, "URL list is empty, cannot find reachable URL.")
            return@withContext null
        }

        Log.d(TAG, "========================================")
        Log.d(TAG, "Testing connectivity for ${urls.size} URLs")
        Log.d(TAG, "Timeout: ${TIMEOUT_MS}ms (30 seconds)")
        Log.d(TAG, "========================================")
        urls.forEachIndexed { index, url ->
            Log.d(TAG, "[${index + 1}/${urls.size}] $url")
        }
        Log.d(TAG, "========================================")

        // Create async tasks for all URLs
        val deferreds = urls.map { url ->
            async {
                checkUrl(url)
            }
        }

        try {
            // Wait for ALL tasks to complete and collect results
            val results = deferreds.awaitAll()

            // Find the first non-null result
            val winner = results.firstOrNull { it != null }

            if (winner != null) {
                Log.i(TAG, "========================================")
                Log.i(TAG, "✅ SUCCESS: First reachable server found")
                Log.i(TAG, "URL: $winner")
                Log.i(TAG, "========================================")
                return@withContext winner
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during parallel checks: ${e.message}", e)
        }

        Log.e(TAG, "========================================")
        Log.e(TAG, "❌ NO REACHABLE SERVER FOUND")
        Log.e(TAG, "All ${urls.size} URLs failed to respond")
        Log.e(TAG, "========================================")
        return@withContext null
    }

    private suspend fun checkUrl(urlStr: String): String? {
        val start = System.currentTimeMillis()
        return try {
            withTimeoutOrNull(TIMEOUT_MS) {
                Log.d(TAG, "🔍 Testing: $urlStr/health")

                val url = URL("$urlStr/health")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = TIMEOUT_MS.toInt()
                connection.readTimeout = TIMEOUT_MS.toInt()
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "eckWMS-Android/1.0")
                // Prevent redirects to speed up failure on captive portals
                connection.instanceFollowRedirects = false

                val responseCode = connection.responseCode
                val timeTaken = System.currentTimeMillis() - start
                connection.disconnect()

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Log.i(TAG, "✅ SUCCESS: $urlStr (${timeTaken}ms, HTTP $responseCode)")
                    urlStr
                } else {
                    Log.w(TAG, "❌ FAILED: $urlStr (${timeTaken}ms, HTTP $responseCode)")
                    null
                }
            } ?: run {
                // Timeout occurred
                val timeTaken = System.currentTimeMillis() - start
                Log.w(TAG, "⏱️ TIMEOUT: $urlStr (${timeTaken}ms)")
                null
            }
        } catch (e: Exception) {
            val timeTaken = System.currentTimeMillis() - start
            Log.w(TAG, "❌ ERROR: $urlStr (${timeTaken}ms) - ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
