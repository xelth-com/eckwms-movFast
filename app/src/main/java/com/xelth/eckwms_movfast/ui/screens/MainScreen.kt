package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel
import com.xelth.eckwms_movfast.ui.data.NetworkHealthState

enum class HexagonSide { FULL, LEFT, RIGHT }

class HexagonShape(private val side: HexagonSide = HexagonSide.FULL) : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        return Outline.Generic(
            path = hexagonPath(size, side)
        )
    }
}

private fun hexagonPath(size: androidx.compose.ui.geometry.Size, side: HexagonSide): Path {
    val width = size.width
    val height = size.height
    return Path().apply {
        when (side) {
            HexagonSide.FULL -> {
                moveTo(width * 0.5f, 0f)
                lineTo(width, height * 0.25f)
                lineTo(width, height * 0.75f)
                lineTo(width * 0.5f, height)
                lineTo(0f, height * 0.75f)
                lineTo(0f, height * 0.25f)
            }
            HexagonSide.LEFT -> {
                moveTo(0f, 0f)
                lineTo(width, height * 0.25f)
                lineTo(width, height * 0.75f)
                lineTo(0f, height)
            }
            HexagonSide.RIGHT -> {
                moveTo(width, 0f)
                lineTo(0f, height * 0.25f)
                lineTo(0f, height * 0.75f)
                lineTo(width, height)
            }
        }
        close()
    }
}

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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            HexagonalGrid(navController)
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
                shape = MaterialTheme.shapes.small,
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
fun HexagonalGrid(navController: NavController) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HexagonalButton(
                label = "Scan",
                colorHex = "#4A90E2",
                onClick = { navController.navigate("scanScreen") }
            )
            HexagonalButton(
                label = "Restock",
                colorHex = "#50E3C2",
                onClick = { navController.navigate("restockScreen") }
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HexagonalButton(
                label = "AI Agent",
                colorHex = "#F5A623",
                onClick = { navController.navigate("scanScreen") }
            )
            HexagonalButton(
                label = "Settings",
                colorHex = "#9013FE",
                onClick = { navController.navigate("settings") }
            )
        }
    }
}

@Composable
fun HexagonalButton(
    label: String,
    colorHex: String,
    onClick: () -> Unit
) {
    val backgroundColor = try {
        Color(android.graphics.Color.parseColor(colorHex))
    } catch (e: IllegalArgumentException) {
        Color.Gray
    }

    Box(
        modifier = Modifier
            .size(140.dp, 160.dp)
            .clip(HexagonShape(HexagonSide.FULL))
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                color = Color.White,
                textAlign = TextAlign.Center,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 24.sp
            )
        }
    }
}
