package com.tukonnichiwa.adskit.internal

import android.os.SystemClock
import com.tukonnichiwa.adskit.AdLoadFailure
import com.tukonnichiwa.adskit.AdLoadState
import com.tukonnichiwa.adskit.AdsRequestGate
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface AdUnitLoadResult<out T> {
    data class Loaded<T>(val ad: T) : AdUnitLoadResult<T>
    data class Failed(val failure: AdLoadFailure) : AdUnitLoadResult<Nothing>
    data object Blocked : AdUnitLoadResult<Nothing>
}

/** Owns a single cached full-screen ad and serializes primary/fallback load attempts. */
internal class FallbackAdSlot<T>(
    private val primaryAdUnitId: String,
    private val fallbackAdUnitId: String?,
    private val requestGate: AdsRequestGate,
    private val scope: CoroutineScope,
    private val loader: suspend (String) -> AdUnitLoadResult<T>,
    private val destroy: (T) -> Unit = {},
    private val cacheLifetimeMillis: Long = DEFAULT_CACHE_LIFETIME_MILLIS,
    private val loadTimeoutMillis: Long = DEFAULT_LOAD_TIMEOUT_MILLIS,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime
) : AutoCloseable {
    private val mutableState = MutableStateFlow<AdLoadState>(AdLoadState.Idle)
    val state: StateFlow<AdLoadState>
        get() { expireIfNeeded(); return mutableState.asStateFlow() }

    private var loadedAd: T? = null
    private var loadedAtMillis: Long = 0L
    private var loadJob: Job? = null
    private val closed = AtomicBoolean(false)

    val hasReadyAd: Boolean
        @Synchronized get() = loadedAd != null

    init {
        require(primaryAdUnitId.isNotBlank()) { "primaryAdUnitId must not be blank" }
        require(fallbackAdUnitId == null || fallbackAdUnitId.isNotBlank()) {
            "fallbackAdUnitId must be null or non-blank"
        }
        require(cacheLifetimeMillis > 0L) { "cacheLifetimeMillis must be greater than zero" }
        require(loadTimeoutMillis in 1L..MAX_LOAD_TIMEOUT_MILLIS) {
            "loadTimeoutMillis must be between 1 and $MAX_LOAD_TIMEOUT_MILLIS milliseconds"
        }
    }

    @Synchronized
    fun preload(): Job {
        check(!closed.get()) { "Ad slot is closed" }
        expireIfNeeded()
        if (loadedAd != null) return completedJob()
        loadJob?.takeIf(Job::isActive)?.let { return it }
        if (!requestGate.canRequestAds()) {
            mutableState.value = AdLoadState.BlockedByRequestGate
            return completedJob()
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            mutableState.value = AdLoadState.Loading
            try {
                val primary = loadUnit(primaryAdUnitId)
                val result = when {
                    primary is AdUnitLoadResult.Loaded -> primary
                    primary is AdUnitLoadResult.Blocked -> primary
                    fallbackAdUnitId != null && fallbackAdUnitId != primaryAdUnitId -> loadUnit(fallbackAdUnitId)
                    else -> primary
                }

                when (result) {
                    is AdUnitLoadResult.Loaded -> {
                        if (closed.get() || !requestGate.canRequestAds()) {
                            destroy(result.ad)
                            mutableState.value = if (closed.get()) AdLoadState.Idle
                            else AdLoadState.BlockedByRequestGate
                        } else {
                            synchronized(this@FallbackAdSlot) {
                                if (closed.get()) {
                                    destroy(result.ad)
                                    mutableState.value = AdLoadState.Idle
                                } else {
                                    loadedAd = result.ad
                                    loadedAtMillis = nowMillis()
                                    mutableState.value = AdLoadState.Ready
                                }
                            }
                        }
                    }
                    is AdUnitLoadResult.Failed -> {
                        mutableState.value = AdLoadState.Failed(result.failure)
                    }
                    AdUnitLoadResult.Blocked -> mutableState.value = AdLoadState.BlockedByRequestGate
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = AdLoadState.Idle
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = AdLoadState.Failed(
                    AdLoadFailure(-1, "android-ads-kit", error.message.orEmpty())
                )
            }
        }
        loadJob = job
        job.invokeOnCompletion {
            synchronized(this) {
                if (loadJob === job) loadJob = null
            }
        }
        job.start()
        return job
    }

    suspend fun awaitPreload(): Boolean {
        val job = preload()
        // Each individual unit request has its own timeout in loadUnit(). Waiting here for the
        // whole job lets a fallback unit use its full timeout after the primary unit times out.
        job.join()
        expireIfNeeded()
        return synchronized(this) { loadedAd != null }
    }

    @Synchronized
    fun take(): T? {
        expireIfNeeded()
        val ad = loadedAd ?: return null
        loadedAd = null
        loadedAtMillis = 0L
        mutableState.value = AdLoadState.Idle
        return ad
    }

    @Synchronized
    fun returnUnused(ad: T) {
        if (closed.get() || loadedAd != null) {
            destroy(ad)
            return
        }
        loadedAd = ad
        loadedAtMillis = nowMillis()
        mutableState.value = AdLoadState.Ready
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val ad: T?
        synchronized(this) {
            loadJob?.cancel()
            loadJob = null
            ad = loadedAd
            loadedAd = null
            loadedAtMillis = 0L
            mutableState.value = AdLoadState.Idle
        }
        ad?.let(destroy)
    }

    private fun completedJob(): Job = Job().apply { complete() }

    @Synchronized
    fun clear() {
        loadJob?.cancel()
        loadJob = null
        loadedAd?.let(destroy)
        loadedAd = null
        loadedAtMillis = 0L
        mutableState.value = AdLoadState.Idle
    }

    private suspend fun loadUnit(adUnitId: String): AdUnitLoadResult<T> =
        kotlinx.coroutines.withTimeoutOrNull(loadTimeoutMillis) { loader(adUnitId) }
            ?: AdUnitLoadResult.Failed(AdLoadFailure(-2, "android-ads-kit", "Ad request timed out"))

    @Synchronized
    private fun expireIfNeeded() {
        if (loadedAd != null && nowMillis() - loadedAtMillis >= cacheLifetimeMillis) {
            loadedAd?.let(destroy)
            loadedAd = null
            loadedAtMillis = 0L
            mutableState.value = AdLoadState.Idle
        }
    }

    private companion object {
        const val DEFAULT_CACHE_LIFETIME_MILLIS = 60 * 60 * 1000L
        const val DEFAULT_LOAD_TIMEOUT_MILLIS = 30_000L
        const val MAX_LOAD_TIMEOUT_MILLIS = 300_000L
    }
}
