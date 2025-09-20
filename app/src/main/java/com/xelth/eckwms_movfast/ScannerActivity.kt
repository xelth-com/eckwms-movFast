// app/src/main/java/com/xelth/eckwms_movfast/ScannerActivity.kt
package com.xelth.eckwms_movfast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.xelth.eckwms_movfast.scanners.ScannerManager
import com.xelth.eckwms_movfast.ui.screens.ScannedImageScreen
import com.xelth.eckwms_movfast.ui.screens.ScannerSettingsScreen
import com.xelth.eckwms_movfast.ui.theme.EckwmsmovFastTheme

class ScannerActivity : ComponentActivity() {
    // Ссылка на ScannerManager
    private lateinit var scannerManager: ScannerManager

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
                    // Управление навигацией между экранами внутри активности
                    var currentScreen by remember { mutableStateOf("settings") }

                    when (currentScreen) {
                        "settings" -> {
                            ScannerSettingsScreen(
                                scannerManager = scannerManager,
                                onNavigateBack = { finish() },
                                onOpenImageViewer = { currentScreen = "image_viewer" }
                            )
                        }
                        "image_viewer" -> {
                            ScannedImageScreen(
                                scannerManager = scannerManager,
                                onNavigateBack = { currentScreen = "settings" }
                            )
                        }
                    }
                }
            }
        }
    }
}