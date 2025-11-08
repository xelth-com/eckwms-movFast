package com.xelth.eckwms_movfast

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.xelth.eckwms_movfast.ui.screens.CameraScanScreen
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
                        CameraScanScreen(
                            navController = navController,
                            scanMode = scanMode
                        )
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
}
