package com.app.vault

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Intruder capture's settings, on the real screen — `intruder-capture-sheet`, moved home by
 * `intruder-captures-at-home`.
 *
 * The sheet is a claim about what is on screen and what is behind it, which only a device
 * answers. `CaptureDeviceTest` covers what enrolment *does*; this covers what it looks like,
 * how it is reached — from the Intruder capture screen — and what it does not say.
 *
 * **This reaches the install's own vault files, so it is gated.** It creates a vault
 * at `filesDir/vault.slots` and deletes it afterwards, which on a real device is the
 * device's vault. `DestructiveTestScanTest`'s allowlist is debt that may only shrink,
 * so a new suite does not join it — it carries the gate instead:
 *
 *   -Pandroid.testInstrumentationRunnerArguments.destructive=true
 *
 * It also skips rather than overwriting a vault that is already there, which is a
 * second guard and not a substitute for the first.
 */
@RunWith(AndroidJUnit4::class)
class CaptureSheetDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")

    private var created = false

    private companion object {
        const val PASS_TEXT = "correct horse battery staple"
    }

    @Before
    fun setUp() {
        // The gate. Without it a routine `connectedAndroidTest` run would delete the
        // vault on whatever device it is pointed at.
        assumeTrue(
            "gated: pass -Pandroid.testInstrumentationRunnerArguments.destructive=true to run it.",
            InstrumentationRegistry.getArguments().getString("destructive") == "true",
        )
        // This suite needs the app to open on the unlock screen — the launcher
        // identity is install-wide and other suites move it.
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        Capture.resetForTest(ctx)
        val store = BlobStore(blobsDir, incoming)
        val lifecycle = VaultLifecycle(slots, store)
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        lifecycle.create(PASS_TEXT.toByteArray(Charsets.UTF_8))
        created = true
        lockKeyringAndVault()
    }

    @After
    fun tearDown() {
        if (InstrumentationRegistry.getArguments().getString("destructive") != "true") return
        Capture.resetForTest(ctx)
        lockKeyringAndVault()
        ThumbnailCache.clear()
        WorkManager.getInstance(ctx).cancelAllWork()
        if (!created) return
        MetadataDb.awaitQuiescent()
        slots.delete()
        blobsDir.deleteRecursively()
        incoming.deleteRecursively()
    }

    // --- harness --------------------------------------------------------------

    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 45_000, condition = condition)
        } catch (e: Throwable) {
            runCatching {
                compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { i ->
                    compose.onAllNodes(isRoot())[i].printToLog("CaptureSheet")
                }
            }
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun showing(description: String) =
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun text(label: String) =
        compose.onAllNodes(hasText(label, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** A passphrase opens the keyring and lands on the vault list. */
    private fun unlock() {
        await("the lock screen") {
            compose.onAllNodes(hasText("Welcome back")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        val field = compose.onNode(hasText("Passphrase"))
        field.performClick()
        compose.waitForIdle()
        field.performTextInput(PASS_TEXT)
        await("the passphrase to register") {
            compose.onAllNodes(hasText("Unlock") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Unlock").performClick()
        await("the vault list") { showing("Menu") }
        compose.waitForIdle()
    }

    /** The Intruder capture screen, then its settings control. */
    private fun openSheet() {
        compose.openIntruderCaptureFromHome()
        compose.onNodeWithContentDescription("Capture settings").performClick()
        await("the sheet") { text("A still from the front camera") }
    }

    /**
     * The camera granted before the app starts — what turning photo on asks for. The app reads its
     * permissions as it resumes, so a grant made under a running screen would not be seen until the
     * next return.
     */
    private fun grantCamera() =
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(ctx.packageName, android.Manifest.permission.CAMERA)

    /** Photo enrolled on this keyring — what turning its switch on leaves. */
    private fun enrolPhotoHere() {
        val key = VaultKeyringSession.captureStoreKey()!!
        val kp = VaultKeys.captureKeypair(key)
        Capture.setDestination(ctx, kp.publicKey)
        Capture.setEnabled(ctx, Capture.Mode.PHOTO, true)
        kp.zero()
        key.fill(0)
    }

    // --- 1. it is a sheet, over the Intruder capture screen ---------------------

    @Test
    fun theSettingsOpenAsASheetOverTheIntruderCaptureScreenAndDismissBackToIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            openSheet()

            // The screen is still composed behind it — a sheet, not a screen that replaced it.
            assertTrue(
                "the Intruder capture screen must still be behind the sheet",
                compose.onAllNodes(isRoot()).fetchSemanticsNodes().size > 1,
            )
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("input keyevent KEYCODE_BACK").close()
            await("the sheet to go") { !text("A still from the front camera") }
            assertTrue("dismissing it leaves the user on the screen", showing("Capture settings"))
        }
    }

    // --- 2. the modes are switches that show their state -----------------------

    @Test
    fun eachModeIsASwitchThatShowsItsState() {
        grantCamera()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            enrolPhotoHere()
            openSheet()

            // Photo was enrolled on this keyring, so its switch is on and its row says so in
            // words as well — never the control alone.
            assertTrue("the state must be in words", text("On"))
            assertTrue(
                "the mode must carry a switch, not a button naming the opposite act",
                compose.onAllNodes(isOn()).fetchSemanticsNodes().isNotEmpty(),
            )
            assertFalse("no button naming the opposite action", text("Turn on"))
            assertFalse(text("Turn off"))
        }
    }

    // --- 3. a keyring that is not the destination -------------------------------

    @Test
    fun theSwitchesReadOffOnAKeyringThatIsNotTheDestination() {
        // Photo on, sealed to some other keyring's store — as a decoy sees the real keyring's.
        val elsewhere = VaultKeys.captureKeypair(ByteArray(32) { 7 })
        Capture.setDestination(ctx, elsewhere.publicKey)
        Capture.setEnabled(ctx, Capture.Mode.PHOTO, true)
        elsewhere.zero()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            openSheet()

            assertTrue(
                "every switch must read off on a keyring that is not the destination",
                compose.onAllNodes(isOn()).fetchSemanticsNodes().isEmpty(),
            )
            assertFalse("nothing may mention another destination", text("destination"))
        }
    }

    // --- 4. no log row, no destination sentence, no prose ------------------------

    @Test
    fun theSheetCarriesNoLogRowNoDestinationSentenceAndNoExplanation() {
        grantCamera()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            enrolPhotoHere()
            openSheet()

            assertFalse("the log is the screen behind the sheet, not a row on it", text("Intruder log"))
            assertFalse("no sentence about a destination", text("destination"))
            assertFalse(text("previous vault"))
            assertFalse("no sentence about where captures go", text("into this vault"))
            // The paragraphs that used to sit above the toggles. Onboarding's own capture
            // step teaches the feature.
            assertFalse(text("can record who is holding it"))
            assertFalse(text("If you turn capture on from a different vault"))
            assertFalse(text("Nothing shows on the lock screen"))
        }
    }

    // --- 5. `per-vault-intruder-capture` D8: a mode that lost its permission ----------------

    /**
     * Photo on with the camera permission not held — every test starts on a cleared install, and
     * nothing here grants it. The row says it is not recording and why, its switch reads off, and
     * turning it on asks for the permission.
     */
    @Test
    fun aModeWithoutItsPermissionReadsNotRecordingAndTurningItOnAsks() {
        assumeTrue(
            "the camera permission is held; this needs it missing",
            ctx.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
        val instr = InstrumentationRegistry.getInstrumentation()
        // Blocking with no result: the request is seen, and the system dialog is never raised.
        val asked = instr.addMonitor(
            android.content.IntentFilter("android.content.pm.action.REQUEST_PERMISSIONS"), null, true,
        )
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                unlock()
                enrolPhotoHere()
                // The photo row's own line is replaced, so the sheet is waited for by its title's line.
                compose.openIntruderCaptureFromHome()
                compose.onNodeWithContentDescription("Capture settings").performClick()
                await("the sheet") { text("Recorded after a failed unlock.") }
                assertTrue("the row says what is wrong", text("Not recording · camera permission off"))
                assertTrue(
                    "a mode that records nothing does not read on",
                    compose.onAllNodes(isOn()).fetchSemanticsNodes().isEmpty(),
                )

                compose.onNodeWithText("Not recording · camera permission off").performClick()
                await("the permission request") { asked.hits > 0 }
            }
        } finally {
            instr.removeMonitor(asked)
        }
    }
}
