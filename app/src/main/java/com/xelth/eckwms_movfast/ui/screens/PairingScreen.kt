package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(viewModel: ScanRecoveryViewModel, navController: NavController) {
    val pairingStatus by viewModel.pairingStatus.observeAsState("Ready to pair device with server.")
    val isPairing by viewModel.isPairing.observeAsState(false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server Pairing") },
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Connect to eckWMS Server",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "To securely connect this device to your eckWMS server, scan the pairing QR code displayed on the server's admin panel.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isPairing) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text(
                        text = pairingStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        fontWeight = if (isPairing) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            pairingStatus.contains("success", ignoreCase = true) ->
                                MaterialTheme.colorScheme.primary
                            pairingStatus.contains("error", ignoreCase = true) ||
                            pairingStatus.contains("failed", ignoreCase = true) ->
                                MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { navController.navigate("cameraScanScreen?scan_mode=pairing") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isPairing
                    ) {
                        Text("Scan Pairing QR Code")
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
