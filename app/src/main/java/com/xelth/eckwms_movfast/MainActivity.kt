package com.xelth.eckwms_movfast

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xelth.eckwms_movfast.ui.theme.EckwmsmovFastTheme
import com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel
import com.xelth.eckwms_movfast.ui.viewmodels.ScanState

class MainActivity : ComponentActivity() {

    private val viewModel: ScanRecoveryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            EckwmsmovFastTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val scanState by viewModel.scanState.observeAsState(ScanState.IDLE)
                    val scannedBarcode by viewModel.scannedBarcode.observeAsState()
                    val recoveryStatus by viewModel.recoveryStatus.observeAsState()
                    val errorMessage by viewModel.errorMessage.observeAsState()

                    MainContent(
                        modifier = Modifier.padding(innerPadding),
                        scanState = scanState,
                        scannedBarcode = scannedBarcode,
                        recoveryStatus = recoveryStatus,
                        errorMessage = errorMessage,
                        onStartScan = { viewModel.startHardwareScan() },
                        onTryEnhancedScan = { viewModel.trySingleImageRecovery() },
                        onStartRecovery = { viewModel.startRecoverySession() },
                        onCaptureForRecovery = { viewModel.captureImageForRecovery() },
                        onReset = { viewModel.reset() },
                        onOpenScannerSettings = {
                            val intent = Intent(this@MainActivity, ScannerActivity::class.java)
                            startActivity(intent)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MainContent(
    modifier: Modifier = Modifier,
    scanState: ScanState,
    scannedBarcode: String?,
    recoveryStatus: com.xelth.eckwms_movfast.ui.viewmodels.RecoveryStatus?,
    errorMessage: String?,
    onStartScan: () -> Unit,
    onTryEnhancedScan: () -> Unit,
    onStartRecovery: () -> Unit,
    onCaptureForRecovery: () -> Unit,
    onReset: () -> Unit,
    onOpenScannerSettings: () -> Unit
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "ECKWMS Hybrid Scanner",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Button(onClick = onOpenScannerSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Scanner Hardware Settings")
        }

        Divider()

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Status: ${scanState.name}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (scanState == ScanState.HW_SCANNING) {
                    CircularProgressIndicator()
                    Text("Point hardware scanner at barcode... (5s timeout)")
                }

                if (scannedBarcode != null) {
                    Text("Result:", style = MaterialTheme.typography.titleSmall)
                    Text(
                        scannedBarcode,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (errorMessage != null) {
                    Text(
                        errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }

                if (scanState == ScanState.RECOVERY_SESSION_ACTIVE && recoveryStatus != null) {
                    Text("Recovery Mode: Please capture ${recoveryStatus.totalImages} images from different angles.")
                    Text("Collected: ${recoveryStatus.imagesCollected} / ${recoveryStatus.totalImages}")
                    LinearProgressIndicator(
                        progress = recoveryStatus.imagesCollected.toFloat() / recoveryStatus.totalImages.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (scanState == ScanState.RECOVERY_ANALYSIS) {
                     CircularProgressIndicator()
                     Text("Analyzing collected images with ML Kit...")
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // State-driven buttons
        when (scanState) {
            ScanState.IDLE, ScanState.FAILURE -> {
                Button(onClick = onStartScan, modifier = Modifier.fillMaxWidth()) {
                    Text("Start Scan")
                }
            }
            ScanState.HW_SCAN_FAILED -> {
                Button(onClick = onTryEnhancedScan, modifier = Modifier.fillMaxWidth()) {
                    Text("Try Enhanced Scan (Chance 2)")
                }
            }
            ScanState.ML_ANALYSIS_FAILED -> {
                 Button(onClick = onStartRecovery, modifier = Modifier.fillMaxWidth()) {
                    Text("Start Recovery Session (Chance 3)")
                }
            }
            ScanState.RECOVERY_SESSION_ACTIVE -> {
                 Button(onClick = onCaptureForRecovery, modifier = Modifier.fillMaxWidth()) {
                    Text("Capture Image (${(recoveryStatus?.imagesCollected ?: 0) + 1}/${recoveryStatus?.totalImages})")
                }
            }
            ScanState.SUCCESS -> {
                Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                    Text("Scan Again")
                }
            }
            else -> {
                // In scanning or analysis states, show a cancel/reset button
                Button(onClick = onReset, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("Cancel and Reset")
                }
            }
        }
    }
}