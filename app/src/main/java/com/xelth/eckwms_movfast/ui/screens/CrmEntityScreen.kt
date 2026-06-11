package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * CRM Entity Screen — offline-capable view for CRM entities (company/person/opportunity).
 * Accepts entityType and entityId from SmartTag QR decryption.
 * Saves updates as `crm_update` entries in the sync queue for later upload.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrmEntityScreen(
    entityType: String,
    entityId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var notes by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    // Server/cache entity data (network-first, Room cache fallback)
    var entityName by remember { mutableStateOf("") }
    var entityFetchedAt by remember { mutableStateOf(0L) }
    var isLoadingEntity by remember { mutableStateOf(true) }

    LaunchedEffect(entityType, entityId) {
        isLoadingEntity = true
        try {
            val app = context.applicationContext as com.xelth.eckwms_movfast.EckwmsApp
            val entity = app.repository.getCrmEntity(entityType, entityId)
            if (entity != null) {
                entityName = entity.name
                entityFetchedAt = entity.fetchedAt
                if (status.isBlank() && entity.status.isNotBlank()) {
                    status = entity.status
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("CrmEntity", "Entity fetch failed: ${e.message}")
        } finally {
            isLoadingEntity = false
        }
    }

    val displayType = when (entityType) {
        "company" -> "Company"
        "person" -> "Person"
        "opp" -> "Opportunity"
        else -> entityType.replaceFirstChar { it.uppercase() }
    }

    val typeColor = when (entityType) {
        "company" -> Color(0xFF1565C0)
        "person" -> Color(0xFF2E7D32)
        "opp" -> Color(0xFFE65100)
        else -> Color(0xFF424242)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            displayType,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            entityId,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = typeColor,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Entity info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = typeColor.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Type", fontSize = 12.sp, color = Color.Gray)
                    Text(displayType, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    if (isLoadingEntity) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Loading entity…", fontSize = 13.sp, color = Color.Gray)
                        }
                        Spacer(Modifier.height(8.dp))
                    } else if (entityName.isNotBlank()) {
                        Text("Name", fontSize = 12.sp, color = Color.Gray)
                        Text(entityName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("Entity ID", fontSize = 12.sp, color = Color.Gray)
                    Text(
                        entityId,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                    if (!isLoadingEntity && entityName.isBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "No server data — entity will be created on sync",
                            fontSize = 12.sp,
                            color = Color(0xFF9E9E9E)
                        )
                    }
                }
            }

            // Status selector
            Text("Status", fontWeight = FontWeight.SemiBold)
            val statusOptions = when (entityType) {
                "opp" -> listOf("", "Contacted", "Qualified", "Proposal", "Won", "Lost")
                "company" -> listOf("", "Active", "Inactive", "Prospect", "Partner")
                "person" -> listOf("", "Active", "Inactive", "VIP")
                else -> listOf("")
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                statusOptions.filter { it.isNotEmpty() }.forEach { option ->
                    FilterChip(
                        selected = status == option,
                        onClick = { status = if (status == option) "" else option },
                        label = { Text(option, fontSize = 12.sp) }
                    )
                }
            }

            // Notes field
            Text("Notes", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                placeholder = { Text("Add notes about this $displayType...") },
                maxLines = 8
            )

            // Save button
            Button(
                onClick = {
                    if (notes.isBlank() && status.isBlank()) return@Button
                    isSaving = true
                    scope.launch {
                        try {
                            val app = context.applicationContext as com.xelth.eckwms_movfast.EckwmsApp
                            val changes = JSONObject().apply {
                                if (notes.isNotBlank()) put("notes", notes)
                                if (status.isNotBlank()) put("status", status)
                            }
                            app.repository.queueCrmUpdate(entityType, entityId, changes)
                            saved = true
                        } catch (e: Exception) {
                            android.util.Log.e("CrmEntity", "Failed to save: ${e.message}", e)
                        } finally {
                            isSaving = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !isSaving && !saved && (notes.isNotBlank() || status.isNotBlank()),
                colors = ButtonDefaults.buttonColors(containerColor = typeColor)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else if (saved) {
                    Icon(Icons.Filled.Check, "Saved", tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Queued for sync", color = Color.White)
                } else {
                    Text("Save Offline", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            if (saved) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
                ) {
                    Text(
                        "Update saved locally. Will sync when server is reachable.",
                        modifier = Modifier.padding(12.dp),
                        color = Color(0xFF2E7D32),
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
