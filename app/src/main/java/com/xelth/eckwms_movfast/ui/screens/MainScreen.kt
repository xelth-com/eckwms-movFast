package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel
import com.xelth.eckwms_movfast.ui.data.NetworkHealthState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    navController: NavController,
    viewModel: ScanRecoveryViewModel
) {
    val networkHealthState by viewModel.networkHealthState.observeAsState(NetworkHealthState.Checking)

    Scaffold(
        topBar = {
            MainTopBar(networkHealthState)
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                text = "Dashboard",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    DashboardCard(
                        title = "Scan",
                        subtitle = "Inventory & Lookup",
                        icon = Icons.Filled.Settings,
                        color = MaterialTheme.colorScheme.primary,
                        onClick = { navController.navigate("scanScreen") }
                    )
                }

                item {
                    DashboardCard(
                        title = "Restock",
                        subtitle = "Manual Orders",
                        icon = Icons.Filled.Add,
                        color = MaterialTheme.colorScheme.secondary,
                        onClick = { navController.navigate("restockScreen") }
                    )
                }

                item {
                    DashboardCard(
                        title = "AI Agent",
                        subtitle = "Ask Assistant",
                        icon = Icons.Filled.Info,
                        color = MaterialTheme.colorScheme.tertiary,
                        onClick = { navController.navigate("scanScreen") }
                    )
                }

                item {
                    DashboardCard(
                        title = "Settings",
                        subtitle = "Debug & Config",
                        icon = Icons.Outlined.Settings,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { navController.navigate("settings") }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(networkState: NetworkHealthState) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "eckWMS",
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Badge(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text("GO", modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        },
        actions = {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (networkState.isConnected()) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                modifier = Modifier.padding(end = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val color = if (networkState.isConnected()) Color(0xFF2E7D32) else Color(0xFFC62828)
                    val icon = if (networkState.isConnected()) "☁️" else "❌"

                    Text(text = icon, fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (networkState.isConnected()) "ONLINE" else "OFFLINE",
                        color = color,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    )
}

@Composable
fun DashboardCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    textColor: Color = Color.White,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .height(160.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            color.copy(alpha = 0.8f),
                            color
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                        .padding(8.dp)
                )

                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}
