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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import com.xelth.eckwms_movfast.ui.data.ScanHistoryItem
import com.xelth.eckwms_movfast.ui.data.ScanStatus
import com.xelth.eckwms_movfast.ui.data.TransactionType
import com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel
import com.xelth.eckwms_movfast.ui.viewmodels.ScanState
import com.xelth.eckwms_movfast.ui.screens.pos.components.order.InterlockingShape
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

    // Trigger ViewModel initialization AFTER first composition
    // This prevents white screen by rendering UI first
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.onViewModelReady()
    }

    val networkHealthState by viewModel.networkHealthState.observeAsState(com.xelth.eckwms_movfast.ui.data.NetworkHealthState.Checking)
    val deviceRegistrationStatus by viewModel.deviceRegistrationStatus.observeAsState("unknown")
    val uiMode by viewModel.uiMode.observeAsState("DEBUG")
    val currentLayout by viewModel.currentLayout.observeAsState("{}")
    val aiInteraction by viewModel.aiInteraction.observeAsState(null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("eckWMS")
                        Text(
                            if(uiMode=="DEBUG") "🔧 Debug Console" else "✨ AI Interface",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF000000), // Black background
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                actions = {
                    // === ADVANCED NETWORK HEALTH INDICATOR ===
                    // Badge color logic:
                    // - RED: device not approved (regardless of connection)
                    // - GREEN: approved + connected via local IP (optimal path)
                    // - YELLOW: approved + connected via internet (non-optimal path)
                    // - GRAY: offline
                    // Accept both 'active' and 'running' as approved statuses
                    val isDeviceApproved = deviceRegistrationStatus == "active" || deviceRegistrationStatus == "running"

                    // LOG: Badge color decision
                    android.util.Log.d("BadgeIndicator", "========================================")
                    android.util.Log.d("BadgeIndicator", "BADGE COLOR CALCULATION:")
                    android.util.Log.d("BadgeIndicator", "  deviceRegistrationStatus = '$deviceRegistrationStatus'")
                    android.util.Log.d("BadgeIndicator", "  isDeviceApproved = $isDeviceApproved (expected 'active' or 'running')")
                    android.util.Log.d("BadgeIndicator", "  networkHealthState = ${networkHealthState::class.simpleName}")
                    android.util.Log.d("BadgeIndicator", "  isConnected = ${networkHealthState.isConnected()}")
                    android.util.Log.d("BadgeIndicator", "  connectionType = ${networkHealthState.connectionType}")
                    android.util.Log.d("BadgeIndicator", "  serverUrl = ${networkHealthState.serverUrl}")
                    android.util.Log.d("BadgeIndicator", "  serverHash = ${networkHealthState.serverHash}")

                    val indicatorColor = when {
                        !isDeviceApproved -> {
                            android.util.Log.w("BadgeIndicator", "  → COLOR: RED (device NOT approved)")
                            Color(0xFFF44336) // Red - not approved
                        }
                        !networkHealthState.isConnected() -> {
                            android.util.Log.d("BadgeIndicator", "  → COLOR: GRAY (offline)")
                            Color(0xFF9E9E9E) // Gray - offline
                        }
                        networkHealthState.connectionType == com.xelth.eckwms_movfast.ui.data.ConnectionType.LOCAL_IP -> {
                            android.util.Log.d("BadgeIndicator", "  → COLOR: GREEN (local IP)")
                            Color(0xFF4CAF50) // Green - local
                        }
                        networkHealthState.connectionType == com.xelth.eckwms_movfast.ui.data.ConnectionType.GLOBAL_URL -> {
                            android.util.Log.d("BadgeIndicator", "  → COLOR: YELLOW (global URL)")
                            Color(0xFFFFEB3B) // Yellow - global
                        }
                        else -> {
                            android.util.Log.w("BadgeIndicator", "  → COLOR: GRAY (unknown)")
                            Color(0xFF9E9E9E) // Gray - unknown
                        }
                    }
                    android.util.Log.d("BadgeIndicator", "========================================")

                    Row(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .background(
                                color = indicatorColor.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // 1. Connection Type Icon (🖥️ for Local, 🌍 for Global, ❌ for Offline)
                        val connectionEmoji = when {
                            networkHealthState.isConnected() -> {
                                when (networkHealthState.connectionType) {
                                    com.xelth.eckwms_movfast.ui.data.ConnectionType.LOCAL_IP -> "🖥️"
                                    com.xelth.eckwms_movfast.ui.data.ConnectionType.GLOBAL_URL -> "🌍"
                                    else -> "❌"
                                }
                            }
                            else -> "❌"
                        }

                        Text(
                            text = connectionEmoji,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.size(16.dp)
                        )

                        // 2. Server Hash (always show if connected, regardless of approval status)
                        if (networkHealthState.isConnected() && networkHealthState.serverHash.isNotEmpty()) {
                            Text(
                                text = networkHealthState.serverHash,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = indicatorColor
                            )
                        } else {
                            Text(
                                text = "OFF",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = indicatorColor
                            )
                        }

                        // 3. Latency Indicator (show if connected, regardless of approval)
                        if (networkHealthState.isConnected() && networkHealthState.latencyMs > 0) {
                            Text(
                                text = "${networkHealthState.latencyMs}ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = indicatorColor.copy(alpha = 0.7f),
                                fontSize = 10.sp
                            )
                        }
                    }

                    // Mode Toggle Button (Temporary for dev)
                    IconButton(onClick = { viewModel.toggleUiMode() }) {
                        Text(
                            text = if (uiMode == "DEBUG") "UI" else "LOG",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }

                    // Settings Button (RBAC Protected)
                    if (viewModel.hasPermission("settings.view")) {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            if (uiMode == "DEBUG") {
                // === LEGACY DEBUG CONSOLE ===
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(scrollState)
                        .focusable(false),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Dummy button to catch keyboard events from scanner - does nothing
                    Button(
                        onClick = {
                            viewModel.addLog(">>> Dummy button clicked - keyboard event caught and ignored")
                        },
                        modifier = Modifier
                            .size(0.dp)  // Invisible button
                            .focusable(true),  // This one SHOULD be focusable to catch events
                        enabled = false  // Disabled so it looks invisible
                    ) { }

                    ActiveOrderCard(viewModel = viewModel)
                    ScanningStatusCard(viewModel = viewModel, navController = navController)
                    if (viewModel.hasPermission("inventory.adjust")) {
                        Button(
                            onClick = { navController.navigate("restockScreen") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusable(false)
                        ) {
                            Text("Start Manual Restock Order")
                        }
                    }
                    WorkflowDrivenUI(viewModel = viewModel, navController = navController, onNavigateToCamera = { viewModel.addLog("Navigate to camera for workflow"); navController.navigate("cameraScanScreen?scan_mode=workflow_capture") })
                    ScanHistorySection(viewModel = viewModel)
                }
            } else {
                // === DYNAMIC AI LAYOUT ===
                com.xelth.eckwms_movfast.ui.dynamic.DynamicUiRenderer(
                    layoutJson = currentLayout,
                    onAction = { action, params ->
                        viewModel.addLog("Dynamic Action: $action")
                        // Handle actions (e.g., start scan, open workflow)
                        if (action == "start_scan") navController.navigate("cameraScanScreen")
                    }
                )
            }

            // === AI INTERACTION OVERLAY ===
            // This overlay renders on top of all content (both DEBUG and DYNAMIC modes)
            AiInteractionOverlay(
                aiInteraction = aiInteraction,
                onDismiss = { viewModel.clearAiInteraction() },
                onResponse = { response -> viewModel.respondToAiInteraction(response) }
            )
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
            containerColor = MaterialTheme.colorScheme.surface
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
                    text = "Order Items",
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
                        text = "No items yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp),
                        verticalArrangement = Arrangement.spacedBy((-24).dp)
                    ) {
                        itemsIndexed(scanHistory, key = { _, item -> item.id }) { index, item ->
                            InterlockingRow(item, index % 2 == 0)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InterlockingRow(item: ScanHistoryItem, isEven: Boolean) {
    val brush = if (isEven) {
        Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceContainer,
                MaterialTheme.colorScheme.surface
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                MaterialTheme.colorScheme.surface,
                MaterialTheme.colorScheme.surfaceContainer
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (isEven) 0.dp else 30.dp,
                end = if (isEven) 30.dp else 0.dp
            )
            .height(48.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(InterlockingShape(isEven))
                .background(brush)
                .padding(horizontal = 40.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (item.transactionType == TransactionType.IMAGE_UPLOAD) {
                        "📷 Image"
                    } else {
                        "🔍 ${item.barcode}"
                    },
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = item.status.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun ScanHistoryItemCard(item: ScanHistoryItem) {
    val backgroundColor = when (item.status) {
        ScanStatus.PENDING -> Color(0xFFEEEEEE) // Light grey (not yet sent)
        ScanStatus.BUFFERED -> Color(0xFFFFF9C4) // Light yellow (in server buffer)
        ScanStatus.CONFIRMED -> Color(0xFFC8E6C9) // Light green (processed by client)
        ScanStatus.FAILED -> Color(0xFFFFCDD2) // Light red (error)
    }
    val statusText = item.status.name
    val statusColor = when (item.status) {
        ScanStatus.PENDING -> Color(0xFF757575) // Dark grey
        ScanStatus.BUFFERED -> Color(0xFFF57F17) // Dark yellow
        ScanStatus.CONFIRMED -> Color(0xFF2E7D32) // Dark green
        ScanStatus.FAILED -> Color(0xFFC62828) // Dark red
    }
    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val timeString = dateFormat.format(Date(item.timestamp))

    // Determine prefix and label based on transaction type
    val prefix = if (item.transactionType == TransactionType.IMAGE_UPLOAD) {
        "📷 "  // Camera emoji
    } else {
        "🔍 "  // Magnifying glass emoji
    }

    val displayText = if (item.transactionType == TransactionType.IMAGE_UPLOAD) {
        val sizeKb = (item.imageSize ?: 0) / 1024
        "${prefix}Image Upload (${sizeKb}KB)"
    } else {
        "${prefix}${item.barcode}"
    }

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
                    text = displayText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    fontFamily = if (item.transactionType == TransactionType.BARCODE_SCAN) {
                        FontFamily.Monospace
                    } else FontFamily.Default
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
    val errorMessage by viewModel.errorMessage.observeAsState()
    val networkHealthState by viewModel.networkHealthState.observeAsState(com.xelth.eckwms_movfast.ui.data.NetworkHealthState.Checking)
    val deviceRegistrationStatus by viewModel.deviceRegistrationStatus.observeAsState("unknown")

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
            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = {
                    navController.navigate("cameraScanScreen")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusable(false)  // Prevent hardware scanner key from triggering this button
            ) {
                Text("Scan with Camera")
            }
            Button(
                onClick = {
                    navController.navigate("cameraScanScreen?scan_mode=direct_upload")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusable(false)  // Prevent hardware scanner key from triggering this button
                ,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Camera Upload")
            }
            OutlinedButton(
                onClick = {
                    navController.navigate("pairingScreen")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusable(false)  // Prevent hardware scanner key from triggering this button
            ) {
                Text("Connect to Server")
            }
        }
    }
}

@Composable
fun WorkflowDrivenUI(
    viewModel: ScanRecoveryViewModel,
    navController: androidx.navigation.NavController,
    onNavigateToCamera: () -> Unit
) {
    val workflowState by viewModel.workflowState.observeAsState()

    if (workflowState?.isActive == true) {
        val currentState = workflowState!!
        val currentStep = currentState.currentStep!!

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(currentState.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(currentState.instruction, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)

                when (currentStep.action) {
                    "scanBarcode" -> Text("Use hardware scanner button to scan", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    "captureImage" -> Button(
                        onClick = onNavigateToCamera,
                        modifier = Modifier.focusable(false)
                    ) { Text("Capture Photo") }
                    "showMap" -> {
                        val whId = currentStep.variable ?: "1"
                        // TODO: Extract target location barcode from step params when WorkflowStep has params map
                        // For now, placeholder for demo
                        val target = "p-LOC-001"
                        
                        Button(
                            onClick = { 
                                navController.navigate("warehouseMap/$whId?target=$target") 
                            },
                            modifier = Modifier.focusable(false)
                        ) { Text("Show Map") }
                    }
                    "showUI" -> Button(
                        onClick = { viewModel.endWorkflowLoop() },
                        modifier = Modifier.focusable(false)
                    ) { Text("OK") }
                }

                if (currentState.showEndSessionButton) {
                    OutlinedButton(
                        onClick = { viewModel.endWorkflowLoop() },
                        modifier = Modifier.focusable(false)
                    ) {
                        Text(currentState.endSessionButtonLabel)
                    }
                }
            }
        }
    } else {
        Button(
            onClick = { viewModel.startWorkflow(DEVICE_RECEIVING_WORKFLOW_JSON) },
            modifier = Modifier.focusable(false)
        ) {
            Text("Start Device Receiving Workflow")
        }
    }
}

// Hardcoded workflow for now. In the future, this will be fetched from the server.
const val DEVICE_RECEIVING_WORKFLOW_JSON = """
{
  "workflowName": "DeviceReceiving",
  "version": "1.0",
  "steps": [
    {
      "stepId": "1",
      "action": "scanBarcode",
      "ui": {
        "title": "Parcel Receiving",
        "instruction": "Scan the tracking number on the parcel."
      },
      "variable": "parcelTrackingNumber"
    },
    {
      "stepId": "2",
      "action": "captureImage",
      "ui": {
        "title": "Parcel Photo",
        "instruction": "Take a photo of the parcel's condition."
      },
      "upload": {
        "reason": "parcel_condition",
        "relatedTo": "parcelTrackingNumber"
      }
    },
    {
      "stepId": "3",
      "action": "scanBarcode",
      "ui": {
        "title": "Scan Devices",
        "instruction": "Scan the serial numbers of all devices inside the parcel. Press 'Finish' when done."
      },
      "variable": "deviceSerialNumbers",
      "loop": {
        "condition": "user_ends_session",
        "endButtonLabel": "Finish Scanning Devices"
      }
    },
    {
      "stepId": "4",
      "action": "showUI",
      "ui": {
        "title": "Finished",
        "instruction": "Receiving complete for parcel {{parcelTrackingNumber}}. Scanned {{deviceSerialNumbers.size}} devices."
      }
    }
  ]
}
"""

@Composable
fun ActiveOrderCard(viewModel: ScanRecoveryViewModel) {
    val activeOrderId by viewModel.activeOrderId.observeAsState()

    AnimatedVisibility(visible = activeOrderId != null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Active Order:", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text(
                        text = activeOrderId ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                Button(
                    onClick = { viewModel.endActiveOrderSession() },
                    modifier = Modifier.focusable(false),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary)
                ) {
                    Text("End")
                }
            }
        }
    }
}
