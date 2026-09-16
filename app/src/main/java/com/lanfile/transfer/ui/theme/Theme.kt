package com.lanfile.transfer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1D4ED8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF00174B),
    secondary = Color(0xFF0F766E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF00201C),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE8ECF4),
    onSurfaceVariant = Color(0xFF4B5563),
    outline = Color(0xFFC7D0DE),
    outlineVariant = Color(0xFFDFE5EE),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF001B4D),
    primaryContainer = Color(0xFF1B3A80),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF00201C),
    secondaryContainer = Color(0xFF115E59),
    onSecondaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF131C2E),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF9AA8BC),
    outline = Color(0xFF3A4A63),
    outlineVariant = Color(0xFF25334A),
    error = Color(0xFFF87171),
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF4A1414),
    onErrorContainer = Color(0xFFFECACA)
)

/** 服务器状态专用色，UI 与主题解耦。 */
object StatusColors {
    val Running = Color(0xFF16A34A)
    val Stopped = Color(0xFF94A3B8)
    val Starting = Color(0xFFF59E0B)
    val Error = Color(0xFFDC2626)
}

@Composable
fun LanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
