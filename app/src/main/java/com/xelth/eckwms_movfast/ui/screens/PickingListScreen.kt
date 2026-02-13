package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xelth.eckwms_movfast.data.local.entity.PickingOrderEntity
import com.xelth.eckwms_movfast.ui.viewmodels.PickingUiState
import com.xelth.eckwms_movfast.ui.viewmodels.PickingViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickingListScreen(
    viewModel: PickingViewModel,
    onPickingSelected: (Long) -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val pickings by viewModel.activePickings.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadActivePickings()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Picking Orders") },
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
        ) {
            when (uiState) {
                PickingUiState.LOADING -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                PickingUiState.PICK_LIST -> {
                    if (pickings.isEmpty()) {
                        Text(
                            "No active pickings",
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(pickings) { picking ->
                                PickingCard(
                                    picking = picking,
                                    onClick = { onPickingSelected(picking.id) }
                                )
                            }
                        }
                    }
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun PickingCard(
    picking: PickingOrderEntity,
    onClick: () -> Unit
) {
    val isUrgent = picking.priority == "1"
    val progress = if (picking.lineCount > 0) picking.pickedCount.toFloat() / picking.lineCount else 0f
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isUrgent)
                MaterialTheme.colorScheme.errorContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = picking.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (isUrgent) {
                    Surface(
                        color = MaterialTheme.colorScheme.error,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            "URGENT",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onError,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (!picking.origin.isNullOrEmpty()) {
                Text(
                    text = "Order: ${picking.origin}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (!picking.partnerName.isNullOrEmpty()) {
                Text(
                    text = picking.partnerName!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (picking.scheduledDate > 0) {
                Text(
                    text = "Scheduled: ${dateFormat.format(Date(picking.scheduledDate))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Progress bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.weight(1f).height(8.dp),
                    color = if (isUrgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = Color.Gray.copy(alpha = 0.3f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${picking.pickedCount}/${picking.lineCount}",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
