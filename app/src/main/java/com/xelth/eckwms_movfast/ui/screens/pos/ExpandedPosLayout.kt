package com.xelth.eckwms_movfast.ui.screens.pos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.xelth.eckwms_movfast.ui.viewmodels.PosViewModel
import com.xelth.eckwms_movfast.utils.DeviceConfig

@Composable
fun ExpandedPosLayout(
    viewModel: PosViewModel,
    deviceConfig: DeviceConfig,
    onLogout: () -> Unit
) {
    // TODO: This layout will replicate the Electron version's UI for tablets.
    // It will feature a side-by-side view of categories, products, and the order.
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "Tablet Layout (Expanded) - Placeholder")
    }
}