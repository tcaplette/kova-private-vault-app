package com.app.vault

import android.Manifest
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `distribution-flavors`, on the `github` build: no trial, no purchase, nothing that says
 * either. Runs only in this flavor's test APK — `play` keeps the trial, and
 * `EntitlementTest` and `PurchaseGateDeviceTest` hold that side.
 *
 * Every screen is reached through the real activity, because the switch is read at the
 * call sites in `MainActivity`: a composable handed `startsTrial = false` proves nothing
 * about what the activity hands it.
 *
 * **Announced test-only state:** this creates a real vault in the app's own `filesDir`
 * (skipping if one is already there), grants `RECORD_AUDIO` so the voice memo screen is
 * in the state that would carry the trial line, and resets the entitlement and
 * onboarding preferences before and after. The vault is removed in [tearDown]; the grant
 * stays, because revoking it kills the instrumentation's process.
 */
@RunWith(AndroidJUnit4::class)
class GithubBuildDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobs get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")

    private var created = false

    private companion object {
        const val PASS_TEXT = "github build passphrase"
        const val DAY_MS = 86_400_000L
    }

    @Before
    fun setUp() {
        assertFalse("this suite is the github build's", BuildConfig.TRIAL)
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        Entitlement.resetForTest(ctx)
        Onboarding.resetForTest(ctx)
        val lifecycle = VaultLifecycle(slots, BlobStore(blobs, incoming))
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        lifecycle.create(PASS_TEXT.toByteArray(Charsets.UTF_8))
        created = true
        lockKeyringAndVault()
        instr.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.RECORD_AUDIO)
    }

    @After
    fun tearDown() {
        lockKeyringAndVault()
        Entitlement.resetForTest(ctx)
        Onboarding.resetForTest(ctx)
        if (!created) return
        MetadataDb.awaitQuiescent()
        slots.delete()
        blobs.deleteRecursively()
        incoming.deleteRecursively()
    }

    // --- harness -------------------------------------------------------------

    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 45_000, condition = condition)
        } catch (e: Throwable) {
            runCatching {
                compose.onAllNodes(isRoot()).fetchSemanticsNodes().forEachIndexed { i, _ ->
                    compose.onAllNodes(isRoot())[i].printToLog("GithubBuild")
                }
            }
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun showing(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun described(text: String) =
        compose.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()

    /** Nodes anywhere in the tree — composed drawers included — that contain [text]. */
    private fun count(text: String) =
        compose.onAllNodes(hasText(text, substring = true, ignoreCase = true)).fetchSemanticsNodes().size

    /** Passphrase in, Unlock pressed. Lands on the vault list, or on a resumed onboarding step. */
    private fun typePassphraseAndUnlock() {
        await("the lock screen") { showing("Welcome back") }
        compose.waitForIdle()
        val field = compose.onNode(hasText("Passphrase"))
        field.performClick()
        compose.waitForIdle()
        field.performTextInput(PASS_TEXT)
        await("the passphrase to register") {
            compose.onAllNodes(hasText("Unlock") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Unlock").performClick()
    }

    // --- entitlement ---------------------------------------------------------

    @Test
    fun addingNeverClosesWithATrialStartedThirtyOneDaysAgo() {
        // The state that closes every add on `play`: started, run out, and Play's "not owned".
        Entitlement.setTrialStartedAtForTest(ctx, System.currentTimeMillis() - 31 * DAY_MS)
        Entitlement.purchasedProbe = { false }
        assertTrue(
            "importing, capturing, recording, creating a vault, building a decoy and sending " +
                "a share all ask this one question, and on github it is always yes",
            Entitlement.importAllowed(ctx),
        )
    }

    @Test
    fun noTrialIsEverRecorded() {
        Entitlement.startTrialIfNeeded(ctx)
        assertEquals("the first add writes no trial start date", 0L, Entitlement.trialStartedAt(ctx))
    }

    @Test
    fun noReminderOnEitherReminderDay() {
        Entitlement.REMINDER_DAYS.forEach { day ->
            Entitlement.setTrialStartedAtForTest(ctx, System.currentTimeMillis() - day * DAY_MS - DAY_MS / 2)
            assertNull("day $day carries no reminder", Entitlement.reminder(ctx))
        }
    }

    @Test
    fun theStubReportsThePurchaseOwned() {
        assertTrue(Billing.hasPurchased())
        assertEquals(true, Billing.owned)
    }

    // --- screens -------------------------------------------------------------

    @Test
    fun neitherDrawerHasAPurchaseRow() {
        ActivityScenario.launch(MainActivity::class.java).use {
            typePassphraseAndUnlock()
            await("the vault list") { described("Menu") }
            compose.onNodeWithContentDescription("Menu").performClick()
            await("the home drawer") { showing("Recover a vault with its kit") }
            compose.waitForIdle()
            assertEquals("the home drawer draws no Purchase row", 0, count("Purchase"))
            assertEquals("and no trial state", 0, count("Trial"))

            it.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            compose.openFirstVaultFromTheList()
            await("the grid") { described("All vaults") }
            compose.onNodeWithContentDescription("Trash and settings").performClick()
            await("the vault's drawer") { showing("Unvault Everything") }
            compose.waitForIdle()
            assertEquals("the vault's drawer draws no Purchase row", 0, count("Purchase"))
            assertEquals("and no trial state", 0, count("Trial"))
        }
    }

    @Test
    fun theVoiceMemoScreenMentionsNoTrial() {
        ActivityScenario.launch(MainActivity::class.java).use {
            typePassphraseAndUnlock()
            compose.openFirstVaultFromTheList()
            await("the grid") { described("All vaults") }
            compose.onNodeWithContentDescription("Actions").performClick()
            await("the actions") { showing("Voice memo") }
            compose.onNodeWithText("Voice memo").performClick()
            // Permitted and nothing ever added: the exact state `play` states the trial in.
            await("the record control") { described("Start recording") }
            compose.waitForIdle()
            assertEquals("the voice memo screen says nothing of a trial", 0, count("trial"))
        }
    }

    @Test
    fun theOnboardingDecoyStepAndTheDecoyIntroMentionNoTrial() {
        // Paused at the decoy step under this keyring, as `advanceOnboarding` leaves it;
        // the unlock below resumes the flow there.
        assertTrue(
            "the keyring opens",
            VaultLifecycle(slots, BlobStore(blobs, incoming)).unlock(PASS_TEXT.toByteArray(Charsets.UTF_8)).opened,
        )
        Onboarding.reached(
            ctx,
            VaultKeyringSession.sealHeld(byteArrayOf(Onboarding.Step.Decoy.ordinal.toByte()))!!,
        )
        lockKeyringAndVault()

        ActivityScenario.launch(MainActivity::class.java).use {
            typePassphraseAndUnlock()
            await("the onboarding decoy step") { showing(ctx.getString(R.string.onboarding_decoy_title)) }
            compose.waitForIdle()
            assertEquals("the onboarding decoy step says nothing of a trial", 0, count("trial"))

            compose.onNodeWithText(ctx.getString(R.string.drawer_set_up_decoy)).performClick()
            await("the decoy intro") { showing(ctx.getString(R.string.decoy_intro_title)) }
            compose.waitForIdle()
            assertEquals("the decoy intro, reached from first run, says nothing of a trial", 0, count("trial"))
        }
    }
}
