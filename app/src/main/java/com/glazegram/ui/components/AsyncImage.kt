package com.glazegram.ui.components

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single async image-decoding boundary for the app.
 *
 * Decoding never runs during composition: [rememberDecodedImage] returns the
 * cached bitmap immediately when available and otherwise decodes off the main
 * thread inside a coroutine, publishing the result into Compose state.
 *
 * Cache keys distinguish file-path sources from in-memory byte sources
 * (TDLib minithumbnails), so identical-looking payloads never collide and
 * progressive TDLib file upgrades (thumbnail -> larger local file) naturally
 * produce a new lookup key.
 */
private object ImageMemoryCache {
    private const val MAX_ENTRIES = 64
    private val cache = LruCache<String, ImageBitmap>(MAX_ENTRIES)

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, bitmap: ImageBitmap) {
        cache.put(key, bitmap)
    }
}

private sealed interface ImageSource {
    data class File(val path: String) : ImageSource
    data class Bytes(val data: ByteArray) : ImageSource

    val cacheKey: String
        get() = when (this) {
            is File -> "file:$path"
            is Bytes -> "bytes:${data.size}:${data.contentHashCode()}"
        }
}

private fun resolveSources(path: String?, minithumbnail: ByteArray?): List<ImageSource> =
    buildList {
        if (!path.isNullOrBlank()) add(ImageSource.File(path))
        if (minithumbnail != null) add(ImageSource.Bytes(minithumbnail))
    }

@Composable
fun rememberDecodedImage(
    path: String?,
    minithumbnail: ByteArray? = null,
): ImageBitmap? {
    val sources = remember(path, minithumbnail) { resolveSources(path, minithumbnail) }
    if (sources.isEmpty()) return null

    val primaryKey = sources.first().cacheKey
    var bitmap by remember(primaryKey) { mutableStateOf(ImageMemoryCache.get(primaryKey)) }

    LaunchedEffect(sources) {
        if (bitmap != null) return@LaunchedEffect
        for (source in sources) {
            ImageMemoryCache.get(source.cacheKey)?.let { cached ->
                bitmap = cached
                return@LaunchedEffect
            }
            val decoded = withContext(Dispatchers.IO) {
                runCatching {
                    when (source) {
                        is ImageSource.File -> BitmapFactory.decodeFile(source.path)
                        is ImageSource.Bytes ->
                            BitmapFactory.decodeByteArray(source.data, 0, source.data.size)
                    }?.asImageBitmap()
                }.getOrNull()
            }
            if (decoded != null) {
                ImageMemoryCache.put(source.cacheKey, decoded)
                bitmap = decoded
                return@LaunchedEffect
            }
        }
    }
    return bitmap
}
