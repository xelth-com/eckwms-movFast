package com.xelth.eckwms_movfast.utils

import android.content.Context
import android.content.SharedPreferences
import com.xelth.eckwms_movfast.BuildConfig
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
    private const val DEFAULT_SERVER_URL = ""  // Empty until paired via relay
    private lateinit var prefs: SharedPreferences

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateServerUrls()
    }

    /**
     * One-time migration: ensure saved server URLs include the /E microservice prefix.
     * Previously URLs like "https://pda.repair" were saved without the path prefix,
     * causing API calls to hit the frontend instead of the Go backend.
     */
    private fun migrateServerUrls() {
        val migrated = prefs.getBoolean("url_e_prefix_migrated_v2", false)
        if (migrated) return

        // Remove hardcoded pda.repair defaults from legacy installs
        val serverUrl = prefs.getString(KEY_SERVER_URL, null)
        if (serverUrl != null && serverUrl.contains("pda.repair")) {
            prefs.edit().remove(KEY_SERVER_URL).apply()
        }
        val globalUrl = prefs.getString(KEY_GLOBAL_SERVER_URL, null)
        if (globalUrl != null && globalUrl.contains("pda.repair")) {
            prefs.edit().remove(KEY_GLOBAL_SERVER_URL).apply()
        }
        // Clean connection history of hardcoded URLs
        val history = prefs.getString("connection_history", "") ?: ""
        if (history.contains("pda.repair")) {
            val cleaned = history.split(",").filter { !it.contains("pda.repair") }.joinToString(",")
            prefs.edit().putString("connection_history", cleaned).apply()
        }
        prefs.edit().putBoolean("url_e_prefix_migrated_v2", true).apply()
    }

    fun saveImageResolution(resolution: Int) = prefs.edit().putInt(KEY_RESOLUTION, resolution).apply()
    fun getImageResolution(): Int = prefs.getInt(KEY_RESOLUTION, 1920)

    fun saveImageQuality(quality: Int) = prefs.edit().putInt(KEY_QUALITY, quality).apply()
    fun getImageQuality(): Int = prefs.getInt(KEY_QUALITY, 75)

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
    private const val DEFAULT_GLOBAL_SERVER_URL = ""  // Empty until paired via relay

    // Critical: Use commit() for immediate disk persistence
    fun saveGlobalServerUrl(url: String) = prefs.edit().putString(KEY_GLOBAL_SERVER_URL, url.trim()).commit()
    fun getGlobalServerUrl(): String = prefs.getString(KEY_GLOBAL_SERVER_URL, DEFAULT_GLOBAL_SERVER_URL) ?: DEFAULT_GLOBAL_SERVER_URL

    // ── Paid mesh: the eckN service nodes are baked-in DEFAULTS (kept OUT of the
    // pairing QR to keep it short). The device orders them by mod3(mesh_id) so no
    // single node is everyone's default, and fails over across them. ──
    //
    // These are the DIRECT relay ports (plain HTTP on :320N), NOT the :443 web-UI.
    // `https://eckN.com` (:443) is the WMS SPA — it 301→index.html, which is why
    // dispatching there returned `<!doctype html>`. The Axum relay (mesh queue
    // /E/m/*, /E/register, /E/health) is bound directly to :320N with no proxy/SPA.
    // Cleartext is fine: the app already sets usesCleartextTraffic=true (LAN HTTP).
    val ECK_DEFAULT_NODES = listOf("http://eck1.com:3201", "http://eck2.com:3202", "http://eck3.com:3203")

    /** eckN defaults rotated so this mesh's mod3 primary comes first. Mirrors the
     *  server: sort by host (ignoring scheme/port), then start at
     *  `compute_primary_index` = u32 BE of sha256(mesh)[..4] % n and rotate. */
    fun orderedEckNodes(meshId: String): List<String> {
        val n = ECK_DEFAULT_NODES.size
        if (n == 0) return emptyList()
        val ordered = ECK_DEFAULT_NODES.sortedBy { url ->
            url.replace(Regex("(?i)^https?://"), "").substringBefore("/").substringBefore(":").lowercase()
        }
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(meshId.toByteArray(Charsets.UTF_8))
        val v = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
                ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        val primary = (v % n).toInt()
        return (0 until n).map { ordered[(primary + it) % n] }
    }

    // Full ordered failover list (active first, then siblings) for the mesh.
    private const val KEY_SERVER_URL_LIST = "server_url_list"
    fun saveServerUrlList(urls: List<String>) =
        prefs.edit().putString(KEY_SERVER_URL_LIST, urls.joinToString(",")).commit()
    fun getServerUrlList(): List<String> =
        (prefs.getString(KEY_SERVER_URL_LIST, "") ?: "").split(",").filter { it.isNotBlank() }

    private const val KEY_SERVER_PUBLIC_KEY = "server_public_key_hex"

    fun saveServerPublicKey(keyHex: String) = prefs.edit().putString(KEY_SERVER_PUBLIC_KEY, keyHex.trim()).commit()
    fun getServerPublicKey(): String? = prefs.getString(KEY_SERVER_PUBLIC_KEY, null)

    // ── Xelixir embedded support agent ─────────────────────────────────────
    // The agent dials the xelixir C2 server directly. Default targets the
    // direct rustls listener on :3221 (nginx :443 301-redirects /X/ws and raw
    // WS clients don't follow the redirect). agent_id reuses getInstanceId().
    private const val KEY_XELIXIR_WS_URL = "xelixir_ws_url"
    private const val DEFAULT_XELIXIR_WS_URL = "wss://xelth.com:3221/X/ws"
    private const val KEY_XELIXIR_TOKEN = "xelixir_ws_token"

    fun saveXelixirWsUrl(url: String) = prefs.edit().putString(KEY_XELIXIR_WS_URL, url.trim()).commit()
    fun getXelixirWsUrl(): String =
        prefs.getString(KEY_XELIXIR_WS_URL, DEFAULT_XELIXIR_WS_URL) ?: DEFAULT_XELIXIR_WS_URL

    // WS auth token the agent connects with. Either a master WS_AUTH_TOKEN dropped
    // in for bring-up, or the access_token cached from a successful license claim
    // (see XelixirTokenProvider). Empty until set/claimed.
    fun saveXelixirToken(token: String) = prefs.edit().putString(KEY_XELIXIR_TOKEN, token.trim()).commit()
    fun getXelixirToken(): String = prefs.getString(KEY_XELIXIR_TOKEN, "") ?: ""

    // Provisioned license token (the gating secret) — POSTed to /api/licensing/claim
    // to obtain the WS access_token, exactly like dno2 devices / the kiosk. Issued by
    // an admin per customer batch. Empty until provisioned.
    private const val KEY_XELIXIR_LICENSE = "xelixir_license_token"
    fun saveXelixirLicenseToken(token: String) = prefs.edit().putString(KEY_XELIXIR_LICENSE, token.trim()).commit()
    // Prefer an explicitly provisioned override; otherwise fall back to the token
    // baked into the APK at build time (BuildConfig.XELIXIR_LICENSE_TOKEN, from the
    // gitignored local.properties). Empty only on unprovisioned dev builds.
    fun getXelixirLicenseToken(): String {
        val override = prefs.getString(KEY_XELIXIR_LICENSE, "") ?: ""
        return if (override.isNotEmpty()) override else BuildConfig.XELIXIR_LICENSE_TOKEN
    }

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

    // ── Trip recording (Fahrtenbuch) ────────────────────────────────────────
    // Owner decision 2026-06-17: trips are ON by default (opt-out) — the user
    // turns them off with the switch if they don't want recording. (Note: for
    // employee deployments this opt-out default is legally sensitive under DSGVO
    // /§26 BDSG/Betriebsrat — see PRIVACY_BY_DESIGN.md; fine for the owner's own
    // use and the standalone free app where the user records their own trips.)
    private const val KEY_TRIP_AUTO_DETECT = "trip_auto_detect"
    fun saveTripAutoDetect(enabled: Boolean) { prefs.edit().putBoolean(KEY_TRIP_AUTO_DETECT, enabled).commit() }
    fun getTripAutoDetect(): Boolean = prefs.getBoolean(KEY_TRIP_AUTO_DETECT, true)

    // DSGVO consent (Einwilligung): revocable at any time via the switch. ON by
    // default per the owner decision above; no recording happens while false.
    private const val KEY_TRIP_CONSENT = "trip_recording_consent"
    fun saveTripConsent(granted: Boolean) { prefs.edit().putBoolean(KEY_TRIP_CONSENT, granted).commit() }
    fun getTripConsent(): Boolean = prefs.getBoolean(KEY_TRIP_CONSENT, true)

    // Separate, ADDITIONAL opt-in: share the live position of a business trip to
    // the dashboard map in near-real-time (a moving car marker with the plate).
    // Distinct from recording consent — recording can be on while live sharing is
    // off (then the vehicle only ever appears at its parked end point). Default
    // OFF; private trips are never shared regardless.
    private const val KEY_TRIP_LIVE_SHARE = "trip_live_share"
    fun saveTripLiveShare(enabled: Boolean) { prefs.edit().putBoolean(KEY_TRIP_LIVE_SHARE, enabled).commit() }
    fun getTripLiveShare(): Boolean = prefs.getBoolean(KEY_TRIP_LIVE_SHARE, false)

    // Last trip-console map camera (lat,lng,zoom). Restored on re-entry so the map
    // resumes where it was instead of flashing the whole globe + re-framing. Pure
    // UI state — no location is persisted beyond this last camera centre.
    private const val KEY_TRIP_MAP_CAM = "trip_map_camera"
    fun saveTripMapCamera(lat: Double, lng: Double, zoom: Double) {
        prefs.edit().putString(KEY_TRIP_MAP_CAM, "$lat,$lng,$zoom").apply()
    }
    fun getTripMapCamera(): Triple<Double, Double, Double>? {
        val parts = (prefs.getString(KEY_TRIP_MAP_CAM, null) ?: return null).split(",")
        if (parts.size != 3) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lng = parts[1].toDoubleOrNull() ?: return null
        val zoom = parts[2].toDoubleOrNull() ?: return null
        return Triple(lat, lng, zoom)
    }

    // Dynamic repair order prefix (fetched from server /api/status)
    private const val KEY_REPAIR_ORDER_PREFIX = "repair_order_prefix"
    private const val DEFAULT_REPAIR_ORDER_PREFIX = "REP-"
    fun getRepairOrderPrefix(): String = prefs.getString(KEY_REPAIR_ORDER_PREFIX, DEFAULT_REPAIR_ORDER_PREFIX) ?: DEFAULT_REPAIR_ORDER_PREFIX
    fun saveRepairOrderPrefix(prefix: String) = prefs.edit().putString(KEY_REPAIR_ORDER_PREFIX, prefix.trim()).commit()

    // Dynamic pairing-code prefix (fetched from server /api/status, like the
    // repair order prefix). NOTE: pairing QRs are detected BEFORE any server is
    // paired (chicken-and-egg), so "ECK" must remain the bootstrap fallback. A
    // self-hosted instance can push its own prefix via `pairing_prefix`, which
    // then applies to re-pairing and additional devices. A blank stored value is
    // coerced back to the default so `startsWith("")` can never match everything.
    private const val KEY_PAIRING_PREFIX = "pairing_prefix"
    private const val DEFAULT_PAIRING_PREFIX = "ECK"
    fun getPairingPrefix(): String =
        (prefs.getString(KEY_PAIRING_PREFIX, DEFAULT_PAIRING_PREFIX) ?: DEFAULT_PAIRING_PREFIX)
            .ifBlank { DEFAULT_PAIRING_PREFIX }
    fun savePairingPrefix(prefix: String) {
        val clean = prefix.trim()
        if (clean.isEmpty()) return  // never store an empty prefix
        prefs.edit().putString(KEY_PAIRING_PREFIX, clean).commit()
    }

    // Dynamic QR prefixes + tenant suffix (fetched from server /api/status)
    // Hardcoded fallbacks: 9eck.com/ and xelth.com/ (relay domains, always valid)
    private const val KEY_QR_PREFIXES = "qr_prefixes"
    private const val KEY_QR_TENANT_SUFFIX = "qr_tenant_suffix"
    private const val DEFAULT_QR_TENANT_SUFFIX = "IB"
    private val HARDCODED_FALLBACK_PREFIXES = listOf("9eck.com/", "xelth.com/")

    fun saveQrPrefixes(prefixes: List<String>) {
        val joined = prefixes.joinToString(",") { it.trim() }
        prefs.edit().putString(KEY_QR_PREFIXES, joined).commit()
    }

    /** Returns merged list: server-configured prefixes + hardcoded fallbacks (deduplicated). */
    fun getQrPrefixes(): List<String> {
        val saved = prefs.getString(KEY_QR_PREFIXES, null)
        val serverPrefixes = if (!saved.isNullOrEmpty()) {
            saved.split(",").filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        return (serverPrefixes + HARDCODED_FALLBACK_PREFIXES).distinct()
    }

    fun saveQrTenantSuffix(suffix: String) = prefs.edit().putString(KEY_QR_TENANT_SUFFIX, suffix.trim()).commit()
    fun getQrTenantSuffix(): String = prefs.getString(KEY_QR_TENANT_SUFFIX, DEFAULT_QR_TENANT_SUFFIX) ?: DEFAULT_QR_TENANT_SUFFIX

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

    // Raw Settings.Secure.ANDROID_ID — the bootstrap pairing handle. Weak/unstable
    // identity (changes on factory reset, differs by signing key on Android 8+), so
    // it is NOT the canonical id; it's only what the device presents on first
    // pairing until the server mints it a UUID.
    fun getAndroidId(context: Context): String {
        return android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    // Canonical server-minted device UUID, anchored server-side to this device's
    // Ed25519 public key (so it survives a factory reset / ANDROID_ID change).
    // Empty until the server returns one via register-device or /api/status.
    private const val KEY_DEVICE_UUID = "device_uuid"
    fun getDeviceUuid(): String = prefs.getString(KEY_DEVICE_UUID, "") ?: ""
    fun saveDeviceUuid(uuid: String) {
        val clean = uuid.trim()
        if (clean.isEmpty()) return
        prefs.edit().putString(KEY_DEVICE_UUID, clean).commit()
    }

    // Canonical device id for all server attribution (uploads, scans, trips, JWT
    // subject): the server-minted UUID once known, else the raw ANDROID_ID for the
    // bootstrap handshake.
    fun getDeviceId(context: Context): String {
        val uuid = getDeviceUuid()
        return if (uuid.isNotEmpty()) uuid else getAndroidId(context)
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
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, out)
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

    // --- UUID-Based Photo Storage (CAS-like, immutable) ---

    private fun photosDir(): File {
        val dir = File(appContext.filesDir, "photos")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Save original bitmap to photos/orig_{uuid}.webp, return file path */
    fun savePhotoOriginal(uuid: String, bitmap: Bitmap): String {
        val file = File(photosDir(), "orig_$uuid.webp")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, out)
        }
        Log.d("SettingsManager", "Saved photo orig_$uuid (${file.length() / 1024}KB)")
        return file.absolutePath
    }

    /** Save smart-crop avatar to photos/avatar_{uuid}.webp, return file path */
    fun savePhotoAvatar(uuid: String, bitmap: Bitmap): String {
        val file = File(photosDir(), "avatar_$uuid.webp")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, out)
        }
        Log.d("SettingsManager", "Saved avatar avatar_$uuid (${file.length() / 1024}KB)")
        return file.absolutePath
    }

    /** Load original photo by UUID */
    fun loadPhotoOriginal(uuid: String): Bitmap? {
        val file = File(photosDir(), "orig_$uuid.webp")
        if (!file.exists()) return null
        return try { BitmapFactory.decodeFile(file.absolutePath) } catch (e: Exception) { null }
    }

    /** Load avatar by UUID */
    fun loadPhotoAvatar(uuid: String): Bitmap? {
        val file = File(photosDir(), "avatar_$uuid.webp")
        if (!file.exists()) return null
        return try { BitmapFactory.decodeFile(file.absolutePath) } catch (e: Exception) { null }
    }

    /** Delete both original and avatar for a UUID */
    fun deletePhoto(uuid: String) {
        File(photosDir(), "orig_$uuid.webp").delete()
        File(photosDir(), "avatar_$uuid.webp").delete()
    }

    /** Get original file path (for upload) without loading bitmap */
    fun getPhotoOriginalPath(uuid: String): String? {
        val file = File(photosDir(), "orig_$uuid.webp")
        return if (file.exists()) file.absolutePath else null
    }

    /** Get avatar file path (for upload) without loading bitmap */
    fun getPhotoAvatarPath(uuid: String): String? {
        val file = File(photosDir(), "avatar_$uuid.webp")
        return if (file.exists()) file.absolutePath else null
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
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, out)
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

    // --- Multi-User Persistence ---

    private const val KEY_CURRENT_USER_ID = "current_user_id"
    private const val KEY_CURRENT_USER_NAME = "current_user_name"

    fun saveCurrentUser(id: String, name: String) {
        prefs.edit()
            .putString(KEY_CURRENT_USER_ID, id)
            .putString(KEY_CURRENT_USER_NAME, name)
            .apply()
    }

    fun getCurrentUserId(): String = prefs.getString(KEY_CURRENT_USER_ID, "") ?: ""
    fun getCurrentUserName(): String = prefs.getString(KEY_CURRENT_USER_NAME, "") ?: ""

    // --- Mesh Networking ---

    private const val KEY_INSTANCE_ID = "instance_id"
    private const val KEY_MESH_ID = "mesh_id"
    private const val KEY_SYNC_NETWORK_KEY = "sync_network_key"
    private const val KEY_RELAY_URL = "relay_url"
    private const val DEFAULT_RELAY_URL = "https://9eck.com"

    fun saveInstanceId(id: String) = prefs.edit().putString(KEY_INSTANCE_ID, id.trim()).commit()
    fun getInstanceId(): String {
        val saved = prefs.getString(KEY_INSTANCE_ID, null)
        if (saved != null) return saved
        // Auto-generate from device ID
        val generated = "pda_${getDeviceId(appContext)}"
        saveInstanceId(generated)
        return generated
    }

    fun saveMeshId(meshId: String) = prefs.edit().putString(KEY_MESH_ID, meshId.trim()).commit()
    fun getMeshId(): String? = prefs.getString(KEY_MESH_ID, null)

    fun saveSyncNetworkKey(key: String) {
        prefs.edit().putString(KEY_SYNC_NETWORK_KEY, key.trim()).commit()
        // Recompute mesh_id when key changes
        saveMeshId(computeMeshId(key.trim()))
    }
    fun getSyncNetworkKey(): String? = prefs.getString(KEY_SYNC_NETWORK_KEY, null)

    fun saveRelayUrl(url: String) = prefs.edit().putString(KEY_RELAY_URL, url.trim()).commit()
    fun getRelayUrl(): String = prefs.getString(KEY_RELAY_URL, DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL

    /**
     * Compute mesh_id from SYNC_NETWORK_KEY.
     * mesh_id = sha256(key)[:8 bytes] = 16 hex characters.
     * Must match the Rust server's compute_mesh_id().
     */
    fun computeMeshId(syncNetworkKey: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val hash = md.digest(syncNetworkKey.toByteArray(Charsets.UTF_8))
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }
}
