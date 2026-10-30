package com.app.vault

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Passphrase practice on the real activity and the emulator's sensor — `recovery-practice`
 * D1–D4. Run with `-e finger relay` and the host relaying `VaultFinger` lines (see
 * [LiveCover]); finger 1 enrolled. Takes no screenshot.
 *
 * Each case makes the vault in the app's storage, links the wrap with the enrolled finger,
 * makes a check due, and removes all of it after ([LiveCover.Keyring.close]).
 */
@RunWith(AndroidJUnit4::class)
class PassphrasePracticeDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val practice get() = PassphrasePractice.of(ctx)
    private var keyring: LiveCover.Keyring? = null
    private val slots get() = File(ctx.filesDir, "vault.slots")

    private companion object {
        const val PANIC = "a-panic-phrase-for-the-practice-test"
        const val NEW = "a-brand-new-list-passphrase-chosen"
        const val TITLE = "Practice your passphrase"
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    @Before fun setUp() {
        LiveCover.assumeRelay()
        DeviceLock.awake()
        assumeTrue(
            "a wrap is already on the device; this run would replace it",
            !File(ctx.filesDir, BiometricWrap.FILE_NAME).exists(),
        )
        // The occupancy guard: never over a vault already on the device.
        val lifecycle = VaultLifecycle(slots, BlobStore(File(ctx.filesDir, "blobs"), File(ctx.filesDir, "incoming")))
        assumeTrue("a vault is already on the device; this run would replace it", !lifecycle.vaultExists())
        ExtraSecurity.set(ctx, true)
        TamperLog.clearPendingDeviceEventsForTest()
        keyring = LiveCover.openKeyring()
        LiveCover.link(BiometricWrap(ctx))
        // Due: the schedule was last set two days ago.
        practice.start(System.currentTimeMillis() - 2 * PassphrasePractice.DAY_MS)
        VaultLifecycle.lockNow()
    }

    @After fun tearDown() {
        keyring?.close()
        practice.clear()
        ExtraSecurity.set(ctx, true)
        Capture.setEnabled(ctx, Capture.Mode.PHOTO, false)
    }

    private fun showing(text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun listShown(): Boolean =
        compose.onAllNodesWithContentDescription("Menu").fetchSemanticsNodes().isNotEmpty() && !showing(TITLE)

    private fun await(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            compose.waitForIdle()
            if (condition()) return
            check(SystemClock.elapsedRealtime() < until) { "timed out waiting: $what" }
            SystemClock.sleep(100)
        }
    }

    /** The prompt the activity raised, answered with the enrolled finger until [then] holds. */
    private fun finger(what: String, then: () -> Boolean) {
        repeat(6) {
            SystemClock.sleep(1_200)
            LiveCover.touch(1)
            runCatching { await(what, 3_000, then) }.onSuccess { return }
        }
        throw AssertionError("the finger never landed: $what")
    }

    private fun field() = compose.onAllNodes(hasSetTextAction())[0]

    private fun entry(text: String) {
        field().performTextReplacement(text)
        // Check waits for text in the field; click it only once the entry has landed.
        await("Check to take the entry") {
            compose.onAllNodes(hasText("Check") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Check") and isEnabled()).performClick()
    }

    private fun backKey() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent KEYCODE_BACK").close()
        SystemClock.sleep(800)
    }

    @Test fun aDueCheckFollowsAFingerprintUnlockAndNeverAPassphraseUnlock() {
        ActivityScenario.launch(MainActivity::class.java).use {
            finger("the practice screen") { showing(TITLE) }
            assertFalse("the list was drawn under it", compose.onAllNodesWithContentDescription("Menu").fetchSemanticsNodes().isNotEmpty())
        }
        VaultLifecycle.lockNow()
        practice.start(System.currentTimeMillis() - 2 * PassphrasePractice.DAY_MS)
        ActivityScenario.launch(MainActivity::class.java).use {
            SystemClock.sleep(1_500)
            backKey()
            await("the unlock screen") { showing("Enter your passphrase") }
            field().performTextInput(LiveCover.PASSPHRASE)
            compose.onNodeWithText("Unlock").performClick()
            await("the list") { listShown() }
            assertFalse(showing(TITLE))
        }
        val now = System.currentTimeMillis()
        assertFalse("a passphrase unlock moves the check ahead", practice.isDue(now))
        assertTrue("by the current interval, one day", practice.isDue(now + PassphrasePractice.DAY_MS + 60_000))
    }

    @Test fun aWrongEntryLeavesNoTraceAndThePanicPhraseIsInert() {
        assertTrue(keyring!!.lifecycle.unlock(pass(LiveCover.PASSPHRASE)).opened)
        assertEquals(VaultLifecycle.PanicResult.DONE, keyring!!.lifecycle.setPanic(pass(LiveCover.PASSPHRASE), pass(PANIC)))
        val key = VaultKeyringSession.captureStoreKey()!!
        val kp = VaultKeys.captureKeypair(key)
        Capture.setDestination(ctx, kp.publicKey)
        Capture.setEnabled(ctx, Capture.Mode.PHOTO, true)
        kp.zero(); key.fill(0)
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(ctx.packageName, android.Manifest.permission.CAMERA)
        VaultLifecycle.lockNow()
        val failuresBefore = TamperLog.pendingFailures()
        val photosBefore = Capture.pending(ctx, Capture.Mode.PHOTO).size

        ActivityScenario.launch(MainActivity::class.java).use {
            finger("the practice screen") { showing(TITLE) }
            entry("not-the-list-passphrase-at-all")
            await("the wrong state") { showing("That's not your passphrase.") }
            assertTrue("the panel", showing("Set a new passphrase now"))
            entry(PANIC)
            await("the panic phrase marked wrong") { showing("That's not your passphrase.") && !showing("Checking") }
            SystemClock.sleep(2_000)
            assertEquals("no failed unlock was recorded", failuresBefore, TamperLog.pendingFailures())
            assertEquals("no capture was taken", photosBefore, Capture.pending(ctx, Capture.Mode.PHOTO).size)
            assertTrue("the panic phrase wiped nothing", VaultKeyringSession.isOpen)
            assertTrue("a slot file is still here", slots.exists())
            assertTrue("Check is still there for another try", showing("Check"))
        }
        VaultLifecycle.lockNow()
        assertTrue("the list still opens", keyring!!.lifecycle.unlock(pass(LiveCover.PASSPHRASE)).opened)
    }

    @Test fun aRightEntryOpensTheListAndNamesTheNextInterval() {
        ActivityScenario.launch(MainActivity::class.java).use {
            finger("the practice screen") { showing(TITLE) }
            entry(LiveCover.PASSPHRASE)
            await("the snackbar") { showing("That's right. Next practice in 3 days.") }
            assertTrue(listShown())
        }
        assertEquals(3, practice.nextInterval())
    }

    @Test fun skipOpensTheListAndAsksAgainInADay() {
        ActivityScenario.launch(MainActivity::class.java).use {
            finger("the practice screen") { showing(TITLE) }
            compose.onNodeWithText("Skip").performClick()
            await("the list") { listShown() }
        }
        val now = System.currentTimeMillis()
        assertFalse(practice.isDue(now))
        assertTrue(practice.isDue(now + PassphrasePractice.DAY_MS + 60_000))
    }

    @Test fun aNewPassphraseIsSetAfterAFreshFingerprintAndTheOldOneStopsWorking() {
        ActivityScenario.launch(MainActivity::class.java).use {
            finger("the practice screen") { showing(TITLE) }
            entry("forgotten-it-completely-wrong")
            await("the panel") { showing("Set a new passphrase now") }
            compose.onNodeWithText("Set a new passphrase now").performScrollTo().performClick()
            await("the new passphrase screen") { showing("Choose the passphrase you'll use") }
            compose.onAllNodes(hasSetTextAction())[0].performTextInput(NEW)
            compose.onAllNodes(hasSetTextAction())[1].performTextInput(NEW)
            compose.onNodeWithText("Set passphrase").performClick()
            finger("the change") { showing("Passphrase changed. Your recovery kit still works.") }
            assertTrue(listShown())
        }
        VaultLifecycle.lockNow()
        assertFalse("the old passphrase is refused", keyring!!.lifecycle.unlock(pass(LiveCover.PASSPHRASE)).opened)
        VaultLifecycle.lockNow()
        assertTrue("the new one opens the list", keyring!!.lifecycle.unlock(pass(NEW)).opened)
        VaultLifecycle.lockNow()
        assertFalse("the schedule restarted, not due", practice.isDue(System.currentTimeMillis()))
        ActivityScenario.launch(MainActivity::class.java).use {
            finger("the fingerprint still opens the list") { listShown() }
        }
    }

    @Test fun aCancelledConfirmationWritesNothingAndKeepsTheFields() {
        ActivityScenario.launch(MainActivity::class.java).use {
            finger("the practice screen") { showing(TITLE) }
            entry("forgotten-it-completely-wrong")
            await("the panel") { showing("Set a new passphrase now") }
            compose.onNodeWithText("Set a new passphrase now").performScrollTo().performClick()
            await("the new passphrase screen") { showing("Choose the passphrase you'll use") }
            compose.onAllNodes(hasSetTextAction())[0].performTextInput(NEW)
            compose.onAllNodes(hasSetTextAction())[1].performTextInput(NEW)
            val before = slots.readBytes()
            compose.onNodeWithText("Set passphrase").performClick()
            SystemClock.sleep(1_500)
            backKey()
            await("the line under the button") { showing("to save your new passphrase.") }
            assertArrayEquals("no slot was written", before, slots.readBytes())
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().forEach { node ->
                val kept = node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
                assertEquals("the fields keep their text", NEW.length, kept.length)
            }
            assertTrue("still on the screen", showing("Choose the passphrase you'll use"))
        }
        VaultLifecycle.lockNow()
        assertTrue("the old passphrase still opens the list", keyring!!.lifecycle.unlock(pass(LiveCover.PASSPHRASE)).opened)
    }
}
