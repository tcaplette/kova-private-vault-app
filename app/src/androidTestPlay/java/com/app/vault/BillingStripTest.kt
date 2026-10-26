package com.app.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * P1-16 task 1 — the measurement P0-2 left outstanding.
 *
 * P0-2 established that `billing-ktx` *declares* `android.permission.INTERNET`,
 * which I1 forbids, and that `tools:node="remove"` strips it from the merged
 * manifest. What it could not establish is the half that matters: whether the
 * library still works once stripped. The purchase flow is `bindService` IPC to
 * `com.android.vending`, so it should — and "should" is not a measurement.
 *
 * This runs against the app's own manifest, so the strip is in force for every
 * line below.
 *
 * **Retained, not spike code**, for `CryptoSpikeBenchmark`'s reason: the answer is
 * version-specific, so it has to be re-measured whenever the Billing version
 * moves, which is a recurring need rather than one that expires. `androidTest`
 * scope; ships in no APK.
 *
 * Legs 1 and 2 run anywhere the Play Store is installed. **Leg 3 — the completed
 * purchase — cannot run from here at all**: it needs the app published to an
 * internal-testing track and a licence-tester account, and it needs a human to
 * tap through Play's sheet. It is a manual measurement, recorded in `OUTLINE.md`.
 *
 * **Skipped unless asked for**, the way [DeviceFixtures] is: these need a Play
 * Store with a Google account signed in, and on a device without one Play answers
 * `BILLING_UNAVAILABLE` to everything — which says nothing about the strip and
 * would otherwise fail the suite forever on an environment that cannot be fixed
 * from the working tree. Not an `Assume` on the response code itself: that is the
 * value this test exists to check, and assuming it away would make the gate inert.
 *
 * Run:
 *
 *     ./gradlew :app:connectedPlayDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.billing=play
 *
 * Read: adb logcat -s VaultBilling:I
 */
@RunWith(AndroidJUnit4::class)
class BillingStripTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun requireAPlayEnvironment() {
        assumeTrue(
            "billing measurement not requested (needs a signed-in Play account)",
            InstrumentationRegistry.getArguments().getString("billing") == "play"
        )
    }

    private companion object {
        const val TAG = "VaultBilling"
        const val TIMEOUT_S = 30L

        /**
         * Deliberately a literal here rather than a reference to [Billing]: this
         * test ran before that file existed, because the point of task 1 is that
         * no billing code gets written on top of an unmeasured assumption.
         */
        const val PRODUCT_ID = Billing.PRODUCT_ID
    }

    private fun client(): BillingClient =
        BillingClient.newBuilder(ctx)
            .setListener { _, _ -> }
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()

    private fun BillingClient.connect(): BillingResult {
        val latch = CountDownLatch(1)
        var result: BillingResult? = null
        startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                result = billingResult
                latch.countDown()
            }

            override fun onBillingServiceDisconnected() = Unit
        })
        check(latch.await(TIMEOUT_S, TimeUnit.SECONDS)) { "startConnection never called back" }
        return result!!
    }

    /**
     * **Leg 1.** The one that would fail outright if the stripped permission were
     * load-bearing: binding to the Play Store service and completing setup.
     */
    @Test
    fun leg1_connectsWithInternetStripped() {
        val billing = client()
        try {
            val result = billing.connect()
            Log.i(TAG, "leg 1 startConnection: ${result.responseCode} ${result.debugMessage}")
            assertEquals(
                "startConnection with INTERNET stripped: ${result.debugMessage}",
                BillingClient.BillingResponseCode.OK,
                result.responseCode,
            )
            Log.i(TAG, "leg 1 isReady after setup: ${billing.isReady}")
        } finally {
            billing.endConnection()
        }
    }

    /**
     * **Leg 2.** A round trip that carries a real payload back from Play, so it
     * exercises more than the bind. Asserts the *transport*, not the catalogue:
     * `vault_unlock` does not resolve until the app is published with the product
     * configured, and an unpublished app answers a well-formed error rather than
     * failing to answer. Either way the response code is recorded, and an
     * unanswered query — the shape a missing permission would produce — fails.
     */
    @Test
    fun leg2_queriesProductDetails() {
        val billing = client()
        try {
            assertEquals(
                BillingClient.BillingResponseCode.OK,
                billing.connect().responseCode,
            )

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

            val latch = CountDownLatch(1)
            var code = Int.MIN_VALUE
            var message = ""
            var payload = "(none)"
            billing.queryProductDetailsAsync(params) { result, details ->
                code = result.responseCode
                message = result.debugMessage
                payload = details.toString()
                latch.countDown()
            }
            check(latch.await(TIMEOUT_S, TimeUnit.SECONDS)) {
                "queryProductDetailsAsync never called back — the shape a missing permission " +
                    "would produce"
            }
            Log.i(TAG, "leg 2 queryProductDetailsAsync: $code $message -> $payload")
        } finally {
            billing.endConnection()
        }
    }

    /**
     * The query the entitlement engine actually depends on at runtime. Not one of
     * the three legs — it reads Play's local purchase cache — but it is the call
     * `Billing` makes, and it must answer on the stripped build.
     */
    @Test
    fun queryPurchasesAnswers() {
        val billing = client()
        try {
            assertEquals(
                BillingClient.BillingResponseCode.OK,
                billing.connect().responseCode,
            )

            val latch = CountDownLatch(1)
            var code = Int.MIN_VALUE
            var count = -1
            billing.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            ) { result, purchases ->
                code = result.responseCode
                count = purchases.size
                latch.countDown()
            }
            check(latch.await(TIMEOUT_S, TimeUnit.SECONDS)) {
                "queryPurchasesAsync never called back"
            }
            Log.i(TAG, "queryPurchasesAsync: $code, ${count} one-time purchase(s)")
            assertEquals(
                "queryPurchasesAsync with INTERNET stripped",
                BillingClient.BillingResponseCode.OK,
                code,
            )
        } finally {
            billing.endConnection()
        }
    }
}
