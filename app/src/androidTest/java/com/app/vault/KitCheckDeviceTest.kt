package com.app.vault

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToLog
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The kit check on the real activity — `recovery-practice` D5.
 *
 * Every case makes a keyring at the install's own paths and removes it after, so it is gated
 * as every suite that names those files is:
 *
 *   -Pandroid.testInstrumentationRunnerArguments.destructive=true
 *
 * and skipped where a vault already exists. Emulator only. The decoy case hands the photo picker
 * thirty generated photos through the app's own share provider, written under `filesDir/shares/`
 * and deleted after.
 */
@RunWith(AndroidJUnit4::class)
class KitCheckDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")
    private val photos get() = File(ctx.filesDir, "shares/kit-check-decoy")
    private var created = false

    private companion object {
        const val TAG = "KitCheckDevice"
        const val GOOD = "correct horse battery staple"
        const val OWN = "lantern quarry obsidian meadow"
        const val DECOY = "harbour violet cinnamon ladder"
        const val NAME = "Receipts"
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    @After fun tearDown() {
        lockKeyringAndVault()
        MetadataDb.awaitQuiescent()
        ExtraSecurity.set(ctx, true)
        photos.deleteRecursively()
        if (!created) return
        Capture.resetForTest(ctx)
        CapturePermissions.resetForTest(ctx)
        slots.delete()
        blobsDir.deleteRecursively()
        incoming.deleteRecursively()
    }

    // --- harness ---------------------------------------------------------------------------------

    private fun await(what: String, timeoutMs: Long = 45_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = timeoutMs, condition = condition)
        } catch (e: Throwable) {
            runCatching { compose.onAllNodes(isRoot())[0].printToLog(TAG) }
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun text(label: String) =
        compose.onAllNodes(hasText(label, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun button(label: String) = compose.onNode(hasText(label) and hasClickAction() and isEnabled())

    /** A button whether it scrolls with its screen or is pinned below it, as the decoy steps' are. */
    private fun tap(label: String) {
        val node = button(label)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun realKeyring() {
        assumeTrue(
            "gated: pass -Pandroid.testInstrumentationRunnerArguments.destructive=true to run it.",
            InstrumentationRegistry.getArguments().getString("destructive") == "true",
        )
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        Capture.resetForTest(ctx)
        CapturePermissions.resetForTest(ctx)
        ExtraSecurity.set(ctx, true)
        val lifecycle = VaultLifecycle(slots, BlobStore(blobsDir, incoming))
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        assertTrue(lifecycle.create(pass(GOOD)).opened)
        created = true
        lockKeyringAndVault()
    }

    private fun unlock() {
        await("the lock screen") { text("Welcome back") }
        val field = compose.onNode(hasText("Passphrase") and hasSetTextAction())
        field.performClick()
        field.performTextInput(GOOD)
        await("the passphrase to register") { compose.onAllNodes(hasText("Unlock") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Unlock") and hasClickAction()).performClick()
        await("the vault list") { compose.onAllNodes(hasContentDescription("New vault")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun type(label: String, value: String) {
        val field = compose.onNode(hasText(label) and hasSetTextAction())
        field.performClick()
        field.performTextReplacement(value)
    }

    /** New vault with its own passphrase, no capture, up to its kit. */
    private fun walkToOwnKit() {
        compose.onNode(hasContentDescription("New vault") and hasClickAction()).performClick()
        await("Name and look") { text("Name your vault") }
        type("Name", NAME)
        button("Continue").performClick()
        await("How it opens") { text("How should $NAME open?") }
        compose.onNode(hasText("With its own passphrase") and hasClickAction()).performClick()
        button("Continue").performClick()
        await("its passphrase") { text("Choose a passphrase for $NAME") }
        type("New passphrase", OWN)
        type("New passphrase again", OWN)
        button("Continue").performClick()
        await("the capture step") { text("See who tries to open $NAME") }
        button("Create vault").performClick()
        await("the kit") { text("Recovery kit for $NAME") }
    }

    /** The acknowledgement, then the kit's Continue. */
    private fun acknowledgeAndContinue() {
        compose.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextInput("I understand")
        button("Continue").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun errorLines(): List<String> =
        compose.onAllNodes(hasText("on your kit.", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }

    private fun vaultEntry() = VaultKeyringSession.vaults().single { it.name == NAME }

    // --- a vault's own kit -----------------------------------------------------------------------

    @Test fun threeRightWordsContinueTheFlow() {
        realKeyring()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            walkToOwnKit()
            val words = KitCheckWalk.wordsOnScreen(compose)
            acknowledgeAndContinue()
            await("the check") { KitCheckWalk.showing(compose) }
            assertTrue("the lead", text("Type these words from your paper, not from memory."))
            val asked = KitCheckWalk.asked(compose)
            assertEquals(asked.sorted(), asked)
            assertEquals(3, asked.toSet().size)
            KitCheckWalk.enter(compose, words)
            await("the vault list") { compose.onAllNodes(hasContentDescription("New vault")).fetchSemanticsNodes().isNotEmpty() }
            assertFalse(KitCheckWalk.showing(compose))
            assertEquals("the kit at creation spent nothing", 0, vaultEntry().kitViewsUsed)
        }
    }

    @Test fun oneWrongWordMarksThatFieldAloneAndTheFlowStays() {
        realKeyring()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            walkToOwnKit()
            val words = KitCheckWalk.wordsOnScreen(compose)
            acknowledgeAndContinue()
            await("the check") { KitCheckWalk.showing(compose) }
            val asked = KitCheckWalk.asked(compose)
            val target = asked[1]
            val other = listOf("zoo", "abandon").first { it != words[target - 1] }
            KitCheckWalk.enter(compose, words, wrong = target to other)
            await("the mismatch line") { errorLines().isNotEmpty() }
            assertEquals(listOf("This isn't word $target on your kit."), errorLines())
            assertTrue("still on the check", KitCheckWalk.showing(compose))
            val kept = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
                .map { it.config.getOrNull(SemanticsProperties.EditableText)?.text }
            assertEquals("the right fields keep what was typed", words[asked[0] - 1], kept[0])
            assertEquals(words[asked[2] - 1], kept[2])
        }
    }

    @Test fun seeTheWordsAgainKeepsTheAcknowledgementSpendsNothingAndAsksAgain() {
        realKeyring()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            walkToOwnKit()
            val words = KitCheckWalk.wordsOnScreen(compose)
            acknowledgeAndContinue()
            await("the check") { KitCheckWalk.showing(compose) }
            val first = KitCheckWalk.asked(compose)
            var next = first
            repeat(4) {
                button("See the words again").performScrollTo().performClick()
                await("the words") { text("Recovery kit for $NAME") }
                assertEquals("the same words", words, KitCheckWalk.wordsOnScreen(compose))
                assertTrue("Continue is there without retyping", compose.onAllNodes(hasText("Continue") and isEnabled()).fetchSemanticsNodes().isNotEmpty())
                assertEquals("no view was spent", 0, vaultEntry().kitViewsUsed)
                button("Continue").performScrollTo().performClick()
                await("the check again") { KitCheckWalk.showing(compose) }
                next = KitCheckWalk.asked(compose)
                if (next != first) return@repeat
            }
            assertNotEquals("the next check asks for new positions", first, next)
            // Back from the check is the words too.
            instr.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
            await("the words after Back") { text("Recovery kit for $NAME") && !KitCheckWalk.showing(compose) }
            button("Continue").performScrollTo().performClick()
            KitCheckWalk.pass(compose, words)
            await("the vault list") { compose.onAllNodes(hasContentDescription("New vault")).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun extraSecurityOffFinishesAVaultsKitWithoutTheCheck() {
        realKeyring()
        ExtraSecurity.set(ctx, false)
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            walkToOwnKit()
            acknowledgeAndContinue()
            await("the vault list") { compose.onAllNodes(hasContentDescription("New vault")).fetchSemanticsNodes().isNotEmpty() }
            assertFalse("no check with Extra Security off", KitCheckWalk.showing(compose))
        }
    }

    // --- a later view ----------------------------------------------------------------------------

    @Test fun aViewFromTheMenuIsNeverChecked() {
        realKeyring()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            compose.onNode(hasContentDescription("Menu") and hasClickAction()).performClick()
            await("the drawer") { text("Recovery kit") }
            compose.onNode(hasText("Recovery kit", substring = true) and hasClickAction()).performScrollTo().performClick()
            await("the view screen") { text("Enter your passphrase to show it.") }
            compose.onAllNodes(hasSetTextAction())[0].performTextInput(GOOD)
            button("Show recovery kit").performClick()
            await("the words") { runCatching { KitCheckWalk.wordsOnScreen(compose) }.isSuccess }
            button("Done").performScrollTo().performClick()
            await("the list again") { compose.onAllNodes(hasContentDescription("New vault")).fetchSemanticsNodes().isNotEmpty() }
            assertFalse("no check after a later view", KitCheckWalk.showing(compose))
        }
    }

    // --- the decoy's kit -------------------------------------------------------------------------

    /** Thirty photos the picker "returns", served by the app's own provider. */
    private fun decoyPhotos(): List<android.net.Uri> {
        photos.mkdirs()
        return (0 until Decoy.MIN_ITEMS).map { i ->
            val file = File(photos, "decoy-$i.jpg")
            val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(Color.rgb(40 + i * 6, 120, 200 - i * 4))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bitmap.recycle()
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.shares", file)
        }
    }

    /** Answers the photo picker with [uris], with a read grant, and never draws it. */
    private inner class Picker(private val uris: List<android.net.Uri>) : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            val action = intent.action ?: return null
            if (!action.contains("PICK_IMAGES") && action != Intent.ACTION_GET_CONTENT && action != Intent.ACTION_OPEN_DOCUMENT) return null
            val data = Intent().apply {
                clipData = ClipData.newRawUri(null, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return Instrumentation.ActivityResult(Activity.RESULT_OK, data)
        }
    }

    @Test fun aDecoysKitIsFollowedByTheCheck() {
        realKeyring()
        val picker = Picker(decoyPhotos())
        instr.addMonitor(picker)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                unlock()
                compose.onNode(hasContentDescription("Menu") and hasClickAction()).performClick()
                await("the drawer") { text("Set up a decoy") }
                compose.onNode(hasText("Set up a decoy") and hasClickAction()).performScrollTo().performClick()
                await("the intro") { text("Start") }
                tap("Start")
                await("the photos step") { text("Choose photos") }
                compose.onNode(hasText("Choose photos") and hasClickAction()).performClick()
                await("the plan", 60_000) { compose.onAllNodes(hasText("Continue") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                tap("Continue")
                await("the name") { text("Name it and lock it") }
                type("Name", "Holidays")
                type("Passphrase for the decoy", DECOY)
                type("Enter it again", DECOY)
                tap("Continue")
                await("the build step") { text("Confirm your passphrase") }
                tap("Confirm your passphrase")
                await("the confirmation") { text("Your passphrase") }
                type("Your passphrase", GOOD)
                tap("Build the decoy")
                await("the handover", 90_000) { text("Show the recovery words") }
                tap("Show the recovery words")
                await("the decoy's kit") { text("Recovery kit for Holidays") }
                val words = KitCheckWalk.wordsOnScreen(compose)
                acknowledgeAndContinue()
                await("the check after the decoy's kit") { KitCheckWalk.showing(compose) }
                KitCheckWalk.enter(compose, words)
                await("back on the vault the flow started from", 60_000) {
                    compose.onAllNodes(hasContentDescription("New vault")).fetchSemanticsNodes().isNotEmpty()
                }
                Log.i(TAG, "decoy kit checked and the flow came back")
            }
        } finally {
            instr.removeMonitor(picker)
        }
    }
}
