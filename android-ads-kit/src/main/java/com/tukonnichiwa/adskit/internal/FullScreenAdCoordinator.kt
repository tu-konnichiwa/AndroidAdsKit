package com.tukonnichiwa.adskit.internal

import android.os.SystemClock
import com.tukonnichiwa.adskit.AdFormat
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap

/** Serializes full-screen ad presentations across formats created by one AdsKit instance. */
internal class FullScreenAdCoordinator(private val nowMillis: () -> Long = SystemClock::elapsedRealtime) {
    private val activeLease = AtomicReference<Lease?>(null)
    private val lastVisibleByFormat = ConcurrentHashMap<AdFormat, Long>()
    @Volatile private var lastVisibleAt: Long = 0L
    @Volatile private var backgroundedAt: Long? = null
    val lastVisibleAtMillis: Long get() = lastVisibleAt

    val isActive: Boolean
        get() = activeLease.get() != null

    val isVisible: Boolean
        get() = activeLease.get()?.isVisible == true

    fun acquire(format: AdFormat): Lease? {
        val lease = Lease(format, ::release, ::markVisible)
        return if (activeLease.compareAndSet(null, lease)) lease else null
    }

    fun recordBackground() { backgroundedAt = nowMillis() }

    fun backgroundAgeMillis(): Long? = backgroundedAt?.let { (nowMillis() - it).coerceAtLeast(0L) }

    fun clearBackground() { backgroundedAt = null }

    fun isWithinInterval(format: AdFormat, intervalMillis: Long): Boolean {
        if (intervalMillis <= 0L) return false
        val last = lastVisibleByFormat[format] ?: return false
        return nowMillis() - last < intervalMillis
    }

    private fun release(lease: Lease) {
        activeLease.compareAndSet(lease, null)
    }

    private fun markVisible(lease: Lease) {
        val now = nowMillis()
        lastVisibleAt = now
        lastVisibleByFormat[lease.format] = now
    }

    internal class Lease(
        val format: AdFormat,
        private val release: (Lease) -> Unit,
        private val onVisible: (Lease) -> Unit
    ) : Closeable {
        private val released = AtomicBoolean(false)
        @Volatile
        var isVisible: Boolean = false
            private set

        fun markVisible() {
            if (!released.get() && !isVisible) {
                isVisible = true
                onVisible(this)
            }
        }

        override fun close() {
            if (released.compareAndSet(false, true)) release(this)
        }
    }
}
