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

    // An open trip with no activity for this long is an orphan (process died and
    // the stop was never delivered). Well above the 3 min graceful-stop window +
    // 5 min checkpoint interval, so a live recording can never look stale.
    private const val STALE_OPEN_TRIP_MS = 30 * 60_000L

    // Grace window after an odometer-photo stop signal (tentative end): if no
    // driving follows within it, the trip auto-ends AT THE PHOTO MOMENT with
    // that reading. Long enough for a full customer visit, short enough that
    // the day's Fahrtenbuch closes itself.
    private const val TENTATIVE_END_GRACE_MS = 6 * 3_600_000L

    // A spoken trip declaration stays armed this long; no movement within it →
    // the intent silently expires (console note at the next voice/trip touch).
    private const val TRIP_INTENT_TTL_MS = 2 * 3_600_000L

    // Trip the recording service is ACTIVELY recording right now (in-memory,
    // same process). reconcileOpenTrip never closes this one — a live trip can
    // legitimately go long without points (no-signal stretch, Privatfahrt).
    @Volatile internal var serviceRecordingTripId: String? = null

    private val _activeTrip = MutableLiveData<TripEntity?>(null)
    val activeTrip: LiveData<TripEntity?> = _activeTrip

    fun publishActiveTrip(trip: TripEntity?) {
        _activeTrip.postValue(trip)
    }

    // VisibleForTesting: pure staleness decision. Last activity = newest point,
    // falling back to the start moment for point-less trips (private / just started).
    internal fun openTripIsStale(startedAt: Long, lastPointTs: Long?, now: Long): Boolean {
        val lastActivity = maxOf(startedAt, lastPointTs ?: startedAt)
        return now - lastActivity > STALE_OPEN_TRIP_MS
    }

    /** The valid open trip, or null. An orphaned open trip (see [openTripIsStale])
     *  is closed HERE at its last real activity — not at "now", days later — with
     *  an honest note, and queued for upload. Without this, the next start would
     *  glue a new drive onto a days-old phantom (the 44e9ee52 case). An ARMED
     *  tentative end (odometer photographed at a stop) replaces the 30-min stale
     *  rule with the 6 h grace: expired → close AT THE PHOTO with that reading. */
    suspend fun reconcileOpenTrip(context: Context): TripEntity? {
        val db = AppDatabase.getInstance(context)
        val open = db.tripDao().getOpenTrip() ?: return null
        if (open.id == serviceRecordingTripId) return open
        open.tentativeEndTs?.let { armTs ->
            return if (armedEndExpired(armTs, System.currentTimeMillis())) {
                closeWithArmedEnd(context, open)
                null
            } else {
                open // explicit driver signal — wait out the 6 h grace
            }
        }
        val lastPointTs = db.tripDao().lastPointTs(open.id)
        if (!openTripIsStale(open.startedAt, lastPointTs, System.currentTimeMillis())) return open
        val endedAt = maxOf(open.startedAt, lastPointTs ?: open.startedAt)
        db.tripDao().closeStale(
            open.id, endedAt,
            "auto-closed: recording was interrupted; ended at last recorded activity " +
                java.time.Instant.ofEpochMilli(endedAt).toString()
        )
        queueTripSync(context, open.id)
        Log.w(TAG, "Closed stale open trip ${open.id} at $endedAt (started ${open.startedAt})")
        return null
    }

    // ── Odometer-photo stop signal (tentative end) ───────────────────────────

    internal fun armedEndExpired(armTs: Long, now: Long): Boolean =
        now - armTs > TENTATIVE_END_GRACE_MS

    /** Is a mid-trip odometer reading a believable "I stopped here" signal?
     *  The delta over the start reading must roughly match what the track says
     *  the car has driven — the band is wide (cell tracks are coarse, fused
     *  under-counts) with absolute slack for short trips. Without a start
     *  reading there is nothing to validate against → never arms. */
    internal fun plausibleOdoStop(startKm: Double?, km: Double, trackKm: Double?): Boolean {
        if (startKm == null) return false
        val diff = km - startKm
        if (diff < 0.2) return false            // same reading / backwards — no movement
        if (trackKm != null && trackKm > 0.5) {
            return diff >= 0.3 * trackKm - 2.0 && diff <= 3.0 * trackKm + 10.0
        }
        return diff < 2000.0                     // no usable track — sanity bound only
    }

    /** Driver set the Km field while a trip is recording = odometer photographed
     *  at a stop. Validates the delta against the track, drops a stop-marker
     *  point, arms the tentative end and pushes a checkpoint. Returns true when
     *  armed. Routed through the SERVICE when it is alive (it owns the seq
     *  counter); written directly when it is not. */
    suspend fun odometerCheckpoint(context: Context, km: Double, photoId: String?): Boolean {
        val db = AppDatabase.getInstance(context)
        val open = db.tripDao().getOpenTrip() ?: return false
        // Smoothed (gated) track distance — a mislocated tower must not widen
        // the plausibility band. Chord sum only as the few-points fallback.
        val trackKm = smoothedTrack(context, open.id)?.distanceKm ?: run {
            val pts = db.tripDao().getPoints(open.id).filter { it.lat != null && it.lng != null }
            var d = 0.0
            for (i in 1 until pts.size) {
                d += haversineKm(pts[i - 1].lat!!, pts[i - 1].lng!!, pts[i].lat!!, pts[i].lng!!)
            }
            d.takeIf { pts.size >= 2 }
        }
        if (!plausibleOdoStop(open.startOdometerKm, km, trackKm)) {
            Log.i(TAG, "odometer $km not a plausible stop for trip ${open.id} (start=${open.startOdometerKm}, track=$trackKm)")
            return false
        }
        val now = System.currentTimeMillis()
        if (serviceRecordingTripId == open.id) {
            val intent = Intent(context, TripRecordingService::class.java).apply {
                action = TripRecordingService.ACTION_ODO
                putExtra(TripRecordingService.EXTRA_ODO_KM, km)
                if (!photoId.isNullOrBlank()) putExtra(TripRecordingService.EXTRA_PHOTO_ID, photoId)
            }
            try { context.startService(intent) } catch (e: Exception) {
                Log.w(TAG, "odometerCheckpoint: service poke failed (${e.message})")
            }
        } else {
            db.tripDao().insertPoint(
                com.xelth.eckwms_movfast.data.local.entity.TripPointEntity(
                    tripId = open.id,
                    seq = db.tripDao().pointCount(open.id) + 1,
                    ts = now,
                    source = "manual",
                    kind = "manual",
                    label = "odometer",
                    odometerKm = km,
                    photoId = photoId
                )
            )
            db.tripDao().armTentativeEnd(open.id, now, km, photoId)
            queueTripSync(context, open.id)
        }
        Log.i(TAG, "tentative end armed for trip ${open.id}: $km km at $now")
        return true
    }

    /** The OCR photo upload finishes after the value lands — attach the CAS id
     *  to a still-armed tentative end (evidence for the auto-closed trip). */
    suspend fun attachArmedOdoPhoto(context: Context, photoId: String) {
        val db = AppDatabase.getInstance(context)
        val open = db.tripDao().getOpenTrip() ?: return
        db.tripDao().setTentativeEndPhoto(open.id, photoId)
    }

    // ── Voice trip intent (spoken declaration, consumed by movement) ─────────
    // "я поехал в Карлсруэ" from the MAIN menu arms this; the next IN_VEHICLE
    // transition starts the trip with the armed fields. declared_at = the VOICE
    // moment (declared BEFORE the drive — the strongest anti-fabrication anchor).
    // Spec: .eck/TRIP_PURPOSE.md §10.

    data class TripIntent(
        val label: String,
        val ref: String?,
        val declaredAt: Long,
        val expiresAt: Long,
        val vehicleId: String?,
        val plate: String?,
        val odoKm: Double?,
        val odoSource: String?,   // "estimated" (last known) | "photo" | "manual"
        val odoPhotoId: String?,
        // How the intent was declared — becomes the trip's purpose_source
        // (GoBD honesty): "voice" | "text" (typed purpose) | "manual" (Start hex).
        val source: String = "voice"
    )

    private fun intentToJson(i: TripIntent): String = org.json.JSONObject().apply {
        put("label", i.label)
        i.ref?.let { put("ref", it) }
        put("declared_at", i.declaredAt)
        put("expires_at", i.expiresAt)
        i.vehicleId?.let { put("vehicle_id", it) }
        i.plate?.let { put("plate", it) }
        i.odoKm?.let { put("odo_km", it) }
        i.odoSource?.let { put("odo_source", it) }
        i.odoPhotoId?.let { put("odo_photo", it) }
        put("source", i.source)
    }.toString()

    private fun intentFromJson(s: String): TripIntent? = try {
        val o = org.json.JSONObject(s)
        TripIntent(
            label = o.getString("label"),
            ref = o.optString("ref").takeIf { it.isNotEmpty() },
            declaredAt = o.getLong("declared_at"),
            expiresAt = o.getLong("expires_at"),
            vehicleId = o.optString("vehicle_id").takeIf { it.isNotEmpty() },
            plate = o.optString("plate").takeIf { it.isNotEmpty() },
            odoKm = if (o.has("odo_km")) o.getDouble("odo_km") else null,
            odoSource = o.optString("odo_source").takeIf { it.isNotEmpty() },
            odoPhotoId = o.optString("odo_photo").takeIf { it.isNotEmpty() },
            source = o.optString("source").ifEmpty { "voice" }
        )
    } catch (e: Exception) { null }

    /** Arm a trip declaration (spoken, or the Start hex — both mean "I'm about
     *  to drive"; the next IN_VEHICLE transition starts the trip, no movement
     *  within the 2 h TTL → silently forgotten). Presets the last known vehicle
     *  and its end odometer (source "estimated" — a guess, never a reading).
     *  Overwrites any previous pending intent (the newest declaration wins). */
    suspend fun armTripIntent(
        context: Context,
        label: String,
        ref: String? = null,
        source: String = "voice"
    ): TripIntent {
        val last = AppDatabase.getInstance(context).tripDao().lastEndedTrip()
        val now = System.currentTimeMillis()
        val intent = TripIntent(
            label = label,
            ref = ref,
            declaredAt = now,
            expiresAt = now + TRIP_INTENT_TTL_MS,
            vehicleId = last?.vehicleId,
            plate = last?.vehiclePlate,
            odoKm = last?.endOdometerKm,
            odoSource = last?.endOdometerKm?.let { "estimated" },
            odoPhotoId = null,
            source = source
        )
        com.xelth.eckwms_movfast.utils.SettingsManager.saveTripIntentJson(intentToJson(intent))
        Log.i(TAG, "trip intent armed: \"$label\" (ref=${ref ?: "-"}, source=$source, odo≈${intent.odoKm ?: "-"})")
        return intent
    }

    /** The pending intent, or null. Expired intents are cleared on read (SYNC —
     *  callable from a BroadcastReceiver). */
    fun peekTripIntent(): TripIntent? {
        val json = com.xelth.eckwms_movfast.utils.SettingsManager.getTripIntentJson() ?: return null
        val intent = intentFromJson(json) ?: run { clearTripIntent(); return null }
        if (System.currentTimeMillis() > intent.expiresAt) {
            clearTripIntent()
            Log.i(TAG, "trip intent \"${intent.label}\" expired unconsumed")
            return null
        }
        return intent
    }

    fun clearTripIntent() {
        com.xelth.eckwms_movfast.utils.SettingsManager.saveTripIntentJson(null)
    }

    /** Upgrade the armed intent's client binding (voice-named client resolved
     *  against CRM, or the confirmed inferred candidate). */
    fun bindTripIntentClient(ref: String?, label: String?) {
        val cur = peekTripIntent() ?: return
        com.xelth.eckwms_movfast.utils.SettingsManager.saveTripIntentJson(
            intentToJson(cur.copy(ref = ref ?: cur.ref, label = label ?: cur.label))
        )
    }

    /** Override the armed intent's vehicle with an explicitly set one (plate
     *  entered/OCR'd on the field hex) — beats the last-trip preset. */
    fun bindTripIntentVehicle(vehicleId: String?, plate: String?) {
        if (vehicleId == null && plate == null) return
        val cur = peekTripIntent() ?: return
        com.xelth.eckwms_movfast.utils.SettingsManager.saveTripIntentJson(
            intentToJson(cur.copy(vehicleId = vehicleId ?: cur.vehicleId, plate = plate ?: cur.plate))
        )
        Log.i(TAG, "trip intent vehicle set: ${plate ?: vehicleId}")
    }

    /** An odometer reading captured while the intent is armed (photo shortly
     *  before/after the voice command) replaces the estimate — and is a REAL
     *  reading (source photo/manual), not a guess. */
    fun attachIntentOdometer(km: Double, source: String, photoId: String?) {
        val cur = peekTripIntent() ?: return
        com.xelth.eckwms_movfast.utils.SettingsManager.saveTripIntentJson(
            intentToJson(cur.copy(odoKm = km, odoSource = source, odoPhotoId = photoId ?: cur.odoPhotoId))
        )
        Log.i(TAG, "trip intent odometer set: $km km ($source)")
    }

    /** The OCR photo upload finishes after the value lands — attach the CAS id
     *  to an armed intent's reading and upgrade its source to "photo". */
    fun attachIntentOdoPhoto(photoId: String) {
        val cur = peekTripIntent() ?: return
        if (cur.odoKm == null) return
        com.xelth.eckwms_movfast.utils.SettingsManager.saveTripIntentJson(
            intentToJson(cur.copy(odoPhotoId = photoId, odoSource = "photo"))
        )
    }

    /** Close a trip on its armed tentative end: ended AT THE PHOTO MOMENT with
     *  that odometer reading (source "scanned") and an honest note. */
    suspend fun closeWithArmedEnd(context: Context, trip: TripEntity) {
        val ts = trip.tentativeEndTs ?: return
        val db = AppDatabase.getInstance(context)
        trip.tentativeEndOdoKm?.let {
            db.tripDao().setEndOdometer(trip.id, it, "scanned", trip.tentativeEndPhotoId)
        }
        db.tripDao().closeStale(
            trip.id, ts,
            "auto-ended: odometer photographed at stop " +
                java.time.Instant.ofEpochMilli(ts).toString() + "; no further driving within 6 h"
        )
        queueTripSync(context, trip.id)
        Log.i(TAG, "Trip ${trip.id} auto-ended on armed odometer stop (${trip.tentativeEndOdoKm} km)")
    }

    /** Start recording (idempotent — reuses an already-open trip).
     *  No-op without DSGVO consent. `purpose = "private"` → Privatfahrt:
     *  the service records NO positions, only the trip frame for km-delta.
     *  Start odometer / plate (hex-field OCR pre-sets) ride IN the intent and
     *  are applied by the service atomically with the trip row — writing them
     *  from the UI after the fact raced the service's async insert and lost. */
    fun startTrip(
        context: Context,
        manual: Boolean,
        purpose: String = "business",
        purposeRef: String? = null,
        purposeLabel: String? = null,
        purposeSource: String? = null,
        purposeDeclaredAt: Long? = null,
        startOdometerKm: Double? = null,
        startOdometerSource: String? = null,
        startOdometerPhotoId: String? = null,
        plate: String? = null,
        platePhotoId: String? = null
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
            // Voice intent: the purpose was declared BEFORE the drive started —
            // carry the true declaration moment into the trip (earliest wins).
            purposeDeclaredAt?.let { putExtra(TripRecordingService.EXTRA_PURPOSE_DECLARED_AT, it) }
            startOdometerKm?.let { putExtra(TripRecordingService.EXTRA_START_ODO_KM, it) }
            putExtra(TripRecordingService.EXTRA_START_ODO_SOURCE, startOdometerSource)
            putExtra(TripRecordingService.EXTRA_START_ODO_PHOTO, startOdometerPhotoId)
            putExtra(TripRecordingService.EXTRA_PLATE, plate)
            putExtra(TripRecordingService.EXTRA_PLATE_PHOTO, platePhotoId)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /** Start a trip from the armed voice intent (called by the vehicle
     *  auto-detector on IN_VEHICLE ENTER). Consumes the intent. SYNC prefs
     *  read — safe from a BroadcastReceiver. Returns true when an intent
     *  was consumed. */
    fun startTripFromIntent(context: Context): Boolean {
        val ti = peekTripIntent() ?: return false
        clearTripIntent()
        Log.i(TAG, "consuming trip intent \"${ti.label}\" on vehicle movement")
        startTrip(
            context, manual = false,
            purpose = "business",
            purposeRef = ti.ref,
            purposeLabel = ti.label.takeIf { it.isNotBlank() },
            purposeSource = ti.source,
            purposeDeclaredAt = ti.declaredAt,
            startOdometerKm = ti.odoKm,
            startOdometerSource = ti.odoSource,
            startOdometerPhotoId = ti.odoPhotoId,
            plate = ti.plate
        )
        return true
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

    /** Log a trip expense on the OPEN trip: a point of kind `fuel`/`parking`/
     *  `toll`/`receipt` with the odometer (estimated or scanned) + receipt photo
     *  id. The server promotes these kinds into the durable fuel_events array
     *  (tax documents — Tankbeleg, Parkschein, Maut). A receipt photo alone is
     *  a valid expense; the type is an optional annotation. */
    fun logExpense(
        context: Context,
        type: String,
        odometerKm: Double?,
        source: String,
        photoId: String?
    ) {
        val intent = Intent(context, TripRecordingService::class.java).apply {
            action = TripRecordingService.ACTION_FUEL
            putExtra(TripRecordingService.EXTRA_EXPENSE_TYPE, type)
            odometerKm?.let { putExtra(TripRecordingService.EXTRA_ODO_KM, it) }
            putExtra(TripRecordingService.EXTRA_ODO_SOURCE, source)
            if (!photoId.isNullOrBlank()) putExtra(TripRecordingService.EXTRA_PHOTO_ID, photoId)
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "logExpense: service not running (${e.message})")
        }
    }

    // ── Least-action track estimation (see .eck/TRACK_ESTIMATION.md) ─────────
    // One smoothing pass serves every km consumer: live odometer estimate,
    // Expense prefill, odometer-stop plausibility, finalize's estimated end
    // reading and the upload's smoothed polyline. Recomputed only when the
    // point count changed (the "streaming" tier is an on-demand full pass —
    // ~10 ms per trip, cheaper than keeping filter state alive).
    @Volatile private var estimateCache: Triple<String, Int, TrackEstimator.Result>? = null

    /** Located observations for the estimator: fused/gps fixes as-is, cell
     *  points through the on-device tower cache (unknown cells contribute
     *  nothing — the dynamics coast through them). σ is honest per source. */
    private suspend fun locatedObservations(
        db: AppDatabase,
        tripId: String
    ): List<TrackEstimator.Obs> {
        val cellDao = db.cellTowerDao()
        return db.tripDao().getPoints(tripId).mapNotNull { p ->
            if (p.source == "cell") {
                val key = "${p.mcc}-${p.mnc}-${p.tac}-${p.cid}"
                if (p.cid == null || p.mcc == null) return@mapNotNull null
                val tower = cellDao.get(key) ?: return@mapNotNull null
                TrackEstimator.Obs(p.ts, tower.lat, tower.lng, maxOf(tower.rangeM, 700.0))
            } else {
                val lat = p.lat ?: return@mapNotNull null
                val lng = p.lng ?: return@mapNotNull null
                TrackEstimator.Obs(p.ts, lat, lng, maxOf(p.accuracyM ?: 100.0, 10.0))
            }
        }
    }

    /** Smoothed track for a trip (cached by point count). Null when there are
     *  too few located points to say anything. */
    suspend fun smoothedTrack(context: Context, tripId: String): TrackEstimator.Result? {
        val db = AppDatabase.getInstance(context)
        val count = db.tripDao().pointCount(tripId)
        estimateCache?.let { (id, n, res) -> if (id == tripId && n == count) return res }
        val res = TrackEstimator.smooth(locatedObservations(db, tripId)) ?: return null
        estimateCache = Triple(tripId, count, res)
        return res
    }

    /** Estimate the current odometer = trip start reading + smoothed track
     *  distance (least-action estimate, mislocated towers gated out). The
     *  driver can always override with an exact odometer OCR. Null if no open
     *  trip or no start reading to add onto. */
    suspend fun estimateCurrentOdometer(context: Context): Double? {
        val db = AppDatabase.getInstance(context)
        val trip = db.tripDao().getOpenTrip() ?: return null
        val start = trip.startOdometerKm ?: return null
        val dist = smoothedTrack(context, trip.id)?.distanceKm ?: run {
            // Too few located points for the smoother — chord sum is the
            // honest fallback (usually the first minutes of a trip).
            val pts = db.tripDao().getPoints(trip.id).filter { it.lat != null && it.lng != null }
            var d = 0.0
            for (i in 1 until pts.size) {
                d += haversineKm(pts[i - 1].lat!!, pts[i - 1].lng!!, pts[i].lat!!, pts[i].lng!!)
            }
            d
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
        // Located observations for the smoothed derived layer (collected in the
        // same pass so cell resolution happens exactly once, same cache rules).
        val smoothObs = ArrayList<TrackEstimator.Obs>(points.size)
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
                        smoothObs.add(TrackEstimator.Obs(p.ts, tower.lat, tower.lng, maxOf(tower.rangeM, 700.0)))
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
                    if (p.source != "cell" && p.lat != null && p.lng != null) {
                        smoothObs.add(TrackEstimator.Obs(p.ts, p.lat, p.lng, maxOf(p.accuracyM ?: 100.0, 10.0)))
                    }
                }
                pointsJson.put(obj)
            }
        }

        // Derived layer: least-action smoothed polyline + km (raw points above
        // stay untouched — they are the GoBD evidence; this is recomputable).
        val smoothed = if (trip.purpose != "private") TrackEstimator.smooth(smoothObs) else null
        // Road snap only on the FINAL upload (needs corridor tiles → network);
        // checkpoints of an open trip skip it. Failure = layer omitted.
        val matched = if (trip.endedAt != null && smoothed != null) {
            try {
                RoadTileProvider.matchTrack(context, smoothed.path)
            } catch (e: Exception) {
                Log.w(TAG, "road match failed: ${e.message}")
                null
            }
        } else null

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
            smoothed?.let { s ->
                put("smoothed_km", Math.round(s.distanceKm * 10.0) / 10.0)
                put("estimation_version", s.version)
                val path = JSONArray()
                TrackEstimator.decimate(s.path).forEach { p ->
                    // GeoJSON order [lng, lat]; 5 decimals ≈ 1 m — plenty.
                    path.put(JSONArray().put(Math.round(p.lng * 1e5) / 1e5).put(Math.round(p.lat * 1e5) / 1e5))
                }
                put("smoothed_path", path)
            }
            matched?.let { m ->
                put("matched_km", Math.round(m.distanceKm * 10.0) / 10.0)
                put("matched_share", Math.round(m.matchedShare * 100.0) / 100.0)
                put("matcher_version", m.version)
                val path = JSONArray()
                TrackEstimator.decimate(m.path).forEach { p ->
                    path.put(JSONArray().put(Math.round(p.lng * 1e5) / 1e5).put(Math.round(p.lat * 1e5) / 1e5))
                }
                put("matched_path", path)
            }
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
