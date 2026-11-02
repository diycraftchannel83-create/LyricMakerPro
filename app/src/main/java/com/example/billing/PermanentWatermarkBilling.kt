package com.winsmediatama.lyricmakerpro.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Non-consumable Google Play entitlement for permanent watermark removal.
 * Google Play remains the source of truth: owned purchases are re-queried on every connection.
 */
class PermanentWatermarkBilling(context: Context) : PurchasesUpdatedListener {
    companion object {
        const val PRODUCT_ID = "remove_watermark"
        private const val PREFS = "lyricmakerpro_purchase_entitlements"
        private const val KEY_OWNED = "permanent_watermark_removed"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _isOwned = MutableStateFlow(prefs.getBoolean(KEY_OWNED, false))
    val isOwned = _isOwned.asStateFlow()

    private val _price = MutableStateFlow<String?>(null)
    val price = _price.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private var productDetails: ProductDetails? = null
    private var connecting = false

    private val billingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .enableAutoServiceReconnection()
        .build()

    fun connect() {
        if (billingClient.isReady || connecting) return
        connecting = true
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProduct()
                    restorePurchases()
                } else {
                    _message.value = "Google Play Billing unavailable: ${result.debugMessage}"
                }
            }

            override fun onBillingServiceDisconnected() {
                connecting = false
            }
        })
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        billingClient.queryProductDetailsAsync(params) { result, detailsResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetails = detailsResult.productDetailsList.firstOrNull()
                _price.value = productDetails
                    ?.oneTimePurchaseOfferDetailsList
                    ?.firstOrNull()
                    ?.formattedPrice
                if (productDetails == null) {
                    _message.value = "Product $PRODUCT_ID belum tersedia di Google Play."
                }
            } else {
                _message.value = "Gagal memuat harga Google Play: ${result.debugMessage}"
            }
        }
    }

    fun launchPurchase(activity: Activity) {
        if (!billingClient.isReady) {
            _message.value = "Menghubungkan ke Google Play. Coba lagi sebentar."
            connect()
            return
        }
        val details = productDetails
        val offer = details?.oneTimePurchaseOfferDetailsList?.firstOrNull()
        if (details == null || offer == null) {
            _message.value = "Produk belum tersedia. Periksa konfigurasi Play Console."
            queryProduct()
            return
        }

        val offerToken = offer.offerToken
        if (offerToken.isNullOrBlank()) {
            _message.value = "Token penawaran produk tidak tersedia. Periksa konfigurasi produk di Play Console."
            return
        }

        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offerToken)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _message.value = "Tidak dapat membuka pembayaran: ${result.debugMessage}"
        }
    }

    fun restorePurchases() {
        if (!billingClient.isReady) {
            connect()
            return
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val owned = purchases.any {
                    PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                setOwned(owned)
                purchases.filter { PRODUCT_ID in it.products }.forEach(::processPurchase)
            } else {
                _message.value = "Gagal memulihkan pembelian: ${result.debugMessage}"
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach(::processPurchase)
            BillingClient.BillingResponseCode.USER_CANCELED -> _message.value = "Pembayaran dibatalkan."
            else -> _message.value = "Pembayaran gagal: ${result.debugMessage}"
        }
    }

    private fun processPurchase(purchase: Purchase) {
        if (PRODUCT_ID !in purchase.products) return
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> {
                _message.value = "Pembayaran masih tertunda. Watermark belum dihapus."
            }
            Purchase.PurchaseState.PURCHASED -> {
                // A non-consumable purchase is retained by Google Play and restored on reinstall.
                setOwned(true)
                _message.value = "Pembelian berhasil. Watermark dihapus secara permanen."
                if (!purchase.isAcknowledged) {
                    val params = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                    billingClient.acknowledgePurchase(params) { ackResult ->
                        if (ackResult.responseCode != BillingClient.BillingResponseCode.OK) {
                            _message.value = "Hak akses aktif, tetapi konfirmasi pembelian perlu dicoba ulang."
                        }
                    }
                }
            }
        }
    }

    private fun setOwned(owned: Boolean) {
        _isOwned.value = owned
        prefs.edit().putBoolean(KEY_OWNED, owned).apply()
    }

    fun clearMessage() {
        _message.value = null
    }

    fun close() {
        if (billingClient.isReady) billingClient.endConnection()
    }
}
