package com.tukonnichiwa.adskit.internal

import com.tukonnichiwa.adskit.AdLoadFailure
import com.tukonnichiwa.adskit.AdLoadState
import com.tukonnichiwa.adskit.AdsRequestGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FallbackAdSlotTest {
    @Test
    fun triesNormalUnitAfterHighFloorFailureAndOnlyThenPublishesReady() = runBlocking {
        val requested = mutableListOf<String>()
        val slot = FallbackAdSlot(
            "high", "normal", AdsRequestGate.allowAll,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            loader = { id ->
                requested += id
                if (id == "high") AdUnitLoadResult.Failed(AdLoadFailure(3, "sdk", "no fill"))
                else AdUnitLoadResult.Loaded("ad")
            },
            nowMillis = { 0L }
        )

        slot.preload().join()

        assertEquals(listOf("high", "normal"), requested)
        assertEquals(AdLoadState.Ready, slot.state.value)
        assertEquals("ad", slot.take())
        slot.close()
    }

    @Test
    fun doesNotRequestSameUnitTwice() = runBlocking {
        val requested = mutableListOf<String>()
        val slot = FallbackAdSlot(
            "same", "same", AdsRequestGate.allowAll,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            loader = { id -> requested += id; AdUnitLoadResult.Failed(AdLoadFailure(1, "sdk", "failed")) },
            nowMillis = { 0L }
        )

        slot.preload().join()

        assertEquals(listOf("same"), requested)
        assertEquals(AdLoadState.Failed(AdLoadFailure(1, "sdk", "failed")), slot.state.value)
        slot.close()
    }

    @Test
    fun expiresCachedAdAtLifetimeBoundaryAndDestroysIt() = runBlocking {
        var now = 10L
        val destroyed = mutableListOf<String>()
        val slot = FallbackAdSlot(
            "normal", null, AdsRequestGate.allowAll,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            loader = { AdUnitLoadResult.Loaded("cached") },
            destroy = destroyed::add,
            cacheLifetimeMillis = 50L,
            nowMillis = { now }
        )
        slot.preload().join()
        assertEquals(AdLoadState.Ready, slot.state.value)

        now += 50L

        assertNull(slot.take())
        assertEquals(listOf("cached"), destroyed)
        assertEquals(AdLoadState.Idle, slot.state.value)
        slot.close()
    }

    @Test
    fun clearCancelsPendingWorkAndDestroysCachedResource() = runBlocking {
        val destroyed = mutableListOf<String>()
        val slot = FallbackAdSlot(
            "normal", null, AdsRequestGate.allowAll,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            loader = { AdUnitLoadResult.Loaded("cached") },
            destroy = destroyed::add,
            nowMillis = { 0L }
        )
        slot.preload().join()

        slot.clear()

        assertEquals(listOf("cached"), destroyed)
        assertEquals(AdLoadState.Idle, slot.state.value)
        assertNull(slot.take())
        slot.close()
    }

    @Test
    fun timedOutHighFloorRequestFallsBackToNormalUnit() = runBlocking {
        val requested = mutableListOf<String>()
        val slot = FallbackAdSlot(
            "high", "normal", AdsRequestGate.allowAll,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            loader = { id ->
                requested += id
                if (id == "high") {
                    suspendCancellableCoroutine<AdUnitLoadResult<String>> { }
                } else AdUnitLoadResult.Loaded("normal-ad")
            },
            loadTimeoutMillis = 25L,
            nowMillis = { 0L }
        )

        slot.preload().join()

        assertEquals(listOf("high", "normal"), requested)
        assertEquals("normal-ad", slot.take())
        slot.close()
    }
}
