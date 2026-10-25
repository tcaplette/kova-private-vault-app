package com.app.vault

import android.app.KeyguardManager
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.app.vault.ui.PATTERN_GRID_TAG
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.TouchInjectionScope
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The cover across the activity's lifecycle — `cover-biometric-suppression`.
 *
 * **The one file in the suite that launches `MainActivity` to test a cover, and it is a
 * separate file for that reason.** `CoverDeviceTest` and `CoverScreenTest` both state, at
 * the top, that they launch the real activity nowhere: P2-23 found that doing so
 * repeatedly broke `BiometricWrapTest` in a full-suite run while every class passed
 * alone. The mechanism is `MainActivity.onCreate`, which calls `BiometricWrap.isEnabled()`
 * — a read that deletes the Keystore alias when the wrap behind it is unusable, which is
 * exactly the state a `BiometricWrapTest` run can leave behind.
 *
 * That invariant is not weakened here; the exposure is moved into one file that says so.
 * The claim under test *is* the real activity's lifecycle — `onCreate` raising the cover,
 * `onStart` raising it again — and there is nothing left to test if the activity is
 * replaced by a bare host. Whether it disturbs `BiometricWrapTest` is settled by the
 * change's full-suite run rather than by argument.
 *
 * **Creates no vault in the app's real storage.** `DeviceFixtures` reserves that for a
 * requested fixture. The live-session case builds its keyring under a scratch directory
 * and reaches `MainActivity` through the process-wide `VaultKeyringSession`, which is the
 * only part of it this test needs. The hold cases (`cover-hold-dots`) are the exception, and say
 * so: the cover leads to the unlock screen only where a vault exists, so they make a vault in the
 * app's storage for the length of the case and delete it after ([LiveCover]) — on a device the
 * orchestrator's `clearPackageData` has just emptied.
 *
 * **Restores everything it changes** — the identity, the trigger and the session are all
 * shared with the app the developer is using, and a test that left a cover enabled would
 * change what their home screen shows.
 */
