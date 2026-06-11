package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xelth.eckwms_movfast.ui.data.ConnectionType
import com.xelth.eckwms_movfast.ui.data.NetworkHealthState
import com.xelth.eckwms_movfast.utils.SettingsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkPanelSheet(
    networkHealthState: NetworkHealthState,
    deviceRegistrationStatus: String,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onRePair: () -> Unit
) {
    val localUrl = SettingsManager.getServerUrl()
    val globalUrl = SettingsManager.getGlobalServerUrl()
    val relayUrl = SettingsManager.getRelayUrl()
    val homeId = SettingsManager.getHomeInstanceId()

    val isApproved = deviceRegistrationStatus == "active" || deviceRegistrationStatus == "running"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1E1E1E)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Network Topology",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            // Registration Status
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusColor = if (isApproved) Color(0xFF4CAF50) else Color(0xFFF44336)
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(statusColor, RoundedCornerShape(50))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Device Registration: ${deviceRegistrationStatus.uppercase()}",
                    color = statusColor,
                    fontWeight = FontWeight.Bold
                )
            }

            HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f))

            // Local Server
            val localConfigured = localUrl.isNotEmpty()
            val localOnline = localConfigured && networkHealthState.connectionType == ConnectionType.LOCAL_IP
            NetworkNodeCard(
                title = "Primary Server (Local)",
                address = localUrl,
                status = if (!localConfigured) "NOT PAIRED" else if (localOnline) "ONLINE" else "OFFLINE",
                statusColor = if (!localConfigured) Color(0xFF757575) else if (localOnline) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
            )

            // Global Server
            val globalConfigured = globalUrl.isNotEmpty()
            val globalOnline = globalConfigured && networkHealthState.isConnected()
            NetworkNodeCard(
                title = "Fallback Server (Global)",
                address = globalUrl,
                status = if (!globalConfigured) "NOT PAIRED" else if (globalOnline) "ONLINE" else "OFFLINE",
                statusColor = if (!globalConfigured) Color(0xFF757575) else if (globalOnline) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
            )

            // Relay Network
            NetworkNodeCard(
                title = "Relay Network",
                address = relayUrl.ifEmpty { "https://9eck.com" },
                status = "STANDBY",
                statusColor = Color(0xFF2196F3)
            )

            // Home Instance ID
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Home Instance ID", color = Color.Gray, fontSize = 12.sp)
                    Text(
                        text = homeId.ifEmpty { "Not Bound" },
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onRefresh()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Refresh")
                }

                Button(
                    onClick = {
                        onRePair()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Re-Pair")
                }
            }
        }
    }
}

@Composable
fun NetworkNodeCard(title: String, address: String, status: String, statusColor: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.Gray, fontSize = 12.sp)
                Text(
                    text = address.ifEmpty { "Not Configured" },
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
            Text(
                text = status,
                color = statusColor,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
    }
}
