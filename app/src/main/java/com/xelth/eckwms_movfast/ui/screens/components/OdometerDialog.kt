package com.xelth.eckwms_movfast.ui.screens.components

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xelth.eckwms_movfast.data.local.entity.VehicleEntity
import com.xelth.eckwms_movfast.utils.OdometerOcr
import com.xelth.eckwms_movfast.utils.SettingsManager
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The vehicle chosen at trip start: a known vehicle picked from the list
 * (`vehicleId`) and/or a typed/OCR'd `plate`, plus the optional plate-photo CAS
 * id when a new vehicle is registered. Resolved by `VehicleManager`.
 */
data class VehicleChoice(val vehicleId: String?, val plate: String?, val photoId: String?)

/**
 * Odometer (Kilometerstand) entry: manual number, or 📷 photo → ML Kit OCR
 * prefills the field. At the START step it ALSO identifies the vehicle: pick a
 * known plate from the list, scan a plate photo (OCR), or — if exactly one
 * vehicle is registered — it is auto-filled (still editable). Shared by the
 * Trips screen and the trip MODE (MainScreen).
 */
@Composable
fun OdometerDialog(
    isStart: Boolean,
    onDismiss: () -> Unit,
    vehicles: List<VehicleEntity> = emptyList(),
    onSave: (km: Double, source: String, photoId: String?, vehicle: VehicleChoice?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Free the hardware-scanner camera while the (external) camera app is open — the
    // scan engine and the camera share one ISP, so an unbracketed camera open can
    // wedge the scanner until reboot (see TECH_DEBT scanner/ISP item).
    val scanMgr = (context.applicationContext as? com.xelth.eckwms_movfast.EckwmsApp)?.scannerManager
    var kmText by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("manual") }
    var photoId by remember { mutableStateOf<String?>(null) }
    var ocrRunning by remember { mutableStateOf(false) }

    // Vehicle selection (start only). Auto-fill when the fleet has exactly one.
    val single = if (isStart && vehicles.size == 1) vehicles[0] else null
    var selectedVehicleId by remember { mutableStateOf(single?.id) }
    var plateText by remember { mutableStateOf(single?.plate ?: "") }
    var platePhotoId by remember { mutableStateOf<String?>(null) }
    var plateOcrRunning by remember { mutableStateOf(false) }

    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        scanMgr?.resumeScanService()   // camera returned → re-acquire scan engine
        if (bitmap != null) {
            ocrRunning = true
            scope.launch {
                val km = OdometerOcr.recognizeKm(bitmap)
                if (km != null) {
                    kmText = km.toInt().toString()
                    source = "photo"
                }
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

    val takePlatePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        scanMgr?.resumeScanService()   // camera returned → re-acquire scan engine
        if (bitmap != null) {
            plateOcrRunning = true
            scope.launch {
                val plate = OdometerOcr.recognizePlate(bitmap)
                if (plate != null) {
                    plateText = plate
                    // A scanned plate is a (possibly new) vehicle — drop any prior pick.
                    selectedVehicleId = vehicles.firstOrNull { it.plate == plate }?.id
                }
                // Upload the plate photo to CAS as evidence ("once-photographed").
                val id = UUID.randomUUID().toString()
                try {
                    val api = com.xelth.eckwms_movfast.api.ScanApiService(context)
                    val deviceId = SettingsManager.getDeviceId(context)
                    val result = api.uploadImage(
                        bitmap, deviceId, "plate_photo", null,
                        quality = 75, existingImageId = id
                    )
                    if (result is com.xelth.eckwms_movfast.api.ScanResult.Success) {
                        platePhotoId = id
                    }
                } catch (e: Exception) {
                    android.util.Log.w("OdometerDialog", "Plate photo upload failed: ${e.message}")
                }
                plateOcrRunning = false
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
                    OutlinedButton(onClick = { scanMgr?.suspendScanService(); takePhoto.launch(null) }, enabled = !ocrRunning) {
                        Text(if (ocrRunning) "OCR…" else "📷 Foto + OCR")
                    }
                    if (photoId != null) {
                        Spacer(Modifier.width(8.dp))
                        Text("✓ Foto", color = Color(0xFF81C784), fontSize = 12.sp)
                    }
                }

                // ── Vehicle (Kennzeichen): chosen once, here at the odometer step ──
                if (isStart) {
                    Text("Fahrzeug (Kennzeichen)", fontSize = 12.sp, color = Color.Gray)
                    vehicles.forEach { v ->
                        val selected = v.id == selectedVehicleId
                        OutlinedButton(
                            onClick = { selectedVehicleId = v.id; plateText = v.plate },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text((if (selected) "● " else "○ ") + v.plate)
                        }
                    }
                    OutlinedTextField(
                        value = plateText,
                        onValueChange = {
                            plateText = it.uppercase()
                            selectedVehicleId = vehicles.firstOrNull { v -> v.plate == plateText }?.id
                        },
                        label = { Text("Kennzeichen") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { scanMgr?.suspendScanService(); takePlatePhoto.launch(null) }, enabled = !plateOcrRunning) {
                            Text(if (plateOcrRunning) "OCR…" else "📷 Kennzeichen scannen")
                        }
                        if (platePhotoId != null) {
                            Spacer(Modifier.width(8.dp))
                            Text("✓ Foto", color = Color(0xFF81C784), fontSize = 12.sp)
                        }
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
                    kmText.toDoubleOrNull()?.let { km ->
                        val vehicle = if (isStart && (selectedVehicleId != null || plateText.isNotBlank())) {
                            VehicleChoice(selectedVehicleId, plateText.ifBlank { null }, platePhotoId)
                        } else null
                        onSave(km, source, photoId, vehicle)
                    }
                }
            ) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Überspringen") }
        }
    )
}
