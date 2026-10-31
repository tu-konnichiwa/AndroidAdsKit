package com.tukonnichiwa.adskit.internal

import com.tukonnichiwa.adskit.AdFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullScreenAdCoordinatorTest {
    @Test
    fun allowsOnlyOneActivePresentationAcrossFormats() {
        val coordinator = FullScreenAdCoordinator { 0L }
        val interstitial = coordinator.acquire(AdFormat.INTERSTITIAL)!!

        assertNull(coordinator.acquire(AdFormat.REWARDED))
        interstitial.close()
        assertTrue(coordinator.acquire(AdFormat.REWARDED) != null)
    }

    @Test
    fun cooldownIsScopedToFormatAndStartsWhenLeaseCloses() {
        var now = 100L
        val coordinator = FullScreenAdCoordinator { now }
        val lease = coordinator.acquire(AdFormat.INTERSTITIAL)!!
        lease.markVisible()
        lease.close()

        assertTrue(coordinator.isWithinInterval(AdFormat.INTERSTITIAL, 1_000L))
        assertFalse(coordinator.isWithinInterval(AdFormat.REWARDED, 1_000L))
        now += 1_000L
        assertFalse(coordinator.isWithinInterval(AdFormat.INTERSTITIAL, 1_000L))
    }

    @Test
    fun backgroundAgeUsesMonotonicClockAndCanBeCleared() {
        var now = 500L
        val coordinator = FullScreenAdCoordinator { now }
        coordinator.recordBackground()
        now += 750L

        assertEquals(750L, coordinator.backgroundAgeMillis())
        coordinator.clearBackground()
        assertNull(coordinator.backgroundAgeMillis())
    }
}
