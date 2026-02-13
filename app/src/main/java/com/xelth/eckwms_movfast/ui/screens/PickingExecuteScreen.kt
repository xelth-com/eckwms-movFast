package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xelth.eckwms_movfast.ui.viewmodels.PickingUiState
import com.xelth.eckwms_movfast.ui.viewmodels.PickingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickingExecuteScreen(
    viewModel: PickingViewModel,
    onShowMap: () -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val picking by viewModel.selectedPicking.collectAsState()
    val pickLines by viewModel.pickLines.collectAsState()
    val currentIndex by viewModel.currentLineIndex.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val locationVerified by viewModel.locationVerified.collectAsState()

    val currentLine = viewModel.getCurrentLine()
    val totalLines = pickLines.size
    val pickedCount = pickLines.count { it.state == "done" || it.qtyDone >= it.qtyDemand }
    val progress = if (totalLines > 0) pickedCount.toFloat() / totalLines else 0f

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(picking?.name ?: "Picking") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.backToPickList()
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            when (uiState) {
                PickingUiState.LOADING -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                PickingUiState.COMPLETED -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = Color(0xFF4CAF50)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Picking Complete!",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("$totalLines items picked", style = MaterialTheme.typography.bodyLarge)
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(onClick = {
                                viewModel.backToPickList()
                                onBack()
                            }) {
                                Text("Back to List")
                            }
                        }
                    }
                }

                PickingUiState.EXECUTING -> {
                    // Progress header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Item ${currentIndex + 1} of $totalLines",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "$pickedCount/$totalLines picked",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Gray.copy(alpha = 0.3f),
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Error message
                    if (errorMessage != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                errorMessage!!,
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Current target card
                    if (currentLine != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                // Product info
                                Text(
                                    currentLine.productName,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                if (!currentLine.productCode.isNullOrEmpty()) {
                                    Text(
                                        "SKU: ${currentLine.productCode}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                if (!currentLine.productBarcode.isNullOrEmpty()) {
                                    Text(
                                        "Barcode: ${currentLine.productBarcode}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(12.dp))

                                // Quantity
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        "${currentLine.qtyDone.toInt()} / ${currentLine.qtyDemand.toInt()}",
                                        style = MaterialTheme.typography.headlineLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = if (currentLine.qtyDone >= currentLine.qtyDemand)
                                            Color(0xFF4CAF50)
                                        else
                                            MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(12.dp))

                                // Location info
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Location: ${currentLine.locationName}",
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        if (!currentLine.rackName.isNullOrEmpty()) {
                                            Text(
                                                "Rack: ${currentLine.rackName}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    // Location verified indicator
                                    if (locationVerified) {
                                        Surface(
                                            color = Color(0xFF4CAF50),
                                            shape = MaterialTheme.shapes.small
                                        ) {
                                            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("AT LOCATION", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Scan instruction
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (!locationVerified)
                                    Color(0xFFFFF3E0) // Orange hint
                                else
                                    Color(0xFFE8F5E9) // Green hint
                            )
                        ) {
                            Text(
                                text = if (!locationVerified)
                                    "Scan LOCATION barcode to verify arrival"
                                else
                                    "Scan PRODUCT barcode to confirm pick",
                                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onShowMap,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Map")
                        }

                        OutlinedButton(
                            onClick = { viewModel.skipCurrentLine() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Skip")
                        }

                        if (pickedCount == totalLines && totalLines > 0) {
                            Button(
                                onClick = { viewModel.validateAndComplete() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Done")
                            }
                        }
                    }
                }

                else -> {}
            }
        }
    }
}
