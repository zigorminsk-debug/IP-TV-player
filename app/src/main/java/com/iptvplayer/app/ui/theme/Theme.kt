package com.iptvplayer.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF22D3EE),
    onPrimary = Color(0xFF00323B),
    primaryContainer = Color(0xFF0E7490),
    onPrimaryContainer = Color(0xFFCFF8FF),
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF00344A),
    tertiary = Color(0xFFF0ABFC),
    onTertiary = Color(0xFF4A1B52),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF111A2C),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1C2942),
    onSurfaceVariant = Color(0xFFA9B7CC),
    outline = Color(0xFF3D4E68),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0891B2),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFF4FE),
    onPrimaryContainer = Color(0xFF001E27),
    secondary = Color(0xFF0284C7),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFEEF3F8),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFDCE5EE),
    onSurfaceVariant = Color(0xFF43536B),
    outline = Color(0xFF7286A0),
    error = Color(0xFFB3261E),
)

/** themeSetting: SYSTEM | DARK | LIGHT (from settings). */
@Composable
fun IPTVTheme(themeSetting: String, content: @Composable () -> Unit) {
    val dark = when (themeSetting) {
        "DARK" -> true
        "LIGHT" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
