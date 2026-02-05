package com.xelth.eckwms_movfast.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object SettingsManager {
    private const val PREFS_NAME = "eckwms_settings"
    private const val KEY_RESOLUTION = "image_resolution"
    private const val KEY_QUALITY = "image_quality"
    private const val KEY_SERVER_URL = "server_url"
    private const val DEFAULT_SERVER_URL = "https://pda.repair"
    private lateinit var prefs: SharedPreferences

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveImageResolution(resolution: Int) = prefs.edit().putInt(KEY_RESOLUTION, resolution).apply()
    fun getImageResolution(): Int = prefs.getInt(KEY_RESOLUTION, 1920)

    fun saveImageQuality(quality: Int) = prefs.edit().putInt(KEY_QUALITY, quality).apply()
    fun getImageQuality(): Int = prefs.getInt(KEY_QUALITY, 80)

    // Critical: Use commit() for immediate disk persistence
    // Ensure URL doesn't end with slash to allow clean concatenation
    fun saveServerUrl(url: String): Boolean {
        var cleanUrl = url.trim()
        if (cleanUrl.endsWith("/")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.length - 1)
        }
        return prefs.edit().putString(KEY_SERVER_URL, cleanUrl).commit()
    }

    fun getServerUrl(): String {
        val url = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        // Defensive: strip trailing slash if present
        return if (url.endsWith("/")) url.substring(0, url.length - 1) else url
    }

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
    private const val KEY_PREFERRED_LOCAL_URL = "preferred_local_url"

    // Critical: Use commit() for immediate disk persistence
    fun saveLastWorkingLocalUrl(url: String) = prefs.edit().putString(KEY_LAST_WORKING_LOCAL_URL, url.trim()).commit()
    fun getLastWorkingLocalUrl(): String? = prefs.getString(KEY_LAST_WORKING_LOCAL_URL, null)

    // Critical: Use commit() for immediate disk persistence
    fun saveLastWorkingGlobalUrl(url: String) = prefs.edit().putString(KEY_LAST_WORKING_GLOBAL_URL, url.trim()).commit()
    fun getLastWorkingGlobalUrl(): String? = prefs.getString(KEY_LAST_WORKING_GLOBAL_URL, null)

    fun savePreferredLocalUrl(url: String) = prefs.edit().putString(KEY_PREFERRED_LOCAL_URL, url.trim()).commit()
    fun getPreferredLocalUrl(): String? = prefs.getString(KEY_PREFERRED_LOCAL_URL, null)

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

    // Warehouse info (from server status endpoint, for client detection)
    private const val KEY_WAREHOUSE_NAME = "warehouse_name"
    private const val KEY_WAREHOUSE_ADDRESS = "warehouse_address"
    fun saveWarehouseInfo(name: String, address: String) = prefs.edit()
        .putString(KEY_WAREHOUSE_NAME, name.trim())
        .putString(KEY_WAREHOUSE_ADDRESS, address.trim())
        .apply()
    fun getWarehouseName(): String = prefs.getString(KEY_WAREHOUSE_NAME, "") ?: ""
    fun getWarehouseAddress(): String = prefs.getString(KEY_WAREHOUSE_ADDRESS, "") ?: ""

    // Encryption Key for ECK Smart QR Codes (AES-192 = 24 bytes = 48 hex chars)
    // In production, this key should come from server during registration or BuildConfig
    private const val KEY_ENC_KEY = "enc_key"
    // DEV key from .env - MUST match server's ENC_KEY
    private const val DEFAULT_ENC_KEY = "d34dac7ad4264dd83dde2b70f4b1b5c065d03723aa0debd2"

    fun getEncKey(): String = prefs.getString(KEY_ENC_KEY, DEFAULT_ENC_KEY) ?: DEFAULT_ENC_KEY
    fun saveEncKey(key: String) = prefs.edit().putString(KEY_ENC_KEY, key.trim()).commit()

    // Authentication token for API requests
    private const val KEY_AUTH_TOKEN = "auth_token"
    fun saveAuthToken(token: String) = prefs.edit().putString(KEY_AUTH_TOKEN, token.trim()).commit()
    fun getAuthToken(): String = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
    fun clearAuthToken() = prefs.edit().remove(KEY_AUTH_TOKEN).commit()

    // Home Instance ID for Smart Routing (extracted from pairing QR code)
    private const val KEY_HOME_INSTANCE_ID = "home_instance_id"
    fun saveHomeInstanceId(id: String) = prefs.edit().putString(KEY_HOME_INSTANCE_ID, id.trim()).commit()
    fun getHomeInstanceId(): String = prefs.getString(KEY_HOME_INSTANCE_ID, "") ?: ""

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

    // --- Repair Mode Slot Persistence ---
    private const val KEY_REPAIR_SLOTS = "repair_slots"

    /**
     * Save repair slots as "index:barcode,index:barcode,..."
     * Only saves bound slots (with barcodes).
     */
    fun saveRepairSlots(slots: List<Pair<Int, String>>) {
        val encoded = slots.joinToString(";") { "${it.first}:${it.second}" }
        prefs.edit().putString(KEY_REPAIR_SLOTS, encoded).commit()
    }

    /**
     * Load saved repair slots. Returns list of (index, barcode) pairs.
     */
    fun loadRepairSlots(): List<Pair<Int, String>> {
        val raw = prefs.getString(KEY_REPAIR_SLOTS, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split(":", limit = 2)
            if (parts.size == 2) {
                val index = parts[0].toIntOrNull()
                val barcode = parts[1]
                if (index != null && barcode.isNotEmpty()) Pair(index, barcode) else null
            } else null
        }
    }

    fun clearRepairSlots() {
        prefs.edit().remove(KEY_REPAIR_SLOTS).commit()
    }

    // --- Device Check Slot Persistence ---
    private const val KEY_DEVICE_CHECK_SLOTS = "device_check_slots"

    fun saveDeviceCheckSlots(slots: List<Pair<Int, String>>) {
        val encoded = slots.joinToString(";") { "${it.first}:${it.second}" }
        prefs.edit().putString(KEY_DEVICE_CHECK_SLOTS, encoded).commit()
    }

    fun loadDeviceCheckSlots(): List<Pair<Int, String>> {
        val raw = prefs.getString(KEY_DEVICE_CHECK_SLOTS, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split(":", limit = 2)
            if (parts.size == 2) {
                val index = parts[0].toIntOrNull()
                val barcode = parts[1]
                if (index != null && barcode.isNotEmpty()) Pair(index, barcode) else null
            } else null
        }
    }

    // --- Repair Slot Photo Persistence ---

    private fun repairPhotoDir(): File {
        val dir = File(appContext.filesDir, "repair_photos")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveRepairPhoto(slotIndex: Int, bitmap: Bitmap) {
        try {
            val file = File(repairPhotoDir(), "slot_$slotIndex.webp")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
            }
            Log.d("SettingsManager", "Saved photo slot #$slotIndex (${file.length() / 1024}KB)")
        } catch (e: Exception) {
            Log.e("SettingsManager", "Failed to save photo slot #$slotIndex", e)
        }
    }

    fun loadRepairPhoto(slotIndex: Int): Bitmap? {
        val file = File(repairPhotoDir(), "slot_$slotIndex.webp")
        // Migrate old JPEG files
        if (!file.exists()) {
            val oldJpeg = File(repairPhotoDir(), "slot_$slotIndex.jpg")
            if (oldJpeg.exists()) oldJpeg.renameTo(file)
        }
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.e("SettingsManager", "Failed to load photo slot #$slotIndex", e)
            null
        }
    }

    fun deleteRepairPhoto(slotIndex: Int) {
        File(repairPhotoDir(), "slot_$slotIndex.webp").delete()
        File(repairPhotoDir(), "slot_$slotIndex.jpg").delete() // cleanup old format
    }

    // --- Item Photos (global, by internal ID) ---
    // Filename format: {internalId}__{timestamp}_{checksum}.webp
    // Internal ID prefix defines type: i=item, b=box, p=place, l=label
    // Example: i0000000000000000001__1738764521000_A7F3.webp
    // Checksum: CRC16 of "{internalId}__{timestamp}" for validation

    private fun itemPhotoDir(): File {
        val dir = File(appContext.filesDir, "item_photos")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Sanitize ID to safe filename (alphanumeric only) */
    private fun sanitizeForFilename(input: String): String {
        return input.replace(Regex("[^a-zA-Z0-9]"), "").take(64)
    }

    /** CRC16-CCITT checksum for filename validation */
    private fun crc16(data: String): String {
        var crc = 0xFFFF
        for (byte in data.toByteArray(Charsets.UTF_8)) {
            crc = crc xor (byte.toInt() and 0xFF)
            for (i in 0 until 8) {
                crc = if (crc and 1 != 0) (crc shr 1) xor 0xA001 else crc shr 1
            }
        }
        return String.format("%04X", crc and 0xFFFF)
    }

    /** Build structured filename: {id}__{timestamp}_{crc}.webp */
    private fun buildPhotoFilename(internalId: String): String {
        val sanitized = sanitizeForFilename(internalId)
        val timestamp = System.currentTimeMillis()
        val base = "${sanitized}__$timestamp"
        val checksum = crc16(base)
        return "${base}_$checksum"
    }

    /** Parse and validate filename, returns (internalId, timestamp) or null if invalid */
    fun parsePhotoFilename(filename: String): Pair<String, Long>? {
        val name = filename.removeSuffix(".webp")
        // Format: {id}__{timestamp}_{crc}
        val doubleSplit = name.split("__")
        if (doubleSplit.size != 2) return null

        val internalId = doubleSplit[0]
        val suffixParts = doubleSplit[1].split("_")
        if (suffixParts.size != 2) return null

        val timestamp = suffixParts[0].toLongOrNull() ?: return null
        val checksum = suffixParts[1]

        // Validate checksum
        val base = "${internalId}__$timestamp"
        if (crc16(base) != checksum) {
            Log.w("SettingsManager", "Invalid checksum for $filename")
            return null
        }

        return Pair(internalId, timestamp)
    }

    /** Get type from internal ID prefix */
    fun getTypeFromInternalId(internalId: String): String {
        return when {
            internalId.startsWith("i", ignoreCase = true) -> "item"
            internalId.startsWith("b", ignoreCase = true) -> "box"
            internalId.startsWith("p", ignoreCase = true) -> "place"
            internalId.startsWith("l", ignoreCase = true) -> "label"
            else -> "unknown"
        }
    }

    /** Save photo with structured filename. Returns filename for reference. */
    fun saveItemPhoto(internalId: String, bitmap: Bitmap): String? {
        return try {
            val filename = buildPhotoFilename(internalId)
            val file = File(itemPhotoDir(), "$filename.webp")

            // Delete old photos for this ID (keep only latest)
            deleteOldPhotosForId(internalId)

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
            }
            Log.d("SettingsManager", "Saved photo: $filename.webp (${file.length() / 1024}KB)")
            "$filename.webp"
        } catch (e: Exception) {
            Log.e("SettingsManager", "Failed to save photo: $internalId", e)
            null
        }
    }

    /** Delete older photos for the same internal ID (keep storage clean) */
    private fun deleteOldPhotosForId(internalId: String) {
        val sanitized = sanitizeForFilename(internalId)
        val prefix = "${sanitized}__"
        itemPhotoDir().listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { file ->
            file.delete()
            Log.d("SettingsManager", "Deleted old photo: ${file.name}")
        }
    }

    /** Load latest photo for internal ID (validates checksum) */
    fun loadItemPhoto(internalId: String): Bitmap? {
        val sanitized = sanitizeForFilename(internalId)
        val prefix = "${sanitized}__"

        // Find most recent valid file
        val file = itemPhotoDir().listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".webp") }
            ?.filter { parsePhotoFilename(it.name) != null }  // validate checksum
            ?.maxByOrNull { it.lastModified() }

        if (file == null) return null

        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.e("SettingsManager", "Failed to load photo: ${file.name}", e)
            null
        }
    }

    /** List all photos with metadata (for recovery/cleanup UI) */
    fun listAllItemPhotos(): List<Pair<String, Long>> {
        return itemPhotoDir().listFiles()
            ?.mapNotNull { parsePhotoFilename(it.name) }
            ?: emptyList()
    }
}
