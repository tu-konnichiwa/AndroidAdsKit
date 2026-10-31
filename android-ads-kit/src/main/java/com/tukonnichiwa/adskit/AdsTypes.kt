package com.tukonnichiwa.adskit

/** Ad formats supported by this library. */
public enum class AdFormat {
    BANNER,
    NATIVE,
    INTERSTITIAL,
    REWARDED,
    APP_OPEN
}

/** A policy supplied by the app to decide whether an ad request is currently allowed. */
public fun interface AdsRequestGate {
    public fun canRequestAds(): Boolean

    public companion object {
        /** Use when consent and eligibility are handled outside this library. */
        public val allowAll: AdsRequestGate = AdsRequestGate { true }
    }
}

/** Stable error details for an SDK ad-load failure. */
public data class AdLoadFailure(
    /** SDK code, `-1` for local request setup, or `-2` for a library load timeout. */
    val code: Int,
    /** SDK error domain, or `android-ads-kit` for a local failure. */
    val domain: String,
    /** Human-readable diagnostic message. Do not use this as a programmatic error key. */
    val message: String
)

/** Current load state of a cached, full-screen ad slot. */
public sealed interface AdLoadState {
    public data object Idle : AdLoadState
    public data object Loading : AdLoadState
    public data object Ready : AdLoadState
    public data object BlockedByRequestGate : AdLoadState
    public data class Failed(val failure: AdLoadFailure) : AdLoadState
}

/** Possible immediate outcomes when a full-screen ad show request is made. */
public sealed interface FullScreenShowResult {
    public data object Started : FullScreenShowResult
    public data object AdNotReady : FullScreenShowResult
    public data object RequestBlocked : FullScreenShowResult
    public data object ActivityNotResumed : FullScreenShowResult
    public data object AnotherAdIsActive : FullScreenShowResult
    public data object SkippedByPolicy : FullScreenShowResult
    public data object CooldownActive : FullScreenShowResult
    public data object BackgroundTooShort : FullScreenShowResult
    public data object ColdStartDisabled : FullScreenShowResult
}

/** Events delivered during a full-screen ad presentation. */
public sealed interface FullScreenAdEvent {
    public val format: AdFormat

    public data class Shown(override val format: AdFormat) : FullScreenAdEvent
    public data class Dismissed(override val format: AdFormat) : FullScreenAdEvent
    public data class Failed(override val format: AdFormat, val failure: AdLoadFailure) : FullScreenAdEvent
    public data class Clicked(override val format: AdFormat) : FullScreenAdEvent
    public data class Impression(override val format: AdFormat) : FullScreenAdEvent
    public data class RewardEarned(
        override val format: AdFormat,
        val type: String,
        val amount: Int
    ) : FullScreenAdEvent
}

/** Receives full-screen ad events. Callbacks run on the main thread. */
public fun interface FullScreenAdEventListener {
    public fun onEvent(event: FullScreenAdEvent)
}

/** Precision category for a paid impression value. */
public enum class AdRevenuePrecision {
    UNKNOWN,
    ESTIMATED,
    PUBLISHER_PROVIDED,
    PRECISE
}

/** App-neutral paid-impression data. [valueMicros] is in millionths of [currencyCode]. */
public data class AdRevenueEvent(
    val format: AdFormat,
    val valueMicros: Long,
    val currencyCode: String,
    val precision: AdRevenuePrecision
)

/** Receives paid-impression events for ad revenue measurement. */
public fun interface PaidEventListener {
    public fun onPaidEvent(event: AdRevenueEvent)
}

/** Values applied process-wide before the Mobile Ads SDK is initialized. Null treatment flags stay unspecified. */
public data class AdsRequestConfiguration(
    val testDeviceIds: List<String> = emptyList(),
    val maxAdContentRating: String? = null,
    val childDirectedTreatment: Boolean? = null,
    val underAgeOfConsent: Boolean? = null
) {
    init {
        require(testDeviceIds.none(String::isBlank)) { "testDeviceIds must not contain blank values" }
        require(maxAdContentRating == null || maxAdContentRating in setOf("G", "PG", "T", "MA")) {
            "maxAdContentRating must be one of G, PG, T, or MA"
        }
    }
}
