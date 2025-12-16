package com.xelth.eckwms_movfast.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel

private const val TAG = "PairingScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(viewModel: ScanRecoveryViewModel, navController: NavController) {
    val pairingLog by viewModel.pairingLog.observeAsState(emptyList())
    val isPairing by viewModel.isPairing.observeAsState(false)
    val pairingSuccess by viewModel.pairingSuccess.observeAsState(false)
    val listState = rememberLazyListState()

    // Track screen visibility for ViewModel
    DisposableEffect(Unit) {
        viewModel.setPairingScreenActive(true)
        onDispose {
            viewModel.setPairingScreenActive(false)
        }
    }

    // Auto-scroll to bottom when new logs appear
    LaunchedEffect(pairingLog.size) {
        if (pairingLog.isNotEmpty()) {
            listState.animateScrollToItem(pairingLog.size - 1)
        }
    }

    // Observe scanned barcode data from camera
    LaunchedEffect(navController) {
        val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
        savedStateHandle?.getLiveData<Map<String, String>>("scanned_barcode_data")?.observeForever { data ->
            if (data != null) {
                Log.d(TAG, "Received scanned barcode data: $data")
                val barcode = data["barcode"] ?: ""
                val scanMode = data["scanMode"] ?: ""

                Log.d(TAG, "Barcode: $barcode, ScanMode: $scanMode")

                if (scanMode == "pairing" && barcode.isNotEmpty()) {
                    Log.d(TAG, "Processing pairing QR code...")
                    viewModel.handlePairingQrCode(barcode)
                    // Clear the data to prevent reprocessing
                    savedStateHandle.remove<Map<String, String>>("scanned_barcode_data")
                } else {
                    Log.w(TAG, "Invalid pairing data - scanMode: $scanMode, barcode empty: ${barcode.isEmpty()}")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device Pairing Console") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Console area - takes all remaining space
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(pairingLog) { logLine ->
                    Text(
                        text = logLine,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = when {
                            logLine.contains("❌") || logLine.contains("FAILED") -> Color(0xFFFF6B6B)
                            logLine.contains("✅") || logLine.contains("SUCCESS") -> Color(0xFF51CF66)
                            logLine.contains("⏳") || logLine.contains("Testing") -> Color(0xFFFECA57)
                            logLine.contains("━━━") -> Color(0xFF4ECDC4)
                            logLine.startsWith("  ") || logLine.startsWith("    ") -> Color(0xFFAAAAAA)
                            else -> Color(0xFFE0E0E0)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Button area at bottom
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (pairingSuccess) {
                    // Success State: "Start Scanning" is primary
                    Button(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF51CF66)) // Green for success
                    ) {
                        Text("Start Scanning")
                    }

                    OutlinedButton(
                        onClick = {
                            viewModel.clearPairingLog()
                            navController.navigate("cameraScanScreen?scan_mode=pairing")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Pair Again")
                    }
                } else {
                    // Default State
                    Button(
                        onClick = {
                            viewModel.clearPairingLog()
                            navController.navigate("cameraScanScreen?scan_mode=pairing")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isPairing
                    ) {
                        Text(if (isPairing) "Pairing in progress..." else "Scan Pairing QR Code")
                    }

                    OutlinedButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isPairing
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}
