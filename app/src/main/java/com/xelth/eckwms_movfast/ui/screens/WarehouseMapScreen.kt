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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.xelth.eckwms_movfast.ui.data.MapRack
import com.xelth.eckwms_movfast.ui.data.PathPoint
import com.xelth.eckwms_movfast.ui.data.RouteStop
import com.xelth.eckwms_movfast.ui.data.WarehouseMapResponse
import com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarehouseMapScreen(
    viewModel: ScanRecoveryViewModel,
    onBack: () -> Unit,
    routeStops: List<RouteStop> = emptyList(),
    routePath: List<PathPoint> = emptyList(),
    currentStopIndex: Int = -1
) {
    val mapData by viewModel.warehouseMap.observeAsState()
    val targetRackId by viewModel.targetRackId.observeAsState()

    // Zoom and Pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Route mode: active when routeStops is not empty
    val hasRoute = routeStops.isNotEmpty()

    // Build a lookup: rackId -> RouteStop
    val routeStopMap = remember(routeStops) { routeStops.associateBy { it.rackId } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (hasRoute) "Pick Route" else (mapData?.name ?: "Warehouse Map")) },
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

                    // --- Draw route path lines first (behind racks) ---
                    if (hasRoute && routePath.size >= 2) {
                        for (i in 0 until routePath.size - 1) {
                            val from = routePath[i]
                            val to = routePath[i + 1]
                            val fromX = (from.x * scale) + offset.x
                            val fromY = (from.y * scale) + offset.y
                            val toX = (to.x * scale) + offset.x
                            val toY = (to.y * scale) + offset.y
                            drawLine(
                                color = Color(0xAAFF9800),
                                start = Offset(fromX, fromY),
                                end = Offset(toX, toY),
                                strokeWidth = 3.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                            )
                        }
                    }

                    // --- Draw racks ---
                    racks.forEach { rack ->
                        val x = (rack.x * scale) + offset.x
                        val y = (rack.y * scale) + offset.y
                        val w = rack.width * scale
                        val h = rack.height * scale

                        val routeStop = routeStopMap[rack.id]
                        val isTarget = rack.id == targetRackId
                        val isCurrent = routeStop?.isCurrent == true
                        val isCompleted = routeStop?.isCompleted == true
                        val isOnRoute = routeStop != null

                        // Determine rack color
                        val rackColor = when {
                            isCurrent -> Color(0xFF4CAF50)     // Green: current stop
                            isCompleted -> Color(0xFF9E9E9E)   // Grey: completed
                            isTarget -> Color(0xFF4CAF50)      // Green: legacy target
                            isOnRoute -> Color(0xFFFFC107)     // Yellow: upcoming
                            else -> Color(0xFF5A7BA9)          // Default blue
                        }

                        val fillStyle = if (isCurrent || isTarget || isCompleted) {
                            androidx.compose.ui.graphics.drawscope.Fill
                        } else if (isOnRoute) {
                            Stroke(width = 3.dp.toPx())
                        } else {
                            Stroke(width = 2.dp.toPx())
                        }

                        rotate(degrees = rack.rotation.toFloat(), pivot = Offset(x + w/2, y + h/2)) {
                            drawRect(
                                color = rackColor,
                                topLeft = Offset(x, y),
                                size = Size(w, h),
                                style = fillStyle
                            )

                            // Draw rack name label
                            drawContext.canvas.nativeCanvas.apply {
                                val paint = android.graphics.Paint().apply {
                                    color = when {
                                        isCurrent || isCompleted || isTarget -> android.graphics.Color.BLACK
                                        else -> android.graphics.Color.WHITE
                                    }
                                    textSize = 12.dp.toPx() * scale
                                    textAlign = android.graphics.Paint.Align.CENTER
                                }
                                drawText(rack.name, x + w/2, y + h/2 + (5.dp.toPx() * scale), paint)
                            }
                        }

                        // --- Draw route sequence number circle ---
                        if (routeStop != null) {
                            val circleRadius = 14.dp.toPx() * scale
                            val cx = x + w / 2
                            val cy = y - circleRadius - 4.dp.toPx()

                            val circleColor = when {
                                isCompleted -> Color(0xFF9E9E9E)
                                isCurrent -> Color(0xFF4CAF50)
                                else -> Color(0xFFFFC107)
                            }

                            drawCircle(
                                color = circleColor,
                                radius = circleRadius,
                                center = Offset(cx, cy)
                            )
                            drawCircle(
                                color = Color.Black,
                                radius = circleRadius,
                                center = Offset(cx, cy),
                                style = Stroke(width = 1.5f)
                            )

                            drawContext.canvas.nativeCanvas.apply {
                                val numPaint = android.graphics.Paint().apply {
                                    color = android.graphics.Color.BLACK
                                    textSize = 11.dp.toPx() * scale
                                    textAlign = android.graphics.Paint.Align.CENTER
                                    isFakeBoldText = true
                                }
                                drawText("${routeStop.sequence}", cx, cy + (4.dp.toPx() * scale), numPaint)
                            }
                        }
                    }
                }

                // --- Bottom info overlay ---
                if (hasRoute) {
                    val currentStop = routeStops.find { it.isCurrent }
                    if (currentStop != null) {
                        Card(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp)
                                .fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Stop ${currentStop.sequence}: ${currentStop.productName}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                                Text(
                                    "Pick ${currentStop.qty.toInt()} items",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                val completed = routeStops.count { it.isCompleted }
                                Text(
                                    "$completed / ${routeStops.size} stops completed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else if (targetRackId != null) {
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
