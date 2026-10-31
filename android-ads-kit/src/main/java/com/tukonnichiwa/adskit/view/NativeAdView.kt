package com.tukonnichiwa.adskit.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.nativead.NativeAd
import com.tukonnichiwa.adskit.R

/**
 * Hosts a caller-provided layout and displays a loaded native ad in its standard asset views.
 * A successfully bound [NativeAd] is owned by this view and destroyed when released.
 */
public class NativeAdView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), DefaultLifecycleObserver {
    private var layoutResource: Int = 0
    private var autoShowShimmer = true
    private var contentView: View? = null
    private var shimmerView: ShimmerFrameLayout? = null
    private var binding: NativeAdBinding? = null
    private var nativeAd: NativeAd? = null
    private var lifecycle: Lifecycle? = null

    init {
        val attributes = context.obtainStyledAttributes(attrs, R.styleable.NativeAdView, defStyleAttr, 0)
        try {
            layoutResource = attributes.getResourceId(R.styleable.NativeAdView_adLayout, 0)
            autoShowShimmer = attributes.getBoolean(R.styleable.NativeAdView_autoShowShimmer, true)
        } finally {
            attributes.recycle()
        }
        if (layoutResource != 0) applyLayout(layoutResource)
    }

    /** Replaces the ad layout. Any ad currently bound to this view is destroyed. */
    public fun applyLayout(@LayoutRes layoutRes: Int) {
        require(layoutRes != 0) { "layoutRes must reference a layout resource" }
        releaseAd(showPlaceholder = false)
        shimmerView?.stopShimmer()
        removeAllViews()
        contentView = null
        shimmerView = null
        binding = null
        layoutResource = layoutRes

        val content = LayoutInflater.from(context).inflate(layoutRes, this, false)
        contentView = content
        binding = NativeAdBinding(content).also(NativeAdBinding::requireNativeAdView)
        addView(content)
        if (autoShowShimmer) showShimmer(true)
    }

    /** Shows or hides the loading placeholder while preserving the ad layout's measured size. */
    public fun showShimmer(show: Boolean) {
        if (show) {
            val overlay = shimmerView ?: createShimmer().also { shimmerView = it; addView(it) }
            contentView?.visibility = INVISIBLE
            overlay.visibility = VISIBLE
            overlay.startShimmer()
            overlay.bringToFront()
        } else {
            shimmerView?.apply {
                stopShimmer()
                visibility = GONE
            }
            contentView?.visibility = VISIBLE
        }
    }

    /** Binds [ad] and transfers ownership of it to this view. */
    public fun bindNativeAd(ad: NativeAd) {
        check(layoutResource != 0 && binding != null) {
            "Call applyLayout() or set app:adLayout before binding an ad"
        }
        if (nativeAd !== ad) nativeAd?.destroy()
        binding?.bind(ad)
        nativeAd = ad
        showShimmer(false)
    }

    /** Observes a lifecycle owner and releases this view's ad when that owner is destroyed. */
    public fun bindLifecycle(owner: LifecycleOwner?) {
        lifecycle?.removeObserver(this)
        lifecycle = owner?.lifecycle
        lifecycle?.addObserver(this)
    }

    /** Releases the current ad and restores the loading placeholder when it is enabled. */
    public fun destroy() {
        releaseAd(showPlaceholder = autoShowShimmer)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (autoShowShimmer && nativeAd == null) showShimmer(true)
    }

    override fun onDetachedFromWindow() {
        releaseAd(showPlaceholder = false)
        shimmerView?.stopShimmer()
        lifecycle?.removeObserver(this)
        lifecycle = null
        super.onDetachedFromWindow()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        destroy()
    }

    private fun releaseAd(showPlaceholder: Boolean) {
        nativeAd?.destroy()
        nativeAd = null
        binding?.clear()
        if (showPlaceholder && layoutResource != 0) showShimmer(true) else showShimmer(false)
    }

    private fun createShimmer(): ShimmerFrameLayout =
        LayoutInflater.from(context)
            .inflate(R.layout.adkit_native_ad_shimmer, this, false) as ShimmerFrameLayout
}
