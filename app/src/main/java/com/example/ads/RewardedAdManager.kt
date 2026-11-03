package com.winsmediatama.lyricmakerpro.ads

import android.app.Activity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.OnUserEarnedRewardListener

/**
 * Activity-scoped rewarded-ad loader. A clean export is authorized only from
 * the SDK's OnUserEarnedRewardListener callback.
 */
class RewardedAdManager(private val activity: Activity) {
    private var rewardedAd: RewardedAd? = null
    private var isLoading = false

    private val adUnitId: String
        get() = if (com.winsmediatama.lyricmakerpro.BuildConfig.DEBUG) {
            TEST_REWARDED_AD_UNIT_ID
        } else {
            PRODUCTION_REWARDED_AD_UNIT_ID
        }

    fun initialize() {
        MobileAds.initialize(activity) { load() }
    }

    fun load() {
        if (isLoading || rewardedAd != null || activity.isFinishing) return
        isLoading = true
        RewardedAd.load(
            activity,
            adUnitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    isLoading = false
                    rewardedAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    rewardedAd = null
                }
            }
        )
    }

    fun show(onRewardEarned: () -> Unit, onUnavailable: () -> Unit) {
        val ad = rewardedAd
        if (ad == null) {
            load()
            onUnavailable()
            return
        }
        rewardedAd = null
        var earnedReward = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                load()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                onUnavailable()
                load()
            }
        }
        ad.show(activity, OnUserEarnedRewardListener {
            if (!earnedReward) {
                earnedReward = true
                onRewardEarned()
            }
        })
    }

    companion object {
        private const val TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
        private const val PRODUCTION_REWARDED_AD_UNIT_ID = "ca-app-pub-1254774552944426/3672317894"
    }
}
