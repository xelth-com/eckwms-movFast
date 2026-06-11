package com.xelth.eckwms_movfast.ui.screens

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xelth.eckwms_movfast.ui.screens.pos.CompactPosLayout
import com.xelth.eckwms_movfast.ui.screens.pos.ExpandedPosLayout
import com.xelth.eckwms_movfast.ui.viewmodels.PosViewModel
import com.xelth.eckwms_movfast.ui.viewmodels.PosViewModelFactory
import com.xelth.eckwms_movfast.utils.DeviceType
import com.xelth.eckwms_movfast.utils.getAppDeviceConfig
import com.xelth.eckwms_movfast.utils.getAppDeviceInfo

@Composable
fun PosScreen(
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: PosViewModel = viewModel(
        factory = PosViewModelFactory(context.applicationContext as Application)
    )
    val deviceInfo = context.getAppDeviceInfo()
    val deviceConfig = context.getAppDeviceConfig()

    when (deviceInfo.deviceType) {
        DeviceType.NEBULAR_GEMINI_D5, DeviceType.GENERIC_TABLET -> {
            ExpandedPosLayout(
                viewModel = viewModel,
                deviceConfig = deviceConfig,
                onLogout = onLogout
            )
        }
        else -> {
            CompactPosLayout(
                viewModel = viewModel,
                deviceConfig = deviceConfig,
                onLogout = onLogout
            )
        }
    }
}
