package com.app.vault

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import com.app.vault.ui.HOLD_DIAGRAM_TAG
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.CalculatorCover
import com.app.vault.ui.COVER_HOST_TAG
import com.app.vault.ui.IdentityScreen
import com.app.vault.ui.DRAW_PATTERN
import com.app.vault.ui.WRONG_PATTERN
import com.app.vault.ui.PATTERN_GRID_TAG
import com.app.vault.ui.IdentitySetupScreen
import com.app.vault.ui.MusicCover
import com.app.vault.ui.RecorderCover
import com.app.vault.ui.theme.VaultTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The covers as screens — P2-24.
 *
 * The claim being tested is the one a cover lives or dies by: **mashing every control
 * reaches nothing.** A curious person with the phone for a minute will tap and hold
 * everything on screen, and if any of that produces a flicker, a buzz, or an unlock
 * prompt, the cover has told them what they wanted to know.
 *
 * **The host is the bare `ComponentActivity` from `src/debug/`**, never
 * `MainActivity` — P2-23's finding applied rather than rediscovered: the real
 * activity's `onCreate` reads the biometric wrap, and repeated launches left a
 * Keystore alias that broke `BiometricWrapTest` in the full suite while every class
 * passed alone.
 */
@RunWith(AndroidJUnit4::class)
class CoverScreenTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val trigger = Trigger.Keys(listOf("7", "3", "sin"))
    private var before: Trigger? = null

    /** Every trigger the screen offered, in order. */
    private val offered = mutableListOf<Trigger>()

    /** A bound on the mashing loop, so a screen that grows controls cannot hang it. */
    private val MASH_LIMIT = 60

    @Before
    fun setUp() {
        before = CoverTrigger.triggerFor(ctx, Cover.Calculator)
        CoverTrigger.set(ctx, Cover.Calculator, trigger)
        CoverTrigger.clearAttempts(ctx, Cover.Calculator)
        offered.clear()
        completed.clear()
        cancelled = 0
        completedHold.clear()
        completedDots.clear()
        holdConfirmed = 0
    }

    @After
    fun tearDown() {
        before.let { if (it == null) CoverTrigger.clear(ctx, Cover.Calculator) else CoverTrigger.set(ctx, Cover.Calculator, it) }
        CoverTrigger.clearAttempts(ctx, Cover.Calculator)
    }

    /**
     * Tap and hold every control on screen, in turn.
     *
     * The node list is re-read on every step rather than counted once, because a
     * control that opens a pane changes the tree underneath the loop — and a mashing
     * test that stops at the first such control would be asserting silence about a
     * screen it never reached.
     */
    private fun mashEveryControl() {
        var i = 0
        while (i < MASH_LIMIT) {
            val nodes = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            if (i >= nodes.size) break
            compose.onAllNodes(hasClickAction())[i].performClick()
            compose.waitForIdle()
            if (i < compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size) {
                compose.onAllNodes(hasClickAction())[i].performTouchInput { longClick() }
                compose.waitForIdle()
            }
            i++
        }
    }

    /** Compose a cover in the bare host and run [assertions] against it. */
    private fun withCover(content: @Composable () -> Unit, assertions: () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { VaultTheme { content() } } }
            compose.waitForIdle()
            assertions()
        }
    }

    // --- 11.4: mashing reaches nothing ---------------------------------------

    /**
     * Every control in the calculator, tapped and then long-pressed in turn.
     *
     * The only gesture that may offer a trigger at all is a long-press on `=`, and
     * even that offers whatever was typed — which, after mashing, is not the stored
     * sequence. So **no attempt may be accepted**, and the screen must behave as an
     * ordinary calculator throughout.
     */
    @Test
    fun mashingEveryCalculatorControlReachesNothing() {
        withCover({ CalculatorCover(onTrigger = { offered += it }) }) {
            val keys = compose.onAllNodes(hasClickAction())
            val count = keys.fetchSemanticsNodes().size
            assertTrue("the calculator drew no controls", count > 20)

            repeat(count) { i ->
                compose.onAllNodes(hasClickAction())[i].performClick()
                compose.onAllNodes(hasClickAction())[i].performTouchInput { longClick() }
            }
            compose.waitForIdle()

            // Whatever was offered, none of it opens anything.
            offered.forEach {
                assertEquals(
                    "mashing produced an accepted trigger: $it",
                    Outcome.Rejected,
                    CoverTrigger.attempt(ctx, Cover.Calculator, it),
                )
            }
        }
    }

    /**
     * Wrong patterns, drawn deliberately — `pattern-cover-trigger`. Mashing taps and
     * holds no longer exercises the trigger mechanism at all, so it is joined by the
     * gesture that does: a handful of five-plus-node shapes that are not the stored
     * one, plus short drags that never reach the floor and so are never even offered.
     */
    private fun mashThePatternGridToo(target: SemanticsNodeInteraction) {
        listOf(
            intArrayOf(1, 3, 5, 7, 0),
            intArrayOf(8, 7, 6, 3, 0, 1, 2),
            intArrayOf(2, 4, 6, 0, 8),
        ).forEach { performPattern(target, *it) }
        // Below the floor: never offered at all, by design — see `capture`'s doc in
        // `IdentitySetupScreen`, the same reasoning applied to a live cover.
        listOf(intArrayOf(0, 1), intArrayOf(4, 5, 6)).forEach { performPattern(target, *it) }
    }

    /** And the voice recorder, the same way. */
    @Test
    fun mashingEveryRecorderControlReachesNothing() {
        CoverTrigger.set(ctx, Cover.VoiceRecorder, Trigger.Pattern(listOf(0, 4, 8, 2, 6)), area = 0)
        // `persist = false`: mashing must not leave a recording on the developer's own
        // device, and what is being asserted is the silence, not the file.
        withCover({
            RecorderCover(
                hasPermission = false,
                onTrigger = { offered += it },
                persist = false,
            )
        }) {
            val nodes = compose.onAllNodes(hasClickAction())
            repeat(nodes.fetchSemanticsNodes().size) { i ->
                compose.onAllNodes(hasClickAction())[i].performClick()
                compose.onAllNodes(hasClickAction())[i].performTouchInput { longClick() }
            }
            mashThePatternGridToo(coverRoot())
            compose.waitForIdle()

            offered.forEach {
                assertEquals(
                    "mashing produced an accepted trigger: $it",
                    Outcome.Rejected,
                    CoverTrigger.attempt(ctx, Cover.VoiceRecorder, it),
                )
            }
        }
        CoverTrigger.clear(ctx, Cover.VoiceRecorder)
    }

    /** And the music player. */
    @Test
    fun mashingEveryMusicControlReachesNothing() {
        CoverTrigger.set(
            ctx, Cover.MusicPlayer,
            Trigger.Pattern(listOf(0, 4, 8, 2, 6)),
            area = 0,
        )
        withCover({
            MusicCover(
                hasAccess = false,
                onTrigger = { offered += it },
                persist = false,
            )
        }) {
            mashEveryControl()
            mashThePatternGridToo(coverRoot())

            offered.forEach {
                assertEquals(
                    "mashing produced an accepted trigger: $it",
                    Outcome.Rejected,
                    CoverTrigger.attempt(ctx, Cover.MusicPlayer, it),
                )
            }
        }
        CoverTrigger.clear(ctx, Cover.MusicPlayer)
    }

    /**
     * ***The trigger's controls are on screen even with no access and no music.***
     *
     * Android revokes permissions from apps nobody has opened for months. A player
     * that drew its transport only when it had a library would, on that day, become a
     * cover whose owner could not reach their own vault — so the player bar and the
     * artwork are drawn in every state this screen has, and this is the assertion that
     * says so.
     */
    @Test
    fun theTriggersControlsSurviveNoAccessAndNoLibrary() {
        withCover({
            MusicCover(
                hasAccess = false,
                onTrigger = { offered += it },
                persist = false,
            )
        }) {
            assertTrue("the player bar is gone with no access", showing("Nothing playing"))
            compose.onAllNodes(hasText("Nothing playing"))[0].performClick()
            compose.waitForIdle()
            listOf("Previous", "Next", "Play", "Shuffle off", "Repeat off").forEach { control ->
                assertTrue(
                    "$control is not reachable with no access",
                    compose.onAllNodesWithContentDescription(control).fetchSemanticsNodes().isNotEmpty(),
                )
            }
        }
    }

    /**
     * ***A cover without its grant offers no way to ask for it.***
     *
     * The recorder's record control used to raise the system permission dialog, which
     * reads the **application** label — so pressing record, the first thing anybody
     * presses, put "Allow Vault to record audio?" in front of whoever was holding the
     * phone. The music player had an "Allow access" button that did the same.
     *
     * The callback is gone from both signatures, so the compiler now carries most of
     * this. What the compiler cannot carry is the copy and the affordance: a button
     * reading "Allow access" could be added back tomorrow, wired to anything. This
     * fails if one appears, and fails if the recovery path stops being stated — an
     * empty state that names a problem and no fix is the other way to get this wrong.
     */
    @Test
    fun neitherCoverOffersAWayToRequestItsGrant() {
        withCover({ RecorderCover(hasPermission = false, onTrigger = { offered += it }, persist = false) }) {
            assertTrue("the recorder does not say the microphone is off", showing("Microphone access is off"))
            assertTrue("the recorder does not name the fix", showing("Turn on Microphone in Settings"))
            assertFalse("the recorder still offers to ask", showing("Allow access"))
        }
        withCover({ MusicCover(hasAccess = false, onTrigger = { offered += it }, persist = false) }) {
            assertTrue("the player does not say music access is off", showing("Music access is off"))
            assertTrue("the player does not name the fix", showing("Turn on Music and audio in Settings"))
            assertFalse("the player still offers to ask", showing("Allow access"))
        }
    }

    // --- 11.3: the correct trigger, and only it ------------------------------

    /**
     * The stored sequence followed by a long-press on `=` is offered and accepted;
     * the same sequence with a plain tap on `=` is not offered at all.
     *
     * **A hold that falls short computes**, which is the property that makes the
     * trigger invisible: the control does what a calculator's `=` does.
     */
    @Test
    fun theStoredSequenceFollowedByAHoldOnEqualsIsAccepted() {
        withCover({ CalculatorCover(onTrigger = { offered += it }) }) {
            listOf("7", "3", "sin").forEach { symbol ->
                compose.onAllNodes(hasText(symbol))[0].performClick()
            }
            compose.waitForIdle()
            assertTrue("a tap on = must not offer a trigger", offered.isEmpty())

            compose.onAllNodes(hasText("="))[0].performTouchInput { longClick(durationMillis = 2_500) }
            compose.waitForIdle()

            assertEquals("the hold offered nothing", 1, offered.size)
            assertEquals(
                "the stored trigger was not accepted",
                Outcome.Accepted,
                CoverTrigger.attempt(ctx, Cover.Calculator, offered.single()),
            )
        }
    }

    /**
     * ***A trigger containing `DEL` records and then matches*** — `calculator-cover-aesthetic`.
     *
     * The backspace is a keystroke like any other: it goes into the sequence rather
     * than erasing from it. A `DEL` that removed the last recorded symbol would make
     * every trigger containing one unenterable, and the person who set it would have
     * no way to find out why — a cover cannot tell them.
     */
    @Test
    fun aTriggerContainingTheBackspaceRecordsAndMatches() {
        CoverTrigger.set(ctx, Cover.Calculator, Trigger.Keys(listOf("4", "DEL", "9")))
        CoverTrigger.clearAttempts(ctx, Cover.Calculator)
        withCover({ CalculatorCover(onTrigger = { offered += it }) }) {
            listOf("4", "DEL", "9").forEach { symbol ->
                compose.onAllNodes(hasText(symbol))[0].performClick()
            }
            compose.waitForIdle()
            compose.onAllNodes(hasText("="))[0].performTouchInput { longClick(durationMillis = 2_500) }
            compose.waitForIdle()

            assertEquals("the hold offered nothing", 1, offered.size)
            assertEquals(
                "a trigger containing DEL was not accepted",
                Outcome.Accepted,
                CoverTrigger.attempt(ctx, Cover.Calculator, offered.single()),
            )
        }
    }

    /**
     * ***`AC` takes a wrong key back*** — `calculator-ac-clears-sequence`. The match is
     * exact, so before this a single stray key left the trigger unenterable until the
     * cover was closed and reopened.
     */
    @Test
    fun aWrongKeyClearedWithACThenTheSequenceIsAccepted() {
        withCover({ CalculatorCover(onTrigger = { offered += it }) }) {
            tapKeys("9", "AC")
            performKeys("7", "3", "sin")

            assertEquals("the hold offered nothing", 1, offered.size)
            assertEquals(
                "the sequence after AC was not accepted",
                Outcome.Accepted,
                CoverTrigger.attempt(ctx, Cover.Calculator, offered.single()),
            )
        }
    }

    /** Ordinary arithmetic, then `AC`, then the trigger — the order anybody would use. */
    @Test
    fun aSumClearedWithACThenTheSequenceIsAccepted() {
        withCover({ CalculatorCover(onTrigger = { offered += it }) }) {
            tapKeys("1", "+", "5", "=", "AC")
            performKeys("7", "3", "sin")

            assertEquals("the hold offered nothing", 1, offered.size)
            assertEquals(
                "the sequence after a sum and AC was not accepted",
                Outcome.Accepted,
                CoverTrigger.attempt(ctx, Cover.Calculator, offered.single()),
            )
        }
    }

    /** `AC` is never in what is offered: only the keys after the last one are. */
    @Test
    fun theClearIsNeverPartOfTheOfferedSequence() {
        withCover({ CalculatorCover(onTrigger = { offered += it }) }) {
            tapKeys("7", "AC", "8", "AC")
            performKeys("4", "5", "6")

            assertEquals("the hold offered nothing", 1, offered.size)
            assertEquals(Trigger.Keys(listOf("4", "5", "6")), offered.single())
        }
    }

    /**
     * ***The hold lives on `=` and on nothing else.*** Every other key is held for
     * longer than the trigger's own duration and offers nothing at all — no toast, no
     * buzz, no attempt recorded.
     */
    @Test
    fun holdingAnyKeyOtherThanEqualsOffersNothing() {
        withCover({ CalculatorCover(onTrigger = { offered += it }) }) {
            listOf("7", "sin", "AC", "DEL", "(", "%", "DEG", "INV").forEach { label ->
                compose.onAllNodes(hasText(label))[0]
                    .performTouchInput { longClick(durationMillis = 2_500) }
            }
            compose.waitForIdle()
            assertTrue("a hold away from = offered a trigger: $offered", offered.isEmpty())
        }
    }

    /**
     * The tell `calculator-cover-aesthetic` exists to remove, asserted on a device:
     * `1 + 2` must read `1 + 2`, not `2`.
     */
    @Test
    fun theExpressionIsOnScreenWhileItIsBeingTyped() {
        withCover({ CalculatorCover(onTrigger = { offered += it }) }) {
            // `9 + 8` rather than `1 + 2`: the running result must be a number that is
            // not also a key, or the assertion matches the keypad instead of the
            // readout and proves nothing about either.
            listOf("9", "+", "8").forEach { label ->
                compose.onAllNodes(hasText(label))[0].performClick()
            }
            compose.waitForIdle()
            compose.onNodeWithText("9 + 8").assertIsDisplayed()
            compose.onNodeWithText("17").assertIsDisplayed()
        }
    }

    // --- setup: record, then confirm -----------------------------------------

    /** Every completed setup the flow reported, exactly as `MainActivity` sees them. */
    private val completed = mutableListOf<Trigger?>()

    private var cancelled = 0

    /** `cover-hold-dots`. The hold area each completed setup reported, parallel to [completed]. */
    private val completedHold = mutableListOf<Int?>()

    /** The second-stage pattern each completion reported — `calculator-dots-methods` D6. */
    private val completedDots = mutableListOf<Trigger.Pattern?>()

    /** How many times a hold step asked the activity for its confirm haptic. */
    private var holdConfirmed = 0

    /** The identity [withSetup] is hosting, for [startRecording] to know what it opens on. */
    private var hosting: LauncherIdentity? = null

    /**
     * The flow as `MainActivity` hosts it: `onComplete` stores, and the screen stays
     * up so its last step can describe the way back in.
     */
    private fun withSetup(identity: LauncherIdentity, assertions: () -> Unit) {
        hosting = identity
        withCover({
            IdentitySetupScreen(
                identity = identity,
                onComplete = { trigger, area, dots ->
                    completed += trigger
                    completedHold += area
                    completedDots += dots
                    val cover = identity.cover
                    if (trigger != null && cover != null) CoverTrigger.set(ctx, cover, trigger, area, dots)
                },
                onFinish = {},
                onCancel = { cancelled++ },
                // The grant is a `MainActivity` concern, asked for by the commit sheet
                // before this screen ever draws; the flow under test here is the
                // recording, so it is handed a cover that already has what it needs.
                grantGiven = true,
                onHoldConfirmed = { holdConfirmed++ },
            )
        }, assertions)
    }

    /**
     * The flow up to its first recording step: after the calculator's choice, and on every method
     * with a hold after both holds in [area] — the music player and the voice recorder have no
     * choice and open on **Choose where to hold** (`cover-hold-dots`).
     *
     * ***It used to open two steps earlier.*** A preview that asked nothing, then a
     * grant step whose "Allow access" button raised the dialog that asks the same
     * question. Both are gone, and this helper is what is left of the click that got
     * past them.
     */
    private fun startRecording(row: String = ctx.getString(BiometricWrap.PLAIN_METHOD), area: Int = 0) {
        compose.waitForIdle()
        if (hosting?.cover == Cover.Calculator) {
            choose(row)
            if (row != ctx.getString(BiometricWrap.HOLD_PATTERN_METHOD)) return
        } else {
            assertTrue("a pattern cover opened on a choice", !showing("How do you want to open it?"))
        }
        assertTrue("setup did not open on where to hold", showing("Choose where to hold"))
        holdCell(area)
        holdCell(area)
        assertTrue("the pattern did not follow the holds", showing("Now draw your shape"))
    }

    /** The calculator's choice: [row] selected and **Continue** pressed. */
    private fun choose(row: String) {
        assertTrue("setup did not open on the choice", showing("How do you want to open it?"))
        compose.onNodeWithText(row).performScrollTo().performClick()
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** Tap calculator keys, with no hold at the end. */
    private fun tapKeys(vararg symbols: String) {
        symbols.forEach { compose.onAllNodes(hasText(it))[0].performClick() }
        compose.waitForIdle()
    }

    /** Perform a calculator sequence and complete it with the hold on `=`. */
    private fun performKeys(vararg symbols: String) {
        symbols.forEach { compose.onAllNodes(hasText(it))[0].performClick() }
        compose.waitForIdle()
        compose.onAllNodes(hasText("="))[0].performTouchInput { longClick(durationMillis = 2_500) }
        compose.waitForIdle()
    }

    /** Substring, so an assertion names the phrase that matters and not a paragraph. */
    private fun showing(text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /**
     * `calculator-setup-keypad-sizing`. Setup is where the gesture is learned, so its
     * keypad must meet Android's 48dp touch floor — it drew 36dp keys before the
     * scaffold stopped paying the bars twice and the readout gave up its empty space.
     */
    @Test
    fun setupNumpadKeysMeetTheTouchFloor() {
        withSetup(LauncherIdentity.Calculator) {
            startRecording()
            listOf("7", "=", "AC", "%").forEach { label ->
                val bounds = compose.onAllNodes(hasText(label) and hasClickAction())[0].getBoundsInRoot()
                assertTrue("key $label is ${bounds.width} wide", bounds.width >= 48.dp)
                assertTrue("key $label is ${bounds.height} high", bounds.height >= 48.dp)
            }
        }
    }

    /**
     * The whole point of the change: a gesture performed twice, matching, is stored.
     */
    @Test
    fun aGesturePerformedTwiceIsStoredAndSwitches() {
        CoverTrigger.clear(ctx, Cover.Calculator)
        withSetup(LauncherIdentity.Calculator) {
            startRecording()
            performKeys("7", "3", "sin")
            assertTrue("the flow did not ask for a second performance", showing("Do it again"))
            assertTrue("nothing may be stored before the confirmation", completed.isEmpty())

            performKeys("7", "3", "sin")
            assertEquals("the confirmed trigger was not reported", 1, completed.size)
            assertEquals(
                Trigger.Keys(listOf("7", "3", "sin")),
                CoverTrigger.triggerFor(ctx, Cover.Calculator),
            )
        }
    }

    /**
     * ***A mismatch discards both performances.*** Not a second attempt against the
     * first one: that lets luck choose between two remembered variants, and rehearses
     * the user against a target they cannot see.
     */
    @Test
    fun aMismatchStoresNothingAndStartsOver() {
        CoverTrigger.clear(ctx, Cover.Calculator)
        withSetup(LauncherIdentity.Calculator) {
            startRecording()
            performKeys("7", "3", "sin")
            performKeys("7", "3", "cos")

            assertTrue("a mismatch must store nothing", completed.isEmpty())
            assertNull(CoverTrigger.triggerFor(ctx, Cover.Calculator))
            assertTrue("the flow must not still be confirming", !showing("Do it again"))

            // ***A step of its own, and not a red line over a live cover.*** The user
            // must be told that *neither* attempt survived — otherwise the honest
            // reading of "try again" is that the app still holds the first one.
            assertTrue(
                "a mismatch must land on a step that says so",
                showing("That was a different gesture"),
            )
            assertTrue("the mismatch step must say neither was kept", showing("neither was kept"))
            assertTrue(
                "the mismatch step must say the launcher is unchanged",
                showing("Nothing has changed"),
            )

            compose.onAllNodes(hasText("Start over"))[0].performClick()
            compose.waitForIdle()
            assertTrue("the flow must return to recording", showing("Record your gesture"))

            // And the first performance is gone: performing it once more records it
            // afresh rather than completing the abandoned confirmation.
            performKeys("7", "3", "sin")
            assertTrue("the discarded performance completed a confirmation", completed.isEmpty())
            assertTrue(showing("Do it again"))
        }
    }

    /** Leaving between the two performances leaves the phone exactly as it was. */
    @Test
    fun abandoningBetweenTheTwoPerformancesStoresNothing() {
        CoverTrigger.clear(ctx, Cover.Calculator)
        withSetup(LauncherIdentity.Calculator) {
            startRecording()
            performKeys("7", "3", "sin")
            assertTrue(showing("Do it again"))
        }
        // The screen is gone with the scenario, which is the whole mechanism: the
        // recorded performance lived in composition and nowhere else.
        assertTrue("an abandoned setup reported a completion", completed.isEmpty())
        assertNull(CoverTrigger.triggerFor(ctx, Cover.Calculator))
    }

    /**
     * ***Rehearsing never spends the user's own attempts.*** Charging the owner for
     * practising would let them lock themselves out of the screen they are standing
     * on — the limiter exists to make guessing expensive for somebody else.
     */
    @Test
    fun rehearsingConsumesNoAttempts() {
        CoverTrigger.clear(ctx, Cover.Calculator)
        CoverTrigger.clearAttempts(ctx, Cover.Calculator)
        val before = CoverTrigger.attempts(ctx, Cover.Calculator)

        withSetup(LauncherIdentity.Calculator) {
            startRecording()
            // Three performances: one short of its floor, one recorded, one that does
            // not match it. Every one of them is a gesture the limiter must not see.
            performKeys("7", "3")
            performKeys("7", "3", "sin")
            performKeys("4", "5", "6")
            assertEquals(
                "setup moved the limiter's counters",
                before,
                CoverTrigger.attempts(ctx, Cover.Calculator),
            )
        }
    }

    /** And a cooling-off period does not block the owner from setting one up. */
    @Test
    fun setupWorksDuringACoolingOffPeriod() {
        CoverTrigger.set(ctx, Cover.Calculator, Trigger.Keys(listOf("1", "2", "3")))
        val wrong = Trigger.Keys(listOf("9", "9", "9"))
        repeat(Attempts.MAX_FAILURES) { CoverTrigger.attempt(ctx, Cover.Calculator, wrong) }
        assertTrue(
            "the limiter did not lock, so this test proves nothing",
            CoverTrigger.attempts(ctx, Cover.Calculator).lockedAt(System.currentTimeMillis()),
        )

        withSetup(LauncherIdentity.Calculator) {
            startRecording()
            performKeys("7", "3", "sin")
            performKeys("7", "3", "sin")
            assertEquals("a locked-out cover refused its own setup", 1, completed.size)
            assertEquals(
                Trigger.Keys(listOf("7", "3", "sin")),
                CoverTrigger.triggerFor(ctx, Cover.Calculator),
            )
        }
    }

    /**
     * A performance under its cover's floor does not advance, and says why. The floor
     * is the same one `FORMAT.md` §10 records; the route to it changed, not the value.
     */
    @Test
    fun aPerformanceUnderTheFloorDoesNotAdvance() {
        CoverTrigger.clear(ctx, Cover.Calculator)
        withSetup(LauncherIdentity.Calculator) {
            startRecording()
            performKeys("7", "3")

            assertTrue("a short sequence advanced the flow", !showing("Do it again"))
            // A distance in the cover's own units, not a verdict — the language the
            // progress row above it was already using.
            assertTrue("the flow did not say the gesture fell short", showing("That was too short"))
            assertTrue("the flow did not state the floor", showing("at least 3 keys"))
            assertTrue(completed.isEmpty())
        }
    }

    /**
     * ***`AC` puts the recording step back where it opened*** —
     * `calculator-ac-clears-sequence`. The count and the bar return to zero and the
     * opening line, the one that names `AC`, is what the user reads again.
     */
    @Test
    fun acRestartsTheRecordingStep() {
        CoverTrigger.clear(ctx, Cover.Calculator)
        withSetup(LauncherIdentity.Calculator) {
            startRecording()
            tapKeys("7", "3")
            assertTrue("two keys were not counted", showing("2 keys so far"))

            tapKeys("AC")
            assertTrue("the count survived AC", !showing("2 keys so far"))
            assertTrue("the opening line did not return", showing("Pressed a wrong key? Press “AC” to start again."))
            assertTrue("AC moved the flow", showing("Record your gesture"))
            assertTrue(completed.isEmpty())
        }
    }

    /** The notice about a short performance goes with the performance it described. */
    @Test
    fun acClearsTheShortNotice() {
        CoverTrigger.clear(ctx, Cover.Calculator)
        withSetup(LauncherIdentity.Calculator) {
            startRecording()
            performKeys("7", "3")
            assertTrue("the short notice was not shown", showing("That was too short"))

            tapKeys("AC")
            assertTrue("the short notice survived AC", !showing("That was too short"))
        }
    }

    /** `AC` on the confirming step discards the confirmation only, never the recording. */
    @Test
    fun acOnTheConfirmingStepKeepsTheFirstRecording() {
        CoverTrigger.clear(ctx, Cover.Calculator)
        withSetup(LauncherIdentity.Calculator) {
            startRecording()
            performKeys("7", "3", "sin")
            assertTrue("the flow did not ask for a second performance", showing("Do it again"))
            assertTrue("the confirming line does not name AC", showing("Press the same keys again. Press “AC” to start again."))

            tapKeys("9", "AC")
            assertTrue("AC left the confirming step", showing("Do it again"))
            performKeys("7", "3", "sin")

            assertEquals("the confirmed trigger was not reported", 1, completed.size)
            assertTrue("the final step was not reached", showing("All set"))
            assertEquals(
                Trigger.Keys(listOf("7", "3", "sin")),
                CoverTrigger.triggerFor(ctx, Cover.Calculator),
            )
        }
    }

    /**
     * ***The recorder writes nothing while setup is hosting it.***
     *
     * The trigger *is* the word typed into the search box, and this is the one moment
     * the app tells the user to type it. A recording filed instead of the gesture
     * would leave that session in plaintext storage — the leak `cover-front-ends`
     * forbids, created by the screen meant to prevent it.
     */
    @Test
    fun theRecorderKeepsNothingDuringSetup() {
        val before = RecorderStore.recordings(ctx).map { it.name }
        withSetup(LauncherIdentity.VoiceRecorder) {
            startRecording()
            compose.onAllNodes(hasSetTextAction())[0].performTextInput("pelican")
            compose.waitForIdle()
            // The record control, pressed and pressed again: a capture started and
            // ended inside setup.
            compose.onAllNodesWithContentDescription("Record").fetchSemanticsNodes()
            compose.onAllNodes(hasClickAction())[1].performClick()
            compose.waitForIdle()
            compose.onAllNodes(hasClickAction())[1].performClick()
            compose.waitForIdle()

            assertEquals(
                "setup filed a recording",
                before,
                RecorderStore.recordings(ctx).map { it.name },
            )
        }
        assertEquals(
            "setup filed a recording",
            before,
            RecorderStore.recordings(ctx).map { it.name },
        )
    }

    /**
     * The recorder's trigger, recorded and confirmed after the holds: a pattern drawn on the
     * invisible grid, completed by lifting the finger.
     */
    @Test
    fun theRecordersPatternIsRecordedAndConfirmed() {
        CoverTrigger.clear(ctx, Cover.VoiceRecorder)
        withSetup(LauncherIdentity.VoiceRecorder) {
            startRecording(area = 2)
            performPattern(setupCoverHost(), 0, 4, 8, 2, 6)
            assertTrue("the flow did not ask for a second performance", showing("Draw it again"))
            performPattern(setupCoverHost(), 0, 4, 8, 2, 6)

            assertEquals("the confirmed trigger was not reported", 1, completed.size)
            assertEquals(
                Trigger.Pattern(listOf(0, 4, 8, 2, 6)),
                CoverTrigger.triggerFor(ctx, Cover.VoiceRecorder),
            )
            assertEquals(2, CoverTrigger.holdFor(ctx, Cover.VoiceRecorder))
        }
        CoverTrigger.clear(ctx, Cover.VoiceRecorder)
    }

    // --- the recording step acknowledges what it is given --------------------

    /**
     * ***Every node lands somewhere the user can see.*** `pattern-cover-trigger`
     * replaced the press-sequence covers this used to test, but not the reason the
     * step exists: enrolling a secret the user cannot see is the problem a fingerprint
     * enrolment solves, and it solves it by showing the progress while it accrues
     * rather than judging it once it is over — now a live count **and** the drawn
     * shape itself, via `PatternGridOverlay`.
     *
     * A single continuous gesture, held open with [startPattern] and
     * [continuePattern] so the count can be read mid-draw — [performPattern] cannot
     * be used here because it lifts, and lifting is what this step is watching for.
     */
    @Test
    fun theRecordingStepCountsPatternNodesAsTheyLand() {
        CoverTrigger.clear(ctx, Cover.MusicPlayer)
        withSetup(LauncherIdentity.MusicPlayer) {
            startRecording()
            assertTrue(
                "the step does not say what to do",
                showing("Draw a shape connecting at least five"),
            )

            val host = setupCoverHost()
            startPattern(host, 0)
            assertTrue("one node was not counted", showing("1 node so far"))
            assertTrue("the distance to the floor is not stated", showing("4 more to go"))

            continuePattern(host, 4, 8)
            assertTrue("three nodes were not counted", showing("3 nodes so far"))
            assertTrue("the distance to the floor is not stated", showing("2 more to go"))

            // The floor. The language turns from distance to the gesture that
            // finishes, and that gesture is not named before it can be used.
            continuePattern(host, 2)
            assertTrue("the floor was not acknowledged", showing("4 nodes"))
            continuePattern(host, 6)
            assertTrue("the floor was not acknowledged at five", showing("5 nodes"))
            assertTrue(
                "the completing gesture is not named once the floor is met",
                showing("Lift your finger to finish"),
            )
            assertTrue("the step still reads as a distance", !showing("more to go"))

            finishPattern(host)
            assertTrue("the completed pattern did not advance the flow", showing("Draw it again"))
        }
        CoverTrigger.clear(ctx, Cover.MusicPlayer)
    }

    /**
     * ***A sub-floor draw is silent, and deliberately so — `pattern-cover-trigger`.***
     * The step this replaced turned red under the floor, in the same units the
     * progress row above it already used. That mechanism does not carry over to a
     * pattern: `CoverGestures.silentPatternGrid` never calls `onTrigger` below the
     * floor, because in setup exactly as on a live cover a short drag is
     * indistinguishable from an ordinary scroll of whatever the grid sits over — see
     * `IdentitySetupScreen.capture`'s doc. What is asserted here is the replacement:
     * the live count is what a shortfall looks like, and nothing else fires.
     */
    @Test
    fun aSubFloorDrawIsSilentAndLeavesTheStepUnchanged() {
        CoverTrigger.clear(ctx, Cover.VoiceRecorder)
        withSetup(LauncherIdentity.VoiceRecorder) {
            startRecording()
            performPattern(setupCoverHost(), 0, 4, 8)

            assertTrue("an under-floor performance was stored", completed.isEmpty())
            assertNull(CoverTrigger.triggerFor(ctx, Cover.VoiceRecorder))
            assertTrue(
                "no verdict is shown for a short draw — only the count it reached",
                !showing("too short"),
            )
            assertTrue("the last count drawn is still shown", showing("3 nodes so far"))
            assertTrue("the flow left the recording step", showing("Now draw your shape"))
        }
        CoverTrigger.clear(ctx, Cover.VoiceRecorder)
    }

    /**
     * ***The last step carries the gesture and the warning, and nothing else.***
     *
     * It used to carry four paragraphs and a third copy of the icon-and-label header,
     * with the one sentence there is no recovering from sitting third of four on the
     * screen users tap through fastest.
     */
    @Test
    fun theFinalStepCarriesOnlyTheGestureAndTheWarning() {
        CoverTrigger.clear(ctx, Cover.Calculator)
        withSetup(LauncherIdentity.Calculator) {
            startRecording()
            performKeys("7", "3", "sin")
            performKeys("7", "3", "sin")

            assertTrue("the final step was not reached", showing("All set"))
            assertTrue("the gesture is not read back", showing("Press 7, 3, sin on the calculator"))
            assertTrue(
                "the way to take back a wrong key is not stated",
                showing("If you press a wrong key, press “AC” and start again."),
            )
            assertTrue("the warning is missing", showing("Nobody can recover this gesture"))
            assertTrue("the way out is not offered", showing("switch back to the vault's own icon"))
            assertFalse("a fingerprint was named", showing("fingerprint"))
            assertFalse("a window was named", showing("within"))
            assertFalse("the PIN was named", showing("PIN"))

            assertTrue(
                "the final step still describes the launcher",
                !showing("Your launcher now shows"),
            )
            assertTrue(
                "the final step still restates the concealment boundary",
                !showing("does not hide it from"),
            )
        }
        CoverTrigger.clear(ctx, Cover.Calculator)
    }

    // --- the method choice — the calculator's alone ----------------------------------

    /**
     * The calculator opens on the choice: its title, its lead, **Button sequence** selected and
     * no biometric row. The music player and the voice recorder have no choice at all
     * (`cover-hold-dots` D3): they open on choosing where to hold.
     */
    @Test
    fun onlyTheCalculatorOpensOnAChoice() {
        withSetup(LauncherIdentity.Calculator) {
            compose.waitForIdle()
            assertTrue("the calculator has no choice", showing("How do you want to open it?"))
            assertTrue(showing(ctx.getString(BiometricWrap.CHOOSE_LEAD)))
            compose.onNodeWithText("Button sequence").assertIsSelected()
            listOf("with fingerprint", "with face", "with biometric unlock").forEach {
                assertFalse("a biometric row: $it", showing(it))
            }
        }
        listOf(LauncherIdentity.MusicPlayer, LauncherIdentity.VoiceRecorder).forEach { identity ->
            withSetup(identity) {
                compose.waitForIdle()
                assertFalse("${identity.name}: a choice", showing("How do you want to open it?"))
                assertTrue("${identity.name}: not on where to hold", showing("Choose where to hold"))
                assertFalse("${identity.name}: a fingerprint method", showing("with fingerprint"))
            }
        }
        assertEquals("Pick how you will open the passphrase screen from this icon. You set it up next.", ctx.getString(BiometricWrap.CHOOSE_LEAD))
    }

    // --- the calculator's three methods — `calculator-dots-methods` --------------------------

    private val shapeLine =
        "Draw a shape connecting at least five of the nine points, in an order you will remember. " +
            "It will not be shown once this screen closes."

    /** How many nodes carry exactly [text] — a key's label, plus the display where it shows it too. */
    private fun count(text: String): Int = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    /**
     * D6. Three rows in order, with their lines, **Button sequence** selected on arrival; a tap
     * selects, and the selection is still there after Back from the method's first step —
     * **Choose where to hold** on **Hold, then pattern**, with the 12 cells over the calculator.
     */
    @Test
    fun theChoiceOffersThreeRowsAndKeepsTheSelectionAcrossBack() {
        withSetup(LauncherIdentity.Calculator) {
            compose.waitForIdle()
            val rows = listOf(
                BiometricWrap.PLAIN_METHOD to "Type your sequence, then hold =.",
                BiometricWrap.SEQUENCE_PATTERN_METHOD to
                    "Type your sequence and hold =, then draw your pattern on the dots that appear.",
                BiometricWrap.HOLD_PATTERN_METHOD to
                    "Hold a spot you choose for 2 seconds, then draw your pattern on the dots that appear.",
            )
            assertEquals(listOf("Button sequence", "Button sequence, then pattern", "Hold, then pattern"), rows.map { ctx.getString(it.first) })
            val tops = rows.map { (name, line) ->
                assertTrue("missing: $line", showing(line))
                compose.onNodeWithText(ctx.getString(name)).getBoundsInRoot().top
            }
            assertEquals("the rows are out of order", tops.sortedBy { it.value }, tops)
            compose.onNodeWithText(ctx.getString(BiometricWrap.PLAIN_METHOD)).assertIsSelected()
            compose.onNodeWithText(ctx.getString(BiometricWrap.SEQUENCE_PATTERN_METHOD)).assertIsNotSelected()
            compose.onNodeWithText(ctx.getString(BiometricWrap.HOLD_PATTERN_METHOD)).assertIsNotSelected()

            choose(ctx.getString(BiometricWrap.HOLD_PATTERN_METHOD))
            assertTrue(showing("Choose where to hold"))
            (0 until HOLD_AREAS).forEach { compose.onNodeWithContentDescription(holdPlace(ctx.words(), it)).assertExists() }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()
            assertTrue("Back did not return to the choice", showing("How do you want to open it?"))
            compose.onNodeWithText(ctx.getString(BiometricWrap.HOLD_PATTERN_METHOD)).assertIsSelected()
            compose.onNodeWithText(ctx.getString(BiometricWrap.PLAIN_METHOD)).assertIsNotSelected()
            assertEquals("Back from the first hold cancelled", 0, cancelled)
        }
    }

    /**
     * D6, row 2. The sequence twice, then **Now draw your shape** and **Draw it again** on the
     * calculator; a different pattern keeps the sequence and Start over returns to the shape; the
     * matching pattern stores the sequence and the pattern, and Done reads the route back.
     */
    @Test
    fun theSequenceThenPatternStepsTitlesMismatchAndDone() {
        CoverTrigger.clear(ctx, Cover.Calculator)
        withSetup(LauncherIdentity.Calculator) {
            startRecording(ctx.getString(BiometricWrap.SEQUENCE_PATTERN_METHOD))
            assertTrue(showing("Record your gesture"))
            performKeys("7", "3", "sin")
            assertTrue(showing("Do it again"))
            performKeys("7", "3", "sin")

            assertTrue("the second stage's title", showing("Now draw your shape"))
            assertTrue(showing(shapeLine))
            compose.onNodeWithTag(PATTERN_GRID_TAG).assertExists()
            assertTrue("something was stored after the sequence", completed.isEmpty())
            assertNull(CoverTrigger.triggerFor(ctx, Cover.Calculator))

            performPattern(setupCoverHost(), 0, 4, 8, 2, 6)
            assertTrue(showing("Draw it again"))
            assertTrue(showing("Draw the same shape again."))
            performPattern(setupCoverHost(), 1, 3, 5, 7, 4)

            assertTrue(showing("That was a different gesture"))
            assertTrue(showing("The second pattern did not match the first"))
            assertTrue(showing("Your sequence is kept — only the shape starts over."))
            assertTrue(showing("Nothing has changed"))
            assertTrue(completed.isEmpty())
            compose.onNodeWithText("Start over").performScrollTo().performClick()
            compose.waitForIdle()
            assertTrue("Start over did not return to the shape", showing("Now draw your shape"))

            performPattern(setupCoverHost(), 0, 4, 8, 2, 6)
            performPattern(setupCoverHost(), 0, 4, 8, 2, 6)
            assertTrue(showing("All set"))
            assertEquals(listOf<Trigger?>(trigger), completed)
            assertEquals(listOf<Trigger.Pattern?>(Trigger.Pattern(listOf(0, 4, 8, 2, 6))), completedDots)
            assertEquals(listOf<Int?>(null), completedHold)
            assertEquals(trigger, CoverTrigger.triggerFor(ctx, Cover.Calculator))
            assertEquals(Trigger.Pattern(listOf(0, 4, 8, 2, 6)), CoverTrigger.patternFor(ctx, Cover.Calculator))
            assertNull(CoverTrigger.holdFor(ctx, Cover.Calculator))
            assertEquals(CalculatorMethod.SequenceThenPattern, CoverTrigger.calculatorMethod(ctx))

            assertTrue(showing("To reach the passphrase screen from this icon:"))
            assertTrue(
                showing(
                    "Press 7, 3, sin on the calculator, then press and hold “=”, and the dots appear. " +
                        "Draw your shape on them: 5 points.",
                ),
            )
            assertTrue(showing("If you press a wrong key, press “AC” and start again."))
            assertTrue(showing("Nobody can recover this gesture"))
            assertFalse("the calculator's dots do not work hidden", showing("hidden dots"))
        }
    }

    /** D6. Back on row 2's shape abandons setup and stores nothing, the sequence included. */
    @Test
    fun backBetweenTheStagesAbandonsAndStoresNothing() {
        CoverTrigger.clear(ctx, Cover.Calculator)
        withSetup(LauncherIdentity.Calculator) {
            startRecording(ctx.getString(BiometricWrap.SEQUENCE_PATTERN_METHOD))
            performKeys("7", "3", "sin")
            performKeys("7", "3", "sin")
            assertTrue(showing("Now draw your shape"))
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()
            assertEquals("Back on the shape did not abandon setup", 1, cancelled)
        }
        assertTrue(completed.isEmpty())
        assertNull(CoverTrigger.calculatorMethod(ctx))
    }

    /**
     * D6. In a pattern step the keypad is held still: a tap on a key enters nothing, and a shape
     * drawn across the keys is a pattern, not key presses.
     */
    @Test
    fun theKeypadIsHeldStillInAPatternStep() {
        withSetup(LauncherIdentity.Calculator) {
            startRecording(ctx.getString(BiometricWrap.HOLD_PATTERN_METHOD))
            val sevens = count("7")
            compose.onAllNodes(hasText("7"))[0].performClick()
            compose.waitForIdle()
            assertEquals("a key under the grid entered itself", sevens, count("7"))
            performPattern(setupCoverHost(), 0, 4, 8, 2, 6)
            assertTrue("the shape was not taken as a pattern", showing("Draw it again"))
            assertEquals("the shape entered keys", sevens, count("7"))
        }
    }

    /**
     * D6, row 3. The hold steps over the calculator, then the pattern on it; the matching pattern
     * stores the pattern and the area and no sequence, and Done states the hold with its place and
     * no hidden dots.
     */
    @Test
    fun theHoldThenPatternStepsAndDone() {
        CoverTrigger.clear(ctx, Cover.Calculator)
        withSetup(LauncherIdentity.Calculator) {
            choose(ctx.getString(BiometricWrap.HOLD_PATTERN_METHOD))
            assertTrue("the hold step did not come first", showing("Choose where to hold"))
            assertTrue(showing(holdLine))
            (0 until HOLD_AREAS).forEach { compose.onNodeWithContentDescription(holdPlace(ctx.words(), it)).assertExists() }
            assertTrue(completed.isEmpty())
            holdCell(11)
            assertTrue(showing("Hold it again"))
            holdCell(11)

            assertTrue("the pattern did not follow the holds", showing("Now draw your shape"))
            assertTrue(showing(shapeLine))
            assertTrue("something was stored after the holds", completed.isEmpty())
            performPattern(setupCoverHost(), 2, 4, 6, 8, 5)
            assertTrue(showing("Draw it again"))
            performPattern(setupCoverHost(), 2, 4, 6, 8, 5)

            assertTrue(showing("All set"))
            assertEquals(2, holdConfirmed)
            assertEquals(listOf<Int?>(11), completedHold)
            assertEquals(listOf<Trigger.Pattern?>(null), completedDots)
            assertNull("a sequence was stored", CoverTrigger.triggerFor(ctx, Cover.Calculator))
            assertEquals(Trigger.Pattern(listOf(2, 4, 6, 8, 5)), CoverTrigger.patternFor(ctx, Cover.Calculator))
            assertEquals(11, CoverTrigger.holdFor(ctx, Cover.Calculator))
            assertEquals(CalculatorMethod.HoldThenPattern, CoverTrigger.calculatorMethod(ctx))

            assertTrue(
                showing(
                    "Press and hold the spot you chose — Bottom row, right — for 2 seconds, and the dots appear. " +
                        "Draw your shape on them: 5 points.",
                ),
            )
            compose.onNodeWithTag(HOLD_DIAGRAM_TAG).assertExists()
            assertFalse("the calculator's dots do not work hidden", showing("hidden dots"))
            assertFalse("a sequence was named", showing("“AC”"))
        }
    }

    /** D8. The calculator's row says how weak it is by its stored method. */
    @Test
    fun theIdentitySentenceFollowsTheCalculatorsMethod() {
        val weak = "The weakest of the covers — its gesture is a short sequence of keystrokes."
        val unset = "With a button sequence alone, it is the weakest of the covers."
        var method by mutableStateOf<CalculatorMethod?>(CalculatorMethod.Sequence)
        withCover({ IdentityScreen(current = LauncherIdentity.Vault, onChoose = {}, onCancel = {}, calculatorMethod = method) }) {
            assertTrue(showing(weak))
            assertFalse(showing(unset))
            listOf(CalculatorMethod.SequenceThenPattern, CalculatorMethod.HoldThenPattern).forEach {
                compose.runOnIdle { method = it }
                compose.waitForIdle()
                assertFalse("$it: a weakness sentence", showing("weakest"))
            }
            compose.runOnIdle { method = null }
            compose.waitForIdle()
            assertTrue(showing(unset))
            assertFalse(showing(weak))
        }
    }

    // --- the hold steps — `cover-hold-dots` D3 ---------------------------------------

    private val holdLine =
        "Press and hold anywhere on the screen for 2 seconds. That spot is what brings up the dots — " +
            "it works whatever is showing there."

    /** The centre of hold cell [area] within the setup's hosted cover. */
    private fun TouchInjectionScope.cellCentre(area: Int) =
        Offset((area % HOLD_COLUMNS + 0.5f) * width / HOLD_COLUMNS, (area / HOLD_COLUMNS + 0.5f) * height / HOLD_ROWS)

    /** One finger on cell [area] of the hosted cover for [ms], then lifted. */
    private fun holdCell(area: Int, ms: Long = 2_500) {
        setupCoverHost().performTouchInput {
            down(cellCentre(area))
            advanceEventTime(ms)
            up()
        }
        compose.waitForIdle()
    }

    /** Hold [area] twice, then record and confirm [nodes] on the hosted cover — every step before Done. */
    private fun recordPattern(vararg nodes: Int, area: Int = 0) {
        startRecording(area = area)
        performPattern(setupCoverHost(), *nodes)
        performPattern(setupCoverHost(), *nodes)
    }

    /**
     * D3. First **Choose where to hold**, its line, the 12 cells named for TalkBack; a 2-second
     * hold confirms with the haptic and moves to **Hold it again**; the same cell again moves to
     * **Now draw your shape**, storing nothing; the confirmed pattern stores the pattern and the
     * area together — and nothing is stored before.
     */
    @Test
    fun thePatternCoversRecordWhereToHoldThenThePatternBeforeAnythingIsStored() {
        CoverTrigger.clear(ctx, Cover.MusicPlayer)
        withSetup(LauncherIdentity.MusicPlayer) {
            compose.waitForIdle()
            assertTrue("setup did not open on where to hold", showing("Choose where to hold"))
            assertTrue(showing(holdLine))
            (0 until HOLD_AREAS).forEach { area ->
                compose.onNodeWithContentDescription(holdPlace(ctx.words(), area)).assertExists()
            }
            assertTrue("something was stored before the holds", completed.isEmpty())
            assertNull(CoverTrigger.triggerFor(ctx, Cover.MusicPlayer))

            holdCell(0)
            assertTrue("the second hold was not asked for", showing("Hold it again"))
            assertTrue(showing("Press and hold the same spot again."))
            assertEquals("the hold did not confirm", 1, holdConfirmed)
            assertTrue("something was stored after one hold", completed.isEmpty())

            holdCell(0)
            assertTrue("the pattern did not follow the holds", showing("Now draw your shape"))
            assertEquals(2, holdConfirmed)
            assertTrue("something was stored after the holds", completed.isEmpty())
            assertNull(CoverTrigger.triggerFor(ctx, Cover.MusicPlayer))
            assertNull(CoverTrigger.holdFor(ctx, Cover.MusicPlayer))

            performPattern(setupCoverHost(), 0, 4, 8, 2, 6)
            assertTrue(showing("Draw it again"))
            assertTrue("something was stored after one pattern", completed.isEmpty())
            performPattern(setupCoverHost(), 0, 4, 8, 2, 6)
            assertTrue(showing("All set"))
            assertEquals(listOf<Int?>(0), completedHold)
            assertEquals(Trigger.Pattern(listOf(0, 4, 8, 2, 6)), CoverTrigger.triggerFor(ctx, Cover.MusicPlayer))
            assertEquals(0, CoverTrigger.holdFor(ctx, Cover.MusicPlayer))
        }
        CoverTrigger.clear(ctx, Cover.MusicPlayer)
    }

    /**
     * D3. Lifted after a second: nothing recorded, the line says to keep holding, announced
     * politely; while the finger is down the cell fills from its centre, and the fill drains.
     */
    @Test
    fun anEarlyLiftSaysKeepHoldingAndTheFillShowsTheHold() {
        CoverTrigger.clear(ctx, Cover.VoiceRecorder)
        withSetup(LauncherIdentity.VoiceRecorder) {
            compose.waitForIdle()
            assertTrue(showing("Choose where to hold"))
            val before = setupCoverHost().captureToImage().asAndroidBitmap()
            // The clock held by hand: left to advance on its own, waiting for idle would run the
            // 2-second fill to its end, and the hold would complete under the capture.
            compose.mainClock.autoAdvance = false
            setupCoverHost().performTouchInput { down(cellCentre(4)) }
            compose.mainClock.advanceTimeBy(1_000)
            val during = setupCoverHost().captureToImage().asAndroidBitmap()
            setupCoverHost().performTouchInput { up() }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()

            fun centreOf4(b: android.graphics.Bitmap) = b.getPixel(b.width / 2, (b.height * 1.5f / HOLD_ROWS).toInt())
            fun centreOf0(b: android.graphics.Bitmap) = b.getPixel(b.width / 6, b.height / 8)
            assertTrue("the held cell did not fill", centreOf4(before) != centreOf4(during))
            assertEquals("another cell filled", centreOf0(before), centreOf0(during))

            compose.onNodeWithText("Keep holding for 2 seconds.")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            assertTrue("an early lift advanced the step", showing("Choose where to hold"))
            assertEquals("an early lift confirmed", 0, holdConfirmed)
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            val after = setupCoverHost().captureToImage().asAndroidBitmap()
            assertEquals("the fill did not drain", centreOf4(before), centreOf4(after))
        }
        CoverTrigger.clear(ctx, Cover.VoiceRecorder)
    }

    /**
     * D3. Two holds in different cells keep neither: **That was a different spot**, and **Start
     * over** returns to the first hold with nothing kept, since nothing comes before the holds.
     */
    @Test
    fun twoDifferentSpotsKeepNeitherAndStartOverReturnsToTheFirstHold() {
        CoverTrigger.clear(ctx, Cover.MusicPlayer)
        withSetup(LauncherIdentity.MusicPlayer) {
            holdCell(0)
            holdCell(5)
            assertTrue(showing("That was a different spot"))
            assertTrue(showing("The second hold was in a different spot, so neither was kept."))
            assertTrue(completed.isEmpty())
            assertNull(CoverTrigger.triggerFor(ctx, Cover.MusicPlayer))

            compose.onNodeWithText("Start over").performClick()
            compose.waitForIdle()
            assertTrue("Start over did not return to the first hold", showing("Choose where to hold"))
            holdCell(11)
            holdCell(11)
            assertTrue("the pattern did not follow the holds", showing("Now draw your shape"))
            performPattern(setupCoverHost(), 0, 4, 8, 2, 6)
            performPattern(setupCoverHost(), 0, 4, 8, 2, 6)
            assertTrue(showing("All set"))
            assertEquals(Trigger.Pattern(listOf(0, 4, 8, 2, 6)), CoverTrigger.triggerFor(ctx, Cover.MusicPlayer))
            assertEquals(11, CoverTrigger.holdFor(ctx, Cover.MusicPlayer))
        }
        CoverTrigger.clear(ctx, Cover.MusicPlayer)
    }

    /**
     * A different pattern after the holds keeps the spot: **That was a different gesture** says so,
     * **Start over** returns to **Now draw your shape** and not to the holds, and the matching
     * pattern then stores the area first held — on the music player and on **Hold, then pattern**.
     */
    @Test
    fun aDifferentPatternAfterTheHoldsKeepsTheSpot() {
        listOf(LauncherIdentity.MusicPlayer, LauncherIdentity.Calculator).forEach { identity ->
            val cover = identity.cover!!
            CoverTrigger.clear(ctx, cover)
            completed.clear()
            completedHold.clear()
            withSetup(identity) {
                startRecording(ctx.getString(BiometricWrap.HOLD_PATTERN_METHOD), area = 7)
                performPattern(setupCoverHost(), 0, 4, 8, 2, 6)
                performPattern(setupCoverHost(), 1, 3, 5, 7, 4)

                assertTrue("${identity.name}: no mismatch", showing("That was a different gesture"))
                assertTrue("${identity.name}: the spot is not said kept", showing("Your spot is kept — only the shape starts over."))
                assertFalse("${identity.name}: a sequence was said kept", showing("Your sequence is kept"))
                assertTrue(completed.isEmpty())
                assertNull(CoverTrigger.holdFor(ctx, cover))

                compose.onNodeWithText("Start over").performScrollTo().performClick()
                compose.waitForIdle()
                assertTrue("${identity.name}: Start over did not return to the shape", showing("Now draw your shape"))
                assertFalse("${identity.name}: Start over returned to the holds", showing("Choose where to hold"))

                performPattern(setupCoverHost(), 2, 4, 6, 8, 5)
                performPattern(setupCoverHost(), 2, 4, 6, 8, 5)
                assertTrue(showing("All set"))
                assertEquals(listOf<Int?>(7), completedHold)
                assertEquals(7, CoverTrigger.holdFor(ctx, cover))
            }
            CoverTrigger.clear(ctx, cover)
        }
    }

    /**
     * On a pattern cover Back on either hold step abandons setup, storing nothing: **Choose where to
     * hold** is its first step, with no choice before it.
     */
    @Test
    fun backOnAHoldStepAbandons() {
        CoverTrigger.clear(ctx, Cover.VoiceRecorder)
        withSetup(LauncherIdentity.VoiceRecorder) {
            compose.waitForIdle()
            assertTrue(showing("Choose where to hold"))
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()
            assertEquals("Back on the first hold did not abandon setup", 1, cancelled)
        }
        withSetup(LauncherIdentity.VoiceRecorder) {
            holdCell(3)
            assertTrue(showing("Hold it again"))
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()
            assertEquals("Back on the second hold did not abandon setup", 2, cancelled)
        }
        assertTrue(completed.isEmpty())
        assertNull(CoverTrigger.triggerFor(ctx, Cover.VoiceRecorder))
        assertNull(CoverTrigger.holdFor(ctx, Cover.VoiceRecorder))
    }

    /**
     * D8. The pattern covers' final step: the hold with its place, beside the 12-cell diagram with
     * the chosen cell in `Primary`; the shape and its count; the hidden dots; the warning. No
     * fingerprint, no window, no PIN.
     */
    @Test
    fun thePatternFinalStepStatesTheHoldAndShowsItsPlace() {
        CoverTrigger.clear(ctx, Cover.VoiceRecorder)
        withSetup(LauncherIdentity.VoiceRecorder) {
            recordPattern(0, 4, 8, 2, 6, area = 0)
            assertTrue(showing("To reach the passphrase screen from this icon:"))
            assertTrue(
                showing(
                    "Press and hold the spot you chose — Top row, left — for 2 seconds, and the dots appear. " +
                        "Draw your shape on them: 5 points.",
                ),
            )
            assertTrue(showing("Your shape also works on the hidden dots at any time."))
            assertTrue("the warning is missing", showing("Nobody can recover this gesture"))
            assertFalse("a fingerprint was named", showing("fingerprint"))
            assertFalse("a window was named", showing("within"))
            assertFalse("the PIN was named", showing("PIN"))

            val diagram = compose.onNodeWithTag(HOLD_DIAGRAM_TAG)
            val bounds = diagram.getBoundsInRoot()
            assertEquals(48.dp, bounds.width)
            assertEquals(64.dp, bounds.height)
            val image = diagram.captureToImage().asAndroidBitmap()
            val cellW = image.width / HOLD_COLUMNS
            val cellH = image.height / HOLD_ROWS
            val primary = image.getPixel(cellW / 2, cellH / 2)
            val other = image.getPixel(cellW * 2 + cellW / 2, cellH * 3 + cellH / 2)
            assertTrue("the chosen cell is not filled", android.graphics.Color.alpha(primary) == 255)
            assertTrue("an unchosen cell is filled", primary != other)
        }
        CoverTrigger.clear(ctx, Cover.VoiceRecorder)
    }

    /** `cover-hold-dots` D6. With a pattern cover worn, App identity carries no fingerprint section and no window. */
    @Test
    fun theIdentityScreenCarriesNoFingerprintSection() {
        withCover({ IdentityScreen(current = LauncherIdentity.MusicPlayer, onChoose = {}, onCancel = {}) }) {
            assertFalse(showing("Link fingerprint again"))
            assertFalse(showing("no longer opens your vault"))
            assertFalse("a pattern cover drew the calculator's window", showing("After unlocking"))
        }
    }

    /**
     * D4. The live dots carry **Draw your pattern**; after a refused pattern **Wrong pattern**,
     * announced politely. Setup's grid carries no line.
     */
    @Test
    fun theLiveDotsSayDrawThenWrong() {
        var error by mutableStateOf(false)
        withCover({ MusicCover(hasAccess = false, onTrigger = {}, persist = false, showDots = true, dotsError = error) }) {
            assertTrue(showing(ctx.getString(DRAW_PATTERN)))
            compose.runOnIdle { error = true }
            compose.waitForIdle()
            compose.onNodeWithText(ctx.getString(WRONG_PATTERN))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            assertFalse(showing(ctx.getString(DRAW_PATTERN)))
        }
        withSetup(LauncherIdentity.MusicPlayer) {
            startRecording()
            assertFalse("setup's grid carried the live line", showing(ctx.getString(DRAW_PATTERN)))
        }
    }

    /**
     * The calculator's first step goes back to its choice; a pattern cover has no choice, so Back on
     * its first step, **Choose where to hold**, abandons setup. Every pattern step comes after the
     * holds, and Back on it abandons too, on the calculator as well.
     */
    @Test
    fun backFromTheFirstRecordingReturnsToTheChoiceAndLaterBackLeaves() {
        withSetup(LauncherIdentity.MusicPlayer) {
            compose.waitForIdle()
            assertTrue(showing("Choose where to hold"))
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()
            assertEquals("Back on a pattern cover's first hold did not abandon setup", 1, cancelled)
        }
        withSetup(LauncherIdentity.MusicPlayer) {
            startRecording()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()
            assertEquals("Back on Now draw your shape did not abandon setup", 2, cancelled)
        }
        withSetup(LauncherIdentity.MusicPlayer) {
            startRecording()
            performPattern(setupCoverHost(), 0, 4, 8, 2, 6)
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()
            assertEquals("Back on Draw it again did not abandon setup", 3, cancelled)
        }
        withSetup(LauncherIdentity.Calculator) {
            startRecording(ctx.getString(BiometricWrap.HOLD_PATTERN_METHOD))
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()
            assertEquals("Back on the calculator's pattern after its holds did not abandon setup", 4, cancelled)
        }
        assertTrue(completed.isEmpty())
        withSetup(LauncherIdentity.Calculator) {
            startRecording()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()
            assertTrue("the calculator's Back did not return to its choice", showing("How do you want to open it?"))
        }
    }

    /**
     * A live cover's revealed dots are setup's grid, holding the cover still the same way: the
     * search field takes no focus, the grid is drawn, and a pattern is still reported. Hidden,
     * nothing is drawn and the field takes focus as it always did.
     */
    @Test
    fun theRevealedDotsAreSetupsGridAndHoldTheCoverStill() {
        withCover({ RecorderCover(hasPermission = true, onTrigger = { offered += it }, persist = false, showDots = true) }) {
            compose.onNodeWithTag(PATTERN_GRID_TAG).assertExists()
            compose.onAllNodes(hasSetTextAction())[0].performClick()
            compose.waitForIdle()
            compose.onAllNodes(hasSetTextAction())[0].assertIsNotFocused()
            performPattern(coverRoot(), 0, 4, 8, 2, 6)
            assertEquals(listOf<Trigger>(Trigger.Pattern(listOf(0, 4, 8, 2, 6))), offered)
        }
        withCover({ MusicCover(hasAccess = false, onTrigger = {}, persist = false, showDots = false) }) {
            compose.onNodeWithTag(PATTERN_GRID_TAG).assertDoesNotExist()
        }
        withCover({ MusicCover(hasAccess = false, onTrigger = {}, persist = false, showDots = true) }) {
            compose.onNodeWithTag(PATTERN_GRID_TAG).assertExists()
        }
    }

    /** The hosted cover takes no focus from a press; a live one does. */
    @Test
    fun theHostedRecorderTakesNoFocusButALiveOneDoes() {
        withSetup(LauncherIdentity.VoiceRecorder) {
            startRecording()
            compose.onAllNodes(hasSetTextAction())[0].performClick()
            compose.waitForIdle()
            compose.onAllNodes(hasSetTextAction())[0].assertIsNotFocused()
        }
        withCover({ RecorderCover(hasPermission = true, onTrigger = {}) }) {
            compose.onAllNodes(hasSetTextAction())[0].performClick()
            compose.waitForIdle()
            compose.onAllNodes(hasSetTextAction())[0].assertIsFocused()
        }
    }

    // --- the pattern grid — `pattern-cover-trigger` --------------------------

    /**
     * Node `n`'s centre within whatever node [performTouchInput] is targeting — the
     * same nine-cell arithmetic `gridNodeAt` hit-tests against, so landing here always
     * registers. `width`/`height` come from [TouchInjectionScope]'s own target node,
     * which is why this is an extension on it rather than a free function.
     */
    private fun TouchInjectionScope.centerOf(node: Int): Offset {
        val cellW = width / 3f
        val cellH = height / 3f
        return Offset((node % 3 + 0.5f) * cellW, (node / 3 + 0.5f) * cellH)
    }

    /**
     * Draw a full pattern through [nodes] on [target] and lift, in one continuous
     * gesture — a single [performTouchInput] block, `down` then a `moveTo` per
     * further node then `up`, landing on each node's exact centre so the hit radius
     * is never in question.
     */
    private fun performPattern(target: SemanticsNodeInteraction, vararg nodes: Int) {
        target.performTouchInput {
            down(centerOf(nodes[0]))
            nodes.drop(1).forEach { moveTo(centerOf(it)) }
            up()
        }
        compose.waitForIdle()
    }

    /**
     * Draw through [nodes] **without lifting**, so a test can assert on progress
     * mid-gesture. [finishPattern] or a further [continuePattern] call completes it —
     * Compose tracks the open gesture across separate [performTouchInput] calls on
     * the same node as long as neither `up()` nor `cancel()` has run yet, the same
     * idiom `ViewerNavigationDeviceTest`'s mid-drag screenshots use.
     */
    private fun startPattern(target: SemanticsNodeInteraction, vararg nodes: Int) {
        target.performTouchInput {
            down(centerOf(nodes[0]))
            nodes.drop(1).forEach { moveTo(centerOf(it)) }
        }
        compose.waitForIdle()
    }

    /** Continue an open gesture [startPattern] began, through further nodes, still without lifting. */
    private fun continuePattern(target: SemanticsNodeInteraction, vararg nodes: Int) {
        target.performTouchInput { nodes.forEach { moveTo(centerOf(it)) } }
        compose.waitForIdle()
    }

    /** Lift the pointer an open gesture left down, completing or abandoning it. */
    private fun finishPattern(target: SemanticsNodeInteraction) {
        target.performTouchInput { up() }
        compose.waitForIdle()
    }

    /** The area a live cover's grid spans: the whole screen, since no header sits above it. */
    private fun coverRoot(): SemanticsNodeInteraction = compose.onRoot()

    /** The area a pattern cover's grid spans during setup — narrower than the root; see [COVER_HOST_TAG]. */
    private fun setupCoverHost(): SemanticsNodeInteraction = compose.onNodeWithTag(COVER_HOST_TAG)
}
