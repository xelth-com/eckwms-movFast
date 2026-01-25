package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.xelth.eckwms_movfast.ui.data.MapRack
import com.xelth.eckwms_movfast.ui.data.WarehouseMapResponse
import com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarehouseMapScreen(
    viewModel: ScanRecoveryViewModel,
    onBack: () -> Unit
) {
    val mapData by viewModel.warehouseMap.observeAsState()
    val targetRackId by viewModel.targetRackId.observeAsState()
    
    // Zoom and Pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(mapData?.name ?: "Warehouse Map") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF121212))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 5f)
                        offset += pan
                    }
                }
        ) {
            if (mapData == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val racks = mapData!!.racks
                    
                    // Apply transformations
                    // Center the map initially if offset is zero
                    // (Simplified for now)
                    
                    racks.forEach { rack ->
                        // Calculate screen coordinates
                        val x = (rack.x * scale) + offset.x
                        val y = (rack.y * scale) + offset.y
                        val w = rack.width * scale
                        val h = rack.height * scale
                        
                        val isTarget = rack.id == targetRackId
                        
                        rotate(degrees = rack.rotation.toFloat(), pivot = Offset(x + w/2, y + h/2)) {
                            // Draw Rack Body
                            drawRect(
                                color = if (isTarget) Color(0xFF4CAF50) else Color(0xFF5A7BA9),
                                topLeft = Offset(x, y),
                                size = Size(w, h),
                                style = if (isTarget) androidx.compose.ui.graphics.drawscope.Fill else Stroke(width = 2.dp.toPx())
                            )
                            
                            // Draw Label
                            drawContext.canvas.nativeCanvas.apply {
                                val paint = android.graphics.Paint().apply {
                                    color = if (isTarget) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                                    textSize = 12.dp.toPx() * scale
                                    textAlign = android.graphics.Paint.Align.CENTER
                                }
                                drawText(rack.name, x + w/2, y + h/2 + (5.dp.toPx() * scale), paint)
                            }
                        }
                    }
                }
                
                // Instructions Overlay
                if (targetRackId != null) {
                   val targetRack = mapData!!.racks.find { it.id == targetRackId }
                   if (targetRack != null) {
                       Card(
                           modifier = Modifier
                               .align(Alignment.BottomCenter)
                               .padding(16.dp)
                               .fillMaxWidth(),
                           colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                       ) {
                           Column(modifier = Modifier.padding(16.dp)) {
                               Text("Go to: ${targetRack.name}", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                               Text("Location: ${targetRack.locationBarcode ?: "Unknown"}", style = MaterialTheme.typography.bodyMedium)
                           }
                       }
                   }
                }
            }
        }
    }
}
