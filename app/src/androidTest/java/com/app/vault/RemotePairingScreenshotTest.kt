package com.app.vault

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.AddContactScreen
import com.app.vault.ui.ContactsScreen
import com.app.vault.ui.RemoteEnd
import com.app.vault.ui.RemotePairingScreen
import com.app.vault.ui.RemoteStep
import com.app.vault.ui.ShareScreen
import com.app.vault.ui.ShareSelections
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `remote-pairing` task 6.4, on screen: the add-a-contact choice, every inviter and
 * responder step, the reply failure, the Waiting section, and the send screen with a
 * contact and with the code selected.
 *
 * `FLAG_SECURE` is cleared on a throwaway `ComponentActivity` and the frame is taken
 * by `uiAutomation` — the real composited pixels. Each shot also asserts the words
 * design D5/D6 specify. Written to `/data/local/tmp/remote-pairing`. Writes nothing
 * to the app's own storage.
 */
@RunWith(AndroidJUnit4::class)
class RemotePairingScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val mnemonic by lazy { Mnemonic.fromResources(instr.targetContext) }

    private companion object {
        const val DIR = "/data/local/tmp/remote-pairing"
        val SID = ByteArray(16) { it.toByte() }
        val WORDS = listOf("defense", "winner", "silent", "sign")
        val BEA = Identity.Contact("Bea", ByteArray(Identity.PUBLIC_KEY_BYTES) { 1 })
    }

    private fun shot(name: String, content: @Composable () -> Unit, check: () -> Unit = {}) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent { VaultTheme(ThemeChoice.Dark) { content() } }
            }
            compose.waitForIdle()
            check()
            Thread.sleep(400)
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
    }

    private fun drain(fd: android.os.ParcelFileDescriptor) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    private fun has(text: String) =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Composable
    private fun Remote(step: RemoteStep) = RemotePairingScreen(
        step = step,
        mnemonic = mnemonic,
        onNameChange = {}, onWordsChange = {}, onMake = {}, onSend = {}, onPick = {},
        onCancel = {}, onNewInvite = {}, onDone = {}, onBack = {},
    )

    private val tomorrow get() = System.currentTimeMillis() + Identity.PAIRING_LIFETIME_MS

    @Test fun addContactChoice() = shot("01-add-contact-choice", { AddContactScreen(onChoose = {}, onBack = {}) }) {
        assertTrue(has("Together"))
        assertTrue(has("Scan each other's codes. The strongest way to pair."))
        assertTrue(has("Send them an invite file and four words."))
        assertTrue(has("Open the file they sent and type their four words."))
    }

    @Test fun inviterNaming() = shot("02-inviter-1-name", { Remote(RemoteStep.Naming("Bea")) }) {
        assertTrue(has("Step 1 of 3"))
        assertTrue(has("Who are you inviting?"))
        assertTrue(has("The name is yours alone and stays on this phone."))
        assertTrue(has("Make invite"))
    }

    @Test fun inviterReady() = shot("03-inviter-2-ready", {
        Remote(RemoteStep.Inviting(SID, "Bea", WORDS, tomorrow, sent = false, problem = null))
    }) {
        assertTrue(has("Step 2 of 3"))
        assertTrue(has("defense winner silent sign"))
        assertTrue(has("Whoever has the file and these words can become this contact."))
        assertTrue(has("Send invite file"))
    }

    @Test fun inviterWaiting() = shot("04-inviter-2-waiting", {
        Remote(RemoteStep.Inviting(SID, "Bea", WORDS, tomorrow, sent = true, problem = null))
    }) {
        assertTrue(has("Open Bea's reply"))
        assertTrue(has("Send invite file again"))
        assertTrue(has("Waiting for Bea's reply. This invite ends tomorrow at"))
        assertFalse("never 'ends at tomorrow'", has("ends at tomorrow"))
        assertTrue(has("Cancel invite"))
    }

    @Test fun inviterDone() = shot("05-inviter-3-done", {
        Remote(RemoteStep.Confirming(SID, "Bea", alreadyKnownAs = null, sent = false))
    }) {
        assertTrue(has("Step 3 of 3"))
        assertTrue(has("Bea is now a contact."))
        assertTrue(has("Send confirmation file"))
    }

    @Test fun responderOpen() = shot("06-responder-1-open", {
        Remote(RemoteStep.Opening(picked = false, name = "", words = List(4) { "" }, problem = null))
    }) {
        assertTrue(has("Step 1 of 3"))
        assertTrue(has("Open the invite file"))
    }

    @Test fun responderEntry() = shot("07-responder-1-entry", {
        Remote(RemoteStep.Opening(picked = true, name = "Ana", words = WORDS, problem = null))
    }) {
        assertTrue(has("Who sent it?"))
        assertTrue(has("Their four words"))
        assertTrue(has("Only continue if you know who sent this."))
        assertTrue(has("Make reply"))
    }

    @Test fun responderWaiting() = shot("08-responder-2-waiting", {
        Remote(RemoteStep.Replying(SID, "Ana", tomorrow, sent = true, problem = null))
    }) {
        assertTrue(has("Step 2 of 3"))
        assertTrue(has("Open Ana's confirmation"))
        assertTrue(has("Send reply file again"))
        assertTrue(has("Waiting for Ana's confirmation."))
    }

    @Test fun responderDone() = shot("09-responder-3-done", { Remote(RemoteStep.Answered("Ana", null)) }) {
        assertTrue(has("Step 3 of 3"))
        assertTrue(has("Ana is now a contact."))
    }

    @Test fun replyFailed() = shot("10-reply-failed", {
        Remote(RemoteStep.Ended(RemoteEnd.ReplyDidNotMatch, "Bea", inviter = true, number = 2))
    }) {
        assertTrue(has("That reply didn't match, so this invite has ended."))
        assertTrue(has("latest version of the app"))
        assertTrue(has("Make a new invite"))
    }

    @Test fun waitingSection() = shot("11-contacts-waiting", {
        val now = System.currentTimeMillis()
        ContactsScreen(
            state = Identity.State.Ready(ByteArray(Identity.PUBLIC_KEY_BYTES), listOf(BEA)),
            waiting = listOf(
                Identity.PairingSession.Inviting(SID, now, "Cy", ByteArray(32), WORDS.joinToString(" ")),
                Identity.PairingSession.Answering(ByteArray(16) { 9 }, now - 1, "Dee", ByteArray(32), ByteArray(1280)),
                Identity.PairingSession.Confirming(ByteArray(16) { 7 }, now - 2, "Eli", ByteArray(32)),
            ),
            onResume = {},
            removing = null,
            onAdd = {},
            onAskRemove = {},
            onConfirmRemove = {},
            onDismissRemove = {},
            confirmingNewIdentity = false,
            onAskNewIdentity = {},
            onConfirmNewIdentity = {},
            onDismissNewIdentity = {},
            onBack = {},
        )
    }) {
        assertTrue(has("Waiting"))
        assertTrue(has("Invite sent — waiting for their reply"))
        assertTrue(has("Reply sent — waiting for their confirmation"))
        assertTrue(has("Confirmation ready to send"))
        assertFalse("waiting rows never show the words", has("defense"))
    }

    @Composable
    private fun Send(recipient: Identity.Contact?) = ShareScreen(
        itemName = "holiday.jpg",
        itemSize = 2_400_000,
        refusal = null,
        contacts = listOf(BEA),
        selections = ShareSelections(recipient = recipient),
        onSelections = {},
        onPair = {},
        code = null,
        sealedTo = null,
        busy = false,
        progress = null,
        failed = null,
        strippable = true,
        onCreate = { _, _, _ -> },
        onSend = {},
        onCopyCode = {},
        onBack = {},
    )

    @Test fun sendToContact() = shot("12-send-contact", { Send(ShareSelections.initial(listOf(BEA)).recipient) }) {
        assertTrue(has("Bea"))
        assertTrue(has("Less secure. Anyone with the file and the words can open it."))
        assertFalse("no warning while a contact is selected", has("Less secure than sending to a contact"))
    }

    @Test fun sendWithCode() = shot("13-send-code-warning", { Send(null) }) {
        assertTrue(has("Less secure than sending to a contact"))
        assertTrue(has("Anyone who has the file and the four words can open it, on any phone."))
        assertTrue(has("To share with one person only, pair with them first."))
        assertTrue(has("Pair with someone"))
    }
}
