// app/src/main/java/com/xelth/eckwms_movfast/MainActivity.kt
package com.xelth.eckwms_movfast

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xelth.eckwms_movfast.scanners.ScannerManager
import com.xelth.eckwms_movfast.ui.theme.EckwmsmovFastTheme

class MainActivity : ComponentActivity() {
    // Ссылка на ScannerManager
    private lateinit var scannerManager: ScannerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Получение ScannerManager из приложения
        scannerManager = (application as EckwmsApp).scannerManager

        setContent {
            EckwmsmovFastTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Наблюдаем за результатами сканирования через LiveData
                    val scanResult by scannerManager.scanResult.observeAsState()

                    // Отображаем состояние постоянного сканирования
                    var isLoopScanActive by remember { mutableStateOf(scannerManager.isLoopScanRunning()) }

                    MainContent(
                        scannedBarcode = scanResult,
                        isLoopScanActive = isLoopScanActive,
                        onScanSettingsClick = {
                            // Открываем экран настройки сканера
                            val intent = Intent(this, ScannerActivity::class.java)
                            startActivity(intent)
                        },
                        onToggleLoopScan = {
                            if (isLoopScanActive) {
                                scannerManager.stopLoopScan()
                            } else {
                                scannerManager.startLoopScan()
                            }
                            isLoopScanActive = !isLoopScanActive
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun MainContent(
    scannedBarcode: String?,
    isLoopScanActive: Boolean,
    onScanSettingsClick: () -> Unit,
    onToggleLoopScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "EckWMS Scanner Demo")

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onScanSettingsClick) {
            Text("Scanner Settings")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onToggleLoopScan) {
            Text(if (isLoopScanActive) "Stop Continuous Scanning" else "Start Continuous Scanning")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (scannedBarcode != null) {
            Text("Scanned Barcode: $scannedBarcode")
        } else {
            Text("Scan a barcode to see the result")
        }
    }
}