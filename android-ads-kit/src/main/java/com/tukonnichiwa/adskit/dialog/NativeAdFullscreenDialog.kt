package com.tukonnichiwa.adskit.dialog

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.annotation.LayoutRes
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.ads.nativead.NativeAd
import com.tukonnichiwa.adskit.R
import com.tukonnichiwa.adskit.view.NativeAdView

/**
 * One-shot full-screen native-ad dialog. The dialog owns [ad] after [show] succeeds.
 * Create a new dialog instance for each presentation.
 */
public class NativeAdFullscreenDialog(
    activity: Activity,
    @LayoutRes nativeAdLayout: Int,
    lifecycleOwner: LifecycleOwner? = null,
    private val callbacks: NativeAdFullscreenCallbacks? = null
) {
    private val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closeButton: ImageButton
    private val adView: NativeAdView
    private var hasShown = false

    init {
        require(nativeAdLayout != 0) { "nativeAdLayout must reference a layout resource" }
        val content = android.view.LayoutInflater.from(activity)
            .inflate(R.layout.adkit_fullscreen_native_ad_dialog, FrameLayout(activity), false)
        val adContainer = content.findViewById<FrameLayout>(R.id.adkit_fullscreen_ad_container)
        adView = NativeAdView(activity).apply {
            applyLayout(nativeAdLayout)
            bindLifecycle(lifecycleOwner)
        }
        adContainer.addView(
            adView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        closeButton = content.findViewById(R.id.adkit_fullscreen_close)
        closeButton.setOnClickListener { dismiss() }
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            mainHandler.removeCallbacks(showCloseButton)
            adView.destroy()
            callbacks?.onAdDismissed()
        }
    }

    /**
     * Shows the dialog and transfers ownership of [ad] to it.
     *
     * @param ad loaded ad to display
     * @param closeButtonDelayMillis how long to hide the close button; must be non-negative
     */
    public fun show(ad: NativeAd, closeButtonDelayMillis: Long = 0L) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "show() must be called on the main thread" }
        check(!hasShown) { "This dialog instance can only be shown once" }
        require(closeButtonDelayMillis >= 0L) { "closeButtonDelayMillis must be non-negative" }
        adView.bindNativeAd(ad)
        hasShown = true
        closeButton.visibility = if (closeButtonDelayMillis == 0L) View.VISIBLE else View.GONE
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (closeButtonDelayMillis > 0L) mainHandler.postDelayed(showCloseButton, closeButtonDelayMillis)
        callbacks?.onAdShown()
    }

    /** Dismisses the dialog if it is currently showing. */
    public fun dismiss() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "dismiss() must be called on the main thread" }
        if (dialog.isShowing) dialog.dismiss()
    }

    /** Returns whether the full-screen dialog is currently visible. */
    public val isShowing: Boolean
        get() = dialog.isShowing

    private val showCloseButton = Runnable {
        if (dialog.isShowing) closeButton.visibility = View.VISIBLE
    }
}
