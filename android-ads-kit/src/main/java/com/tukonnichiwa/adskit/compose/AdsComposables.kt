package com.tukonnichiwa.adskit.compose

import android.app.Activity
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.ads.nativead.NativeAd
import com.tukonnichiwa.adskit.AdsKit
import com.tukonnichiwa.adskit.banner.BannerAdOptions
import com.tukonnichiwa.adskit.banner.BannerAdSession
import com.tukonnichiwa.adskit.banner.BannerAdState
import com.tukonnichiwa.adskit.view.NativeAdView
import kotlinx.coroutines.flow.collect

/** Hosts a library-managed banner in Compose and closes its session when the composable leaves. */
@Composable
public fun AndroidAdsBanner(
    adsKit: AdsKit,
    activity: Activity,
    adUnitId: String,
    modifier: Modifier = Modifier,
    options: BannerAdOptions? = null,
    onState: (BannerAdState) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnState = rememberUpdatedState(onState)
    val effectiveOptions = remember(options) { options ?: BannerAdOptions() }

    key(adsKit, activity, lifecycleOwner, adUnitId, effectiveOptions) {
        val container = remember(context) { FrameLayout(context) }
        var session by remember { mutableStateOf<BannerAdSession?>(null) }

        DisposableEffect(adsKit, activity, lifecycleOwner, adUnitId, effectiveOptions, container) {
            val createdSession = adsKit.banners.load(
                activity = activity,
                container = container,
                adUnitId = adUnitId,
                options = effectiveOptions,
                lifecycleOwner = lifecycleOwner
            )
            session = createdSession
            onDispose {
                createdSession.close()
                if (session === createdSession) session = null
            }
        }

        LaunchedEffect(session) {
            session?.state?.collect { state -> latestOnState.value(state) }
        }

        AndroidView(
            factory = { container },
            modifier = modifier
        )
    }
}

/** Binds a loaded native ad to a host-provided layout for the lifetime of this Compose node. */
@Composable
public fun AndroidNativeAd(
    ad: NativeAd,
    @LayoutRes layoutRes: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val adView = remember(context, layoutRes) {
        NativeAdView(context).apply {
            applyLayout(layoutRes)
            bindLifecycle(lifecycleOwner)
        }
    }

    DisposableEffect(adView, lifecycleOwner) {
        adView.bindLifecycle(lifecycleOwner)
        onDispose { adView.bindLifecycle(null) }
    }
    DisposableEffect(adView, ad) {
        adView.bindNativeAd(ad)
        onDispose(adView::destroy)
    }

    AndroidView(
        factory = { adView },
        modifier = modifier
    )
}
