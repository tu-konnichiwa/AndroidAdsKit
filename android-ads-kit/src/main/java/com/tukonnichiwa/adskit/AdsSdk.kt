package com.tukonnichiwa.adskit

import android.content.Context
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.initialization.AdapterStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Process-wide initializer for Google Mobile Ads. */
public object AdsSdk {
    // Mobile Ads uses -1 for UNSPECIFIED, 0 for FALSE and 1 for TRUE.
    private const val TAG_UNSPECIFIED = -1
    private val lock = Any()
    @Volatile
    private var initialization: CompletableDeferred<Map<String, AdapterStatus>>? = null
    private var initializationConfiguration: AdsRequestConfiguration? = null

    /**
     * Applies request configuration and initializes the SDK once per process.
     * Concurrent calls await the same SDK initialization. Later calls must provide the same
     * configuration; a different configuration throws [IllegalArgumentException].
     */
    public suspend fun initialize(
        context: Context,
        configuration: AdsRequestConfiguration = AdsRequestConfiguration()
    ): Map<String, AdapterStatus> = withContext(Dispatchers.Main.immediate) {
        val appContext = context.applicationContext
        val deferred = synchronized(lock) {
            initialization?.let { pending ->
                require(initializationConfiguration == configuration) {
                    "Google Mobile Ads was already initialized with a different request configuration"
                }
                pending
            } ?: CompletableDeferred<Map<String, AdapterStatus>>().also { pending ->
                initialization = pending
                initializationConfiguration = configuration
                try {
                    MobileAds.setRequestConfiguration(configuration.toSdkConfiguration())
                    MobileAds.initialize(appContext) { status ->
                        pending.complete(status.adapterStatusMap)
                    }
                } catch (error: Exception) {
                    initialization = null
                    initializationConfiguration = null
                    pending.completeExceptionally(error)
                }
            }
        }
        deferred.await()
    }

    private fun AdsRequestConfiguration.toSdkConfiguration(): RequestConfiguration =
        RequestConfiguration.Builder()
            .setTestDeviceIds(testDeviceIds)
            .setMaxAdContentRating(maxAdContentRating)
            .setTagForChildDirectedTreatment(
                childDirectedTreatment?.let { if (it) 1 else 0 } ?: TAG_UNSPECIFIED
            )
            .setTagForUnderAgeOfConsent(
                underAgeOfConsent?.let { if (it) 1 else 0 } ?: TAG_UNSPECIFIED
            )
            .build()
}
