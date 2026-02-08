package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.xelth.eckwms_movfast.api.ScanApiService
import com.xelth.eckwms_movfast.api.ScanResult
import com.xelth.eckwms_movfast.ui.data.AttachmentInfo
import com.xelth.eckwms_movfast.utils.SettingsManager
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class BreadcrumbItem(val id: Long, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val api = remember { ScanApiService(context) }
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Locations", "Products")

    // Location tab state
    var breadcrumbs by remember { mutableStateOf(listOf<BreadcrumbItem>()) }
    var locations by remember { mutableStateOf(emptyList<JSONObject>()) }
    var locationContents by remember { mutableStateOf(emptyList<JSONObject>()) }
    var showContents by remember { mutableStateOf(false) }

    // Product tab state
    var searchQuery by remember { mutableStateOf("") }
    var products by remember { mutableStateOf(emptyList<JSONObject>()) }
    var productLocations by remember { mutableStateOf(emptyList<JSONObject>()) }
    var selectedProduct by remember { mutableStateOf<JSONObject?>(null) }
    var productAttachments by remember { mutableStateOf(emptyList<AttachmentInfo>()) }

    var isLoading by remember { mutableStateOf(false) }

    fun loadLocations(parentId: Long?) {
        scope.launch {
            isLoading = true
            showContents = false
            locationContents = emptyList()
            val param = if (parentId != null) "?parent_id=$parentId" else ""
            val result = api.fetchExplorerData("/api/explorer/locations$param")
            if (result is ScanResult.Success) {
                locations = parseJsonArray(result.data)
            }
            isLoading = false
        }
    }

    fun loadLocationContents(locationId: Long) {
        scope.launch {
            isLoading = true
            val result = api.fetchExplorerData("/api/explorer/locations/$locationId/contents")
            if (result is ScanResult.Success) {
                locationContents = parseJsonArray(result.data)
                showContents = true
            }
            isLoading = false
        }
    }

    fun searchProducts(query: String) {
        scope.launch {
            isLoading = true
            selectedProduct = null
            productLocations = emptyList()
            productAttachments = emptyList()
            val result = api.fetchExplorerData("/api/explorer/products?q=$query&limit=50")
            if (result is ScanResult.Success) {
                products = parseJsonArray(result.data)
            }
            isLoading = false
        }
    }

    fun loadProductLocations(productId: Long) {
        scope.launch {
            isLoading = true
            val result = api.fetchExplorerData("/api/explorer/products/$productId/locations")
            if (result is ScanResult.Success) {
                productLocations = parseJsonArray(result.data)
            }
            isLoading = false
        }
    }

    fun loadProductDetails(prod: JSONObject) {
        selectedProduct = prod
        productAttachments = emptyList()
        loadProductLocations(prod.optLong("id"))

        // Fetch attachments using barcode (server auto-resolves EAN -> smart code)
        val barcode = prod.optString("barcode", "")
        val lookupId = if (barcode.isNotEmpty() && barcode != "false") barcode else prod.optString("id", "")
        if (lookupId.isNotEmpty()) {
            scope.launch {
                productAttachments = api.fetchAttachments("product", lookupId)
            }
        }
    }

    // Initial load
    LaunchedEffect(selectedTab) {
        if (selectedTab == 0) {
            breadcrumbs = emptyList()
            loadLocations(null)
        } else {
            products = emptyList()
            selectedProduct = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Explorer", fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A1A),
                    titleContentColor = Color(0xFF2196F3)
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
                contentColor = Color(0xFF2196F3)
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontFamily = FontFamily.Monospace) }
                    )
                }
            }

            if (selectedTab == 0) {
                // Breadcrumb bar
                if (breadcrumbs.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A1A1A))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Root", color = Color(0xFF2196F3), fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.clickable {
                                breadcrumbs = emptyList()
                                loadLocations(null)
                            })
                        breadcrumbs.forEachIndexed { idx, bc ->
                            Text(" > ", color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text(bc.name, color = if (idx == breadcrumbs.lastIndex) Color.White else Color(0xFF2196F3),
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.clickable {
                                    breadcrumbs = breadcrumbs.take(idx + 1)
                                    loadLocations(bc.id)
                                })
                        }
                    }
                }

                // Location content or children
                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF2196F3))
                    }
                } else if (showContents && locationContents.isNotEmpty()) {
                    // Show products at this location
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item {
                            Text("Contents (${locationContents.size} items)",
                                color = Color(0xFF888888), fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(bottom = 4.dp))
                        }
                        items(locationContents) { item ->
                            ProductAtLocationCard(item)
                        }
                    }
                } else if (locations.isEmpty() && !showContents) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No locations found", color = Color.Gray, fontFamily = FontFamily.Monospace)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(locations) { loc ->
                            LocationCard(loc,
                                onDrillDown = { id, name ->
                                    breadcrumbs = breadcrumbs + BreadcrumbItem(id, name)
                                    loadLocations(id)
                                },
                                onViewContents = { id ->
                                    loadLocationContents(id)
                                }
                            )
                        }
                    }
                }
            } else {
                // Products tab
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search products...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF1E1E1E),
                        unfocusedContainerColor = Color(0xFF1E1E1E),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFF2196F3)
                    )
                )

                // Search button
                TextButton(
                    onClick = { if (searchQuery.isNotBlank()) searchProducts(searchQuery) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text("SEARCH", fontFamily = FontFamily.Monospace, color = Color(0xFF2196F3))
                }

                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF2196F3))
                    }
                } else if (selectedProduct != null) {
                    // Show details for selected product
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(selectedProduct?.optString("name") ?: "",
                                    color = Color.White, fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                                TextButton(onClick = {
                                    selectedProduct = null
                                    productLocations = emptyList()
                                    productAttachments = emptyList()
                                }) {
                                    Text("BACK", color = Color(0xFF2196F3), fontFamily = FontFamily.Monospace)
                                }
                            }
                        }

                        // Gallery section
                        if (productAttachments.isNotEmpty()) {
                            item {
                                Text("Visual Evidence:", color = Color.Gray, fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace)
                                Spacer(modifier = Modifier.height(4.dp))
                                val serverUrl = SettingsManager.getServerUrl()
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.height(100.dp).fillMaxWidth()
                                ) {
                                    items(productAttachments) { att ->
                                        val url = "$serverUrl/api/files/${att.fileId}"
                                        Box(
                                            modifier = Modifier
                                                .size(100.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color.DarkGray)
                                        ) {
                                            AsyncImage(
                                                model = ImageRequest.Builder(context)
                                                    .data(url)
                                                    .crossfade(true)
                                                    .build(),
                                                contentDescription = "Attachment",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            if (att.isMain) {
                                                Box(
                                                    modifier = Modifier
                                                        .align(Alignment.BottomEnd)
                                                        .background(Color(0xFF4CAF50), RoundedCornerShape(topStart = 4.dp))
                                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                                ) {
                                                    Text("MAIN", color = Color.White, fontSize = 8.sp,
                                                        fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp),
                                    color = Color(0xFF333333))
                            }
                        }

                        items(productLocations) { loc ->
                            ProductLocationCard(loc)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(products) { prod ->
                            ProductCard(prod) {
                                loadProductDetails(prod)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationCard(loc: JSONObject, onDrillDown: (Long, String) -> Unit, onViewContents: (Long) -> Unit) {
    val id = loc.optLong("id")
    val name = loc.optString("name", "?")
    val childCount = loc.optInt("child_count", 0)
    val itemCount = loc.optInt("item_count", 0)
    val barcode = loc.optString("barcode", "")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, color = Color.White, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                if (barcode.isNotEmpty()) {
                    Text(barcode, color = Color(0xFF666666), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (childCount > 0) Text("$childCount sub-locations",
                        color = Color(0xFF888888), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    if (itemCount > 0) Text("$itemCount items",
                        color = Color(0xFF4CAF50), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (itemCount > 0) {
                    TextButton(onClick = { onViewContents(id) }) {
                        Text("ITEMS", color = Color(0xFF4CAF50), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
                if (childCount > 0) {
                    TextButton(onClick = { onDrillDown(id, name) }) {
                        Text("OPEN", color = Color(0xFF2196F3), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductAtLocationCard(item: JSONObject) {
    val name = item.optString("product_name", "?")
    val code = item.optString("default_code", "")
    val qty = item.optDouble("quantity", 0.0)
    val reserved = item.optDouble("reserved_qty", 0.0)
    val lot = item.optString("lot_name", "")
    val pkg = item.optString("package_name", "")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                if (code.isNotEmpty()) Text(code, color = Color(0xFF888888), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                if (lot.isNotEmpty()) Text("Lot: $lot", color = Color(0xFF666666), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                if (pkg.isNotEmpty()) Text("Pkg: $pkg", color = Color(0xFF666666), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtQty(qty), color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                if (reserved > 0) Text("rsv: ${fmtQty(reserved)}",
                    color = Color(0xFFFF9800), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ProductCard(prod: JSONObject, onClick: () -> Unit) {
    val name = prod.optString("name", "?")
    val code = prod.optString("default_code", "")
    val barcode = prod.optString("barcode", "")

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(name, color = Color.White, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (code.isNotEmpty()) Text(code, color = Color(0xFF888888), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                if (barcode.isNotEmpty()) Text(barcode, color = Color(0xFF666666), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ProductLocationCard(loc: JSONObject) {
    val name = loc.optString("location_name", "?")
    val barcode = loc.optString("barcode", "")
    val qty = loc.optDouble("quantity", 0.0)
    val reserved = loc.optDouble("reserved_qty", 0.0)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                if (barcode.isNotEmpty()) Text(barcode, color = Color(0xFF666666), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtQty(qty), color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                if (reserved > 0) Text("rsv: ${fmtQty(reserved)}",
                    color = Color(0xFFFF9800), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private fun fmtQty(qty: Double): String =
    if (qty % 1.0 == 0.0) qty.toInt().toString() else "%.1f".format(qty)

private fun parseJsonArray(data: String): List<JSONObject> {
    return try {
        val arr = JSONArray(data)
        (0 until arr.length()).map { arr.getJSONObject(it) }
    } catch (_: Exception) {
        emptyList()
    }
}
