package com.tukonnichiwa.adskit.appopen

/** Cache lifetime and reload settings for one app-open ad placement. */
public data class AppOpenAdConfig(
    val adUnitId: String,
    val highFloorAdUnitId: String? = null,
    val fallbackAdUnitId: String? = null,
    val maximumCacheAgeMillis: Long = 4 * 60 * 60 * 1000L,
    val loadTimeoutMillis: Long = 30_000L,
    val minimumIntervalMillis: Long = 0L,
    val minimumBackgroundDurationMillis: Long = 0L,
    val allowsColdStart: Boolean = true,
    val immersiveMode: Boolean = true,
    val reloadAfterDismissOrFailure: Boolean = true
) {
    init {
        require(adUnitId.isNotBlank()) { "adUnitId must not be blank" }
        require(highFloorAdUnitId == null || highFloorAdUnitId.isNotBlank()) { "highFloorAdUnitId must be null or non-blank" }
        require(fallbackAdUnitId == null || fallbackAdUnitId.isNotBlank()) { "fallbackAdUnitId must be null or non-blank" }
        require(maximumCacheAgeMillis > 0L) { "maximumCacheAgeMillis must be greater than zero" }
        require(loadTimeoutMillis in 1L..300_000L) { "loadTimeoutMillis must be between 1 and 300000" }
        require(minimumIntervalMillis >= 0L) { "minimumIntervalMillis must not be negative" }
        require(minimumBackgroundDurationMillis >= 0L) { "minimumBackgroundDurationMillis must not be negative" }
    }
}
