package com.xelth.eckwms_movfast.ui.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.xelth.eckwms_movfast.EckwmsApp
import com.xelth.eckwms_movfast.scanners.ScannerManager
import com.xelth.eckwms_movfast.scanners.getLastDecodedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ScanState {
    IDLE, // Waiting for user action
    HW_SCANNING, // Hardware scanner is active
    HW_SCAN_FAILED, // Hardware scan timed out or failed, ready for chance 2
    ML_ANALYSIS_SINGLE, // ML Kit is analyzing a single image
    ML_ANALYSIS_FAILED, // ML Kit failed on single image, ready for chance 3
    RECOVERY_SESSION_ACTIVE, // User is collecting multiple images
    RECOVERY_ANALYSIS, // ML Kit is analyzing collected images
    SUCCESS, // Barcode found
    FAILURE // All attempts failed
}

data class RecoveryStatus(val imagesCollected: Int, val totalImages: Int)

class ScanRecoveryViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "ScanRecoveryVM"
    private val scannerManager: ScannerManager = (application as EckwmsApp).scannerManager

    private val _scanState = MutableLiveData<ScanState>(ScanState.IDLE)
    val scanState: LiveData<ScanState> = _scanState

    private val _scannedBarcode = MutableLiveData<String?>(null)
    val scannedBarcode: LiveData<String?> = _scannedBarcode

    private val _recoveryStatus = MutableLiveData<RecoveryStatus>()
    val recoveryStatus: LiveData<RecoveryStatus> = _recoveryStatus

    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage

    private var hardwareScanJob: Job? = null
    private val recoveryImages = mutableListOf<Bitmap>()
    private val RECOVERY_IMAGE_COUNT = 3

    init {
        scannerManager.scanResult.observeForever {
            if (it != null && _scanState.value == ScanState.HW_SCANNING) {
                Log.d(TAG, "Hardware scan success: $it")
                hardwareScanJob?.cancel()
                _scannedBarcode.postValue(it)
                _scanState.postValue(ScanState.SUCCESS)
            }
        }
    }

    fun startHardwareScan() {
        Log.d(TAG, "Starting hardware scan...")
        _scanState.value = ScanState.HW_SCANNING
        _errorMessage.value = null
        _scannedBarcode.value = null
        scannerManager.startLoopScan(500)

        hardwareScanJob = viewModelScope.launch {
            delay(5000) // 5 second timeout for hardware scan
            if (_scanState.value == ScanState.HW_SCANNING) {
                Log.d(TAG, "Hardware scan timed out.")
                scannerManager.stopLoopScan()
                _scanState.postValue(ScanState.HW_SCAN_FAILED)
            }
        }
    }

    fun trySingleImageRecovery() {
        Log.d(TAG, "Starting single image recovery (Chance 2)...")
        _scanState.value = ScanState.ML_ANALYSIS_SINGLE
        val image = scannerManager.getLastDecodedImage()
        if (image != null) {
            processImageWithMlKit(image) { success, result ->
                if (success) {
                    _scannedBarcode.postValue(result)
                    _scanState.postValue(ScanState.SUCCESS)
                } else {
                    Log.d(TAG, "Single image recovery failed.")
                    _errorMessage.postValue(result)
                    _scanState.postValue(ScanState.ML_ANALYSIS_FAILED)
                }
            }
        } else {
            Log.e(TAG, "Failed to get image from scanner for recovery.")
            _errorMessage.postValue("Could not get image from scanner.")
            _scanState.postValue(ScanState.ML_ANALYSIS_FAILED)
        }
    }

    fun startRecoverySession() {
        Log.d(TAG, "Starting multi-image recovery session (Chance 3)...")
        recoveryImages.clear()
        _recoveryStatus.value = RecoveryStatus(0, RECOVERY_IMAGE_COUNT)
        _scanState.value = ScanState.RECOVERY_SESSION_ACTIVE
        _errorMessage.value = null
    }

    fun captureImageForRecovery() {
        if (_scanState.value != ScanState.RECOVERY_SESSION_ACTIVE) return

        val image = scannerManager.getLastDecodedImage()
        if (image != null) {
            recoveryImages.add(image)
            _recoveryStatus.value = RecoveryStatus(recoveryImages.size, RECOVERY_IMAGE_COUNT)
            Log.d(TAG, "Collected image ${recoveryImages.size}/$RECOVERY_IMAGE_COUNT for recovery.")

            if (recoveryImages.size >= RECOVERY_IMAGE_COUNT) {
                processRecoveryImages()
            }
        } else {
            _errorMessage.postValue("Failed to capture image. Please try again.")
        }
    }

    private fun processRecoveryImages() {
        Log.d(TAG, "Processing ${recoveryImages.size} collected images...")
        _scanState.value = ScanState.RECOVERY_ANALYSIS

        viewModelScope.launch(Dispatchers.IO) {
            for ((index, image) in recoveryImages.withIndex()) {
                Log.d(TAG, "Analyzing image ${index + 1}...")
                val found = processImageWithMlKitSuspending(image) // Using suspending version
                if (found != null) {
                    _scannedBarcode.postValue(found)
                    _scanState.postValue(ScanState.SUCCESS)
                    return@launch
                }
            }
            Log.d(TAG, "Multi-image recovery failed.")
            _errorMessage.postValue("Could not decode barcode from any of the images.")
            _scanState.postValue(ScanState.FAILURE)
        }
    }

    private fun processImageWithMlKit(bitmap: Bitmap, callback: (Boolean, String) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val options = BarcodeScannerOptions.Builder().build() // All formats
        val scanner = BarcodeScanning.getClient(options)

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    callback(true, barcodes[0].rawValue ?: "")
                } else {
                    callback(false, "No barcode found in image.")
                }
            }
            .addOnFailureListener { e ->
                callback(false, "ML Kit analysis failed: ${e.message}")
            }
    }

    private suspend fun processImageWithMlKitSuspending(bitmap: Bitmap): String? {
        return kotlin.coroutines.suspendCoroutine { continuation ->
            processImageWithMlKit(bitmap) { success, result ->
                if (success) {
                    continuation.resumeWith(Result.success(result))
                } else {
                    continuation.resumeWith(Result.success(null))
                }
            }
        }
    }

    fun reset() {
        hardwareScanJob?.cancel()
        scannerManager.stopLoopScan()
        _scanState.postValue(ScanState.IDLE)
        _scannedBarcode.postValue(null)
        _errorMessage.postValue(null)
    }

    override fun onCleared() {
        super.onCleared()
        scannerManager.scanResult.removeObserver { }
    }
}