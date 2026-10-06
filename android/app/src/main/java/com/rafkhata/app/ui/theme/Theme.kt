package com.rafkhata.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// "Rough notebook" palette: ink blue on paper, with a terracotta accent for exam alerts.
private val Ink = Color(0xFF2F4A8A)
private val Paper = Color(0xFFFBF8F1)
private val Terracotta = Color(0xFFB4532A)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE3F7),
    onPrimaryContainer = Color(0xFF14254D),
    secondary = Terracotta,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBDCCD),
    onSecondaryContainer = Color(0xFF4A1A06),
    tertiary = Color(0xFF3E6B48),
    tertiaryContainer = Color(0xFFD3EBD6),
    onTertiaryContainer = Color(0xFF10301A),
    background = Paper,
    onBackground = Color(0xFF1B1C20),
    surface = Paper,
    onSurface = Color(0xFF1B1C20),
    surfaceVariant = Color(0xFFEDE8DC),
    onSurfaceVariant = Color(0xFF4A4740),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F3EA),
    surfaceContainer = Color(0xFFF2EEE4),
    surfaceContainerHigh = Color(0xFFECE8DE),
    surfaceContainerHighest = Color(0xFFE6E2D8),
    outline = Color(0xFF7B776E),
    outlineVariant = Color(0xFFCFC9BC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB3C5F5),
    onPrimary = Color(0xFF14254D),
    primaryContainer = Color(0xFF2B3F70),
    onPrimaryContainer = Color(0xFFDCE3F7),
    secondary = Color(0xFFF2B597),
    onSecondary = Color(0xFF4A1A06),
    secondaryContainer = Color(0xFF6E3317),
    onSecondaryContainer = Color(0xFFFBDCCD),
    tertiary = Color(0xFFA8D2AF),
    tertiaryContainer = Color(0xFF26502F),
    onTertiaryContainer = Color(0xFFD3EBD6),
    background = Color(0xFF15181F),
    onBackground = Color(0xFFE4E2DB),
    surface = Color(0xFF15181F),
    onSurface = Color(0xFFE4E2DB),
    surfaceVariant = Color(0xFF3A3D45),
    onSurfaceVariant = Color(0xFFC6C4BC),
    surfaceContainerLowest = Color(0xFF101318),
    surfaceContainerLow = Color(0xFF1C1F26),
    surfaceContainer = Color(0xFF20232A),
    surfaceContainerHigh = Color(0xFF2A2D35),
    surfaceContainerHighest = Color(0xFF353840),
    outline = Color(0xFF8F8D86),
    outlineVariant = Color(0xFF45474E),
)

@Composable
fun RafKhataTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
