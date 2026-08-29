package com.birdmachine.paidin

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val OceanScheme = darkColorScheme(
    primary = Color(0xFF67F4FF),
    onPrimary = Color(0xFF003A58),
    secondary = Color(0xFFB8FF45),
    tertiary = Color(0xFFFFF38A),
    background = Color(0xFF006DC2),
    surface = Color(0x550078C8),
    onSurface = Color.White,
    onBackground = Color.White,
)

@Composable
fun PaidInTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OceanScheme, typography = MaterialTheme.typography, content = content)
}
