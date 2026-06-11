package com.xelth.eckwms_movfast.ui.dynamic

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.location.Location
import android.location.LocationManager
import android.speech.RecognizerIntent
import android.util.Base64
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream

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
    // Raw strokes mirror the Compose Path so the signature can be rendered
    // to a Bitmap on confirm (Compose Path itself can't be rasterized off-screen)
    val signatureStrokes = remember { mutableStateListOf<MutableList<Offset>>() }
    var signatureBoxSize by remember { mutableStateOf(IntSize.Zero) }
    var isSigned by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf<Location?>(null) }
    var locationIsFresh by remember { mutableStateOf(false) }

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)
            recognizedName = spokenText ?: ""
        }
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (requireGps && permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            fetchLocation(context) { loc, fresh ->
                // Prefer a fresh fix over any last-known fallback
                if (fresh || location == null) {
                    location = loc
                    locationIsFresh = fresh
                }
            }
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
                    }) { Text("🎤") }
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
                    .onSizeChanged { signatureBoxSize = it }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                signaturePath.moveTo(offset.x, offset.y)
                                signatureStrokes.add(mutableListOf(offset))
                            },
                            onDrag = { change, _ ->
                                signaturePath.lineTo(change.position.x, change.position.y)
                                signatureStrokes.lastOrNull()?.add(change.position)
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
                onClick = {
                    signaturePath = Path()
                    signatureStrokes.clear()
                    isSigned = false
                },
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
                        "accuracy" to location!!.accuracy,
                        "source" to if (locationIsFresh) "fresh_fix" else "last_known",
                        "fix_time" to location!!.time
                    )
                }

                if (isSigned) {
                    val b64 = renderSignatureToBase64(signatureStrokes, signatureBoxSize)
                    if (b64 != null) {
                        proofMap["signature_image"] = b64
                        proofMap["signature_mime"] = "image/webp"
                    } else {
                        proofMap["signature_image"] = "capture_failed"
                    }
                }

                onProofComplete(proofMap)
            }
        ) {
            Text("Confirm & Save")
        }
    }
}

/**
 * Rasterize the captured strokes to a white-background Bitmap and encode as
 * Base64 WebP (lossy 75 — project-wide image convention).
 */
private fun renderSignatureToBase64(
    strokes: List<List<Offset>>,
    size: IntSize
): String? {
    if (strokes.isEmpty() || size.width <= 0 || size.height <= 0) return null
    return try {
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)

        val paint = Paint().apply {
            color = android.graphics.Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 5f
            isAntiAlias = true
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        val path = android.graphics.Path()
        for (stroke in strokes) {
            if (stroke.isEmpty()) continue
            path.moveTo(stroke[0].x, stroke[0].y)
            for (i in 1 until stroke.size) {
                path.lineTo(stroke[i].x, stroke[i].y)
            }
        }
        canvas.drawPath(path, paint)

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, out)
        bitmap.recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) {
        android.util.Log.e("ActionProof", "Signature render failed: ${e.message}", e)
        null
    }
}

/**
 * Two-stage location: deliver the best last-known fix immediately (may be
 * stale/null), then request a single fresh fix and deliver it when it lands.
 * The callback's `fresh` flag tells the caller which stage produced the value.
 */
private fun fetchLocation(context: Context, onLocationResult: (Location?, Boolean) -> Unit) {
    try {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Stage 1: best last-known across providers (instant, possibly stale)
        var bestLocation: Location? = null
        for (provider in locationManager.getProviders(true)) {
            @Suppress("MissingPermission")
            val l = locationManager.getLastKnownLocation(provider) ?: continue
            if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                bestLocation = l
            }
        }
        if (bestLocation != null) {
            onLocationResult(bestLocation, false)
        }

        // Stage 2: single fresh fix (GPS preferred, network fallback).
        // getCurrentLocation handles its own timeout and returns null on failure.
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return
        }
        @Suppress("MissingPermission")
        locationManager.getCurrentLocation(provider, null, context.mainExecutor) { fresh ->
            if (fresh != null) {
                onLocationResult(fresh, true)
            }
        }
    } catch (e: SecurityException) {
        onLocationResult(null, false)
    }
}
