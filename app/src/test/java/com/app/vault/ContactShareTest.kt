package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What contact mode must not become — P4-5, in `ShareOpenTest`'s shape.
 *
 * Three of these read for something whose absence is invisible: a code generated
 * on a path that has no code to give anyone, the code's own three sentences
 * surviving into a state where they are false, and a message that tells a stranger
 * whether a container was addressed to the phone in their hand.
 */
class ContactShareTest {

    private fun repoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "app/src/main/java/com/app/vault").isDirectory) return dir
            if (File(dir, "src/main/java/com/app/vault").isDirectory) return dir.parentFile!!
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the repository from ${File(".").absolutePath}")
    }

    private fun source(name: String): String =
        File(repoRoot(), "app/src/main/java/com/app/vault/$name").readSource()

    private fun stripped(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")

    /**
     * Strings blanked, so "does the code call this" is not answered by a label.
     *
     * Deliberately a simple, non-alternating pattern: the obvious
     * `"(\\.|[^"\\])*"` backtracks catastrophically and overflowed the stack on
     * `MainActivity`, which is the largest file here. An escaped quote inside a
     * literal is the only thing this reads imprecisely, and none of the assertions
     * below turn on one.
     */
    private fun code(text: String): String =
        stripped(text).replace(Regex("\"[^\"\n]*\""), "\"\"")

    // ── the send path ─────────────────────────────────────────────────────────

    @Test
    fun theTwoSealsShareOneBody() {
        // A second copy of the seal body is a second place for the note-body rule
        // and the plaintext accounting to drift, and the drift would ship.
        val send = code(source("ShareSend.kt"))
        assertTrue(
            "both entry points must go through one private body",
            send.contains("fun seal(") && send.contains("fun sealToContact(") &&
                send.contains("private fun sealInto("),
        )
        assertEquals(
            "the plaintext stream is opened in exactly one place",
            1,
            Regex("""plaintext\(store, item, vmk""").findAll(send).count(),
        )
        assertEquals(
            "and the container is written in exactly one place",
            1,
            Regex("""file\.outputStream\(\)""").findAll(send).count(),
        )
    }

    @Test
    fun noCodeIsGeneratedForAContactShare() {
        val activity = code(source("MainActivity.kt"))
        val start = activity.substringAfter("fun startShare(").substringBefore("fun sendShare(")
        assertTrue(
            "the code is generated only when there is no recipient",
            start.contains("if (recipient == null)") && start.contains("ShareCode("),
        )
        assertTrue(
            "and the contact path seals to a public key",
            start.contains("ShareSend.sealToContact("),
        )
        // Whoever adds a second `ShareCode(` here has given a contact share a code
        // that opens nothing and told the sender to pass it on.
        assertEquals(
            "there is one code generation on the send path",
            1,
            Regex("""ShareCode\(Mnemonic""").findAll(start).count(),
        )
    }

    @Test
    fun theSendFlowKeepsNoRecipient() {
        val activity = code(source("MainActivity.kt"))
        // The name is held only to render the send state, and is cleared with the
        // rest of the flow's state — §7.19's no-history rule.
        assertTrue(
            "the recipient's name is cleared when the flow resets",
            activity.contains("shareSealedTo = null"),
        )
        val send = code(source("ShareSend.kt"))
        listOf("SharedPreferences", "prefs", "index.add", "MetadataDb").forEach {
            assertTrue("the send path must not record a recipient via $it", !send.contains(it))
        }
    }

    // ── the send screen's second shape ────────────────────────────────────────

    @Test
    fun theContactSendStateCarriesNoneOfTheCodesSentences() {
        val screen = source("ui/ShareScreen.kt")
        val contactState = screen.substringAfter("private fun ContactSendState(")
            .substringBefore("private fun PolicyRow(")
        listOf(
            "Send the code a different way",
            "Anyone who has both can open it",
            "shown once and is not saved",
            "Copy code",
            "VaultMono",
        ).forEach {
            assertTrue(
                "«$it» is about a secret a contact share does not have; it must not appear",
                !contactState.contains(it),
            )
        }
        assertTrue(
            "it says who can open it instead",
            contactState.contains("can open this"),
        )
    }

    @Test
    fun theCodeStateKeptAllThreeSentences() {
        // The second shape must not have been bought by weakening the first.
        val send = source("ui/ShareScreen.kt").substringAfter("private fun SendState(")
            .substringBefore("private fun ContactSendState(")
        listOf(
            "Send the code a different way than the file.",
            "Anyone who has both can open it",
            "This code is shown once and is not saved",
        ).forEach {
            assertTrue("the code state lost «$it»", send.contains(it))
        }
    }

    @Test
    fun theRecipientChoiceIsAlwaysDrawnAndInvitesPairingOnlyFromTheWarning() {
        // `remote-pairing` D6: drawn with no contacts too, and the code warning is the
        // one place the screen offers pairing.
        val screen = source("ui/ShareScreen.kt")
        val compose = code(screen)
            .substringAfter("private fun ComposeState(")
            .substringBefore("private fun CodeWarning(")
        assertTrue(
            "the block is no longer gated on having contacts",
            !compose.contains("if (contacts.isNotEmpty())"),
        )
        assertEquals("Pair with someone is drawn once", 1, Regex("\"Pair with someone\"").findAll(screen).count())
        assertTrue(
            "and it is inside the warning",
            screen.substringAfter("private fun CodeWarning(").substringBefore("private fun SendState(")
                .contains("Pair with someone"),
        )
    }

    // ── the receive path ──────────────────────────────────────────────────────

    @Test
    fun theFileIsProbedBeforeTheRecipientIsAskedAnything() {
        // `share-format-v2`: the file names no mode, so this install's identity is
        // tried on it first, and only what that leaves is asked about.
        val activity = code(source("MainActivity.kt"))
        val begin = activity.substringAfter("fun beginShareOpen(").substringBefore("fun openShare(")
        assertTrue("the probe runs while staging", begin.contains("ShareOpen.probe("))
        assertTrue("nothing reads a mode from the file", !activity.contains("keyModeOf"))
        assertTrue(
            "the identity is read without minting one for somebody who never paired",
            begin.contains("Identity.seed(this)") && !begin.contains("Identity.state("),
        )
        assertTrue(
            "the unreadable-identity condition is decided before the probe reads the file",
            begin.indexOf("shareIdentityUnreadable = Identity.file(this)") in 0 until begin.indexOf("ShareOpen.probe("),
        )
    }

    @Test
    fun aContactContainerAsksForNoWords() {
        val screen = code(source("ui/ShareOpenScreen.kt"))
        assertTrue(
            "a file the probe found sealed here takes its own state rather than the word entry",
            screen.contains("sealedHere -> ContactState("),
        )
        val contactState = screen.substringAfter("private fun ContactState(")
            .substringBefore("private fun RefusedState(")
        assertTrue(
            "the four fields must not be drawn for a container that needs no code",
            !contactState.contains("MnemonicWordEntry"),
        )
    }

    @Test
    fun aContainerForSomebodyElseIsTheSingleFailure() {
        // The message must be the same one a wrong code gets. Anything that named
        // the recipient would make every container an oracle for "is this the
        // phone it was sent to", answerable by a stranger holding the file.
        val screen = source("ui/ShareOpenScreen.kt")
        listOf(
            "not sent to you",
            "sealed to someone else",
            "not the recipient",
            "wrong recipient",
        ).forEach {
            assertTrue("the receive screen must never say «$it»", !screen.contains(it))
        }
        val contactState = screen.substringAfter("private fun ContactState(")
            .substringBefore("private fun RefusedState(")
        assertTrue(
            "it shows P4-3's single failure",
            contactState.contains("This share could not be opened."),
        )
    }

    @Test
    fun noSenderIsIdentifiedOnTheReceivePath() {
        // **The role, not the person.** P4-3's code screen says "the sender" and
        // must keep saying it — the recipient of a code share knows who sent it,
        // because that person read them four words. What must never appear is a
        // *named* sender, because §12's container carries none: the X-Wing
        // encapsulation spends an ephemeral key, so any name on this screen would be
        // a guess dressed as a fact.
        val screen = stripped(source("ui/ShareOpenScreen.kt"))
        val contactStates = screen.substringAfter("private fun ContactState(")
            .substringBefore("private fun RefusedState(")
        listOf("sender", "Sender", "From ", "sent by").forEach {
            assertTrue(
                "a contact share names nobody, so «$it» must not appear in its states",
                !contactStates.contains(it),
            )
        }
        // And no contact is rendered anywhere on this screen, which is the only way
        // a name could reach it at all.
        assertTrue(
            "the receive screen must not take or render a contact",
            !code(source("ui/ShareOpenScreen.kt")).contains("Identity.Contact"),
        )
        // Nor does the receive path match a container against the contact list to
        // work out who it might be from.
        val open = code(source("ShareOpen.kt"))
        assertTrue(
            "the receive path must not read the contact list",
            !open.contains("Identity.contacts("),
        )
    }

    @Test
    fun bothModesGoThroughOnePolicyGate() {
        val open = code(source("ShareOpen.kt"))
        assertTrue(
            "one shared body, two key recoveries",
            open.contains("private fun openInto(") && open.contains("fun openWithIdentity("),
        )
        listOf(
            // The gate, the ledger write and the blob write, each read at their
            // one call site inside the shared body — `isSpent` is also *defined*
            // in this file and `spend` is called by its own guard, so the strings
            // here are the call, not the name.
            "isSpent(context, opened.shareId)",
            "spend(context, opened.shareId)",
            "store.write(source, vmk)",
        ).forEach {
            assertEquals(
                "«$it» must have exactly one call site, or the single-view rule can drift",
                1,
                Regex(Regex.escape(it)).findAll(open).count(),
            )
        }
    }

    @Test
    fun theIdentitySecretIsZeroedOnBothPaths() {
        val open = code(source("ShareOpen.kt"))
        val fn = open.substringAfter("fun openWithIdentity(").substringBefore("private fun openInto(")
        assertTrue(
            "the identity seed is zeroed in a finally, so a failed open zeroes it too",
            fn.contains("finally") && fn.contains("seed.fill(0)"),
        )
        val probe = open.substringAfter("fun probe(").substringBefore("private fun openInto(")
        assertTrue(
            "and so does the probe's",
            probe.contains("finally") && probe.contains("seed.fill(0)"),
        )
    }

    @Test
    fun noShippingCodeInstallsATestIdentity() {
        // `Identity.installForTest` exists for P4-5's two-device rig, which needs a
        // device to hold the same identity across two instrumentation runs. If the
        // app itself ever calls it, an identity somebody paired with is silently
        // replaced.
        val callers = File(repoRoot(), "app/src/main/java/com/app/vault")
            .walkTopDown().filter { it.extension == "kt" }
            .filter { it.name != "Identity.kt" }
            .filter { stripped(it.readSource()).contains("installForTest") }
            .map { it.name }
            .toList()
        assertEquals("no shipping code may install a test identity", emptyList<String>(), callers)
    }

    @Test
    fun theSendScreenNeverMintsAnIdentity() {
        // `Identity.state` creates a keypair on first use. The send flow only needs
        // the list, and a user who never pairs should not acquire an identity by
        // opening the share screen.
        val activity = code(source("MainActivity.kt"))
        assertTrue(
            "the share flow reads contacts without creating an identity",
            activity.contains("shareContacts = Identity.contacts(this)"),
        )
        val identity = code(source("Identity.kt"))
        val contactsFn = identity.substringAfter("fun contacts(context: Context)")
            .substringBefore("fun keypair(")
        assertTrue(
            "and that accessor must not create one",
            !contactsFn.contains("create(context)"),
        )
    }
}
