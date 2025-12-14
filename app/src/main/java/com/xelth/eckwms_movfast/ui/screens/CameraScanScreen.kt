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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
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
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    Log.d(TAG, "CameraScanScreen opened with scan_mode: $scanMode")

    if (cameraPermissionState.status.isGranted) {
        when (scanMode) {
            "barcode" -> {
                Log.d(TAG, "Using BarcodeScanPreviewScreen for barcode mode")
                BarcodeScanPreviewScreen(navController = navController, scanMode = scanMode)
            }
            "pairing" -> {
                Log.d(TAG, "Using BarcodeScanPreviewScreen for pairing mode")
                BarcodeScanPreviewScreen(navController = navController, scanMode = scanMode)
            }
            "single_recovery", "multi_recovery", "multi_recovery_continue", "direct_upload", "workflow_capture" -> {
                Log.d(TAG, "Using ImageCapturePreviewScreen for $scanMode mode")
                ImageCapturePreviewScreen(navController = navController, scanMode = scanMode)
            }
            else -> {
                Log.d(TAG, "Unknown scan_mode: $scanMode, defaulting to BarcodeScanPreviewScreen")
                BarcodeScanPreviewScreen(navController = navController, scanMode = scanMode)
            }
        }
    } else {
        CameraPermissionScreen(onRequestPermission = { cameraPermissionState.launchPermissionRequest() })
    }
}

@Composable
fun CameraPermissionScreen(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Camera Permission Required", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text("This app needs camera access to scan barcodes and QR codes.", textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermission) {
            Text("Grant Permission")
        }
    }
}

@Composable
fun BarcodeScanPreviewScreen(
    navController: NavController,
    scanMode: String = "barcode"
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    Log.d(TAG, "BarcodeScanPreviewScreen: Setting up camera for mode: $scanMode")

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val imageAnalyzer = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also {
                                it.setAnalyzer(cameraExecutor, BarcodeAnalyzer { barcode, type ->
                                    Log.d(TAG, "BarcodeScanPreviewScreen: Barcode detected! Value: $barcode, Type: $type, ScanMode: $scanMode")
                                    cameraExecutor.shutdown()
                                    ContextCompat.getMainExecutor(ctx).execute {
                                        Log.d(TAG, "BarcodeScanPreviewScreen: Returning barcode to previous screen")
                                        navController.previousBackStackEntry?.savedStateHandle?.set(
                                            "scanned_barcode_data",
                                            mapOf("barcode" to barcode, "type" to type, "scanMode" to scanMode)
                                        )
                                        navController.popBackStack()
                                    }
                                })
                            }

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalyzer
                        )
                        Log.d(TAG, "BarcodeScanPreviewScreen: Camera bound successfully")
                    } catch (exc: Exception) {
                        Log.e(TAG, "Use case binding failed", exc)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )
        Button(
            onClick = { navController.popBackStack() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        ) {
            Text("Cancel")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }
}

@Composable
fun ImageCapturePreviewScreen(
    navController: NavController,
    scanMode: String
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        // Setup ImageCapture for high-quality photos
                        val captureUseCase = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                            .build()

                        imageCapture = captureUseCase

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            captureUseCase
                        )
                    } catch (exc: Exception) {
                        Log.e(TAG, "Use case binding failed", exc)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Capture button UI
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
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
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

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
    )
    private var isProcessing = false

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty()) {
                        isProcessing = true
                        val barcode = barcodes[0]
                        val barcodeValue = barcode.rawValue ?: ""
                        val barcodeType = getBarcodeTypeName(barcode.format)
                        onBarcodeDetected(barcodeValue, barcodeType)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Barcode scanning failed", e)
                }
                .addOnCompleteListener { _ ->
                    imageProxy.close()
                }
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
