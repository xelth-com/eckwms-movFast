package com.xelth.eckwms_movfast.trips

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.xelth.eckwms_movfast.MainActivity
import com.xelth.eckwms_movfast.R
import com.xelth.eckwms_movfast.data.local.AppDatabase
import com.xelth.eckwms_movfast.data.local.entity.TripEntity
import com.xelth.eckwms_movfast.data.local.entity.TripPointEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that records a trip track without GPS.
 *
 * Every [SAMPLE_INTERVAL_MS] it snapshots the registered cell towers
 * (MCC/MNC/TAC/CID — geocoded server-side via OpenCelliD) and, when Play
 * Services is available, low-power fused locations (cell/WiFi positioning,
 * GPS chip stays off). The persistent notification doubles as the legally
 * required transparency indicator for movement tracking.
 */
class TripRecordingService : Service() {
    companion object {
        const val ACTION_START = "trip_start"
        const val ACTION_STOP = "trip_stop"
        const val ACTION_STOP_GRACEFUL = "trip_stop_graceful"
        // Driver-dropped waypoint on the OPEN trip (a stop within a multi-stop trip)
        // — records the current position as a `manual` point and forces an immediate
        // checkpoint upload, so one trip can hold several stops without stop/restart.
        const val ACTION_CHECKPOINT = "trip_checkpoint"
        // Refuel event on the OPEN trip: records a `fuel` point (odometer + receipt
        // photo id) — a Tankbeleg the server promotes to a durable fuel_events row.
        const val ACTION_FUEL = "trip_fuel"
        const val EXTRA_MANUAL = "manual"
        const val EXTRA_LABEL = "label"
        const val EXTRA_ODO_KM = "odo_km"
        const val EXTRA_ODO_SOURCE = "odo_source"
        const val EXTRA_PHOTO_ID = "photo_id"
        const val EXTRA_PURPOSE = "purpose"
        const val EXTRA_PURPOSE_REF = "purpose_ref"
        const val EXTRA_PURPOSE_LABEL = "purpose_label"
        const val EXTRA_PURPOSE_SOURCE = "purpose_source"

        private const val TAG = "TripRecordingService"
        private const val CHANNEL_ID = "trip_recording"
        private const val NOTIFICATION_ID = 7302
        private const val SAMPLE_INTERVAL_MS = 30_000L
        private const val GRACEFUL_STOP_DELAY_MS = 3 * 60_000L
        // Periodic mid-trip upload so an in-progress trip is visible in the WMS
        // within a few minutes even off-LAN (delivered via the relay mesh-queue
        // when the LAN master is unreachable — see ScanApiService.uploadTrip).
        private const val CHECKPOINT_INTERVAL_MS = 5 * 60_000L
        // First checkpoint fires SOON after start so even a short-lived (OOM-prone)
        // process delivers a position/track before it may be killed; then the steady
        // interval above. Matters on memory-starved PDAs where the FGS gets culled
        // every few minutes during a drive (Android Auto etc. exhausting swap).
        private const val CHECKPOINT_FIRST_MS = 75_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var samplingJob: Job? = null
    private var checkpointJob: Job? = null
    private var gracefulStopJob: Job? = null
    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    private var tripId: String? = null
    // Most recent fused fix, so a driver-dropped checkpoint has a position even
    // between the 30 s fused updates.
    @Volatile private var lastLoc: android.location.Location? = null
    private val seq = AtomicInteger(0)
    private val apiService by lazy { com.xelth.eckwms_movfast.api.ScanApiService(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // DSGVO: consent is checked at every entry point — also here,
                // because the auto-detect receiver can outlive a revocation
                if (!com.xelth.eckwms_movfast.utils.SettingsManager.getTripConsent()) {
                    Log.w(TAG, "START ignored: recording consent not granted")
                    stopSelf()
                    return START_NOT_STICKY
                }
                // A new START cancels any pending graceful stop (auto-detector
                // re-entered the vehicle at a traffic light / fuel stop)
                gracefulStopJob?.cancel()
                gracefulStopJob = null
                if (tripId == null) {
                    startRecording(
                        intent.getBooleanExtra(EXTRA_MANUAL, false),
                        intent.getStringExtra(EXTRA_PURPOSE) ?: "business",
                        intent.getStringExtra(EXTRA_PURPOSE_REF),
                        intent.getStringExtra(EXTRA_PURPOSE_LABEL),
                        intent.getStringExtra(EXTRA_PURPOSE_SOURCE)
                    )
                } else if (intent.getBooleanExtra(EXTRA_MANUAL, false)) {
                    // Already recording (auto-detect won the race): the manual
                    // start carries the driver's DECLARED purpose — merge it onto
                    // the open trip instead of silently dropping it (purpose is
                    // editable until trip end; earliest declared_at is preserved).
                    val id = tripId ?: return START_STICKY
                    val purpose = intent.getStringExtra(EXTRA_PURPOSE) ?: "business"
                    val ref = intent.getStringExtra(EXTRA_PURPOSE_REF)
                    val label = intent.getStringExtra(EXTRA_PURPOSE_LABEL)
                    val source = intent.getStringExtra(EXTRA_PURPOSE_SOURCE)
                    scope.launch {
                        applyDeclaredPurpose(id, purpose, ref, label, source)
                            ?.let { TripManager.publishActiveTrip(it) }
                    }
                }
            }
            ACTION_STOP_GRACEFUL -> scheduleGracefulStop()
            ACTION_STOP -> finalizeAndStop()
            ACTION_CHECKPOINT -> recordManualCheckpoint(intent.getStringExtra(EXTRA_LABEL))
            ACTION_FUEL -> recordFuelEvent(
                intent.getDoubleExtra(EXTRA_ODO_KM, Double.NaN).let { if (it.isNaN()) null else it },
                intent.getStringExtra(EXTRA_ODO_SOURCE),
                intent.getStringExtra(EXTRA_PHOTO_ID)
            )
            else -> {
                // Sticky restart after an OOM kill delivers a NULL intent (no
                // action). A *started* FGS MUST call startForeground within ~5 s
                // or the OS crashes it (the "process is bad" loop seen on the
                // memory-starved Ranger2), and without this the open trip's
                // recording + checkpoints would NOT resume until the next
                // IN_VEHICLE transition. So resume the open trip right here.
                if (tripId == null) resumeAfterRestart()
            }
        }
        return START_STICKY
    }

