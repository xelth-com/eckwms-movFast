// app/src/main/java/com/xelth/eckwms_movfast/ScannerActivity.kt
package com.xelth.eckwms_movfast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.xelth.eckwms_movfast.scanners.ScannerManager
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
                    // Используем экран настройки сканера вместо сканирования
                    ScannerSettingsScreen(
                        scannerManager = scannerManager,
                        onNavigateBack = { finish() }
                    )
                }
            }
        }
    }
}