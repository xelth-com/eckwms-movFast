package com.xelth.eckwms_movfast

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.xelth.eckwms_movfast.ui.screens.CameraScanScreen
import com.xelth.eckwms_movfast.ui.screens.PairingScreen
import com.xelth.eckwms_movfast.ui.screens.RestockScreen
import com.xelth.eckwms_movfast.ui.screens.ScanScreen
import com.xelth.eckwms_movfast.ui.theme.EckwmsmovFastTheme
import com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel
import com.xelth.eckwms_movfast.utils.BitmapCache

class MainActivity : ComponentActivity() {

    private val viewModel: ScanRecoveryViewModel by viewModels {
        ScanRecoveryViewModel.Companion.Factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            EckwmsmovFastTheme {
                val navController = rememberNavController()

                // Global navigation command observer - works on all screens
                val navCommand by viewModel.navigationCommand.observeAsState(com.xelth.eckwms_movfast.ui.viewmodels.NavigationCommand.NONE)
                LaunchedEffect(navCommand) {
                    android.util.Log.e("AUTO_PAIR", "=== GLOBAL LaunchedEffect triggered ===")
                    android.util.Log.e("AUTO_PAIR", "navCommand: $navCommand")
                    when (navCommand) {
                        com.xelth.eckwms_movfast.ui.viewmodels.NavigationCommand.TO_PAIRING -> {
                            android.util.Log.e("AUTO_PAIR", "Executing TO_PAIRING - navigating to pairingScreen")
                            navController.navigate("pairingScreen")
                            android.util.Log.e("AUTO_PAIR", "Navigation to pairingScreen completed")
                            viewModel.resetNavigationCommand()
                            android.util.Log.e("AUTO_PAIR", "Navigation command reset")
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
                        else -> {
                            android.util.Log.e("AUTO_PAIR", "Command is NONE - no action")
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = "scanScreen"
                ) {
                    composable("scanScreen") { backStackEntry ->
                        // Listen for result from camera scan
                        val savedStateHandle = backStackEntry.savedStateHandle
                        LaunchedEffect(savedStateHandle) {
                            // Handle barcode scan results
                            savedStateHandle.get<Map<String, String>>("scanned_barcode_data")?.let { data ->
                                val barcode = data["barcode"] ?: return@let
                                val type = data["type"] ?: "UNKNOWN"
                                // Centralized scan handling in ViewModel
                                viewModel.handleGeneralScanResult(barcode, type)
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
                                viewModel.addLog("!!! onNavigateToSettings called !!!")
                                val intent = Intent(this@MainActivity, ScannerActivity::class.java)
                                startActivity(intent)
                            }
                        )
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
                            scanMode = scanMode
                        )
                    }

                    composable("pairingScreen") {
                        PairingScreen(
                            viewModel = viewModel,
                            navController = navController
                        )
                    }

                    composable("restockScreen") {
                        RestockScreen(navController = navController)
                    }
                }
            }
        }
    }

    /**
     * Override key events to prevent hardware scanner button from triggering UI buttons
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Log all key events for debugging
        viewModel.addLog("KeyEvent: keyCode=$keyCode, action=${event?.action}, scanCode=${event?.scanCode}")

        // Block common scanner trigger keys from propagating to UI
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_F1,
            KeyEvent.KEYCODE_F2,
            KeyEvent.KEYCODE_F3,
            KeyEvent.KEYCODE_F4,
            KeyEvent.KEYCODE_F5 -> {
                viewModel.addLog("Scanner key blocked: $keyCode")
                true // Consume the event - don't pass to UI
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /**
     * Start smart status monitoring when app comes to foreground
     */
    override fun onResume() {
        super.onResume()
        viewModel.startStatusMonitoring()
    }

    /**
     * Stop status monitoring when app goes to background to save battery
     */
    override fun onPause() {
        super.onPause()
        viewModel.stopStatusMonitoring()
    }
}
