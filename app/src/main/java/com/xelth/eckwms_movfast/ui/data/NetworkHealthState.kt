package com.xelth.eckwms_movfast.ui.data

import androidx.compose.ui.graphics.Color

/**
 * Connection type indicator
 */
enum class ConnectionType {
    LOCAL_IP,    // IP address (e.g., 10.x.x.x, 192.168.x.x) - shows Computer icon
    GLOBAL_URL,  // Domain name (e.g., wms.example.com) - shows Globe icon
    NONE         // No connection
}

/**
 * Represents the network health status of the device's connectivity to servers.
 * Provides visual indicators and descriptions for different connection scenarios.
 */
sealed class NetworkHealthState(
    val displayName: String,
    val description: String,
    val color: Color,
    val icon: String,
    open val serverUrl: String = "",
    open val serverHash: String = "",
    open val connectionType: ConnectionType = ConnectionType.NONE,
    open val latencyMs: Long = 0
) {
    /**
     * DIRECT_LOCAL (Green): Direct connection to local server, optimal performance
     */
    data class DirectLocal(
        override val serverUrl: String = "",
        override val latencyMs: Long = 0
    ) : NetworkHealthState(
        displayName = "Direct Local",
        description = "Connected to local server - Optimal performance",
        color = Color(0xFF4CAF50), // Green
        icon = "✓",
        serverUrl = serverUrl,
        serverHash = generateHash(serverUrl),
        connectionType = detectConnectionType(serverUrl),
        latencyMs = latencyMs
    )

    /**
     * PROXY_GLOBAL (Yellow): Connected via global proxy server, slower but functional
     */
    data class ProxyGlobal(
        override val serverUrl: String = "",
        override val latencyMs: Long = 0
    ) : NetworkHealthState(
        displayName = "Proxy Global",
        description = "Connected via global proxy - Slower performance",
        color = Color(0xFFFFEB3B), // Yellow
        icon = "⚡",
        serverUrl = serverUrl,
        serverHash = generateHash(serverUrl),
        connectionType = detectConnectionType(serverUrl),
        latencyMs = latencyMs
    )

    /**
     * OFFLINE (Red): No connection to any server
     */
    object Offline : NetworkHealthState(
        displayName = "Offline",
        description = "No connection to servers - Working in offline mode",
        color = Color(0xFFF44336), // Red
        icon = "✗"
    )

    /**
     * LOCAL_ONLY_NO_INTERNET (Orange warning): Local server reachable but no internet
     */
    data class LocalOnlyNoInternet(
        override val serverUrl: String = "",
        override val latencyMs: Long = 0
    ) : NetworkHealthState(
        displayName = "Local Only",
        description = "Local server OK, but no internet access",
        color = Color(0xFFFF9800), // Orange
        icon = "⚠",
        serverUrl = serverUrl,
        serverHash = generateHash(serverUrl),
        connectionType = detectConnectionType(serverUrl),
        latencyMs = latencyMs
    )

    /**
     * GLOBAL_ONLY_CACHE_MODE (Blue warning): Only global server reachable, using cached config
     */
    data class GlobalOnlyCacheMode(
        override val serverUrl: String = "",
        override val latencyMs: Long = 0
    ) : NetworkHealthState(
        displayName = "Global Only",
        description = "Only global server reachable - Using cached configuration",
        color = Color(0xFF2196F3), // Blue
        icon = "ℹ",
        serverUrl = serverUrl,
        serverHash = generateHash(serverUrl),
        connectionType = detectConnectionType(serverUrl),
        latencyMs = latencyMs
    )

    /**
     * CHECKING: Currently checking connectivity status
     */
    object Checking : NetworkHealthState(
        displayName = "Checking",
        description = "Checking network connectivity...",
        color = Color(0xFF9E9E9E), // Gray
        icon = "..."
    )

    /**
     * RESTORING: Attempting to restore connection using previous configurations
     */
    object Restoring : NetworkHealthState(
        displayName = "Restoring",
        description = "Restoring connection from history...",
        color = Color(0xFF9E9E9E), // Gray
        icon = "⟳"
    )

    /**
     * Returns true if the device can communicate with servers (either local or global)
     */
    fun isConnected(): Boolean = when (this) {
        is DirectLocal, is ProxyGlobal, is LocalOnlyNoInternet, is GlobalOnlyCacheMode -> true
        is Offline, is Checking, is Restoring -> false
    }

    /**
     * Returns true if the connection is optimal (direct local connection)
     */
    fun isOptimal(): Boolean = this is DirectLocal

    companion object {
        /**
         * Generates a short hash identifier from URL (last 4 chars)
         */
        fun generateHash(url: String): String {
            if (url.isEmpty()) return "??"
            val hash = url.hashCode().toString(16).uppercase().replace("-", "")
            return "#" + hash.takeLast(4)
        }

        /**
         * Detects if URL is local IP or global domain
         */
        fun detectConnectionType(url: String): ConnectionType {
            if (url.isEmpty()) return ConnectionType.NONE

            // Remove protocol and port for cleaner detection
            val cleanUrl = url.replace(Regex("https?://"), "").split(":").first()

            // Check if it's an IP address (contains only digits and dots)
            return if (cleanUrl.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                ConnectionType.LOCAL_IP
            } else {
                ConnectionType.GLOBAL_URL
            }
        }
    }
}
