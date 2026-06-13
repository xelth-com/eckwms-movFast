package com.xelth.eckwms_movfast.ui.screens

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xelth.eckwms_movfast.data.local.AppDatabase
import com.xelth.eckwms_movfast.data.local.entity.TripEntity
import com.xelth.eckwms_movfast.trips.TripManager
import com.xelth.eckwms_movfast.utils.OdometerOcr
import com.xelth.eckwms_movfast.utils.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Fahrtenbuch: manual trip start/stop, auto-detect toggle, odometer entry
 * with OCR assist, and the local trip history with sync status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }

    val trips by db.tripDao().observeTrips(50).collectAsState(initial = emptyList())
    val activeTrip by TripManager.activeTrip.observeAsState(null)
    var autoDetect by remember { mutableStateOf(SettingsManager.getTripAutoDetect()) }
    var consent by remember { mutableStateOf(SettingsManager.getTripConsent()) }
    var privateMode by remember { mutableStateOf(false) }

    // Battery / background-restriction guards — re-checked on every recomposition
    // (e.g. after returning from the system dialog). The OS kills the recording
    // FGS mid-trip unless the app is battery-exempt and not background-restricted.
    var batteryOk by remember { mutableStateOf(TripManager.isIgnoringBatteryOptimizations(context)) }
    var bgRestricted by remember { mutableStateOf(TripManager.isBackgroundRestricted(context)) }
    val systemDialog = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryOk = TripManager.isIgnoringBatteryOptimizations(context)
        bgRestricted = TripManager.isBackgroundRestricted(context)
    }

    // Odometer dialog state: which end of the trip it is for
    var odometerFor by remember { mutableStateOf<String?>(null) } // "start" | "end"
    var pendingTripId by remember { mutableStateOf<String?>(null) }

    // Refresh active-trip LiveData from DB on entry (after process restarts)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            TripManager.publishActiveTrip(db.tripDao().getOpenTrip())
        }
    }

    val recordingPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val locationOk = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        // Privatfahrt needs no location at all — it records only the km frame
        if (locationOk || privateMode) {
            TripManager.startTrip(
                context, manual = true,
                purpose = if (privateMode) "private" else "business"
            )
            odometerFor = "start"
        }
    }

    val autoDetectPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && TripManager.enableAutoDetect(context)) {
            autoDetect = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fahrten", fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A1A),
                    titleContentColor = Color(0xFF4CAF50)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF121212))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── DSGVO consent gate (Einwilligung, revocable any time) ──
            if (!consent) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF263238))
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Einwilligung erforderlich", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            "Die Fahrt-Aufzeichnung erfasst während GESCHÄFTLICHER Fahrten " +
                            "ungefähre Positionen über Mobilfunkzellen (kein GPS). Privatfahrten " +
                            "werden nie geortet — nur der Kilometerstand. Rohdaten werden nach " +
                            "14 Tagen automatisch gelöscht; alle Einträge sind kryptografisch " +
                            "versiegelt und auch für den Arbeitgeber nicht nachträglich änderbar. " +
                            "Diese Einwilligung kann jederzeit ohne Nachteile widerrufen werden.",
                            color = Color(0xFFB0BEC5), fontSize = 12.sp
                        )
                        Button(
                            onClick = { SettingsManager.saveTripConsent(true); consent = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Einverstanden — Aufzeichnung aktivieren") }
                    }
                }
            } else if (activeTrip == null) {
                TextButton(onClick = {
                    SettingsManager.saveTripConsent(false)
                    TripManager.disableAutoDetect(context)
                    autoDetect = false
                    consent = false
                }) { Text("Einwilligung widerrufen", color = Color(0xFF90A4AE), fontSize = 12.sp) }
            }

            // ── Battery / background-restriction warning ──
            // Without these the OS kills the recording service mid-trip
            // ("Stopping service due to app idle / background restricted").
            if (consent && (!batteryOk || bgRestricted)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF4E2A00))
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("⚠️ Aufzeichnung kann unterbrochen werden", color = Color(0xFFFFB74D), fontWeight = FontWeight.Bold)
                        Text(
                            "Das System beendet die Fahrt-Aufzeichnung im Hintergrund, solange die " +
                            "App nicht von der Akku-Optimierung ausgenommen und nicht „eingeschränkt“ ist.",
                            color = Color(0xFFE0E0E0), fontSize = 12.sp
                        )
                        if (!batteryOk) {
                            Button(
                                onClick = {
                                    // paid: direct one-tap exemption dialog (restricted permission).
                                    // free/Play: open the general battery-opt list (Play-safe).
                                    val intent = if (com.xelth.eckwms_movfast.BuildConfig.ENTERPRISE)
                                        TripManager.batteryExemptionIntent(context)
                                    else TripManager.batteryListSettingsIntent()
                                    systemDialog.launch(intent)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF6C00)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (com.xelth.eckwms_movfast.BuildConfig.ENTERPRISE) "Akku-Optimierung ausnehmen"
                                    else "Akku-Einstellungen öffnen"
                                )
                            }
                        }
                        if (bgRestricted) {
                            OutlinedButton(
                                onClick = { systemDialog.launch(TripManager.appSettingsIntent(context)) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("App-Einstellungen öffnen (Einschränkung aufheben)") }
                        }
                    }
                }
            }

            // ── Active trip / start control ──
            if (consent) Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (activeTrip != null) Color(0xFF1B5E20) else Color(0xFF1E1E1E)
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (activeTrip != null) {
                        Text(
                            if (activeTrip!!.purpose == "private") "🔒 Privatfahrt läuft (keine Ortung)"
                            else "🚗 Fahrt läuft",
                            color = Color.White, fontWeight = FontWeight.Bold
                        )
                        Text(
                            "seit ${timeFmt.format(Date(activeTrip!!.startedAt))}" +
                                (activeTrip!!.startOdometerKm?.let { " · ab ${it.toInt()} km" } ?: ""),
                            color = Color(0xFFB0BEC5), fontSize = 13.sp
                        )
                        Button(
                            onClick = {
                                pendingTripId = activeTrip!!.id
                                TripManager.stopTrip(context, graceful = false)
                                odometerFor = "end"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("⏹ Fahrt beenden") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = !privateMode,
                                onClick = { privateMode = false },
                                label = { Text("Geschäftlich", fontSize = 12.sp) }
                            )
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = privateMode,
                                onClick = { privateMode = true },
                                label = { Text("🔒 Privat", fontSize = 12.sp) }
                            )
                        }
                        Button(
                            onClick = {
                                recordingPermissions.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    )
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (privateMode) "🔒 Privatfahrt starten" else "🚗 Fahrt starten") }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = autoDetect,
                            onCheckedChange = { wanted ->
                                if (wanted) {
                                    autoDetectPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                                } else {
                                    TripManager.disableAutoDetect(context)
                                    autoDetect = false
                                }
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Fahrten automatisch erkennen", color = Color(0xFFB0BEC5), fontSize = 13.sp)
                    }
                }
            }

            // ── Today's visits (check-in/check-out, never auto-confirmed) ──
            val visits by db.visitDao().observeOpenVisits().collectAsState(initial = emptyList())
            if (visits.isNotEmpty()) {
                Text("Besuche heute", color = Color.Gray, fontSize = 13.sp)
            }

            // ── Trip history ──
            Text("Verlauf", color = Color.Gray, fontSize = 13.sp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visits, key = { "v-" + it.id }) { visit ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A237E).copy(alpha = 0.35f))
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(visit.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                            if (!visit.address.isNullOrBlank()) {
                                Text(visit.address!!, color = Color(0xFF90A4AE), fontSize = 12.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Navigation: coordinates go only to the maps app
                                // on this device — nothing is reported anywhere
                                if (visit.lat != null && visit.lng != null) {
                                    OutlinedButton(onClick = {
                                        try {
                                            val uri = android.net.Uri.parse(
                                                "google.navigation:q=${visit.lat},${visit.lng}"
                                            )
                                            context.startActivity(
                                                android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW, uri
                                                )
                                            )
                                        } catch (e: Exception) {
                                            // No Google Maps — generic geo: fallback
                                            try {
                                                context.startActivity(
                                                    android.content.Intent(
                                                        android.content.Intent.ACTION_VIEW,
                                                        android.net.Uri.parse(
                                                            "geo:${visit.lat},${visit.lng}?q=${visit.lat},${visit.lng}"
                                                        )
                                                    )
                                                )
                                            } catch (_: Exception) {}
                                        }
                                    }) { Text("🧭", fontSize = 13.sp) }
                                }
                                if (visit.status == "open") {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                com.xelth.eckwms_movfast.trips.VisitManager.queueCheckEvent(
                                                    context, visit.id, "checkin",
                                                    oneShotLocation(context)
                                                )
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                                    ) { Text("✓ Check-in", fontSize = 13.sp) }
                                } else {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                com.xelth.eckwms_movfast.trips.VisitManager.queueCheckEvent(
                                                    context, visit.id, "checkout",
                                                    oneShotLocation(context)
                                                )
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                                    ) { Text("⏏ Check-out", fontSize = 13.sp) }
                                }
                                if (visit.status == "checked_in") {
                                    Text(
                                        "vor Ort seit ${visit.checkedInAt?.let { timeFmt.format(Date(it)) } ?: "?"}",
                                        color = Color(0xFF81C784), fontSize = 12.sp,
                                        modifier = Modifier.align(Alignment.CenterVertically)
                                    )
                                }
                            }
                        }
                    }
                }
                items(trips.filter { it.status != TripEntity.STATUS_RECORDING }, key = { it.id }) { trip ->
                    TripRow(trip)
                }
            }
        }
    }

    if (odometerFor != null) {
        OdometerDialog(
            isStart = odometerFor == "start",
            onDismiss = { odometerFor = null; pendingTripId = null },
            onSave = { km, source, photoId ->
                val forStart = odometerFor == "start"
                val tripId = pendingTripId
                scope.launch(Dispatchers.IO) {
                    // For start: the service has just created/reused the open trip
                    val target = tripId ?: db.tripDao().getOpenTrip()?.id
                    if (target != null) {
                        if (forStart) db.tripDao().setStartOdometer(target, km, source, photoId)
                        else db.tripDao().setEndOdometer(target, km, source, photoId)
                        // End odometer arrives after finalize — re-queue the upload
                        if (!forStart) TripManager.queueTripSync(context, target)
                        TripManager.publishActiveTrip(db.tripDao().getOpenTrip())
                    }
                }
                odometerFor = null
                pendingTripId = null
            }
        )
    }
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.GERMANY)
private val dateFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)

