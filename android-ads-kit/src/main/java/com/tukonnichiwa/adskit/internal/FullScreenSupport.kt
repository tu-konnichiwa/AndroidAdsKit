package com.tukonnichiwa.adskit.internal

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.ads.AdValue
import com.tukonnichiwa.adskit.AdFormat
import com.tukonnichiwa.adskit.AdRevenueEvent
import com.tukonnichiwa.adskit.AdRevenuePrecision
import com.tukonnichiwa.adskit.PaidEventListener

internal fun Activity.isResumedForAd(): Boolean =
    !isFinishing && !isDestroyed && ((this as? LifecycleOwner)?.lifecycle?.currentState
        ?.isAtLeast(Lifecycle.State.RESUMED) ?: true)

internal fun PaidEventListener?.notifyPaid(format: AdFormat, value: AdValue) {
    this?.onPaidEvent(
        AdRevenueEvent(format, value.valueMicros, value.currencyCode, when (value.precisionType) {
            AdValue.PrecisionType.UNKNOWN -> AdRevenuePrecision.UNKNOWN
            AdValue.PrecisionType.ESTIMATED -> AdRevenuePrecision.ESTIMATED
            AdValue.PrecisionType.PUBLISHER_PROVIDED -> AdRevenuePrecision.PUBLISHER_PROVIDED
            AdValue.PrecisionType.PRECISE -> AdRevenuePrecision.PRECISE
            else -> AdRevenuePrecision.UNKNOWN
        })
    )
}
