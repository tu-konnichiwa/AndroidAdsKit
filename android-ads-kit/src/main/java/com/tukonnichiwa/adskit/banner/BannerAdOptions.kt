package com.tukonnichiwa.adskit.banner

import com.google.android.gms.ads.AdSize
import java.util.UUID

/** Banner sizes offered by [BannerAdManager]. */
public sealed interface BannerAdSize {
    /** Current-orientation anchored adaptive banner using the measured host width. */
    public data object AnchoredAdaptive : BannerAdSize

    /** Standard 300-by-250 medium rectangle. */
    public data object MediumRectangle : BannerAdSize

    /** An SDK-defined fixed size. */
    public data class Fixed(val adSize: AdSize) : BannerAdSize
}

/** Direction where a collapsible banner initially expands. */
public enum class BannerCollapseEdge {
    TOP,
    BOTTOM
}

/** Request and sizing options for a banner session. */
public data class BannerAdOptions(
    val size: BannerAdSize = BannerAdSize.AnchoredAdaptive,
    val collapsibleEdge: BannerCollapseEdge? = null,
    val collapsibleRequestId: String = UUID.randomUUID().toString(),
    val highFloorAdUnitId: String? = null,
    val fallbackAdUnitId: String? = null,
    val loadTimeoutMillis: Long = 30_000L
) {
    init {
        require(collapsibleEdge == null || collapsibleRequestId.isNotBlank()) {
            "collapsibleRequestId must not be blank when collapsibleEdge is set"
        }
        require(highFloorAdUnitId == null || highFloorAdUnitId.isNotBlank()) { "highFloorAdUnitId must be null or non-blank" }
        require(fallbackAdUnitId == null || fallbackAdUnitId.isNotBlank()) { "fallbackAdUnitId must be null or non-blank" }
        require(loadTimeoutMillis in 1L..300_000L) { "loadTimeoutMillis must be between 1 and 300000" }
    }
}

/** Observable banner-session state. */
public sealed interface BannerAdState {
    public data object Loading : BannerAdState
    public data object Loaded : BannerAdState
    public data object BlockedByRequestGate : BannerAdState
    public data class Failed(val failure: com.tukonnichiwa.adskit.AdLoadFailure) : BannerAdState
    public data object Destroyed : BannerAdState
}
