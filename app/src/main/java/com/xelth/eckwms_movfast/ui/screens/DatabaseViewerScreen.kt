package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xelth.eckwms_movfast.api.ScanApiService
import com.xelth.eckwms_movfast.data.local.AppDatabase
import com.xelth.eckwms_movfast.data.local.entity.LocationEntity
import com.xelth.eckwms_movfast.data.local.entity.ProductEntity
import com.xelth.eckwms_movfast.data.local.entity.ScanEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabaseViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val api = remember { ScanApiService(context) }
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Products", "Locations", "Scans")

    // Collect flows unconditionally (Compose requirement)
    val products by db.referenceDao().getAllProductsFlow().collectAsState(initial = emptyList())
    val locations by db.referenceDao().getAllLocationsFlow().collectAsState(initial = emptyList())

    var scans by remember { mutableStateOf(emptyList<ScanEntity>()) }
    var isSyncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }

    // Load scans (one-shot, no flow available for all scans)
    LaunchedEffect(Unit) {
        scans = db.scanDao().getAllScans()
    }

    fun syncData() {
        scope.launch {
            isSyncing = true
            syncMessage = null
            try {
                val prods = api.fetchProducts()
                if (prods.isNotEmpty()) {
                    db.referenceDao().clearProducts()
                    db.referenceDao().insertProducts(prods)
                }

                val locs = api.fetchLocations()
                if (locs.isNotEmpty()) {
                    db.referenceDao().clearLocations()
                    db.referenceDao().insertLocations(locs)
                }

                // Refresh scans too
                scans = db.scanDao().getAllScans()

                syncMessage = "Synced: ${prods.size} products, ${locs.size} locations"
            } catch (e: Exception) {
                syncMessage = "Sync error: ${e.message}"
            } finally {
                isSyncing = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline Database") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("< Back", color = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                actions = {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp).padding(end = 8.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    TextButton(onClick = { syncData() }, enabled = !isSyncing) {
                        Text("Sync", color = MaterialTheme.colorScheme.onPrimary)
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
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Stats Row
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatBadge("Products", products.size)
                StatBadge("Locations", locations.size)
                StatBadge("Scans", scans.size)
            }

            // Sync status message
            syncMessage?.let { msg ->
                Text(
                    text = msg,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (msg.startsWith("Sync error")) Color.Red else Color(0xFF4CAF50)
                )
            }

            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).background(Color(0xFF1A1A1A))) {
                when (selectedTab) {
                    0 -> ProductList(products)
                    1 -> LocationList(locations)
                    2 -> ScanList(scans)
                }
            }
        }
    }
}

@Composable
private fun StatBadge(label: String, count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProductList(products: List<ProductEntity>) {
    if (products.isEmpty()) {
        EmptyState("No products cached.\nTap Sync to download.")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(products, key = { it.id }) { p ->
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))) {
                Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                    Text(p.name, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = p.defaultCode.ifEmpty { "-" },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4CAF50),
                            fontFamily = FontFamily.Monospace
                        )
                        p.barcode?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Stock quantity
                        Text(
                            text = "Qty: ${p.qtyAvailable.toInt()}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (p.qtyAvailable > 0) Color(0xFF4CAF50) else Color(0xFFFF5722)
                        )
                        if (p.listPrice > 0) {
                            Text(
                                text = "%.2f".format(p.listPrice),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFFB74D)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationList(locs: List<LocationEntity>) {
    if (locs.isEmpty()) {
        EmptyState("No locations cached.\nTap Sync to download.")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(locs, key = { it.id }) { l ->
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))) {
                Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                    Text(
                        text = l.completeName.ifEmpty { l.name },
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = l.usage,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        l.barcode?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2196F3),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanList(scans: List<ScanEntity>) {
    if (scans.isEmpty()) {
        EmptyState("No scans in history.")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(scans, key = { it.id }) { scan ->
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = scan.barcode,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = scan.type,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                    Text(
                        text = scan.status,
                        color = when (scan.status) {
                            "CONFIRMED" -> Color(0xFF4CAF50)
                            "FAILED" -> Color.Red
                            else -> Color(0xFFFFB74D)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = Color.Gray,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
