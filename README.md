# AndroidAdsKit

AndroidAdsKit is a Kotlin Android library for Google Mobile Ads. It provides adaptive, fixed-size,
medium-rectangle, and collapsible banners; cached native ads; interstitial, rewarded, and app-open
ads; and an optional Google User Messaging Platform (UMP) consent manager.

## Requirements

- Android SDK 26 or newer.
- The consuming application must configure its Google Mobile Ads app ID and provide its own ad unit
  IDs.
- The consuming application decides when ads are appropriate, handles premium/subscription policy,
  and configures any mediation networks it uses.

## Add the library

For a local multi-module build, include the module in `settings.gradle.kts` and add it to the app:

```kotlin
// settings.gradle.kts
include(":android-ads-kit")
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":android-ads-kit"))
}
```

The library is not published to Maven Central by this project yet.

## Configure the consuming app

Add your AdMob app ID to the **application** manifest of the consuming app. The library module does
not contain an app-specific ID:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <meta-data
            android:name="com.google.android.gms.ads.APPLICATION_ID"
            android:value="@string/admob_app_id" />
    </application>
</manifest>
```

Keep the value in the app's resources, for example `app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="admob_app_id" translatable="false">ca-app-pub-0000000000000000~0000000000</string>
</resources>
```

Replace the placeholder with the app ID from AdMob. App IDs contain `~`; ad unit IDs contain `/`.
Use Google's test ad units while developing (see [Google's Android test ad guide](https://developers.google.com/admob/android/test-ads)):

| Format | Google test ad unit ID |
| --- | --- |
| Anchored adaptive banner | `ca-app-pub-3940256099942544/9214589741` |
| Fixed-size banner | `ca-app-pub-3940256099942544/6300978111` |
| Interstitial | `ca-app-pub-3940256099942544/1033173712` |
| Rewarded | `ca-app-pub-3940256099942544/5224354917` |
| App open | `ca-app-pub-3940256099942544/9257395921` |
| Native | `ca-app-pub-3940256099942544/2247696110` |

Replace test IDs with your own ad unit IDs before release. Google test IDs are not a substitute for
testing mediated networks; configure test mode for each mediation network as well.

## Initialize and manage consent

Create one `AdsKit` for the application process and reuse it. By default, its request gate uses UMP.
Refresh consent before loading ads; initialization applies the process-wide Google Mobile Ads request
configuration and does not itself make an ad request:

```kotlin
class MainActivity : AppCompatActivity() {
    private val adsKit by lazy { (application as MyApplication).adsKit }

    private fun refreshConsentAndInitialize() {
        lifecycleScope.launch {
            val result = adsKit.consent.gatherConsent(this@MainActivity)
            result.failure?.let { error ->
                // Log or report the consent error as appropriate for the app.
            }

            adsKit.initialize(
                AdsRequestConfiguration(
                    testDeviceIds = listOf("YOUR_TEST_DEVICE_HASHED_ID"),
                    maxAdContentRating = "PG"
                )
            )
            adsKit.setAdsEnabled(result.canRequestAds)
        }
    }
}
```

For production, remove the example test device ID or replace it with the device IDs used for QA.
`AdsRequestConfiguration` is applied once per process. Every later call to `AdsKit.initialize()` or
`AdsSdk.initialize()` must pass the same configuration; a different configuration throws
`IllegalArgumentException`. The `childDirectedTreatment` and `underAgeOfConsent` flags default to
unspecified (`null`). Set them only according to the app's applicable requirements.

If consent is managed outside UMP, provide an `AdsRequestGate`:

```kotlin
val adsKit = AdsKit(
    context = applicationContext,
    requestGate = AdsRequestGate { appConsentAllowsAds }
)
```

When the user's consent or subscription eligibility changes, update the app's gate state and call
`adsKit.setAdsEnabled(allowed)`. Disabling ads cancels pending loads and clears library-managed
caches and banner sessions. Any native ad already bound to a host view remains owned by that view;
remove or destroy it in the host too.

If UMP says a privacy-options entry point is required, expose one in the app's privacy settings and
call it from an active `Activity`:

```kotlin
lifecycleScope.launch {
    val result = adsKit.consent.showPrivacyOptions(this@PrivacyActivity)
    result.failure?.let { error -> /* Log or report the error. */ }
    adsKit.setAdsEnabled(result.canRequestAds)
}
```

For consent QA, `gatherConsent(activity, debugOptions)` supports `ConsentDebugOptions` with
`ConsentDebugGeography.EEA` or `NOT_EEA` and test-device hashed IDs. Do not ship debug geography
overrides.

## Banners

Add a `FrameLayout` to the consuming app's layout, then load a banner into it. The manager inserts
and owns the Google `AdView`; the returned session exposes a `StateFlow<BannerAdState>` and should be
closed with its host:

```kotlin
val bannerSession = adsKit.banners.load(
    activity = this,
    container = binding.bannerContainer,
    adUnitId = "ca-app-pub-3940256099942544/9214589741",
    options = BannerAdOptions(
        size = BannerAdSize.AnchoredAdaptive,
        loadTimeoutMillis = 30_000L
    ),
    lifecycleOwner = this
)

