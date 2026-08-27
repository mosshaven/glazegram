package com.glazegram.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Reusable avatar: async-decoded image when available, initials fallback
 * otherwise. All avatars in the app should use this component.
 */
@Composable
fun GlazegramAvatar(
    name: String,
    imagePath: String?,
    size: Dp = 52.dp,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberDecodedImage(path = imagePath, targetDp = size.value.toInt())
    if (bitmap != null) {
        AvatarImage(bitmap, name, size, modifier)
    } else {
        AvatarFallback(name, size, modifier)
    }
}

@Composable
private fun AvatarImage(
    bitmap: ImageBitmap,
    contentDescription: String?,
    size: Dp,
    modifier: Modifier,
) {
    androidx.compose.foundation.Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(CircleShape),
    )
}

@Composable
private fun AvatarFallback(
    name: String,
    size: Dp,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull()?.uppercase() ?: "?",
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
