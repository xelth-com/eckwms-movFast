package com.xelth.eckwms_movfast.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.xelth.eckwms_movfast.ui.screens.PosScreen
import com.xelth.eckwms_movfast.ui.screens.ScannerScreen

@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainScreenPlaceholder(navController = navController)
        }

        composable("scanner_screen") {
            ScannerScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onBarcodeScanned = { barcode ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("scanned_barcode", barcode)
                    navController.popBackStack()
                }
            )
        }

        composable("pos") {
            PosScreen(
                onLogout = {
                    navController.popBackStack()
                }
            )
        }
    }
}

@Composable
fun MainScreenPlaceholder(navController: NavHostController) {
    // Placeholder — actual MainScreen is wired separately via MainActivity
}