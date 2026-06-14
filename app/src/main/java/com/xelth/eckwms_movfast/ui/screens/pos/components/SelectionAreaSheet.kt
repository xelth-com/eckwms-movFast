package com.xelth.eckwms_movfast.ui.screens.pos.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.rotate
import com.xelth.eckwms_movfast.ui.data.ConnectionType
import com.xelth.eckwms_movfast.ui.data.NetworkHealthState
import com.xelth.eckwms_movfast.ui.model.CategoryItem
import com.xelth.eckwms_movfast.ui.model.ProductItem
import com.xelth.eckwms_movfast.ui.screens.pos.components.icons.HandIcon
import com.xelth.eckwms_movfast.ui.screens.pos.grid.RenderCell
import com.xelth.eckwms_movfast.ui.screens.pos.grid.SlotType
import com.xelth.eckwms_movfast.ui.viewmodels.AppUser
import com.xelth.eckwms_movfast.ui.viewmodels.HalfButtonState
import com.xelth.eckwms_movfast.ui.viewmodels.PosViewModel
import com.xelth.eckwms_movfast.ui.viewmodels.SelectionAreaState

@Composable
fun SelectionAreaSheet(
    renderCells: List<RenderCell>,
    cellWidth: Dp = 140.dp,
    cellHeight: Dp = 80.dp,
    buttonGap: Dp = 6.dp,
    networkState: NetworkHealthState = NetworkHealthState.Checking,
    regStatus: String = "unknown",
    currentUser: AppUser? = null,
    viewingUser: AppUser? = null,
    exitButton: HalfButtonState? = null,
    onButtonClick: (String) -> Unit = {},
    onButtonLongClick: (String) -> Unit = {},
    onNetworkIndicatorClick: () -> Unit = {},
    onNetworkIndicatorLongClick: () -> Unit = {},
    // PUSH-TO-TALK mic button (action == "trip_mic"): press/release callbacks
    onMicPress: () -> Unit = {},
    onMicRelease: () -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxSize()) {

        // 1. Grid Layer
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Identify last row index
            val maxRow = renderCells.maxOfOrNull { it.logicalPosition.first } ?: 0

            renderCells.forEach { cell ->
                val content = cell.content
                val slotType = (cell.geometryMetadata["slotType"] as? SlotType) ?: SlotType.DEAD
                val offsetX = cell.cssPosition.x
                val offsetY = cell.cssPosition.y
                val (row, col) = cell.logicalPosition

                val isBottomRow = row == maxRow

                // Handle DEAD slots in bottom row as Indicators
                if (slotType == SlotType.DEAD) {
                    if (isBottomRow) {
                        IndicatorButton(
                            modifier = Modifier
                                .size(width = cellWidth, height = cellHeight)
                                .offset(x = offsetX, y = offsetY),
                            isActive = false,
                            defaultColor = Color(0xFF1E1E1E)
                        )
                    }
                    return@forEach
                }

                val buttonSide = when(slotType) {
                    SlotType.FULL -> HexagonSide.FULL
                    SlotType.HALF_LEFT -> HexagonSide.LEFT
                    SlotType.HALF_RIGHT -> HexagonSide.RIGHT
                    else -> HexagonSide.FULL
                }

                val buttonWidth = if (buttonSide == HexagonSide.FULL) {
                    cellWidth
                } else {
                    (cellWidth / 2) - 1.dp
                }

                // First HALF_LEFT (row=0, col=0) -> Network Indicator (clickable)
                if (slotType == SlotType.HALF_LEFT && row == 0) {
                    NetworkIndicatorButton(
                        modifier = Modifier
                            .size(width = buttonWidth, height = cellHeight)
                            .offset(x = offsetX, y = offsetY),
                        side = buttonSide,
                        networkState = networkState,
                        regStatus = regStatus,
                        onClick = onNetworkIndicatorClick,
                        onLongClick = onNetworkIndicatorLongClick
                    )
                    return@forEach
                }

                // User Profile: HALF_LEFT at row=2 — native reactive component
                if (slotType == SlotType.HALF_LEFT && row == 2) {
                    UserIndicatorButton(
                        modifier = Modifier
                            .size(width = buttonWidth, height = cellHeight)
                            .offset(x = offsetX, y = offsetY),
                        side = buttonSide,
                        currentUser = currentUser,
                        viewingUser = viewingUser,
                        onClick = { onButtonClick("act_user_profile") },
                        onLongClick = { onButtonLongClick("act_user_profile") }
                    )
                    return@forEach
                }

                // EXIT button: HALF_RIGHT at row=1 — native reactive component
                if (slotType == SlotType.HALF_RIGHT && row == 1 && exitButton != null) {
                    HexagonalButton(
                        modifier = Modifier
                            .size(width = buttonWidth, height = cellHeight)
                            .offset(x = offsetX, y = offsetY),
                        label = exitButton.label,
                        colorHex = exitButton.colorHex,
                        textColorHex = exitButton.textColorHex,
                        enabled = exitButton.enabled,
                        side = buttonSide,
                        onClick = { onButtonClick(exitButton.action) },
                        onLongClick = { onButtonLongClick(exitButton.action) }
                    )
                    return@forEach
                }

                // Skip any other unhandled HALF slots
                if (slotType == SlotType.HALF_LEFT || slotType == SlotType.HALF_RIGHT) {
                    return@forEach
                }

                // Regular buttons logic
                var label = ""
                var color = "#3a3a3a"
                var enabled = false

                when {
                    content is Map<*, *> && content["type"] == "button" -> {
                        label = content["label"] as? String ?: ""
                        color = content["color"] as? String ?: "#3a3a3a"
                        enabled = true
                    }
                    else -> {
                        label = ""
                        color = "#2a2a2a"
                        enabled = false
                    }
                }

                val action = if (content is Map<*, *>) content["action"] as? String else null
                val textColor = if (content is Map<*, *>) content["textColor"] as? String else null

                HexagonalButton(
                    modifier = Modifier
                        .size(width = buttonWidth, height = cellHeight)
                        .offset(x = offsetX, y = offsetY),
                    label = label,
                    colorHex = color,
                    textColorHex = textColor,
                    enabled = enabled,
                    side = buttonSide,
                    onClick = {
                        if (enabled && action != null) onButtonClick(action)
                    },
                    onLongClick = {
                        if (enabled && action != null) onButtonLongClick(action)
                    },
                    onPress = if (action == "trip_mic") onMicPress else null,
                    onRelease = if (action == "trip_mic") onMicRelease else null
                )
            }
        }

        // Gradient removed — clean boundary between console and grid
    }
}

