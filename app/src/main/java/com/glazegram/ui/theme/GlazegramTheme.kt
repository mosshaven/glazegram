package com.glazegram.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.luminance
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat

@Composable
fun GlazegramTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    SideEffect {
        val window = (context as ComponentActivity).window
        val useDarkIcons = colors.background.luminance() > 0.5f
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = useDarkIcons
            isAppearanceLightNavigationBars = useDarkIcons
        }
    }

    val defaults = Typography()
    val typography = Typography(
        displayLarge = defaults.displayLarge.copy(fontFamily = FontFamily.SansSerif),
        displayMedium = defaults.displayMedium.copy(fontFamily = FontFamily.SansSerif),
        displaySmall = defaults.displaySmall.copy(fontFamily = FontFamily.SansSerif),
        headlineLarge = defaults.headlineLarge.copy(fontFamily = FontFamily.SansSerif),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = FontFamily.SansSerif),
        headlineSmall = defaults.headlineSmall.copy(fontFamily = FontFamily.SansSerif),
        titleLarge = defaults.titleLarge.copy(fontFamily = FontFamily.SansSerif),
        titleMedium = defaults.titleMedium.copy(fontFamily = FontFamily.SansSerif),
        titleSmall = defaults.titleSmall.copy(fontFamily = FontFamily.SansSerif),
        bodyLarge = defaults.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
        bodyMedium = defaults.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
        bodySmall = defaults.bodySmall.copy(fontFamily = FontFamily.SansSerif),
        labelLarge = defaults.labelLarge.copy(fontFamily = FontFamily.SansSerif),
        labelMedium = defaults.labelMedium.copy(fontFamily = FontFamily.SansSerif),
        labelSmall = defaults.labelSmall.copy(fontFamily = FontFamily.SansSerif),
    )

    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        content = content,
    )
}
