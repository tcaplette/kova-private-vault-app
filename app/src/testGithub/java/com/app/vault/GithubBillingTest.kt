package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `distribution-flavors`. The `github` build's `Billing` is a stub that reports the
 * purchase as owned — there is nothing to buy — so `Entitlement`'s probe can never
 * read a "not owned" on this build, whatever else changes.
 *
 * JVM scope: the stub touches no Android API until a call that does nothing.
 */
class GithubBillingTest {

    @Test
    fun theStubReportsThePurchaseOwned() {
        assertTrue("hasPurchased() is always true", Billing.hasPurchased())
        assertEquals("owned is always true", true, Billing.owned)
    }
}
