package com.app.vault

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.ACKNOWLEDGEMENT
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The flow moves between its steps in the real activity — `first-run-onboarding`.
 *
 * **`OnboardingStepScreenTest` was not enough, and this class is why.** That one
 * composes each step in isolation and proves each has a working Continue. Every
 * one of those tests passed while the flow was, in the real app, **unable to get
 * off step 1**: creating the vault set the recovery kit but never advanced the
 * step, and the kit's own branch had been told to stand down during first run — so
 * the passphrase screen simply redrew itself with empty fields.
 *
 * A screen that works in isolation and a flow that works are different claims. The
 * seam between two steps is where this broke, so the seam is what this tests: the
 * real `MainActivity`, real vault creation, real state.
 *
 * These are slow — each one runs Argon2id at full parameters — so there are few of
 * them and each covers a transition nothing else can see.
 *
 * **DESTRUCTIVE, and therefore opt-in.** Driving the real activity means using the
 * real `filesDir`: there is no sandbox subdirectory to retreat into, because the
 * activity does not know about one. Testing a *first* run means arriving with no
 * vault, so this deletes `vault.slots`, empties `blobs/` and clears the onboarding
 * flag — on the actual install. Every other test in this suite sandboxes; this one
 * cannot, and it silently destroyed a vault someone was using between manual
 * checks, which is how the requirement below was learnt.
 *
 * So it runs only when asked for by name:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.destructive=true
 * ```
 *
 * Without that argument every test here is skipped, so a full-suite run leaves the
 * device's vault alone.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val PASS = "onboarding-flow-test-passphrase"
        /** Vault creation writes install chaff, so this is not a fast operation. */
        const val CREATE_TIMEOUT_MS = 120_000L
    }

    /**
     * Empties the store **without removing the directories**.
     *
     * `BlobStore` calls `mkdirs()` once, at construction, and the application's
     * instance is already live by the time this runs — the compose rule launches
     * the activity before `@Before`. Deleting `blobs/` and `incoming/` outright
     * leaves that instance writing into paths that no longer exist, which surfaces
     * as `IllegalStateException: could not move blob into place` from a create that
     * should have worked. Clearing the contents leaves the store usable.
     */
    private fun emptyStore() {
        File(ctx.filesDir, "vault.slots").delete()
        listOf("blobs", "incoming").forEach { name ->
            File(ctx.filesDir, name).listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    @Before
    fun setUp() {
        // Before anything is deleted. `assumeTrue` skips rather than fails, so a
        // routine full-suite run reports these as skipped and touches nothing.
        assumeTrue(
            "OnboardingFlowTest wipes the install's vault. Pass " +
                "-Pandroid.testInstrumentationRunnerArguments.destructive=true to run it.",
            InstrumentationRegistry.getArguments().getString("destructive") == "true",
        )
        VaultLifecycle.lockNow()
        Onboarding.resetForTest(ctx)
        emptyStore()
        // The activity read its state before this method ran, so it is holding the
        // previous test's. Recreating makes it read what was just reset.
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }

    @After
    fun tearDown() {
        if (InstrumentationRegistry.getArguments().getString("destructive") != "true") return
        VaultLifecycle.lockNow()
        Onboarding.resetForTest(ctx)
        emptyStore()
    }

    private fun exists(text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun awaitText(text: String, timeoutMs: Long = 10_000L) {
        compose.waitUntil(timeoutMs) { exists(text) }
    }

    /**
     * The transition that was broken: passphrase entered, vault created, step 2.
     *
     * Asserts the *destination*, not that a button was tappable. The broken build
     * would fail here on the timeout, having redrawn step 1.
     */
    @Test
    fun creatingTheVaultAdvancesToTheRecoveryKit() {
        // The welcome comes before the counter starts.
        awaitText("Photos nobody else can open")
        compose.onNodeWithText("Get started").performClick()

        awaitText("Step 1 of 8")
        compose.onNodeWithText("Passphrase").performTextInput(PASS)
        compose.onNodeWithText("Enter it again").performTextInput(PASS)
        compose.onNodeWithText("Create vault").performClick()

        // Argon2id plus the install chaff write.
        awaitText("Step 2 of 8", CREATE_TIMEOUT_MS)
        assertTrue(
            "creating a vault must land on the recovery kit, not redraw the passphrase",
            exists("Write these down"),
        )
        assertTrue("the kit's own gate must still be there", exists(ctx.getString(ACKNOWLEDGEMENT)))
    }

    /**
     * Continue on step 2 is inert until the acknowledgement is typed, and the flow
     * does not skip past a step whose gate has not been satisfied.
     */
    @Test
    fun theKitStepHoldsUntilAcknowledged() {
        awaitText("Photos nobody else can open")
        compose.onNodeWithText("Get started").performClick()
        awaitText("Step 1 of 8")
        compose.onNodeWithText("Passphrase").performTextInput(PASS)
        compose.onNodeWithText("Enter it again").performTextInput(PASS)
        compose.onNodeWithText("Create vault").performClick()
        awaitText("Step 2 of 8", CREATE_TIMEOUT_MS)

        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        assertTrue(
            "an unacknowledged kit must not advance",
            exists("Step 2 of 8"),
        )

    }

    /** The welcome is the door, and it is not counted as a step. */
    @Test
    fun theWelcomeIsNotAStep() {
        awaitText("Photos nobody else can open")
        assertTrue(
            "the welcome must not occupy a place in the eight",
            !exists("Step 1 of 8"),
        )
        compose.onNodeWithText("Get started").performClick()
        awaitText("Step 1 of 8")
    }
}