@RunWith(AndroidJUnit4::class)
class CoverLifecycleDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** `sin` is on the calculator's keypad and on no other screen in the app. */
    private val calculatorKey = "sin"

    private val calcTrigger = Trigger.Keys(listOf("7", "3", calculatorKey))

    private lateinit var identityBefore: LauncherIdentity
    private var triggerBefore: Trigger? = null
    private var calcPatternBefore: Trigger.Pattern? = null
    private var calcHoldBefore: Int? = null
    private var musicBefore: Trigger? = null
    private var musicHoldBefore: Int? = null
    private var scratch: File? = null

    @Before
    fun setUp() {
        DeviceLock.awake()
        identityBefore = GridPreference.identity(ctx)
        triggerBefore = CoverTrigger.triggerFor(ctx, Cover.Calculator)
        calcPatternBefore = CoverTrigger.patternFor(ctx, Cover.Calculator)
        calcHoldBefore = CoverTrigger.holdFor(ctx, Cover.Calculator)
        musicBefore = CoverTrigger.triggerFor(ctx, Cover.MusicPlayer)
        musicHoldBefore = CoverTrigger.holdFor(ctx, Cover.MusicPlayer)
        VaultKeyringSession.lock()
        VaultSession.lock()
        CoverTrigger.set(ctx, Cover.Calculator, calcTrigger)
        CoverTrigger.clearAttempts(ctx, Cover.Calculator)
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Calculator)
    }

    @After
    fun restore() {
        // A failed assertion can leave the picker suppression latched on, which would hold
        // the vault open for every suite that runs after this one.
        AutoLock.awaitingResult(false)
        VaultKeyringSession.lock()
        VaultSession.lock()
        scratch?.deleteRecursively()
        // Whichever of its three methods it had — `calculator-dots-methods`.
        CoverTrigger.clear(ctx, Cover.Calculator)
        triggerBefore?.let { CoverTrigger.set(ctx, Cover.Calculator, it, dots = calcPatternBefore) }
            ?: calcPatternBefore?.let { CoverTrigger.set(ctx, Cover.Calculator, it, area = calcHoldBefore) }
        CoverTrigger.clearAttempts(ctx, Cover.Calculator)
        CoverTrigger.clear(ctx, Cover.MusicPlayer)
        musicBefore?.let { CoverTrigger.set(ctx, Cover.MusicPlayer, it, area = musicHoldBefore) }
        CoverTrigger.clearAttempts(ctx, Cover.MusicPlayer)
        LauncherIdentity.switchTo(ctx, identityBefore)
        // The switch only enables; the departure pass leaves exactly one entry.
        LauncherIdentity.reconcile(ctx)
    }

    /** Substring, so an assertion names the thing that matters and not a paragraph. */
    private fun showing(text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun coverIsUp(): Boolean = showing(calculatorKey)

    /**
     * A full trip through the background: stopped, then resumed.
     *
     * `CREATED` is the state a stopped activity holds — `onStop` has run and `onDestroy`
     * has not — so returning to `RESUMED` runs `onStart` without running `onCreate`. That
     * distinction is the whole defect: `onCreate` always raised the cover, and only a
     * process death ran it.
     */
    private fun ActivityScenario<MainActivity>.backgroundAndReturn() {
        moveToState(Lifecycle.State.CREATED)
        moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    /** The stored sequence, completed by the hold on `=`. */
    private fun performTrigger() {
        calcTrigger.keys.forEach { compose.onAllNodes(hasText(it))[0].performClick() }
        compose.waitForIdle()
        compose.onAllNodes(hasText("="))[0]
            .performTouchInput { longClick(durationMillis = 2_500) }
        compose.waitForIdle()
    }

    /**
     * A cover that was never triggered is still there after a trip through the background.
     *
     * The weaker half of the claim, and the one that already held — `coverShowing` was
     * true and nothing lowered it. It is asserted anyway, because `onStart` now writes to
     * that flag and the failure mode of writing it wrongly is a cover that dismisses
     * itself.
     */
    @Test
    fun aCoverThatWasNeverTriggeredSurvivesTheBackground() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            assertTrue("the cover was not shown on launch", coverIsUp())

            scenario.backgroundAndReturn()
            assertTrue("the cover did not survive a trip through the background", coverIsUp())
        }
    }

    /**
     * ***The defect.*** Pass the trigger, go away, come back to a locked vault — and the
     * cover is there, not the vault's unlock screen.
     *
     * Before this change `coverShowing` was written in `onCreate` and cleared by the
     * trigger, and `onStart` never touched it, so this landed on "Locked" — the disguise
     * gone until something killed the process, which auto-lock makes the ordinary case
     * rather than a rare one.
     */
    @Test
    fun aTriggeredCoverComesBackWhenTheVaultLockedWhileAway() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            assertTrue("the cover was not shown on launch", coverIsUp())

            performTrigger()
            assertFalse("the trigger did not dismiss the cover", coverIsUp())

            scenario.backgroundAndReturn()
            assertTrue(
                "the vault was locked while away, so the cover — not the unlock screen — " +
                    "is what a return to the foreground must show",
                coverIsUp(),
            )
        }
    }

    /**
     * A return to the foreground is not a launch.
     *
     * **There is no auto-lock window to come back inside**, and this test was written once
     * on the assumption that there was. P1-10 is explicit that auto-lock is not a grace
     * period — a timeout is something an attacker waits out — so an ordinary trip through
     * the background has already locked by the time `onStart` runs, and the cover going up
     * is the correct answer there. That case is
     * [aTriggeredCoverComesBackWhenTheVaultLockedWhileAway].
     *
     * What actually reaches `onStart` with a session live is one of `AutoLock`'s two
     * suppressions, and this drives the first of them: a picker this app launched, which
     * is the app backgrounding itself. Coming back from the photo picker to a calculator
     * standing in front of the import the user just chose files for would be a worse bug
     * than the one being fixed.
     *
     * `onCreate` still raises the cover over a live session, which is `cover-front-ends`'
     * requirement of a launch and is asserted first.
     */
    @Test
    fun aLiveSessionReturnsToTheVaultAndNotToTheCover() {
        openAScratchKeyring()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            assertTrue("a launch shows the cover even with a session live", coverIsUp())

            performTrigger()
            assertFalse("the trigger did not dismiss the cover", coverIsUp())

            // Exactly what every `ActivityResultLauncher.launch` in the app is wrapped in.
            AutoLock.awaitingResult(true)
            scenario.backgroundAndReturn()
            assertTrue("the suppression did not hold the keyring open", VaultKeyringSession.isOpen)
            assertFalse(
                "the session is still live, so the return belongs to the vault — raising " +
                    "the cover on the way back from a picker would put a calculator in " +
                    "front of the import the user just chose files for",
                coverIsUp(),
            )
            AutoLock.awaitingResult(false)
        }
    }

    // --- the hold — `cover-hold-dots` --------------------------------------------------
    //
    // Here and not in `CoverDeviceTest` because the decision under test is the real activity's:
    // `onStart` reading the stored area, the hold bringing up its dots, its Back handler, and
    // `coverTrigger`.

    private val musicPattern = Trigger.Pattern(listOf(0, 4, 8, 2, 6))

    /** The music player worn with [musicPattern] and [area] as its hold. */
    private fun wearMusicPlayer(area: Int = 0) {
        assertTrue(CoverTrigger.set(ctx, Cover.MusicPlayer, musicPattern, area = area))
        CoverTrigger.clearAttempts(ctx, Cover.MusicPlayer)
        LauncherIdentity.switchTo(ctx, LauncherIdentity.MusicPlayer)
    }

    private fun dotsShown(): Boolean =
        compose.onAllNodesWithTag(PATTERN_GRID_TAG).fetchSemanticsNodes().isNotEmpty()

    /** "Tracks" is the music player's first tab and on no other screen in the app. */
    private fun musicPlayerUp(): Boolean = showing("Tracks")

    private fun playerExpanded(): Boolean =
        compose.onAllNodesWithContentDescription("Close player").fetchSemanticsNodes().isNotEmpty()

    /** The mini player's title, which expands the player on release when nothing holds the cover. */
    private fun TouchInjectionScope.miniPlayer() = Offset(width * 0.4f, height - 72.dp.toPx())

    private fun tapMiniPlayer() {
        compose.onRoot().performTouchInput { click(miniPlayer()) }
        compose.waitForIdle()
    }

    /** The centre of hold [area] — the 12 cells over the cover's bounds, as `holdAreaAt` divides them. */
    private fun TouchInjectionScope.centreOf(area: Int) =
        Offset((area % HOLD_COLUMNS + 0.5f) * width / HOLD_COLUMNS, (area / HOLD_COLUMNS + 0.5f) * height / HOLD_ROWS)

    /** Which hold area the mini player's title is in, on this screen. */
    private fun miniPlayerArea(): Int {
        var area = -1
        compose.onRoot().performTouchInput {
            val at = miniPlayer()
            area = holdAreaAt(at.x, at.y, width.toFloat(), height.toFloat())
        }
        return area
    }

    /** One finger held at [at] for [ms], then lifted. */
    private fun hold(ms: Long, at: TouchInjectionScope.() -> Offset) {
        compose.onRoot().performTouchInput {
            down(at())
            advanceEventTime(ms)
            up()
        }
        compose.waitForIdle()
    }

    private fun drawPattern(vararg nodes: Int) {
        compose.onRoot().performTouchInput {
            fun at(n: Int) = Offset((n % 3 + 0.5f) * width / 3f, (n / 3 + 0.5f) * height / 3f)
            down(at(nodes[0]))
            nodes.drop(1).forEach { moveTo(at(it)) }
            up()
        }
        compose.waitForIdle()
    }

    private fun await(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            compose.waitForIdle()
            if (condition()) return
            check(SystemClock.elapsedRealtime() < until) { "timed out waiting: $what" }
            SystemClock.sleep(100)
        }
    }

    /** No fingerprint operation is running on the device — `dumpsys fingerprint`'s scheduler. */
    private fun noFingerprintOperation(): Boolean {
        val out = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys fingerprint")
            .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
        return out.contains("Current operation: null")
    }

    /**
     * D2, D4. A 2-second hold in the stored area brings up the dots and holds the cover still; the
     * right pattern on them reaches the unlock screen, where the passphrase is still asked for. No
     * fingerprint operation runs while the cover is up, and nothing is raised over it.
     */
    @Test
    fun aHoldInTheStoredAreaBringsUpTheDotsAndThePatternReachesTheUnlockScreen() {
        val keyring = LiveCover.openKeyring()
        try {
            wearMusicPlayer(area = 0)
            VaultKeyringSession.lock()
            VaultSession.lock()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitForIdle()
                assertTrue("the music player was not shown", musicPlayerUp())
                assertFalse("dots with nothing held", dotsShown())
                assertTrue("a fingerprint operation runs under the cover", noFingerprintOperation())
                scenario.onActivity { assertTrue("something was raised over the cover", it.hasWindowFocus()) }

                hold(2_500) { centreOf(0) }
                assertTrue("the hold did not bring up the dots", dotsShown())
                assertTrue(showing(ctx.getString(com.app.vault.ui.DRAW_PATTERN)))
                assertTrue("a fingerprint operation ran for the hold", noFingerprintOperation())
                tapMiniPlayer()
                assertFalse("the shown dots let the cover act on a tap", playerExpanded())

                drawPattern(*musicPattern.dots.toIntArray())
                await("the unlock screen") { showing("Enter your passphrase") }
                assertFalse("the pattern on the shown dots opened the keyring", VaultKeyringSession.isOpen)
            }
        } finally {
            keyring.close()
        }
    }

    /**
     * D2. A hold anywhere else — here over the mini player, which a release expands — does what
     * that press does with no trigger set, and brings up nothing; a 1.5 s hold in the stored area
     * brings up nothing either. The same press in the stored area is the hold's: the dots, and
     * the release taken from the control.
     */
    @Test
    fun aHoldElsewhereOrTooShortDoesNothing() {
        val keyring = LiveCover.openKeyring()
        try {
            wearMusicPlayer(area = 0)
            VaultKeyringSession.lock()
            VaultSession.lock()
            var mini = -1
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitForIdle()
                mini = miniPlayerArea()
                assertTrue("the mini player sits in the stored area", mini != 0)
                hold(1_500) { centreOf(0) }
                assertFalse("a 1.5 s hold brought up the dots", dotsShown())

                hold(2_500) { miniPlayer() }
                assertFalse("a hold in another area brought up the dots", dotsShown())
                assertTrue("the control under a hold elsewhere did not act as it always does", playerExpanded())
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
                assertEquals("a hold was judged as an attempt", Attempts(), CoverTrigger.attempts(ctx, Cover.MusicPlayer))
            }
            wearMusicPlayer(area = mini)
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForIdle()
                hold(2_500) { miniPlayer() }
                assertTrue("the hold in the stored area did not bring up the dots", dotsShown())
                assertFalse("the control under the hold acted on release", playerExpanded())
            }
        } finally {
            keyring.close()
        }
    }

    /**
     * D4. A wrong pattern on shown dots shows the error, then the dots go, and the limiter counts
     * one; another hold brings them back, and Back hides them with the app still in front.
     */
    @Test
    fun aWrongPatternOnTheDotsShowsTheErrorAndBackHidesThem() {
        val keyring = LiveCover.openKeyring()
        try {
            wearMusicPlayer(area = 0)
            VaultKeyringSession.lock()
            VaultSession.lock()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitForIdle()
                hold(2_500) { centreOf(0) }
                drawPattern(1, 3, 5, 7, 4)
                assertTrue("no error on the dots", showing(ctx.getString(com.app.vault.ui.WRONG_PATTERN)))
                assertEquals("the limiter did not count it", 1, CoverTrigger.attempts(ctx, Cover.MusicPlayer).failures)
                await("the dots to go after the error", 3_000) { !dotsShown() }
                assertTrue("the cover went with the dots", musicPlayerUp())

                hold(2_500) { centreOf(0) }
                assertTrue("another hold did not bring the dots back", dotsShown())
                assertFalse("the error outlived its dots", showing(ctx.getString(com.app.vault.ui.WRONG_PATTERN)))
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
                assertFalse("Back left the dots up", dotsShown())
                assertEquals("Back left the app", Lifecycle.State.RESUMED, scenario.state)
                assertTrue("Back took the cover away", musicPlayerUp())
                tapMiniPlayer()
                assertTrue("the cover did not act again once its dots were gone", playerExpanded())
            }
        } finally {
            keyring.close()
        }
    }

    /** D1. Turned sideways, the cells are the rotated screen's: its top-left cell brings up the dots. */
    @Test
    fun landscapeUsesTheRotatedCells() {
        val keyring = LiveCover.openKeyring()
        try {
            wearMusicPlayer(area = 0)
            VaultKeyringSession.lock()
            VaultSession.lock()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitForIdle()
                scenario.onActivity {
                    it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                await("landscape") {
                    var wide = false
                    scenario.onActivity {
                        wide = it.resources.configuration.orientation ==
                            android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    }
                    wide && musicPlayerUp()
                }
                hold(2_500) { centreOf(1) }
                assertFalse("the top-centre cell of the rotated screen brought up the dots", dotsShown())
                hold(2_500) { centreOf(0) }
                assertTrue("the rotated screen's top-left cell did not bring up the dots", dotsShown())
                scenario.onActivity {
                    it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        } finally {
            keyring.close()
        }
    }

    // --- the calculator's dots — `calculator-dots-methods` ------------------------------
    //
    // Here for the music player's reason: the decision under test is the real activity's —
    // `onStart` reading the method and the area, the dots, Back, and `coverTrigger`.

    private val calcPattern = Trigger.Pattern(listOf(0, 4, 8, 2, 6))

    /** The calculator worn as **Button sequence, then pattern**: [calcTrigger], then [calcPattern]. */
    private fun wearSequenceThenPattern() {
        assertTrue(CoverTrigger.set(ctx, Cover.Calculator, calcTrigger, dots = calcPattern))
        CoverTrigger.clearAttempts(ctx, Cover.Calculator)
    }

    /** The calculator worn as **Hold, then pattern**, with [calcPattern] and [area] as its hold. */
    private fun wearHoldThenPattern(area: Int) {
        assertTrue(CoverTrigger.set(ctx, Cover.Calculator, calcPattern, area = area))
        CoverTrigger.clearAttempts(ctx, Cover.Calculator)
    }

    /** A calculator key — clickable, so never the display showing the same symbol. */
    private fun key(label: String) = compose.onAllNodes(hasText(label) and hasClickAction())[0]

    /** The hold area [label]'s key sits in, on this screen. */
    private fun areaOf(label: String): Int {
        val b = key(label).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        return holdAreaAt(b.center.x, b.center.y, root.width, root.height)
    }

    /** Everything the calculator's display says: the text on screen that is not a control. */
    private fun display(): List<String> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and !hasClickAction())
            .fetchSemanticsNodes()
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }

    private fun tap(vararg labels: String) {
        labels.forEach { key(it).performClick() }
        compose.waitForIdle()
    }

    /** One finger on [label]'s key for [ms], then lifted. */
    private fun holdOn(label: String, ms: Long = 2_500) {
        key(label).performTouchInput {
            down(center)
            advanceEventTime(ms)
            up()
        }
        compose.waitForIdle()
    }

    /**
     * D2, D4, row 2. The right sequence and a hold on `=` bring up the dots, and a key under them
     * enters nothing; Back takes them away with the app in front and the display as typed; the
     * right pattern on them reaches the unlock screen, where the passphrase is still asked for.
     */
    @Test
    fun theCalculatorsSequenceBringsUpItsDotsAndThePatternReachesTheUnlockScreen() {
        val keyring = LiveCover.openKeyring()
        try {
            wearSequenceThenPattern()
            VaultKeyringSession.lock()
            VaultSession.lock()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitForIdle()
                assertTrue("the calculator was not shown", coverIsUp())
                assertFalse("dots with nothing entered", dotsShown())
                tap(*calcTrigger.keys.toTypedArray())
                val typed = display()
                holdOn("=")
                assertTrue("the right sequence did not bring up the dots", dotsShown())
                assertTrue(showing(ctx.getString(com.app.vault.ui.DRAW_PATTERN)))
                assertTrue("the sequence took the cover down", coverIsUp())
                assertEquals("the sequence cleared the limiter's record", Attempts(), CoverTrigger.attempts(ctx, Cover.Calculator))
                tap("7")
                assertEquals("a key under the dots entered itself", typed, display().filter { it != ctx.getString(com.app.vault.ui.DRAW_PATTERN) })

                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
                assertFalse("Back left the dots up", dotsShown())
                assertEquals("Back left the app", Lifecycle.State.RESUMED, scenario.state)
                assertEquals("the display did not keep what was typed", typed, display())

                holdOn("=")
                assertTrue("the sequence did not bring the dots back", dotsShown())
                drawPattern(*calcPattern.dots.toIntArray())
                await("the unlock screen") { showing("Enter your passphrase") }
                assertFalse("the pattern on the dots opened the keyring", VaultKeyringSession.isOpen)
            }
        } finally {
            keyring.close()
        }
    }

    /**
     * D4, row 2. A wrong pattern on the dots shows the error, the limiter counts it and the dots go;
     * a wrong sequence brings up nothing and counts one more; the right sequence during a lockout
     * brings up nothing.
     */
    @Test
    fun theCalculatorsWrongPatternWrongSequenceAndLockout() {
        wearSequenceThenPattern()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            tap(*calcTrigger.keys.toTypedArray())
            holdOn("=")
            assertTrue(dotsShown())
            drawPattern(1, 3, 5, 7, 4)
            assertTrue("no error on the dots", showing(ctx.getString(com.app.vault.ui.WRONG_PATTERN)))
            assertEquals("the limiter did not count it", 1, CoverTrigger.attempts(ctx, Cover.Calculator).failures)
            await("the dots to go after the error", 3_000) { !dotsShown() }
            assertTrue("the calculator went with the dots", coverIsUp())

            tap("AC", "1", "1", "1")
            holdOn("=")
            assertFalse("a wrong sequence brought up the dots", dotsShown())
            assertFalse(showing(ctx.getString(com.app.vault.ui.WRONG_PATTERN)))
            assertEquals("the wrong sequence was not counted", 2, CoverTrigger.attempts(ctx, Cover.Calculator).failures)

            repeat(Attempts.MAX_FAILURES) { CoverTrigger.attempt(ctx, Cover.Calculator, Trigger.Keys(listOf("1", "1", "1"))) }
            assertTrue(CoverTrigger.attempts(ctx, Cover.Calculator).lockedAt(System.currentTimeMillis()))
            tap("AC", *calcTrigger.keys.toTypedArray())
            holdOn("=")
            assertFalse("the right sequence during a lockout brought up the dots", dotsShown())
        }
    }

    /**
     * D3, row 3. A 2 s hold in the stored area, on a key, brings up the dots and does not enter the
     * key; a hold elsewhere is the key's own press; `=` held and released evaluates; the right
     * pattern reaches the unlock screen.
     */
    @Test
    fun theCalculatorsHoldBringsUpItsDotsAndEqualsIsAPlainKey() {
        val keyring = LiveCover.openKeyring()
        try {
            // Where the 7 key is depends on the screen, so the stored area is found, not assumed.
            var seven = -1
            wearHoldThenPattern(area = 0)
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForIdle()
                seven = areaOf("7")
                assertTrue("8 and = sit in 7's area", areaOf("8") != seven && areaOf("=") != seven)
            }
            wearHoldThenPattern(area = seven)
            VaultKeyringSession.lock()
            VaultSession.lock()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitForIdle()
                val before = display()
                holdOn("7")
                assertTrue("the hold in the stored area did not bring up the dots", dotsShown())
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
                assertEquals("the key under the hold was entered", before, display())

                holdOn("8")
                assertFalse("a hold elsewhere brought up the dots", dotsShown())
                assertTrue("the key under a hold elsewhere was not entered", display().any { it.contains("8") })

                tap("AC", "2", "+", "2")
                holdOn("=")
                assertFalse("= brought up the dots", dotsShown())
                assertTrue("a held = did not evaluate", display().contains("4"))
                assertEquals("a hold was judged as an attempt", Attempts(), CoverTrigger.attempts(ctx, Cover.Calculator))

                holdOn("7")
                drawPattern(*calcPattern.dots.toIntArray())
                await("the unlock screen") { showing("Enter your passphrase") }
                assertFalse(VaultKeyringSession.isOpen)
            }
        } finally {
            keyring.close()
        }
    }

    /**
     * D3, row 3. The stored pattern drawn over the hidden keypad is the keypad's own: no dots, no
     * unlock screen, no attempt — the calculator has no grid while its dots are hidden.
     */
    @Test
    fun theStoredPatternOverTheHiddenKeypadOpensNothing() {
        wearHoldThenPattern(area = 0)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            drawPattern(*calcPattern.dots.toIntArray())
            assertFalse("the hidden keypad drew dots", dotsShown())
            assertTrue("the pattern over the hidden keypad took the cover down", coverIsUp())
            assertFalse(showing("Enter your passphrase"))
            assertEquals("a drag on the keypad was judged as an attempt", Attempts(), CoverTrigger.attempts(ctx, Cover.Calculator))
        }
    }

    /** D3, row 3. Turned sideways, the rotated screen's cells; leaving the foreground hides the dots. */
    @Test
    fun theCalculatorsHoldUsesTheRotatedCellsAndLeavingHidesTheDots() {
        wearHoldThenPattern(area = 0)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            hold(2_500) { centreOf(0) }
            assertTrue(dotsShown())
            scenario.backgroundAndReturn()
            assertTrue(coverIsUp())
            assertFalse("leaving the foreground left the dots up", dotsShown())

            scenario.onActivity {
                it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            await("landscape") {
                var wide = false
                scenario.onActivity {
                    wide = it.resources.configuration.orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE
                }
                wide && coverIsUp()
            }
            hold(2_500) { centreOf(1) }
            assertFalse("the top-centre cell of the rotated screen brought up the dots", dotsShown())
            hold(2_500) { centreOf(0) }
            assertTrue("the rotated screen's top-left cell did not bring up the dots", dotsShown())
            scenario.onActivity {
                it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    /**
     * A keyring in the process-wide session, opened from a directory of this test's own.
     *
     * `MainActivity` reads `VaultKeyringSession.isOpen`, which is process state and not a
     * file, so nothing here needs to touch `filesDir/vault.slots` — the app's real vault
     * is neither read nor written.
     */
    private fun openAScratchKeyring() {
        val root = File(ctx.filesDir, "coverlifecycle-${System.nanoTime()}").also { it.mkdirs() }
        scratch = root
        val lifecycle = VaultLifecycle(
            File(root, "vault.slots"),
            BlobStore(File(root, "blobs"), File(root, "incoming")),
        )
        lifecycle.create("correcthorsebatterystaple".toByteArray(Charsets.UTF_8))
        assertTrue("the scratch keyring did not open", VaultKeyringSession.isOpen)
    }
}

/**
 * The live runs shared with `CaptureDeviceTest`: the real activity, the emulator's sensor, and a
 * keyring that activity can open. The sensor is touched only for the vault's own biometric unlock
 * at the unlock screen — nothing reads it on a cover (`cover-hold-dots`).
 *
 * **The sensor is the host's, touched when the test asks.** An instrumented test cannot reach
 * the emulator's console — it runs as the app, which holds no `INTERNET` — so each touch is asked
 * for with a `VaultFinger` log line and the host relays it:
 *
 *     adb -s emulator-5554 logcat -c
 *     adb -s emulator-5554 logcat -s VaultFinger:I -v raw | while read -r line; do
 *       case "$line" in "touch "*) adb -s emulator-5554 emu finger $line ;; esac
 *     done
 *
 * and the run passes `-e finger relay`. A finger landing at a chosen moment, and which finger, is
 * what these cases are about; the earlier tests' touch every couple of seconds would race them.
 * Finger 1 must be enrolled on the emulator, and finger 2 must not be.
 */
internal object LiveCover {

    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instr.targetContext

    const val PASSPHRASE = "the-live-cover-keyring-passphrase"

    fun relayed(): Boolean = InstrumentationRegistry.getArguments().getString("finger") == "relay"

    fun assumeRelay() = org.junit.Assume.assumeTrue(
        "a finger at a chosen moment needs -e finger relay, with the host relaying VaultFinger lines " +
            "to `adb -s emulator-5554 emu finger touch`",
        relayed(),
    )

    /** Ask the host to touch [finger] on the emulator's sensor. */
    fun touch(finger: Int) {
        android.util.Log.i("VaultFinger", "touch $finger")
    }

    /**
     * A vault in the app's own storage, its keyring and first vault open in the process — the
     * activity resolves a wrap against its own blob store, and reaches the unlock screen only
     * where `vault.slots` exists. Made only where there is none, which under the orchestrator's
     * `clearPackageData` is every run; [close] deletes the slot file, every blob it added, and
     * the wrap it may have linked.
     */
    class Keyring(private val blobsBefore: Set<String>) {
        val store = BlobStore(File(ctx.filesDir, "blobs"), File(ctx.filesDir, "incoming"))
        val lifecycle = VaultLifecycle(File(ctx.filesDir, "vault.slots"), store)

        fun close() {
            VaultKeyringSession.lock()
            VaultSession.lock()
            BiometricWrap(ctx).disable()
            File(ctx.filesDir, "blobs").listFiles().orEmpty()
                .filter { it.name !in blobsBefore }
                .forEach { it.delete() }
            File(ctx.filesDir, "vault.slots").delete()
        }
    }

    fun openKeyring(): Keyring {
        val blobs = File(ctx.filesDir, "blobs").also { it.mkdirs() }
        val keyring = Keyring(blobs.list().orEmpty().toSet())
        // The occupancy guard `DestructiveTestScanTest` recognises: skip, never overwrite.
        org.junit.Assume.assumeTrue(
            "a vault is already on the device, and this run would need its passphrase",
            !keyring.lifecycle.vaultExists(),
        )
        check(keyring.lifecycle.create(PASSPHRASE.toByteArray(Charsets.UTF_8)).opened) { "the keyring did not open" }
        return keyring
    }

    /**
     * Seal the open keyring under [wrap]'s production key, with the enrolled finger on the
     * emulator's sensor — the enable prompt's effect, with no prompt for the test to drive.
     *
     * **With an activity of the app in front.** The platform fails a fingerprint read for an app
     * in the background (`AuthenticationClient`: "background authentication"); so a bare activity
     * stands in while it scans.
     */
    fun link(vararg wraps: BiometricWrap) {
        // One stand-in for every wrap: closing it leaves the app in the background, and auto-lock
        // closes the keyring the next wrap would seal.
        ActivityScenario.launch(androidx.activity.ComponentActivity::class.java).use {
            wraps.forEach { linkInFront(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun linkInFront(wrap: BiometricWrap) {
        val payload = checkNotNull(VaultKeyringSession.wrapPayload()) { "no keyring open to link" }
        val cipher = wrap.encryptCipher()
        val done = java.util.concurrent.CountDownLatch(1)
        var authenticated: javax.crypto.Cipher? = null
        val signal = android.os.CancellationSignal()
        instr.runOnMainSync {
            androidx.core.hardware.fingerprint.FingerprintManagerCompat.from(ctx).authenticate(
                androidx.core.hardware.fingerprint.FingerprintManagerCompat.CryptoObject(cipher),
                0,
                signal,
                object : androidx.core.hardware.fingerprint.FingerprintManagerCompat.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: androidx.core.hardware.fingerprint.FingerprintManagerCompat.AuthenticationResult,
                    ) {
                        authenticated = result.cryptoObject?.cipher
                        done.countDown()
                    }
                },
                null,
            )
        }
        repeat(5) {
            touch(1)
            if (done.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
                wrap.write(checkNotNull(authenticated), payload)
                return
            }
        }
        signal.cancel()
        throw AssertionError("the enrolled finger was never read — is the host relaying, and finger 1 enrolled?")
    }
}

/**
 * The emulator's lock screen, driven from inside the device — shared with `CaptureDeviceTest`.
 * Shell commands only; the fingerprint itself is the host's.
 */
internal object DeviceLock {

    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val keyguard get() = instr.targetContext.getSystemService(KeyguardManager::class.java)
    private val power get() = instr.targetContext.getSystemService(android.os.PowerManager::class.java)

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instr.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }

    private fun await(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < until) { "timed out waiting: $what" }
            SystemClock.sleep(50)
        }
    }

    /**
     * The screen on and the device unlocked, before a case that needs an activity in front — a
     * display that slept between cases leaves every activity paused behind the lock screen. With
     * a secure lock it unlocks with the relayed finger, or `-e pin`; with neither it can only wake.
     */
    fun awake() {
        if (!power.isInteractive) {
            shell("input keyevent KEYCODE_WAKEUP")
            await("the screen to come on", 10_000) { power.isInteractive }
        }
        if (!keyguard.isDeviceLocked) {
            shell("wm dismiss-keyguard")
            return
        }
        val pin = InstrumentationRegistry.getArguments().getString("pin")
        if (LiveCover.relayed()) {
            repeat(10) {
                if (!keyguard.isDeviceLocked) return
                LiveCover.touch(1)
                runCatching { await("the relayed fingerprint", 3_000) { !keyguard.isDeviceLocked } }
            }
        } else if (pin != null) {
            unlockWithPin(pin)
        }
    }

    private fun unlockWithPin(pin: String) {
        // A secure lock screen answers this with its PIN pad.
        shell("wm dismiss-keyguard")
        SystemClock.sleep(800)
        shell("input text $pin")
        shell("input keyevent KEYCODE_ENTER")
        await("the PIN to unlock the device", 15_000) { !keyguard.isDeviceLocked }
    }
}
