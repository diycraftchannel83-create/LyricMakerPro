package com.winsmediatama.lyricmakerpro.ads

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

private object AdRetry {
    private val handler = Handler(Looper.getMainLooper())
    fun schedule(delayMs: Long, action: () -> Unit) {
        handler.postDelayed(action, delayMs)
    }
}

/**
 * Rewarded ads authorize a clean export only after the SDK reward callback.
 * Failed loads are retried with a bounded backoff while the user remains Free.
 */
class RewardedAdManager(private val activity: Activity) {
    private var rewardedAd: RewardedAd? = null
    private var isLoading = false
    private var initialized = false
    private var isPremium = true
    private var retryDelayMs = 15_000L

    private val adUnitId: String = if (com.winsmediatama.lyricmakerpro.BuildConfig.DEBUG) TEST_REWARDED_AD_UNIT_ID else PRODUCTION_REWARDED_AD_UNIT_ID

    fun initialize() {
        isPremium = false
        if (initialized) {
            load()
            return
        }
        initialized = true
        MobileAds.initialize(activity) { load() }
    }

    fun setPremiumStatus(premium: Boolean) {
        isPremium = premium
        if (premium) {
            rewardedAd = null
        } else {
            initialize()
        }
    }

    fun load() {
        if (isPremium || isLoading || rewardedAd != null ||
            activity.isFinishing || activity.isDestroyed
        ) return
        isLoading = true
        RewardedAd.load(
            activity,
            adUnitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    isLoading = false
                    retryDelayMs = 15_000L
                    rewardedAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    rewardedAd = null
                    scheduleRetry()
                }
            }
        )
    }

    fun show(onRewardEarned: () -> Unit, onUnavailable: () -> Unit) {
        if (isPremium) {
            onUnavailable()
            return
        }
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
        ad.show(activity) {
            if (!earnedReward) {
                earnedReward = true
                onRewardEarned()
            }
        }
    }

    private fun scheduleRetry() {
        if (isPremium || activity.isFinishing || activity.isDestroyed) return
        val delay = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(5 * 60_000L)
        AdRetry.schedule(delay) { load() }
    }

    companion object {
        private const val PRODUCTION_REWARDED_AD_UNIT_ID = "ca-app-pub-1254774552944426/5741489179"
        private const val TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
    }
}


/** App-open ads are requested only for confirmed Free users. */
class FreeAppOpenAdManager(private val activity: Activity) {
    private var appOpenAd: com.google.android.gms.ads.appopen.AppOpenAd? = null
    private var loading = false
    private var premiumUser = true
    private var hasShownThisForeground = false
    private var isForeground = false
    private var initialized = false
    private var retryDelayMs = 15_000L

    private val adUnitId: String = if (com.winsmediatama.lyricmakerpro.BuildConfig.DEBUG) "ca-app-pub-3940256099942544/9257395921" else "ca-app-pub-1254774552944426/9151767484"

    fun setPremiumStatus(isPremium: Boolean) {
        premiumUser = isPremium
        if (isPremium) {
            appOpenAd = null
            return
        }
        if (!initialized) {
            initialized = true
            MobileAds.initialize(activity) { load() }
        } else {
            load()
        }
        showIfReady()
    }

    fun onForeground() {
        isForeground = true
        hasShownThisForeground = false
        if (!premiumUser) {
            if (appOpenAd == null) load() else showIfReady()
        }
    }

    fun onBackground() {
        isForeground = false
    }

    private fun load() {
        if (premiumUser || loading || appOpenAd != null ||
            activity.isFinishing || activity.isDestroyed
        ) return
        loading = true
        com.google.android.gms.ads.appopen.AppOpenAd.load(
            activity,
            adUnitId,
            AdRequest.Builder().build(),
            object : com.google.android.gms.ads.appopen.AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: com.google.android.gms.ads.appopen.AppOpenAd) {
                    loading = false
                    retryDelayMs = 15_000L
                    appOpenAd = ad
                    showIfReady()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loading = false
                    scheduleRetry()
                }
            }
        )
    }

    private fun showIfReady() {
        val ad = appOpenAd ?: return
        if (premiumUser || hasShownThisForeground || activity.isFinishing ||
            activity.isDestroyed || !isForeground
        ) return
        hasShownThisForeground = true
        appOpenAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { load() }
            override fun onAdFailedToShowFullScreenContent(error: AdError) { load() }
        }
        ad.show(activity)
    }

    private fun scheduleRetry() {
        if (premiumUser || activity.isFinishing || activity.isDestroyed) return
        val delay = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(5 * 60_000L)
        AdRetry.schedule(delay) { load() }
    }
}

