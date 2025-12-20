package com.xelth.eckwms_movfast.ui.data

import androidx.compose.ui.graphics.Color

/**
 * Represents the network health status of the device's connectivity to servers.
 * Provides visual indicators and descriptions for different connection scenarios.
 */
sealed class NetworkHealthState(
    val displayName: String,
    val description: String,
    val color: Color,
    val icon: String
) {
    /**
     * DIRECT_LOCAL (Green): Direct connection to local server, optimal performance
     */
    object DirectLocal : NetworkHealthState(
        displayName = "Direct Local",
        description = "Connected to local server - Optimal performance",
        color = Color(0xFF4CAF50), // Green
        icon = "✓"
    )

    /**
     * PROXY_GLOBAL (Yellow): Connected via global proxy server, slower but functional
     */
    object ProxyGlobal : NetworkHealthState(
        displayName = "Proxy Global",
        description = "Connected via global proxy - Slower performance",
        color = Color(0xFFFFEB3B), // Yellow
        icon = "⚡"
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
    object LocalOnlyNoInternet : NetworkHealthState(
        displayName = "Local Only",
        description = "Local server OK, but no internet access",
        color = Color(0xFFFF9800), // Orange
        icon = "⚠"
    )

    /**
     * GLOBAL_ONLY_CACHE_MODE (Blue warning): Only global server reachable, using cached config
     */
    object GlobalOnlyCacheMode : NetworkHealthState(
        displayName = "Global Only",
        description = "Only global server reachable - Using cached configuration",
        color = Color(0xFF2196F3), // Blue
        icon = "ℹ"
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
}
