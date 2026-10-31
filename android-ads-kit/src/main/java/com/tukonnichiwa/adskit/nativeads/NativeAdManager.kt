package com.tukonnichiwa.adskit.nativeads

import android.content.Context
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.tukonnichiwa.adskit.AdsRequestGate
import com.tukonnichiwa.adskit.AdFormat
import com.tukonnichiwa.adskit.PaidEventListener
import com.tukonnichiwa.adskit.internal.notifyPaid
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Loads and preloads Google Mobile Ads native ads and keeps a bounded in-memory pool per placement.
 * Create one manager per application process and call [close] when the application is terminating.
 */
public class NativeAdManager(
    context: Context,
    private val maxCachedAdsPerPlacement: Int = 3,
    private val requestGate: AdsRequestGate = AdsRequestGate.allowAll,
    private val paidEventListener: PaidEventListener? = null
) : Closeable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pools = ConcurrentHashMap<String, AdPool<NativeAd>>()
    private val states = ConcurrentHashMap<String, MutableStateFlow<NativePlacementState>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val placements = ConcurrentHashMap<String, NativePlacement>()
    private val closed = AtomicBoolean(false)

    init {
        require(maxCachedAdsPerPlacement > 0) { "maxCachedAdsPerPlacement must be greater than zero" }
    }

    /**
     * Loads one ad asynchronously.
     *
     * The SDK request is started on the main thread. Cancellation abandons the result and destroys
     * an ad that arrives after cancellation. On success, ownership transfers to the caller.
     *
     * @param adUnitId non-blank SDK ad unit ID
     * @param placement optional placement supplying media and video request options
     */
    public suspend fun load(adUnitId: String, placement: NativePlacement? = null): NativeAdLoadResult {
        checkOpen()
        require(adUnitId.isNotBlank()) { "adUnitId must not be blank" }
        if (!requestGate.canRequestAds()) return NativeAdLoadResult.BlockedByRequestGate
        val options = placement?.let(::nativeAdOptions) ?: nativeAdOptions(null)
        val primary = placement?.highFloorAdUnitId ?: adUnitId
        val normal = when {
            placement?.highFloorAdUnitId != null -> adUnitId
            placement?.fallbackAdUnitId != null -> placement.fallbackAdUnitId
            else -> null
        }
        val timeoutMillis = placement?.loadTimeoutMillis ?: 30_000L
        val first = loadOne(primary, options, timeoutMillis)
        return if (first is NativeAdLoadResult.Failed && normal != null && normal != primary) {
            loadOne(normal, options, timeoutMillis)
        } else first
    }

    /**
     * Starts a sequential preload and publishes the result through [observe].
     * The primary unit is tried first for each slot; its fallback is tried only after failure.
     * Calling this while the same placement is already preloading returns the active job.
     *
     * @return job that can be cancelled to stop the remaining requests
     */
    @Synchronized
    public fun preload(placement: NativePlacement): Job {
        checkOpen()
        registerPlacement(placement)
        jobs[placement.key]?.takeIf(Job::isActive)?.let { return it }
        if (!requestGate.canRequestAds()) {
            states.computeIfAbsent(placement.key) { MutableStateFlow(NativePlacementState.Idle) }
                .value = NativePlacementState.BlockedByRequestGate
            return scope.launch { }
        }

        states.computeIfAbsent(placement.key) { MutableStateFlow(NativePlacementState.Idle) }
            .value = NativePlacementState.Loading

        return scope.launch {
            var lastFailure: NativeAdLoadFailure? = null
            var blockedByRequestGate = false
            for (index in 0 until placement.preloadCount) {
                if (!requestGate.canRequestAds()) {
                    blockedByRequestGate = true
                    break
                }
                val primaryUnitId = placement.highFloorAdUnitId ?: placement.adUnitId
                val normalUnitId = if (placement.highFloorAdUnitId != null) placement.adUnitId
                    else placement.fallbackAdUnitId
                val primaryResult = loadOne(primaryUnitId, nativeAdOptions(placement), placement.loadTimeoutMillis)
                val result = when {
                    primaryResult is NativeAdLoadResult.Loaded -> primaryResult
                    primaryResult is NativeAdLoadResult.BlockedByRequestGate -> primaryResult
                    normalUnitId != null && normalUnitId != primaryUnitId -> {
                        if (primaryResult is NativeAdLoadResult.Failed) lastFailure = primaryResult.error
                        loadOne(normalUnitId, nativeAdOptions(placement), placement.loadTimeoutMillis)
                    }
                    else -> primaryResult
                }

                when (result) {
                    is NativeAdLoadResult.Loaded -> poolFor(placement.key).offer(result.ad)
                    is NativeAdLoadResult.Failed -> lastFailure = result.error
                    NativeAdLoadResult.BlockedByRequestGate -> blockedByRequestGate = true
                }
                if (blockedByRequestGate) break
            }

            val cachedAds = poolFor(placement.key).size()
            states.getValue(placement.key).value = when {
                cachedAds > 0 -> NativePlacementState.Ready(cachedAds)
                blockedByRequestGate -> NativePlacementState.BlockedByRequestGate
                lastFailure != null -> NativePlacementState.Failed(requireNotNull(lastFailure))
                else -> NativePlacementState.Idle
            }
        }.also { job ->
            jobs[placement.key] = job
            job.invokeOnCompletion { cause ->
                if (jobs.remove(placement.key, job) && cause != null && !closed.get()) {
                    updateReadyState(placement.key, poolFor(placement.key).size())
                }
            }
        }
    }

    /** Observes loading and cache state for a placement key. The returned flow is read-only. */
    public fun observe(placementKey: String): StateFlow<NativePlacementState> {
        require(placementKey.isNotBlank()) { "placementKey must not be blank" }
        return states.computeIfAbsent(placementKey) { MutableStateFlow(NativePlacementState.Idle) }.asStateFlow()
    }

    /**
     * Removes and returns the next cached ad, scheduling a preload when the pool is empty.
     * Ownership of a returned ad transfers to the caller. If no ad is cached yet, this returns
     * `null`; observe [observe] to track loading state.
     */
    public fun take(placement: NativePlacement, refillWhenEmpty: Boolean = true): NativeAd? {
        checkOpen()
        registerPlacement(placement)
        if (!requestGate.canRequestAds()) {
            clear(placement.key)
            states.computeIfAbsent(placement.key) { MutableStateFlow(NativePlacementState.Idle) }
                .value = NativePlacementState.BlockedByRequestGate
            return null
        }
        val ad = poolFor(placement.key).poll()
        val remaining = poolFor(placement.key).size()
        if (ad != null) updateReadyState(placement.key, remaining)
        if (ad == null && refillWhenEmpty) preload(placement)
        return ad
    }

    /** Cancels an active preload and destroys all cached ads for one placement. */
    @Synchronized
    public fun clear(placementKey: String) {
        require(placementKey.isNotBlank()) { "placementKey must not be blank" }
        jobs.remove(placementKey)?.cancel()
        pools.remove(placementKey)?.clear()
        states[placementKey]?.value = NativePlacementState.Idle
        placements.remove(placementKey)
    }

    /** Cancels all pending loads and destroys every cached native ad. */
    @Synchronized
    public fun clearAll() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        pools.values.forEach(AdPool<NativeAd>::clear)
        pools.clear()
        placements.clear()
        states.values.forEach { it.value = NativePlacementState.Idle }
        states.clear()
    }

    /** Cancels pending preloads and destroys cached ads still owned by this manager. */
    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        jobs.clear()
        pools.values.forEach(AdPool<NativeAd>::clear)
        pools.clear()
        placements.clear()
        states.values.forEach { it.value = NativePlacementState.Idle }
        states.clear()
    }

    private suspend fun loadOne(adUnitId: String, options: NativeAdOptions, timeoutMillis: Long): NativeAdLoadResult {
        if (!requestGate.canRequestAds()) return NativeAdLoadResult.BlockedByRequestGate
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) { loadOneWithoutTimeout(adUnitId, options) }
            ?: NativeAdLoadResult.Failed(NativeAdLoadFailure(-2, "android-ads-kit", "Ad request timed out"))
    }

    private suspend fun loadOneWithoutTimeout(adUnitId: String, options: NativeAdOptions): NativeAdLoadResult {
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val delivered = AtomicBoolean(false)
                continuation.invokeOnCancellation { delivered.set(true) }

                try {
                    val loader = AdLoader.Builder(appContext, adUnitId)
                        .forNativeAd { ad ->
                            if (!continuation.isActive || !delivered.compareAndSet(false, true)) {
                                ad.destroy()
                            } else if (!requestGate.canRequestAds()) {
                                ad.destroy()
                                continuation.resume(NativeAdLoadResult.BlockedByRequestGate)
                            } else {
                                ad.setOnPaidEventListener(com.google.android.gms.ads.OnPaidEventListener { value ->
                                    paidEventListener.notifyPaid(AdFormat.NATIVE, value)
                                })
                                continuation.resume(NativeAdLoadResult.Loaded(ad)) { _, result, _ ->
                                    result.ad.destroy()
                                }
                            }
                        }
                        .withAdListener(object : AdListener() {
                            override fun onAdFailedToLoad(error: LoadAdError) {
                                if (delivered.compareAndSet(false, true) && continuation.isActive) {
                                    continuation.resume(NativeAdLoadResult.Failed(error.toFailure()))
                                }
                            }
                        })
                        .withNativeAdOptions(options)
                        .build()
                    loader.loadAd(AdRequest.Builder().build())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (delivered.compareAndSet(false, true) && continuation.isActive) {
                        val result = NativeAdLoadResult.Failed(
                            NativeAdLoadFailure(-1, "android-ads-kit", failure.message.orEmpty())
                        )
                        continuation.resume(result)
                    }
                }
            }
        }
    }

    private fun nativeAdOptions(placement: NativePlacement?): NativeAdOptions =
        NativeAdOptions.Builder()
            .setMediaAspectRatio(placement?.mediaAspectRatio ?: NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_LANDSCAPE)
            .setVideoOptions(VideoOptions.Builder().setStartMuted(placement?.startVideoMuted ?: true).build())
            .build()

    private fun LoadAdError.toFailure() = NativeAdLoadFailure(code, domain, message)

    private fun registerPlacement(placement: NativePlacement) {
        val registered = placements.putIfAbsent(placement.key, placement)
        require(registered == null || registered == placement) {
            "A different placement is already registered with key '${placement.key}'"
        }
    }

    private fun poolFor(key: String): AdPool<NativeAd> =
        pools.computeIfAbsent(key) {
            AdPool(maxCachedAdsPerPlacement, NativeAd::destroy, placements[key]?.cacheLifetimeMillis ?: 60 * 60 * 1000L)
        }

    private fun updateReadyState(key: String, cachedAds: Int) {
        states.computeIfAbsent(key) { MutableStateFlow(NativePlacementState.Idle) }.value =
            if (cachedAds > 0) NativePlacementState.Ready(cachedAds) else NativePlacementState.Idle
    }

    private fun checkOpen() {
        check(!closed.get()) { "NativeAdManager is closed" }
    }
}
