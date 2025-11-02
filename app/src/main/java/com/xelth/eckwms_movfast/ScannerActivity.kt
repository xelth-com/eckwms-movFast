// app/src/main/java/com/xelth/eckwms_movfast/ScannerActivity.kt
package com.xelth.eckwms_movfast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import androidx.compose.runtime.LaunchedEffect
import com.xelth.eckwms_movfast.scanners.ScannerManager
import com.xelth.eckwms_movfast.ui.screens.CameraScanScreen
import com.xelth.eckwms_movfast.ui.screens.ScannedImageScreen
import com.xelth.eckwms_movfast.ui.screens.ScannerSettingsScreen
import com.xelth.eckwms_movfast.ui.theme.EckwmsmovFastTheme
import com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel
import com.xelth.eckwms_movfast.utils.BitmapCache

class ScannerActivity : ComponentActivity() {
    // Ссылка на ScannerManager
    private lateinit var scannerManager: ScannerManager

    private val viewModel: ScanRecoveryViewModel by viewModels {
        ScanRecoveryViewModel.Companion.Factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Получение ScannerManager из приложения
        scannerManager = (application as EckwmsApp).scannerManager

        setContent {
            EckwmsmovFastTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = "settings"
                    ) {
                        composable("settings") { backStackEntry ->
                            // Listen for results from camera
                            val savedStateHandle = backStackEntry.savedStateHandle
                            LaunchedEffect(savedStateHandle) {
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

                            ScannerSettingsScreen(
                                scannerManager = scannerManager,
                                viewModel = viewModel,
                                onNavigateBack = { finish() },
                                onOpenImageViewer = { navController.navigate("image_viewer") },
                                onNavigateToCamera = { scanMode ->
                                    navController.navigate("cameraScanScreen?scan_mode=$scanMode")
                                }
                            )
                        }

                        composable("image_viewer") {
                            ScannedImageScreen(
                                scannerManager = scannerManager,
                                onNavigateBack = { navController.popBackStack() }
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
}
