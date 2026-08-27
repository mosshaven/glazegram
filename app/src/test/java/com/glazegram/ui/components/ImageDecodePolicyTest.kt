package com.glazegram.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDecodePolicyTest {

    // ---- inSampleSize -------------------------------------------------------

    @Test
    fun sampleSizeIsOneWhenSourceAlreadyNearTarget() {
        assertEquals(1, ImageDecodePolicy.sampleSize(300, 300, 300))
    }

    @Test
    fun sampleSizeNeverUpscalesForTinySources() {
        // Source smaller than the target must not sample below 1 (no upscaling at decode).
        assertEquals(1, ImageDecodePolicy.sampleSize(80, 80, 300))
    }

    @Test
    fun sampleSizeIsAConservativePowerOfTwoThatStaysAboveTarget() {
        // 4000x3000 down to a ~300px tile: 8 => 500x375 (still >= target); 16 => 250 (< target).
        assertEquals(8, ImageDecodePolicy.sampleSize(4000, 3000, 300))
    }

    @Test
    fun sampleSizeFallsBackToOneForInvalidInput() {
        assertEquals(1, ImageDecodePolicy.sampleSize(0, 0, 300))
        assertEquals(1, ImageDecodePolicy.sampleSize(4000, 3000, 0))
    }

    // ---- target bucketing ---------------------------------------------------

    @Test
    fun nearbyTargetsSnapToTheSameBucket() {
        // Small differences must not create distinct cache variants.
        assertEquals(ImageDecodePolicy.targetBucket(170), ImageDecodePolicy.targetBucket(190))
    }

    @Test
    fun bucketGrowsForClearlyLargerTargets() {
        assertTrue(ImageDecodePolicy.targetBucket(700) > ImageDecodePolicy.targetBucket(150))
    }

    @Test
    fun bucketIsZeroForUnknownTarget() {
        assertEquals(0, ImageDecodePolicy.targetBucket(0))
    }

    @Test
    fun bucketIsCappedForHugeTargets() {
        assertEquals(1536, ImageDecodePolicy.targetBucket(10_000))
    }

    // ---- cache limit policy -------------------------------------------------

    @Test
    fun cacheLimitClampsToAbsoluteCapOnLargeHeap() {
        val huge = 2L * 1024 * 1024 * 1024 // 2 GB heap
        assertEquals(ImageDecodePolicy.CACHE_ABSOLUTE_CAP_BYTES, ImageDecodePolicy.cacheLimitBytes(huge))
    }

    @Test
    fun cacheLimitRespectsFloorOnTinyHeap() {
        assertEquals(ImageDecodePolicy.CACHE_MIN_BYTES, ImageDecodePolicy.cacheLimitBytes(8L * 1024 * 1024))
    }

    @Test
    fun cacheLimitStaysBetweenFloorAndCapForMidHeap() {
        val limit = ImageDecodePolicy.cacheLimitBytes(256L * 1024 * 1024)
        assertTrue(limit in ImageDecodePolicy.CACHE_MIN_BYTES..ImageDecodePolicy.CACHE_ABSOLUTE_CAP_BYTES)
    }
}
