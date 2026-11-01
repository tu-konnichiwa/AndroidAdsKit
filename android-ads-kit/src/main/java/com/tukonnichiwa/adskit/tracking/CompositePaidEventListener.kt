package com.tukonnichiwa.adskit.tracking

import com.tukonnichiwa.adskit.AdRevenueEvent
import com.tukonnichiwa.adskit.PaidEventListener

/** Forwards paid impression events to several app-selected tracking destinations. */
public class CompositePaidEventListener(vararg listeners: PaidEventListener) : PaidEventListener {
    private val listeners = listeners

    override fun onPaidEvent(event: AdRevenueEvent) {
        listeners.forEach { listener -> listener.onPaidEvent(event) }
    }
}
