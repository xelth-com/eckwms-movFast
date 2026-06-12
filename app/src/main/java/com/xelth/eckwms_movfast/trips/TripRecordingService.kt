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
        const val EXTRA_MANUAL = "manual"
        const val EXTRA_PURPOSE = "purpose"

        private const val TAG = "TripRecordingService"
        private const val CHANNEL_ID = "trip_recording"
        private const val NOTIFICATION_ID = 7302
        private const val SAMPLE_INTERVAL_MS = 30_000L
        private const val GRACEFUL_STOP_DELAY_MS = 3 * 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var samplingJob: Job? = null
    private var gracefulStopJob: Job? = null
    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    private var tripId: String? = null
    private val seq = AtomicInteger(0)

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
                        intent.getStringExtra(EXTRA_PURPOSE) ?: "business"
                    )
                }
            }
            ACTION_STOP_GRACEFUL -> scheduleGracefulStop()
            ACTION_STOP -> finalizeAndStop()
        }
        return START_STICKY
    }

    private fun startRecording(manual: Boolean, purpose: String) {
        val isPrivate = purpose == "private"
        startForegroundWithNotification(isPrivate)

        scope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            // Reuse an open trip after process death
            val existing = db.tripDao().getOpenTrip()
            val trip = existing ?: TripEntity(
                id = TripManager.newTripId(),
                startedAt = System.currentTimeMillis(),
                manualStart = manual,
                purpose = purpose
            ).also { db.tripDao().upsertTrip(it) }

            tripId = trip.id
            seq.set(db.tripDao().pointCount(trip.id))
            TripManager.publishActiveTrip(trip)
            Log.i(TAG, "Recording trip ${trip.id} (resumed=${existing != null}, manual=$manual, purpose=${trip.purpose})")

            // Privatfahrt: NO positions are sampled at all — the trip exists
            // only as a time frame for the odometer delta (1%-Regelung)
            if (trip.purpose != "private") {
                startCellSampling()
                startFusedUpdates()
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
        val id = tripId
        samplingJob?.cancel()
        locationCallback?.let { fusedClient?.removeLocationUpdates(it) }

        if (id != null) {
            // Finalize synchronously-ish before the service dies
            scope.launch {
                try {
                    val db = AppDatabase.getInstance(applicationContext)
                    db.tripDao().endTrip(id, System.currentTimeMillis())
                    TripManager.publishActiveTrip(null)
                    TripManager.queueTripSync(applicationContext, id)
                    Log.i(TAG, "Trip $id finalized (${db.tripDao().pointCount(id)} points)")
                } catch (e: Exception) {
                    Log.e(TAG, "finalize failed: ${e.message}", e)
                } finally {
                    stopSelf()
                }
            }
        } else {
            stopSelf()
        }
        tripId = null
    }

    override fun onDestroy() {
        locationCallback?.let { fusedClient?.removeLocationUpdates(it) }
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
