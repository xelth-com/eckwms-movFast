// app/src/main/java/com/xelth/eckwms_movfast/ui/screens/ScannerScreen.kt
package com.xelth.eckwms_movfast.ui.screens

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xcheng.scanner.AimerMode
import com.xcheng.scanner.BarcodeType
import com.xcheng.scanner.FlashMode
import com.xcheng.scanner.NotificationType
import com.xcheng.scanner.OutputMethod
import com.xcheng.scanner.RegionSizeType
import com.xcheng.scanner.TextCaseType
import com.xelth.eckwms_movfast.scanners.XCScannerWrapper

private const val TAG = "ScannerScreen"

// Константы для broadcast
private const val SCAN_ACTION = "com.xcheng.scanner.action.BARCODE_DECODING_BROADCAST"
private const val SCAN_DATA_KEY = "EXTRA_BARCODE_DECODING_DATA"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onNavigateBack: () -> Unit,
    onBarcodeScanned: (String) -> Unit
) {
    val context = LocalContext.current
    var scanResult by remember { mutableStateOf("Scan result will appear here") }
    var isScanning by remember { mutableStateOf(false) }
    var isLoopScanning by remember { mutableStateOf(false) }

    // Для BroadcastReceiver
    var scanReceiver: BroadcastReceiver? = null

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

    // Инициализация сканера при отображении экрана
    LaunchedEffect(Unit) {
        Log.d(TAG, "⭐ Инициализация сканера начинается...")

        // Инициализация XCScannerWrapper
        XCScannerWrapper.initialize(context) { symbology, barcode ->
            Log.d(TAG, "⭐ Scan result: type=$symbology, barcode=$barcode")
            scanResult = "Отсканировано (SDK): type=$symbology, barcode=$barcode"
            onBarcodeScanned(barcode)
        }

        // Настройка сканера
        configureScanner(context)

        // Регистрируем BroadcastReceiver для получения результатов сканирования
        scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Log.d(TAG, "⭐ BroadcastReceiver получил Intent: ${intent.action}")

                // Проверяем, что это именно наш action
                if (intent.action == SCAN_ACTION) {
                    val barcode = intent.getStringExtra(SCAN_DATA_KEY)

                    if (barcode != null) {
                        Log.d(TAG, "⭐⭐⭐ Получен штрих-код через broadcast: $barcode")

                        // Обновляем UI в главном потоке
                        (context as? Activity)?.runOnUiThread {
                            scanResult = "Отсканировано: $barcode"
                            onBarcodeScanned(barcode)
                        }
                    } else {
                        Log.d(TAG, "⚠️ Intent не содержит данных по ключу $SCAN_DATA_KEY")
                        // Проверяем все extras для отладки
                        intent.extras?.keySet()?.forEach { key ->
                            Log.d(TAG, "  Ключ: $key, Значение: ${intent.extras?.get(key)}")
                        }
                    }
                }
            }
        }

        // Регистрируем BroadcastReceiver с конкретным фильтром
        val intentFilter = IntentFilter(SCAN_ACTION)
        context.registerReceiver(scanReceiver, intentFilter)

        Log.d(TAG, "⭐ ScannerScreen настроен и готов к сканированию")
    }

    // Очистка ресурсов при закрытии экрана
    DisposableEffect(Unit) {
        onDispose {
            Log.d(TAG, "⭐ Очистка ресурсов ScannerScreen")

            if (isScanning) {
                XCScannerWrapper.stopScan()
            }

            if (isLoopScanning) {
                XCScannerWrapper.stopLoopScan()
            }

            // Отменяем регистрацию BroadcastReceiver
            scanReceiver?.let {
                try {
                    context.unregisterReceiver(it)
                    Log.d(TAG, "✓ BroadcastReceiver отменен")
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка при отмене регистрации BroadcastReceiver", e)
                }
            }

            XCScannerWrapper.deinitialize(context)
            Log.d(TAG, "✓ Ресурсы ScannerScreen очищены")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Barcode Scanner") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
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
                        text = "Scan Result",
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
                            text = scanResult,
                            modifier = Modifier.fillMaxWidth()
                        )
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
                                Icon(Icons.Default.ArrowDropDown, "Dropdown Arrow")
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
                                                XCScannerWrapper.enableBarcodeType(barcodeType, barcodeType == type)
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
                                Icon(Icons.Default.ArrowDropDown, "Dropdown Arrow")
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
                                            XCScannerWrapper.setFlashLightsMode(mode)
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
                                Icon(Icons.Default.ArrowDropDown, "Dropdown Arrow")
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
                                            XCScannerWrapper.setAimerLightsMode(mode)
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

            // Тестовая кнопка для эмуляции сканирования (для отладки)
            Button(
                onClick = {
                    Log.d(TAG, "⭐ Test scan button clicked")
                    scanResult = "TEST: Generated test barcode"
                    onBarcodeScanned("TEST123456789")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Generate Test Barcode")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Scan Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = {
                        Log.d(TAG, "⭐ Scan button clicked, current state: ${if (isScanning) "scanning" else "not scanning"}")
                        if (isScanning) {
                            XCScannerWrapper.stopScan()
                            Log.d(TAG, "⭐ STOPPING SCAN")
                        } else {
                            XCScannerWrapper.startScan()
                            Log.d(TAG, "⭐ STARTING SCAN")
                        }
                        isScanning = !isScanning
                        Log.d(TAG, "⭐ Scan state changed to: ${if (isScanning) "scanning" else "not scanning"}")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isScanning) "Stop Scan" else "Start Scan")
                }

                Button(
                    onClick = {
                        Log.d(TAG, "⭐ Loop scan button clicked, current state: ${if (isLoopScanning) "loop scanning" else "not loop scanning"}")
                        if (isLoopScanning) {
                            XCScannerWrapper.stopLoopScan()
                            Log.d(TAG, "⭐ STOPPING LOOP SCAN")
                        } else {
                            XCScannerWrapper.setLoopScanInterval(500) // 500ms interval
                            XCScannerWrapper.startLoopScan()
                            Log.d(TAG, "⭐ STARTING LOOP SCAN")
                        }
                        isLoopScanning = !isLoopScanning
                        Log.d(TAG, "⭐ Loop scan state changed to: ${if (isLoopScanning) "loop scanning" else "not loop scanning"}")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isLoopScanning) "Stop Loop Scan" else "Start Loop Scan")
                }
            }
        }
    }
}

