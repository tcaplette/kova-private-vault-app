package com.app.vault

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.R
import com.app.vault.ui.BiometricLayout
import com.app.vault.ui.BiometricScreen
import com.app.vault.ui.BiometricState
import com.app.vault.ui.CaptureStepScreen
import com.app.vault.ui.FaceDownScreen
import com.app.vault.ui.FingerprintStepScreen
import com.app.vault.ui.MusicCover
import com.app.vault.ui.PATTERN_GRID_TAG
import com.app.vault.ui.RecorderCover
import com.app.vault.ui.NO_FRONT_CAMERA
import com.app.vault.ui.NO_MOTION_SENSOR
import com.app.vault.ui.OnboardingFrame
import com.app.vault.ui.VaultDrawerSheet
import com.app.vault.ui.VaultGrid
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every unavailable form, on screen — `unavailable-capability-states`,
 * `biometric-unlock-screen`.
 *
 * The emulator cannot present a face, a weak-only biometric, a security update, a missing
 * sensor or a missing camera, so each is rendered with the state injected. Each is captured
 * in both schemes, at the default font scale and at 2× — linear, so larger than Android's
 * nonlinear 200% for body text — and each capture first asserts what the spec requires of
 * the form: disabled semantics, the 48dp floor, and text that is never clipped or
 * ellipsised. A greyed switch has no semantics of its own — it cannot be toggled — so the
 * captures are where it is seen.
 *
 * Captures: `RecoveryKitViewsScreenshotTest`'s method — `FLAG_SECURE` cleared on a
 * throwaway activity, `uiAutomation.takeScreenshot()`, saved under [DIR]. Pull them with
 * `adb -s emulator-5554 pull` and look at every one.
 */
