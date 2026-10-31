package com.tukonnichiwa.adskit.nativeads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativePlacementTest {
    @Test
    fun acceptsValidPlacementDefaults() {
        val placement = NativePlacement(key = "feed", adUnitId = "unit-id")

        assertEquals(1, placement.preloadCount)
        assertNull(placement.fallbackAdUnitId)
        assertEquals(true, placement.startVideoMuted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankPlacementKey() {
        NativePlacement(key = " ", adUnitId = "unit-id")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangePreloadCount() {
        NativePlacement(key = "feed", adUnitId = "unit-id", preloadCount = 11)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankFallbackId() {
        NativePlacement(key = "feed", adUnitId = "unit-id", fallbackAdUnitId = " ")
    }
}
