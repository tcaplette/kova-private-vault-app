package com.app.vault

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Every screen locks when the app is left — `screen-off-lock-all-screens`.
 *
 * Drives the real activity: a screen drawn **above** the lock branch, a sheet drawn
 * outside the `when`, a screen reached from the lock screen with nothing open, and a
 * recovery kit. Each is left by a trip to `CREATED` — the state a screen-off leaves the
 * activity in — and each must come back as the lock screen. The kit must then come back
 * after the same passphrase opens its keyring.
 *
 * A vault in the app's real storage, because `MainActivity` reads `hasVault` from there
 * and the reset runs only when a vault exists. The orchestrator clears package data
 * between tests; this also removes what it made.
 *
 * Screenshots, taken the only way that shows what is on the display (`FLAG_SECURE`
 * cleared, `uiAutomation`), land in [DIR] for a person to look at.
 */
@RunWith(AndroidJUnit4::class)
class ScreenOffLockDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val slots = File(ctx.filesDir, "vault.slots")
    private val blobDir = File(ctx.filesDir, "blobs")
    private val incoming = File(ctx.filesDir, "incoming")

    private val lifecycle = VaultLifecycle(slots, BlobStore(blobDir, incoming))

    /**
     * Set once the guard passed. JUnit runs `@After` even when `@Before`'s assumption skips the
     * test, so without this the teardown deleted a real vault it had just declined to touch.
     */
    private var ours = false

    @Before
    fun setUp() {
        assumeTrue("a real vault exists on this device; not touching it", !lifecycle.vaultExists())
        ours = true
        lockKeyringAndVault()
    }

    /** A vault on disk and nothing open — where every case but the first-run kit starts. */
    private fun aVaultOnDisk() {
        lifecycle.create(pass(KEYRING))
        lockKeyringAndVault()
    }

    @After
    fun tearDown() {
        if (!ours) return
        AutoLock.awaitingResult(false)
        lockKeyringAndVault()
        slots.delete()
        blobDir.deleteRecursively()
        incoming.deleteRecursively()
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun showing(text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun lockScreenIsUp(): Boolean = showing(LOCK_SCREEN)

    private fun waitFor(what: String, condition: () -> Boolean) =
        compose.waitUntil(timeoutMillis = 60_000) { condition() }.also { compose.waitForIdle() }

    /** Types the keyring's passphrase at the lock screen and waits for the list. */
    private fun unlock() {
        compose.onNode(hasSetTextAction()).performTextInput(KEYRING)
        compose.onNode(hasText("Unlock")).performClick()
        waitFor("the list") { showing(VaultKeyringSession.MIGRATED_NAME) || showing(KIT_MARK) }
    }

    /**
     * Stopped, the lock lands, then started again — a screen-off and a screen-on. Waits
     * for the lock rather than racing it: auto-lock asks WorkManager before it locks.
     */
    private fun ActivityScenario<MainActivity>.screenOffAndOn() {
        moveToState(Lifecycle.State.CREATED)
        val deadline = System.currentTimeMillis() + 10_000
        while (VaultKeyringSession.isOpen && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertFalse("the keyring did not lock while away", VaultKeyringSession.isOpen)
        moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    private fun ActivityScenario<MainActivity>.screenshot(name: String) {
        onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        compose.waitForIdle()
        Thread.sleep(700)
        val png = ByteArrayOutputStream().also {
            instr.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val ui = instr.uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("mkdir -p $DIR")).use { it.readBytes() }
        // `dd`, not `tee`: tee echoes the bytes to stdout, read only after the write, so a
        // large PNG (the keyboard up) fills the pipes and both ends block. HangWatch caught it.
        val (stdout, stdin) = ui.executeShellCommandRw("dd of=$DIR/$name.png")
        ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(png) }
        ParcelFileDescriptor.AutoCloseInputStream(stdout).use { it.readBytes() }
        onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    /** The create-vault screen is drawn above the lock branch — the defect's own shape. */
    @Test
    fun aScreenAboveTheLockBranchLocks() {
        aVaultOnDisk()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("the lock screen") { lockScreenIsUp() }
            unlock()
            compose.onNode(hasContentDescription("New vault")).performClick()
            waitFor("the create screen") { showing(CREATE_MARK) }

            scenario.screenOffAndOn()
            scenario.screenshot("above-the-gate")
            assertTrue("the lock screen is not what came back", lockScreenIsUp())
            assertFalse("the create screen survived the lock", showing(CREATE_MARK))

            unlock()
            assertFalse("the create screen came back after unlock", showing(CREATE_MARK))
        }
    }

    /**
     * A modal is drawn outside the `when`, so no branch order hides it. The vault's destroy
     * dialog, since `vault-list-refresh` retired the list's rename sheet for a screen.
     */
    @Test
    fun aSheetDoesNotFloatOverTheLockScreen() {
        aVaultOnDisk()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("the lock screen") { lockScreenIsUp() }
            unlock()
            compose.onNode(hasContentDescription("Options for ${VaultKeyringSession.MIGRATED_NAME}"))
                .performClick()
            compose.onNode(hasText("Remove")).performClick()
            waitFor("the destroy dialog") { showing("Destroy") }

            scenario.screenOffAndOn()
            scenario.screenshot("sheet")
            assertTrue("the lock screen is not what came back", lockScreenIsUp())
            assertFalse("the destroy dialog survived the lock", showing("Destroy"))
        }
    }

    /** Recover is reached with nothing open, and still goes back to the lock screen. */
    @Test
    fun recoverFromTheLockScreenGoesBackToIt() {
        aVaultOnDisk()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("the lock screen") { lockScreenIsUp() }
            compose.onNode(hasText("Forgot passphrase?")).performClick()
            waitFor("the recover screen") { showing(RECOVER_MARK) }

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            scenario.screenshot("recover")
            assertTrue("the lock screen is not what came back", lockScreenIsUp())
            assertFalse("the recover screen survived leaving", showing(RECOVER_MARK))
        }
    }

    /**
     * A kit on screen at the lock is covered, and comes back after the same passphrase.
     *
     * The first-run kit — the keyring's own. It is the kit the screen timeout is most likely
     * to land on, because it is where a person stops to write 24 words down.
     */
    @Test
    fun aKitComesBackAfterTheSamePassphrase() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // A fresh install opens on the language step, then the welcome.
            waitFor("the language step") { showing("Continue") }
            compose.onNode(hasText("Continue")).performClick()
            waitFor("the welcome") { showing("Get started") }
            compose.onNode(hasText("Get started")).performClick()
            // `first-vault-naming`. Name and look comes before the passphrase.
            waitFor("Name and look") { showing("Name your vault") }
            compose.onNode(hasText("Name")).performTextInput(VaultKeyringSession.MIGRATED_NAME)
            compose.onNode(hasText("Continue")).performClick()
            waitFor("the passphrase step") { showing("Enter it again") }
            compose.onNode(hasText("Passphrase")).performTextInput(KEYRING)
            compose.onNode(hasText("Enter it again")).performTextInput(KEYRING)
            compose.onNode(hasText("Create vault")).performClick()
            waitFor("the kit") { showing("1. ") }
            val firstWord = compose.onAllNodes(hasText("1. ", substring = true))
                .fetchSemanticsNodes().first().config[SemanticsProperties.Text].first().text

            scenario.screenOffAndOn()
            scenario.screenshot("kit-locked")
            assertTrue("the lock screen is not what came back", lockScreenIsUp())
            assertFalse("the kit is drawn over a locked vault", showing(firstWord))

            unlock()
            scenario.screenshot("kit-resumed")
            assertTrue("the kit did not come back after the same passphrase", showing(firstWord))
        }
    }

    /**
     * A vault with its own passphrase shows **its** kit, and that kit survives a lock too.
     *
     * The kit used to never appear on this path: the entry KEK it is derived from was the
     * array `createVault` zeroes, so the unseal ran under zeros and returned nothing.
     */
    @Test
    fun aVaultsOwnKitIsShownAndComesBack() {
        aVaultOnDisk()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("the lock screen") { lockScreenIsUp() }
            unlock()
            compose.onNode(hasContentDescription("New vault")).performClick()
            waitFor("the create screen") { showing(CREATE_MARK) }
            // `new-vault-setup-flow`. Name and look, How it opens, its passphrase, capture.
            compose.onNode(hasText("Name") and hasSetTextAction()).performTextInput("Kept")
            compose.onNode(hasText("Continue") and hasClickAction()).performClick()
            waitFor("How it opens") { showing("How should Kept open?") }
            compose.onNode(hasText("With its own passphrase") and hasClickAction()).performClick()
            compose.onNode(hasText("Continue") and hasClickAction()).performClick()
            waitFor("its passphrase") { showing("Choose a passphrase for Kept") }
            compose.onNode(hasText("New passphrase") and hasSetTextAction()).performTextInput(OWN)
            compose.onNode(hasText("New passphrase again") and hasSetTextAction()).performTextInput(OWN)
            compose.onNode(hasText("Continue") and hasClickAction()).performClick()
            waitFor("the capture step") { showing("See who tries to open Kept") }
            compose.onNode(hasText("Create vault") and hasClickAction()).performClick()
            waitFor("the vault's own kit") { showing("Recovery kit for Kept") }
            val firstWord = compose.onAllNodes(hasText("1. ", substring = true))
                .fetchSemanticsNodes().first().config[SemanticsProperties.Text].first().text

            scenario.screenOffAndOn()
            assertTrue("the lock screen is not what came back", lockScreenIsUp())
            assertFalse("the kit is drawn over a locked vault", showing(firstWord))

            unlock()
            scenario.screenshot("own-kit-resumed")
            assertTrue("the vault's own kit did not come back", showing(firstWord))
        }
    }

    companion object {
        const val DIR = "/data/local/tmp/screen-off-lock"
        const val KEYRING = "screen-off-lock-passphrase"
        const val OWN = "its-own-secret-phrase"
        const val LOCK_SCREEN = "Enter your passphrase to open this vault."
        /** New vault's first step, Name and look — `new-vault-setup-flow`. */
        const val CREATE_MARK = "Name your vault"
        const val RECOVER_MARK = "Type the 24 words from your recovery kit"
        const val KIT_MARK = "to confirm you have written them down"
    }
}
