package com.tukonnichiwa.adskit.rewarded

import android.app.Activity
import android.content.Context
import android.os.Looper
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.tukonnichiwa.adskit.AdFormat
import com.tukonnichiwa.adskit.AdLoadFailure
import com.tukonnichiwa.adskit.AdLoadState
import com.tukonnichiwa.adskit.AdsRequestGate
import com.tukonnichiwa.adskit.FullScreenAdEvent
import com.tukonnichiwa.adskit.FullScreenAdEventListener
import com.tukonnichiwa.adskit.FullScreenShowResult
import com.tukonnichiwa.adskit.PaidEventListener
import com.tukonnichiwa.adskit.fullscreen.AdLoadingDialog
import com.tukonnichiwa.adskit.internal.AdUnitLoadResult
import com.tukonnichiwa.adskit.internal.FallbackAdSlot
import com.tukonnichiwa.adskit.internal.FullScreenAdCoordinator
import com.tukonnichiwa.adskit.internal.awaitAdLoad
import com.tukonnichiwa.adskit.internal.isResumedForAd
import com.tukonnichiwa.adskit.internal.notifyPaid
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Loads and presents rewarded ads, reporting the reward through [FullScreenAdEvent.RewardEarned]. */
public class RewardedAdManager internal constructor(
    context: Context,
    private val config: RewardedAdConfig,
    private val gate: AdsRequestGate,
    private val coordinator: FullScreenAdCoordinator,
    private val paidListener: PaidEventListener?
) : Closeable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val slot = FallbackAdSlot(
        config.highFloorAdUnitId ?: config.adUnitId,
        if (config.highFloorAdUnitId != null) config.adUnitId else config.fallbackAdUnitId,
        gate, scope, ::loadOne, cacheLifetimeMillis = config.cacheLifetimeMillis,
        loadTimeoutMillis = config.loadTimeoutMillis
    )
    public val state: StateFlow<AdLoadState> get() = slot.state
    public fun preload() { slot.preload() }
    public fun clear() { slot.clear() }

    public fun show(
        activity: Activity,
        listener: FullScreenAdEventListener? = null,
        excluded: Boolean = false,
        bypassInterval: Boolean = false
    ): FullScreenShowResult {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Rewarded show must run on main thread" }
        if (!gate.canRequestAds()) return FullScreenShowResult.RequestBlocked
        if (!activity.isResumedForAd()) return FullScreenShowResult.ActivityNotResumed
        if (excluded) return FullScreenShowResult.SkippedByPolicy
        if (!bypassInterval && coordinator.isWithinInterval(AdFormat.REWARDED, config.minimumIntervalMillis)) return FullScreenShowResult.CooldownActive
        val lease = coordinator.acquire(AdFormat.REWARDED) ?: return FullScreenShowResult.AnotherAdIsActive
        val ad = slot.take() ?: run { lease.close(); slot.preload(); return FullScreenShowResult.AdNotReady }
        present(ad, activity, listener, lease)
        return FullScreenShowResult.Started
    }

    /** Waits for a load before presenting; returns the same immediate result contract as [show]. */
    public suspend fun loadAndShow(
        activity: Activity,
        listener: FullScreenAdEventListener? = null,
        excluded: Boolean = false,
        bypassInterval: Boolean = false
    ): FullScreenShowResult {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Rewarded show must start on main thread" }
        if (!gate.canRequestAds()) return FullScreenShowResult.RequestBlocked
        if (!activity.isResumedForAd()) return FullScreenShowResult.ActivityNotResumed
        if (excluded) return FullScreenShowResult.SkippedByPolicy
        if (!bypassInterval && coordinator.isWithinInterval(AdFormat.REWARDED, config.minimumIntervalMillis)) return FullScreenShowResult.CooldownActive
        val lease = coordinator.acquire(AdFormat.REWARDED) ?: return FullScreenShowResult.AnotherAdIsActive
        val dialog = config.loadingDialogStyle?.let { AdLoadingDialog(activity, it).also(AdLoadingDialog::show) }
        var transferredToPresentation = false
        try {
            val ready = slot.awaitPreload()
            val ad = if (ready) slot.take() else null
            if (ad == null || !activity.isResumedForAd() || !gate.canRequestAds()) {
                return if (!gate.canRequestAds()) FullScreenShowResult.RequestBlocked else FullScreenShowResult.AdNotReady
            }
            if (config.preShowDelayMillis > 0L) delay(config.preShowDelayMillis)
            if (!activity.isResumedForAd()) return FullScreenShowResult.ActivityNotResumed
            present(ad, activity, listener, lease)
            transferredToPresentation = true
            return FullScreenShowResult.Started
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            dialog?.dismiss()
            if (!transferredToPresentation) lease.close()
        }
    }

    override fun close() { slot.close(); scope.cancel() }

    private fun present(ad: RewardedAd, activity: Activity, listener: FullScreenAdEventListener?, lease: FullScreenAdCoordinator.Lease) {
        if (config.immersiveMode) ad.setImmersiveMode(true)
        ad.setOnPaidEventListener(com.google.android.gms.ads.OnPaidEventListener { value -> paidListener.notifyPaid(AdFormat.REWARDED, value) })
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() { lease.markVisible(); listener?.onEvent(FullScreenAdEvent.Shown(AdFormat.REWARDED)) }
            override fun onAdDismissedFullScreenContent() { listener?.onEvent(FullScreenAdEvent.Dismissed(AdFormat.REWARDED)); ad.fullScreenContentCallback = null; lease.close(); if (config.reloadAfterDismissOrFailure) slot.preload() }
            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) { listener?.onEvent(FullScreenAdEvent.Failed(AdFormat.REWARDED, AdLoadFailure(error.code, error.domain, error.message))); ad.fullScreenContentCallback = null; lease.close(); if (config.reloadAfterDismissOrFailure) slot.preload() }
            override fun onAdClicked() { listener?.onEvent(FullScreenAdEvent.Clicked(AdFormat.REWARDED)) }
            override fun onAdImpression() { listener?.onEvent(FullScreenAdEvent.Impression(AdFormat.REWARDED)) }
        }
        val rewardDelivered = AtomicBoolean(false)
        try {
            ad.show(activity) { reward ->
                if (rewardDelivered.compareAndSet(false, true)) {
                    listener?.onEvent(FullScreenAdEvent.RewardEarned(AdFormat.REWARDED, reward.type, reward.amount))
                }
            }
        }
        catch (error: Exception) { ad.fullScreenContentCallback = null; lease.close(); if (config.reloadAfterDismissOrFailure) slot.preload(); listener?.onEvent(FullScreenAdEvent.Failed(AdFormat.REWARDED, AdLoadFailure(-1, "android-ads-kit", error.message.orEmpty()))) }
    }

    private suspend fun loadOne(id: String): AdUnitLoadResult<RewardedAd> = awaitAdLoad(gate) { complete ->
        RewardedAd.load(appContext, id, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) = complete(AdUnitLoadResult.Loaded(ad))
            override fun onAdFailedToLoad(error: LoadAdError) = complete(AdUnitLoadResult.Failed(AdLoadFailure(error.code, error.domain, error.message)))
        })
    }
}
