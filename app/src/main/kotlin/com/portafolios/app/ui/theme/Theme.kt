package com.portafolios.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Green = Color(0xFF14876A)
val GainColor = Color(0xFF13804F)
val LossColor = Color(0xFFC62828)

private val Light = lightColorScheme(primary = Green, secondary = Color(0xFF2F6F8F), tertiary = Color(0xFF8A6D1D))
private val Dark = darkColorScheme(primary = Color(0xFF5FD3AE), secondary = Color(0xFF8CC8E6), tertiary = Color(0xFFE2C26A))

@Composable
fun PortafoliosTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** Colors for allocation charts. */
val ChartColors = listOf(
    Color(0xFF14876A), Color(0xFF2F6F8F), Color(0xFFE0A526), Color(0xFFC25A3A), Color(0xFF7B5EA7),
    Color(0xFF4FA3A5), Color(0xFF9C6644), Color(0xFF5B8C3A), Color(0xFFB0476E), Color(0xFF6C7A89),
)
