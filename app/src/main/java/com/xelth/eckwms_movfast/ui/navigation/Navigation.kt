package com.xelth.eckwms_movfast.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.xelth.eckwms_movfast.ui.screens.ScannerScreen

@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            // Замените на ваш главный экран или создайте простой заглушку
            MainScreenPlaceholder(navController = navController)
        }

        // Маршрут для сканера
        composable("scanner_screen") {
            ScannerScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onBarcodeScanned = { barcode ->
                    // Сохраняем результат сканирования в savedStateHandle
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("scanned_barcode", barcode)

                    // Возвращаемся назад
                    navController.popBackStack()
                }
            )
        }
    }
}

// Временная заглушка для главного экрана
@Composable
fun MainScreenPlaceholder(navController: NavHostController) {
    // Здесь должен быть ваш главный экран
    // Это просто заглушка для примера
}