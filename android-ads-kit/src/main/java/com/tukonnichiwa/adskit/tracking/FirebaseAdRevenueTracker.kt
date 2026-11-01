package com.tukonnichiwa.adskit.tracking

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.tukonnichiwa.adskit.AdRevenueEvent
import com.tukonnichiwa.adskit.PaidEventListener

/** Optional Firebase Analytics adapter for Google Mobile Ads paid impression events. */
public class FirebaseAdRevenueTracker(context: Context) : PaidEventListener {
    private val analytics = FirebaseAnalytics.getInstance(context.applicationContext)

    override fun onPaidEvent(event: AdRevenueEvent) {
        if (event.valueMicros <= 0L || event.currencyCode.isBlank()) return

        val params = Bundle().apply {
            putDouble("value", event.valueMicros / 1_000_000.0)
            putString("currency", event.currencyCode)
            putString("ad_format", event.format.name.lowercase())
            putString("precision_type", event.precision.name.lowercase())
        }
        analytics.logEvent("ad_impression", params)
    }
}
