// app/src/main/java/com/xelth/eckwms_movfast/ui/screens/ScannerSettingsScreen.kt
package com.xelth.eckwms_movfast.ui.screens

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xcheng.scanner.AimerMode
import com.xcheng.scanner.BarcodeType
import com.xcheng.scanner.FlashMode
import com.xcheng.scanner.NotificationType
import com.xcheng.scanner.OutputMethod
import com.xcheng.scanner.RegionSizeType
import com.xcheng.scanner.TextCaseType
import com.xelth.eckwms_movfast.diagnostics.FunctionTestResult
import com.xelth.eckwms_movfast.diagnostics.ScannerApiTester
import com.xelth.eckwms_movfast.scanners.ScannerManager
import com.xelth.eckwms_movfast.scanners.XCScannerWrapper
import kotlinx.coroutines.launch

private const val TAG = "ScannerSettingsScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerSettingsScreen(
    scannerManager: ScannerManager,
    viewModel: com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel,
    onNavigateBack: () -> Unit,
    onOpenImageViewer: () -> Unit
) {
    // Наблюдаем за последним отсканированным штрих-кодом
    val latestScanResult by scannerManager.scanResult.observeAsState()
    val debugPanelEnabled by viewModel.debugPanelEnabled.observeAsState(false)
    val scrollState = rememberScrollState()

    // Состояние для отслеживания переключения на API тестирование
    var showApiTestingSection by remember { mutableStateOf(false) }

    // Для логгирования ошибок
    val context = LocalContext.current
    val errorMessages = remember { mutableStateListOf<String>() }

    // Обработка потенциальных ошибок инициализации
    LaunchedEffect(Unit) {
        try {
            val isInitialized = scannerManager.isInitialized()
            Log.d(TAG, "Scanner initialization status: $isInitialized")

            if (!isInitialized) {
                errorMessages.add("Scanner not initialized. Attempting to initialize...")
                scannerManager.initialize()
                if (scannerManager.isInitialized()) {
                    errorMessages.add("Scanner initialized successfully.")
                } else {
                    errorMessages.add("Failed to initialize scanner.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking scanner initialization", e)
            errorMessages.add("Error checking scanner: ${e.message}")
        }
    }

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
                    // TODO: Add Debug Panel Toggle here
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
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Debug Panel Toggle Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Show Debug Panel",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    
                    Switch(
                        checked = debugPanelEnabled,
                        onCheckedChange = { viewModel.setDebugPanelEnabled(it) }
                    )
                }
            }

            // Переключатель для API Testing Section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showApiTestingSection = !showApiTestingSection },
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (showApiTestingSection)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "API Testing Tools",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (showApiTestingSection)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurface
                    )

                    // Индикатор состояния
                    Text(
                        text = if (showApiTestingSection) "▼" else "▶",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Отображение ошибок, если они есть
            if (errorMessages.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Diagnostic Messages:",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        errorMessages.forEach { message ->
                            Text(
                                text = "• $message",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        TextButton(
                            onClick = { errorMessages.clear() },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Clear Messages")
                        }
                    }
                }
            }

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

            // Показываем либо API тесты, либо обычные настройки в зависимости от состояния
            if (showApiTestingSection) {
                ApiTestsSection(scannerManager)
            } else {
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

                                                try {
                                                    // Включаем только выбранный тип штрих-кода
                                                    barcodeTypes.forEach { (_, barcodeType) ->
                                                        scannerManager.enableBarcodeType(barcodeType, barcodeType == type)
                                                    }
                                                    Log.d(TAG, "Selected barcode type: $name")
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "Error setting barcode type", e)
                                                    errorMessages.add("Error setting barcode type: ${e.message}")
                                                }
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
                                                try {
                                                    scannerManager.setFlashLightsMode(mode)
                                                    Log.d(TAG, "Selected flash mode: $name")
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "Error setting flash mode", e)
                                                    errorMessages.add("Error setting flash mode: ${e.message}")
                                                }
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
                                                try {
                                                    scannerManager.setAimerLightsMode(mode)
                                                    Log.d(TAG, "Selected aimer mode: $name")
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "Error setting aimer mode", e)
                                                    errorMessages.add("Error setting aimer mode: ${e.message}")
                                                }
                                            }
                                        )
                                    }
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
                    try {
                        if (isLoopScanActive) {
                            scannerManager.stopLoopScan()
                        } else {
                            scannerManager.startLoopScan(500) // 500ms интервал
                        }
                        isLoopScanActive = !isLoopScanActive
                        Log.d(TAG, "⭐ Scan state changed to: ${if (isLoopScanActive) "scanning" else "not scanning"}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error toggling scan", e)
                        errorMessages.add("Error toggling scan: ${e.message}")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isLoopScanActive) "Stop Continuous Scanning" else "Start Continuous Scanning")
            }
        }
    }
}

