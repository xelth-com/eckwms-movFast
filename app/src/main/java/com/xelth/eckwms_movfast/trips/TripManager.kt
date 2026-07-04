package com.xelth.eckwms_movfast.trips

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.xelth.eckwms_movfast.data.local.AppDatabase
import com.xelth.eckwms_movfast.data.local.entity.SyncQueueEntity
import com.xelth.eckwms_movfast.data.local.entity.TripEntity
import com.xelth.eckwms_movfast.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Entry point for trip recording (Fahrtenbuch).
 *
 * Manual flow: the 🚗 grid button / TripsScreen toggles recording.
 * Auto flow: Activity Recognition IN_VEHICLE transitions start/stop the
 * recording service (opt-in, needs ACTIVITY_RECOGNITION permission).
 */
object TripManager {
    private const val TAG = "TripManager"
    private const val TRANSITION_REQUEST_CODE = 7301

    private val _activeTrip = MutableLiveData<TripEntity?>(null)
    val activeTrip: LiveData<TripEntity?> = _activeTrip

    fun publishActiveTrip(trip: TripEntity?) {
        _activeTrip.postValue(trip)
    }

    /** Start recording (idempotent — reuses an already-open trip).
     *  No-op without DSGVO consent. `purpose = "private"` → Privatfahrt:
     *  the service records NO positions, only the trip frame for km-delta. */
    fun startTrip(
        context: Context,
        manual: Boolean,
        purpose: String = "business",
        purposeRef: String? = null,
        purposeLabel: String? = null,
        purposeSource: String? = null
    ) {
        if (!com.xelth.eckwms_movfast.utils.SettingsManager.getTripConsent()) {
            Log.w(TAG, "startTrip blocked: no recording consent")
            return
        }
        val intent = Intent(context, TripRecordingService::class.java).apply {
            action = TripRecordingService.ACTION_START
            putExtra(TripRecordingService.EXTRA_MANUAL, manual)
            putExtra(TripRecordingService.EXTRA_PURPOSE, purpose)
            putExtra(TripRecordingService.EXTRA_PURPOSE_REF, purposeRef)
            putExtra(TripRecordingService.EXTRA_PURPOSE_LABEL, purposeLabel)
            putExtra(TripRecordingService.EXTRA_PURPOSE_SOURCE, purposeSource)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /** Stop recording. Graceful: waits a few minutes before finalizing
     *  (red-traffic-light / fuel-stop debounce for the auto-detector). */
    fun stopTrip(context: Context, graceful: Boolean) {
        val intent = Intent(context, TripRecordingService::class.java).apply {
            action = if (graceful) TripRecordingService.ACTION_STOP_GRACEFUL
                     else TripRecordingService.ACTION_STOP
        }
        // Service may already be dead — startService for command delivery
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "stopTrip: service not running (${e.message})")
        }
    }

