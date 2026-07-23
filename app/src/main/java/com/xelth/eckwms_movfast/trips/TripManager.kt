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

    // ── Battery-death gap bridge (owner rule, Eschborn→Speyer 2026-07-23) ────
    // The recorder died mid-drive (battery) and the phone came back somewhere
    // else. If the displacement since the last recorded fix was PHYSICALLY
    // POSSIBLE in the elapsed time, believe it: drop a bridge point at the
    // power-on position and keep the trip open instead of amputating it at the
    // last pre-death fix. Bounds: below MIN km it's the same parking lot (the
    // plain stale close is right); above the gap window it's no longer one
    // continuous journey (the 44e9ee52 phantom-glue protection); above MAX
    // average speed a car can't have done it — refuse and close honestly.
    private const val BRIDGE_MAX_GAP_MS = 6 * 3_600_000L
    private const val BRIDGE_MIN_KM = 2.0
    private const val BRIDGE_MAX_AVG_KMH = 160.0

    // Trip the recording service is ACTIVELY recording right now (in-memory,
    // same process). reconcileOpenTrip never closes this one — a live trip can
    // legitimately go long without points (no-signal stretch, Privatfahrt).
    @Volatile internal var serviceRecordingTripId: String? = null

    // The recorder runs in the `:trips` process, so the static above is invisible
    // to the UI process — the cross-process "service is alive" signal is a tiny
    // heartbeat file the service refreshes every minute (atomic tmp+rename).
    // Fresh heartbeat = never stale-close the trip, route commands via Intents.
    private const val HEARTBEAT_FRESH_MS = 5 * 60_000L

    private fun heartbeatFile(context: Context) =
        java.io.File(context.filesDir, "trip_service_heartbeat")

    internal fun writeServiceHeartbeat(context: Context, tripId: String) {
        try {
            val f = heartbeatFile(context)
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.writeText("$tripId|${System.currentTimeMillis()}")
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (_: Exception) {
            // Heartbeat is advisory — never disturb recording over it.
        }
    }

    internal fun clearServiceHeartbeat(context: Context) {
        try { heartbeatFile(context).delete() } catch (_: Exception) {}
    }

    /** Is the recording service (in whichever process) actively on this trip?
     *  Same process: the static. Cross-process: a heartbeat younger than 5 min. */
    fun isServiceRecording(context: Context, tripId: String): Boolean {
        if (serviceRecordingTripId == tripId) return true
        return try {
            val parts = heartbeatFile(context).readText().split('|')
            parts.size == 2 && parts[0] == tripId &&
                System.currentTimeMillis() - (parts[1].toLongOrNull() ?: 0L) < HEARTBEAT_FRESH_MS
        } catch (_: Exception) {
            false
        }
    }

    private val _activeTrip = MutableLiveData<TripEntity?>(null)
    val activeTrip: LiveData<TripEntity?> = _activeTrip

    fun publishActiveTrip(trip: TripEntity?) {
        _activeTrip.postValue(trip)
    }

    // Main-process bridge: the recorder's publishActiveTrip() happens in the
    // :trips process, so the UI's copy of this LiveData would stay silent. Mirror
    // the Room open-trip row instead — cross-process invalidation re-emits it on
    // every :trips write. Started once from EckwmsApp (main process only).
    @Volatile private var uiBridgeStarted = false

    fun startUiBridge(context: Context) {
        if (uiBridgeStarted) return
        uiBridgeStarted = true
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppDatabase.getInstance(appContext).tripDao().observeOpenTrip()
                    .collect { _activeTrip.postValue(it) }
            } catch (e: Exception) {
                Log.e(TAG, "UI bridge died: ${e.message}", e)
            }
        }
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
     *  rule with the 6 h grace: expired → close AT THE PHOTO with that reading.
     *
     *  Battery-death exception: before amputating a stale trip, check whether the
     *  CURRENT position is a plausible continuation of the drive (see
     *  [bridgeableGap]) — then a bridge point is recorded and the trip stays
     *  open, so the drive that outlived the phone keeps its second half. */
    suspend fun reconcileOpenTrip(context: Context): TripEntity? {
        val db = AppDatabase.getInstance(context)
        val open = db.tripDao().getOpenTrip() ?: return null
        if (isServiceRecording(context, open.id)) return open
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
        if (open.purpose != "private") {
            tryBridgeGap(context, open, lastPointTs)?.let { return it }
        }
        val endedAt = maxOf(open.startedAt, lastPointTs ?: open.startedAt)
        // Stop without any odometer signal: close the reading gap with the
        // least-action track estimate, honestly marked "estimated" — same rule
        // as finalizeAndStop (spec .eck/TRACK_ESTIMATION.md, trigger 3). Until
        // now auto-closed trips lost their end reading entirely (94dd8bac).
        if (open.startOdometerKm != null && open.endOdometerKm == null) {
            smoothedTrack(context, open.id)?.let { s ->
                val estEnd = Math.round((open.startOdometerKm + s.distanceKm) * 10.0) / 10.0
                db.tripDao().setEndOdometer(open.id, estEnd, "estimated", null)
                TripLog.i(TAG, "end odometer estimated for stale trip ${open.id}: $estEnd km")
            }
        }
        db.tripDao().closeStale(
            open.id, endedAt,
            "auto-closed: recording was interrupted; ended at last recorded activity " +
                java.time.Instant.ofEpochMilli(endedAt).toString()
        )
        queueTripSync(context, open.id)
        TripLog.w(TAG, "Closed stale open trip ${open.id} at $endedAt (started ${open.startedAt})")
        return null
    }

    // VisibleForTesting: pure decision — is the displacement between the last
    // recorded activity and a fresh fix a believable continuation of the drive?
    internal fun bridgeableGap(lastActivityTs: Long, fixTs: Long, distKm: Double): Boolean {
        val dtMs = fixTs - lastActivityTs
        if (dtMs <= 0 || dtMs > BRIDGE_MAX_GAP_MS) return false
        if (distKm < BRIDGE_MIN_KM) return false
        return distKm / (dtMs / 3_600_000.0) <= BRIDGE_MAX_AVG_KMH
    }

    /** Try to bridge a stale open trip to the phone's CURRENT position. Returns
     *  the refreshed (still-open) trip when the bridge was recorded, null when
     *  the plain stale close should proceed. Needs a located anchor point, the
     *  location permission and a fix NEWER than the last recorded activity —
     *  anything missing or implausible falls back to the honest amputation. */
    private suspend fun tryBridgeGap(
        context: Context,
        open: TripEntity,
        lastPointTs: Long?
    ): TripEntity? {
        val db = AppDatabase.getInstance(context)
        val anchor = db.tripDao().lastLocatedPoint(open.id) ?: return null
        val anchorLat = anchor.lat ?: return null
        val anchorLng = anchor.lng ?: return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        val fix = try {
            val client = com.google.android.gms.location.LocationServices
                .getFusedLocationProviderClient(context)
            val cts = com.google.android.gms.tasks.CancellationTokenSource()
            try {
                com.google.android.gms.tasks.Tasks.await(
                    client.getCurrentLocation(
                        com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        cts.token
                    ),
                    20, java.util.concurrent.TimeUnit.SECONDS
                )
            } catch (e: Exception) {
                cts.cancel()
                com.google.android.gms.tasks.Tasks.await(client.lastLocation)
            }
        } catch (e: Exception) {
            null
        } ?: return null
        val lastActivity = maxOf(open.startedAt, lastPointTs ?: open.startedAt)
        // A cached fix from BEFORE the gap carries no new information — without
        // fresh evidence of where the phone woke up, amputate as before.
        if (fix.time <= lastActivity) return null
        val distKm = haversineKm(anchorLat, anchorLng, fix.latitude, fix.longitude)
        if (!bridgeableGap(lastActivity, fix.time, distKm)) {
            TripLog.i(
                TAG,
                "gap not bridgeable for trip ${open.id}: ${Math.round(distKm * 10.0) / 10.0} km in " +
                    "${(fix.time - lastActivity) / 60000} min — closing stale"
            )
            return null
        }
        db.tripDao().insertPoint(
            com.xelth.eckwms_movfast.data.local.entity.TripPointEntity(
                tripId = open.id,
                seq = db.tripDao().pointCount(open.id) + 1,
                ts = fix.time,
                source = "fused",
                lat = fix.latitude,
                lng = fix.longitude,
                accuracyM = fix.accuracy.toDouble(),
                kind = "bridge",
                label = "recording interrupted; plausible movement of " +
                    "${Math.round(distKm * 10.0) / 10.0} km bridged at power-on"
            )
        )
        TripLog.w(
            TAG,
            "bridged trip ${open.id}: ${Math.round(distKm * 10.0) / 10.0} km gap over " +
                "${(fix.time - lastActivity) / 60000} min — trip stays open"
        )
        return db.tripDao().getTrip(open.id)
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
        if (isServiceRecording(context, open.id)) {
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

    /** Start a trip from the armed intent. Two callers: the vehicle
     *  auto-detector on IN_VEHICLE ENTER (manual=false), and the yellow armed
     *  Start hex — the driver forcing the start NOW because detection didn't
     *  come (manual=true; from the UI the app is foreground, so the FGS gets
     *  full location rights). Consumes the intent. SYNC prefs read — safe from
     *  a BroadcastReceiver. Returns true when an intent was consumed. */
    fun startTripFromIntent(context: Context, manual: Boolean = false): Boolean {
        val ti = peekTripIntent() ?: return false
        clearTripIntent()
        Log.i(TAG, "consuming trip intent \"${ti.label}\" (${if (manual) "forced start" else "vehicle movement"})")
        startTrip(
            context, manual = manual,
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

    /** Outcome of [declareNextDestination] — drives the console feedback. */
    enum class NextDest { ARMED_NO_TRIP, ARMED_AT_STOP, MERGED_LIVE }

    /** Driver names where the NEXT leg goes (ticket-row tap or spoken while a
     *  trip exists; owner wish 2026-07-20 — "на чекпойнте дай ввести
     *  следующую цель").
     *  - No open trip → classic armed intent (next trip starts with it).
     *  - Open trip with an ARMED TENTATIVE END (odometer photographed at this
     *    stop) → labeled checkpoint `→ <Ziel>` on the open trip + armed intent
     *    presetting THIS stop's odometer reading and the trip's vehicle. The
     *    declaration survives both futures: driving on consumes the intent on
     *    IN_VEHICLE ENTER and the service merges the purpose onto the SAME
     *    trip (multi-stop); a stale-/tentative-end close hands it to the next
     *    trip with the true (pre-drive) declaration moment.
     *  - Open trip still live without a stop reading → merge onto the running
     *    trip NOW (the long-standing voice semantics — waiting for an ENTER
     *    transition that never comes mid-drive would lose it) + checkpoint. */
    suspend fun declareNextDestination(
        context: Context,
        label: String,
        ref: String? = null,
        source: String = "planned"
    ): NextDest {
        val open = AppDatabase.getInstance(context).tripDao().getOpenTrip() ?: run {
            armTripIntent(context, label, ref, source)
            Log.i(TAG, "next destination \"$label\" armed (no open trip)")
            return NextDest.ARMED_NO_TRIP
        }
        if (isServiceRecording(context, open.id)) {
            checkpointNow(context, "→ $label")
        }
        if (open.tentativeEndTs != null) {
            armTripIntent(context, label, ref, source)
            val stopKm = open.tentativeEndOdoKm
            if (stopKm != null) {
                attachIntentOdometer(
                    stopKm,
                    if (open.tentativeEndPhotoId != null) "photo" else "manual",
                    open.tentativeEndPhotoId
                )
            } else {
                estimateCurrentOdometer(context)?.let { attachIntentOdometer(it, "estimated", null) }
            }
            bindTripIntentVehicle(open.vehicleId, open.vehiclePlate)
            Log.i(TAG, "next destination \"$label\" armed at checkpoint stop of trip ${open.id}")
            return NextDest.ARMED_AT_STOP
        }
        startTrip(
            context, manual = true, purpose = "business",
            purposeRef = ref, purposeLabel = label, purposeSource = source
        )
        Log.i(TAG, "next destination \"$label\" merged onto live trip ${open.id}")
        return NextDest.MERGED_LIVE
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

    /** "Allow all the time" location. Without it an auto-detected (background-
     *  started) recording is silently stripped of location by Android 14/15 —
     *  the FGS never truly foregrounds and every fused/cell sample is denied
     *  until the app comes on screen (2026-07-13 field case: 0 points for a
     *  27-min drive). Below Q background access rides on FINE. */
    fun hasBackgroundLocation(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

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

    /** Queue an ended trip for upload and poke the SyncWorker.
     *
     *  In the `:trips` process there is no WorkManager (it stays main-process-
     *  only by design) — upload directly over the same HTTP→relay path the
     *  checkpoints use. The main process's periodic SyncWorker sweep
     *  (getUnsyncedEnded) remains the retry net for anything that fails here. */
    fun queueTripSync(context: Context, tripId: String) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(appContext)
                if (com.xelth.eckwms_movfast.utils.ProcessUtils.isTripsProcess()) {
                    val json = buildUploadJson(appContext, tripId) ?: return@launch
                    val ok = try {
                        com.xelth.eckwms_movfast.api.ScanApiService(appContext).uploadTrip(json)
                    } catch (e: Exception) {
                        false
                    }
                    // Only an ENDED trip may be marked synced — this path also
                    // fires for open-trip checkpoint pushes (closeStale callers).
                    if (ok && db.tripDao().getTrip(tripId)?.status == TripEntity.STATUS_ENDED) {
                        db.tripDao().markSynced(tripId, System.currentTimeMillis())
                    }
                    TripLog.i(TAG, "trip $tripId direct upload from :trips: ok=$ok")
                    return@launch
                }
                db.syncQueueDao().addToQueue(
                    SyncQueueEntity(
                        type = "trip_sync",
                        payload = JSONObject().put("trip_id", tripId).toString()
                    )
                )
                SyncManager.scheduleSync(appContext)
                Log.d(TAG, "Trip $tripId queued for sync")
            } catch (e: Exception) {
                TripLog.e(TAG, "queueTripSync failed: ${e.message}", e)
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
            // In the :trips process UserManager is never logged in — fall back to
            // the persisted current user (prefs snapshot from process start).
            val userId = com.xelth.eckwms_movfast.ui.viewmodels.UserManager.currentUser.value?.id
                ?.takeIf { it.isNotEmpty() }
                ?: com.xelth.eckwms_movfast.utils.SettingsManager.getCurrentUserId()
            if (userId.isNotEmpty()) put("driver_user_id", userId)
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
