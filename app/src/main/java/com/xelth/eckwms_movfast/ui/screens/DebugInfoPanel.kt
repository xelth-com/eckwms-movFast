package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
                if (recoveryStatus != null && scanState?.name?.contains("RECOVERY") == true) {
                    Text("Images: ${recoveryStatus.imagesCollected}/${recoveryStatus.totalImages}", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Image Previews
            if (recoveryImages.isNotEmpty()) {
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