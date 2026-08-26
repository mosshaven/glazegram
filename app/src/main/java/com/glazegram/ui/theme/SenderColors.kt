package com.glazegram.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Small deterministic sender palette.
 * Colors are chosen for contrast against surfaceContainerHigh / primaryContainer
 * and differ for light/dark themes. Mapping is via stable senderKey, not display name.
 * Two different users named "Андрей" get different colors.
 */
private val SenderLightPalette = listOf(
    Color(0xFFC62828), // red 800
    Color(0xFFEF6C00), // orange 800
    Color(0xFF2E7D32), // green 800
    Color(0xFF00796B), // teal 700
    Color(0xFF1565C0), // blue 800
    Color(0xFF6A1B9A), // purple 800
    Color(0xFFAD1457), // pink 700
    Color(0xFF5D4037), // brown 700
)

private val SenderDarkPalette = listOf(
    Color(0xFFFF8A80), // light red
    Color(0xFFFFB74D), // light orange
    Color(0xFF81C784), // light green
    Color(0xFF4DB6AC), // light teal
    Color(0xFF90CAF9), // light blue
    Color(0xFFCE93D8), // light purple
    Color(0xFFF48FB1), // light pink
    Color(0xFFBCAAA4), // light brown
)

@Composable
fun senderColor(senderKey: String): Color {
    if (senderKey.isBlank()) return MaterialTheme.colorScheme.primary
    val isDark = isSystemInDarkTheme()
    val palette = if (isDark) SenderDarkPalette else SenderLightPalette
    val idx = (senderKey.hashCode() and Int.MAX_VALUE) % palette.size
    return palette[idx]
}
