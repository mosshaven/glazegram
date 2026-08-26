package com.glazegram.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * Shared motion timings and easing so transitions feel consistent app-wide.
 */
object Motion {
    const val DURATION_SHORT = 90
    const val DURATION_MEDIUM = 220

    val EasingStandard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EasingExit: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /** Scale applied to a pressed element for tactile press feedback. */
    const val PRESSED_SCALE = 0.97f
}
