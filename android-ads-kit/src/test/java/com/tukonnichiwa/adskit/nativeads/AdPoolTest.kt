package com.tukonnichiwa.adskit.nativeads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AdPoolTest {
    @Test
    fun pollReturnsItemsInInsertionOrder() {
        val pool = AdPool<Int>(2, {}, nowMillis = { 0L })
        pool.offer(1)
        pool.offer(2)

        assertEquals(1, pool.poll())
        assertEquals(2, pool.poll())
        assertNull(pool.poll())
    }

    @Test
    fun offerDestroysItemWhenPoolIsFull() {
        val destroyed = mutableListOf<Int>()
        val pool = AdPool(1, destroyed::add, nowMillis = { 0L })
        pool.offer(1)

        assertFalse(pool.offer(2))
        assertEquals(listOf(2), destroyed)
        assertEquals(1, pool.size())
    }

    @Test
    fun clearDestroysCachedItems() {
        val destroyed = mutableListOf<Int>()
        val first = 1
        val second = 2
        val pool = AdPool(2, destroyed::add, nowMillis = { 0L })
        pool.offer(first)
        pool.offer(second)

        pool.clear()

        assertEquals(listOf(first, second), destroyed)
        assertEquals(0, pool.size())
    }

    @Test
    fun pollExpiresAndDestroysItemsAtCacheLifetimeBoundary() {
        var now = 0L
        val destroyed = mutableListOf<Int>()
        val pool = AdPool(2, destroyed::add, cacheLifetimeMillis = 25L, nowMillis = { now })
        pool.offer(1)

        now = 25L

        assertNull(pool.poll())
        assertEquals(listOf(1), destroyed)
    }
}
