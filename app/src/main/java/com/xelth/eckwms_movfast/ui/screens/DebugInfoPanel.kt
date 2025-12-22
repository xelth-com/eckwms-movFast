package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel

@Composable
fun DebugInfoPanel(viewModel: ScanRecoveryViewModel, modifier: Modifier = Modifier) {
    val scanState by viewModel.scanState.observeAsState()
    val recoveryStatus by viewModel.recoveryStatus.observeAsState()
    val debugLog by viewModel.debugLog.observeAsState(emptyList())
    val recoveryImages by viewModel.recoveryImagesPreview.observeAsState(emptyList())
    val singleRecoveryImage by viewModel.singleRecoveryImage.observeAsState()
    val allDiagnosticImages by viewModel.allDiagnosticImages.observeAsState(emptyList())
    val logListState = rememberLazyListState()

    // Auto-scroll log to the bottom
    LaunchedEffect(debugLog.size) {
        if (debugLog.isNotEmpty()) {
            logListState.animateScrollToItem(debugLog.size - 1)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                "DEBUG PANEL",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(8.dp))

            // State and Status
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                Text("State: ${scanState?.name ?: "N/A"}", fontWeight = FontWeight.Bold)
                recoveryStatus?.let { status ->
                    if (scanState?.name?.contains("RECOVERY") == true) {
                        Text("Images: ${status.imagesCollected}/${status.totalImages}", fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Single Recovery Image (Stage 2)
            singleRecoveryImage?.let { bitmap ->
                Text(
                    "Stage 2 - Single Recovery Image:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Single recovery image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Recovery Session Images (Stage 3)
            if (recoveryImages.isNotEmpty()) {
                Text(
                    "Stage 3 - Recovery Session Images:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(recoveryImages) { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Recovery image preview",
                            modifier = Modifier.height(80.dp).background(MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // All Diagnostic Images Summary
            if (allDiagnosticImages.isNotEmpty()) {
                Text(
                    "All Diagnostic Images (${allDiagnosticImages.size} total):",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(allDiagnosticImages.withIndex().toList()) { (index, bitmap) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Diagnostic image ${index + 1}",
                                modifier = Modifier.height(45.dp).background(MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                            Text(
                                "${index + 1}",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // AI UI Simulation Section
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Text(
                    "🧪 AI UI Simulation",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                // Row 1: Dialogs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = { viewModel.debugTriggerAiEvent("question") },
                        modifier = Modifier.weight(1f).height(32.dp)
                    ) {
                        Text("Question", fontSize = 10.sp)
                    }
                    Button(
                        onClick = { viewModel.debugTriggerAiEvent("confirmation") },
                        modifier = Modifier.weight(1f).height(32.dp)
                    ) {
                        Text("Confirm", fontSize = 10.sp)
                    }
                    Button(
                        onClick = { viewModel.debugTriggerAiEvent("multi_option") },
                        modifier = Modifier.weight(1f).height(32.dp)
                    ) {
                        Text("Multi", fontSize = 10.sp)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Row 2: Banners
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = { viewModel.debugTriggerAiEvent("info") },
                        modifier = Modifier.weight(1f).height(32.dp)
                    ) {
                        Text("Info", fontSize = 10.sp)
                    }
                    Button(
                        onClick = { viewModel.debugTriggerAiEvent("warning") },
                        modifier = Modifier.weight(1f).height(32.dp)
                    ) {
                        Text("Warning", fontSize = 10.sp)
                    }
                    Button(
                        onClick = { viewModel.debugTriggerAiEvent("error") },
                        modifier = Modifier.weight(1f).height(32.dp)
                    ) {
                        Text("Error", fontSize = 10.sp)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Row 3: Success
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = { viewModel.debugTriggerAiEvent("success") },
                        modifier = Modifier.weight(1f).height(32.dp)
                    ) {
                        Text("Success", fontSize = 10.sp)
                    }
                    Spacer(modifier = Modifier.weight(2f))
                }
            }

            // Event Log
            Box(modifier = Modifier.fillMaxWidth().height(100.dp).background(MaterialTheme.colorScheme.background).padding(4.dp)) {
                LazyColumn(state = logListState) {
                    items(debugLog) { msg ->
                        Text(msg, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}