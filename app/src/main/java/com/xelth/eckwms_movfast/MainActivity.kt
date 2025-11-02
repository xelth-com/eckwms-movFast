package com.xelth.eckwms_movfast

import android.content.Intent
import android.os.Bundle
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
                                viewModel.handleScannedData(barcode, type)
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
                        }

                        ScanScreen(
                            viewModel = viewModel,
                            navController = navController,
                            onNavigateToSettings = {
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
}
