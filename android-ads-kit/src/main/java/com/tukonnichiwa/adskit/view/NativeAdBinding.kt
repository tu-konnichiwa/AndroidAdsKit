package com.tukonnichiwa.adskit.view

import android.view.View
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView as GoogleNativeAdView
import com.tukonnichiwa.adskit.R

/** Binds standard native-ad asset IDs inside a Google Mobile Ads native-ad view. */
internal class NativeAdBinding(private val root: View) {
    private val nativeAdView = (root as? GoogleNativeAdView)
        ?: root.findViewById<GoogleNativeAdView?>(R.id.ad_container)
    private val headline = root.findViewById<TextView?>(R.id.ad_headline)
    private val media = root.findViewById<MediaView?>(R.id.ad_media)
    private val body = root.findViewById<TextView?>(R.id.ad_body)
    private val callToAction = root.findViewById<View?>(R.id.ad_call_to_action)
    private val icon = root.findViewById<ImageView?>(R.id.ad_app_icon)
    private val advertiser = root.findViewById<TextView?>(R.id.ad_advertiser)
    private val rating = root.findViewById<RatingBar?>(R.id.ad_stars)

    fun requireNativeAdView() {
        require(nativeAdView != null) {
            "The ad layout must use Google NativeAdView as its root or include @id/ad_container"
        }
    }

    fun bind(ad: NativeAd) {
        val adView = requireNotNull(nativeAdView)

        adView.headlineView = headline
        adView.mediaView = media
        adView.bodyView = body
        adView.callToActionView = callToAction
        adView.iconView = icon
        adView.advertiserView = advertiser
        adView.starRatingView = rating

        headline?.text = ad.headline
        media?.mediaContent = ad.mediaContent
        bindOptionalText(body, ad.body)
        bindOptionalText(callToAction as? TextView, ad.callToAction)
        bindOptionalIcon(ad)
        bindAdvertiserOrRating(ad)
        adView.setNativeAd(ad)
    }

    fun clear() {
        headline?.text = null
        media?.mediaContent = null
        body?.text = null
        callToAction?.let { if (it is TextView) it.text = null }
        icon?.setImageDrawable(null)
        advertiser?.text = null
        rating?.rating = 0f
        listOf(body, callToAction, icon, advertiser, rating).forEach { it?.visibility = View.GONE }
    }

    private fun bindOptionalText(view: TextView?, value: String?) {
        view?.text = value
        view?.visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun bindOptionalIcon(ad: NativeAd) {
        icon?.setImageDrawable(ad.icon?.drawable)
        icon?.visibility = if (ad.icon == null) View.GONE else View.VISIBLE
    }

    private fun bindAdvertiserOrRating(ad: NativeAd) {
        if (!ad.advertiser.isNullOrBlank()) {
            bindOptionalText(advertiser, ad.advertiser)
            rating?.visibility = View.GONE
            return
        }

        advertiser?.visibility = View.GONE
        val starRating = ad.starRating
        if (starRating == null) {
            rating?.visibility = View.GONE
        } else {
            rating?.rating = starRating.toFloat()
            rating?.visibility = View.VISIBLE
        }
    }
}
