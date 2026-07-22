package com.xelth.eckwms_movfast

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.xelth.eckwms_movfast.ui.screens.CameraScanScreen
import com.xelth.eckwms_movfast.ui.screens.CrmEntityScreen
import com.xelth.eckwms_movfast.ui.screens.ImageViewerScreen
import com.xelth.eckwms_movfast.ui.screens.MainScreen
import com.xelth.eckwms_movfast.ui.screens.RestockScreen
import com.xelth.eckwms_movfast.ui.screens.ScanScreen
import com.xelth.eckwms_movfast.ui.screens.DatabaseViewerScreen
import com.xelth.eckwms_movfast.ui.screens.QcScreen
import com.xelth.eckwms_movfast.ui.screens.ExplorerScreen
import com.xelth.eckwms_movfast.ui.screens.PickingListScreen
import com.xelth.eckwms_movfast.ui.screens.PickingExecuteScreen
import com.xelth.eckwms_movfast.ui.screens.ScannerSettingsScreen
import com.xelth.eckwms_movfast.ui.screens.WarehouseMapScreen
import com.xelth.eckwms_movfast.ui.theme.EckwmsmovFastTheme
import com.xelth.eckwms_movfast.ui.viewmodels.PickingViewModel
import com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel
import com.xelth.eckwms_movfast.utils.BitmapCache
import com.xelth.eckwms_movfast.utils.SettingsManager
import com.xelth.eckwms_movfast.utils.SunlightModeManager

class MainActivity : ComponentActivity() {

    private val viewModel: ScanRecoveryViewModel by viewModels {
        ScanRecoveryViewModel.Companion.Factory(application)
    }

    private val pickingViewModel: PickingViewModel by viewModels()

    // Graceful RECORD_AUDIO permission request (for AdaptiveAudioManager)
    private val micPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        android.util.Log.d("MainActivity", "RECORD_AUDIO permission: $granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Two-press wake-then-scan (2026-07-21): press 1 wakes the device (short
        // vibration acks it, no laser — the wake key is consumed by the system),
        // press 2 scans. Showing over the NON-secure keyguard (swipe, password
        // quality 0 — bypasses no password) lands the worker straight in the app
        // after the wake so press 2 scans immediately, no swiping.
        // NOTE: a SECURE PIN/pattern can never be bypassed by an app; requestDismissKeyguard
        // would just surface the unlock prompt. Nothing here weakens a real lock.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        (getSystemService(android.app.KeyguardManager::class.java))
            ?.requestDismissKeyguard(this, null)

        // Hide gesture indicator bar — maximize usable screen area
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.hide(android.view.WindowInsets.Type.navigationBars())
            window.insetsController?.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }

