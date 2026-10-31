package com.tukonnichiwa.adskit.nativeads

import android.os.SystemClock

internal class AdPool<T>(
    private val capacity: Int,
    private val destroy: (T) -> Unit,
    private val cacheLifetimeMillis: Long = 60 * 60 * 1000L,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime
) {
    private data class Entry<T>(val value: T, val loadedAt: Long)
    private val items = ArrayDeque<Entry<T>>()

    init {
        require(capacity > 0) { "capacity must be greater than zero" }
    }

    fun offer(item: T): Boolean {
        val accepted = synchronized(this) {
            if (items.size >= capacity) {
                false
            } else {
                items.addLast(Entry(item, nowMillis()))
                true
            }
        }
        if (!accepted) {
            destroy(item)
        }
        return accepted
    }

    fun poll(): T? {
        val expired = mutableListOf<T>()
        val result = synchronized(this) {
            val now = nowMillis()
            while (items.firstOrNull()?.let { now - it.loadedAt >= cacheLifetimeMillis } == true) {
                expired += items.removeFirst().value
            }
            items.removeFirstOrNull()?.value
        }
        expired.forEach(destroy)
        return result
    }

    @Synchronized
    fun size(): Int {
        val expired = mutableListOf<T>()
        val count = synchronized(this) {
            val now = nowMillis()
            val iterator = items.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.loadedAt >= cacheLifetimeMillis) {
                    expired += entry.value
                    iterator.remove()
                }
            }
            items.size
        }
        expired.forEach(destroy)
        return count
    }

    fun clear() {
        val discarded = synchronized(this) {
            items.map(Entry<T>::value).also { items.clear() }
        }
        discarded.forEach(destroy)
    }
}
