package com.wifishare.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Purple/violet brand palette. Matches the modern mockup style — soft
// gradients, lavender backgrounds, single saturated primary. We do
// NOT use dynamic Material You colors here because they'd repaint the
// whole app to match the user's wallpaper and lose the brand feel.
object WiFiShareColors {
    val Primary = Color(0xFF7C5CFC)
    val PrimaryDeep = Color(0xFF6244F0)
    val PrimaryFaint = Color(0xFFEFEBFE)
    val SoftLavender = Color(0xFFEEEBFF)
    val SoftPeach = Color(0xFFFFEFEA)
    val LiveGreen = Color(0xFF1AB071)
    val LiveGreenBg = Color(0xFFE5F8EF)
    val Surface = Color(0xFFFAFAFC)
    val SurfaceVariant = Color(0xFFF2F2F7)
    val OutlineSoft = Color(0xFFE6E5EE)
    val OnSurface = Color(0xFF161623)
    val OnSurfaceMuted = Color(0xFF6B6A78)
}

private val LightColors = lightColorScheme(
    primary = WiFiShareColors.Primary,
    onPrimary = Color.White,
    primaryContainer = WiFiShareColors.PrimaryFaint,
    onPrimaryContainer = WiFiShareColors.PrimaryDeep,
    secondary = WiFiShareColors.LiveGreen,
    background = Color(0xFFF6F5FA),
    onBackground = WiFiShareColors.OnSurface,
    surface = Color.White,
    onSurface = WiFiShareColors.OnSurface,
    surfaceVariant = WiFiShareColors.SurfaceVariant,
    onSurfaceVariant = WiFiShareColors.OnSurfaceMuted,
    outline = WiFiShareColors.OutlineSoft,
    outlineVariant = WiFiShareColors.OutlineSoft,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAE96FF),
    onPrimary = Color(0xFF1D0F4F),
    primaryContainer = Color(0xFF3B2A8C),
    onPrimaryContainer = Color(0xFFE7DFFF),
    secondary = Color(0xFF4FD0A2),
    background = Color(0xFF101019),
    onBackground = Color(0xFFE9E8F0),
    surface = Color(0xFF181826),
    onSurface = Color(0xFFE9E8F0),
    surfaceVariant = Color(0xFF22222F),
    onSurfaceVariant = Color(0xFFAEADBC),
    outline = Color(0xFF2D2D3A),
    outlineVariant = Color(0xFF2D2D3A),
)

@Composable
fun WiFiShareTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
