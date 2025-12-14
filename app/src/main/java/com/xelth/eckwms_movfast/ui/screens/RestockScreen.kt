package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.xelth.eckwms_movfast.ui.viewmodels.RestockViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestockScreen(navController: NavController, viewModel: RestockViewModel = viewModel()) {
    val restockList by viewModel.restockList.observeAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        viewModel.loadWorkflow()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Manual Restock Order") }) }
    ) {
        Column(modifier = Modifier.padding(it).padding(16.dp)) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(restockList) { item ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text = item.barcode, modifier = Modifier.weight(1f))
                            Text(text = "Qty: ${item.quantity}", modifier = Modifier.padding(horizontal = 8.dp))
                            Text(text = "Note: ${item.note}", modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { viewModel.triggerScan() }, modifier = Modifier.fillMaxWidth()) {
                Text("Scan Next Part")
            }
            Button(onClick = { viewModel.submitOrder() }, modifier = Modifier.fillMaxWidth()) {
                Text("Submit Order to Server")
            }
        }
    }
}
