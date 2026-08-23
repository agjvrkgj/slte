package com.slte.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SlteBackground = Color(0xFF0C1624)
val SlteSurface = Color(0xFF152637)
val SlteSurfaceLight = Color(0xFF1D3550)
val SlteBlue = Color(0xFF6DB8F7)
val SltePink = Color(0xFFF083A1)
val SlteGreen = Color(0xFF5AC98A)
val SlteMuted = Color(0xFF8297AE)

private val SlteColors = darkColorScheme(
    primary = SlteBlue,
    onPrimary = Color(0xFF08223A),
    secondary = SltePink,
    onSecondary = Color(0xFF34101A),
    background = SlteBackground,
    onBackground = Color(0xFFF4F7FB),
    surface = SlteSurface,
    onSurface = Color(0xFFF4F7FB),
    surfaceVariant = SlteSurfaceLight,
    onSurfaceVariant = Color(0xFFB8C8D9),
    error = Color(0xFFFF8A91),
)

@Composable
fun SlteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SlteColors, content = content)
}
