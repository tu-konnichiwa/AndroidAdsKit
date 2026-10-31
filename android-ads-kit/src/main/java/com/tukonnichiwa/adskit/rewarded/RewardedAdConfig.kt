package com.tukonnichiwa.adskit.rewarded

import com.tukonnichiwa.adskit.fullscreen.AdLoadingDialogStyle

/** Load and show settings for one rewarded-ad placement. */
public data class RewardedAdConfig(
    val adUnitId: String,
    val fallbackAdUnitId: String? = null,
    val highFloorAdUnitId: String? = null,
    val cacheLifetimeMillis: Long = 60 * 60 * 1000L,
    val loadTimeoutMillis: Long = 30_000L,
    val minimumIntervalMillis: Long = 0L,
    val preShowDelayMillis: Long = 0L,
    val loadingDialogStyle: AdLoadingDialogStyle? = null,
    val immersiveMode: Boolean = true,
    val reloadAfterDismissOrFailure: Boolean = true
) {
    init {
        require(adUnitId.isNotBlank()) { "adUnitId must not be blank" }
        require(fallbackAdUnitId == null || fallbackAdUnitId.isNotBlank()) {
            "fallbackAdUnitId must be null or non-blank"
        }
        require(highFloorAdUnitId == null || highFloorAdUnitId.isNotBlank()) { "highFloorAdUnitId must be null or non-blank" }
        require(cacheLifetimeMillis > 0L) { "cacheLifetimeMillis must be greater than zero" }
        require(loadTimeoutMillis in 1L..300_000L) { "loadTimeoutMillis must be between 1 and 300000" }
        require(minimumIntervalMillis >= 0L) { "minimumIntervalMillis must not be negative" }
        require(preShowDelayMillis >= 0L) { "preShowDelayMillis must not be negative" }
        require(loadTimeoutMillis > 0L) { "loadTimeoutMillis must be greater than zero" }
    }
}