/**
 * One-shot position for a user-initiated check event (legally fine: the
 * worker taps, one fix is taken, nothing is tracked). Returns null fast
 * when permission is missing or no provider answers.
 */
private suspend fun oneShotLocation(context: android.content.Context): android.location.Location? {
    if (androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) return null
    return try {
        kotlinx.coroutines.withTimeoutOrNull(5000) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE)
                    as android.location.LocationManager
                val provider = when {
                    lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ->
                        android.location.LocationManager.NETWORK_PROVIDER
                    lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ->
                        android.location.LocationManager.GPS_PROVIDER
                    else -> { cont.resume(null, null); return@suspendCancellableCoroutine }
                }
                @Suppress("MissingPermission")
                lm.getCurrentLocation(provider, null, context.mainExecutor) { loc ->
                    if (cont.isActive) cont.resume(loc, null)
                }
            }
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun TripRow(trip: TripEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(dateFmt.format(Date(trip.startedAt)), color = Color.White, fontSize = 14.sp)
                Text(
                    when (trip.status) {
                        TripEntity.STATUS_SYNCED -> "✓ synced"
                        else -> "⏳ pending"
                    },
                    color = if (trip.status == TripEntity.STATUS_SYNCED) Color(0xFF81C784) else Color(0xFFFFB74D),
                    fontSize = 12.sp
                )
            }
            val dur = trip.endedAt?.let { (it - trip.startedAt) / 60000 }
            val odo = listOfNotNull(trip.startOdometerKm?.toInt(), trip.endOdometerKm?.toInt())
            val dist = if (odo.size == 2) "${odo[1] - odo[0]} km" else null
            Text(
                listOfNotNull(
                    if (trip.purpose == "private") "🔒 privat" else null,
                    dur?.let { "$it min" },
                    dist,
                    if (trip.manualStart) "manuell" else "auto"
                ).joinToString(" · "),
                color = Color(0xFF90A4AE), fontSize = 12.sp
            )
        }
    }
}

