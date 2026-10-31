package com.tukonnichiwa.adskit.internal

import com.google.android.gms.ads.LoadAdError
import com.tukonnichiwa.adskit.AdLoadFailure
import com.tukonnichiwa.adskit.AdsRequestGate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal suspend fun <T> awaitAdLoad(
    requestGate: AdsRequestGate,
    destroyLateResult: (T) -> Unit = {},
    startRequest: (complete: (AdUnitLoadResult<T>) -> Unit) -> Unit
): AdUnitLoadResult<T> = withContext(Dispatchers.Main.immediate) {
    if (!requestGate.canRequestAds()) return@withContext AdUnitLoadResult.Blocked
    suspendCancellableCoroutine<AdUnitLoadResult<T>> { continuation ->
        val delivered = AtomicBoolean(false)
        continuation.invokeOnCancellation { delivered.set(true) }

        fun deliver(result: AdUnitLoadResult<T>) {
            if (!delivered.compareAndSet(false, true)) {
                if (result is AdUnitLoadResult.Loaded) destroyLateResult(result.ad)
                return
            }
            if (!continuation.isActive) {
                if (result is AdUnitLoadResult.Loaded) destroyLateResult(result.ad)
                return
            }
            if (!requestGate.canRequestAds()) {
                if (result is AdUnitLoadResult.Loaded) destroyLateResult(result.ad)
                continuation.resume(AdUnitLoadResult.Blocked)
                return
            }
            when (result) {
                is AdUnitLoadResult.Loaded -> continuation.resume(result)
                is AdUnitLoadResult.Failed -> continuation.resume(result)
                AdUnitLoadResult.Blocked -> continuation.resume(result)
            }
        }

        try {
            startRequest(::deliver)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            deliver(AdUnitLoadResult.Failed(AdLoadFailure(-1, "android-ads-kit", failure.message.orEmpty())))
        }
    }
}

internal fun LoadAdError.toAdLoadFailure(): AdLoadFailure = AdLoadFailure(code, domain, message)

