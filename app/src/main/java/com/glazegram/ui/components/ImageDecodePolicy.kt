package com.glazegram.ui.components

/**
 * Pure, side-effect-free decode/cache policy for the image loader. Kept separate from the
 * Compose/Android code so it can be unit-tested without a device.
 */
object ImageDecodePolicy {
    /** Fraction of the heap the bitmap cache may use. */
    private const val HEAP_FRACTION = 8 // 1/8 of maxMemory
    /** Absolute ceiling so a large-heap device does not grow an enormous bitmap cache. */
    const val CACHE_ABSOLUTE_CAP_BYTES = 48 * 1024 * 1024
    /** Floor so a tiny-heap device still caches something useful. */
    const val CACHE_MIN_BYTES = 4 * 1024 * 1024

    /**
     * Conservative power-of-two sample size: the largest power of two such that both source
     * dimensions stay at or above the target. Never upscales (min 1); safe fallback of 1 when
     * the target or source is unknown/invalid.
     */
    fun sampleSize(srcWidth: Int, srcHeight: Int, targetPx: Int): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || targetPx <= 0) return 1
        var sample = 1
        var halfW = srcWidth / 2
        var halfH = srcHeight / 2
        while (halfW >= targetPx && halfH >= targetPx) {
            sample *= 2
            halfW /= 2
            halfH /= 2
        }
        return sample
    }

    /**
     * Snaps a physical-pixel target to a coarse power-of-two bucket so tiny size differences do
     * not spawn unlimited cache variants. Buckets: 96, 192, 384, 768, 1536 (px). Invalid/unknown
     * targets map to bucket 0 = "full/native decode".
     */
    fun targetBucket(targetPx: Int): Int {
        if (targetPx <= 0) return 0
        var bucket = 96
        while (bucket < targetPx && bucket < 1536) bucket *= 2
        return bucket
    }

    /**
     * The single decode target for a bucket, so every target that snaps to the same bucket
     * decodes at one resolution (and thus one cache entry stays consistent). Bucket 0 (unknown)
     * decodes natively — [sampleSize] returns 1 for a target of 0.
     */
    fun decodeTargetForBucket(bucket: Int): Int = bucket

    /** Byte-bounded cache limit: 1/8 of heap, clamped to [CACHE_MIN_BYTES, CACHE_ABSOLUTE_CAP_BYTES]. */
    fun cacheLimitBytes(maxMemoryBytes: Long): Int {
        val fraction = maxMemoryBytes / HEAP_FRACTION
        val clamped = fraction.coerceIn(CACHE_MIN_BYTES.toLong(), CACHE_ABSOLUTE_CAP_BYTES.toLong())
        return clamped.toInt()
    }
}
