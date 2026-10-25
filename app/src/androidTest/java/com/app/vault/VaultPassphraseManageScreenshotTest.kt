package com.app.vault

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `vault-passphrase-manage`, task 7.3 — real captures of what the change draws, in the dark and the
 * light scheme: Edit vault unprotected, Add a passphrase empty and with an error, the kit after Add,
 * Edit vault protected with its status line, Change passphrase, and Remove passphrase.
 *
 * The real activity with `FLAG_SECURE` cleared, captured from `uiAutomation` and written with `dd`
 * to [DIR]. Gated like `VaultCaptureDeviceTest`. Test-only state: a keyring at
 * `filesDir/vault.slots` whose "My vault" is given the passphrase [OWN] and has it removed between
 * the two schemes; all of it deleted after.
 */
@RunWith(AndroidJUnit4::class)
class VaultPassphraseManageScreenshotTest {

    @Suppress("DEPRECATION")
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")
    private val store by lazy { BlobStore(blobsDir, incoming) }
    private val lifecycle by lazy { VaultLifecycle(slots, store) }
    private var created = false

    private companion object {
        const val DIR = "/data/local/tmp/vault-passphrase-manage"
        const val PASS = "correct horse battery staple"
        const val OWN = "lantern quarry obsidian meadow"
        const val NAME = VaultKeyringSession.MIGRATED_NAME
    }

    @Before
    fun setUp() {
        assumeTrue(
            "gated: pass -Pandroid.testInstrumentationRunnerArguments.destructive=true to run it.",
            InstrumentationRegistry.getArguments().getString("destructive") == "true",
        )
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        check(lifecycle.create(PASS.toByteArray(Charsets.UTF_8)).opened)
        created = true
        lockKeyringAndVault()
    }

    @After
    fun tearDown() {
        if (InstrumentationRegistry.getArguments().getString("destructive") != "true") return
        lockKeyringAndVault()
        ThumbnailCache.clear()
        WorkManager.getInstance(ctx).cancelAllWork()
        GridPreference.setAppearance(ctx, ThemeChoice.Default)
        if (!created) return
        MetadataDb.awaitQuiescent()
        slots.delete()
        blobsDir.deleteRecursively()
        incoming.deleteRecursively()
    }

    @Test
    fun theSectionAndTheThreeScreens() {
        listOf(ThemeChoice.Dark, ThemeChoice.Light).forEach { theme ->
            GridPreference.setAppearance(ctx, theme)
            val p = theme.name.lowercase()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                unlock()

                compose.openEditVault(NAME)
                compose.onNode(hasText("Add a passphrase") and hasClickAction()).performScrollTo()
                shot("$p-01-edit-vault-unprotected")

                compose.onNode(hasText("Add a passphrase") and hasClickAction()).performClick()
                await("Add a passphrase") { text("Vault list passphrase") }
                shot("$p-02-add-empty")

                type("Vault list passphrase", PASS)
                type("New passphrase", "too short")
                type("New passphrase again", "too short")
                compose.onNode(hasText("Add passphrase") and hasClickAction()).performScrollTo().performClick()
                await("the refusal") { text("too easy to guess") }
                hideKeyboard(scenario)
                compose.onNodeWithText("That passphrase is too easy to guess. Add another word or two.").performScrollTo()
                shot("$p-03-add-error")

                retype("New passphrase", OWN)
                retype("New passphrase again", OWN)
                hideKeyboard(scenario)
                compose.onNode(hasText("Add passphrase") and hasClickAction()).performScrollTo().performClick()
                await("the kit") { text("I understand") }
                shot("$p-04-kit-after-add")

                compose.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextInput("I understand")
                hideKeyboard(scenario)
                compose.onNode(hasText("Continue") and hasClickAction()).performScrollTo().performClick()
                await("the status line") { text("Passphrase added.") }
                compose.onNode(hasText("Remove passphrase") and hasClickAction()).performScrollTo()
                shot("$p-05-edit-vault-protected-status")

                compose.onNode(hasText("Change passphrase") and hasClickAction()).performClick()
                await("Change passphrase") { text("Current passphrase") }
                shot("$p-06-change")
                compose.onNodeWithContentDescription("Back").performClick()

                compose.onNode(hasText("Remove passphrase") and hasClickAction()).performScrollTo().performClick()
                await("Remove passphrase") { text("Its intruder captures move home") }
                shot("$p-07-remove")
            }
            // The next scheme starts from the onboarding vault again.
            check(lifecycle.unlock(PASS.toByteArray(Charsets.UTF_8)).opened)
            check(lifecycle.removeVaultPassphrase(0, OWN.toByteArray(Charsets.UTF_8)) == VaultLifecycle.PassphraseAct.DONE)
            lockKeyringAndVault()
        }
    }

    // --- harness --------------------------------------------------------------------------

    private fun await(what: String, condition: () -> Boolean) = try {
        compose.waitUntil(timeoutMillis = 45_000, condition = condition)
    } catch (e: Throwable) {
        throw AssertionError("timed out waiting for $what", e)
    }

    private fun described(text: String) =
        compose.onAllNodes(hasContentDescription(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun text(label: String) =
        compose.onAllNodes(hasText(label, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun type(label: String, value: String) {
        val field = compose.onNode(hasText(label) and hasSetTextAction())
        field.performScrollTo().performClick()
        field.performTextInput(value)
    }

    private fun retype(label: String, value: String) {
        val field = compose.onNode(hasText(label) and hasSetTextAction())
        field.performScrollTo()
        field.performTextReplacement(value)
    }

    private fun hideKeyboard(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity {
            WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
        Thread.sleep(400)
    }

    private fun unlock() {
        await("the lock screen") { text("Welcome back") }
        compose.waitForIdle()
        val field = compose.onNode(hasText("Passphrase"))
        field.performClick()
        compose.waitForIdle()
        field.performTextInput(PASS)
        await("the passphrase to register") {
            compose.onAllNodes(hasText("Unlock") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Unlock").performClick()
        await("the vault list") { described("Menu") }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(700)
        val png = java.io.ByteArrayOutputStream().also {
            instr.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val ui = instr.uiAutomation
        ui.executeShellCommand("mkdir -p $DIR").use { drain(it) }
        // `dd`, not `tee` — `tee` echoes the PNG back and deadlocks on a large one.
        val (stdout, stdin) = ui.executeShellCommandRw("dd of=$DIR/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(png) }
        drain(stdout)
    }

    private fun drain(fd: android.os.ParcelFileDescriptor) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }
}
