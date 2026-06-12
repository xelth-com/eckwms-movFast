package com.xelth.eckwms_movfast.trips

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xelth.eckwms_movfast.MainActivity
import com.xelth.eckwms_movfast.data.local.AppDatabase
import com.xelth.eckwms_movfast.data.local.entity.SyncQueueEntity
import com.xelth.eckwms_movfast.sync.SyncManager
import com.xelth.eckwms_movfast.utils.SettingsManager
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Check-in/check-out visit flow (VG Lüneburg model, see PRIVACY_BY_DESIGN.md):
 * geofencing happens LOCALLY — when a business-trip location lands near an
 * open visit target the worker gets a prompt notification. Only the explicit
 * confirmation produces a server event; tracks are never matched server-side.
 */
object VisitManager {
    private const val TAG = "VisitManager"
    private const val CHANNEL_ID = "visit_prompts"
    private const val PROMPT_COOLDOWN_MS = 30 * 60_000L

    /** Queue a confirmed check-in/check-out (offline-first). One-shot position
     *  is attached only when provided by the UI at the moment of the tap. */
    suspend fun queueCheckEvent(
        context: Context,
        visitId: String,
        kind: String, // "checkin" | "checkout"
        location: Location?
    ) {
        val db = AppDatabase.getInstance(context)
        val now = System.currentTimeMillis()
        val payload = JSONObject().apply {
            put("visit_id", visitId)
            put("kind", kind)
            put("ts", java.time.Instant.ofEpochMilli(now).toString())
            location?.let {
                put("lat", it.latitude)
                put("lng", it.longitude)
                put("accuracy_m", it.accuracy.toDouble())
            }
        }
        db.visitDao().setStatus(
            visitId,
            if (kind == "checkin") "checked_in" else "done",
            if (kind == "checkin") now else null,
            if (kind == "checkout") now else null
        )
        db.syncQueueDao().addToQueue(SyncQueueEntity(type = "visit_event", payload = payload.toString()))
        SyncManager.scheduleSync(context)
        Log.i(TAG, "Visit $kind queued for $visitId (geo=${location != null})")
    }

    /** Called by TripRecordingService for each fused location during a
     *  BUSINESS trip: prompt (never auto-confirm) when near an open target. */
    suspend fun onTripLocation(context: Context, lat: Double, lng: Double, accuracyM: Double) {
        try {
            val db = AppDatabase.getInstance(context)
            val now = System.currentTimeMillis()
            for (visit in db.visitDao().openVisitsWithGeo()) {
                val vLat = visit.lat ?: continue
                val vLng = visit.lng ?: continue
                if (visit.status != "open") continue
                if ((visit.lastPromptAt ?: 0) > now - PROMPT_COOLDOWN_MS) continue

                val dist = haversineM(lat, lng, vLat, vLng)
                val radius = maxOf(2 * accuracyM, 300.0)
                if (dist <= radius) {
                    db.visitDao().markPrompted(visit.id, now)
                    showPrompt(context, visit.id, visit.title)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "onTripLocation: ${e.message}")
        }
    }

    private fun showPrompt(context: Context, visitId: String, title: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Besuchs-Hinweise", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, visitId.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            visitId.hashCode(),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_myplaces)
                .setContentTitle("In der Nähe: $title")
                .setContentText("Check-in über den Fahrten-Bildschirm bestätigen?")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
        Log.i(TAG, "Visit prompt shown for $visitId")
    }

    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lng2 - lng1)
        val a = sin(dp / 2) * sin(dp / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}

/** End-of-day "не съездил" reminder — purely status-based, no location. */
object VisitReminder {
    private const val CHANNEL_ID = "visit_reminder"
    private const val KEY_LAST_REMINDER_DAY = "visit_reminder_day"

    suspend fun maybeRemind(context: Context, db: AppDatabase) {
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        if (hour < 16 || hour > 20) return

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val prefs = context.getSharedPreferences("visit_reminder", Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_REMINDER_DAY, "") == today) return

        val openCount = db.visitDao().openDueCount(today)
        if (openCount == 0) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Besuchs-Erinnerung", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 7401, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            7401,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle("Offene Besuche heute: $openCount")
                .setContentText("Geplante Besuche sind noch nicht erledigt.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
        prefs.edit().putString(KEY_LAST_REMINDER_DAY, today).apply()
    }
}