@androidx.compose.runtime.Composable
fun FreeBannerAd(isPremium: Boolean, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    if (isPremium) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val adView = androidx.compose.runtime.remember(context) {
        com.google.android.gms.ads.AdView(context).apply {
            setAdSize(com.google.android.gms.ads.AdSize.BANNER)
            adUnitId = if (com.winsmediatama.lyricmakerpro.BuildConfig.DEBUG) "ca-app-pub-3940256099942544/6300978111" else "ca-app-pub-1254774552944426/2802476395"
            loadAd(AdRequest.Builder().build())
        }
    }
    androidx.compose.runtime.DisposableEffect(adView) {
        onDispose { adView.destroy() }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier.fillMaxWidth().height(50.dp),
        factory = { adView },
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
        var loadedNativeAd: com.google.android.gms.ads.nativead.NativeAd? = null
        var disposed = false
        val retryHandler = Handler(Looper.getMainLooper())
        lateinit var loader: com.google.android.gms.ads.AdLoader
        loader = com.google.android.gms.ads.AdLoader.Builder(
            context,
            if (com.winsmediatama.lyricmakerpro.BuildConfig.DEBUG) "ca-app-pub-3940256099942544/2247696110" else "ca-app-pub-1254774552944426/3471514050"
        ).forNativeAd { ad ->
            loadedNativeAd?.destroy()
            loadedNativeAd = ad
            nativeView.setNativeAd(ad)
            (nativeView.headlineView as? android.widget.TextView)?.text = ad.headline
            (nativeView.bodyView as? android.widget.TextView)?.apply {
                text = ad.body.orEmpty()
                visibility = if (ad.body.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
            }
            (nativeView.callToActionView as? android.widget.Button)?.apply {
                text = ad.callToAction ?: "Learn more"
                visibility = if (ad.callToAction.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
            }
        }.withAdListener(object : AdListener() {
            override fun onAdFailedToLoad(error: LoadAdError) {
                if (!disposed) {
                    retryHandler.postDelayed({
                        if (!disposed) loader.loadAd(AdRequest.Builder().build())
                    }, 30_000L)
                }
            }
        }).build()
        loader.loadAd(AdRequest.Builder().build())
        onDispose {
            disposed = true
            retryHandler.removeCallbacksAndMessages(null)
            loadedNativeAd?.destroy()
        }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier.fillMaxWidth().heightIn(min = 76.dp, max = 116.dp),
        factory = { nativeView },
        update = {}
    )
}


/** Interstitial ads are queued after successful export if a request is still loading. */
class FreeInterstitialAdManager(private val activity: Activity) {
    private var interstitialAd: com.google.android.gms.ads.interstitial.InterstitialAd? = null
    private var isLoading = false
    private var isPremium = true
    private var pendingShowAfterExport = false
    private var retryDelayMs = 15_000L

    private val adUnitId: String = if (com.winsmediatama.lyricmakerpro.BuildConfig.DEBUG) TEST_INTERSTITIAL_AD_UNIT_ID else PRODUCTION_INTERSTITIAL_AD_UNIT_ID

    fun setPremiumStatus(premium: Boolean) {
        isPremium = premium
        if (premium) {
            interstitialAd = null
            pendingShowAfterExport = false
        } else {
            load()
        }
    }

    fun load() {
        if (isPremium || isLoading || interstitialAd != null ||
            activity.isFinishing || activity.isDestroyed
        ) return
        isLoading = true
        com.google.android.gms.ads.interstitial.InterstitialAd.load(
            activity,
            adUnitId,
            AdRequest.Builder().build(),
            object : com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: com.google.android.gms.ads.interstitial.InterstitialAd) {
                    isLoading = false
                    retryDelayMs = 15_000L
                    interstitialAd = ad
                    if (pendingShowAfterExport) showLoadedAd()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    interstitialAd = null
                    scheduleRetry()
                }
            }
        )
    }

    fun showAfterExport() {
        if (isPremium) return
        pendingShowAfterExport = true
        // Do not surface a stale export ad if the network takes too long to fill it.
        AdRetry.schedule(60_000L) { pendingShowAfterExport = false }
        if (interstitialAd != null) {
            showLoadedAd()
        } else {
            load()
        }
    }

    private fun showLoadedAd() {
        if (isPremium || activity.isFinishing || activity.isDestroyed) return
        val ad = interstitialAd ?: return
        interstitialAd = null
        pendingShowAfterExport = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { load() }
            override fun onAdFailedToShowFullScreenContent(error: AdError) { load() }
        }
        ad.show(activity)
    }

    private fun scheduleRetry() {
        if (isPremium || activity.isFinishing || activity.isDestroyed) return
        val delay = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(5 * 60_000L)
        AdRetry.schedule(delay) { load() }
    }

    companion object {
        private const val PRODUCTION_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-1254774552944426/3914322052"
        private const val TEST_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
    }
}
