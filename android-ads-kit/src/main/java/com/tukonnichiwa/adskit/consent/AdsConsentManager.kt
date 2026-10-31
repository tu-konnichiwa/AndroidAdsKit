package com.tukonnichiwa.adskit.consent

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import com.tukonnichiwa.adskit.AdsRequestGate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Geography to use with UMP debug settings. Use only for development and QA. */
public enum class ConsentDebugGeography {
    EEA,
    NOT_EEA
}

/** Optional UMP debug overrides. Never ship these values as production configuration. */
public data class ConsentDebugOptions(
    val geography: ConsentDebugGeography,
    val testDeviceHashedIds: List<String> = emptyList()
)

/** Error returned by UMP without exposing a UMP SDK type in the consent API. */
public data class AdsConsentFailure(val code: Int, val message: String)

/** Latest consent outcome and request eligibility. */
public data class AdsConsentResult(
    val failure: AdsConsentFailure?,
    val canRequestAds: Boolean,
    val privacyOptionsRequired: Boolean
)

/** Manages UMP consent state and can be used directly as an [AdsRequestGate]. */
public class AdsConsentManager(context: Context) : AdsRequestGate {
    private val information = UserMessagingPlatform.getConsentInformation(context.applicationContext)

    /** Whether UMP currently allows ad requests. */
    override fun canRequestAds(): Boolean = information.canRequestAds()

    /** Whether the app should offer a privacy-options entry point. */
    public val isPrivacyOptionsRequired: Boolean
        get() = information.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    /**
     * Refreshes consent information and shows a required form, if any.
     * Call on each app launch before requesting ads.
     */
    public suspend fun gatherConsent(
        activity: Activity,
        debugOptions: ConsentDebugOptions? = null
    ): AdsConsentResult = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val delivered = AtomicBoolean(false)
            fun complete(error: FormError?) {
                if (!delivered.compareAndSet(false, true) || !continuation.isActive) return
                continuation.resume(result(error))
            }

            val parameters = ConsentRequestParameters.Builder().apply {
                debugOptions?.let { debug ->
                    val settings = ConsentDebugSettings.Builder(activity)
                        .setDebugGeography(
                            when (debug.geography) {
                                ConsentDebugGeography.EEA -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA
                                ConsentDebugGeography.NOT_EEA -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_NOT_EEA
                            }
                        )
                    debug.testDeviceHashedIds.forEach(settings::addTestDeviceHashedId)
                    setConsentDebugSettings(settings.build())
                }
            }.build()

            try {
                information.requestConsentInfoUpdate(
                    activity,
                    parameters,
                    {
                        UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity, ::complete)
                    },
                    ::complete
                )
            } catch (error: Exception) {
                if (delivered.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(
                        AdsConsentResult(
                            AdsConsentFailure(-1, error.message.orEmpty()),
                            canRequestAds(),
                            isPrivacyOptionsRequired
                        )
                    )
                }
            }
        }
    }

    /** Shows the privacy-options form when available. */
    public suspend fun showPrivacyOptions(activity: Activity): AdsConsentResult =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val delivered = AtomicBoolean(false)
                try {
                    UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
                        if (delivered.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resume(result(error))
                        }
                    }
                } catch (error: Exception) {
                    if (delivered.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(
                            AdsConsentResult(
                                AdsConsentFailure(-1, error.message.orEmpty()),
                                canRequestAds(),
                                isPrivacyOptionsRequired
                            )
                        )
                    }
                }
            }
        }

    private fun result(error: FormError?): AdsConsentResult = AdsConsentResult(
        failure = error?.let { AdsConsentFailure(it.errorCode, it.message) },
        canRequestAds = canRequestAds(),
        privacyOptionsRequired = isPrivacyOptionsRequired
    )
}
