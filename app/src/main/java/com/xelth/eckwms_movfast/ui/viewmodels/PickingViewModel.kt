package com.xelth.eckwms_movfast.ui.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xelth.eckwms_movfast.api.ScanApiService
import com.xelth.eckwms_movfast.data.local.AppDatabase
import com.xelth.eckwms_movfast.data.local.entity.PickLineEntity
import com.xelth.eckwms_movfast.data.local.entity.PickingOrderEntity
import com.xelth.eckwms_movfast.ui.data.PathPoint
import com.xelth.eckwms_movfast.ui.data.RouteStop
import com.xelth.eckwms_movfast.utils.EckSecurityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PickingUiState {
    LOADING,
    PICK_LIST,
    EXECUTING,
    COMPLETED,
    ERROR
}

class PickingViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "PickingViewModel"
    private val db = AppDatabase.getInstance(application)
    private val pickingDao = db.pickingDao()
    private val apiService = ScanApiService(application)

    private val _uiState = MutableStateFlow(PickingUiState.LOADING)
    val uiState: StateFlow<PickingUiState> = _uiState.asStateFlow()

    private val _activePickings = MutableStateFlow<List<PickingOrderEntity>>(emptyList())
    val activePickings: StateFlow<List<PickingOrderEntity>> = _activePickings.asStateFlow()

    private val _selectedPicking = MutableStateFlow<PickingOrderEntity?>(null)
    val selectedPicking: StateFlow<PickingOrderEntity?> = _selectedPicking.asStateFlow()

    private val _pickLines = MutableStateFlow<List<PickLineEntity>>(emptyList())
    val pickLines: StateFlow<List<PickLineEntity>> = _pickLines.asStateFlow()

    private val _currentLineIndex = MutableStateFlow(0)
    val currentLineIndex: StateFlow<Int> = _currentLineIndex.asStateFlow()

    private val _routeStops = MutableStateFlow<List<RouteStop>>(emptyList())
    val routeStops: StateFlow<List<RouteStop>> = _routeStops.asStateFlow()

    private val _routePath = MutableStateFlow<List<PathPoint>>(emptyList())
    val routePath: StateFlow<List<PathPoint>> = _routePath.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _locationVerified = MutableStateFlow(false)
    val locationVerified: StateFlow<Boolean> = _locationVerified.asStateFlow()

    fun loadActivePickings() {
        viewModelScope.launch {
            _uiState.value = PickingUiState.LOADING
            try {
                val pickings = apiService.fetchActivePickings()
                if (pickings != null) {
                    pickingDao.insertPickings(pickings)
                }
                // Always read from local DB
                _activePickings.value = pickingDao.getActivePickings()
                _uiState.value = PickingUiState.PICK_LIST
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load pickings", e)
                // Fallback to local data
                _activePickings.value = pickingDao.getActivePickings()
                _uiState.value = PickingUiState.PICK_LIST
            }
        }
    }

    fun selectPicking(pickingId: Long) {
        viewModelScope.launch {
            _uiState.value = PickingUiState.LOADING
            try {
                // Fetch route-optimized lines from server
                val routeResponse = apiService.fetchPickingRoute(pickingId)
                if (routeResponse != null) {
                    pickingDao.clearPickLines(pickingId)
                    pickingDao.insertPickLines(routeResponse.lines)
                    _routePath.value = routeResponse.path
                }

                _selectedPicking.value = pickingDao.getPickingById(pickingId)
                _pickLines.value = pickingDao.getPickLines(pickingId)
                _currentLineIndex.value = findFirstUnpickedIndex()
                _locationVerified.value = false
                updateRouteStops()
                _uiState.value = PickingUiState.EXECUTING
            } catch (e: Exception) {
                Log.e(TAG, "Failed to select picking $pickingId", e)
                _errorMessage.value = "Failed to load picking: ${e.message}"
                _uiState.value = PickingUiState.ERROR
            }
        }
    }

    fun onLocationScanned(barcode: String) {
        val currentLine = getCurrentLine() ?: return
        if (currentLine.locationBarcode == barcode) {
            _locationVerified.value = true
            _errorMessage.value = null
        } else {
            _errorMessage.value = "Wrong location! Expected: ${currentLine.locationBarcode}"
            _locationVerified.value = false
        }
    }

    fun onProductScanned(barcode: String) {
        val currentLine = getCurrentLine() ?: return
        if (currentLine.productBarcode != barcode) {
            _errorMessage.value = "Wrong product! Expected: ${currentLine.productBarcode}"
            return
        }

        _errorMessage.value = null
        viewModelScope.launch {
            val newQtyDone = currentLine.qtyDone + 1.0
            val newState = if (newQtyDone >= currentLine.qtyDemand) "done" else "assigned"

            // Update local DB
            pickingDao.updatePickLineProgress(currentLine.id, newQtyDone, newState)

            // Send to server
            try {
                apiService.confirmPickLine(
                    currentLine.pickingId, currentLine.id,
                    newQtyDone, barcode, currentLine.locationBarcode ?: ""
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to confirm pick line on server", e)
                // Local update already done, will sync later
            }

            // Refresh lines
            _pickLines.value = pickingDao.getPickLines(currentLine.pickingId)

            // Advance to next line if done
            if (newState == "done") {
                val nextIndex = findFirstUnpickedIndex()
                _currentLineIndex.value = nextIndex
                _locationVerified.value = false
                updateRouteStops()

                // Check if all done
                if (_pickLines.value.all { it.state == "done" || it.qtyDone >= it.qtyDemand }) {
                    _uiState.value = PickingUiState.COMPLETED
                }
            }

            // Update picking progress
            val pickedCount = _pickLines.value.count { it.state == "done" || it.qtyDone >= it.qtyDemand }
            pickingDao.updatePickingProgress(currentLine.pickingId, "assigned", pickedCount)
        }
    }

    /**
     * Entry point for hardware scanner barcodes.
     * Decrypts encrypted ECK QR codes, resolves smart codes, then routes
     * to location or product validation based on current picking state.
     */
    fun processScan(rawBarcode: String) {
        if (_uiState.value != PickingUiState.EXECUTING) return
        val currentLine = getCurrentLine() ?: return

        // Decrypt encrypted ECK QR if needed
        var code = rawBarcode
        if (EckSecurityManager.isEncryptedEckUrl(rawBarcode)) {
            val decrypted = EckSecurityManager.tryDecryptBarcode(rawBarcode)
            if (decrypted != null) {
                code = decrypted
                Log.d(TAG, "Picking scan decrypted: $code")
            } else {
                _errorMessage.value = "Failed to decrypt QR code"
                return
            }
        }

        if (!_locationVerified.value) {
            // --- Expecting location scan ---
            // Smart place code: 'p' + 18-digit zero-padded Odoo location ID
            if (code.startsWith("p") && code.length == 19) {
                val scannedLocId = code.substring(1).trimStart('0').toLongOrNull()
                if (scannedLocId != null && scannedLocId == currentLine.locationId) {
                    _locationVerified.value = true
                    _errorMessage.value = null
                    Log.d(TAG, "Location verified via smart place code: $scannedLocId")
                    return
                }
            }
            // Direct barcode comparison
            onLocationScanned(code)
        } else {
            // --- Expecting product scan ---
            // Smart item code: 'i' + base36(EAN length) + padded serial + EAN
            if (code.startsWith("i") && code.length == 19) {
                val eanLen = code[1].toString().toIntOrNull(36)
                if (eanLen != null && eanLen in 1..18) {
                    val ean = code.substring(19 - eanLen)
                    if (ean == currentLine.productBarcode) {
                        onProductScanned(ean)
                        return
                    }
                }
            }
            // Direct barcode comparison
            onProductScanned(code)
        }
    }

    fun skipCurrentLine() {
        val lines = _pickLines.value
        if (lines.isEmpty()) return
        val nextIndex = (_currentLineIndex.value + 1) % lines.size
        _currentLineIndex.value = nextIndex
        _locationVerified.value = false
        updateRouteStops()
    }

    fun validateAndComplete() {
        val picking = _selectedPicking.value ?: return
        viewModelScope.launch {
            try {
                val success = apiService.validatePicking(picking.id)
                if (success) {
                    pickingDao.updatePickingProgress(picking.id, "done", _pickLines.value.size)
                    _uiState.value = PickingUiState.COMPLETED
                } else {
                    _errorMessage.value = "Server rejected validation — not all lines complete"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to validate picking", e)
                _errorMessage.value = "Validation failed: ${e.message}"
            }
        }
    }

    fun backToPickList() {
        _selectedPicking.value = null
        _pickLines.value = emptyList()
        _routeStops.value = emptyList()
        _routePath.value = emptyList()
        _currentLineIndex.value = 0
        _errorMessage.value = null
        _uiState.value = PickingUiState.PICK_LIST
        loadActivePickings()
    }

    fun getCurrentLine(): PickLineEntity? {
        val lines = _pickLines.value
        val idx = _currentLineIndex.value
        return if (idx in lines.indices) lines[idx] else null
    }

    private fun findFirstUnpickedIndex(): Int {
        return _pickLines.value.indexOfFirst {
            it.state != "done" && it.qtyDone < it.qtyDemand
        }.coerceAtLeast(0)
    }

    private fun updateRouteStops() {
        val lines = _pickLines.value
        val currentIdx = _currentLineIndex.value
        val currentLine = if (currentIdx in lines.indices) lines[currentIdx] else null

        _routeStops.value = lines.filter { it.rackId != null }.distinctBy { it.rackId }.map { line ->
            RouteStop(
                rackId = line.rackId!!,
                sequence = line.sequence,
                productName = line.productName,
                qty = line.qtyDemand,
                isCompleted = line.state == "done" || line.qtyDone >= line.qtyDemand,
                isCurrent = line.rackId == currentLine?.rackId
            )
        }
    }
}
