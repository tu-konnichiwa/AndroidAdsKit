package com.tukonnichiwa.adskit.banner

import android.app.Activity
import android.os.Bundle
import android.os.Looper
import android.os.Handler
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.AdValue
import com.tukonnichiwa.adskit.AdFormat
import com.tukonnichiwa.adskit.AdLoadFailure
import com.tukonnichiwa.adskit.AdRevenueEvent
import com.tukonnichiwa.adskit.AdRevenuePrecision
import com.tukonnichiwa.adskit.AdsRequestGate
import com.tukonnichiwa.adskit.PaidEventListener
import com.tukonnichiwa.adskit.R
import java.io.Closeable
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Creates lifecycle-bound adaptive, fixed-size, medium-rectangle, and collapsible banners. */
public class BannerAdManager(
    private val requestGate: AdsRequestGate = AdsRequestGate.allowAll
) : Closeable {
    private val sessions = Collections.newSetFromMap(WeakHashMap<BannerAdSession, Boolean>())

    /**
     * Loads a banner into [container], replacing any previous session attached there.
     * Must be called on the main thread. Close the returned session when its host is destroyed.
     */
    public fun load(
        activity: Activity,
        container: FrameLayout,
        adUnitId: String,
        options: BannerAdOptions = BannerAdOptions(),
        lifecycleOwner: LifecycleOwner? = null,
        paidEventListener: PaidEventListener? = null
    ): BannerAdSession {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Banner requests must start on the main thread" }
        require(!activity.isFinishing && !activity.isDestroyed) { "activity must be active" }
        require(adUnitId.isNotBlank()) { "adUnitId must not be blank" }
        (container.getTag(R.id.adkit_banner_session) as? BannerAdSession)?.destroy()
        return BannerAdSession(
            activity,
            container,
            adUnitId,
            options,
            lifecycleOwner,
            requestGate,
            paidEventListener,
            ::removeSession
        ).also { session ->
            sessions.add(session)
            container.setTag(R.id.adkit_banner_session, session)
        }
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Banner sessions must close on the main thread" }
        sessions.toList().forEach(BannerAdSession::destroy)
        sessions.clear()
    }

    /** Releases every currently displayed banner session. */
    public fun clear() = close()

    private fun removeSession(session: BannerAdSession) {
        sessions.remove(session)
    }
}

