package com.app.vault

import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.vault.ui.DecoyAuthSheet
import com.app.vault.ui.DecoyBuildStep
import com.app.vault.ui.DecoyFrame
import com.app.vault.ui.DecoyHandoverStep
import com.app.vault.ui.DecoyIdentityStep
import com.app.vault.ui.DecoyIntroStep
import com.app.vault.ui.DecoyPhotosStep
import com.app.vault.ui.DecoyPreserveSheet
import com.app.vault.ui.DecoySummary
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The decoy flow can be walked, and says the true things on the way —
 * `decoy-flow-overhaul`.
 *
 * **This class exists because the screen it replaces was wrong in ways review kept
 * missing.** `FLAG_SECURE` makes the app unscreenshottable, so nobody can eyeball
 * these screens; every structural claim about them has to be a test or it is not
 * checked at all. The claims here are the ones whose absence was the defect:
 *
 *  - the overwrite warning does not stand over a user who has not asked about other
 *    vaults, because for them it is not true;
 *  - the question about other vaults is plural and is answered by something plural;
 *  - the authorisation asks for authorisation, in those words, and not for
 *    preservation;
 *  - every gate that fails says what to do about it;
 *  - the flow says where the user is, from the first step;
 *  - the handover step says the switch happened.
 */
@RunWith(AndroidJUnit4::class)
class DecoyFlowScreenTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun screen(content: @Composable () -> Unit, assertions: () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { VaultTheme { content() } } }
            compose.waitForIdle()
            assertions()
        }
    }

    private fun count(text: String): Int =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    private fun summary(items: Int, weeks: Long, installDays: Long = 200) = DecoySummary(
        chosen = items,
        kept = items,
        assigned = 0,
        declined = 0,
        spreadWeeks = weeks,
        outsideWindow = true,
        installDays = installDays,
        truncated = false,
    )

    private fun ready() = summary(Decoy.MIN_ITEMS, Decoy.MIN_SPREAD_WEEKS.toLong())

    // --- where the user is ----------------------------------------------------

    @Test
    fun everyStepSaysWhichOneItIs() {
        DecoyFlow.Step.entries.forEach { step ->
            screen({
                DecoyFrame(step = step, onExit = {}, onBack = null) { }
            }) {
                assertTrue(
                    "step ${step.name} does not say where the user is",
                    count("Step ${step.position} of ${DecoyFlow.TOTAL}") > 0,
                )
            }
        }
    }

    @Test
    fun theFirstStepCarriesTheCounterToo() {
        // The rail appearing part-full on a flow the user thinks has just started is
        // `OnboardingFrame`'s recorded bug. It is not repeated here.
        assertEquals(1, DecoyFlow.Step.Intro.position)
        assertFalse("nothing is behind the first step", DecoyFlow.canGoBack(DecoyFlow.Step.Intro))
        assertFalse(
            "the vault exists by the handover, so there is nothing to go back to",
            DecoyFlow.canGoBack(DecoyFlow.Step.Handover),
        )
    }

    // --- the gates ------------------------------------------------------------

    @Test
    fun aFailingGateSaysWhatToDoAboutIt() {
        screen({
            DecoyPhotosStep(
                summary = summary(items = 4, weeks = 1),
                declineReason = null,
                onPickItems = {},
                onContinue = {},
            )
        }) {
            assertTrue("the shortfall is named", count("Not enough items") > 0)
            assertTrue(
                "and so is the way out of it — a count alone is a dead end",
                count("add at least") > 0,
            )
            assertTrue("the spread shortfall is named", count("week and") > 0)
            assertTrue(
                "with its own recovery path, which is not the same as the other one",
                count("add older photographs") > 0,
            )
        }
    }

    @Test
    fun aPassingGateAlsoSaysSoInWords() {
        // §6.4: the state must survive being read with colour disregarded, in both
        // directions. A gate that only speaks when it fails is a gate that leaves the
        // passing case carried by a tint.
        screen({
            DecoyPhotosStep(ready(), null, {}, {})
        }) {
            assertTrue(count("Enough items") > 0)
            assertTrue(count("is the minimum") > 0)
        }
    }

    @Test
    fun theStepCannotBeLeftUntilTheSetIsPlausible() {
        var advanced = 0
        screen({ DecoyPhotosStep(summary(4, 1), null, {}, { advanced++ }) }) {
            compose.onNodeWithText("Not a plausible set yet").performClick()
            compose.waitForIdle()
        }
        assertEquals("a disabled action must not advance", 0, advanced)

        screen({ DecoyPhotosStep(ready(), null, {}, { advanced++ }) }) {
            compose.onNodeWithText("Continue").performClick()
            compose.waitForIdle()
        }
        assertEquals("a plausible set advances", 1, advanced)
    }

    // --- the name -------------------------------------------------------------

    @Test
    fun theNameFieldStartsEmptyAndABlankNameIsRefused() {
        var name by mutableStateOf("")
        var advanced = 0

        screen({
            DecoyIdentityStep(
                name = name,
                onName = { name = it },
                onContinue = { advanced++; it.fill(0) },
            )
        }) {
            assertEquals("the flow proposes no name", "", name)
            compose.onNodeWithText("Continue").performClick()
            compose.waitForIdle()
            assertTrue(
                "the same refusal an ordinary vault creation gives",
                count("Give the vault a name.") > 0,
            )
        }
        assertEquals("nothing advances on a blank name", 0, advanced)
    }

    @Test
    fun aNamedVaultWithAMatchingPassphraseAdvances() {
        var name by mutableStateOf("")
        var carried: ByteArray? = null

        screen({
            DecoyIdentityStep(name = name, onName = { name = it }, onContinue = { carried = it })
        }) {
            compose.onNodeWithText("Name").performTextInput("Holiday photos")
            compose.onNodeWithText("Passphrase for the decoy").performTextInput("a-good-secret-phrase")
            compose.onNodeWithText("Enter it again").performTextInput("a-good-secret-phrase")
            compose.waitForIdle()
            compose.onNodeWithText("Continue").performClick()
            compose.waitForIdle()
        }

        assertEquals("Holiday photos", name)
        assertTrue(
            "the passphrase crosses as bytes the caller can zero, never as a String",
            carried?.toString(Charsets.UTF_8) == "a-good-secret-phrase",
        )
        carried?.fill(0)
    }

    @Test
    fun twoEntriesThatDisagreeAreNamedAsTheProblem() {
        var name by mutableStateOf("Holiday photos")
        var advanced = 0

        screen({
            DecoyIdentityStep(name = name, onName = { name = it }, onContinue = { advanced++ })
        }) {
            compose.onNodeWithText("Passphrase for the decoy").performTextInput("a-good-secret-phrase")
            compose.onNodeWithText("Enter it again").performTextInput("a-different-one")
            compose.waitForIdle()
            compose.onNodeWithText("Continue").performClick()
            compose.waitForIdle()
            assertTrue(count("The two entries do not match.") > 0)
        }
        assertEquals(0, advanced)
    }

    // --- the strength floor, `passphrase-strength-meter` ------------------------

    /**
     * The decoy's passphrase is held to the same floor as every other. A decoy whose
     * passphrase is weaker than a real vault's is a decoy that can be told from one.
     */
    @Test
    fun aGuessableDecoyPassphraseDoesNotAdvance() {
        var name by mutableStateOf("Holiday photos")
        var advanced = 0

        screen({
            DecoyIdentityStep(name = name, onName = { name = it }, onContinue = { advanced++ })
        }) {
            compose.onNodeWithText("Passphrase for the decoy").performTextInput("passwordpassword")
            compose.onNodeWithText("Enter it again").performTextInput("passwordpassword")
            compose.waitForIdle()
            compose.onNodeWithText("Continue").performClick()
            compose.waitForIdle()
            assertTrue("the refusal names the strength", count(ctx.getString(R.string.passphrase_too_weak)) > 0)
        }
        assertEquals("a guessable decoy passphrase must not advance", 0, advanced)
    }

    /**
     * D8: the decoy is named on this same step, and its own name must not be usable as the
     * passphrase that opens it.
     */
    @Test
    fun theDecoysOwnNameCannotBeItsPassphrase() {
        var name by mutableStateOf("")
        var advanced = 0

        screen({
            DecoyIdentityStep(name = name, onName = { name = it }, onContinue = { advanced++ })
        }) {
            compose.onNodeWithText("Name").performTextInput("Marmalade")
            val built = "marmalade marmalade marmalade"
            compose.onNodeWithText("Passphrase for the decoy").performTextInput(built)
            compose.onNodeWithText("Enter it again").performTextInput(built)
            compose.waitForIdle()
            compose.onNodeWithText("Continue").performClick()
            compose.waitForIdle()
        }
        assertEquals("a decoy must not open to its own name", 0, advanced)
    }

    /** The floor is on the field before anything is typed, not discovered by being refused. */
    @Test
    fun theFloorIsStatedOnTheDecoyPassphraseField() {
        var name by mutableStateOf("Holiday photos")
        screen({
            DecoyIdentityStep(name = name, onName = { name = it }, onContinue = {})
        }) {
            assertTrue(count(ctx.getString(R.string.passphrase_requirement, PassphraseStrength.MIN_CODE_POINTS)) > 0)
        }
    }

    // --- the warning, and where it is allowed to be ---------------------------

    /**
     * The defect this whole change exists for.
     *
     * The old screen stood "any vault you do not enter may be overwritten" above a
     * field every user saw, describing a risk to the vault they had open — which
     * `VaultLifecycle.create` now makes structurally impossible, and which was
     * already impossible in practice. It is true only of a second sealed slot, so it
     * appears only where the user is being asked about one.
     */
    @Test
    fun theBuildStepCarriesNoOverwriteWarning() {
        screen({
            DecoyBuildStep(
                name = "Holiday photos",
                summary = ready(),
                preserveCount = 0,
                busy = false,
                progress = null,
                problem = null,
                onOpenPreserve = {},
                onAuthorise = {},
            )
        }) {
            assertEquals(
                "no user is told their open vault may be overwritten — it may not",
                0,
                count("may be overwritten"),
            )
            assertTrue(
                "and the screen says so outright",
                count("cannot be overwritten by this") > 0,
            )
            assertTrue(
                "the offer to name other vaults is present but closed",
                count("I have other vaults on this device") > 0,
            )
        }
    }

    @Test
    fun theWarningLivesInsideTheOfferThatMakesItTrue() {
        screen({
            DecoyPreserveSheet(count = 0, onAdd = {}, onRemove = {}, onDone = {})
        }) {
            assertTrue(count("may be overwritten") > 0)
            assertTrue(
                "and it says which vaults it is about",
                count("Vaults on your own list are not this") > 0,
            )
        }
    }

    @Test
    fun theOfferIsPluralAndSoIsWhatAnswersIt() {
        val entries = mutableStateListOf<ByteArray>()

        screen({
            DecoyPreserveSheet(
                count = entries.size,
                onAdd = { entries += it },
                onRemove = { entries.removeAt(it) },
                onDone = {},
            )
        }) {
            // Through the button's click action once it is enabled, not a tap at its coordinates:
            // the first entry raises the keyboard, the sheet's `imePadding` moves the button while
            // the inset animates, and a tap fetched before that lands where the button was.
            listOf("one-vault", "two-vault").forEach { typed ->
                compose.onNodeWithText("Passphrase of a vault to keep").performTextInput(typed)
                val add = hasText("Add to the list") and isEnabled()
                compose.waitUntil(5_000) { compose.onAllNodes(add).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(add).performSemanticsAction(SemanticsActions.OnClick)
                compose.waitForIdle()
            }

            assertEquals("each entry is its own row", 2, entries.size)
            assertTrue("row one is visible", count("Passphrase 1") > 0)
            assertTrue("row two is visible", count("Passphrase 2") > 0)

            // Silence: a row may not say whether its entry opened anything, because
            // "that one worked" is an occupancy oracle for whoever holds one
            // passphrase and this device.
            assertEquals(0, count("opened"))
            assertEquals(0, count("did not match"))

            // By description, not by label: every row's button says "Remove", which
            // is right for the screen and ambiguous for a test.
            compose.onNodeWithContentDescription("Remove passphrase 2").performClick()
            compose.waitForIdle()
            assertEquals("an entry can be taken back off the list", 1, entries.size)
        }
        entries.forEach { it.fill(0) }
    }

    // --- authorisation, asked as authorisation --------------------------------

    @Test
    fun theSheetAsksToConfirmItIsYouAndNotToPreserveAnything() {
        screen({
            DecoyAuthSheet(onConfirm = { it.fill(0) }, onCancel = {}, busy = false, failed = false)
        }) {
            assertTrue(count("Confirm it is you") > 0)
            assertTrue(count("This authorises building the decoy") > 0)
            assertEquals(
                "nothing here asks the user to keep anything — that is the other sheet",
                0,
                count("want to keep"),
            )
            assertEquals(0, count("may be overwritten"))
        }
    }

    // --- the handover ---------------------------------------------------------

    @Test
    fun theLastStepSaysWhereTheUserIsAndWhereTheyGoNext() {
        screen({
            DecoyHandoverStep(name = "Holiday photos", itemCount = 40, onShowKit = {})
        }) {
            assertTrue("the decoy is shown under its own name", count("Holiday photos") > 0)
            assertTrue(
                "the user is told they are looking at it as the other person would",
                count("as they would") > 0,
            )
            assertTrue(
                "and that they are returned to their own vault, which the app now does " +
                    "rather than leaving them stranded in the decoy",
                count("your own opens again") > 0,
            )
            assertTrue("the words are offered as the decoy's", count("recovery words") > 0)
        }
    }
}
