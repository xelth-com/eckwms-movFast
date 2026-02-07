package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.xelth.eckwms_movfast.api.ScanApiService
import com.xelth.eckwms_movfast.api.ScanResult
import kotlinx.coroutines.launch
import org.json.JSONObject

data class DiscrepancyItem(
    val id: String,
    val productName: String,
    val productCode: String,
    val locationName: String,
    val expectedQty: Double,
    val countedQty: Double,
    val delta: Double,
    val deviceId: String,
    val status: String,
    val createdAt: String,
    val notes: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QcScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val api = remember { ScanApiService(context) }
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Pending", "Reviewed", "Stats")

    var discrepancies by remember { mutableStateOf(emptyList<DiscrepancyItem>()) }
    var isLoading by remember { mutableStateOf(true) }
    var stats by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun loadData(status: String?) {
        scope.launch {
            isLoading = true
            errorMsg = null
            try {
                val statusParam = when (selectedTab) {
                    0 -> "pending"
                    1 -> "reviewed"
                    else -> null
                }
                val result = api.fetchDiscrepancies(statusParam, 100)
                if (result is ScanResult.Success) {
                    val json = JSONObject(result.data)
                    val items = json.optJSONArray("items") ?: org.json.JSONArray()
                    discrepancies = (0 until items.length()).map { i ->
                        val d = items.getJSONObject(i)
                        DiscrepancyItem(
                            id = d.optString("id"),
                            productName = d.optString("product_name", ""),
                            productCode = d.optString("product_code", ""),
                            locationName = d.optString("location_name", ""),
                            expectedQty = d.optDouble("expected_qty", 0.0),
                            countedQty = d.optDouble("counted_qty", 0.0),
                            delta = d.optDouble("delta", 0.0),
                            deviceId = d.optString("device_id", ""),
                            status = d.optString("status", ""),
                            createdAt = d.optString("created_at", ""),
                            notes = d.optString("notes", "")
                        )
                    }
                } else if (result is ScanResult.Error) {
                    errorMsg = result.message
                }
            } catch (e: Exception) {
                errorMsg = e.message
            }
            isLoading = false
        }
    }

    fun loadStats() {
        scope.launch {
            try {
                val result = api.fetchDiscrepancyStats()
                if (result is ScanResult.Success) {
                    val json = JSONObject(result.data)
                    stats = mapOf(
                        "pending" to json.optInt("pending", 0),
                        "reviewed" to json.optInt("reviewed", 0),
                        "resolved" to json.optInt("resolved", 0)
                    )
                }
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab < 2) loadData(null)
        else loadStats()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QC Discrepancies", fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A1A),
                    titleContentColor = Color(0xFFF44336)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF121212))
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF1A1A1A),
                contentColor = Color(0xFFF44336)
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontFamily = FontFamily.Monospace) }
                    )
                }
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFF44336))
                }
            } else if (errorMsg != null) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(errorMsg ?: "Error", color = Color.Red, fontFamily = FontFamily.Monospace)
                }
            } else if (selectedTab < 2) {
                // Discrepancy list
                if (discrepancies.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No ${tabs[selectedTab].lowercase()} discrepancies",
                            color = Color.Gray, fontFamily = FontFamily.Monospace)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(discrepancies) { disc ->
                            DiscrepancyCard(disc, api, scope) { loadData(null) }
                        }
                    }
                }
            } else {
                // Stats tab
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Discrepancy Summary", color = Color.White,
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    StatRow("Pending", stats["pending"] ?: 0, Color(0xFFFF9800))
                    StatRow("Reviewed", stats["reviewed"] ?: 0, Color(0xFF2196F3))
                    StatRow("Resolved", stats["resolved"] ?: 0, Color(0xFF4CAF50))
                    val total = stats.values.sum()
                    HorizontalDivider(color = Color(0xFF333333))
                    StatRow("Total", total, Color.White)
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, count: Int, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = color, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
        Text(count.toString(), color = color, fontFamily = FontFamily.Monospace,
            fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DiscrepancyCard(
    disc: DiscrepancyItem,
    api: ScanApiService,
    scope: kotlinx.coroutines.CoroutineScope,
    onRefresh: () -> Unit
) {
    val deltaColor = if (disc.delta > 0) Color(0xFF4CAF50) else Color(0xFFF44336)
    val deltaSign = if (disc.delta > 0) "+" else ""

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(disc.productName.ifEmpty { disc.productCode },
                color = Color.White, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            Text(disc.locationName,
                color = Color(0xFF888888), fontFamily = FontFamily.Monospace, fontSize = 12.sp)

            Spacer(Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("Server", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text(fmtQty(disc.expectedQty), color = Color(0xFFBBBBBB),
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("Counted", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text(fmtQty(disc.countedQty), color = Color.White,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("Delta", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("$deltaSign${fmtQty(disc.delta)}", color = deltaColor,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(disc.createdAt.take(16).replace("T", " "),
                    color = Color(0xFF666666), fontSize = 10.sp, fontFamily = FontFamily.Monospace)

                if (disc.status == "pending") {
                    TextButton(
                        onClick = {
                            scope.launch {
                                api.reviewDiscrepancy(disc.id, "Reviewed from PDA")
                                onRefresh()
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF2196F3))
                    ) {
                        Text("REVIEW", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun fmtQty(qty: Double): String =
    if (qty % 1.0 == 0.0) qty.toInt().toString() else "%.1f".format(qty)
