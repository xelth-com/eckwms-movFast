package com.xelth.eckwms_movfast.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xelth.eckwms_movfast.ui.dynamic.DynamicUiRenderer
import com.xelth.eckwms_movfast.ui.screens.components.ConsoleList
import com.xelth.eckwms_movfast.ui.screens.components.ConsoleRow
import com.xelth.eckwms_movfast.ui.screens.pos.components.ConsoleView
import com.xelth.eckwms_movfast.ui.screens.pos.components.SelectionAreaSheet
import com.xelth.eckwms_movfast.ui.viewmodels.MainScreenViewModel
import com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel
import com.xelth.eckwms_movfast.ui.data.NetworkHealthState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.graphics.Bitmap

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    navController: NavController,
    viewModel: ScanRecoveryViewModel
) {
    val mainViewModel: MainScreenViewModel = viewModel()
    val networkHealthState by viewModel.networkHealthState.observeAsState(NetworkHealthState.Checking)
    val deviceRegistrationStatus by viewModel.deviceRegistrationStatus.observeAsState("unknown")

    val renderCells by mainViewModel.renderCells.observeAsState(emptyList())
    val consoleLogs by mainViewModel.consoleLogs.observeAsState(emptyList())
    val scannerEnabled by mainViewModel.scannerEnabled.observeAsState(true)

    // Repair mode state
    val isRepairMode by mainViewModel.isRepairMode.observeAsState(false)
    val repairStatus by mainViewModel.repairStatus.observeAsState("")

    // Active slot history and photos
    val activeSlotHistory by mainViewModel.activeSlotHistory.observeAsState(emptyList())
    val activeSlotPhotosList by mainViewModel.activeSlotPhotosList.observeAsState(emptyList())
    var fullScreenPhoto by remember { mutableStateOf<Bitmap?>(null) }

    // Receiving mode state
    val isReceivingMode by mainViewModel.isReceivingMode.observeAsState(false)
    val receivingStatus by mainViewModel.receivingStatus.observeAsState("")
    val receivingModalJson by mainViewModel.showReceivingModal.observeAsState(null)

    // Trip mode state (Fahrtenbuch)
    val isTripMode by mainViewModel.isTripMode.observeAsState(false)
    val tripStatus by mainViewModel.tripStatus.observeAsState("")
    val tripActive by com.xelth.eckwms_movfast.trips.TripManager.activeTrip.observeAsState(null)
    LaunchedEffect(tripActive) { mainViewModel.setTripRecording(tripActive != null) }

    // Device Check mode state
    val isDeviceCheckMode by mainViewModel.isDeviceCheckMode.observeAsState(false)
    val deviceCheckStatus by mainViewModel.deviceCheckStatus.observeAsState("")

    // Restock mode state
    val isRestockMode by mainViewModel.isRestockMode.observeAsState(false)

    // Inventory mode state
    val isInventoryMode by mainViewModel.isInventoryMode.observeAsState(false)

    // Multi-user state (collected early for SelectionAreaSheet)
    val currentUserState by com.xelth.eckwms_movfast.ui.viewmodels.UserManager.currentUser.collectAsState()
    val viewingUserState by com.xelth.eckwms_movfast.ui.viewmodels.UserManager.viewingUser.collectAsState()

    // Native half-slot buttons
    val exitButtonState by mainViewModel.exitButton.observeAsState(null)

    // Read shared settings from ScanRecoveryViewModel
    val sharedGridRowCount by viewModel.gridRowCount.observeAsState(7)
    val sharedIsLeftHanded by viewModel.isLeftHanded.observeAsState(false)

    val density = LocalDensity.current
    val context = LocalContext.current

    // Trip mode: refresh active trip on enter + auto-detect permission launcher
    LaunchedEffect(isTripMode) {
        if (isTripMode) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val db = com.xelth.eckwms_movfast.data.local.AppDatabase.getInstance(context)
                com.xelth.eckwms_movfast.trips.TripManager.publishActiveTrip(db.tripDao().getOpenTrip())
            }
        }
    }
    val tripAutoPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        mainViewModel.setTripAutoDetect(
            granted && com.xelth.eckwms_movfast.trips.TripManager.enableAutoDetect(context)
        )
    }

    // Trip mode: console search (text + push-to-talk voice) + start (ticket tap
    // OR dictated/typed address) + odometer
    val tripScope = rememberCoroutineScope()
    val tripDestinations by mainViewModel.tripDestinations.observeAsState(emptyList())
    var tripQuery by remember { mutableStateOf("") }
    var tripListening by remember { mutableStateOf(false) }
    val speech = remember { com.xelth.eckwms_movfast.utils.SpeechToText(context) }
    var tripPendingRef by remember { mutableStateOf<String?>(null) }
    var tripPendingLabel by remember { mutableStateOf<String?>(null) }
    var tripPendingSource by remember { mutableStateOf("planned") }
    var tripOdometerStart by remember { mutableStateOf(false) }

    // Debounced live search across ALL tickets while typing/dictating
    LaunchedEffect(tripQuery, isTripMode) {
        if (isTripMode) {
            kotlinx.coroutines.delay(350)
            mainViewModel.searchTripDestinations(tripQuery)
        }
    }
    DisposableEffect(Unit) { onDispose { speech.destroy() } }

    val tripStartLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            com.xelth.eckwms_movfast.trips.TripManager.startTrip(
                context, manual = true, purpose = "business",
                purposeRef = tripPendingRef, purposeLabel = tripPendingLabel,
                purposeSource = tripPendingSource
            )
            tripOdometerStart = true
        }
    }
    val tripStart: (String?, String?, String) -> Unit = { ref, label, src ->
        tripPendingRef = ref; tripPendingLabel = label; tripPendingSource = src
        tripStartLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            )
        )
    }
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted → user presses the mic again to talk */ }

    // State for the Intake Bottom Sheet
    var showIntakeSheet by remember { mutableStateOf(false) }
    var showNetworkPanel by remember { mutableStateOf(false) }
    var intakeConfigJson by remember { mutableStateOf("{}") }
    var intakeLongPressAction by remember { mutableStateOf("") }
    val intakeFormState = remember { mutableStateMapOf<String, Any>() }

    // State for slot long-press menu (delete + change background)
    var showSlotMenu by remember { mutableStateOf(false) }
    var slotMenuAction by remember { mutableStateOf("") }

    // State for receiving workflow modal
    val receivingModalFormState = remember { mutableStateMapOf<String, Any>() }

    // Sync shared settings into MainScreenViewModel
    LaunchedEffect(sharedGridRowCount) {
        mainViewModel.setGridRowCount(sharedGridRowCount)
    }
    LaunchedEffect(sharedIsLeftHanded) {
        if (sharedIsLeftHanded != (mainViewModel.isLeftHanded.value ?: false)) {
            mainViewModel.toggleHandedness()
        }
    }

    // Initialize network health monitoring (same as ScanScreen does)
    LaunchedEffect(Unit) {
        viewModel.onViewModelReady()
    }

    // Wire repair event callbacks from MainScreenViewModel to ScanRecoveryViewModel
    LaunchedEffect(Unit) {
        mainViewModel.onRepairEventSend = { targetDeviceId, eventType, data ->
            viewModel.sendRepairEvent(targetDeviceId, eventType, data)
        }
        mainViewModel.onRepairPhotoUpload = { targetDeviceId, bitmap ->
            viewModel.uploadRepairPhoto(targetDeviceId, bitmap)
        }
        mainViewModel.onSaveRepairSlots = { slots ->
            com.xelth.eckwms_movfast.utils.SettingsManager.saveRepairSlots(slots)
        }
        mainViewModel.onLoadRepairSlots = {
            com.xelth.eckwms_movfast.utils.SettingsManager.loadRepairSlots()
        }
        mainViewModel.onSaveRepairPhoto = { index, bitmap ->
            com.xelth.eckwms_movfast.utils.SettingsManager.saveRepairPhoto(index, bitmap)
        }
        mainViewModel.onLoadRepairPhoto = { index ->
            com.xelth.eckwms_movfast.utils.SettingsManager.loadRepairPhoto(index)
        }
        mainViewModel.onDeleteRepairPhoto = { index ->
            com.xelth.eckwms_movfast.utils.SettingsManager.deleteRepairPhoto(index)
        }
        // UUID-based photo persistence (immutable CAS)
        val db = com.xelth.eckwms_movfast.data.local.AppDatabase.getInstance(context)
        val localPhotoDao = db.localPhotoDao()
        mainViewModel.onSavePhoto = { uuid, slotIndex, bitmap ->
            val origPath = com.xelth.eckwms_movfast.utils.SettingsManager.savePhotoOriginal(uuid, bitmap)
            // DB insert is fire-and-forget via ScanRecoveryViewModel's scope
            viewModel.insertLocalPhoto(uuid, slotIndex, origPath)
        }
        mainViewModel.onLoadSlotPhotoUuids = { slotIndex ->
            localPhotoDao.getBySlotIndex(slotIndex).map { it.uuid }
        }
        mainViewModel.onDeleteSlotPhotos = { slotIndex ->
            val photos = localPhotoDao.getBySlotIndex(slotIndex)
            photos.forEach { com.xelth.eckwms_movfast.utils.SettingsManager.deletePhoto(it.uuid) }
            localPhotoDao.deleteBySlotIndex(slotIndex)
        }
        mainViewModel.onBindSlotPhotos = { slotIndex, receiverId ->
            localPhotoDao.bindSlotPhotos(slotIndex, receiverId)
        }
        // Device Check persistence
        mainViewModel.onSaveDeviceCheckSlots = { slots ->
            com.xelth.eckwms_movfast.utils.SettingsManager.saveDeviceCheckSlots(slots)
        }
        mainViewModel.onLoadDeviceCheckSlots = {
            com.xelth.eckwms_movfast.utils.SettingsManager.loadDeviceCheckSlots()
        }
        // Item photo persistence (global, by internal ID)
        // Internal ID prefix defines type: i=item, b=box, p=place, l=label
        mainViewModel.onSaveItemPhoto = { internalId, bitmap ->
            com.xelth.eckwms_movfast.utils.SettingsManager.saveItemPhoto(internalId, bitmap)
        }
        val repo = com.xelth.eckwms_movfast.data.WarehouseRepository.getInstance(context)
        mainViewModel.onLoadItemPhoto = { internalId ->
            // 1. Try Local Disk (High Resolution, User Generated)
            var bitmap = com.xelth.eckwms_movfast.utils.SettingsManager.loadItemPhoto(internalId)
            // 2. Fallback to Database (Synced Avatars)
            if (bitmap == null) {
                bitmap = repo.getAvatarForEntity(internalId)
            }
            bitmap
        }
    }

    // Wire shipment fetching callback
    LaunchedEffect(Unit) {
        val scanApiService = com.xelth.eckwms_movfast.api.ScanApiService(context)
        mainViewModel.onFetchShipments = { limit -> scanApiService.getShipments(limit) }
        mainViewModel.onFetchDestinations = { q, city, ai -> scanApiService.fetchDestinations(q, city, ai) }
        mainViewModel.onInventorySubmit = { target, event, data ->
            scanApiService.sendRepairEvent(target, event, data)
        }
        mainViewModel.onFetchLocationContents = { locationId ->
            scanApiService.fetchExplorerData("/api/explorer/locations/$locationId/contents")
        }
        // Multi-user callbacks
        mainViewModel.onFetchUsers = { scanApiService.fetchActiveUsers() }
        mainViewModel.onVerifyPin = { userId, pin -> scanApiService.verifyUserPin(userId, pin) }
        // Restore saved user + load user list from server
        com.xelth.eckwms_movfast.ui.viewmodels.UserManager.restoreFromSettings()
        mainViewModel.loadAvailableUsers()
    }

    // Wire Fat Client offline lookup callbacks
    LaunchedEffect(Unit) {
        val repo = com.xelth.eckwms_movfast.data.WarehouseRepository.getInstance(context)
        mainViewModel.onLookupProduct = { barcode -> repo.getLocalProduct(barcode) }
        mainViewModel.onLookupLocation = { barcode -> repo.getLocalLocation(barcode) }
        mainViewModel.onUpdateProductQty = { barcode, qty -> repo.updateLocalProductQty(barcode, qty) }
        mainViewModel.onSaveInventoryRecords = { loc, records -> repo.saveInventoryRecords(loc, records) }
        mainViewModel.onLoadInventoryRecords = { loc -> repo.getInventoryRecords(loc) }

        // Wire haptic feedback through SunlightModeManager (replaces raw vibrator)
        mainViewModel.onLongVibrate = { com.xelth.eckwms_movfast.utils.SunlightModeManager.playAttention() }
        mainViewModel.onHapticSuccess = { com.xelth.eckwms_movfast.utils.SunlightModeManager.playSuccess() }
        mainViewModel.onHapticError = { com.xelth.eckwms_movfast.utils.SunlightModeManager.playError() }
        mainViewModel.onHapticAttention = { com.xelth.eckwms_movfast.utils.SunlightModeManager.playAttention() }
    }

    // Load receiving workflow JSON
    LaunchedEffect(Unit) {
        mainViewModel.loadReceivingWorkflow(context)
    }

    // Receiving mode: camera navigation
    val receivingCameraNav by mainViewModel.receivingCameraNav.observeAsState(null)
    LaunchedEffect(receivingCameraNav) {
        val scanMode = receivingCameraNav
        if (scanMode != null) {
            android.util.Log.e("NAV_CAMERA", ">>> receivingCameraNav: $scanMode")
            mainViewModel.consumeReceivingCameraNav()
            navController.navigate("cameraScanScreen?scan_mode=$scanMode")
        }
    }

    // Bridge: forward scanner results — device check auto-enter takes priority
    val scannedBarcode by viewModel.scannedBarcode.observeAsState(null)
    LaunchedEffect(scannedBarcode) {
        if (scannedBarcode != null) {
            // Haptic: confirm scan received (two quick ticks)
            com.xelth.eckwms_movfast.utils.SunlightModeManager.playSuccess()

            // Cross-mode auto-enter: device check takes priority over ALL modes
            if (mainViewModel.checkDeviceCheckAutoEnter(scannedBarcode!!)) {
                viewModel.consumeScannedBarcode()
                return@LaunchedEffect
            }
            // Then existing mode routing
            if (isRestockMode) {
                mainViewModel.onRestockScan(scannedBarcode!!)
            } else if (isInventoryMode) {
                mainViewModel.onInventoryScan(scannedBarcode!!, viewModel.lastScanWasEncrypted)
            } else if (isReceivingMode) {
                mainViewModel.onReceivingScan(scannedBarcode!!)
            } else if (isDeviceCheckMode) {
                mainViewModel.onDeviceCheckScan(scannedBarcode!!)
            } else if (isTripMode) {
                // Phase 4 will bind this as a vehicle link-barcode; for now log it
                mainViewModel.addLog("🚗 Fahrzeug-Barcode: ${scannedBarcode}")
            } else {
                mainViewModel.onRepairScan(scannedBarcode!!)
            }
            viewModel.consumeScannedBarcode()
        }
    }

    // Bridge: forward repair photo from ScanRecoveryViewModel to MainScreenViewModel
    val repairPhoto by viewModel.repairPhotoBitmap.observeAsState(null)
    LaunchedEffect(repairPhoto, isRepairMode, isReceivingMode, isDeviceCheckMode, isInventoryMode) {
        if (repairPhoto != null) {
            if (isInventoryMode) {
                mainViewModel.onInventoryPhotoCaptured(repairPhoto!!)
            } else if (isReceivingMode) {
                mainViewModel.onReceivingPhotoCaptured(repairPhoto!!)
            } else if (isDeviceCheckMode) {
                mainViewModel.onDeviceCheckPhotoCaptured(repairPhoto!!)
            } else if (isRepairMode) {
                mainViewModel.onRepairPhotoCaptured(repairPhoto!!)
            }
            viewModel.consumeRepairPhotoBitmap()
        }
    }

    // Auto-navigate to camera after slot binding
    val shouldNavigateToCamera by mainViewModel.navigateToCamera.observeAsState(false)
    LaunchedEffect(shouldNavigateToCamera) {
        if (shouldNavigateToCamera) {
            android.util.Log.e("NAV_CAMERA", ">>> navigateToCamera TRIGGERED (mode=inventory=${mainViewModel.isInventoryMode.value})")
            mainViewModel.consumeNavigateToCamera()
            navController.navigate("cameraScanScreen?scan_mode=workflow_capture")
        }
    }

    // No Scaffold, no TopBar — edge-to-edge layout, ignore nav bar insets
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight
        val gridRowCount by mainViewModel.gridRowCount.observeAsState(7)

        // Recalculate grid when dimensions or settings change
        LaunchedEffect(containerWidth, containerHeight, mainViewModel.isLeftHanded.value, gridRowCount) {
            if (containerWidth > 0.dp && containerHeight > 0.dp) {
                mainViewModel.updateLayoutDimensions(containerWidth, containerHeight, density)
            }
        }

        val gridConfig = mainViewModel.gridConfig
        val gridHeight = mainViewModel.gridTotalHeight
        // Console gets whatever is left above the grid
        val consoleHeight = (containerHeight - gridHeight).coerceAtLeast(60.dp)

        // Console extends 1/4 button height under the grid to fill hex gaps
        val overlap = gridConfig.cellHeight / 4
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Console / Repair Status — extends slightly under grid
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(consoleHeight + overlap)
                    .align(Alignment.TopCenter)
                    .background(Color.Black)
            ) {
                if (isRepairMode) {
                    val activeSlotPhoto by mainViewModel.activeSlotPhoto.observeAsState(null)
                    val activeSlotAction = mainViewModel.getActiveSlotAction()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(consoleHeight + overlap)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    if (activeSlotAction != null) {
                                        slotMenuAction = activeSlotAction
                                        showSlotMenu = true
                                    }
                                }
                            )
                    ) {
                        if (activeSlotPhoto != null) {
                            Image(
                                bitmap = activeSlotPhoto!!.asImageBitmap(),
                                contentDescription = "Device photo",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(0.4f),
                                contentScale = ContentScale.Crop
                            )
                        }

                        // Overlay with history, thumbnails, status
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .padding(bottom = overlap),
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            // History items (last 3)
                            activeSlotHistory.takeLast(3).forEach { msg ->
                                Text(
                                    text = msg,
                                    color = Color(0xFFE0E0E0),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .padding(bottom = 4.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }

                            // Thumbnails row
                            if (activeSlotPhotosList.isNotEmpty()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(bottom = 8.dp)
                                ) {
                                    activeSlotPhotosList.take(4).forEach { bmp ->
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = "Thumbnail",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(50.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color.Black)
                                                .clickable { fullScreenPhoto = bmp }
                                        )
                                    }
                                    if (activeSlotPhotosList.size > 4) {
                                        Box(
                                            modifier = Modifier
                                                .size(50.dp)
                                                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("+${activeSlotPhotosList.size - 4}", color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            // Current status
                            Text(
                                text = repairStatus,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(Color(0xFF4CAF50).copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                } else if (isDeviceCheckMode) {
                    val activeSlotPhoto by mainViewModel.activeSlotPhoto.observeAsState(null)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(consoleHeight + overlap)
                    ) {
                        if (activeSlotPhoto != null) {
                            Image(
                                bitmap = activeSlotPhoto!!.asImageBitmap(),
                                contentDescription = "Device check photo",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(0.2f),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = deviceCheckStatus,
                                color = Color(0xFF4CAF50),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else if (isRestockMode) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(consoleHeight + overlap)
                            .background(Color.Black)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val status by mainViewModel.restockStatus.observeAsState("Restock Mode")
                            Text(
                                text = status,
                                color = Color(0xFF00BCD4),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        ConsoleView(
                            logs = consoleLogs,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(bottom = 8.dp),
                            scannerEnabled = false,
                            onScannerToggle = {}
                        )
                    }
                } else if (isInventoryMode) {
                    val inventoryItemPhoto by mainViewModel.inventoryItemPhoto.observeAsState(null)
                    val inventoryLocationPhoto by mainViewModel.inventoryLocationPhoto.observeAsState(null)
                    // Show location photo when place is last scanned, item photo otherwise
                    val displayPhoto = inventoryLocationPhoto ?: inventoryItemPhoto
                    val canTakePhoto = mainViewModel.canTakeInventoryPhoto()

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(consoleHeight + overlap)
                            .background(Color.Black)
                    ) {
                        // Background photo - tap to take new photo
                        if (displayPhoto != null) {
                            Image(
                                bitmap = displayPhoto.asImageBitmap(),
                                contentDescription = "Photo",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(0.25f)
                                    .focusProperties { canFocus = false }
                                    .clickable(enabled = canTakePhoto) {
                                        android.util.Log.e("NAV_CAMERA", ">>> PHOTO_BG tapped")
                                        navController.navigate("cameraScanScreen?scan_mode=workflow_capture")
                                    },
                                contentScale = ContentScale.Crop
                            )
                        }
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Status bar - tap to take photo if possible
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .focusProperties { canFocus = false }
                                    .clickable(enabled = canTakePhoto) {
                                        android.util.Log.e("NAV_CAMERA", ">>> STATUS_BAR tapped")
                                        navController.navigate("cameraScanScreen?scan_mode=workflow_capture")
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                val status by mainViewModel.inventoryStatus.observeAsState("Inventory Mode")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = status,
                                        color = Color(0xFFFFC107),  // Amber
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (canTakePhoto) {
                                        Text(
                                            text = " 📷",
                                            color = Color(0xFF9C27B0),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                }
                            }
                            ConsoleView(
                                logs = consoleLogs,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(bottom = 8.dp),
                                scannerEnabled = false,
                                onScannerToggle = {}
                            )
                        }
                    }
                } else if (isReceivingMode) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(consoleHeight + overlap)
                            .background(Color.Black)
                    ) {
                        // Status bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = receivingStatus,
                                color = Color(0xFFFF9800),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        // Console logs below status
                        ConsoleView(
                            logs = consoleLogs,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(bottom = 8.dp),
                            scannerEnabled = false,
                            onScannerToggle = {}
                        )
                    }
                } else if (isTripMode) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(consoleHeight + overlap)
                            .background(Color.Black)
                    ) {
                        // Thin status header (top)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tripStatus,
                                color = Color(0xFF4CAF50),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        // The ecKasse-style interactive console (interlocking
                        // rows, auto-scroll, bottom → up). Generalized component.
                        ConsoleList(
                            rows = tripDestinations.map { dest ->
                                ConsoleRow(
                                    id = dest.purposeRef,
                                    primary = dest.label,
                                    secondary = listOfNotNull(
                                        dest.address?.takeIf { it.isNotBlank() },
                                        dest.city?.takeIf { it.isNotBlank() }
                                    ).joinToString(" · "),
                                    trailing = "🚗",
                                    onClick = { tripStart(dest.purposeRef, dest.label, "planned") }
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(vertical = 4.dp),
                            emptyHint = "Stadt wählen oder Ziel tippen/🎤"
                        )
                        // Smart Gemini fallback — only when local search missed
                        // (corrects mis-heard queries: "treutlingen" → "Reutlingen")
                        if (tripDestinations.isEmpty() && tripQuery.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF4A148C))
                                    .clickable { mainViewModel.aiSearchTripDestinations(tripQuery.trim()) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "🤖 Mit KI suchen „${tripQuery.trim()}“",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        // Start by a dictated/typed address (no specific client)
                        if (tripQuery.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF1B5E20))
                                    .clickable { tripStart(null, tripQuery.trim(), "text") }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "🚗 Mit „${tripQuery.trim()}“ fahren",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        // INPUT = the freshest, BOTTOM-most console line
                        OutlinedTextField(
                            value = tripQuery,
                            onValueChange = { tripQuery = it },
                            placeholder = {
                                Text(
                                    if (tripListening) "🎤 hört zu…" else "› Ziel tippen oder 🎤 halten…",
                                    color = Color.Gray, fontSize = 13.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Color(0xFF00FF00),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        )
                    }
                } else {
                    // Idle / Smart Context mode
                    val smartStatus by mainViewModel.smartStatus.observeAsState("")
                    val smartPhoto by mainViewModel.activeSlotPhoto.observeAsState(null)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(consoleHeight)
                    ) {
                        if (smartPhoto != null) {
                            Image(
                                bitmap = smartPhoto!!.asImageBitmap(),
                                contentDescription = "Smart context photo",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(0.15f),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (smartStatus.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = smartStatus,
                                        color = Color(0xFF4CAF50),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            ConsoleView(
                                logs = consoleLogs,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(bottom = 8.dp),
                                scannerEnabled = scannerEnabled,
                                onScannerToggle = { mainViewModel.toggleScanner() }
                            )
                        }
                    }
                }
            }

            // Grid area — transparent background, hex gaps show console beneath
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(gridHeight)
                    .align(Alignment.BottomCenter)
            ) {
                SelectionAreaSheet(
                    renderCells = renderCells,
                    cellWidth = gridConfig.cellWidth,
                    cellHeight = gridConfig.cellHeight,
                    buttonGap = gridConfig.buttonGap,
                    networkState = networkHealthState,
                    regStatus = deviceRegistrationStatus,
                    currentUser = currentUserState,
                    viewingUser = viewingUserState,
                    exitButton = exitButtonState,
                    onButtonClick = { action ->
                        val result = mainViewModel.onButtonClick(action)
                        android.util.Log.e("NAV_CAMERA", ">>> onButtonClick action=$action result=$result")
                        when (result) {
                            "capture_photo" -> {
                                android.util.Log.e("NAV_CAMERA", ">>> BUTTON→CAMERA (photo) action=$action")
                                navController.navigate("cameraScanScreen?scan_mode=workflow_capture")
                            }
                            "capture_barcode" -> {
                                android.util.Log.e("NAV_CAMERA", ">>> BUTTON→CAMERA (barcode) action=$action")
                                navController.navigate("cameraScanScreen?scan_mode=barcode")
                            }
                            "navigate_scan" -> navController.navigate("scanScreen")
                            "navigate_ai" -> navController.navigate("scanScreen")
                            "navigate_settings" -> navController.navigate("settings")
                            "navigate_qc" -> navController.navigate("qcScreen")
                            "navigate_explorer" -> navController.navigate("explorerScreen")
                            "navigate_picking" -> navController.navigate("pickingList")
                            "navigate_pos" -> navController.navigate("pos")
                            "trip_open_start" -> navController.navigate("tripsScreen")
                            "trip_toggle_autodetect" -> {
                                val tm = com.xelth.eckwms_movfast.trips.TripManager
                                if (mainViewModel.tripAutoDetect.value == true) {
                                    tm.disableAutoDetect(context)
                                    mainViewModel.setTripAutoDetect(false)
                                } else if (tm.hasActivityPermission(context)) {
                                    mainViewModel.setTripAutoDetect(tm.enableAutoDetect(context))
                                } else {
                                    tripAutoPermLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                                }
                            }
                        }
                    },
                    onButtonLongClick = { action ->
                        val result = mainViewModel.onButtonLongClick(action)
                        when (result) {
                            "handled" -> { /* Already handled by ViewModel (e.g. user button) */ }
                            "capture_photo_continuous" -> {
                                navController.navigate("cameraScanScreen?scan_mode=workflow_capture_continuous")
                            }
                            "capture_barcode_continuous" -> {
                                navController.navigate("cameraScanScreen?scan_mode=barcode_continuous")
                            }
                            else -> {
                                // Intake sheet for repair slots only
                                if (action.startsWith("slot_")) {
                                    intakeLongPressAction = action
                                    intakeFormState.clear()
                                    try {
                                        val inputStream = context.assets.open("workflows/device_intake.json")
                                        val json = inputStream.bufferedReader().use { it.readText() }
                                        intakeConfigJson = json
                                        showIntakeSheet = true
                                    } catch (e: Exception) {
                                        mainViewModel.addLog("Error loading intake config: ${e.message}")
                                    }
                                }
                            }
                        }
                    },
                    onNetworkIndicatorClick = {
                        showNetworkPanel = true
                    },
                    onNetworkIndicatorLongClick = {
                        navController.navigate("pairingScreen")
                    },
                    // 🎤 PUSH-TO-TALK in the grid (under Photo/Scan): hold to talk
                    onMicPress = {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            tripListening = true
                            speech.start { text, _ -> tripQuery = text }
                        } else {
                            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onMicRelease = {
                        speech.stop()
                        tripListening = false
                    }
                )
            }
        }
    }

    // Network Status Bottom Sheet
    if (showNetworkPanel) {
        NetworkPanelSheet(
            networkHealthState = networkHealthState,
            deviceRegistrationStatus = deviceRegistrationStatus,
            onDismiss = { showNetworkPanel = false },
            onRefresh = { viewModel.triggerManualHealthCheck() },
            onRePair = {
                showNetworkPanel = false
                navController.navigate("pairingScreen")
            }
        )
    }

    // Intake ModalBottomSheet
    if (showIntakeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showIntakeSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 20.dp)
            ) {
                DynamicUiRenderer(
                    layoutJson = intakeConfigJson,
                    stateValues = intakeFormState.toMap(),
                    onValueChange = { key, value ->
                        intakeFormState[key] = value
                    },
                    onAction = { action, _ ->
                        mainViewModel.addLog("Intake action: $action ($intakeLongPressAction)")
                        when (action) {
                            "save_intake_details" -> {
                                val jsonPayload = org.json.JSONObject(intakeFormState as Map<*, *>).toString(2)
                                mainViewModel.addLog("--- Intake Payload ---")
                                mainViewModel.addLog(jsonPayload)
                                mainViewModel.addLog("--- End Payload ---")
                                viewModel.sendRepairEvent(
                                    intakeLongPressAction,
                                    "intake_save",
                                    org.json.JSONObject(intakeFormState as Map<*, *>).toString()
                                )
                                showIntakeSheet = false
                            }
                            "capture_evidence_box", "capture_evidence_device" -> {
                                showIntakeSheet = false
                                navController.navigate("cameraScanScreen?scan_mode=workflow_capture")
                            }
                            "scan_link_hwb", "scan_link_sn" -> {
                                showIntakeSheet = false
                                navController.navigate("cameraScanScreen?scan_mode=barcode")
                            }
                        }
                    }
                )
            }
        }
    }

    // Shipment Picker BottomSheet (swipeable list)
    val showShipmentPicker by mainViewModel.showShipmentPicker.observeAsState(false)
    if (showShipmentPicker) {
        ModalBottomSheet(
            onDismissRequest = { mainViewModel.dismissShipmentPicker() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            // Observe shipment loading state to trigger recomposition
            val shipmentsLoaded by mainViewModel.shipmentsLoaded.observeAsState(false)
            val shipmentsError by mainViewModel.shipmentsError.observeAsState(null)
            val shipments = remember(shipmentsLoaded) { mainViewModel.getShipmentDisplayList() }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 20.dp)
            ) {
                // Header
                Text(
                    text = "Select Shipment  (long-press = manual)",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f))

                if (shipmentsError != null) {
                    // Show error message
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("⚠️ Error loading shipments", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(shipmentsError!!, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (shipments.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No shipments available", color = Color.Gray)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(shipments) { item ->
                            val bgColor = when {
                                item.isMatched -> Color(0xFF1B5E20) // dark green for auto-match
                                item.status == "delivered" || item.status == "cancelled" -> Color(0xFF212121)
                                else -> Color(0xFF2E2E2E)
                            }
                            val statusIcon = when (item.status) {
                                "delivered" -> "✅"
                                "cancelled" -> "❌"
                                "error" -> "⚠️"
                                "shipped" -> "\uD83D\uDE9A"
                                else -> "\uD83D\uDCE6"
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            mainViewModel.selectShipment(item.id)
                                        },
                                        onLongClick = {
                                            mainViewModel.openManualClientEntry()
                                        }
                                    ),
                                colors = CardDefaults.cardColors(containerColor = bgColor)
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                    // Line 1: icon + tracking | product type | date
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "$statusIcon ${item.trackingNumber}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (item.productType.isNotEmpty()) {
                                            Text(
                                                text = item.productType,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFFFF9800),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    // Line 2: Client (smart-detected) bold
                                    Text(
                                        text = item.clientName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFE0E0E0)
                                    )
                                    if (item.clientAddress.isNotEmpty()) {
                                        Text(
                                            text = item.clientAddress,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF999999)
                                        )
                                    }
                                    // Line 4: status | received by | date | MATCH
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        val infoLeft = buildString {
                                            append(item.status)
                                            if (item.receivedBy.isNotEmpty()) append(" | ${item.receivedBy}")
                                        }
                                        Text(
                                            text = infoLeft,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.Gray
                                        )
                                        Row {
                                            if (item.isMatched) {
                                                Text(
                                                    text = "MATCH ",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF4CAF50),
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Text(
                                                text = item.date,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFFAAAAAA)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Receiving Workflow Step Modal (manual entry or device info)
    if (receivingModalJson != null) {
        LaunchedEffect(Unit) {
            mainViewModel.receivingData.forEach { (k, v) ->
                if (v is String) receivingModalFormState.putIfAbsent(k, v)
            }
        }

        ModalBottomSheet(
            onDismissRequest = { mainViewModel.dismissReceivingModal() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 20.dp)
            ) {
                DynamicUiRenderer(
                    layoutJson = receivingModalJson!!,
                    stateValues = receivingModalFormState.toMap(),
                    onValueChange = { key, value ->
                        receivingModalFormState[key] = value
                    },
                    onAction = { action, _ ->
                        mainViewModel.addLog("Receiving modal: $action")
                        when (action) {
                            "close_modal" -> {
                                receivingModalFormState.forEach { (k, v) ->
                                    mainViewModel.setReceivingDataValue(k, v)
                                }
                                mainViewModel.dismissReceivingModal()
                                mainViewModel.advanceToNextStep()
                                receivingModalFormState.clear()
                            }
                            "scan_serial" -> {
                                mainViewModel.dismissReceivingModal()
                                navController.navigate("cameraScanScreen?scan_mode=barcode")
                            }
                        }
                    }
                )
            }
        }
    }

    // Slot long-press menu: delete + change background from photo avatars
    if (showSlotMenu) {
        val slotBarcode = mainViewModel.getSlotBarcode(slotMenuAction)
        val slotPhotos = remember(slotMenuAction) { mainViewModel.getActiveSlotPhotos() }
        var showDeleteConfirm by remember { mutableStateOf(false) }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                containerColor = Color(0xFF1E1E1E),
                titleContentColor = Color.White,
                textContentColor = Color.White,
                title = { Text("Delete Slot?") },
                text = { Text("Unbind device $slotBarcode and clear all data?") },
                confirmButton = {
                    TextButton(onClick = {
                        mainViewModel.deleteSlot(slotMenuAction)
                        showDeleteConfirm = false
                        showSlotMenu = false
                    }) { Text("Delete", color = Color.Red) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", color = Color.White) }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { showSlotMenu = false },
                containerColor = Color(0xFF1E1E1E),
                titleContentColor = Color.White,
                textContentColor = Color.White,
                title = { Text("Slot: ${slotBarcode.takeLast(10)}") },
                text = {
                    Column {
                        // Delete button
                        TextButton(onClick = { showDeleteConfirm = true }) {
                            Text("Delete Slot", color = Color.Red)
                        }
                        // Change background section
                        if (slotPhotos.size > 1) {
                            HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f))
                            Text(
                                "Change Background:",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                            // Scrollable row of photo thumbnails
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                slotPhotos.forEachIndexed { idx, photo ->
                                    val isCurrentBg = mainViewModel.activeSlotPhoto.value == photo
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .background(
                                                if (isCurrentBg) Color(0xFF4CAF50) else Color.DarkGray,
                                                RoundedCornerShape(6.dp)
                                            )
                                            .padding(if (isCurrentBg) 2.dp else 0.dp)
                                            .combinedClickable(
                                                onClick = {
                                                    mainViewModel.changeSlotBackground(idx)
                                                    showSlotMenu = false
                                                }
                                            )
                                    ) {
                                        Image(
                                            bitmap = photo.asImageBitmap(),
                                            contentDescription = "Photo #${idx + 1}",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }
                            }
                        } else if (slotPhotos.isEmpty()) {
                            Text(
                                "No photos yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSlotMenu = false }) { Text("Close", color = Color.White) }
                }
            )
        }
    }

    // --- Multi-User Dialogs ---
    val showUserDialog by mainViewModel.showUserDialog.observeAsState(false)
    val userDialogMode by mainViewModel.userDialogMode.observeAsState("view")
    val showPinDialog by mainViewModel.showPinDialog.observeAsState(false)
    val availableUsers by com.xelth.eckwms_movfast.ui.viewmodels.UserManager.availableUsers.collectAsState()

    // User Selection Dialog
    if (showUserDialog) {
        AlertDialog(
            onDismissRequest = { mainViewModel.dismissUserDialog() },
            containerColor = Color(0xFF1E1E1E),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = {
                Text(if (userDialogMode == "login") "Anmeldung" else "View User")
            },
            text = {
                if (availableUsers.isEmpty()) {
                    Text("No users available. Check server connection.", color = Color.Gray)
                } else {
                    LazyColumn {
                        items(availableUsers) { user ->
                            val isCurrent = currentUserState?.id == user.id
                            val isViewing = viewingUserState?.id == user.id
                            val bgColor = when {
                                isCurrent && isViewing -> Color(0xFF1B5E20).copy(alpha = 0.3f)
                                isViewing -> Color(0xFFFDD835).copy(alpha = 0.2f)
                                else -> Color.Transparent
                            }
                            TextButton(
                                onClick = { mainViewModel.onUserSelected(user) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bgColor)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        user.getDialogName(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color.White,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (isCurrent) {
                                        Text(
                                            "current",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF4CAF50)
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { mainViewModel.dismissUserDialog() }) { Text("Cancel", color = Color.White) }
            }
        )
    }

    // PIN Input Dialog
    if (showPinDialog) {
        var pinValue by remember { mutableStateOf("") }
        var pinError by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { mainViewModel.dismissPinDialog() },
            containerColor = Color(0xFF1E1E1E),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = { Text("PIN eingeben") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // PIN display (dots)
                    Text(
                        text = "●".repeat(pinValue.length) + "○".repeat((4 - pinValue.length).coerceAtLeast(0)),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                    if (pinError) {
                        Text("Wrong PIN", color = Color.Red, style = MaterialTheme.typography.bodySmall)
                    }
                    // Numpad grid
                    val buttons = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("⌫", "0", "✓")
                    )
                    buttons.forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            row.forEach { digit ->
                                TextButton(
                                    onClick = {
                                        pinError = false
                                        when (digit) {
                                            "⌫" -> if (pinValue.isNotEmpty()) pinValue = pinValue.dropLast(1)
                                            "✓" -> if (pinValue.length == 4) mainViewModel.onPinSubmitted(pinValue)
                                            else -> if (pinValue.length < 4) pinValue += digit
                                        }
                                    },
                                    modifier = Modifier.size(64.dp)
                                ) {
                                    Text(digit, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { mainViewModel.dismissPinDialog() }) { Text("Cancel", color = Color.White) }
            }
        )
    }

    // Full-screen photo overlay
    if (fullScreenPhoto != null) {
        Dialog(
            onDismissRequest = { fullScreenPhoto = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
                    .clickable { fullScreenPhoto = null },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = fullScreenPhoto!!.asImageBitmap(),
                    contentDescription = "Full screen",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }

    // Trip mode: start-odometer dialog (after starting a trip from a ticket)
    if (tripOdometerStart) {
        com.xelth.eckwms_movfast.ui.screens.components.OdometerDialog(
            isStart = true,
            onDismiss = { tripOdometerStart = false },
            onSave = { km, source, photoId ->
                tripScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val db = com.xelth.eckwms_movfast.data.local.AppDatabase.getInstance(context)
                    val target = db.tripDao().getOpenTrip()?.id
                    if (target != null) {
                        db.tripDao().setStartOdometer(target, km, source, photoId)
                        com.xelth.eckwms_movfast.trips.TripManager.publishActiveTrip(db.tripDao().getOpenTrip())
                    }
                }
                tripOdometerStart = false
            }
        )
    }
}

