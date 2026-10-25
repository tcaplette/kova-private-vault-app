package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.app.vault.ui.ShareSelections
import com.app.vault.ui.SharePolicyChoice
import java.io.File

/**
 * What may be shared, and what the send path is not allowed to keep — P4-2.
 *
 * JVM: the rules under test are decisions about an item and about source, neither
 * of which needs a device. Sealing itself is `ShareSendDeviceTest`'s.
 */
class ShareSendTest {

    private fun item(
        filename: String,
        durationMs: Long? = null,
        deletedAt: Long? = null,
        body: String? = null,
        noteId: ByteArray? = null,
    ) = VaultItem(
        blobIds = listOf(ByteArray(16) { 1 }),
        plaintextLength = 1024,
        thumb = null,
        filename = filename,
        album = null,
        createdAt = 0,
        importedAt = 0,
        tags = emptyList(),
        durationMs = durationMs,
        deletedAt = deletedAt,
        body = body,
        noteId = noteId,
    )

    // ── Which kinds ───────────────────────────────────────────────────────────

    @Test
    fun everyKindTheVaultHoldsIsShareable() {
        assertEquals(ShareContainer.Kind.IMAGE, ShareSend.kindOf(item("a.jpg")))
        assertEquals(ShareContainer.Kind.VIDEO, ShareSend.kindOf(item("a.mp4", durationMs = 5_000)))
        assertEquals(ShareContainer.Kind.VOICE_MEMO, ShareSend.kindOf(item("a.m4a")))
        assertEquals(
            ShareContainer.Kind.NOTE,
            ShareSend.kindOf(item("a.md", body = "text", noteId = ByteArray(16) { 2 })),
        )
        assertEquals(ShareContainer.Kind.FILE, ShareSend.kindOf(item("contract.pdf")))
    }

    @Test
    fun aFileTheAppDoesNotRenderIsShareableToo() {
        // It was not, and that was the defect: the file kind was left out of §12's
        // prelude when the feature was built, so a document in the vault had no
        // share action anywhere. Code 5 is that kind.
        val doc = item("contract.pdf")
        assertEquals(ShareContainer.Kind.FILE, ShareSend.kindOf(doc))
        assertNull(ShareSend.refusal(TestWords, doc))
        assertTrue(ShareSend.canShare(doc))
    }

    @Test
    fun everyPreludeCodeIsDistinctAndNoneAreRenumbered() {
        // The byte on disk is the contract between two devices that may not be on
        // the same version. A renumbering silently opens the wrong kind on a build
        // that predates it, which is worse than failing to open at all.
        assertEquals(
            "the four that shipped keep their codes, and the file kind is the fifth",
            listOf("IMAGE", "VIDEO", "NOTE", "VOICE_MEMO", "FILE"),
            ShareContainer.Kind.values().map { it.name },
        )
    }

    // ── The video cap ─────────────────────────────────────────────────────────

    @Test
    fun aShortVideoIsShareable() {
        assertNull(ShareSend.refusal(TestWords, item("clip.mp4", durationMs = 59_000)))
        assertNull(ShareSend.refusal(TestWords, item("clip.mp4", durationMs = 60_000)))
    }

    @Test
    fun anOverLongVideoIsRefusedWithItsReason() {
        val refusal = ShareSend.refusal(TestWords, item("film.mp4", durationMs = 61_000))
        assertNotNull(refusal)
        assertTrue("the refusal states the cap", refusal!!.contains("60"))
    }

    @Test
    fun aVideoWithNoRecordedLengthIsRefused() {
        // Null is not "no limit": the cap cannot be checked, and sharing it
        // unchecked would be a silent hole in the one limit this feature has.
        val refusal = ShareSend.refusal(TestWords, item("mystery.mp4", durationMs = null))
        assertNotNull(refusal)
        assertTrue("the refusal explains itself", refusal!!.contains("length"))
    }

    @Test
    fun theCapAppliesOnlyToVideo() {
        // An hour-long voice memo is not a video and is not capped.
        assertNull(ShareSend.refusal(TestWords, item("memo.m4a", durationMs = 3_600_000)))
    }

    @Test
    fun aTrashedItemIsNotShareable() {
        assertNotNull(ShareSend.refusal(TestWords, item("a.jpg", deletedAt = 1)))
    }

    // ── What the path must not keep ───────────────────────────────────────────