/** Owns the [AdView] inserted into one banner host. */
public class BannerAdSession internal constructor(
    private val activity: Activity,
    private val container: FrameLayout,
    adUnitId: String,
    private val options: BannerAdOptions,
    lifecycleOwner: LifecycleOwner?,
    private val requestGate: AdsRequestGate,
    private val paidEventListener: PaidEventListener?,
    private val onDestroyed: (BannerAdSession) -> Unit
) : Closeable, DefaultLifecycleObserver {
    private val mutableState = MutableStateFlow<BannerAdState>(BannerAdState.Loading)
    public val state: StateFlow<BannerAdState> = mutableState.asStateFlow()
    private val lifecycle = lifecycleOwner?.lifecycle
    private var closed = false
    private var banner = AdView(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null
    private var requestGeneration = 0
    private var selectedSize: AdSize? = null
    private val adUnitIds: List<String> = when {
        options.highFloorAdUnitId != null -> listOf(options.highFloorAdUnitId, adUnitId).distinct()
        options.fallbackAdUnitId != null -> listOf(adUnitId, options.fallbackAdUnitId).distinct()
        else -> listOf(adUnitId)
    }

    init {
        if (!requestGate.canRequestAds()) {
            mutableState.value = BannerAdState.BlockedByRequestGate
        } else {
            lifecycle?.addObserver(this)
            when (val size = options.size) {
                BannerAdSize.AnchoredAdaptive -> container.post { loadAdaptive(activity) }
                BannerAdSize.MediumRectangle -> loadWithSize(AdSize.MEDIUM_RECTANGLE)
                is BannerAdSize.Fixed -> loadWithSize(size.adSize)
            }
        }
    }

    /** Returns the underlying SDK view, if a request was allowed. */
    public val adView: AdView?
        get() = if (closed || mutableState.value == BannerAdState.BlockedByRequestGate) null else banner

    /** Releases the SDK view and removes it from its host. Safe to call repeatedly. */
    public fun destroy() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Banner sessions must close on the main thread" }
        if (closed) return
        closed = true
        requestGeneration++
        timeout?.let(handler::removeCallbacks)
        timeout = null
        lifecycle?.removeObserver(this)
        destroyBannerView()
        if (container.getTag(R.id.adkit_banner_session) === this) {
            container.setTag(R.id.adkit_banner_session, null)
        }
        mutableState.value = BannerAdState.Destroyed
        onDestroyed(this)
    }

    override fun close() = destroy()

    override fun onResume(owner: LifecycleOwner) {
        if (!closed && mutableState.value != BannerAdState.BlockedByRequestGate) banner.resume()
    }

    override fun onPause(owner: LifecycleOwner) {
        if (!closed && mutableState.value != BannerAdState.BlockedByRequestGate) banner.pause()
    }

    override fun onDestroy(owner: LifecycleOwner) = destroy()

    private fun loadAdaptive(activity: Activity) {
        if (closed || mutableState.value == BannerAdState.BlockedByRequestGate) return
        val widthPixels = (container.width - container.paddingLeft - container.paddingRight)
            .takeIf { it > 0 }
            ?: (activity.resources.displayMetrics.widthPixels - container.paddingLeft - container.paddingRight)
        val widthDp = (widthPixels / activity.resources.displayMetrics.density).toInt()
        if (widthDp <= 0) {
            mutableState.value = BannerAdState.Failed(
                AdLoadFailure(-1, "android-ads-kit", "Banner host has no usable width")
            )
            return
        }
        val size = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp)
        loadWithSize(size)
    }

    private fun loadWithSize(size: AdSize) {
        if (closed || mutableState.value == BannerAdState.BlockedByRequestGate) return
        selectedSize = size
        startAttempt(0)
    }

    private fun startAttempt(index: Int) {
        if (closed || !requestGate.canRequestAds()) {
            mutableState.value = BannerAdState.BlockedByRequestGate
            return
        }
        val size = selectedSize ?: return
        timeout?.let(handler::removeCallbacks)
        val generation = ++requestGeneration
        destroyBannerView()
        banner = AdView(activity)
        banner.adUnitId = adUnitIds[index]
        banner.setAdSize(size)
        banner.adListener = object : AdListener() {
            override fun onAdLoaded() {
                if (!closed && generation == requestGeneration) {
                    timeout?.let(handler::removeCallbacks)
                    timeout = null
                    mutableState.value = BannerAdState.Loaded
                }
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                if (!closed && generation == requestGeneration) failAttempt(index, error.toAdFailure())
            }
        }
        banner.setOnPaidEventListener { value ->
            paidEventListener?.onPaidEvent(
                AdRevenueEvent(AdFormat.BANNER, value.valueMicros, value.currencyCode,
                    value.precisionType.toRevenuePrecision())
            )
        }
        container.addView(banner, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val builder = AdRequest.Builder()
        options.collapsibleEdge?.let { edge ->
            val extras = Bundle().apply {
                putString("collapsible", if (edge == BannerCollapseEdge.BOTTOM) "bottom" else "top")
                putString("collapsible_request_id", options.collapsibleRequestId)
            }
            builder.addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
        }
        banner.loadAd(builder.build())
        val deadline = Runnable {
            if (!closed && generation == requestGeneration) {
                failAttempt(index, AdLoadFailure(-2, "android-ads-kit", "Banner request timed out"))
            }
        }
        timeout = deadline
        handler.postDelayed(deadline, options.loadTimeoutMillis)
    }

    private fun failAttempt(index: Int, failure: AdLoadFailure) {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        if (index + 1 < adUnitIds.size) startAttempt(index + 1)
        else mutableState.value = BannerAdState.Failed(failure)
    }

    private fun destroyBannerView() {
        banner.adListener = object : AdListener() {}
        banner.setOnPaidEventListener(null)
        banner.pause()
        banner.destroy()
        container.removeView(banner)
    }
}

private fun LoadAdError.toAdFailure() = AdLoadFailure(code, domain, message)

private fun Int.toRevenuePrecision(): AdRevenuePrecision = when (this) {
    AdValue.PrecisionType.UNKNOWN -> AdRevenuePrecision.UNKNOWN
    AdValue.PrecisionType.ESTIMATED -> AdRevenuePrecision.ESTIMATED
    AdValue.PrecisionType.PUBLISHER_PROVIDED -> AdRevenuePrecision.PUBLISHER_PROVIDED
    AdValue.PrecisionType.PRECISE -> AdRevenuePrecision.PRECISE
    else -> AdRevenuePrecision.UNKNOWN
}
