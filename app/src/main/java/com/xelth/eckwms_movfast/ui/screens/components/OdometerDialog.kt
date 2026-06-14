package com.xelth.eckwms_movfast.ui.screens.components

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.xelth.eckwms_movfast.utils.OdometerOcr
import com.xelth.eckwms_movfast.utils.SettingsManager
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Odometer (Kilometerstand) entry: manual number, or 📷 photo → ML Kit OCR
 * prefills the field. The photo is uploaded through the standard CAS pipeline.
 * Shared by the Trips screen and the trip MODE (MainScreen).
 */
@Composable
fun OdometerDialog(
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
                onClick = { kmText.toDoubleOrNull()?.let { onSave(it, source, photoId) } }
            ) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Überspringen") }
        }
    )
}