    private fun sourceDir(): File {
        var dir: File? = File(".").absoluteFile
        val rel = "src/main/java/com/app/vault"
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isDirectory }
                ?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the source package from ${File(".").absolutePath}")
    }

    private fun stripped(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")

    @Test
    fun theSendPathExists() {
        listOf("ShareSend.kt", "ShareCode.kt", "ui/ShareScreen.kt").forEach {
            assertTrue("$it not found", File(sourceDir(), it).isFile)
        }
    }

    @Test
    fun nothingInTheSendPathPersistsACode() {
        // §7.19: the code is shown once and is not saved. A code reaching
        // SharedPreferences, the index, or a file is that promise broken, and it
        // would put both halves of every share on the sender's own device.
        val sources = listOf("ShareSend.kt", "ShareCode.kt", "ui/ShareScreen.kt")
            .map { File(sourceDir(), it).readSource() }
            .joinToString("\n") { stripped(it) }

        listOf("SharedPreferences", "getPreferences", "MetadataDb", "VaultItem(").forEach {
            assertTrue("the send path must not reach $it", !sources.contains(it))
        }
    }

    @Test
    fun containersAreWrittenInsideFilesDirOnly() {
        // I9. `cacheDir` is not `filesDir`, and an external dir is neither.
        val source = stripped(File(sourceDir(), "ShareSend.kt").readSource())
        assertTrue("containers live in filesDir", source.contains("filesDir"))
        listOf("cacheDir", "getExternalFilesDir", "getExternalStorage").forEach {
            assertTrue("the send path must not write to $it", !source.contains(it))
        }
    }

    @Test
    fun theSweepDoesNotWaitForACompletionSignal() {
        // Android reports none, so a sweep is the only thing that can bound a
        // container's life. Both call sites must exist.
        val activity = stripped(File(sourceDir(), "MainActivity.kt").readSource())
        assertTrue(
            "the unlock path sweeps share containers",
            activity.contains("ShareSend.sweep"),
        )
        assertEquals(
            "both the unlock sweep and the leave-screen sweep are present",
            2,
            Regex("ShareSend\\.sweep").findAll(activity).count(),
        )
    }

    // ── What the handed-over file says about the app — `share-format-v2` ─────

    @Test
    fun aContainerIsNamedEightHexCharactersAndBin() {
        val names = List(200) { ShareSend.containerName() }
        names.forEach {
            assertTrue("«$it» must match [0-9a-f]{8}\\.bin", it.matches(Regex("[0-9a-f]{8}\\.bin")))
        }
        // Random, not a counter or a stamp: two hundred draws from 2³² collide
        // with probability under one in a hundred thousand.
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun bothSendStatesOfferSaveToDevice() {
        // `FORMAT.md` §10 item 51: the share sheet tells the receiving app who sent the
        // file; saving and attaching from Files does not. Both modes get the route.
        val screen = stripped(File(sourceDir(), "ui/ShareScreen.kt").readSource())
        val code = screen.substringAfter("private fun SendState(").substringBefore("private fun ContactSendState(")
        val contact = screen.substringAfter("private fun ContactSendState(").substringBefore("private fun SaveRoute(")
        assertTrue("the code send state offers it", code.contains("SaveRoute(onSave, saved)"))
        assertTrue("the contact send state offers it", contact.contains("SaveRoute(onSave, saved)"))
        val route = screen.substringAfter("private fun SaveRoute(")
        assertTrue("below the primary, as a text button", route.contains("TextButton("))
        assertTrue("the result is announced", route.contains("LiveRegionMode.Polite"))
        val labels = File(sourceDir(), "ui/ShareScreen.kt").readSource()
        listOf(
            "Save to device",
            "Or save it and attach it from Files. The app you send with then can't ",
            "Saved. Attach it from Files in the app you send with.",
            "The file couldn't be saved. Try again, or send it instead.",
        ).forEach { assertTrue("the send screen must say «$it»", labels.contains(it)) }
    }

    @Test
    fun savingWritesTheSealedFileAndNothingElse() {
        val send = stripped(File(sourceDir(), "ShareSend.kt").readSource())
        val save = send.substringAfter("fun saveTo(").substringBefore("fun sweep(")
        assertTrue("the container's own bytes are copied", save.contains("container.inputStream()"))
        listOf("BlobStore", "reader(", "openWith", "VaultNote").forEach {
            assertTrue("saving must not reach plaintext through «$it»", !save.contains(it))
        }
        val activity = stripped(File(sourceDir(), "MainActivity.kt").readSource())
        val launch = activity.substringAfter("private fun saveShare(").substringBefore("private fun ")
        assertTrue("the save dialog is offered the neutral name", launch.contains("saveShareFile.launch(file.name)"))
        assertTrue("auto-lock waits for the dialog", launch.contains("AutoLock.awaitingResult(true)"))
    }

    @Test
    fun theFileIsHandedOverAsOpaqueBytes() {
        val activity = stripped(File(sourceDir(), "MainActivity.kt").readSource())
        // The intent is built once, in the hand-off both a share and a pairing file leave by.
        assertTrue(
            "the share leaves through the one hand-off",
            activity.substringAfter("private fun sendShare(").substringBefore("private fun ").contains("handOff(file)"),
        )
        val send = activity.substringAfter("private fun handOff(").substringBefore("private fun ")
        assertTrue(
            "the send intent's type is application/octet-stream",
            send.contains("type = \"application/octet-stream\""),
        )
        val sources = listOf("ShareSend.kt", "ShareOpen.kt", "ShareContainer.kt", "MainActivity.kt")
            .joinToString("\n") { stripped(File(sourceDir(), it).readSource()) }
        listOf(".vshare", "VSHARE").forEach {
            assertTrue("nothing on the share path may still write «$it»", !sources.contains(it))
        }
    }

    // ── the recipient choice and the code warning — `remote-pairing` D6 ───────

    private fun contact(name: String, fill: Int) =
        Identity.Contact(name, ByteArray(Identity.PUBLIC_KEY_BYTES) { fill.toByte() })

    @Test
    fun theInitialRecipientIsTheFirstContactOtherwiseTheCode() {
        assertNull("no contacts: Anyone with the code", ShareSelections.initial(emptyList()).recipient)
        val ana = contact("Ana", 1)
        val zoe = contact("Zoe", 2)
        val initial = ShareSelections.initial(listOf(ana, zoe))
        assertEquals("the first contact, in the store's order", ana, initial.recipient)
        assertEquals("the policy default is unchanged", SharePolicyChoice.NoLimit, initial.choice)
        assertTrue("and metadata is removed by default", initial.stripMetadata)
    }

    @Test
    fun theSelectionIsMadeFreshOnEveryOpenAndNeverStored() {
        val activity = stripped(File(sourceDir(), "MainActivity.kt").readSource())
        val begin = activity.substringAfter("private fun beginShare(").substringBefore("private fun ")
        assertTrue(
            "the send flow chooses its recipient as it opens",
            begin.contains("shareSelections = ShareSelections.initial(shareContacts)"),
        )
        val screen = stripped(File(sourceDir(), "ui/ShareScreen.kt").readSource())
        listOf("SharedPreferences", "getSharedPreferences", "rememberSaveable").forEach {
            assertTrue("the recipient must not be stored via «$it»", !screen.contains(it))
        }
    }

    @Test
    fun contactsComeFirstAndTheCodeIsLastWithItsDetail() {
        val screen = File(sourceDir(), "ui/ShareScreen.kt").readSource()
        val compose = screen.substringAfter("private fun ComposeState(").substringBefore("private fun CodeWarning(")
        val contactsAt = compose.indexOf("contacts.forEach")
        val codeAt = compose.indexOf("name = \"Anyone with the code\"")
        assertTrue("both rows are drawn", contactsAt >= 0 && codeAt >= 0)
        assertTrue("contacts before the code row", contactsAt < codeAt)
        assertTrue(
            "the code row says it is less secure",
            compose.contains("detail = \"Less secure. Anyone with the file and the words can open it.\""),
        )
    }

    @Test
    fun theWarningIsDrawnForTheCodeAndAbsentForAContact() {
        val screen = File(sourceDir(), "ui/ShareScreen.kt").readSource()
        val compose = stripped(screen).substringAfter("private fun ComposeState(").substringBefore("private fun CodeWarning(")
        assertTrue("only while the code is selected", compose.contains("if (recipient == null) CodeWarning(onPair)"))
        val rows = compose.indexOf("name = \"Anyone with the code\"")
        val warning = compose.indexOf("CodeWarning(onPair)")
        val limits = compose.indexOf("How long it opens for")
        assertTrue("directly under the recipient rows, above the policy", warning in (rows + 1) until limits)

        val block = screen.substringAfter("private fun CodeWarning(").substringBefore("private fun SendState(")
        assertTrue("marked with the error glyph, not colour alone", block.contains("R.drawable.ic_error"))
        assertTrue("tinted in the error colour", block.contains("tint = VaultColors.Error"))
        listOf(
            "Less secure than sending to a contact",
            "Anyone who has the file and the four words can open it, on any phone.",
            "“Open once” means once per phone. Passed on, it opens again.",
            "Words sent in the same chat as the file give away both.",
            "Anyone who keeps a copy of the file can keep guessing the words, with no limit.",
            "To share with one person only, pair with them first.",
            "Pair with someone",
        ).forEach { assertTrue("the warning must say «$it»", block.contains(it)) }
    }

    @Test
    fun theLimitsSentenceNoLongerRepeatsThePassedOnFact() {
        val screen = File(sourceDir(), "ui/ShareScreen.kt").readSource()
        assertTrue(
            "the warning says it; the limits sentence must not say it twice",
            !screen.contains("Pass the file and code on and it opens again on the next phone."),
        )
    }

    @Test
    fun pairingFromTheWarningReturnsToTheSameCompose() {
        val activity = stripped(File(sourceDir(), "MainActivity.kt").readSource())
        assertTrue("the warning opens the add-a-contact choice", activity.contains("onPair = ::openAddContact"))
        assertTrue("the selections live above the screen", activity.contains("selections = shareSelections"))
        listOf("private fun closePairing(", "private fun closeRemote(").forEach { fn ->
            val body = activity.substringAfter(fn).substringBefore("private fun ")
            assertTrue(
                "$fn re-reads the contacts for a send flow underneath",
                body.contains("if (sharingItem != null) shareContacts = Identity.contacts(this)"),
            )
            assertTrue("$fn leaves the selections as they were", !body.contains("shareSelections ="))
        }
    }
}