/**
 * Odometer (Kilometerstand) entry: manual number, or 📷 photo → ML Kit OCR
 * prefills the field. The photo is uploaded through the standard CAS pipeline.
 */
@Composable
private fun OdometerDialog(
    isStart: Boolean,
    onDismiss: () -> Unit,
    onSave: (km: Double, source: String, photoId: String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kmText by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("manual") }
    var photoId by remember { mutableStateOf<String?>(null) }
    var ocrRunning by remember { mutableStateOf(false) }

    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            ocrRunning = true
            scope.launch {
                val km = OdometerOcr.recognizeKm(bitmap)
                if (km != null) {
                    kmText = km.toInt().toString()
                    source = "photo"
                }
                // Upload the evidence photo through the CAS pipeline (offline-safe:
                // ScanApiService queues a retry path on failure inside callers; here
                // we fire-and-forget — the odometer VALUE is what the trip stores)
                val id = UUID.randomUUID().toString()
                try {
                    val api = com.xelth.eckwms_movfast.api.ScanApiService(context)
                    val deviceId = SettingsManager.getDeviceId(context)
                    val result = api.uploadImage(
                        bitmap, deviceId, "odometer_photo", null,
                        quality = 75, existingImageId = id
                    )
                    if (result is com.xelth.eckwms_movfast.api.ScanResult.Success) {
                        photoId = id
                        source = "photo"
                    }
                } catch (e: Exception) {
                    android.util.Log.w("OdometerDialog", "Photo upload failed: ${e.message}")
                }
                ocrRunning = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isStart) "Kilometerstand (Start)" else "Kilometerstand (Ende)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = kmText,
                    onValueChange = { kmText = it.filter { c -> c.isDigit() } },
                    label = { Text("km") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { takePhoto.launch(null) }, enabled = !ocrRunning) {
                        Text(if (ocrRunning) "OCR…" else "📷 Foto + OCR")
                    }
                    if (photoId != null) {
                        Spacer(Modifier.width(8.dp))
                        Text("✓ Foto", color = Color(0xFF81C784), fontSize = 12.sp)
                    }
                }
                Text(
                    "Ohne Eingabe wird die Distanz aus dem Streckenverlauf geschätzt.",
                    fontSize = 12.sp, color = Color.Gray
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = kmText.isNotBlank(),
                onClick = {
                    kmText.toDoubleOrNull()?.let { onSave(it, source, photoId) }
                }
            ) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Überspringen") }
        }
    )
}
