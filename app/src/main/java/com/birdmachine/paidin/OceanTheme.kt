package com.birdmachine.paidin

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Aqua Vista: modern glass with the optimistic, aquatic colour language of Frutiger Aero.
private val AquaVistaScheme = darkColorScheme(
    primary = Color(0xFF72F6FF),
    onPrimary = Color(0xFF00384F),
    primaryContainer = Color(0x6638D8F2),
    secondary = Color(0xFFB9FF52),
    onSecondary = Color(0xFF173900),
    tertiary = Color(0xFFFFF29A),
    background = Color(0xFF047DD0),
    surface = Color(0x66317FC0),
    surfaceVariant = Color(0x665DE6EF),
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFE5FBFF),
    onBackground = Color.White,
    outline = Color(0xCCEAFFFF),
)

@Composable
fun PaidInTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AquaVistaScheme, typography = MaterialTheme.typography, content = content)
}
