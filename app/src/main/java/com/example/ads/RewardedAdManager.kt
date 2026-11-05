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
    // If the user taps before an ad is ready, hold one request and show the ad
    // automatically after loading instead of making the user tap a second time.
    private var pendingRewardCallbacks: Pair<() -> Unit, () -> Unit>? = null

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
            val pending = pendingRewardCallbacks
            pendingRewardCallbacks = null
            pending?.second?.invoke()
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
                    val pending = pendingRewardCallbacks
                    if (pending != null) {
                        pendingRewardCallbacks = null
                        rewardedAd = null
                        showLoadedAd(ad, pending.first, pending.second)
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    rewardedAd = null
                    val pending = pendingRewardCallbacks
                    if (pending != null) {
                        pendingRewardCallbacks = null
                        pending.second()
                    }
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
            if (pendingRewardCallbacks != null) {
                onUnavailable()
                return
            }
            pendingRewardCallbacks = onRewardEarned to onUnavailable
            load()
            return
        }

        rewardedAd = null
        showLoadedAd(ad, onRewardEarned, onUnavailable)
    }

    private fun showLoadedAd(
        ad: RewardedAd,
        onRewardEarned: () -> Unit,
        onUnavailable: () -> Unit
    ) {
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
    val density = context.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()

    val nativeView = androidx.compose.runtime.remember(context) {
        val adView = com.google.android.gms.ads.nativead.NativeAdView(context)
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(28, 34, 48))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Keep the ad attribution visible while using a compact horizontal layout.
        val attribution = android.widget.TextView(context).apply {
            text = "Ad"
            textSize = 10f
            setTextColor(android.graphics.Color.rgb(0, 220, 200))
            setPadding(0, 0, 0, dp(4))
            contentDescription = "Advertisement"
        }
        val row = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val icon = android.widget.ImageView(context).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                marginEnd = dp(9)
            }
        }
        val details = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        val headline = android.widget.TextView(context).apply {
            textSize = 13f
            setTextColor(android.graphics.Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val advertiser = android.widget.TextView(context).apply {
            textSize = 10f
            setTextColor(android.graphics.Color.LTGRAY)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val body = android.widget.TextView(context).apply {
            textSize = 11f
            setTextColor(android.graphics.Color.rgb(190, 196, 208))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        val cta = android.widget.Button(context).apply {
            textSize = 10f
            isAllCaps = false
            minWidth = dp(64)
            minimumWidth = dp(64)
            minHeight = dp(34)
            minimumHeight = dp(34)
            setPadding(dp(8), 0, dp(8), 0)
            setTextColor(android.graphics.Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.rgb(235, 0, 126)
            )
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply { marginStart = dp(8) }
        }

        details.addView(headline)
        details.addView(advertiser)
        details.addView(body)
        row.addView(icon)
        row.addView(details)
        row.addView(cta)
        container.addView(attribution)
        container.addView(row)
        adView.addView(container)

        // Register every asset used by this compact native-ad layout.
        adView.iconView = icon
        adView.headlineView = headline
        adView.advertiserView = advertiser
        adView.bodyView = body
        adView.callToActionView = cta
        adView
    }

    androidx.compose.runtime.DisposableEffect(nativeView) {
        var loadedNativeAd: com.google.android.gms.ads.nativead.NativeAd? = null
        var disposed = false
        val retryHandler = Handler(Looper.getMainLooper())
        var loader: com.google.android.gms.ads.AdLoader? = null
        loader = com.google.android.gms.ads.AdLoader.Builder(
            context,
            if (com.winsmediatama.lyricmakerpro.BuildConfig.DEBUG) "ca-app-pub-3940256099942544/2247696110" else "ca-app-pub-1254774552944426/3471514050"
        ).forNativeAd { ad ->
            if (disposed) {
                ad.destroy()
                return@forNativeAd
            }
            loadedNativeAd?.destroy()
            loadedNativeAd = ad

            (nativeView.iconView as? android.widget.ImageView)?.apply {
                setImageDrawable(ad.icon?.drawable)
                visibility = if (ad.icon?.drawable != null) android.view.View.VISIBLE else android.view.View.GONE
            }
            (nativeView.headlineView as? android.widget.TextView)?.apply {
                text = ad.headline
                visibility = if (ad.headline.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
            }
            (nativeView.advertiserView as? android.widget.TextView)?.apply {
                text = ad.advertiser.orEmpty()
                visibility = if (ad.advertiser.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
            }
            (nativeView.bodyView as? android.widget.TextView)?.apply {
                text = ad.body.orEmpty()
                visibility = if (ad.body.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
            }
            (nativeView.callToActionView as? android.widget.Button)?.apply {
                text = ad.callToAction.orEmpty()
                visibility = if (ad.callToAction.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
            }
            // Let the SDK manage native-ad clicks and impressions.
            nativeView.setNativeAd(ad)
        }.withAdListener(object : AdListener() {
            override fun onAdFailedToLoad(error: LoadAdError) {
                if (!disposed) {
                    retryHandler.postDelayed({
                        if (!disposed) loader?.loadAd(AdRequest.Builder().build())
                    }, 30_000L)
                }
            }
        }).build()

        loader?.loadAd(AdRequest.Builder().build())
        onDispose {
            disposed = true
            retryHandler.removeCallbacksAndMessages(null)
            loadedNativeAd?.destroy()
            loadedNativeAd = null
            nativeView.setNativeAd(null)
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier.fillMaxWidth().heightIn(min = 88.dp, max = 112.dp),
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