    /** Drop a checkpoint (a stop within a multi-stop trip) on the OPEN trip: the
     *  service records the current position as a `manual` point and uploads it now.
     *  No-op if no trip is recording (the service logs it). */
    fun checkpointNow(context: Context, label: String? = null) {
        val intent = Intent(context, TripRecordingService::class.java).apply {
            action = TripRecordingService.ACTION_CHECKPOINT
            if (!label.isNullOrBlank()) putExtra(TripRecordingService.EXTRA_LABEL, label)
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "checkpointNow: service not running (${e.message})")
        }
    }

    /** Log a refuel on the OPEN trip: a `fuel` point with the odometer (estimated
     *  or scanned) + receipt photo id. The server promotes it to a durable
     *  fuel_events row (Tankbeleg). */
    fun logFuel(context: Context, odometerKm: Double?, source: String, photoId: String?) {
        val intent = Intent(context, TripRecordingService::class.java).apply {
            action = TripRecordingService.ACTION_FUEL
            odometerKm?.let { putExtra(TripRecordingService.EXTRA_ODO_KM, it) }
            putExtra(TripRecordingService.EXTRA_ODO_SOURCE, source)
            if (!photoId.isNullOrBlank()) putExtra(TripRecordingService.EXTRA_PHOTO_ID, photoId)
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "logFuel: service not running (${e.message})")
        }
    }

    /** Estimate the current odometer = trip start reading + summed track distance
     *  (haversine over resolved points). Rough by design (noisy cell/fused fixes);
     *  the driver can override with an exact odometer OCR at the fuel stop. Null if
     *  no open trip or no start reading to add onto. */
    suspend fun estimateCurrentOdometer(context: Context): Double? {
        val db = AppDatabase.getInstance(context)
        val trip = db.tripDao().getOpenTrip() ?: return null
        val start = trip.startOdometerKm ?: return null
        val pts = db.tripDao().getPoints(trip.id).filter { it.lat != null && it.lng != null }
        var dist = 0.0
        for (i in 1 until pts.size) {
            dist += haversineKm(pts[i - 1].lat!!, pts[i - 1].lng!!, pts[i].lat!!, pts[i].lng!!)
        }
        return start + dist
    }

    // VisibleForTesting: widened from private to internal so unit tests can verify
    // the great-circle math directly. Pure function, no Android/state dependencies.
    internal fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    // ── Auto-detection (Activity Recognition Transition API) ────────────────

    fun hasActivityPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    private fun transitionPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, VehicleTransitionReceiver::class.java).apply {
            action = VehicleTransitionReceiver.ACTION_TRANSITION
        }
        return PendingIntent.getBroadcast(
            context, TRANSITION_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    fun enableAutoDetect(context: Context): Boolean {
        if (!hasActivityPermission(context)) {
            Log.w(TAG, "enableAutoDetect: ACTIVITY_RECOGNITION not granted")
            return false
        }
        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build()
        )
        return try {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(
                    ActivityTransitionRequest(transitions),
                    transitionPendingIntent(context)
                )
                .addOnSuccessListener { Log.i(TAG, "Auto trip detection enabled") }
                .addOnFailureListener { Log.e(TAG, "Auto detection failed: ${it.message}") }
            com.xelth.eckwms_movfast.utils.SettingsManager.saveTripAutoDetect(true)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "enableAutoDetect: ${e.message}")
            false
        }
    }

    fun disableAutoDetect(context: Context) {
        try {
            ActivityRecognition.getClient(context)
                .removeActivityTransitionUpdates(transitionPendingIntent(context))
        } catch (e: Exception) {
            Log.w(TAG, "disableAutoDetect: ${e.message}")
        }
        com.xelth.eckwms_movfast.utils.SettingsManager.saveTripAutoDetect(false)
    }

    // ── Sync payload ─────────────────────────────────────────────────────────

    /** Queue an ended trip for upload and poke the SyncWorker. */
    fun queueTripSync(context: Context, tripId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                db.syncQueueDao().addToQueue(
                    SyncQueueEntity(
                        type = "trip_sync",
                        payload = JSONObject().put("trip_id", tripId).toString()
                    )
                )
                SyncManager.scheduleSync(context)
                Log.d(TAG, "Trip $tripId queued for sync")
            } catch (e: Exception) {
                Log.e(TAG, "queueTripSync failed: ${e.message}", e)
            }
        }
    }

    /** Assemble the server upload JSON for a trip (matches POST /api/trips). */
    suspend fun buildUploadJson(context: Context, tripId: String): String? {
        val db = AppDatabase.getInstance(context)
        val trip = db.tripDao().getTrip(tripId) ?: return null
        val points = db.tripDao().getPoints(tripId)

        fun iso(ms: Long): String =
            java.time.Instant.ofEpochMilli(ms).toString()

        // Privatfahrt: coordinates never leave the device — km delta only
        val pointsJson = JSONArray()
        if (trip.purpose != "private") {
            val cellDao = db.cellTowerDao()
            points.forEach { p ->
                val obj = JSONObject().apply {
                    put("seq", p.seq)
                    put("ts", iso(p.ts))
                    put("source", p.source)
                    // Trip-event fields — only emitted for non-plain points so the
                    // payload stays lean (server defaults kind to "auto").
                    if (p.kind != "auto") put("kind", p.kind)
                    p.label?.let { put("label", it) }
                    p.odometerKm?.let { put("odometer_km", it) }
                    p.photoId?.let { put("photo_id", it) }
                }
                // On-device resolution: if this cell is in the local tower cache,
                // attach coordinates HERE and DROP the raw cell identity — the
                // cell's position never leaves the phone. Only cells unknown to
                // the cache go up raw for server-side OpenCelliD resolution.
                var resolved = false
                if (p.source == "cell" && p.cid != null && p.mcc != null) {
                    val key = "${p.mcc}-${p.mnc}-${p.tac}-${p.cid}"
                    val tower = cellDao.get(key)
                    if (tower != null) {
                        obj.put("lat", tower.lat)
                        obj.put("lng", tower.lng)
                        obj.put("accuracy_m", tower.rangeM)
                        obj.put("resolved_by", "device_cache")
                        resolved = true
                    }
                }
                if (!resolved) {
                    p.lat?.let { obj.put("lat", it) }
                    p.lng?.let { obj.put("lng", it) }
                    p.accuracyM?.let { obj.put("accuracy_m", it) }
                    p.mcc?.let { obj.put("mcc", it) }
                    p.mnc?.let { obj.put("mnc", it) }
                    p.tac?.let { obj.put("tac", it) }
                    p.cid?.let { obj.put("cid", it) }
                    p.signalDbm?.let { obj.put("signal_dbm", it) }
                }
                pointsJson.put(obj)
            }
        }

        return JSONObject().apply {
            put("trip_uuid", trip.id)
            put("device_id", com.xelth.eckwms_movfast.utils.SettingsManager.getDeviceId(context))
            put("started_at", iso(trip.startedAt))
            trip.endedAt?.let { put("ended_at", iso(it)) }
            trip.startOdometerKm?.let { put("start_odometer_km", it) }
            trip.startOdometerSource?.let { put("start_odometer_source", it) }
            trip.startOdometerPhotoId?.let { put("start_odometer_photo", it) }
            trip.endOdometerKm?.let { put("end_odometer_km", it) }
            trip.endOdometerSource?.let { put("end_odometer_source", it) }
            trip.endOdometerPhotoId?.let { put("end_odometer_photo", it) }
            put("purpose", trip.purpose)
            trip.purposeRef?.let { put("purpose_ref", it) }
            trip.purposeLabel?.let { put("purpose_label", it) }
            trip.purposeDeclaredAt?.let { put("purpose_declared_at", iso(it)) }
            trip.purposeSource?.let { put("purpose_source", it) }
            trip.vehicleId?.let { put("vehicle_id", it) }
            trip.vehiclePlate?.let { put("vehicle_plate", it) }
            trip.note?.let { put("note", it) }
            val userId = com.xelth.eckwms_movfast.ui.viewmodels.UserManager.currentUser.value?.id
            if (!userId.isNullOrEmpty()) put("driver_user_id", userId)
            put("points", pointsJson)
        }.toString()
    }

    /** Generate the id for a new trip. */
    fun newTripId(): String = UUID.randomUUID().toString()

    // ── Battery / background-restriction guards ──────────────────────────────
    // The OS kills the recording FGS mid-trip ("Stopping service due to app
    // idle" / "background restricted") unless the app is exempt from battery
    // optimization AND not background-restricted. These let the UI detect and
    // fix that before a trip silently dies.

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isBackgroundRestricted(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
            am.isBackgroundRestricted else false
    }

    /** PAID flavor only — direct "ignore battery optimization for THIS app?"
     *  dialog (one tap). Needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which is
     *  Play-restricted, so never call this in the free flavor. */
    @android.annotation.SuppressLint("BatteryLife")
    fun batteryExemptionIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        }

    /** Play-safe (free flavor) — opens the general battery-optimization list;
     *  the user finds the app and switches it to "not optimized" manually.
     *  No restricted permission required. */
    fun batteryListSettingsIntent(): Intent =
        Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** Intent to the app's detail settings (to lift "background restricted"). */
    fun appSettingsIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        }
}
