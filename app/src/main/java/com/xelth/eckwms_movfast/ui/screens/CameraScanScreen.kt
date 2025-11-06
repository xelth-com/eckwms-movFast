package com.xelth.eckwms_movfast.ui.screens

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.xelth.eckwms_movfast.utils.BitmapCache
import java.nio.ByteBuffer
import java.util.concurrent.Executors

private const val TAG = "CameraScanScreen"

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScanScreen(
    navController: NavController,
    scanMode: String = "barcode"
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var isProcessing by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }

    // ImageCapture use case for taking high-quality photos
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraPermissionState.status.isGranted) {
            // Camera preview setup
            when (scanMode) {
                "barcode" -> {
                    // Original barcode scanning mode with continuous analysis
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProvider = cameraProviderFuture.get()

                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            val imageAnalyzer = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also {
                                    it.setAnalyzer(cameraExecutor, BarcodeAnalyzer(
                                        onBarcodeDetected = { barcode, type ->
                                            if (!isProcessing) {
                                                isProcessing = true
                                                Log.d(TAG, "Barcode detected: $barcode (type: $type)")

                                                // Pass data back through savedStateHandle
                                                navController.previousBackStackEntry?.savedStateHandle?.set(
                                                    "scanned_barcode_data",
                                                    mapOf("barcode" to barcode, "type" to type)
                                                )

                                                // Navigate back
                                                navController.popBackStack()
                                            }
                                        }
                                    ))
                                }

                            val cameraSelector = CameraSelector.Builder()
                                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                                .build()

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalyzer
                            )
                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                "single_recovery", "multi_recovery", "multi_recovery_continue", "direct_upload", "workflow_capture" -> {
                    // Image capture mode for ML Kit recovery and direct upload
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProvider = cameraProviderFuture.get()

                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            // Setup ImageCapture for high-quality photos
                            val captureUseCase = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .build()

                            imageCapture = captureUseCase

                            val cameraSelector = CameraSelector.Builder()
                                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                                .build()

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                captureUseCase
                            )
                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Capture button for recovery modes and direct upload
            if (scanMode in listOf("single_recovery", "multi_recovery", "multi_recovery_continue", "direct_upload", "workflow_capture")) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isCapturing) {
                        CircularProgressIndicator()
                        Text("Capturing...")
                    } else {
                        Button(
                            onClick = {
                                if (!isCapturing && imageCapture != null) {
                                    isCapturing = true
                                    Log.d(TAG, "Capturing image for $scanMode mode")

                                    imageCapture?.takePicture(
                                        cameraExecutor,
                                        object : ImageCapture.OnImageCapturedCallback() {
                                            override fun onCaptureSuccess(image: ImageProxy) {
                                                try {
                                                    // Convert ImageProxy to Bitmap
                                                    val bitmap = imageProxyToBitmap(image)
                                                    Log.d(TAG, "Image captured: ${bitmap.width}x${bitmap.height}")

                                                    // Store bitmap in cache to avoid TransactionTooLargeException
                                                    BitmapCache.setCapturedImage(bitmap)

                                                    // Post to main thread for navigation and state updates
                                                    mainHandler.post {
                                                        try {
                                                            // Signal success via savedStateHandle
                                                            val resultKey = when (scanMode) {
                                                                "single_recovery" -> "captured_recovery_image"
                                                                "multi_recovery", "multi_recovery_continue" -> "captured_session_image"
                                                                "direct_upload" -> "captured_direct_upload_image"
                                                                "workflow_capture" -> "captured_workflow_image"
                                                                else -> "captured_recovery_image"
                                                            }

                                                            navController.previousBackStackEntry?.savedStateHandle?.set(
                                                                resultKey,
                                                                true // Just signal that capture succeeded
                                                            )

                                                            // Navigate back on main thread
                                                            navController.popBackStack()
                                                        } catch (e: Exception) {
                                                            Log.e(TAG, "Error during navigation", e)
                                                            isCapturing = false
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "Error processing captured image", e)
                                                    mainHandler.post {
                                                        isCapturing = false
                                                    }
                                                } finally {
                                                    image.close()
                                                }
                                            }

                                            override fun onError(exception: ImageCaptureException) {
                                                Log.e(TAG, "Image capture failed", exception)
                                                mainHandler.post {
                                                    isCapturing = false
                                                }
                                            }
                                        }
                                    )
                                }
                            },
                            enabled = !isCapturing
                        ) {
                            Text("Capture Image")
                        }

                        Button(
                            onClick = { navController.popBackStack() }
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            } else {
                // Back button for barcode mode
                Button(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    Text("Back")
                }
            }
        } else {
            // Permission not granted UI
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Camera permission is required to use this feature.")
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                    Text("Grant Permission")
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }
}

/**
 * Convert ImageProxy to Bitmap
 */
private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
    val buffer: ByteBuffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

/**
 * BarcodeAnalyzer processes camera frames with ML Kit
 */
private class BarcodeAnalyzer(
    private val onBarcodeDetected: (barcode: String, type: String) -> Unit
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient()
    private var lastAnalyzedTimestamp = 0L
    private var lastDetectedBarcode: String? = null

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val currentTimestamp = System.currentTimeMillis()

        // Throttle analysis to once per 500ms
        if (currentTimestamp - lastAnalyzedTimestamp < 500) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty()) {
                        val barcode = barcodes[0]
                        val barcodeValue = barcode.rawValue ?: ""

                        // Only trigger if it's a new barcode
                        if (barcodeValue.isNotEmpty() && barcodeValue != lastDetectedBarcode) {
                            lastDetectedBarcode = barcodeValue
                            val barcodeType = getBarcodeTypeName(barcode.format)
                            Log.d(TAG, "New barcode found: $barcodeValue (type: $barcodeType)")
                            onBarcodeDetected(barcodeValue, barcodeType)
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Barcode scanning failed", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                    lastAnalyzedTimestamp = currentTimestamp
                }
        } else {
            imageProxy.close()
        }
    }

    private fun getBarcodeTypeName(format: Int): String {
        return when (format) {
            Barcode.FORMAT_QR_CODE -> "QR_CODE"
            Barcode.FORMAT_CODE_128 -> "CODE_128"
            Barcode.FORMAT_CODE_39 -> "CODE_39"
            Barcode.FORMAT_CODE_93 -> "CODE_93"
            Barcode.FORMAT_CODABAR -> "CODABAR"
            Barcode.FORMAT_EAN_13 -> "EAN_13"
            Barcode.FORMAT_EAN_8 -> "EAN_8"
            Barcode.FORMAT_ITF -> "ITF"
            Barcode.FORMAT_UPC_A -> "UPC_A"
            Barcode.FORMAT_UPC_E -> "UPC_E"
            Barcode.FORMAT_PDF417 -> "PDF417"
            Barcode.FORMAT_AZTEC -> "AZTEC"
            Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
            else -> "UNKNOWN"
        }
    }
}
