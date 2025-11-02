package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xelth.eckwms_movfast.ui.data.ScanHistoryItem
import com.xelth.eckwms_movfast.ui.data.ScanStatus
import com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel
import com.xelth.eckwms_movfast.ui.viewmodels.ScanState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    viewModel: ScanRecoveryViewModel,
    navController: androidx.navigation.NavController,
    onNavigateToSettings: () -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("eckWMS Scanner") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
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
            ScanningStatusCard(viewModel = viewModel, navController = navController)
            ScanHistorySection(viewModel = viewModel)
        }
    }
}

@Composable
fun ScanHistorySection(viewModel: ScanRecoveryViewModel) {
    val scanHistory by viewModel.scanHistory.observeAsState(emptyList())
    var isExpanded by remember { mutableStateOf(true) }
    val rotationAngle by animateFloatAsState(targetValue = if (isExpanded) 0f else -90f, label = "rotation")

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Scan History (${scanHistory.size})",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "▼",
                    modifier = Modifier.rotate(rotationAngle),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                if (scanHistory.isEmpty()) {
                    Text(
                        text = "No scans yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp) // Limit height to make the column scrollable inside a Column
                    ) {
                        items(scanHistory, key = { it.id }) { item ->
                            ScanHistoryItemCard(item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScanHistoryItemCard(item: ScanHistoryItem) {
    val backgroundColor = when (item.status) {
        ScanStatus.PENDING -> Color(0xFFFFF9C4) // Light yellow
        ScanStatus.CONFIRMED -> Color(0xFFC8E6C9) // Light green
        ScanStatus.FAILED -> Color(0xFFFFCDD2) // Light red
    }
    val statusText = item.status.name
    val statusColor = when (item.status) {
        ScanStatus.PENDING -> Color(0xFFF57F17) // Dark yellow
        ScanStatus.CONFIRMED -> Color(0xFF2E7D32) // Dark green
        ScanStatus.FAILED -> Color(0xFFC62828) // Dark red
    }
    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val timeString = dateFormat.format(Date(item.timestamp))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.barcode,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "${item.type} • $timeString",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )
        }
    }
}

@Composable
fun ScanningStatusCard(
    viewModel: ScanRecoveryViewModel,
    navController: androidx.navigation.NavController
) {
    val scanState by viewModel.scanState.observeAsState(ScanState.IDLE)
    val scannedBarcode by viewModel.scannedBarcode.observeAsState()
    val errorMessage by viewModel.errorMessage.observeAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (scanState) {
                ScanState.HW_SCANNING -> MaterialTheme.colorScheme.primaryContainer
                ScanState.SUCCESS -> MaterialTheme.colorScheme.tertiaryContainer
                ScanState.FAILURE -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Scanner Status",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Status: ${scanState.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (scanState == ScanState.HW_SCANNING) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            scannedBarcode?.let {
                Text("Last Scan Result:", style = MaterialTheme.typography.titleSmall)
                Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
            }
            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = {
                    if (scanState == ScanState.HW_SCANNING) {
                        viewModel.reset()
                    } else {
                        viewModel.startHardwareScan()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = if (scanState == ScanState.HW_SCANNING) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
            ) {
                Text(if (scanState == ScanState.HW_SCANNING) "Cancel Scan" else "Start Hardware Scan")
            }
            Button(
                onClick = {
                    navController.navigate("cameraScanScreen")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Scan with Camera")
            }
        }
    }
}
