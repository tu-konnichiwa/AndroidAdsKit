package com.tukonnichiwa.adskit.appopen

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.tukonnichiwa.adskit.AdFormat
import com.tukonnichiwa.adskit.AdLoadFailure
import com.tukonnichiwa.adskit.AdLoadState
import com.tukonnichiwa.adskit.AdsRequestGate
import com.tukonnichiwa.adskit.FullScreenAdEvent
import com.tukonnichiwa.adskit.FullScreenAdEventListener
import com.tukonnichiwa.adskit.FullScreenShowResult
import com.tukonnichiwa.adskit.PaidEventListener
import com.tukonnichiwa.adskit.internal.AdUnitLoadResult
import com.tukonnichiwa.adskit.internal.FallbackAdSlot
import com.tukonnichiwa.adskit.internal.FullScreenAdCoordinator
import com.tukonnichiwa.adskit.internal.awaitAdLoad
import com.tukonnichiwa.adskit.internal.isResumedForAd
import com.tukonnichiwa.adskit.internal.notifyPaid
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

/** Caches and presents app-open ads. The host app decides when app foreground events trigger [show]. */
public class AppOpenAdManager internal constructor(
    context: Context,
    private val config: AppOpenAdConfig,
    private val gate: AdsRequestGate,
    private val coordinator: FullScreenAdCoordinator,
    private val paidListener: PaidEventListener?
) : Closeable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val slot = FallbackAdSlot(
        config.highFloorAdUnitId ?: config.adUnitId,
        if (config.highFloorAdUnitId != null) config.adUnitId else config.fallbackAdUnitId,
        gate, scope, ::loadOne, cacheLifetimeMillis = config.maximumCacheAgeMillis,
        loadTimeoutMillis = config.loadTimeoutMillis
    )
    private var loadedAt = 0L
    public fun preload() { slot.preload() }
    public fun clear() { slot.clear(); loadedAt = 0L }
    public val state: StateFlow<AdLoadState> get() = slot.state

    public fun show(
        activity: Activity,
        listener: FullScreenAdEventListener? = null,
        excluded: Boolean = false,
        bypassInterval: Boolean = false
    ): FullScreenShowResult {
        if (!gate.canRequestAds()) return FullScreenShowResult.RequestBlocked
        if (!activity.isResumedForAd()) return FullScreenShowResult.ActivityNotResumed
        if (excluded) return FullScreenShowResult.SkippedByPolicy
        if (!bypassInterval && coordinator.isWithinInterval(AdFormat.APP_OPEN, config.minimumIntervalMillis)) return FullScreenShowResult.CooldownActive
        val backgroundAge = coordinator.backgroundAgeMillis()
        if (backgroundAge == null && !config.allowsColdStart) return FullScreenShowResult.ColdStartDisabled
        if (backgroundAge != null && backgroundAge < config.minimumBackgroundDurationMillis) return FullScreenShowResult.BackgroundTooShort
        if (loadedAt > 0L && SystemClock.elapsedRealtime() - loadedAt >= config.maximumCacheAgeMillis) {
            slot.take(); loadedAt = 0L; slot.preload(); return FullScreenShowResult.AdNotReady
        }
        val lease = coordinator.acquire(AdFormat.APP_OPEN) ?: return FullScreenShowResult.AnotherAdIsActive
        val ad = slot.take() ?: run { lease.close(); slot.preload(); return FullScreenShowResult.AdNotReady }
        loadedAt = 0L
        if (config.immersiveMode) ad.setImmersiveMode(true)
        ad.setOnPaidEventListener(com.google.android.gms.ads.OnPaidEventListener { value ->
            paidListener.notifyPaid(AdFormat.APP_OPEN, value)
        })
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() { lease.markVisible(); listener?.onEvent(FullScreenAdEvent.Shown(AdFormat.APP_OPEN)) }
            override fun onAdDismissedFullScreenContent() { listener?.onEvent(FullScreenAdEvent.Dismissed(AdFormat.APP_OPEN)); ad.fullScreenContentCallback = null; lease.close(); coordinator.clearBackground(); if (config.reloadAfterDismissOrFailure) slot.preload() }
            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) { listener?.onEvent(FullScreenAdEvent.Failed(AdFormat.APP_OPEN, AdLoadFailure(error.code, error.domain, error.message))); ad.fullScreenContentCallback = null; lease.close(); coordinator.clearBackground(); if (config.reloadAfterDismissOrFailure) slot.preload() }
            override fun onAdClicked() { listener?.onEvent(FullScreenAdEvent.Clicked(AdFormat.APP_OPEN)) }
            override fun onAdImpression() { listener?.onEvent(FullScreenAdEvent.Impression(AdFormat.APP_OPEN)) }
        }
        try { ad.show(activity) } catch (error: Exception) { ad.fullScreenContentCallback = null; lease.close(); if (config.reloadAfterDismissOrFailure) slot.preload(); return FullScreenShowResult.AdNotReady }
        return FullScreenShowResult.Started
    }

    override fun close() { slot.close(); scope.cancel() }

    private suspend fun loadOne(id: String): AdUnitLoadResult<AppOpenAd> = awaitAdLoad(gate) { complete ->
        AppOpenAd.load(appContext, id, AdRequest.Builder().build(), object : AppOpenAd.AppOpenAdLoadCallback() {
            override fun onAdLoaded(ad: AppOpenAd) { loadedAt = SystemClock.elapsedRealtime(); complete(AdUnitLoadResult.Loaded(ad)) }
            override fun onAdFailedToLoad(error: LoadAdError) = complete(AdUnitLoadResult.Failed(AdLoadFailure(error.code, error.domain, error.message)))
        })
    }
}
