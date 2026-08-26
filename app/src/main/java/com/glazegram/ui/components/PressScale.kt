package com.glazegram.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import com.glazegram.ui.theme.Motion

/**
 * Tactile press feedback: scales the element down while pressed and restores
 * it on release using shared motion tokens.
 *
 * Pass the same [interactionSource] to the element's clickable so press state
 * is observed.
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = Motion.PRESSED_SCALE,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(durationMillis = Motion.DURATION_SHORT, easing = Motion.EasingStandard),
        label = "press-scale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun rememberPressInteraction(): MutableInteractionSource = remember { MutableInteractionSource() }
