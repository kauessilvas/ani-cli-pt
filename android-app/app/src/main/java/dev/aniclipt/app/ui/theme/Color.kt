package dev.aniclipt.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkBackground = Color(0xFF111113)
private val DarkSurface = Color(0xFF202024)
private val DarkInk = Color(0xFFF5F5F7)
private val DarkMuted = Color(0xFF9A9AA2)
private val DarkAccent = Color(0xFF0A84FF)

private val LightBackground = Color(0xFFF5F5F8)
private val LightSurface = Color.White
private val LightInk = Color(0xFF18181D)
private val LightMuted = Color(0xFF575962)
private val LightAccent = Color(0xFF0064C8)

internal val DarkAniCliColors = darkColorScheme(
    primary = DarkAccent,
    onPrimary = Color.White,
    secondaryContainer = Color(0xFF24384F),
    onSecondaryContainer = Color(0xFFD6E9FF),
    background = DarkBackground,
    surface = DarkSurface,
    onBackground = DarkInk,
    onSurface = DarkInk,
    onSurfaceVariant = DarkMuted,
    error = Color(0xFFFF8A80),
)

internal val LightAniCliColors = lightColorScheme(
    primary = LightAccent,
    onPrimary = Color.White,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = Color(0xFFE9E9EE),
    onBackground = LightInk,
    onSurface = LightInk,
    onSurfaceVariant = LightMuted,
    error = Color(0xFFB3261E),
)

// Semantic aliases keep feature UI independent of the concrete palette.
val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Canvas: Color @Composable get() = MaterialTheme.colorScheme.background
val Panel: Color @Composable get() = MaterialTheme.colorScheme.surface
val Accent: Color @Composable get() = MaterialTheme.colorScheme.primary
