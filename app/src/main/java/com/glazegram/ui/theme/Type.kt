package com.glazegram.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

/**
 * Single swap point for the application font family.
 *
 * No font binaries are bundled yet; the architecture allows choosing a
 * custom open-source or system font later by changing only this value.
 */
val GlazegramFontFamily: FontFamily = FontFamily.SansSerif

fun glazegramTypography(): Typography {
    val defaults = Typography()
    return Typography(
        displayLarge = defaults.displayLarge.copy(fontFamily = GlazegramFontFamily),
        displayMedium = defaults.displayMedium.copy(fontFamily = GlazegramFontFamily),
        displaySmall = defaults.displaySmall.copy(fontFamily = GlazegramFontFamily),
        headlineLarge = defaults.headlineLarge.copy(fontFamily = GlazegramFontFamily),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = GlazegramFontFamily),
        headlineSmall = defaults.headlineSmall.copy(fontFamily = GlazegramFontFamily),
        titleLarge = defaults.titleLarge.copy(fontFamily = GlazegramFontFamily),
        titleMedium = defaults.titleMedium.copy(fontFamily = GlazegramFontFamily),
        titleSmall = defaults.titleSmall.copy(fontFamily = GlazegramFontFamily),
        bodyLarge = defaults.bodyLarge.copy(fontFamily = GlazegramFontFamily),
        bodyMedium = defaults.bodyMedium.copy(fontFamily = GlazegramFontFamily),
        bodySmall = defaults.bodySmall.copy(fontFamily = GlazegramFontFamily),
        labelLarge = defaults.labelLarge.copy(fontFamily = GlazegramFontFamily),
        labelMedium = defaults.labelMedium.copy(fontFamily = GlazegramFontFamily),
        labelSmall = defaults.labelSmall.copy(fontFamily = GlazegramFontFamily),
    )
}