/**
 * Настраивает сканер с оптимальными параметрами
 */
private fun configureScanner(context: Context) {
    Log.d(TAG, "⭐ Configuring scanner with improved settings...")

    // Настройка вывода для работы с broadcast
    XCScannerWrapper.setOutputMethod(OutputMethod.BROADCAST)

    // Максимальная область сканирования
    XCScannerWrapper.setScanRegionSize(RegionSizeType.VIEWSIZE_100)

    // Максимальное время сканирования
    XCScannerWrapper.setTimeout(9)

    // Звуковое и вибро-оповещение при успешном сканировании
    XCScannerWrapper.setSuccessNotification(NotificationType.SOUND_VIBRATOR)

    // Включить LED-индикатор при успешном сканировании
    XCScannerWrapper.enableSuccessIndicator(true)

    // Настройка обработки текста
    XCScannerWrapper.setTextCase(TextCaseType.NONE)

    // Включить распознавание всех основных типов штрих-кодов
    XCScannerWrapper.enableBarcodeType(BarcodeType.QRCODE, true)
    XCScannerWrapper.enableBarcodeType(BarcodeType.CODE128, true)
    XCScannerWrapper.enableBarcodeType(BarcodeType.CODE39, true)
    XCScannerWrapper.enableBarcodeType(BarcodeType.EAN13, true)
    XCScannerWrapper.enableBarcodeType(BarcodeType.EAN8, true)
    XCScannerWrapper.enableBarcodeType(BarcodeType.UPCA, true)
    XCScannerWrapper.enableBarcodeType(BarcodeType.UPCE, true)
    XCScannerWrapper.enableBarcodeType(BarcodeType.DATAMATRIX, true)
    XCScannerWrapper.enableBarcodeType(BarcodeType.PDF417, true)

    Log.d(TAG, "⭐ Scanner configuration complete with all barcode types enabled")
}