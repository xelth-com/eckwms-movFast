package com.xelth.eckwms_movfast.utils

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {
    private const val PREFS_NAME = "eckwms_settings"
    private const val KEY_RESOLUTION = "image_resolution"
    private const val KEY_QUALITY = "image_quality"
    private const val KEY_SERVER_URL = "server_url"
    private const val DEFAULT_SERVER_URL = "https://pda.repair"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveImageResolution(resolution: Int) = prefs.edit().putInt(KEY_RESOLUTION, resolution).apply()
    fun getImageResolution(): Int = prefs.getInt(KEY_RESOLUTION, 1920)

    fun saveImageQuality(quality: Int) = prefs.edit().putInt(KEY_QUALITY, quality).apply()
    fun getImageQuality(): Int = prefs.getInt(KEY_QUALITY, 75)

    // Critical: Use commit() for immediate disk persistence
    fun saveServerUrl(url: String) = prefs.edit().putString(KEY_SERVER_URL, url.trim()).commit()
    fun getServerUrl(): String = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL

    private const val KEY_GLOBAL_SERVER_URL = "global_server_url"
    private const val DEFAULT_GLOBAL_SERVER_URL = "https://pda.repair"

    // Critical: Use commit() for immediate disk persistence
    fun saveGlobalServerUrl(url: String) = prefs.edit().putString(KEY_GLOBAL_SERVER_URL, url.trim()).commit()
    fun getGlobalServerUrl(): String = prefs.getString(KEY_GLOBAL_SERVER_URL, DEFAULT_GLOBAL_SERVER_URL) ?: DEFAULT_GLOBAL_SERVER_URL

    private const val KEY_SERVER_PUBLIC_KEY = "server_public_key_hex"

    fun saveServerPublicKey(keyHex: String) = prefs.edit().putString(KEY_SERVER_PUBLIC_KEY, keyHex.trim()).commit()
    fun getServerPublicKey(): String? = prefs.getString(KEY_SERVER_PUBLIC_KEY, null)

    // Network health and connectivity persistence
    private const val KEY_LAST_WORKING_LOCAL_URL = "last_working_local_url"
    private const val KEY_LAST_WORKING_GLOBAL_URL = "last_working_global_url"
    private const val KEY_LAST_HEALTH_CHECK_TIMESTAMP = "last_health_check_timestamp"
    private const val KEY_LAST_HEALTH_STATE = "last_health_state"
    private const val KEY_LOCAL_SERVER_REACHABLE = "local_server_reachable"
    private const val KEY_GLOBAL_SERVER_REACHABLE = "global_server_reachable"

    // Critical: Use commit() for immediate disk persistence
    fun saveLastWorkingLocalUrl(url: String) = prefs.edit().putString(KEY_LAST_WORKING_LOCAL_URL, url.trim()).commit()
    fun getLastWorkingLocalUrl(): String? = prefs.getString(KEY_LAST_WORKING_LOCAL_URL, null)

    // Critical: Use commit() for immediate disk persistence
    fun saveLastWorkingGlobalUrl(url: String) = prefs.edit().putString(KEY_LAST_WORKING_GLOBAL_URL, url.trim()).commit()
    fun getLastWorkingGlobalUrl(): String? = prefs.getString(KEY_LAST_WORKING_GLOBAL_URL, null)

    fun saveLastHealthCheckTimestamp(timestamp: Long) = prefs.edit().putLong(KEY_LAST_HEALTH_CHECK_TIMESTAMP, timestamp).apply()
    fun getLastHealthCheckTimestamp(): Long = prefs.getLong(KEY_LAST_HEALTH_CHECK_TIMESTAMP, 0L)

    fun saveLastHealthState(state: String) = prefs.edit().putString(KEY_LAST_HEALTH_STATE, state).apply()
    fun getLastHealthState(): String? = prefs.getString(KEY_LAST_HEALTH_STATE, null)

    fun saveLocalServerReachable(reachable: Boolean) = prefs.edit().putBoolean(KEY_LOCAL_SERVER_REACHABLE, reachable).apply()
    fun isLocalServerReachable(): Boolean = prefs.getBoolean(KEY_LOCAL_SERVER_REACHABLE, false)

    fun saveGlobalServerReachable(reachable: Boolean) = prefs.edit().putBoolean(KEY_GLOBAL_SERVER_REACHABLE, reachable).apply()
    fun isGlobalServerReachable(): Boolean = prefs.getBoolean(KEY_GLOBAL_SERVER_REACHABLE, false)

    private const val KEY_DEVICE_STATUS = "device_status"
    fun saveDeviceStatus(status: String) = prefs.edit().putString(KEY_DEVICE_STATUS, status).apply()
    fun getDeviceStatus(): String = prefs.getString(KEY_DEVICE_STATUS, "unknown") ?: "unknown"

    private const val KEY_USER_ROLE = "user_role"
    private const val KEY_PERMISSIONS = "user_permissions"

    fun saveUserRole(role: String) = prefs.edit().putString(KEY_USER_ROLE, role).apply()
    fun getUserRole(): String = prefs.getString(KEY_USER_ROLE, "Unknown") ?: "Unknown"

    fun savePermissions(permissions: Set<String>) = prefs.edit().putStringSet(KEY_PERMISSIONS, permissions).apply()
    fun getPermissions(): Set<String> = prefs.getStringSet(KEY_PERMISSIONS, emptySet()) ?: emptySet()

    fun hasPermission(permission: String): Boolean {
        // Admin role implies all permissions (failsafe)
        if (getUserRole() == "SUPER_ADMIN") return true
        return getPermissions().contains(permission)
    }

    fun getDeviceId(context: Context): String {
        return android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    // Connection history for smart recovery (circular buffer of last 5 successful URLs)
    private const val KEY_CONNECTION_HISTORY = "connection_history"
    private const val MAX_HISTORY_SIZE = 5

    /**
     * Adds a successful server URL to the connection history
     * Maintains a circular buffer of the last 5 successful connections
     */
    fun addToConnectionHistory(url: String) {
        val history = getConnectionHistory().toMutableList()

        // Remove if already exists to avoid duplicates
        history.remove(url)

        // Add to the beginning (most recent)
        history.add(0, url)

        // Keep only the last MAX_HISTORY_SIZE entries
        if (history.size > MAX_HISTORY_SIZE) {
            history.subList(MAX_HISTORY_SIZE, history.size).clear()
        }

        // Save back to preferences (using commit for critical data)
        val jsonString = history.joinToString(",")
        prefs.edit().putString(KEY_CONNECTION_HISTORY, jsonString).commit()
    }

    /**
     * Gets the connection history list (most recent first)
     */
    fun getConnectionHistory(): List<String> {
        val jsonString = prefs.getString(KEY_CONNECTION_HISTORY, "") ?: ""
        if (jsonString.isEmpty()) return emptyList()

        return jsonString.split(",").filter { it.isNotBlank() }
    }

    /**
     * Clears the connection history
     */
    fun clearConnectionHistory() {
        prefs.edit().remove(KEY_CONNECTION_HISTORY).commit()
    }
}
