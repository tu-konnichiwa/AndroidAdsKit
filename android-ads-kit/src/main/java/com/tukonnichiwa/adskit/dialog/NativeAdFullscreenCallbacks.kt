package com.tukonnichiwa.adskit.dialog

/** Receives presentation events from [NativeAdFullscreenDialog]. */
public interface NativeAdFullscreenCallbacks {
    /** Called after the dialog has been shown. */
    public fun onAdShown()

    /** Called when the dialog is dismissed by the close button, Back, or the host. */
    public fun onAdDismissed()
}
