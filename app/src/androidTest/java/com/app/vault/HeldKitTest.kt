package com.app.vault

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A kit held across a lock opens only under the keyring that sealed it —
 * `screen-off-lock-all-screens`, D5.
 *
 * On the device because the seal is libsodium's, which the JVM suites do not load. Two
 * keyrings in two scratch stores: the one that sealed opens it again after a lock, and
 * the other one — a decoy's, in the case that matters — gets nothing.
 */
@RunWith(AndroidJUnit4::class)
class HeldKitTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val roots = mutableListOf<File>()
    private var installVault = false

    private fun lifecycle(): VaultLifecycle {
        val root = File(ctx.filesDir, "heldkit-${System.nanoTime()}").apply { mkdirs() }
        roots += root
        return VaultLifecycle(
            File(root, "vault.slots"),
            BlobStore(File(root, "blobs"), File(root, "incoming")),
        )
    }

    @After
    fun tearDown() {
        lockKeyringAndVault()
        roots.forEach { it.deleteRecursively() }
        if (installVault) {
            Onboarding.resetForTest(ctx)
            File(ctx.filesDir, "vault.slots").delete()
            listOf("blobs", "incoming").forEach { name -> File(ctx.filesDir, name).listFiles()?.forEach { it.deleteRecursively() } }
        }
    }

    private fun showing(text: String) =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(what: String, timeoutMs: Long = 120_000, condition: () -> Boolean) {
        runCatching { compose.waitUntil(timeoutMs, condition) }
            .onFailure { throw AssertionError("timed out waiting for $what", it) }
    }

    /**
     * `recovery-practice` D5, 4.6. First run paused **on the kit check** comes back on the words —
     * the held-kit path — with an empty acknowledgement, and the next check asks again. Uses the
     * install's own paths, so it is gated and skipped where a vault exists.
     */
    @Test
    fun aLockOnTheKitCheckResumesOnTheWordsWithAnEmptyAcknowledgement() {
        assumeTrue(
            "gated: pass -Pandroid.testInstrumentationRunnerArguments.destructive=true to run it.",
            InstrumentationRegistry.getArguments().getString("destructive") == "true",
        )
        assumeTrue("a vault already exists here; not overwriting it", !File(ctx.filesDir, "vault.slots").exists())
        installVault = true
        Onboarding.resetForTest(ctx)
        val pass = "held-kit-resume-passphrase"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor("the language step") { showing("Continue") }
            compose.onNode(hasText("Continue")).performClick()
            waitFor("the welcome") { showing("Get started") }
            compose.onNode(hasText("Get started")).performClick()
            waitFor("Name and look") { showing("Name your vault") }
            compose.onNode(hasText("Name")).performTextInput("Held")
            compose.onNode(hasText("Continue")).performClick()
            waitFor("the passphrase step") { showing("Enter it again") }
            compose.onNode(hasText("Passphrase")).performTextInput(pass)
            compose.onNode(hasText("Enter it again")).performTextInput(pass)
            compose.onNode(hasText("Create vault")).performClick()
            waitFor("the kit") { showing("1. ") }
            val words = KitCheckWalk.wordsOnScreen(compose)
            compose.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextInput("I understand")
            compose.onNode(hasText("Continue") and isEnabled()).performClick()
            waitFor("the check") { KitCheckWalk.showing(compose) }

            scenario.moveToState(Lifecycle.State.CREATED)
            val deadline = System.currentTimeMillis() + 10_000
            while (VaultKeyringSession.isOpen && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertFalse("the keyring did not lock while away", VaultKeyringSession.isOpen)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()

            waitFor("the lock screen") { showing("Welcome back") }
            compose.onNode(hasSetTextAction()).performTextInput(pass)
            compose.onNode(hasText("Unlock")).performClick()
            waitFor("the words again") { showing("1. ") }
            assertFalse("not the check", KitCheckWalk.showing(compose))
            assertEquals("the same words", words, KitCheckWalk.wordsOnScreen(compose))
            assertTrue(
                "the acknowledgement starts empty, so Continue waits for it",
                compose.onAllNodes(hasText("Continue") and isEnabled()).fetchSemanticsNodes().isEmpty(),
            )
            compose.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextInput("I understand")
            compose.onNode(hasText("Continue") and isEnabled()).performClick()
            KitCheckWalk.pass(compose, words)
            waitFor("the next step") { !showing("1. ") && !KitCheckWalk.showing(compose) }
        }
    }

    @Test
    fun theSealingKeyringOpensItAndAnotherDoesNot() {
        val kit = "abandon ability able about above absent\nMine".toByteArray(Charsets.UTF_8)

        val a = lifecycle()
        a.create("first-keyring-passphrase".toByteArray(Charsets.UTF_8))
        val held = VaultKeyringSession.sealHeld(kit)
        assertNotNull("an open keyring seals", held)
        lockKeyringAndVault()

        assertNull("no keyring open, nothing opens", VaultKeyringSession.openHeld(held!!))

        assertTrue(a.unlock("first-keyring-passphrase".toByteArray(Charsets.UTF_8)).opened)
        assertArrayEquals(kit, VaultKeyringSession.openHeld(held))
        lockKeyringAndVault()

        val b = lifecycle()
        b.create("second-keyring-passphrase".toByteArray(Charsets.UTF_8))
        assertNull("another keyring cannot read it", VaultKeyringSession.openHeld(held))
    }
}
