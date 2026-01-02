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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import com.xelth.eckwms_movfast.utils.SettingsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xelth.eckwms_movfast.ui.data.ScanHistoryItem
import com.xelth.eckwms_movfast.ui.data.ScanStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "ScannerSettingsScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerSettingsScreen(
    scannerManager: ScannerManager,
    viewModel: com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel,
    onNavigateBack: () -> Unit,
    onOpenImageViewer: () -> Unit,
    onNavigateToCamera: (String) -> Unit = {}
) {
    // Наблюдаем за последним отсканированным штрих-кодом
    val latestScanResult by scannerManager.scanResult.observeAsState()
    val debugPanelEnabled by viewModel.debugPanelEnabled.observeAsState(false)
    val scrollState = rememberScrollState()

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
    var selectedFlashMode by remember { mutableStateOf("Illuminate Only") }
    var selectedAimerMode by remember { mutableStateOf("On During Scan") }

    // Dropdown expanded state
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

            // 1. Continuous Scan Toggle
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

            // 2. View Image Card
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

            // Server Settings Card
            ServerSettingsSection()

            // 3. ML Kit Recovery Section
            MlKitRecoverySection(viewModel, onNavigateToCamera)

            // 4. API Testing Tools Section (Simplified)
            ApiTestsSection(viewModel, scannerManager)

            // 5. Image Upload Section
            ImageUploadSection(viewModel = viewModel)

            // 6. Image Settings Section
            ImageSettingsSection(viewModel = viewModel)

            // 7. Scanner Settings Card
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

                    // Barcode Type Toggles
                    Column {
                        Text("Enabled Barcode Types", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        barcodeTypes.forEach { (name, type) ->
                            var isEnabled by remember {
                                mutableStateOf(
                                    try {
                                        XCScannerWrapper.isBarcodeTypeEnabled(type)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Cannot check if $name is enabled, defaulting to true", e)
                                        true // Default to enabled if we can't check
                                    }
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val newState = !isEnabled
                                        isEnabled = newState
                                        try {
                                            scannerManager.enableBarcodeType(type, newState)
                                            Log.d(TAG, "Set $name to ${if(newState) "enabled" else "disabled"}")
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error setting barcode type for $name", e)
                                            errorMessages.add("Error for $name: ${e.message}")
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                                Switch(
                                    checked = isEnabled,
                                    onCheckedChange = { checked ->
                                        isEnabled = checked
                                        try {
                                            scannerManager.enableBarcodeType(type, checked)
                                            Log.d(TAG, "Set $name to ${if(checked) "enabled" else "disabled"}")
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error setting barcode type for $name", e)
                                            errorMessages.add("Error for $name: ${e.message}")
                                        }
                                    }
                                )
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

            // Debug Panel removed - all logs go to logcat
        }
    }
}

/**
 * Simplified API Testing Section - Single button that logs to Debug Panel
 */
@Composable
fun ApiTestsSection(
    viewModel: com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel,
    scannerManager: ScannerManager
) {
    val coroutineScope = rememberCoroutineScope()
    var isTestRunning by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "API Testing Tools",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Text(
                text = "Run tests and view results in the Debug Panel below",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Button(
                onClick = {
                    if (!isTestRunning) {
                        isTestRunning = true

                        // Enable debug panel if not already enabled
                        viewModel.setDebugPanelEnabled(true)

                        coroutineScope.launch {
                            try {
                                val tester = ScannerApiTester(scannerManager)
                                tester.testAndLogToViewModel { message ->
                                    viewModel.addLog(message)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error running tests", e)
                                viewModel.addLog("❌ Error running tests: ${e.message}")
                            } finally {
                                isTestRunning = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isTestRunning
            ) {
                if (isTestRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isTestRunning) "Running Tests..." else "Run All Tests")
            }
        }
    }
}

/**
 * ML Kit Recovery Section Component - Now uses camera for image capture
 */
@Composable
fun MlKitRecoverySection(
    viewModel: com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel,
    onNavigateToCamera: (String) -> Unit
) {
    // Наблюдаем за состоянием ML Kit
    val scanState by viewModel.scanState.observeAsState(com.xelth.eckwms_movfast.ui.viewmodels.ScanState.IDLE)
    val recoveryStatus by viewModel.recoveryStatus.observeAsState()
    val errorMessage by viewModel.errorMessage.observeAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "ML Kit Barcode Recovery",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )

            Text(
                text = "When hardware scanner fails, use ML Kit with camera",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )

            // Показать статус
            if (scanState != com.xelth.eckwms_movfast.ui.viewmodels.ScanState.IDLE) {
                Text(
                    "Status: ${scanState.name}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            errorMessage?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (scanState == com.xelth.eckwms_movfast.ui.viewmodels.ScanState.RECOVERY_SESSION_ACTIVE) {
                recoveryStatus?.let { status ->
                    Text("Recovery Mode: Capture ${status.totalImages} images from different angles.")
                    Text("Collected: ${status.imagesCollected} / ${status.totalImages}")
                }
            }

            // Кнопки ML Kit - теперь открывают камеру
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Enhanced Scan button - открывает камеру для single recovery
                Button(
                    onClick = {
                        viewModel.requestSingleImageRecovery()
                        onNavigateToCamera("single_recovery")
                    },
                    modifier = Modifier.weight(1f),
                    enabled = scanState == com.xelth.eckwms_movfast.ui.viewmodels.ScanState.HW_SCAN_FAILED || scanState == com.xelth.eckwms_movfast.ui.viewmodels.ScanState.IDLE
                ) {
                    Text("Enhanced Scan")
                }

                // Recovery Session button - открывает камеру для multi recovery
                Button(
                    onClick = {
                        viewModel.requestRecoverySession()
                        onNavigateToCamera("multi_recovery")
                    },
                    modifier = Modifier.weight(1f),
                    enabled = scanState == com.xelth.eckwms_movfast.ui.viewmodels.ScanState.ML_ANALYSIS_FAILED || scanState == com.xelth.eckwms_movfast.ui.viewmodels.ScanState.IDLE
                ) {
                    Text("Recovery Session")
                }
            }

            if (scanState == com.xelth.eckwms_movfast.ui.viewmodels.ScanState.RECOVERY_SESSION_ACTIVE) {
                Button(
                    onClick = {
                        onNavigateToCamera("multi_recovery_continue")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Capture Image (${(recoveryStatus?.imagesCollected ?: 0) + 1}/${recoveryStatus?.totalImages})")
                }
            }

            if (scanState != com.xelth.eckwms_movfast.ui.viewmodels.ScanState.IDLE) {
                OutlinedButton(
                    onClick = { viewModel.reset() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Reset")
                }
            }
        }
    }
}

@Composable
fun ImageUploadSection(
    viewModel: com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel
) {
    val singleRecoveryImage by viewModel.singleRecoveryImage.observeAsState()
    val isImageAvailable = singleRecoveryImage != null

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Image Upload Tools",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "Upload the last captured image (from Stage 2 recovery) to the server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.uploadLastImage("dumb") },
                    modifier = Modifier.weight(1f),
                    enabled = isImageAvailable
                ) {
                    Text("Upload (Dumb)")
                }

                Button(
                    onClick = { viewModel.uploadLastImage("mlkit") },
                    modifier = Modifier.weight(1f),
                    enabled = isImageAvailable
                ) {
                    Text("Upload (ML Kit)")
                }
            }
            if (!isImageAvailable) {
                Text(
                    text = "No image available. Use 'Enhanced Scan' to capture one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun ImageSettingsSection(viewModel: com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel) {
    var resolution by remember { mutableStateOf(com.xelth.eckwms_movfast.utils.SettingsManager.getImageResolution().toFloat()) }
    var quality by remember { mutableStateOf(com.xelth.eckwms_movfast.utils.SettingsManager.getImageQuality().toFloat()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Image Upload Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            // Resolution Slider
            Column {
                Text("Max Image Resolution: ${resolution.toInt()}px", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = resolution,
                    onValueChange = { resolution = it },
                    valueRange = 800f..4032f,
                    steps = 15,
                    onValueChangeFinished = { viewModel.setUploadImageResolution(resolution.toInt()) }
                )
            }

            // Quality Slider
            Column {
                Text("WEBP Compression Quality: ${quality.toInt()}%", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = quality,
                    onValueChange = { quality = it },
                    valueRange = 10f..100f,
                    steps = 8,
                    onValueChangeFinished = { viewModel.setUploadImageQuality(quality.toInt()) }
                )
            }
        }
    }
}

@Composable
fun ServerSettingsSection() {
    var serverUrl by remember { mutableStateOf(SettingsManager.getServerUrl()) }
    val coroutineScope = rememberCoroutineScope()
    var showSavedMessage by remember { mutableStateOf(false) }

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
                text = "Server Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("Server Address (e.g., http://192.168.0.1:3100)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showSavedMessage) {
                    Text(
                        "Saved!",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                Button(
                    onClick = {
                        SettingsManager.saveServerUrl(serverUrl)
                        coroutineScope.launch {
                            showSavedMessage = true
                            delay(2000)
                            showSavedMessage = false
                        }
                    }
                ) {
                    Text("Save")
                }
            }
        }
    }
}
