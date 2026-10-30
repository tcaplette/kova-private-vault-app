package com.app.vault

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.ACKNOWLEDGEMENT
import org.junit.After
import org.junit.Assert.assertEquals
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
 * ./gradlew :app:connectedPlayDebugAndroidTest \
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

    /** The language screen, then the welcome, then Get started — the doors before step 1. */
    private fun throughTheWelcome() {
        awaitText("System default")
        compose.onNodeWithText("Continue").performClick()
        awaitText("Photos nobody else can open")
        compose.onNodeWithText("Get started").performClick()
        awaitText("Step 1 of 9")
    }

    /** Name and look, passed with a name — `first-vault-naming`. */
    private fun nameTheVault(name: String = "Flow test") {
        compose.onNodeWithText("Name").performTextInput(name)
        compose.onNodeWithText("Continue").performClick()
        awaitText("Step 2 of 9")
    }

    private fun createTheVault() {
        compose.onNodeWithText("Passphrase").performTextInput(PASS)
        compose.onNodeWithText("Enter it again").performTextInput(PASS)
        compose.onNodeWithText("Create vault").performClick()
        // Argon2id plus the install chaff write.
        awaitText("Step 3 of 9", CREATE_TIMEOUT_MS)
    }

    /**
     * The transition that was broken: passphrase entered, vault created, the kit.
     *
     * Asserts the *destination*, not that a button was tappable. The broken build
     * would fail here on the timeout, having redrawn the passphrase step.
     */
    @Test
    fun creatingTheVaultAdvancesToTheRecoveryKit() {
        throughTheWelcome()
        nameTheVault()
        createTheVault()
        assertTrue(
            "creating a vault must land on the recovery kit, not redraw the passphrase",
            exists("Write these down"),
        )
        assertTrue("the kit's own gate must still be there", exists(ctx.getString(ACKNOWLEDGEMENT)))
    }

    /**
     * Continue on the kit is inert until the acknowledgement is typed, and the flow
     * does not skip past a step whose gate has not been satisfied.
     */
    @Test
    fun theKitStepHoldsUntilAcknowledged() {
        throughTheWelcome()
        nameTheVault()
        createTheVault()

        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        assertTrue(
            "an unacknowledged kit must not advance",
            exists("Step 3 of 9"),
        )

    }

    /**
     * `recovery-practice` D5. The kit step holds the check as its second screen: still step 3 of
     * nine (step 2 of the flow's own steps after Name), Back from the check is the words with the
     * acknowledgement kept, and only a passed check moves on.
     */
    @Test
    fun theKitStepEndsWithTheCheckAndStaysOneStep() {
        throughTheWelcome()
        nameTheVault()
        createTheVault()
        val words = KitCheckWalk.wordsOnScreen(compose)
        compose.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextInput(ctx.getString(ACKNOWLEDGEMENT))
        compose.onNodeWithText("Continue").performClick()
        compose.waitUntil(10_000) { KitCheckWalk.showing(compose) }
        assertTrue("the check is part of the kit step", exists("Step 3 of 9"))

        compose.onNodeWithText("Back").performClick()
        compose.waitUntil(10_000) { exists("Write these down") }
        assertTrue("still the kit step", exists("Step 3 of 9"))
        compose.onNodeWithText("Continue").assertIsEnabled()
        compose.onNodeWithText("Continue").performClick()

        KitCheckWalk.pass(compose, words)
        awaitText("Step 4 of 9")
        assertTrue("still nine steps", exists("of 9"))
    }

    /** The welcome is the door, and it is not counted as a step. */
    @Test
    fun theWelcomeIsNotAStep() {
        awaitText("System default")
        compose.onNodeWithText("Continue").performClick()
        awaitText("Photos nobody else can open")
        assertTrue(
            "the welcome must not occupy a place in the nine",
            !exists("Step 1 of 9"),
        )
        compose.onNodeWithText("Get started").performClick()
        awaitText("Step 1 of 9")
    }

    /**
     * `first-vault-naming`. Name and look comes first, refuses an empty name under the
     * field, keeps the name, colour and icon across Back from the passphrase step and
     * across Back to the welcome, and the vault is created as chosen.
     */
    @Test
    fun theFirstVaultIsNamedAndKeepsItsLookAcrossBack() {
        throughTheWelcome()
        awaitText("Name your vault")

        // Empty: refused, under the field, and the step stays.
        compose.onNodeWithText("Continue").performClick()
        awaitText("Give the vault a name.")
        assertTrue("an empty name must not leave the step", exists("Step 1 of 9"))

        compose.onNodeWithText("Name").performTextInput("Family")
        compose.onNodeWithContentDescription("Teal").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Home").performScrollTo().performClick()
        compose.onNodeWithText("Continue").performClick()
        awaitText("Step 2 of 9")

        // Back from the passphrase step: all three kept.
        compose.onNodeWithText("Back").performClick()
        awaitText("Step 1 of 9")
        compose.onNode(hasSetTextAction()).assertTextContains("Family")
        compose.onNodeWithContentDescription("Teal").assertIsSelected()
        compose.onNodeWithContentDescription("Home").assertIsSelected()

        // Back to the welcome and in again: still kept.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitText("Photos nobody else can open")
        compose.onNodeWithText("Get started").performClick()
        awaitText("Step 1 of 9")
        compose.onNode(hasSetTextAction()).assertTextContains("Family")
        compose.onNodeWithContentDescription("Teal").assertIsSelected()
        compose.onNodeWithContentDescription("Home").assertIsSelected()

        compose.onNodeWithText("Continue").performClick()
        awaitText("Step 2 of 9")
        createTheVault()

        // Home is the first glyph, stored as 1.
        val chosen = VaultAppearance(FolderColour.Teal, 1)
        val entry = VaultKeyringSession.vaults().single()
        assertEquals("Family", entry.name)
        assertEquals(chosen, VaultAppearance.of(entry.appearance))

        // On the list: the flow finished and the keyring reopened at the lock screen.
        VaultLifecycle.lockNow()
        Onboarding.finish(ctx)
        compose.activityRule.scenario.recreate()
        compose.onNode(hasSetTextAction()).performTextInput(PASS)
        compose.onNodeWithText("Unlock").performClick()
        compose.waitUntil(CREATE_TIMEOUT_MS) {
            compose.onAllNodes(hasContentDescription("New vault")).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("the list must show the vault under its chosen name", exists("Family"))
        assertEquals(
            "the list draws the badge from the entry",
            chosen, VaultAppearance.of(VaultKeyringSession.vaults().single().appearance),
        )
    }
}
