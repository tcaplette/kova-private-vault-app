package com.app.vault

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * The one purchase — P1-16, made a one-time product by `one-time-purchase`.
 *
 * This ships with `android.permission.INTERNET` stripped from the merged manifest
 * (see the app manifest for the reasoning, and `OUTLINE.md` P1-16 for the
 * measurement). A purchase is `bindService` IPC to `com.android.vending`; the Play
 * Store owns the network side of it, in its own process, under its own
 * permissions. Nothing in this file opens a socket, and nothing in it may start.
 *
 * What it does: connect, query this one product, query purchases, launch this one
 * flow, acknowledge. There is no obfuscated account id, no receipt cache, no
 * retry loop and no telemetry — a retry loop would be code written to make a user
 * *less* able to use the app, which is the wrong direction to spend effort in.
 *
 * **A non-consumable `INAPP` product.** Acknowledged, and never consumed: consuming
 * is what makes a product buyable again, and it would throw ownership away. Play
 * keeps the purchase on the account, so a reinstall or a new phone gets it back
 * from [refresh] with no restore button, and a refund removes it the same way.
 *
 * [owned] is `null` until Play answers, and [hasPurchased] reads that as
 * entitled. That is I5, and it is why nothing here has a timeout: an answer that
 * never arrives and an answer of "yes" have the same consequence.
 */
object Billing {

    const val PRODUCT_ID = "vault_unlock"

    private const val TAG = "VaultBilling"

    /**
     * `null` = Play has not answered. `true`/`false` = it has. Only `false` can
     * gate anything, and only alongside an expired trial.
     */
    @Volatile
    var owned: Boolean? = null
        private set

    /**
     * A purchase Play holds as `PENDING` — a cash or delayed payment that has not
     * cleared. It is **not** ownership: nothing is granted for money that has not
     * arrived. It exists so the Purchase screen can say so instead of offering a
     * second purchase on top of the first.
     */
    @Volatile
    var pending: Boolean = false
        private set

    /**
     * Play's localised price for the product, `null` until a product query has
     * answered. The button never shows a price written into the app.
     */
    @Volatile
    var price: String? = null
        private set

    /**
     * Called whenever [owned], [pending] or [price] changes, on whatever thread Play
     * answered on. The activity sets it, so a purchase completed over an open screen
     * lifts that screen's gate in place.
     */
    @Volatile
    var onChange: (() -> Unit)? = null

    private var client: BillingClient? = null

    /** No answer is not a "no". [Entitlement] holds the other half of this rule. */
    fun hasPurchased(): Boolean = owned ?: true

    /**
     * Connects once per process, from the activity's `onCreate`. Idempotent, so a
     * configuration change or a second call costs nothing.
     *
     * `enableAutoServiceReconnection` is the library's own reconnect, which is why
     * this file has no reconnection logic of its own.
     */
    fun connect(context: Context) {
        if (client != null) return
        val c = BillingClient.newBuilder(context.applicationContext)
            .setListener { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    handle(purchases.orEmpty())
                }
            }
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .enableAutoServiceReconnection()
            .build()
        client = c
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                Log.i(TAG, "setup: ${billingResult.responseCode}")
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    refresh()
                    loadPrice()
                }
            }

            // Deliberately empty: the client reconnects itself, and a disconnected
            // client leaves `active` where it was, which reads as entitled if Play
            // never answered at all.
            override fun onBillingServiceDisconnected() = Unit
        })
    }

    /**
     * Reads Play's local purchase cache. This is the whole licence check, and the
     * only call whose answer can ever restrict anything. Called on connection and on
     * every return to the foreground: a pending payment that clears, or a refund,
     * happens while the app is away.
     */
    fun refresh() {
        val c = client ?: return
        c.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                // A failed query is not a negative answer. I5.
                Log.i(TAG, "purchase query failed: ${result.responseCode}; staying entitled")
                return@queryPurchasesAsync
            }
            handle(purchases)
            val ours = purchases.filter { PRODUCT_ID in it.products }
            owned = ours.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            pending = ours.any { it.purchaseState == Purchase.PurchaseState.PENDING }
            Log.i(TAG, "owned: $owned, pending: $pending")
            onChange?.invoke()
        }
    }

    /**
     * Fetches the product's localised price for the buy button. A failure leaves
     * [price] null, and the button reads "Buy" — nothing is blocked by not knowing
     * what it costs.
     */
    fun loadPrice() {
        val c = client ?: return
        if (!c.isReady) return
        c.queryProductDetailsAsync(productQuery()) { result, details ->
            val offer = details.productDetailsList.firstOrNull()?.let(::offerOf)
            if (result.responseCode == BillingClient.BillingResponseCode.OK && offer != null) {
                price = offer.formattedPrice
                onChange?.invoke()
            }
        }
    }

    /**
     * The product's one offer. A product created with Play's one-time offers carries
     * a list and an offer token; one created without them carries a single offer and
     * no token, which the flow then takes without one.
     */
    private fun offerOf(product: ProductDetails): ProductDetails.OneTimePurchaseOfferDetails? =
        product.oneTimePurchaseOfferDetailsList?.firstOrNull() ?: product.oneTimePurchaseOfferDetails

    private fun productQuery(): QueryProductDetailsParams =
        QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

    /**
     * Acknowledges anything purchased and not yet acknowledged. Play auto-refunds
     * an unacknowledged purchase after three days, so this is not optional and is
     * not deferrable to a later screen.
     */
    private fun handle(purchases: List<Purchase>) {
        val c = client ?: return
        // A pending purchase is reported on its own; it is acknowledged only once
        // Play moves it to PURCHASED, which the listener or the next [refresh] sees.
        if (purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING && PRODUCT_ID in it.products }) {
            pending = true
            onChange?.invoke()
        }
        purchases
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
            .forEach { purchase ->
                c.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                ) { result ->
                    Log.i(TAG, "acknowledge: ${result.responseCode}")
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        owned = true
                        pending = false
                        onChange?.invoke()
                    }
                }
            }
    }

    /**
     * Fetches the product at the moment the user asks for it and launches Play's
     * sheet. Nothing is cached across process death: if Play is not answering,
     * the user is not blocked from anything anyway.
     *
     * [onUnavailable] is called when there is nothing to launch — an unpublished
     * product, no Play Store, a dead client — so Settings can say so instead of
     * showing a button that does nothing.
     */
    fun purchase(activity: Activity, onUnavailable: (Int) -> Unit) {
        val c = client
        if (c == null || !c.isReady) {
            onUnavailable(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
            return
        }
        c.queryProductDetailsAsync(productQuery()) { result, details ->
            val product: ProductDetails? = details.productDetailsList.firstOrNull()
            val offer = product?.let(::offerOf)
            if (result.responseCode != BillingClient.BillingResponseCode.OK ||
                product == null || offer == null
            ) {
                Log.i(TAG, "product query: ${result.responseCode}, product=${product != null}")
                activity.runOnUiThread { onUnavailable(result.responseCode) }
                return@queryProductDetailsAsync
            }
            val flow = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(product)
                            .apply { offer.offerToken?.let(::setOfferToken) }
                            .build()
                    )
                )
                .build()
            activity.runOnUiThread { c.launchBillingFlow(activity, flow) }
        }
    }
}
