package com.xelth.eckwms_movfast.utils

import android.content.Context
import android.os.Build
import android.annotation.SuppressLint
import android.util.Log

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val serial: String,
    val deviceType: DeviceType,
    val androidId: String = "unknown"
)

enum class DeviceType {
    MOVFAST_RANGER2,
    MOVFAST_RANGER2K,
    NEBULAR_GEMINI_D5,
    GENERIC_TABLET,
    GENERIC_PHONE,
    UNKNOWN
}

data class DeviceConfig(
    val deviceType: DeviceType,
    val isHandheld: Boolean,
    val hasPhysicalScanner: Boolean,
    val hasPhysicalKeyboard: Boolean,
    val screenSizeCategory: ScreenSizeCategory
)

enum class ScreenSizeCategory {
    SMALL, MEDIUM, LARGE
}

@SuppressLint("HardwareIds")
fun getDeviceInfo(context: Context): DeviceInfo {
    val manufacturer = Build.MANUFACTURER
    val model = Build.MODEL
    val androidId = getAndroidId(context)
    val deviceType = determineDeviceType(manufacturer, model)

    val serial = if (manufacturer.equals("Movfast", ignoreCase = true)) {
        androidId
    } else {
        "unknown"
    }

    Log.i("DeviceUtils", "Detected device - Manufacturer: $manufacturer, Model: $model, Type: $deviceType")

    return DeviceInfo(
        manufacturer = manufacturer,
        model = model,
        serial = serial,
        deviceType = deviceType,
        androidId = androidId
    )
}

fun getDeviceConfig(deviceInfo: DeviceInfo): DeviceConfig {
    return when (deviceInfo.deviceType) {
        DeviceType.MOVFAST_RANGER2 -> DeviceConfig(
            deviceType = DeviceType.MOVFAST_RANGER2,
            isHandheld = true,
            hasPhysicalScanner = true,
            hasPhysicalKeyboard = false,
            screenSizeCategory = ScreenSizeCategory.MEDIUM
        )
        DeviceType.MOVFAST_RANGER2K -> DeviceConfig(
            deviceType = DeviceType.MOVFAST_RANGER2K,
            isHandheld = true,
            hasPhysicalScanner = true,
            hasPhysicalKeyboard = true,
            screenSizeCategory = ScreenSizeCategory.SMALL
        )
        DeviceType.NEBULAR_GEMINI_D5 -> DeviceConfig(
            deviceType = DeviceType.NEBULAR_GEMINI_D5,
            isHandheld = false,
            hasPhysicalScanner = false,
            hasPhysicalKeyboard = false,
            screenSizeCategory = ScreenSizeCategory.LARGE
        )
        DeviceType.GENERIC_TABLET -> DeviceConfig(
            deviceType = DeviceType.GENERIC_TABLET,
            isHandheld = false,
            hasPhysicalScanner = false,
            hasPhysicalKeyboard = false,
            screenSizeCategory = ScreenSizeCategory.LARGE
        )
        DeviceType.GENERIC_PHONE -> DeviceConfig(
            deviceType = DeviceType.GENERIC_PHONE,
            isHandheld = true,
            hasPhysicalScanner = false,
            hasPhysicalKeyboard = false,
            screenSizeCategory = ScreenSizeCategory.SMALL
        )
        DeviceType.UNKNOWN -> DeviceConfig(
            deviceType = DeviceType.UNKNOWN,
            isHandheld = true,
            hasPhysicalScanner = false,
            hasPhysicalKeyboard = false,
            screenSizeCategory = ScreenSizeCategory.MEDIUM
        )
    }
}

private fun determineDeviceType(manufacturer: String, model: String): DeviceType {
    return when {
        manufacturer.equals("Movfast", ignoreCase = true) && model.equals("Ranger2", ignoreCase = true) -> {
            DeviceType.MOVFAST_RANGER2
        }
        manufacturer.equals("Movfast", ignoreCase = true) && model.equals("Ranger2k", ignoreCase = true) -> {
            DeviceType.MOVFAST_RANGER2K
        }
        model.equals("D5", ignoreCase = true) -> {
            DeviceType.NEBULAR_GEMINI_D5
        }
        isTabletDevice() -> DeviceType.GENERIC_TABLET
        else -> DeviceType.GENERIC_PHONE
    }
}

private fun isTabletDevice(): Boolean {
    return try {
        val screenLayout = android.content.res.Resources.getSystem().configuration.screenLayout
        (screenLayout and android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK) >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE
    } catch (e: Exception) {
        false
    }
}

fun Context.getAppDeviceInfo(): DeviceInfo {
    return getDeviceInfo(this)
}

fun Context.getAppDeviceConfig(): DeviceConfig {
    return getDeviceConfig(getDeviceInfo(this))
}

@SuppressLint("HardwareIds")
private fun getAndroidId(context: Context): String {
    return try {
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
        if (!androidId.isNullOrEmpty()) androidId else "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}