    /** Resume the open trip after a process/OOM kill (sticky restart, null intent).
     *  Calls startForeground immediately (FGS 5 s contract), then re-arms sampling +
     *  checkpoints for the open trip, or stops cleanly if there is nothing to resume. */
    private fun resumeAfterRestart() {
        if (!com.xelth.eckwms_movfast.utils.SettingsManager.getTripConsent()) {
            stopSelf(); return
        }
        // Prompt FGS immediately with a neutral (business) notification; the async
        // block refines it for a private trip, or stops if there's no open trip.
        startForegroundWithNotification(false)
        scope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            // Stale-guard: a sticky restart normally follows the kill within
            // moments; an open trip whose last activity is >30 min old is an
            // orphan — close it at that activity instead of resurrecting it.
            val trip = TripManager.reconcileOpenTrip(applicationContext)
            if (trip == null) {
                Log.i(TAG, "Sticky restart with no open trip — stopping")
                stopSelf()
                return@launch
            }
            if (trip.purpose == "private") startForegroundWithNotification(true)
            tripId = trip.id
            TripManager.serviceRecordingTripId = trip.id
            seq.set(db.tripDao().pointCount(trip.id))
            TripManager.publishActiveTrip(trip)
            Log.i(TAG, "Resumed open trip ${trip.id} after sticky restart (post-kill)")
            if (trip.purpose != "private") {
                startCellSampling()
                startFusedUpdates()
                startCheckpoints(trip.id)
            }
        }
    }

    /** Merge a driver-declared purpose onto the open trip. Refuses to flip a
     *  RECORDING business trip to private mid-flight (positions were already
     *  sampled — stopping first is the honest path); private stays private. */
    private suspend fun applyDeclaredPurpose(
        id: String,
        purpose: String,
        ref: String?,
        label: String?,
        source: String?
    ): TripEntity? {
        val db = AppDatabase.getInstance(applicationContext)
        val open = db.tripDao().getTrip(id) ?: return null
        if (purpose == "private" && open.purpose != "private") {
            Log.w(TAG, "declared purpose ignored: can't switch recording trip $id to private mid-trip")
            return open
        }
        val isPrivate = purpose == "private"
        db.tripDao().updatePurpose(
            id, purpose,
            if (isPrivate) null else ref,
            if (isPrivate) null else label,
            if (isPrivate) null else source,
            System.currentTimeMillis()
        )
        Log.i(TAG, "declared purpose merged onto open trip $id (purpose=$purpose, label=${label ?: "-"})")
        return db.tripDao().getTrip(id)
    }

    private fun startRecording(
        manual: Boolean,
        purpose: String,
        purposeRef: String? = null,
        purposeLabel: String? = null,
        purposeSource: String? = null
    ) {
        val isPrivate = purpose == "private"
        startForegroundWithNotification(isPrivate)

        scope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            // Reuse an open trip after process death — but only a LIVE one; a
            // stale orphan is closed at its last activity by the reconciler so
            // today's drive never glues onto a days-old phantom.
            val existing = TripManager.reconcileOpenTrip(applicationContext)
            val now = System.currentTimeMillis()
            var trip = existing ?: TripEntity(
                id = TripManager.newTripId(),
                startedAt = now,
                manualStart = manual,
                purpose = purpose,
                // private trips carry no destination; declared_at = start moment
                purposeRef = if (isPrivate) null else purposeRef,
                purposeLabel = if (isPrivate) null else purposeLabel,
                purposeDeclaredAt = if (isPrivate) null else now,
                purposeSource = if (isPrivate) null else purposeSource
            ).also { db.tripDao().upsertTrip(it) }

            if (existing != null && manual) {
                // Resumed a live trip AND the driver declared a purpose now —
                // merge it (earliest declared_at wins inside updatePurpose).
                applyDeclaredPurpose(trip.id, purpose, purposeRef, purposeLabel, purposeSource)
                    ?.let { trip = it }
            }

            tripId = trip.id
            TripManager.serviceRecordingTripId = trip.id
            seq.set(db.tripDao().pointCount(trip.id))
            TripManager.publishActiveTrip(trip)
            Log.i(TAG, "Recording trip ${trip.id} (resumed=${existing != null}, manual=$manual, purpose=${trip.purpose})")

            // Privatfahrt: NO positions are sampled at all — the trip exists
            // only as a time frame for the odometer delta (1%-Regelung)
            if (trip.purpose != "private") {
                startCellSampling()
                startFusedUpdates()
                startCheckpoints(trip.id)
            }
        }
    }

    // ── Periodic mid-trip checkpoint upload ──────────────────────────────────

    /** Every [CHECKPOINT_INTERVAL_MS] push the current (open) trip to the server.
     *  `uploadTrip` tries direct HTTP first, then the relay mesh-queue, so the
     *  trip becomes visible in the WMS within minutes even when the driver is on
     *  mobile data. Private trips never get here (no positions to share). */
    private fun startCheckpoints(id: String) {
        checkpointJob?.cancel()
        checkpointJob = scope.launch {
            // First checkpoint soon (CHECKPOINT_FIRST_MS), then the steady interval.
            // delay() (outside the try) throws on cancel → the loop ends cleanly.
            var wait = CHECKPOINT_FIRST_MS
            while (true) {
                delay(wait)
                wait = CHECKPOINT_INTERVAL_MS
                try {
                    val json = TripManager.buildUploadJson(applicationContext, id) ?: continue
                    val ok = apiService.uploadTrip(json)
                    Log.i(TAG, "checkpoint upload for trip $id: ok=$ok")
                } catch (e: Exception) {
                    Log.w(TAG, "checkpoint upload failed: ${e.message}")
                }
            }
        }
    }

    /** Driver dropped a checkpoint (a stop within a multi-stop trip). Record the
     *  current position as a `manual` point (with an optional label) and push an
     *  immediate checkpoint upload so the stop is in the WMS at once and survives an
     *  OOM kill. Falls back to the fused client's last known fix if we have no
     *  cached one yet; a manual point with no position is still recorded (time-only)
     *  so the stop is never lost. */
    @SuppressLint("MissingPermission")
    private fun recordManualCheckpoint(label: String?) {
        val id = tripId ?: run { Log.w(TAG, "checkpoint ignored: no open trip"); return }
        scope.launch {
            val loc = lastLoc ?: try {
                if (hasLocationPermission()) fusedClient?.lastLocation?.let { task ->
                    com.google.android.gms.tasks.Tasks.await(task)
                } else null
            } catch (e: Exception) { null }
            val db = AppDatabase.getInstance(applicationContext)
            db.tripDao().insertPoint(
                TripPointEntity(
                    tripId = id,
                    seq = seq.incrementAndGet(),
                    ts = System.currentTimeMillis(),
                    source = if (loc != null) "fused" else "manual",
                    lat = loc?.latitude,
                    lng = loc?.longitude,
                    accuracyM = loc?.accuracy?.toDouble(),
                    kind = "manual",
                    label = label?.takeIf { it.isNotBlank() }
                )
            )
            Log.i(TAG, "manual checkpoint for trip $id (label=${label ?: "-"}, pos=${loc != null})")
            // Force an immediate upload so the stop reaches the WMS now.
            try {
                val json = TripManager.buildUploadJson(applicationContext, id)
                if (json != null) {
                    val ok = apiService.uploadTrip(json)
                    Log.i(TAG, "manual checkpoint upload for trip $id: ok=$ok")
                }
            } catch (e: Exception) {
                Log.w(TAG, "manual checkpoint upload failed: ${e.message}")
            }
        }
    }

    /** Refuel event on the OPEN trip: records a `fuel` point at the current
     *  position carrying the odometer reading (estimated from the track or scanned)
     *  and the receipt photo CAS id, then uploads immediately. The server promotes
     *  it to a durable fuel_events row (survives point pruning — it's a tax doc). */
    @SuppressLint("MissingPermission")
    private fun recordFuelEvent(odometerKm: Double?, source: String?, photoId: String?) {
        val id = tripId ?: run { Log.w(TAG, "fuel ignored: no open trip"); return }
        scope.launch {
            val loc = lastLoc ?: try {
                if (hasLocationPermission()) fusedClient?.lastLocation?.let { task ->
                    com.google.android.gms.tasks.Tasks.await(task)
                } else null
            } catch (e: Exception) { null }
            val db = AppDatabase.getInstance(applicationContext)
            db.tripDao().insertPoint(
                TripPointEntity(
                    tripId = id,
                    seq = seq.incrementAndGet(),
                    ts = System.currentTimeMillis(),
                    source = if (loc != null) "fused" else "manual",
                    lat = loc?.latitude,
                    lng = loc?.longitude,
                    accuracyM = loc?.accuracy?.toDouble(),
                    kind = "fuel",
                    label = source,          // "estimated" | "scanned"
                    odometerKm = odometerKm,
                    photoId = photoId
                )
            )
            Log.i(TAG, "fuel event for trip $id (odo=${odometerKm ?: "-"}, src=$source, photo=${photoId != null})")
            try {
                val json = TripManager.buildUploadJson(applicationContext, id)
                if (json != null) {
                    val ok = apiService.uploadTrip(json)
                    Log.i(TAG, "fuel upload for trip $id: ok=$ok")
                }
            } catch (e: Exception) {
                Log.w(TAG, "fuel upload failed: ${e.message}")
            }
        }
    }

    // ── Cell sampling ────────────────────────────────────────────────────────

    private fun startCellSampling() {
        samplingJob?.cancel()
        samplingJob = scope.launch {
            while (true) {
                snapshotCells()
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun snapshotCells() {
        val id = tripId ?: return
        if (!hasLocationPermission()) return
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val registered = tm.allCellInfo.orEmpty().filter { it.isRegistered }
            val db = AppDatabase.getInstance(applicationContext)
            for (info in registered) {
                val point = cellToPoint(id, info) ?: continue
                db.tripDao().insertPoint(point)
            }
        } catch (e: Exception) {
            Log.w(TAG, "snapshotCells: ${e.message}")
        }
    }

    private fun cellToPoint(tripId: String, info: CellInfo): TripPointEntity? {
        fun intOrNull(v: Int): Int? = if (v == CellInfo.UNAVAILABLE) null else v
        fun strToInt(s: String?): Int? = s?.toIntOrNull()

        val (mcc, mnc, tac, cid, dbm) = when (info) {
            is CellInfoLte -> {
                val ci = info.cellIdentity
                Quint(strToInt(ci.mccString), strToInt(ci.mncString), intOrNull(ci.tac),
                    intOrNull(ci.ci)?.toLong(), info.cellSignalStrength.dbm)
            }
            is CellInfoNr -> {
                val ci = info.cellIdentity as CellIdentityNr
                val nci = if (ci.nci == CellInfo.UNAVAILABLE_LONG) null else ci.nci
                Quint(strToInt(ci.mccString), strToInt(ci.mncString), intOrNull(ci.tac),
                    nci, info.cellSignalStrength.dbm)
            }
            is CellInfoWcdma -> {
                val ci = info.cellIdentity
                Quint(strToInt(ci.mccString), strToInt(ci.mncString), intOrNull(ci.lac),
                    intOrNull(ci.cid)?.toLong(), info.cellSignalStrength.dbm)
            }
            is CellInfoGsm -> {
                val ci = info.cellIdentity
                Quint(strToInt(ci.mccString), strToInt(ci.mncString), intOrNull(ci.lac),
                    intOrNull(ci.cid)?.toLong(), info.cellSignalStrength.dbm)
            }
            else -> return null
        }

        if (cid == null || mcc == null) return null
        return TripPointEntity(
            tripId = tripId,
            seq = seq.incrementAndGet(),
            ts = System.currentTimeMillis(),
            source = "cell",
            mcc = mcc, mnc = mnc, tac = tac, cid = cid,
            signalDbm = dbm
        )
    }

    private data class Quint(
        val mcc: Int?, val mnc: Int?, val tac: Int?, val cid: Long?, val dbm: Int
    )

    // ── Fused low-power locations (no GPS chip) ──────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startFusedUpdates() {
        if (!hasLocationPermission()) return
        try {
            val client = LocationServices.getFusedLocationProviderClient(this)
            fusedClient = client
            val request = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY, SAMPLE_INTERVAL_MS
            ).setMinUpdateDistanceMeters(50f).build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val id = tripId ?: return
                    val loc = result.lastLocation ?: return
                    lastLoc = loc
                    scope.launch {
                        AppDatabase.getInstance(applicationContext).tripDao().insertPoint(
                            TripPointEntity(
                                tripId = id,
                                seq = seq.incrementAndGet(),
                                ts = loc.time,
                                source = "fused",
                                lat = loc.latitude,
                                lng = loc.longitude,
                                accuracyM = loc.accuracy.toDouble()
                            )
                        )
                        // Local geofence: prompt (never auto-confirm) near open visits
                        VisitManager.onTripLocation(
                            applicationContext, loc.latitude, loc.longitude, loc.accuracy.toDouble()
                        )
                        // Consent-gated live dashboard visibility: reuse this same
                        // fused fix (no extra location cost) to push an ephemeral
                        // live position for the moving car marker.
                        maybeShareLive(id, loc)
                    }
                }
            }
            locationCallback = callback
            client.requestLocationUpdates(request, callback, mainLooper)
        } catch (e: Exception) {
            // No Play Services / no permission — cells alone still work
            Log.w(TAG, "Fused updates unavailable: ${e.message}")
        }
    }

    // ── Live dashboard visibility (consent-gated) ────────────────────────────

    /** Push the current fused fix as an ephemeral live position when the driver
     *  has opted into live sharing. Skipped for private trips (which never reach
     *  the fused callback anyway) and when sharing is off. Re-read per fix so a
     *  mid-trip toggle takes effect immediately. Fire-and-forget — failure here
     *  must never disturb recording. */
    private suspend fun maybeShareLive(id: String, loc: android.location.Location) {
        if (!com.xelth.eckwms_movfast.utils.SettingsManager.getTripLiveShare()) return
        try {
            val trip = AppDatabase.getInstance(applicationContext).tripDao().getTrip(id) ?: return
            if (trip.purpose == "private") return
            val heading = if (loc.hasBearing()) loc.bearing.toDouble() else null
            val speed = if (loc.hasSpeed()) (loc.speed * 3.6) else null
            apiService.postTripLive(id, loc.latitude, loc.longitude, heading, speed, trip.vehiclePlate)
        } catch (e: Exception) {
            Log.w(TAG, "live share failed: ${e.message}")
        }
    }

    // ── Stop / finalize ──────────────────────────────────────────────────────

    private fun scheduleGracefulStop() {
        if (gracefulStopJob != null) return
        Log.i(TAG, "Graceful stop scheduled (+${GRACEFUL_STOP_DELAY_MS / 60000} min)")
        gracefulStopJob = scope.launch {
            delay(GRACEFUL_STOP_DELAY_MS)
            finalizeAndStop()
        }
    }

    private fun finalizeAndStop() {
        val memberId = tripId
        samplingJob?.cancel()
        checkpointJob?.cancel()
        locationCallback?.let { fusedClient?.removeLocationUpdates(it) }
        tripId = null
        TripManager.serviceRecordingTripId = null

        // Always resolve through the DB: if the OS killed the FGS mid-trip and
        // sticky-restarted it with no intent, `tripId` is null even though an
        // open trip still exists — fall back to it so "Fahrt beenden" actually
        // closes the trip instead of silently doing nothing.
        scope.launch {
            try {
                val db = AppDatabase.getInstance(applicationContext)
                val id = memberId ?: db.tripDao().getOpenTrip()?.id
                if (id != null) {
                    db.tripDao().endTrip(id, System.currentTimeMillis())
                    TripManager.publishActiveTrip(null)
                    TripManager.queueTripSync(applicationContext, id)
                    Log.i(TAG, "Trip $id finalized (${db.tripDao().pointCount(id)} points, resumed=${memberId == null})")
                } else {
                    Log.w(TAG, "finalizeAndStop: no open trip to finalize")
                    TripManager.publishActiveTrip(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "finalize failed: ${e.message}", e)
            } finally {
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        locationCallback?.let { fusedClient?.removeLocationUpdates(it) }
        TripManager.serviceRecordingTripId = null
        scope.cancel()
        super.onDestroy()
    }

    // ── Notification / permissions ───────────────────────────────────────────

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startForegroundWithNotification(isPrivate: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, getString(R.string.trip_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TripRecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(
                if (isPrivate) getString(R.string.trip_notification_private_title)
                else getString(R.string.trip_notification_title)
            )
            .setContentText(
                if (isPrivate) getString(R.string.trip_notification_private_text)
                else getString(R.string.trip_notification_text)
            )
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.trip_notification_stop), stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
