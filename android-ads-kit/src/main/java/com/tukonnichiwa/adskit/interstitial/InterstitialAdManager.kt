package com.tukonnichiwa.adskit.interstitial

import android.app.Activity
import android.content.Context
import android.os.Looper
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.tukonnichiwa.adskit.AdFormat
import com.tukonnichiwa.adskit.AdLoadState
import com.tukonnichiwa.adskit.AdLoadFailure
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
import com.tukonnichiwa.adskit.fullscreen.AdLoadingDialog
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Loads and presents interstitial ads for one placement. */
public class InterstitialAdManager internal constructor(
    context: Context,
    private val config: InterstitialAdConfig,
    private val gate: AdsRequestGate,
    private val coordinator: FullScreenAdCoordinator,
    private val paidListener: PaidEventListener?
) : Closeable {
    private val activityContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val slot = FallbackAdSlot(
        config.highFloorAdUnitId ?: config.adUnitId,
        if (config.highFloorAdUnitId != null) config.adUnitId else config.fallbackAdUnitId,
        gate, scope, ::loadOne, cacheLifetimeMillis = config.cacheLifetimeMillis,
        loadTimeoutMillis = config.loadTimeoutMillis
    )
    private var skips = config.initialSkippedShowRequests
    public val state: StateFlow<AdLoadState> get() = slot.state

    public fun preload() { slot.preload() }
    public fun clear() { slot.clear() }

    public fun show(
        activity: Activity,
        listener: FullScreenAdEventListener? = null,
        excluded: Boolean = false,
        bypassInterval: Boolean = false
    ): FullScreenShowResult {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Interstitial show must run on main thread" }
        if (!gate.canRequestAds()) return FullScreenShowResult.RequestBlocked
        if (!activity.isResumedForAd()) return FullScreenShowResult.ActivityNotResumed
        if (excluded) return FullScreenShowResult.SkippedByPolicy
        if (skips > 0) { skips--; slot.preload(); return FullScreenShowResult.SkippedByPolicy }
        if (!bypassInterval && coordinator.isWithinInterval(AdFormat.INTERSTITIAL, config.minimumIntervalMillis)) return FullScreenShowResult.CooldownActive
        val lease = coordinator.acquire(AdFormat.INTERSTITIAL) ?: return FullScreenShowResult.AnotherAdIsActive
        val ad = slot.take() ?: run { lease.close(); slot.preload(); return FullScreenShowResult.AdNotReady }
        val dialog = config.loadingDialogStyle?.let { AdLoadingDialog(activity, it) }
        if (config.preShowDelayMillis > 0L) {
            dialog?.show()
            scope.launch {
                var handedToSdk = false
                try {
                    delay(config.preShowDelayMillis)
                    dialog?.dismiss()
                    if (!activity.isResumedForAd() || !gate.canRequestAds()) {
                        if (config.reloadAfterDismissOrFailure) slot.preload()
                        return@launch
                    }
                    present(ad, activity, listener, lease)
                    handedToSdk = true
                } finally {
                    dialog?.dismiss()
                    if (!handedToSdk) lease.close()
                }
            }
        } else present(ad, activity, listener, lease)
        return FullScreenShowResult.Started
    }

    override fun close() { slot.close(); scope.cancel() }

    private fun present(ad: InterstitialAd, activity: Activity, listener: FullScreenAdEventListener?, lease: FullScreenAdCoordinator.Lease) {
        if (config.immersiveMode) ad.setImmersiveMode(true)
        ad.setOnPaidEventListener(com.google.android.gms.ads.OnPaidEventListener { value -> paidListener.notifyPaid(AdFormat.INTERSTITIAL, value) })
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() { lease.markVisible(); listener?.onEvent(FullScreenAdEvent.Shown(AdFormat.INTERSTITIAL)) }
            override fun onAdDismissedFullScreenContent() { listener?.onEvent(FullScreenAdEvent.Dismissed(AdFormat.INTERSTITIAL)); ad.fullScreenContentCallback = null; lease.close(); if (config.reloadAfterDismissOrFailure) slot.preload() }
            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) { listener?.onEvent(FullScreenAdEvent.Failed(AdFormat.INTERSTITIAL, AdLoadFailure(error.code, error.domain, error.message))); ad.fullScreenContentCallback = null; lease.close(); if (config.reloadAfterDismissOrFailure) slot.preload() }
            override fun onAdClicked() { listener?.onEvent(FullScreenAdEvent.Clicked(AdFormat.INTERSTITIAL)) }
            override fun onAdImpression() { listener?.onEvent(FullScreenAdEvent.Impression(AdFormat.INTERSTITIAL)) }
        }
        try { ad.show(activity) } catch (error: Exception) { ad.fullScreenContentCallback = null; lease.close(); if (config.reloadAfterDismissOrFailure) slot.preload(); listener?.onEvent(FullScreenAdEvent.Failed(AdFormat.INTERSTITIAL, AdLoadFailure(-1, "android-ads-kit", error.message.orEmpty()))) }
    }

    private suspend fun loadOne(id: String): AdUnitLoadResult<InterstitialAd> = awaitAdLoad(gate) { complete ->
        InterstitialAd.load(activityContext, id, AdRequest.Builder().build(), object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) = complete(AdUnitLoadResult.Loaded(ad))
            override fun onAdFailedToLoad(error: LoadAdError) = complete(AdUnitLoadResult.Failed(AdLoadFailure(error.code, error.domain, error.message)))
        })
    }
}