lifecycleScope.launch {
    bannerSession.state.collect { state ->
        // Handle Loading, Loaded, BlockedByRequestGate, Failed, or Destroyed.
    }
}
```

Available sizes are `BannerAdSize.AnchoredAdaptive` (default), `MediumRectangle`, and
`Fixed(AdSize)`. For a collapsible banner, also set `collapsibleEdge` and a unique
`collapsibleRequestId` in `BannerAdOptions`. `highFloorAdUnitId` is attempted before `adUnitId`;
`fallbackAdUnitId` is used after `adUnitId` only when no high-floor ID is configured. Each ad-unit
attempt has its own bounded `loadTimeoutMillis` (maximum five minutes). Pass a `LifecycleOwner` to
pause, resume, and destroy the banner with that lifecycle; otherwise close the session yourself.

## Native ads

Create a native layout whose root is Google's `NativeAdView`, or which contains one with
`@id/ad_container`. Add the asset IDs used by the layout: `ad_headline`, `ad_media`, `ad_body`,
`ad_call_to_action`, `ad_app_icon`, `ad_advertiser`, and `ad_stars`. Assets are optional. Media uses
Google's `MediaView`; rating uses a `RatingBar`.

```kotlin
val placement = NativePlacement(
    key = "home_feed",
    adUnitId = "ca-app-pub-3940256099942544/2247696110",
    cacheLifetimeMillis = 60 * 60 * 1000L,
    loadTimeoutMillis = 30_000L,
    preloadCount = 2
)
adsKit.nativeAds.preload(placement)

lifecycleScope.launch {
    val ad = adsKit.nativeAds.take(placement) ?: return@launch
    binding.nativeAdHost.bindLifecycle(this@HomeActivity)
    binding.nativeAdHost.bindNativeAd(ad)
}
```

`highFloorAdUnitId` is tried before `adUnitId`. If no high-floor ID is set, the optional
`fallbackAdUnitId` is tried after `adUnitId`. `NativeAdManager.load(adUnitId, placement)` loads one ad
directly; a successful result transfers ownership to the caller. `NativeAdView.bindNativeAd` moves
that ownership to the view, which releases the ad on detach, lifecycle destruction, replacement, or
explicit `destroy()`.

## Full-screen ads

Create one manager per placement and preload before the point where the app may show an ad.
`show()` returns an immediate `FullScreenShowResult`; it does not wait for dismissal. Observe
`FullScreenAdEventListener` for shown, dismissed, clicked, impression, failed, and (for rewarded ads)
reward events.

```kotlin
val interstitial = adsKit.interstitial(
    InterstitialAdConfig(
        adUnitId = "ca-app-pub-3940256099942544/1033173712",
        minimumIntervalMillis = 10_000L
    )
)
interstitial.preload()
val result = interstitial.show(this, listener = FullScreenAdEventListener { event ->
    if (event is FullScreenAdEvent.Failed) logAdFailure(event.failure)
})
```

Use the immediate result to handle cases such as `Started`, `AdNotReady`, `RequestBlocked`,
`ActivityNotResumed`, `AnotherAdIsActive`, `SkippedByPolicy`, `CooldownActive`,
`BackgroundTooShort`, and `ColdStartDisabled`. `excluded` skips by host policy; `bypassInterval`
bypasses only the configured cooldown. Cooldowns are tracked independently per format.

For a user-initiated rewarded flow, `loadAndShow` waits for the ad load and returns the same immediate
result contract:

```kotlin
val rewarded = adsKit.rewarded(
    RewardedAdConfig(adUnitId = "ca-app-pub-3940256099942544/5224354917")
)
val result = rewarded.loadAndShow(this) { event ->
    if (event is FullScreenAdEvent.RewardEarned) {
        grantReward(event.type, event.amount)
    }
}
```

For interstitial, rewarded, and app-open configs, `highFloorAdUnitId` is tried first and `adUnitId`
second. If no high-floor ID is set, `fallbackAdUnitId` is tried after `adUnitId`. Each attempt uses
the configured bounded load timeout. The library serializes full-screen presentations created by
one `AdsKit` instance.

For app-open ads, the host app decides when to show them and must report background transitions when
using minimum-background or cold-start rules:

```kotlin
val appOpen = adsKit.appOpen(
    AppOpenAdConfig(
        adUnitId = "ca-app-pub-3940256099942544/9257395921",
        minimumBackgroundDurationMillis = 30_000L,
        allowsColdStart = false
    )
)
appOpen.preload()

// From the app's process/lifecycle observer:
adsKit.recordBackground()

// After returning to foreground, show only from a resumed Activity:
appOpen.show(activity)
```

`NativeAdFullscreenDialog` is also available if the app chooses to present a native ad full-screen.

## Events, lifecycle, and ownership

- Close each banner session with its host. Close placement managers when their feature scope ends.
  `AdsKit.close()` closes managers created through the facade, plus its native and banner managers.
- Pass a `PaidEventListener` to `AdsKit` to receive paid-impression values for supported formats.
  `valueMicros` is in millionths of `currencyCode`; forward it to the app's analytics or revenue
  destination.
- The host owns app-open triggering, premium gating, Remote Config, app-specific resources, and
  native views that it has already attached.
- Cache lifetime and load timeout values are milliseconds. Load timeouts are limited to five
  minutes per ad-unit attempt.
- Configure the Google Mobile Ads app ID and mediation networks in the consuming app. This project
  does not configure application-specific resources or Maven Central publishing.

## Build

From the project root, build the library with:

```shell
./gradlew :android-ads-kit:build
```

Run the library's unit tests with:

```shell
./gradlew :android-ads-kit:test
```