/**
 * Network indicator inside a HALF_LEFT hexagonal button.
 * Shows connection icon, server hash, and latency.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NetworkIndicatorButton(
    modifier: Modifier = Modifier,
    side: HexagonSide,
    networkState: NetworkHealthState,
    regStatus: String,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {}
) {
    val isApproved = regStatus == "active" || regStatus == "running"

    val bgColor = when {
        !isApproved -> Color(0xFF8B0000)
        !networkState.isConnected() -> Color(0xFF3a3a3a)
        networkState.connectionType == ConnectionType.LOCAL_IP -> Color(0xFF1B5E20)
        else -> Color(0xFF5D4037)
    }

    val textColor = when {
        !isApproved -> Color(0xFFF44336)
        !networkState.isConnected() -> Color(0xFF9E9E9E)
        networkState.connectionType == ConnectionType.LOCAL_IP -> Color(0xFF4CAF50)
        else -> Color(0xFFFFEB3B)
    }

    Box(
        modifier = modifier
            .clip(HexagonShape(side))
            .background(bgColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (networkState.isConnected()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 2.dp)
            ) {
                Text(
                    text = networkState.serverHash,
                    color = textColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    lineHeight = 12.sp
                )
                if (networkState.latencyMs > 0) {
                    Text(
                        text = "${networkState.latencyMs}ms",
                        color = textColor.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        lineHeight = 11.sp
                    )
                }
            }
        } else {
            Text(
                text = "OFF",
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * User Profile indicator inside a HALF_LEFT hexagonal button.
 * Shows current user or Login prompt. Native reactive component.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserIndicatorButton(
    modifier: Modifier = Modifier,
    side: HexagonSide,
    currentUser: AppUser?,
    viewingUser: AppUser?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val isLoggedIn = currentUser != null
    val isActingAsSelf = isLoggedIn && currentUser?.id == viewingUser?.id

    val bgColor = when {
        !isLoggedIn -> Color(0xFF8B0000)     // Dark red: not logged in
        isActingAsSelf -> Color(0xFF1B5E20)  // Dark green: acting as self
        else -> Color(0xFF5D4037)            // Dark brown: viewing another user
    }

    val textColor = when {
        !isLoggedIn -> Color.White
        isActingAsSelf -> Color(0xFF4CAF50)  // Light green
        else -> Color(0xFFFFEB3B)            // Yellow
    }

    val label = (viewingUser ?: currentUser)?.getDisplayLabel() ?: "LOG\nIN"

    Box(
        modifier = modifier
            .clip(HexagonShape(side))
            .background(bgColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            textAlign = TextAlign.Center,
            fontSize = when {
                label.contains("\n") -> 14.sp
                label.length > 7 -> 12.sp
                label.length > 5 -> 14.sp
                else -> 16.sp
            },
            lineHeight = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun IndicatorButton(
    modifier: Modifier = Modifier,
    isActive: Boolean,
    defaultColor: Color
) {
    val targetColor = if (isActive) Color.Green else defaultColor
    val animatedColor by animateColorAsState(targetValue = targetColor, animationSpec = tween(500))

    Box(
        modifier = modifier
            .clip(HexagonShape(HexagonSide.FULL))
            .background(animatedColor.copy(alpha = 0.3f))
    )
}

/**
 * POS-specific SelectionAreaSheet overload.
 * Handles CategoryItem, ProductItem, and POS system actions via PosViewModel.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectionAreaSheet(
    viewModel: PosViewModel,
    renderCells: List<RenderCell>
) {
    val selectionAreaState by viewModel.selectionAreaState.collectAsState()
    val isLeftHandedMode by viewModel.isLeftHandedMode.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        renderCells.forEach { cell ->
            val content = cell.content
            var label = ""
            var color = "#3a3a3a"
            var enabled = false
            val slotType = (cell.geometryMetadata["slotType"] as? SlotType) ?: SlotType.DEAD

            if (slotType == SlotType.DEAD) {
                return@forEach
            }

            when {
                content is CategoryItem -> {
                    label = content.name
                    color = "#5A4B35"
                    enabled = true
                }
                content is ProductItem -> {
                    label = content.name
                    color = "#4A5D23"
                    enabled = true
                }
                content is Map<*, *> && content["type"] == "placeholder" -> {
                    label = ""
                    color = "#404040"
                    enabled = true
                }
                content is Map<*, *> && content["type"] == "system" -> {
                    val subtype = content["subtype"] as? String ?: ""
                    label = content["label"] as? String ?: ""
                    color = when (subtype) {
                        "button" -> "#4A5D23"
                        else -> "#2c2c2c"
                    }
                    enabled = true
                }
            }

            val offsetX = cell.cssPosition.x
            val offsetY = cell.cssPosition.y
            val cellWidth = viewModel.gridManager.config.cellWidth
            val cellHeight = viewModel.gridManager.config.cellHeight

            val buttonSide = when(slotType) {
                SlotType.FULL -> HexagonSide.FULL
                SlotType.HALF_LEFT -> HexagonSide.LEFT
                SlotType.HALF_RIGHT -> HexagonSide.RIGHT
                else -> HexagonSide.FULL
            }

            val buttonWidth = if (buttonSide == HexagonSide.FULL) {
                cellWidth
            } else {
                (cellWidth / 2) - 1.dp
            }

            if (content is Map<*, *> && content["subtype"] == "hand_toggle") {
                Box(
                    modifier = Modifier
                        .size(width = buttonWidth, height = cellHeight)
                        .offset(x = offsetX, y = offsetY)
                        .clip(HexagonShape(buttonSide))
                        .background(Color(android.graphics.Color.parseColor(color)))
                        .combinedClickable(
                            enabled = enabled,
                            onClick = {
                                val action = content["action"] as? String ?: ""
                                viewModel.onSystemAction(action)
                            },
                            onLongClick = {
                                viewModel.toggleHandMode()
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    HandIcon(
                        modifier = Modifier.rotate(if (isLeftHandedMode) 180f else 0f),
                        color = Color.White
                    )
                }
            } else {
                HexagonalButton(
                    modifier = Modifier
                        .size(width = buttonWidth, height = cellHeight)
                        .offset(x = offsetX, y = offsetY),
                    label = label,
                    colorHex = color,
                    enabled = enabled,
                    side = buttonSide,
                    onClick = {
                        if (enabled) {
                            when (content) {
                                is CategoryItem -> viewModel.onCategoryClick(content)
                                is ProductItem -> viewModel.onProductClick(content)
                                is Map<*, *> -> {
                                    if (content["type"] == "system") {
                                        val action = content["action"] as? String ?: ""
                                        if (action == "back" && selectionAreaState is SelectionAreaState.Products) {
                                            viewModel.onBackToCategories()
                                        } else {
                                            viewModel.onSystemAction(action)
                                        }
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}