        val composeView = androidx.compose.ui.platform.ComposeView(this)
        composeView.setContent {
            val isSunlightMode by SunlightModeManager.isSunlightMode.collectAsState()
            EckwmsmovFastTheme(highContrast = isSunlightMode) {
                val navController = rememberNavController()

                // Global navigation command observer - works on all screens
                val navCommand by viewModel.navigationCommand.observeAsState(com.xelth.eckwms_movfast.ui.viewmodels.NavigationCommand.NONE)
                LaunchedEffect(navCommand) {
                    android.util.Log.e("AUTO_PAIR", "=== GLOBAL LaunchedEffect triggered ===")
                    android.util.Log.e("AUTO_PAIR", "navCommand: $navCommand")
                    when (navCommand) {
                        com.xelth.eckwms_movfast.ui.viewmodels.NavigationCommand.TO_PAIRING -> {
                            // Pairing now runs in place — the dedicated Pairing Console
                            // screen was removed; feedback streams into the main hex
                            // console and status shows on the network half-button. Kept
                            // as a harmless no-op for any legacy posters of this command.
                            viewModel.resetNavigationCommand()
                        }
                        com.xelth.eckwms_movfast.ui.viewmodels.NavigationCommand.BACK -> {
                            android.util.Log.e("AUTO_PAIR", "Executing BACK - calling popBackStack()")
                            android.util.Log.e("AUTO_PAIR", "Current backstack: ${navController.currentBackStackEntry?.destination?.route}")
                            val result = navController.popBackStack()
                            android.util.Log.e("AUTO_PAIR", "popBackStack result: $result")
                            android.util.Log.e("AUTO_PAIR", "New destination: ${navController.currentBackStackEntry?.destination?.route}")
                            viewModel.resetNavigationCommand()
                            android.util.Log.e("AUTO_PAIR", "Navigation command reset")
                        }
                        com.xelth.eckwms_movfast.ui.viewmodels.NavigationCommand.TO_MAIN_REPAIR -> {
                            navController.navigate("mainMenu") {
                                popUpTo("mainMenu") { inclusive = true }
                            }
                            viewModel.resetNavigationCommand()
                        }
                        com.xelth.eckwms_movfast.ui.viewmodels.NavigationCommand.TO_CRM -> {
                            val eType = viewModel.pendingCrmEntityType.value ?: ""
                            val eId = viewModel.pendingCrmEntityId.value ?: ""
                            if (eType.isNotEmpty() && eId.isNotEmpty()) {
                                navController.navigate("crmEntity/$eType/$eId")
                            }
                            viewModel.consumeCrmNavigation()
                            viewModel.resetNavigationCommand()
                        }
                        else -> {
                            android.util.Log.e("AUTO_PAIR", "Command is NONE - no action")
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = "mainMenu"
                ) {
                    composable("mainMenu") { backStackEntry ->
                        val savedStateHandle = backStackEntry.savedStateHandle

                        // Handle workflow image capture returning to main menu (Repair Mode photo)
                        LaunchedEffect(savedStateHandle) {
                            savedStateHandle.get<Boolean>("captured_workflow_image")?.let { success ->
                                if (success) {
                                    val bitmap = BitmapCache.getCapturedImage()
                                    if (bitmap != null) {
                                        // Copy before clearing cache — cache may recycle the original
                                        val copy = bitmap.copy(bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
                                        BitmapCache.clearCapturedImage()
                                        viewModel.setRepairPhotoBitmap(copy)
                                    }
                                }
                                savedStateHandle.remove<Boolean>("captured_workflow_image")
                            }

                            // Handle camera barcode scan results (same path as hardware scanner)
                            savedStateHandle.get<Map<String, String>>("scanned_barcode_data")?.let { data ->
                                val barcode = data["barcode"] ?: return@let
                                if (data["scanMode"] == "pairing") {
                                    // In-place pairing: a QR scanned via the network
                                    // half-button returns here — route it to the pairing
                                    // handler, NOT the general/item scan path.
                                    android.util.Log.d("MainMenu", "Pairing QR received: $barcode")
                                    viewModel.handlePairingQrCode(barcode)
                                } else {
                                    android.util.Log.d("MainMenu", "Camera barcode received: $barcode")
                                    viewModel.onCameraBarcode(barcode)
                                }
                                savedStateHandle.remove<Map<String, String>>("scanned_barcode_data")
                            }
                        }

                        MainScreen(navController = navController, viewModel = viewModel)
                    }

                    composable("scanScreen") { backStackEntry ->
                        // Listen for result from camera scan
                        val savedStateHandle = backStackEntry.savedStateHandle
                        LaunchedEffect(savedStateHandle) {
                            // Handle barcode scan results
                            savedStateHandle.get<Map<String, String>>("scanned_barcode_data")?.let { data ->
                                val barcode = data["barcode"] ?: return@let
                                val type = data["type"] ?: "UNKNOWN"
                                if (data["scanMode"] == "pairing") {
                                    viewModel.handlePairingQrCode(barcode)
                                } else {
                                    // Centralized scan handling in ViewModel
                                    viewModel.handleGeneralScanResult(barcode, type)
                                }
                                // Clear the result after processing
                                savedStateHandle.remove<Map<String, String>>("scanned_barcode_data")
                            }

                            // Handle single recovery image capture
                            savedStateHandle.get<Boolean>("captured_recovery_image")?.let { success ->
                                if (success) {
                                    val bitmap = BitmapCache.getCapturedImage()
                                    if (bitmap != null) {
                                        viewModel.processCapturedImageForSingleRecovery(bitmap)
                                        BitmapCache.clearCapturedImage()
                                    }
                                }
                                savedStateHandle.remove<Boolean>("captured_recovery_image")
                            }

                            // Handle multi-recovery image capture
                            savedStateHandle.get<Boolean>("captured_session_image")?.let { success ->
                                if (success) {
                                    val bitmap = BitmapCache.getCapturedImage()
                                    if (bitmap != null) {
                                        viewModel.processCapturedImageForRecoverySession(bitmap)
                                        BitmapCache.clearCapturedImage()
                                    }
                                }
                                savedStateHandle.remove<Boolean>("captured_session_image")
                            }

                            // Handle direct upload image capture
                            savedStateHandle.get<Boolean>("captured_direct_upload_image")?.let { success ->
                                if (success) {
                                    val bitmap = BitmapCache.getCapturedImage()
                                    if (bitmap != null) {
                                        viewModel.captureAndUploadImage(bitmap)
                                        BitmapCache.clearCapturedImage()
                                    }
                                }
                                savedStateHandle.remove<Boolean>("captured_direct_upload_image")
                            }

                            savedStateHandle.get<Boolean>("captured_workflow_image")?.let { success ->
                                if (success) {
                                    val bitmap = BitmapCache.getCapturedImage()
                                    if (bitmap != null) {
                                        viewModel.onImageCapturedForWorkflow(bitmap)
                                        BitmapCache.clearCapturedImage()
                                    }
                                }
                                savedStateHandle.remove<Boolean>("captured_workflow_image")
                            }
                        }

                        // Handle pairing QR code scan on scanScreen (forwarded from pairingScreen)
                        LaunchedEffect(Unit) {
                            savedStateHandle.getLiveData<String>("pairing_qr_data").observeForever { qrData ->
                                if (qrData != null) {
                                    viewModel.handlePairingQrCode(qrData)
                                    savedStateHandle.remove<String>("pairing_qr_data")
                                }
                            }
                        }

                        ScanScreen(
                            viewModel = viewModel,
                            navController = navController,
                            onNavigateToSettings = {
                                navController.navigate("settings")
                            }
                        )
                    }

                    composable("settings") {
                        ScannerSettingsScreen(
                            scannerManager = (application as EckwmsApp).scannerManager,
                            viewModel = viewModel,
                            onNavigateBack = { navController.popBackStack() },
                            onOpenImageViewer = { navController.navigate("imageViewer") },
                            onNavigateToCamera = { scanMode ->
                                navController.navigate("cameraScanScreen?scan_mode=$scanMode")
                            },
                            onNavigateToDatabase = {
                                navController.navigate("databaseViewer")
                            }
                        )
                    }

                    composable("databaseViewer") {
                        DatabaseViewerScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable("qcScreen") {
                        QcScreen(onBack = { navController.popBackStack() })
                    }

                    composable("explorerScreen") {
                        ExplorerScreen(onBack = { navController.popBackStack() })
                    }

                    composable("tripsScreen") {
                        com.xelth.eckwms_movfast.ui.screens.TripsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenMap = { navController.navigate("tripMapScreen") }
                        )
                    }

                    composable("tripMapScreen") {
                        com.xelth.eckwms_movfast.ui.screens.TripMapScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable("imageViewer") {
                        ImageViewerScreen(onBack = { navController.popBackStack() })
                    }

                    composable(
                        route = "cameraScanScreen?scan_mode={scan_mode}",
                        arguments = listOf(
                            navArgument("scan_mode") {
                                type = NavType.StringType
                                defaultValue = "barcode"
                            }
                        )
                    ) { backStackEntry ->
                        val scanMode = backStackEntry.arguments?.getString("scan_mode") ?: "barcode"
                        val savedStateHandle = backStackEntry.savedStateHandle

                        // Handle pairing mode result
                        LaunchedEffect(savedStateHandle) {
                            savedStateHandle.get<Map<String, String>>("scanned_barcode_data")?.let { data ->
                                if (scanMode == "pairing") {
                                    val barcode = data["barcode"] ?: return@let
                                    // Process pairing QR code directly
                                    viewModel.handlePairingQrCode(barcode)
                                    savedStateHandle.remove<Map<String, String>>("scanned_barcode_data")
                                    // Navigate back
                                    navController.popBackStack()
                                }
                            }
                        }

                        CameraScanScreen(
                            navController = navController,
                            scanMode = scanMode,
                            // Direct per-shot hand-off into the workflow photo
                            // queue — survives photo series (savedStateHandle
                            // would only deliver the last frame).
                            onPhotoCaptured = { bmp -> viewModel.setRepairPhotoBitmap(bmp) }
                        )
                    }


                    composable("restockScreen") {
                        RestockScreen(navController = navController)
                    }

                    composable(
                        route = "warehouseMap/{warehouseId}?target={target}",
                        arguments = listOf(
                            navArgument("warehouseId") { type = NavType.StringType },
                            navArgument("target") { type = NavType.StringType; nullable = true }
                        )
                    ) { backStackEntry ->
                        val warehouseId = backStackEntry.arguments?.getString("warehouseId") ?: "1"
                        val target = backStackEntry.arguments?.getString("target")

                        LaunchedEffect(warehouseId) {
                            viewModel.fetchAndShowMap(warehouseId, target)
                        }

                        WarehouseMapScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    // --- Picking Routes ---
                    composable("pickingList") {
                        PickingListScreen(
                            viewModel = pickingViewModel,
                            onPickingSelected = { pickingId ->
                                pickingViewModel.selectPicking(pickingId)
                                navController.navigate("pickingExecute")
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable("pickingExecute") {
                        val routeStops by pickingViewModel.routeStops.collectAsState()
                        val routePath by pickingViewModel.routePath.collectAsState()
                        val currentIndex by pickingViewModel.currentLineIndex.collectAsState()

                        // Wire hardware scanner to picking ViewModel
                        val scanResult by (application as EckwmsApp).scannerManager.scanResult.observeAsState()
                        LaunchedEffect(scanResult) {
                            scanResult?.let { barcode ->
                                pickingViewModel.processScan(barcode)
                            }
                        }

                        PickingExecuteScreen(
                            viewModel = pickingViewModel,
                            onShowMap = {
                                // Load warehouse map with route overlay
                                viewModel.fetchAndShowMap("8", null) // WH id=8
                                navController.navigate("pickingMap")
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable("pickingMap") {
                        val routeStops by pickingViewModel.routeStops.collectAsState()
                        val routePath by pickingViewModel.routePath.collectAsState()
                        val currentIndex by pickingViewModel.currentLineIndex.collectAsState()

                        WarehouseMapScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() },
                            routeStops = routeStops,
                            routePath = routePath,
                            currentStopIndex = currentIndex
                        )
                    }

                    // --- CRM Entity Route ---
                    composable(
                        route = "crmEntity/{entityType}/{entityId}",
                        arguments = listOf(
                            navArgument("entityType") { type = NavType.StringType },
                            navArgument("entityId") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        CrmEntityScreen(
                            entityType = backStackEntry.arguments?.getString("entityType") ?: "",
                            entityId = backStackEntry.arguments?.getString("entityId") ?: "",
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
        // Host Compose inside a container that catches a pre-IME key event dispatched
        // during a focus transition. A hardware-scanner / IME key arriving while the
        // Compose focus system is invalidated (e.g. right after a stocktake submit
        // recomposes) otherwise crashes in
        // FocusOwnerImpl.dispatchInterceptedSoftKeyboardEvent — a known Compose bug.
        setContentView(
            object : android.widget.FrameLayout(this) {
                override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean =
                    try {
                        super.dispatchKeyEventPreIme(event)
                    } catch (e: IllegalStateException) {
                        android.util.Log.w("MainActivity", "Swallowed Compose focus key-dispatch crash: ${e.message}")
                        false
                    }
            }.apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                addView(composeView)
            }
        )
    }

    /**
     * Intercept ALL key events BEFORE Compose processes them.
     * dispatchKeyEvent fires before onKeyDown/onKeyUp and before Compose focus handling.
     * This prevents hardware scanner's Enter key from clicking focused UI elements.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Hardware scan triggers: the PDA's two side buttons emit F10/F11 (and the
        // front trigger F8/F9) via the "xctech-key" input device (confirmed with
        // getevent). The XCheng service does NOT bind them to the scan engine, so
        // drive our SDK directly — a trigger press starts a scan and the result
        // comes back through ScannerManager.scanResult. Consume them so the raw
        // F-keys never leak into the Compose UI.
        when (event.keyCode) {
            KeyEvent.KEYCODE_F8,
            KeyEvent.KEYCODE_F9,
            KeyEvent.KEYCODE_F10,
            KeyEvent.KEYCODE_F11 -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    android.util.Log.d("ScanTrigger", "hardware key ${event.keyCode} → onScanTriggerPressed")
                    try {
                        // Single entry point: plain startScan when the engine is up,
                        // assisted resume-then-scan when it's suspended (stranded
                        // vendor suspend, or this very press just woke the device —
                        // the one-press-from-sleep path).
                        (application as EckwmsApp).scannerManager
                            .onScanTriggerPressed("key", event.keyCode)
                    } catch (e: Exception) {
                        android.util.Log.w("ScanTrigger", "trigger handling failed: ${e.message}")
                    }
                }
                return true
            }
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_SPACE -> true // Consume — never reaches Compose
            else -> super.dispatchKeyEvent(event)
        }
    }

    /**
     * Start smart status monitoring when app comes to foreground
     */
    override fun onResume() {
        super.onResume()
        viewModel.startStatusMonitoring()
        SunlightModeManager.startListening()
        // Request mic permission for adaptive audio (graceful — silently skips if denied)
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Stop status monitoring when app goes to background to save battery
     */
    override fun onPause() {
        super.onPause()
        viewModel.stopStatusMonitoring()
        SunlightModeManager.stopListening()
    }
}
