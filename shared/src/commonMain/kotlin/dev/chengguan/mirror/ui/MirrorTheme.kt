package dev.chengguan.mirror.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF1A1714)
val Amber = Color(0xFFB7791F)
val Sand = Color(0xFFF4EFE6)
val UserBubble = Color(0xFF2C3A4A)
val AssistantBubble = Color(0xFFE8DFD0)

private val LightColors = lightColorScheme(
    primary = Amber,
    onPrimary = Color.White,
    secondary = Ink,
    onSecondary = Color.White,
    background = Sand,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE6DCCB),
    onSurfaceVariant = Ink.copy(alpha = 0.72f),
    error = Color(0xFFB42318),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE0B15A),
    onPrimary = Ink,
    secondary = Color(0xFFE6DCCB),
    background = Color(0xFF12110F),
    onBackground = Color(0xFFF2EBE0),
    surface = Color(0xFF1C1A17),
    onSurface = Color(0xFFF2EBE0),
    error = Color(0xFFE08A5C),
)

@Composable
fun MirrorTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
