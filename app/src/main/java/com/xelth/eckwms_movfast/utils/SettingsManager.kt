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

    fun saveServerUrl(url: String) = prefs.edit().putString(KEY_SERVER_URL, url.trim()).apply()
    fun getServerUrl(): String = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL

    private const val KEY_GLOBAL_SERVER_URL = "global_server_url"
    private const val DEFAULT_GLOBAL_SERVER_URL = "http://your-public-domain.com:8080"

    fun saveGlobalServerUrl(url: String) = prefs.edit().putString(KEY_GLOBAL_SERVER_URL, url.trim()).apply()
    fun getGlobalServerUrl(): String = prefs.getString(KEY_GLOBAL_SERVER_URL, DEFAULT_GLOBAL_SERVER_URL) ?: DEFAULT_GLOBAL_SERVER_URL
}
