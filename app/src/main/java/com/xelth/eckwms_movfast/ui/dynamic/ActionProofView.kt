package com.xelth.eckwms_movfast.ui.dynamic

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun ActionProofView(
    requireGps: Boolean,
    requireSignature: Boolean,
    requireVoiceName: Boolean,
    legalText: String,
    onProofComplete: (Map<String, Any>) -> Unit
) {
    val context = LocalContext.current
    var recognizedName by remember { mutableStateOf("") }
    var signaturePath by remember { mutableStateOf(Path()) }
    var isSigned by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf<Location?>(null) }

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)
            recognizedName = spokenText ?: ""
        }
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (requireGps && permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            fetchLocation(context) { loc -> location = loc }
        }
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf<String>()
        if (requireGps) perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (requireVoiceName) perms.add(Manifest.permission.RECORD_AUDIO)

        if (perms.isNotEmpty()) {
            requestPermissionLauncher.launch(perms.toTypedArray())
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (legalText.isNotEmpty()) {
            Text(legalText, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }

        if (requireVoiceName) {
            OutlinedTextField(
                value = recognizedName,
                onValueChange = { recognizedName = it },
                label = { Text("Name (Voice or Text)") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    Button(onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
                        }
                        speechLauncher.launch(intent)
                    }) { Text("\uD83C\uDFA4") }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
        }

        if (requireSignature) {
            Text("Signature:", color = Color.White)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(Color.White)
                    .border(1.dp, Color.Gray)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset -> signaturePath.moveTo(offset.x, offset.y) },
                            onDrag = { change, _ ->
                                signaturePath.lineTo(change.position.x, change.position.y)
                                isSigned = true
                            }
                        )
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawPath(path = signaturePath, color = Color.Black, style = Stroke(width = 5f))
                }
            }
            Button(
                onClick = { signaturePath = Path(); isSigned = false },
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
            ) {
                Text("Clear Signature")
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = (!requireVoiceName || recognizedName.isNotEmpty()) && (!requireSignature || isSigned),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            onClick = {
                val proofMap = mutableMapOf<String, Any>()
                proofMap["verified_by"] = recognizedName
                proofMap["proof_type"] = if (isSigned) "signature" else "voice_text"
                proofMap["timestamp"] = System.currentTimeMillis().toString()

                if (requireGps && location != null) {
                    proofMap["location"] = mapOf(
                        "lat" to location!!.latitude,
                        "lng" to location!!.longitude,
                        "accuracy" to location!!.accuracy
                    )
                }

                if (isSigned) {
                    proofMap["signature_image"] = "captured"
                }

                onProofComplete(proofMap)
            }
        ) {
            Text("Confirm & Save")
        }
    }
}

private fun fetchLocation(context: Context, onLocationResult: (Location?) -> Unit) {
    try {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = locationManager.getProviders(true)
        var bestLocation: Location? = null
        for (provider in providers) {
            @Suppress("MissingPermission")
            val l = locationManager.getLastKnownLocation(provider) ?: continue
            if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                bestLocation = l
            }
        }
        onLocationResult(bestLocation)
    } catch (e: SecurityException) {
        onLocationResult(null)
    }
}
