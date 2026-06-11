package com.xelth.eckwms_movfast.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

// High-contrast scheme for sunlight/outdoor use — black/yellow for max readability
val HighContrastColorScheme = darkColorScheme(
    primary = Color(0xFFFFEB3B),         // Bright yellow
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF333300),
    onPrimaryContainer = Color(0xFFFFEB3B),
    secondary = Color.White,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF333333),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFFF9800),         // Orange for accents
    onTertiary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFFFEB3B),
    surface = Color(0xFF1A1A00),
    onSurface = Color(0xFFFFEB3B),
    surfaceVariant = Color(0xFF2A2A00),
    onSurfaceVariant = Color(0xFFFFEB3B),
    error = Color(0xFFFF1744),
    onError = Color.White,
    outline = Color(0xFFFFEB3B)
)

@Composable
fun EckwmsmovFastTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    highContrast: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        highContrast -> HighContrastColorScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
