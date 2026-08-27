package com.glazegram.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single async image-decoding boundary for the app.
 *
 * Decoding never runs during composition: [rememberDecodedImage] returns the
 * cached bitmap immediately when available and otherwise decodes off the main
 * thread inside a coroutine, publishing the result into Compose state.
 *
 * Decoding is target-sized: callers pass an approximate rendered size in physical
 * pixels and the loader computes a conservative power-of-two `inSampleSize` so a
 * multi-megapixel source is never decoded at full resolution to fill a small tile.
 *
 * Cache keys distinguish file-path sources from in-memory byte sources
 * (TDLib minithumbnails) *and* the target-size bucket, so identical-looking
 * payloads never collide, different tile sizes never share one decode, and
 * progressive TDLib file upgrades (thumbnail -> larger local file) naturally
 * produce a new lookup key. The cache is byte-bounded (see [ImageDecodePolicy]).
 */
private object ImageMemoryCache {
    private val limit = ImageDecodePolicy.cacheLimitBytes(Runtime.getRuntime().maxMemory())
    private val cache = object : LruCache<String, ImageBitmap>(limit) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            // ImageBitmap wraps an android Bitmap; approximate by pixel bytes.
            (value.width * value.height * 4).coerceAtLeast(1)
    }

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, bitmap: ImageBitmap) {
        cache.put(key, bitmap)
    }
}

private sealed interface ImageSource {
    data class File(val path: String) : ImageSource
    data class Bytes(val data: ByteArray) : ImageSource

    fun cacheKey(targetBucket: Int): String = when (this) {
        is File -> "file:$path@$targetBucket"
        is Bytes -> "bytes:${data.size}:${data.contentHashCode()}@$targetBucket"
    }
}

private fun resolveSources(path: String?, minithumbnail: ByteArray?): List<ImageSource> =
    buildList {
        if (!path.isNullOrBlank()) add(ImageSource.File(path))
        if (minithumbnail != null) add(ImageSource.Bytes(minithumbnail))
    }

/**
 * Decodes [source] sized to [targetPx] physical pixels. When [allowRgb565] is true and the
 * decoded content is opaque, uses RGB_565 to halve memory; otherwise keeps ARGB_8888 so
 * transparency and color precision are preserved. Runs on the caller's (IO) dispatcher.
 */
private fun decodeSized(source: ImageSource, targetPx: Int, allowRgb565: Boolean): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    when (source) {
        is ImageSource.File -> BitmapFactory.decodeFile(source.path, bounds)
        is ImageSource.Bytes -> BitmapFactory.decodeByteArray(source.data, 0, source.data.size, bounds)
    }
    val opts = BitmapFactory.Options().apply {
        inSampleSize = ImageDecodePolicy.sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
        // RGB_565 only for clearly-opaque content; a source that may carry alpha stays ARGB_8888.
        val opaque = allowRgb565 && bounds.outMimeType == "image/jpeg"
        inPreferredConfig = if (opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
    }
    val decoded = when (source) {
        is ImageSource.File -> BitmapFactory.decodeFile(source.path, opts)
        is ImageSource.Bytes -> BitmapFactory.decodeByteArray(source.data, 0, source.data.size, opts)
    }
    return decoded?.asImageBitmap()
}

@Composable
fun rememberDecodedImage(
    path: String?,
    minithumbnail: ByteArray? = null,
    targetDp: Int = 0,
    allowRgb565: Boolean = false,
): ImageBitmap? {
    val density = LocalDensity.current.density
    val targetPx = remember(targetDp, density) {
        if (targetDp <= 0) 0 else (targetDp * density).toInt()
    }
    val bucket = remember(targetPx) { ImageDecodePolicy.targetBucket(targetPx) }
    val sources = remember(path, minithumbnail) { resolveSources(path, minithumbnail) }
    if (sources.isEmpty()) return null

    val primaryKey = sources.first().cacheKey(bucket)
    var bitmap by remember(primaryKey) { mutableStateOf(ImageMemoryCache.get(primaryKey)) }

    LaunchedEffect(sources, bucket) {
        if (bitmap != null) return@LaunchedEffect
        for (source in sources) {
            val key = source.cacheKey(bucket)
            ImageMemoryCache.get(key)?.let { cached ->
                bitmap = cached
                return@LaunchedEffect
            }
            val decoded = withContext(Dispatchers.IO) {
                runCatching { decodeSized(source, targetPx, allowRgb565) }.getOrNull()
            }
            if (decoded != null) {
                ImageMemoryCache.put(key, decoded)
                bitmap = decoded
                return@LaunchedEffect
            }
        }
    }
    return bitmap
}
