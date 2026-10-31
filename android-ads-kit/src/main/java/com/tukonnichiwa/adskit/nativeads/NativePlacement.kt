package com.tukonnichiwa.adskit.nativeads

import com.google.android.gms.ads.nativead.NativeAdOptions

/**
 * A named native-ad slot.
 *
 * @property key stable key used to observe and cache this placement
 * @property adUnitId primary ad unit requested first
 * @property fallbackAdUnitId optional unit requested when the primary request fails
 * @property preloadCount maximum number of sequential requests to make per preload operation
 * @property mediaAspectRatio desired media aspect ratio from [NativeAdOptions]
 * @property startVideoMuted whether video assets should start muted
 */
public data class NativePlacement(
    val key: String,
    val adUnitId: String,
    val fallbackAdUnitId: String? = null,
    val highFloorAdUnitId: String? = null,
    val cacheLifetimeMillis: Long = 60 * 60 * 1000L,
    val loadTimeoutMillis: Long = 30_000L,
    val preloadCount: Int = 1,
    val mediaAspectRatio: Int = NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_LANDSCAPE,
    val startVideoMuted: Boolean = true
) {
    init {
        require(key.isNotBlank()) { "key must not be blank" }
        require(adUnitId.isNotBlank()) { "adUnitId must not be blank" }
        require(fallbackAdUnitId == null || fallbackAdUnitId.isNotBlank()) {
            "fallbackAdUnitId must be null or non-blank"
        }
        require(highFloorAdUnitId == null || highFloorAdUnitId.isNotBlank()) { "highFloorAdUnitId must be null or non-blank" }
        require(cacheLifetimeMillis > 0L) { "cacheLifetimeMillis must be greater than zero" }
        require(loadTimeoutMillis in 1L..300_000L) { "loadTimeoutMillis must be between 1 and 300000" }
        require(preloadCount in 1..10) { "preloadCount must be between 1 and 10" }
        require(mediaAspectRatio in NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_UNKNOWN..
            NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_SQUARE
        ) { "Unsupported media aspect ratio" }
    }
}

/** State of the cached ads for one [NativePlacement]. */
public sealed interface NativePlacementState {
    public data object Idle : NativePlacementState
    public data object Loading : NativePlacementState
    public data object BlockedByRequestGate : NativePlacementState
    public data class Ready(val cachedAds: Int) : NativePlacementState
    public data class Failed(val error: NativeAdLoadFailure) : NativePlacementState
}

/** Error details returned when a native ad request fails. */
public data class NativeAdLoadFailure(
    /** Error code reported by the SDK, or `-1` when request setup itself failed. */
    val code: Int,
    /** Error domain reported by the SDK, or `android-ads-kit` for request setup errors. */
    val domain: String,
    /** Human-readable diagnostic message. Do not use this value as a programmatic error key. */
    val message: String
)

/** Result of loading one native ad. The caller owns and must eventually destroy a loaded ad. */
public sealed interface NativeAdLoadResult {
    /** Loaded ad; ownership transfers to the caller. */
    public data class Loaded(val ad: com.google.android.gms.ads.nativead.NativeAd) : NativeAdLoadResult
    /** Request failed; no ad ownership is transferred. */
    public data class Failed(val error: NativeAdLoadFailure) : NativeAdLoadResult
    /** The configured request gate currently disallows ad requests. */
    public data object BlockedByRequestGate : NativeAdLoadResult
}
