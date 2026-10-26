package com.app.vault

import android.app.Activity
import android.content.Context

/**
 * The purchase, as the GitHub build has it — `distribution-flavors`.
 *
 * A copy built from the public source is free and complete: no trial and no purchase.
 * This stands in for `src/play`'s `Billing`, which wraps Play Billing, with the same
 * public surface so shared code compiles unchanged against either. Here the purchase
 * is always owned, nothing is pending, there is no price, and every call does nothing.
 *
 * There is no `com.android.billingclient` import, and there must never be one: the
 * library is a `play`-only dependency, so the `github` APK carries none of its classes,
 * its notice or its `BILLING` permission.
 */
object Billing {

    const val PRODUCT_ID = "vault_unlock"

    /** Always owned: there is nothing to buy. */
    @Volatile
    var owned: Boolean? = true
        private set

    /** Never pending: no payment is ever started. */
    @Volatile
    var pending: Boolean = false
        private set

    /** No price: there is no product to show one for. */
    @Volatile
    var price: String? = null
        private set

    /** Kept for the shared call sites; nothing here ever changes, so it is never called. */
    @Volatile
    var onChange: (() -> Unit)? = null

    fun hasPurchased(): Boolean = true

    fun connect(context: Context) = Unit

    fun refresh() = Unit

    fun loadPrice() = Unit

    /** Never reached: no route shows the Purchase screen on this build. */
    fun purchase(activity: Activity, onUnavailable: (Int) -> Unit) = Unit
}
