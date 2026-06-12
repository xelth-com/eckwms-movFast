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
    fun startTrip(context: Context, manual: Boolean, purpose: String = "business") {
        if (!com.xelth.eckwms_movfast.utils.SettingsManager.getTripConsent()) {
            Log.w(TAG, "startTrip blocked: no recording consent")
            return
        }
        val intent = Intent(context, TripRecordingService::class.java).apply {
            action = TripRecordingService.ACTION_START
            putExtra(TripRecordingService.EXTRA_MANUAL, manual)
            putExtra(TripRecordingService.EXTRA_PURPOSE, purpose)
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
            points.forEach { p ->
                pointsJson.put(JSONObject().apply {
                    put("seq", p.seq)
                    put("ts", iso(p.ts))
                    put("source", p.source)
                    p.lat?.let { put("lat", it) }
                    p.lng?.let { put("lng", it) }
                    p.accuracyM?.let { put("accuracy_m", it) }
                    p.mcc?.let { put("mcc", it) }
                    p.mnc?.let { put("mnc", it) }
                    p.tac?.let { put("tac", it) }
                    p.cid?.let { put("cid", it) }
                    p.signalDbm?.let { put("signal_dbm", it) }
                })
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
            trip.note?.let { put("note", it) }
            val userId = com.xelth.eckwms_movfast.ui.viewmodels.UserManager.currentUser.value?.id
            if (!userId.isNullOrEmpty()) put("driver_user_id", userId)
            put("points", pointsJson)
        }.toString()
    }

    /** Generate the id for a new trip. */
    fun newTripId(): String = UUID.randomUUID().toString()
}
