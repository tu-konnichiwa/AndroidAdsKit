package com.tukonnichiwa.adskit

import android.content.Context
import com.tukonnichiwa.adskit.appopen.AppOpenAdConfig
import com.tukonnichiwa.adskit.appopen.AppOpenAdManager
import com.tukonnichiwa.adskit.banner.BannerAdManager
import com.tukonnichiwa.adskit.consent.AdsConsentManager
import com.tukonnichiwa.adskit.interstitial.InterstitialAdConfig
import com.tukonnichiwa.adskit.interstitial.InterstitialAdManager
import com.tukonnichiwa.adskit.internal.FullScreenAdCoordinator
import com.tukonnichiwa.adskit.nativeads.NativeAdManager
import com.tukonnichiwa.adskit.rewarded.RewardedAdConfig
import com.tukonnichiwa.adskit.rewarded.RewardedAdManager
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean as AtomicFlag

/** Entry point that shares consent, request eligibility, and full-screen coordination. */
public class AdsKit @JvmOverloads constructor(
    context: Context,
    public val consent: AdsConsentManager = AdsConsentManager(context),
    private val requestGate: AdsRequestGate = consent,
    private val paidEventListener: PaidEventListener? = null,
    maxCachedNativeAdsPerPlacement: Int = 3
) : Closeable {
    private val appContext = context.applicationContext
    private val coordinator = FullScreenAdCoordinator()
    private val closed = AtomicBoolean(false)
    private val adsEnabled = AtomicFlag(true)
    private val effectiveGate = AdsRequestGate { adsEnabled.get() && requestGate.canRequestAds() }
    private val managers = LinkedHashSet<Closeable>()
    public val nativeAds: NativeAdManager = NativeAdManager(
        appContext, maxCachedNativeAdsPerPlacement, effectiveGate, paidEventListener
    )
    public val banners: BannerAdManager = BannerAdManager(effectiveGate)
    public val isFullScreenAdShowing: Boolean get() = coordinator.isVisible
    public val isFullScreenAdActive: Boolean get() = coordinator.isActive

    /** Enables requests or clears every pending/cached placement when disabled. Call on main. */
    public fun setAdsEnabled(enabled: Boolean) {
        check(!closed.get()) { "AdsKit is closed" }
        if (adsEnabled.getAndSet(enabled) == enabled) return
        if (!enabled) {
            synchronized(managers) { managers.toList() }.forEach { manager ->
                when (manager) {
                    is InterstitialAdManager -> manager.clear()
                    is RewardedAdManager -> manager.clear()
                    is AppOpenAdManager -> manager.clear()
                }
            }
            nativeAds.clearAll()
            banners.clear()
        }
    }

    /** Forward the owning app/process background event for app-open eligibility checks. */
    public fun recordBackground() { coordinator.recordBackground() }

    /** Initializes the SDK once, applying [configuration] process-wide. */
    public suspend fun initialize(
        configuration: AdsRequestConfiguration = AdsRequestConfiguration()
    ) {
        check(!closed.get()) { "AdsKit is closed" }
        AdsSdk.initialize(appContext, configuration)
    }

    public fun interstitial(config: InterstitialAdConfig): InterstitialAdManager =
        InterstitialAdManager(appContext, config, effectiveGate, coordinator, paidEventListener).also(::register)

    public fun rewarded(config: RewardedAdConfig): RewardedAdManager =
        RewardedAdManager(appContext, config, effectiveGate, coordinator, paidEventListener).also(::register)

    public fun appOpen(config: AppOpenAdConfig): AppOpenAdManager =
        AppOpenAdManager(appContext, config, effectiveGate, coordinator, paidEventListener).also(::register)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(managers) { managers.toList().also { managers.clear() } }.forEach(Closeable::close)
        nativeAds.close(); banners.close()
    }

    private fun register(manager: Closeable) {
        check(!closed.get()) { "AdsKit is closed" }
        synchronized(managers) {
            check(!closed.get()) { "AdsKit is closed" }
            managers.add(manager)
        }
    }
}
