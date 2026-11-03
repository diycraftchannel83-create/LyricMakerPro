package com.winsmediatama.lyricmakerpro.ads

import android.app.Activity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
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


/** App-open ads are shown only after Play Billing confirms this is a Free user. */
class FreeAppOpenAdManager(private val activity: Activity) {
    private var appOpenAd: com.google.android.gms.ads.appopen.AppOpenAd? = null
    private var loading = false
    private var premiumUser = true
    private var hasShownThisForeground = false
    private var initialized = false

    private val adUnitId: String
        get() = if (com.winsmediatama.lyricmakerpro.BuildConfig.DEBUG)
            "ca-app-pub-3940256099942544/9257395921"
        else "ca-app-pub-1254774552944426/5183216826"

    fun setPremiumStatus(isPremium: Boolean) {
        premiumUser = isPremium
        if (!isPremium) {
            if (!initialized) {
                initialized = true
                com.google.android.gms.ads.MobileAds.initialize(activity) { load() }
            }
            showIfReady()
        } else {
            appOpenAd = null
        }
    }

    fun onForeground() {
        hasShownThisForeground = false
        if (!premiumUser) {
            if (appOpenAd == null) load() else showIfReady()
        }
    }

    fun onBackground() {
        hasShownThisForeground = false
    }

    private fun load() {
        if (loading || appOpenAd != null || activity.isFinishing || premiumUser) return
        loading = true
        com.google.android.gms.ads.appopen.AppOpenAd.load(
            activity,
            adUnitId,
            com.google.android.gms.ads.AdRequest.Builder().build(),
            object : com.google.android.gms.ads.appopen.AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: com.google.android.gms.ads.appopen.AppOpenAd) {
                    loading = false
                    appOpenAd = ad
                    showIfReady()
                }

                override fun onAdFailedToLoad(error: com.google.android.gms.ads.LoadAdError) {
                    loading = false
                }
            }
        )
    }

    private fun showIfReady() {
        val ad = appOpenAd ?: return
        if (premiumUser || hasShownThisForeground || activity.isFinishing ||
            activity.isDestroyed || !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        ) return
        hasShownThisForeground = true
        appOpenAd = null
        ad.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { load() }
            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) { load() }
        }
        ad.show(activity)
    }
}

@androidx.compose.runtime.Composable
fun FreeBannerAd(isPremium: Boolean, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    if (isPremium) return
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier.fillMaxWidth().height(50.dp),
        factory = { ctx ->
            com.google.android.gms.ads.AdView(ctx).apply {
                setAdSize(com.google.android.gms.ads.AdSize.BANNER)
                adUnitId = if (com.winsmediatama.lyricmakerpro.BuildConfig.DEBUG)
                    "ca-app-pub-3940256099942544/6300978111"
                else "ca-app-pub-1254774552944426/2802476395"
                loadAd(com.google.android.gms.ads.AdRequest.Builder().build())
            }
        },
        update = {}
    )
}

@androidx.compose.runtime.Composable
fun FreeNativeAd(isPremium: Boolean, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    if (isPremium) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val nativeView = androidx.compose.runtime.remember(context) {
        val adView = com.google.android.gms.ads.nativead.NativeAdView(context)
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(14, 8, 14, 8)
            setBackgroundColor(android.graphics.Color.rgb(28, 34, 48))
        }
        val headline = android.widget.TextView(context).apply {
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            maxLines = 2
        }
        val body = android.widget.TextView(context).apply {
            textSize = 12f
            setTextColor(android.graphics.Color.LTGRAY)
            maxLines = 2
        }
        val cta = android.widget.Button(context).apply { text = "Learn more" }
        container.addView(headline)
        container.addView(body)
        container.addView(cta)
        adView.addView(container)
        adView.headlineView = headline
        adView.bodyView = body
        adView.callToActionView = cta
        adView
    }
    androidx.compose.runtime.DisposableEffect(nativeView) {
        val loader = com.google.android.gms.ads.AdLoader.Builder(
            context,
            if (com.winsmediatama.lyricmakerpro.BuildConfig.DEBUG)
                "ca-app-pub-3940256099942544/2247696110"
            else "ca-app-pub-1254774552944426/3471514050"
        ).forNativeAd { ad ->
            nativeView.setNativeAd(ad)
            (nativeView.headlineView as? android.widget.TextView)?.text = ad.headline
            (nativeView.bodyView as? android.widget.TextView)?.text = ad.body.orEmpty()
            (nativeView.bodyView as? android.widget.TextView)?.visibility =
                if (ad.body.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
            (nativeView.callToActionView as? android.widget.Button)?.apply {
                text = ad.callToAction ?: "Learn more"
                visibility = if (ad.callToAction.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
            }
        }.withAdListener(object : com.google.android.gms.ads.AdListener() {}).build()
        loader.loadAd(com.google.android.gms.ads.AdRequest.Builder().build())
        onDispose { nativeView.destroy() }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier.fillMaxWidth().heightIn(min = 76.dp, max = 116.dp),
        factory = { nativeView },
        update = {}
    )
}
