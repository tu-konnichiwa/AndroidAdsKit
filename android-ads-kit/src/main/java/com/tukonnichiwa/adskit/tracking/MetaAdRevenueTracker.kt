package com.tukonnichiwa.adskit.tracking

import android.app.Application
import android.os.Bundle
import com.facebook.FacebookSdk
import com.facebook.appevents.AppEventsConstants
import com.facebook.appevents.AppEventsLogger
import com.tukonnichiwa.adskit.AdFormat
import com.tukonnichiwa.adskit.AdRevenueEvent
import com.tukonnichiwa.adskit.PaidEventListener

/** Optional Meta App Events adapter for paid ad impressions. Initialize the Facebook SDK in the app. */
public class MetaAdRevenueTracker(application: Application) : PaidEventListener {
    @Volatile
    private var logger: AppEventsLogger? = null

    private val app: Application = application

    override fun onPaidEvent(event: AdRevenueEvent) {
        val revenue = event.valueMicros / 1_000_000.0
        if (!revenue.isFinite() || revenue <= 0.0 || event.currencyCode.isBlank()) return
        if (!FacebookSdk.isInitialized()) return

        val eventLogger = logger ?: synchronized(this) {
            logger ?: AppEventsLogger.newLogger(app).also { logger = it }
        }
        val params = Bundle().apply {
            putString(AppEventsConstants.EVENT_PARAM_CURRENCY, event.currencyCode)
            putString(AppEventsConstants.EVENT_PARAM_AD_TYPE, event.format.toMetaAdType())
        }
        eventLogger.logEvent(
            AppEventsConstants.EVENT_NAME_AD_IMPRESSION,
            revenue,
            params
        )
    }

    private fun AdFormat.toMetaAdType(): String = when (this) {
        AdFormat.BANNER -> "banner"
        AdFormat.NATIVE -> "native"
        AdFormat.INTERSTITIAL -> "interstitial"
        AdFormat.REWARDED -> "rewarded_video"
        AdFormat.APP_OPEN -> "unknown"
    }
}