@RunWith(AndroidJUnit4::class)
class UnavailableStatesScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext

    private companion object {
        const val DIR = "/data/local/tmp/unavailable-capability-states"
        val SCHEMES = listOf(ThemeChoice.Light to "light", ThemeChoice.Dark to "dark")
        val SCALES = listOf(1f to "1x", 2f to "2x")
        val NON_READY = BiometricState.entries.filterNot { it.ready }
    }

    /**
     * [content] in each scheme and scale: [check] runs on each, then a capture is saved. Where
     * [end] is given it then scrolls to the foot of the content and a second capture is saved,
     * so what lies below the fold at 2× is looked at too.
     */
    private fun each(name: String, content: @Composable () -> Unit, end: (() -> Unit)? = null, check: () -> Unit) {
        var theme by mutableStateOf(ThemeChoice.Light)
        var scale by mutableStateOf(1f)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent {
                    VaultTheme(theme) {
                        val density = LocalDensity.current.density
                        // The covers follow the system's dark flag, not the vault's appearance, so
                        // the scheme is the system's too — `cover-unlock-methods`' revealed grids.
                        val base = LocalConfiguration.current
                        val config = Configuration(base).apply {
                            uiMode = (base.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                if (theme == ThemeChoice.Dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                        }
                        CompositionLocalProvider(
                            LocalDensity provides Density(density, scale),
                            LocalConfiguration provides config,
                        ) { content() }
                    }
                }
            }
            for ((t, tn) in SCHEMES) for ((f, fn) in SCALES) {
                instr.runOnMainSync { theme = t; scale = f }
                compose.waitForIdle()
                check()
                shot("$name-$tn-$fn")
                end?.let { it(); shot("$name-$tn-$fn-end") }
            }
        }
    }

    // --- the Biometric unlock screen ----------------------------------------------

    /** One screen case: the inputs, and the rows it must draw, in order. */
    private class Case(
        val name: String,
        val layout: BiometricLayout,
        val state: BiometricState,
        val fingerprint: Boolean,
        val face: Boolean,
    )

    /**
     * `biometric-unlock-screen`'s list: every layout, and the live row in every state. The
     * Fingerprint layout in Off, On and NotSetUp is also driven for real on the emulator;
     * these are the same states rendered beside the ones it cannot produce.
     */
    private val cases = listOf(
        // A Samsung: a strong fingerprint beside a Class 2 face, so the face row says why.
        Case("fingerprint-Off", BiometricLayout.Fingerprint, BiometricState.Off, true, true),
        Case("fingerprint-On", BiometricLayout.Fingerprint, BiometricState.On, true, false),
        Case("fingerprint-NotSetUp", BiometricLayout.Fingerprint, BiometricState.NotSetUp, true, false),
        Case("fingerprint-NotAvailableNow", BiometricLayout.Fingerprint, BiometricState.NotAvailableNow, true, false),
        Case("fingerprint-NeedsUpdate", BiometricLayout.Fingerprint, BiometricState.NeedsUpdate, true, false),
        // A Pixel 4: a strong face and no fingerprint sensor.
        Case("face-Off", BiometricLayout.Face, BiometricState.Off, false, true),
        // A Pixel 8: both strong, one row.
        Case("both-Off", BiometricLayout.Both, BiometricState.Off, true, true),
        Case("unnamed-Off", BiometricLayout.Unnamed, BiometricState.Off, false, false),
        // Nothing at all, and a weak face beside a weak fingerprint.
        Case("none-NotSupported", BiometricLayout.None, BiometricState.NotSupported, false, false),
        Case("none-NotSecureEnough", BiometricLayout.None, BiometricState.NotSecureEnough, true, true),
    )

    /** The rows [c] draws, name then line, and whether each is greyed. */
    private fun rowsOf(c: Case): List<Triple<String, String, Boolean>> {
        val live = BiometricWrap.liveName(c.layout)?.let {
            Triple(ctx.getString(it), ctx.getString(BiometricWrap.liveLine(c.state)!!), !c.state.ready && !c.state.fixable)
        }
        fun greyed(face: Boolean) = Triple(
            ctx.getString(if (face) BiometricWrap.FACE_ROW else BiometricWrap.FINGERPRINT_ROW),
            ctx.getString(BiometricWrap.unavailableLine(face, if (face) c.face else c.fingerprint)),
            true,
        )
        return when (c.layout) {
            BiometricLayout.Fingerprint -> listOf(live!!, greyed(face = true))
            BiometricLayout.Face -> listOf(greyed(face = false), live!!)
            BiometricLayout.Both, BiometricLayout.Unnamed -> listOf(live!!)
            BiometricLayout.None -> listOf(greyed(face = false), greyed(face = true))
        }
    }

    /** Each row: present, 48dp, whole, and in the form its state gives it. */
    private fun assertRows(c: Case) {
        rowsOf(c).forEach { (name, line, greyed) ->
            val row = compose.onNode(hasText(name, substring = false) and hasText(line, substring = false))
                .performScrollTo().assertHeightIsAtLeast(48.dp)
            when {
                greyed -> row.assertIsNotEnabled()
                c.state.ready -> row.assert(isToggleable())
                else -> row.assertHasClickAction()
            }
            assertWholeText(name)
            assertWholeText(line)
        }
    }

    /** Every fact the layout gives, scrolled to and whole. */
    private fun assertFacts(layout: BiometricLayout) {
        BiometricWrap.facts(layout).forEach { (heading, body) ->
            compose.onNode(hasText(ctx.getString(body))).performScrollTo()
            assertWholeText(ctx.getString(heading))
            assertWholeText(ctx.getString(body))
        }
    }

    private fun scrollToLastFact(layout: BiometricLayout) {
        compose.onNode(hasText(ctx.getString(BiometricWrap.facts(layout).last().second))).performScrollTo()
    }

    @Test
    fun theBiometricScreenInEveryLayoutAndState() {
        cases.forEach { c ->
            each("screen-${c.name}", {
                BiometricScreen(
                    layout = c.layout, state = c.state, hasFingerprint = c.fingerprint, hasFace = c.face,
                    onToggle = {}, onSetUp = {}, onBack = {},
                )
            }, end = { scrollToLastFact(c.layout) }) {
                assertFacts(c.layout)
                assertWholeText(ctx.getString(BiometricWrap.LEAD))
                // Rows last, so the capture is taken from the top.
                assertRows(c)
                assertEquals(
                    "${c.name}: only a live On/Off row can be toggled",
                    if (c.state.ready) 1 else 0, switches(),
                )
            }
        }
    }

    // --- the drawer's biometric and face-down rows ------------------------------

    @Composable
    private fun Drawer(state: BiometricState) {
        VaultDrawerSheet(
            owned = false,
            trialDaysRemaining = 12,
            trialStarted = true,
            onPurchase = {},
            currentIdentity = LauncherIdentity.Vault,
            onIdentity = {},
            biometricState = state,
            onBiometric = {},
            // No accelerometer in every capture. Since `face-down-lock-settings` the row is not
            // itself gated — it says so and opens its screen, whose switch is.
            faceDownAvailable = false,
            faceDownOn = false,
            faceDownLockAfter = 0L,
            onFaceDown = {},
            decoySetUp = false,
            onSetUpDecoy = {},
            onPanicCredential = {},
            onIntruderCapture = {},
            onExport = {},
            currentAppearance = ThemeChoice.System,
            onChooseAppearance = {},
        )
    }

    /** `face-down-lock-settings` D6: the row says it, and still opens the screen. */
    private fun assertFaceDownRowSaysNotSupported() {
        compose.onNode(
            hasText("Face-down lock") and hasText(ctx.getString(R.string.bio_drawer_not_supported), substring = false),
        ).performScrollTo().assertHeightIsAtLeast(48.dp).assertHasClickAction()
    }

    /** The gated face-down control: the Face-down lock screen's main switch, greyed, saying why. */
    @Test
    fun theFaceDownSwitchWithNoMotionSensor() {
        each("face-down-screen-no-sensor", {
            FaceDownScreen(
                available = false,
                on = false,
                lockAfterMs = 0L,
                screenTimeoutMs = Long.MAX_VALUE,
                onToggle = {},
                onLockAfter = {},
                onBack = {},
            )
        }) {
            val row = compose.onNode(
                hasText(ctx.getString(R.string.face_down_switch)) and hasText(ctx.getString(NO_MOTION_SENSOR)),
            ).performScrollTo()
            row.assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
            assertWholeText(ctx.getString(NO_MOTION_SENSOR))
        }
    }

    /**
     * The row opens the screen in every state, with the state in words and no switch; the
     * face-down row beside it does the same, reading "Not supported on this device".
     */
    @Test
    fun theDrawerRowInEveryState() {
        BiometricState.entries.forEach { state ->
            val line = BiometricWrap.drawerLine(state)
            each("drawer-${state.name}", { Drawer(state) }) {
                compose.onNode(hasText(ctx.getString(BiometricWrap.NAME)) and hasText(ctx.getString(line), substring = false)).performScrollTo()
                    .assertHeightIsAtLeast(48.dp).assertHasClickAction()
                assertWholeText(ctx.getString(line))
                assertFaceDownRowSaysNotSupported()
                assertEquals("$state: nothing in the install group can be toggled", 0, switches())
            }
        }
    }

    // --- the first-run biometric step ---------------------------------------------

    /** The step in the emulator's own layout, Off — the screen's rows and facts in the frame. */
    @Test
    fun theBiometricStepInTheFingerprintLayout() {
        val c = Case("step", BiometricLayout.Fingerprint, BiometricState.Off, true, true)
        // Inside the frame, as the app draws it: the step has no ground of its own.
        each("step-fingerprint-Off", {
            OnboardingFrame(step = Onboarding.Step.Fingerprint, onBack = null) {
                FingerprintStepScreen(
                    layout = c.layout, state = c.state, hasFingerprint = c.fingerprint, hasFace = c.face,
                    onToggle = {}, onSetUp = {}, onContinue = {},
                )
            }
        }, end = { scrollToLastFact(c.layout) }) {
            assertFacts(c.layout)
            assertRows(c)
            assertEquals("the step's one switch is the live row's", 1, switches())
        }
    }

    // --- the first-run capture step ------------------------------------------------

    @Test
    fun theCaptureStepWithNoFrontCamera() {
        each("capture-no-front-camera", {
            OnboardingFrame(step = Onboarding.Step.Capture, onBack = null) {
                CaptureStepScreen(
                    enabled = emptySet(), hasFrontCamera = false,
                    onEnable = {}, onDisable = {}, problem = null, onContinue = {},
                )
            }
        }) {
            listOf("Photo", "Video").forEach {
                compose.onNode(hasText(it) and hasText(ctx.getString(NO_FRONT_CAMERA))).performScrollTo()
                    .assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
            }
            assertEquals("only Audio's switch can move — Photo and Video draw theirs greyed out", 1, switches())
        }
    }

    // --- the pattern covers' revealed grid ----------------------------------------

    /** The revealed grid over both pattern covers — the covers in their own light and dark. */
    @Test
    fun theRevealedGridOnBothPatternCovers() {
        each("revealed-music", { MusicCover(hasAccess = false, onTrigger = {}, persist = false, showDots = true) }) {
            compose.onNodeWithTag(PATTERN_GRID_TAG).assertExists()
        }
        each("revealed-recorder", { RecorderCover(hasPermission = false, onTrigger = {}, persist = false, showDots = true) }) {
            compose.onNodeWithTag(PATTERN_GRID_TAG).assertExists()
        }
    }

    // --- the FAB's Camera pill ------------------------------------------------------

    @Test
    fun theCameraPillWithNoCamera() {
        val item = VaultItem(
            blobIds = listOf(ByteArray(16) { 7 }),
            plaintextLength = 1L,
            thumb = null,
            filename = "a.jpg",
            album = null,
            createdAt = null,
            importedAt = 1L,
            tags = emptyList(),
        )
        each("fab-camera-no-camera", {
            VaultGrid(
                items = listOf(item),
                thumbnailFor = { null },
                onOpen = {},
                selected = emptySet(),
                onBeginSelection = {},
                onToggleSelection = {},
                onSelectAll = {},
                onClearSelection = {},
                onDeleteSelected = {},
                onExportSelected = {},
                onImport = {},
                drawerState = DrawerState(DrawerValue.Closed),
                drawerContent = {},
                fabExpanded = true,
                onFabExpandedChange = {},
                cameraAvailable = false,
                vaultName = "Vault",
            )
        }) {
            compose.onNode(hasText("Camera  ·  Not on this device"))
                .assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
            assertWholeText("Camera  ·  Not on this device")
        }
    }

    // --- helpers ----------------------------------------------------------------------

    private fun switches(): Int = compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size

    /**
     * The text laid out whole, at whatever scale is in effect: no line ellipsised, nothing cut
     * off below, and every line's ink inside the box.
     *
     * Not `hasVisualOverflow`: its width half compares the box to the width the paragraph was
     * laid out at, which flags a single short line on sub-pixel rounding while the capture
     * shows it whole. The line edges are what is actually drawn.
     */
    private fun assertWholeText(text: String) {
        // Every node carrying it: the Unnamed row's name is the screen's title as well.
        compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().also {
            assertFalse("\"$text\" is not on screen", it.isEmpty())
        }.forEach { assertWhole(text, it) }
    }

    private fun assertWhole(text: String, node: SemanticsNode) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        val where = "\"$text\" (box ${layout.size}, ${layout.lineCount} lines)"
        assertFalse("$where is cut off below", layout.didOverflowHeight)
        (0 until layout.lineCount).forEach { line ->
            assertFalse("$where: line $line is ellipsised", layout.isLineEllipsized(line))
            assertFalse(
                "$where: line $line ends at ${layout.getLineRight(line)}px, past the box",
                layout.getLineRight(line) > layout.size.width + 1f,
            )
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(600)
        val png = java.io.ByteArrayOutputStream().also {
            instr.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val ui = instr.uiAutomation
        ui.executeShellCommand("mkdir -p $DIR").use { drain(it) }
        // `dd`, not `tee`: tee echoes the bytes to stdout, read only after the write, so a
        // large PNG (the keyboard up) fills the pipes and both ends block. HangWatch caught it.
        val (stdout, stdin) = ui.executeShellCommandRw("dd of=$DIR/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(png) }
        drain(stdout)
    }

    private fun drain(fd: android.os.ParcelFileDescriptor) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }
}
