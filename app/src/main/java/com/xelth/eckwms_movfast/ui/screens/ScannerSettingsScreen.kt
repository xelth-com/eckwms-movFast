// app/src/main/java/com/xelth/eckwms_movfast/ui/screens/ScannerSettingsScreen.kt
// Обновлено с добавлением кнопки для глубокой диагностики функций изображения
package com.xelth.eckwms_movfast.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xcheng.scanner.AimerMode
import com.xcheng.scanner.BarcodeType
import com.xcheng.scanner.FlashMode
import com.xelth.eckwms_movfast.diagnostics.ScannerApiTester
import com.xelth.eckwms_movfast.diagnostics.ScannerImageDiagnostics
import com.xelth.eckwms_movfast.scanners.ScannerManager

private const val TAG = "ScannerSettingsScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerSettingsScreen(
    scannerManager: ScannerManager,
    onNavigateBack: () -> Unit,
    onOpenImageViewer: () -> Unit
) {
    // Наблюдаем за последним отсканированным штрих-кодом
    val latestScanResult by scannerManager.scanResult.observeAsState()

    // Dropdown state
    var selectedBarcodeType by remember { mutableStateOf("QR Code") }
    var selectedFlashMode by remember { mutableStateOf("Illuminate Only") }
    var selectedAimerMode by remember { mutableStateOf("On During Scan") }

    // Dropdown expanded state
    var barcodeTypeExpanded by remember { mutableStateOf(false) }
    var flashModeExpanded by remember { mutableStateOf(false) }
    var aimerModeExpanded by remember { mutableStateOf(false) }

    // Barcode type mapping
    val barcodeTypes = remember {
        listOf(
            "QR Code" to BarcodeType.QRCODE,
            "Code 128" to BarcodeType.CODE128,
            "Code 39" to BarcodeType.CODE39,
            "EAN-13" to BarcodeType.EAN13,
            "EAN-8" to BarcodeType.EAN8,
            "UPC-A" to BarcodeType.UPCA,
            "UPC-E" to BarcodeType.UPCE,
            "DataMatrix" to BarcodeType.DATAMATRIX,
            "PDF417" to BarcodeType.PDF417
        )
    }

    // Flash mode mapping
    val flashModes = remember {
        listOf(
            "Off" to FlashMode.OFF,
            "Illuminate Only" to FlashMode.ILLUME_ONLY,
            "Illuminate with Strobe" to FlashMode.ILLUME_STROBE
        )
    }

    // Aimer mode mapping
    val aimerModes = remember {
        listOf(
            "Always Off" to AimerMode.ALWAYS_OFF,
            "On During Scan" to AimerMode.TRIGGER_ON,
            "Always On" to AimerMode.ALWAYS_ON
        )
    }

    // Отслеживаем, запущено ли непрерывное сканирование
    var isLoopScanActive by remember { mutableStateOf(scannerManager.isLoopScanRunning()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scanner Settings") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text("← Back")
                    }
                },
                actions = {
                    TextButton(onClick = onOpenImageViewer) {
                        Text("View Image")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Result Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Last Scan Result",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.LightGray.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                            .padding(16.dp)
                    ) {
                        Text(
                            text = latestScanResult ?: "No scan results yet",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = onOpenImageViewer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("View Scanned Image")
                    }
                }
            }

            // Settings Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Scanner Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    // Barcode Type Dropdown
                    Column {
                        Text("Barcode Type", fontWeight = FontWeight.Bold)
                        Box {
                            TextButton(
                                onClick = { barcodeTypeExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = selectedBarcodeType,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Start
                                )
                                Text("▼")
                            }
                            DropdownMenu(
                                expanded = barcodeTypeExpanded,
                                onDismissRequest = { barcodeTypeExpanded = false },
                                modifier = Modifier.fillMaxWidth(0.9f)
                            ) {
                                barcodeTypes.forEach { (name, type) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            selectedBarcodeType = name
                                            barcodeTypeExpanded = false

                                            // Включаем только выбранный тип штрих-кода
                                            barcodeTypes.forEach { (_, barcodeType) ->
                                                scannerManager.enableBarcodeType(barcodeType, barcodeType == type)
                                            }

                                            Log.d(TAG, "Selected barcode type: $name")
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Divider()

                    // Flash Mode Dropdown
                    Column {
                        Text("Flash Mode", fontWeight = FontWeight.Bold)
                        Box {
                            TextButton(
                                onClick = { flashModeExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = selectedFlashMode,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Start
                                )
                                Text("▼")
                            }
                            DropdownMenu(
                                expanded = flashModeExpanded,
                                onDismissRequest = { flashModeExpanded = false },
                                modifier = Modifier.fillMaxWidth(0.9f)
                            ) {
                                flashModes.forEach { (name, mode) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            selectedFlashMode = name
                                            flashModeExpanded = false
                                            scannerManager.setFlashLightsMode(mode)
                                            Log.d(TAG, "Selected flash mode: $name")
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Divider()

                    // Aimer Mode Dropdown
                    Column {
                        Text("Aimer Mode", fontWeight = FontWeight.Bold)
                        Box {
                            TextButton(
                                onClick = { aimerModeExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = selectedAimerMode,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Start
                                )
                                Text("▼")
                            }
                            DropdownMenu(
                                expanded = aimerModeExpanded,
                                onDismissRequest = { aimerModeExpanded = false },
                                modifier = Modifier.fillMaxWidth(0.9f)
                            ) {
                                aimerModes.forEach { (name, mode) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            selectedAimerMode = name
                                            aimerModeExpanded = false
                                            scannerManager.setAimerLightsMode(mode)
                                            Log.d(TAG, "Selected aimer mode: $name")
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Scan Button
            Button(
                onClick = {
                    Log.d(TAG, "⭐ Toggle scan button clicked, current state: ${if (isLoopScanActive) "scanning" else "not scanning"}")
                    if (isLoopScanActive) {
                        scannerManager.stopLoopScan()
                    } else {
                        scannerManager.startLoopScan(500) // 500ms интервал
                    }
                    isLoopScanActive = !isLoopScanActive
                    Log.d(TAG, "⭐ Scan state changed to: ${if (isLoopScanActive) "scanning" else "not scanning"}")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isLoopScanActive) "Stop Continuous Scanning" else "Start Continuous Scanning")
            }

            // Кнопки диагностики
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Кнопка тестирования API
                OutlinedButton(
                    onClick = {
                        Log.d(TAG, "⭐ API Test button clicked")
                        ScannerApiTester(scannerManager).testAndLogAllApiFunctions()
                        Log.d(TAG, "⭐ API Test completed, check Logcat for results")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test API", textAlign = TextAlign.Center)
                }

                // Кнопка диагностики изображений
                OutlinedButton(
                    onClick = {
                        Log.d(TAG, "⭐ Image Diagnostics button clicked")
                        ScannerImageDiagnostics().runFullDiagnostics()
                        Log.d(TAG, "⭐ Image Diagnostics completed, check Logcat for results")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Image Diagnostics", textAlign = TextAlign.Center)
                }
            }

            Text(
                text = "Use Logcat to see diagnostic results",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
    }
}