/**
 * Выделение секции тестирования API в отдельный компонент
 */
@Composable
fun ApiTestsSection(scannerManager: ScannerManager) {
    val coroutineScope = rememberCoroutineScope()

    // States for the API testing UI
    val apiTestResults = remember { mutableStateListOf<FunctionTestResult>() }
    var isTestRunning by remember { mutableStateOf(false) }

    // Help dialog state
    var showHelpDialog by remember { mutableStateOf(false) }

    if (showHelpDialog) {
        ApiTestHelpDialog(onDismiss = { showHelpDialog = false })
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header with help button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Title
                Text(
                    text = "Scanner API Tests",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                // Help button
                TextButton(
                    onClick = { showHelpDialog = true },
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Text("?")
                }
            }

            // Test content section
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Run tests to verify scanner API functionality",
                    style = MaterialTheme.typography.bodyMedium
                )

                // Test buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Basic scanner functionality test
                    OutlinedButton(
                        onClick = {
                            if (!isTestRunning) {
                                isTestRunning = true
                                apiTestResults.clear()

                                coroutineScope.launch {
                                    try {
                                        // Run the tests
                                        val tester = ScannerApiTester(scannerManager)
                                        val results = tester.runUIFriendlyTests()

                                        // Update the UI with results
                                        apiTestResults.clear()
                                        apiTestResults.addAll(results.functionResults)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error running tests", e)
                                        apiTestResults.add(
                                            FunctionTestResult(
                                                functionName = "Test Execution",
                                                passed = false,
                                                message = "Error: ${e.message}"
                                            )
                                        )
                                    } finally {
                                        isTestRunning = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isTestRunning
                    ) {
                        Text("Run All Tests")
                    }

                    // Barcode type test
                    OutlinedButton(
                        onClick = {
                            if (!isTestRunning) {
                                isTestRunning = true
                                apiTestResults.clear()

                                coroutineScope.launch {
                                    try {
                                        // Run just the barcode type tests
                                        val tester = ScannerApiTester(scannerManager)
                                        val results = tester.testBarcodeTypesUI()

                                        // Update the UI with results
                                        apiTestResults.clear()
                                        apiTestResults.addAll(results)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error testing barcode types", e)
                                        apiTestResults.add(
                                            FunctionTestResult(
                                                functionName = "Barcode Types Test",
                                                passed = false,
                                                message = "Error: ${e.message}"
                                            )
                                        )
                                    } finally {
                                        isTestRunning = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isTestRunning
                    ) {
                        Text("Test Barcode Types")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Scanner settings test
                    OutlinedButton(
                        onClick = {
                            if (!isTestRunning) {
                                isTestRunning = true
                                apiTestResults.clear()

                                coroutineScope.launch {
                                    try {
                                        // Run just the scanner settings tests
                                        val tester = ScannerApiTester(scannerManager)
                                        val results = tester.testScannerSettingsUI()

                                        // Update the UI with results
                                        apiTestResults.clear()
                                        apiTestResults.addAll(results)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error testing scanner settings", e)
                                        apiTestResults.add(
                                            FunctionTestResult(
                                                functionName = "Scanner Settings Test",
                                                passed = false,
                                                message = "Error: ${e.message}"
                                            )
                                        )
                                    } finally {
                                        isTestRunning = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isTestRunning
                    ) {
                        Text("Test Settings")
                    }

                    // Image capabilities test
                    OutlinedButton(
                        onClick = {
                            if (!isTestRunning) {
                                isTestRunning = true
                                apiTestResults.clear()

                                coroutineScope.launch {
                                    try {
                                        // Run the image capabilities tests
                                        val tester = ScannerApiTester(scannerManager)
                                        val results = tester.testImageFunctionsUI()

                                        // Update the UI with results
                                        apiTestResults.clear()
                                        apiTestResults.addAll(results)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error testing image functions", e)
                                        apiTestResults.add(
                                            FunctionTestResult(
                                                functionName = "Image Functions Test",
                                                passed = false,
                                                message = "Error: ${e.message}"
                                            )
                                        )
                                    } finally {
                                        isTestRunning = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isTestRunning
                    ) {
                        Text("Test Image API")
                    }
                }

                // Добавляем кнопку для тестирования последнего изображения
                OutlinedButton(
                    onClick = {
                        if (!isTestRunning) {
                            isTestRunning = true
                            apiTestResults.clear()

                            coroutineScope.launch {
                                try {
                                    // Получаем последнее изображение через XCScannerWrapper
                                    val lastImg = XCScannerWrapper.getLastDecodeImage()

                                    if (lastImg != null) {
                                        val width = lastImg.width
                                        val height = lastImg.height
                                        val hasData = lastImg.data != null
                                        val dataSize = lastImg.data?.size ?: 0

                                        // Добавляем результаты
                                        apiTestResults.add(
                                            FunctionTestResult(
                                                functionName = "Last Image Test",
                                                passed = true,
                                                message = "Got image: ${width}x${height}, data size: $dataSize bytes"
                                            )
                                        )
                                    } else {
                                        apiTestResults.add(
                                            FunctionTestResult(
                                                functionName = "Last Image Test",
                                                passed = false,
                                                message = "No image available - try scanning a barcode first"
                                            )
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error getting last image", e)
                                    apiTestResults.add(
                                        FunctionTestResult(
                                            functionName = "Last Image Test",
                                            passed = false,
                                            message = "Error: ${e.message}"
                                        )
                                    )
                                } finally {
                                    isTestRunning = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isTestRunning
                ) {
                    Text("Get Last Image (Direct API)")
                }

                // Loading indicator
                if (isTestRunning) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Running tests...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // Results display
                if (apiTestResults.isNotEmpty()) {
                    Divider(modifier = Modifier.padding(vertical = 8.dp))

                    Text(
                        text = "Test Results:",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                    ) {
                        items(apiTestResults) { result ->
                            TestResultItem(result)
                        }
                    }

                    // Summary
                    val passedCount = apiTestResults.count { it.passed }
                    val totalCount = apiTestResults.size
                    val passPercentage = if (totalCount > 0) (passedCount * 100 / totalCount) else 0

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Passed: $passedCount/$totalCount",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Text(
                            text = "$passPercentage%",
                            color = when {
                                passPercentage > 80 -> Color.Green
                                passPercentage > 50 -> Color.Yellow
                                else -> Color.Red
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TestResultItem(result: FunctionTestResult) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Status indicator
        Surface(
            modifier = Modifier.size(20.dp),
            shape = CircleShape,
            color = if (result.passed) Color.Green else Color.Red,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (result.passed) "✓" else "✗",
                    color = Color.White,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Function name and result
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = result.functionName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (result.message.isNotEmpty()) {
                Text(
                    text = buildAnnotatedString {
                        if (result.passed) {
                            withStyle(style = SpanStyle(color = Color.Gray)) {
                                append(result.message)
                            }
                        } else {
                            withStyle(style = SpanStyle(color = Color.Red, fontFamily = FontFamily.Monospace, fontSize = 12.sp)) {
                                append(result.message)
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.alpha(0.8f)
                )
            }
        }
    }
}