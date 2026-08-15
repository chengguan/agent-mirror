package dev.chengguan.mirror.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// GrokNight — same slots as the default Grok Build TUI.
val Ink = Color(0xFFE1E1E1)
val AccentUser = Color(0xFFBB9AF7)
val AccentAssistant = Color(0xFF7AA2F7)
val AccentCyan = Color(0xFF1ABC9C)
val AccentError = Color(0xFFF7768E)
val AccentSuccess = Color(0xFF9ECE6A)
val AccentWarn = Color(0xFFE0AF68)
val BgBase = Color(0xFF0A0A0A)
val BgRaised = Color(0xFF111111)
val BgHighlight = Color(0xFF242424)
val UserBubble = Color(0xFF16121F)
val AssistantBubble = Color(0xFF111111)

private val LightColors = lightColorScheme(
    primary = AccentUser,
    onPrimary = BgBase,
    secondary = AccentAssistant,
    onSecondary = BgBase,
    background = BgBase,
    onBackground = Ink,
    surface = BgRaised,
    onSurface = Ink,
    surfaceVariant = BgHighlight,
    onSurfaceVariant = Color(0xFFC8C8C8),
    error = AccentError,
)

private val DarkColors = darkColorScheme(
    primary = AccentUser,
    onPrimary = BgBase,
    secondary = AccentAssistant,
    onSecondary = BgBase,
    background = BgBase,
    onBackground = Ink,
    surface = BgRaised,
    onSurface = Ink,
    surfaceVariant = BgHighlight,
    onSurfaceVariant = Color(0xFFC8C8C8),
    error = AccentError,
)

@Composable
fun MirrorTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
