package com.winsmediatama.lyricmakerpro.ads

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd

/**
 * Shows one App Open ad on the first successful app launch after installation.
 * The preference is persisted only after the ad is actually shown.
 */
class FirstLaunchAppOpenAdManager(private val activity: Activity) {
    private var appOpenAd: AppOpenAd? = null
    private var isLoading = false
    private var hasAttemptedShow = false

    fun loadForFirstLaunch() {
        if (hasAlreadyBeenShown() || isLoading || appOpenAd != null || activity.isFinishing) return
        isLoading = true

        AppOpenAd.load(
            activity,
            adUnitId(),
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    isLoading = false
                    appOpenAd = ad
                    showIfReady()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    appOpenAd = null
                    // Do not mark as shown; a later app launch can retry.
                }
            }
        )
    }

    private fun showIfReady() {
        if (hasAttemptedShow || activity.isFinishing || activity.isDestroyed) return
        val ad = appOpenAd ?: return
        hasAttemptedShow = true
        appOpenAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                preferences().edit().putBoolean(KEY_SHOWN, true).apply()
            }

            override fun onAdDismissedFullScreenContent() {
                // This ad is intentionally first-launch-only; do not reload it.
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                // A failed show does not consume the first-launch opportunity.
                hasAttemptedShow = false
            }
        }
        ad.show(activity)
    }

    private fun hasAlreadyBeenShown(): Boolean =
        preferences().getBoolean(KEY_SHOWN, false)

    private fun preferences() =
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun adUnitId(): String =
        if (com.winsmediatama.lyricmakerpro.BuildConfig.DEBUG) {
            TEST_APP_OPEN_AD_UNIT_ID
        } else {
            PRODUCTION_APP_OPEN_AD_UNIT_ID
        }

    companion object {
        private const val PREFS_NAME = "lyricmakerpro_ad_preferences"
        private const val KEY_SHOWN = "first_launch_app_open_shown"
        private const val TEST_APP_OPEN_AD_UNIT_ID = "ca-app-pub-3940256099942544/9257395921"
        private const val PRODUCTION_APP_OPEN_AD_UNIT_ID = "ca-app-pub-1254774552944426/5183216826"
    }
}
