package com.app.vault

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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

/**
 * The recent-apps card shows the identity — `recents-shows-identity`.
 *
 * The leave and return ordering of D4, driven on the real activity: `onUserLeaveHint` through
 * `Instrumentation.callActivityOnUserLeaving`, the stop and start through `ActivityScenario`.
 * What the card actually looks like is the change's on-device check, through the launcher; this
 * file holds the order that keeps vault content off it.
 *
 * **Makes a vault in the app's storage for the length of each case** ([LiveCover.openKeyring]),
 * which skips rather than overwrites when one is already there, and deletes it after. Restores the
 * identity and the calculator trigger it changes.
 *
 * Writes no file: the hidden-layer case compares two captures in memory.
 */
@RunWith(AndroidJUnit4::class)
class RecentsIdentityDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instr.targetContext

    /** `sin` is on the calculator's keypad and on no other screen in the app. */
    private val calculatorKey = "sin"
    private val calcTrigger = Trigger.Keys(listOf("7", "3", calculatorKey))

    private lateinit var identityBefore: LauncherIdentity
    private var triggerBefore: Trigger? = null
    private var calcPatternBefore: Trigger.Pattern? = null
    private var calcHoldBefore: Int? = null
    private var keyring: LiveCover.Keyring? = null

    @Before
    fun setUp() {
        DeviceLock.awake()
        identityBefore = GridPreference.identity(ctx)
        triggerBefore = CoverTrigger.triggerFor(ctx, Cover.Calculator)
        calcPatternBefore = CoverTrigger.patternFor(ctx, Cover.Calculator)
        calcHoldBefore = CoverTrigger.holdFor(ctx, Cover.Calculator)
        VaultKeyringSession.lock()
        VaultSession.lock()
        keyring = LiveCover.openKeyring()
    }

    @After
    fun restore() {
        AutoLock.awaitingResult(false)
        keyring?.close()
        CoverTrigger.clear(ctx, Cover.Calculator)
        triggerBefore?.let { CoverTrigger.set(ctx, Cover.Calculator, it, dots = calcPatternBefore) }
            ?: calcPatternBefore?.let { CoverTrigger.set(ctx, Cover.Calculator, it, area = calcHoldBefore) }
        CoverTrigger.clearAttempts(ctx, Cover.Calculator)
        LauncherIdentity.switchTo(ctx, identityBefore)
        LauncherIdentity.reconcile(ctx)
    }

    // --- helpers -------------------------------------------------------------------------

    private fun wear(identity: LauncherIdentity) {
        if (identity.cover == Cover.Calculator) {
            CoverTrigger.set(ctx, Cover.Calculator, calcTrigger)
            CoverTrigger.clearAttempts(ctx, Cover.Calculator)
        }
        LauncherIdentity.switchTo(ctx, identity)
    }

    /** The activity's `leaving` state, read through its delegate — no production accessor for a test. */
    private fun MainActivity.leavingState(): MutableState<*> {
        val field = MainActivity::class.java.getDeclaredField("leaving\$delegate")
        field.isAccessible = true
        return field.get(this) as MutableState<*>
    }

    private fun MainActivity.leaving(): Boolean = leavingState().value as Boolean

    private fun MainActivity.secure(): Boolean =
        window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun ActivityScenario<MainActivity>.secureNow(): Boolean {
        var secure = false
        onActivity { secure = it.secure() }
        return secure
    }

    /** The user chose to leave: what Home, Recents or an app switch call before `onPause`. */
    private fun ActivityScenario<MainActivity>.userLeaves() {
        onActivity { instr.callActivityOnUserLeaving(it) }
    }

    /** Frames until the flag comes off, or false at [timeoutMs]. */
    private fun ActivityScenario<MainActivity>.awaitFlagLifted(timeoutMs: Long = 5_000): Boolean {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            compose.waitForIdle()
            if (!secureNow()) return true
            SystemClock.sleep(50)
        }
        return false
    }

    private fun showing(text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun performTrigger() {
        calcTrigger.keys.forEach { compose.onAllNodes(hasText(it))[0].performClick() }
        compose.waitForIdle()
        compose.onAllNodes(hasText("="))[0].performTouchInput { longClick(durationMillis = 2_500) }
        compose.waitForIdle()
    }

    // --- the cases -----------------------------------------------------------------------

    /**
     * D4, leave and return. The flag is on until a frame of the raised picture commits, comes
     * off after it, and is back on in `onStart` **before** `leaving` drops — asserted at the write
     * itself, so no frame of the live screen can follow the picture down with the flag off.
     */
    @Test
    fun leavingAnOpenVaultLiftsTheFlagOnlyAfterThePictureAndRestoresItFirst() {
        wear(LauncherIdentity.Vault)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            assertTrue("the flag was not set while the app was in front", scenario.secureNow())

            var secureAtFirstCommit: Boolean? = null
            scenario.onActivity { activity ->
                instr.callActivityOnUserLeaving(activity)
                assertTrue("the leave did not raise the picture", activity.leaving())
                assertTrue("the flag came off before any frame of the picture", activity.secure())
                // Registered before the composition's own, so it runs first on the same commit.
                activity.window.decorView.viewTreeObserver.registerFrameCommitCallback {
                    if (secureAtFirstCommit == null) secureAtFirstCommit = activity.secure()
                }
            }
            assertTrue("the flag never came off over the committed picture", scenario.awaitFlagLifted())
            assertEquals("a commit ran with the flag already off", true, secureAtFirstCommit)

            var secureWhenLowered: Boolean? = null
            var activityRef: MainActivity? = null
            scenario.onActivity { activityRef = it }
            val activity = checkNotNull(activityRef)
            val leavingState = activity.leavingState()
            val observer = Snapshot.registerGlobalWriteObserver { written ->
                if (written === leavingState && leavingState.value == false && secureWhenLowered == null) {
                    secureWhenLowered = activity.secure()
                }
            }
            try {
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                compose.waitForIdle()
            } finally {
                observer.dispose()
            }
            assertEquals("the picture came down before the flag was back on", true, secureWhenLowered)
            assertTrue("the flag was not set on return", scenario.secureNow())
            scenario.onActivity { assertFalse("the picture stayed up on return", it.leaving()) }
        }
    }

    /** D1. A result this app is waiting on — the photo picker — raises nothing and lifts nothing. */
    @Test
    fun leavingForAResultRaisesNoPicture() {
        wear(LauncherIdentity.Vault)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            AutoLock.awaitingResult(true)
            scenario.userLeaves()
            scenario.onActivity { assertFalse("a picker launch raised the picture", it.leaving()) }
            assertFalse("the flag came off for a picker launch", scenario.awaitFlagLifted(timeoutMs = 1_500))
            AutoLock.awaitingResult(false)
        }
    }

    /**
     * A leave gesture let go: hint, pause, resume and no stop. The flag is back, the picture is
     * down, and the live calculator still holds the digits typed before it.
     */
    @Test
    fun aCancelledLeaveReturnsToTheScreenAsItWas() {
        wear(LauncherIdentity.Calculator)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            compose.onAllNodes(hasText("7"))[0].performClick()
            compose.onAllNodes(hasText("8"))[0].performClick()
            compose.waitForIdle()
            assertTrue("the typed digits were not on the display", showing("78"))

            scenario.userLeaves()
            assertTrue("the flag never came off over the picture", scenario.awaitFlagLifted())
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()

            assertTrue("the flag was not set after the cancelled leave", scenario.secureNow())
            scenario.onActivity { assertFalse("the picture stayed up after the cancelled leave", it.leaving()) }
            assertTrue("the live display lost what was typed", showing("78"))
        }
    }

    /**
     * D2. The hidden calculator beneath an open vault takes no tap. A tap where its keypad lies
     * would change its display, so the raised picture is captured before and after the taps and
     * the display area compared.
     */
    @Test
    fun theHiddenLayerTakesNoTap() {
        wear(LauncherIdentity.Calculator)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            performTrigger()
            assertFalse("the trigger did not dismiss the cover", showing(calculatorKey))

            val before = scenario.raisedPicture()
            // Down again by a pause and resume: a stop would lock, and the cover would come back.
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            assertFalse("the cover came back without a stop", showing(calculatorKey))

            // Where the hidden keypad lies, on the live screen's **empty** area: every point inside
            // a live control is dropped, so no tap opens anything of the vault's own. Each control's
            // bounds grow by 24dp: Material widens a short button's touch target to 48dp, past
            // what it draws, and a tap 5px under "Add photos" opened the photo picker.
            val margin = 24 * ctx.resources.displayMetrics.density
            // The merged tree, which is the live screen's alone: the unmerged one still lists the
            // children of the hidden layer's `clearAndSetSemantics`, its keys among them.
            val controls = compose.onAllNodes(hasClickAction())
                .fetchSemanticsNodes().map { it.boundsInRoot.inflate(margin) }
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val empty = listOf(0.2f, 0.35f, 0.5f, 0.65f, 0.8f).flatMap { x ->
                listOf(0.55f, 0.62f, 0.7f, 0.78f).map { y -> Offset(root.width * x, root.height * y) }
            }.filter { point -> controls.none { it.contains(point) } }
            assertTrue("the live screen left fewer than three empty points over the keypad", empty.size >= 3)
            assertFalse("a result was already outstanding before the taps", AutoLock.isAwaitingResult)
            compose.onRoot().performTouchInput { empty.forEach { click(it) } }
            compose.waitForIdle()
            assertFalse("a tap on an empty point opened something waiting on a result", AutoLock.isAwaitingResult)

            val after = scenario.raisedPicture()
            assertTrue("a tap reached the hidden calculator: its display changed", sameDisplay(before, after))
        }
    }

    /** D2. The hidden layer is in no semantics tree, so no screen reader and no test finds it. */
    @Test
    fun theHiddenLayerIsAbsentFromSemantics() {
        // First, while the keyring `setUp` opened is still open: the live screen is the vault's.
        wear(LauncherIdentity.Vault)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            assertFalse(
                "the hidden unlock screen is in the semantics tree over an open keyring",
                showing(ctx.getString(R.string.unlock_welcome)),
            )
        }
        // The close above locked the keyring, so past the cover is the live unlock screen.
        wear(LauncherIdentity.Calculator)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            performTrigger()
            assertFalse(
                "the hidden calculator's keys are in the semantics tree",
                showing(calculatorKey),
            )
        }
    }

    /** Leave, wait for the flag to come off over the picture, and capture the display. */
    private fun ActivityScenario<MainActivity>.raisedPicture(): Bitmap {
        userLeaves()
        assertTrue("the flag never came off over the picture", awaitFlagLifted())
        return checkNotNull(instr.uiAutomation.takeScreenshot()) { "no capture" }
    }

    /** The calculator's readout band, below the status bar and above the keypad. */
    private fun sameDisplay(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        val top = (a.height * 0.08f).toInt()
        val bottom = (a.height * 0.35f).toInt()
        val rowA = IntArray(a.width)
        val rowB = IntArray(b.width)
        for (y in top until bottom) {
            a.getPixels(rowA, 0, a.width, 0, y, a.width, 1)
            b.getPixels(rowB, 0, b.width, 0, y, b.width, 1)
            if (!rowA.contentEquals(rowB)) return false
        }
        return true
    }
}
