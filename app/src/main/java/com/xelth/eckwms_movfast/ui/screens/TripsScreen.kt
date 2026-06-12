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
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            TripManager.startTrip(context, manual = true)
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
            // ── Active trip / start control ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (activeTrip != null) Color(0xFF1B5E20) else Color(0xFF1E1E1E)
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (activeTrip != null) {
                        Text("🚗 Fahrt läuft", color = Color.White, fontWeight = FontWeight.Bold)
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
                        ) { Text("🚗 Fahrt starten") }
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

            // ── Trip history ──
            Text("Verlauf", color = Color.Gray, fontSize = 13.sp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
