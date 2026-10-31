package com.tukonnichiwa.adskit.fullscreen

import android.graphics.Color

/** Optional styling for the lightweight loading dialog shown before a full-screen ad. */
public data class AdLoadingDialogStyle(
    val backgroundColor: Int = Color.WHITE,
    val indicatorColor: Int = Color.DKGRAY,
    val message: String? = null,
    val messageColor: Int = Color.DKGRAY,
    val messageSizeSp: Float = 16f
) {
    init {
        require(messageSizeSp > 0f) { "messageSizeSp must be greater than zero" }
    }
}
