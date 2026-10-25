package com.app.vault

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.Identity.PairingSession
import com.app.vault.RemotePairing.Answered
import com.app.vault.RemotePairing.Invited
import com.app.vault.RemotePairing.Opened
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Remote pairing end to end, between two simulated installs — `remote-pairing` task 2.4.
 *
 * Each install is the app's context with its own `filesDir`, so each has its own
 * `identity.bin` and `pairing.bin`; both are sealed under the one `vault.identity`
 * key, which is install-wide on a real phone too.
 *
 * **Announced test-only state.** The two directories under `filesDir` and the
 * `vault.identity` alias. `tearDown` removes all three, including on failure.
 */
@RunWith(AndroidJUnit4::class)
class RemotePairingDeviceTest {

    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val codes by lazy { ShareCode(Mnemonic.fromResources(app)) }

    private class Install(base: Context, private val dir: File) : ContextWrapper(base) {
        override fun getFilesDir(): File = dir.also { it.mkdirs() }
    }

    private val dirA = File(app.filesDir, "pairing-test-a")
    private val dirB = File(app.filesDir, "pairing-test-b")
    private val dirC = File(app.filesDir, "pairing-test-c")
    private val a = Install(app, dirA)
    private val b = Install(app, dirB)
    private val c = Install(app, dirC)

    private fun clear() {
        Identity.deleteKeyForTest()
        listOf(dirA, dirB, dirC).forEach { it.deleteRecursively() }
    }

    @Before fun setUp() = clear()

    @After fun tearDown() = clear()

    private fun publicKey(install: Context) = (Identity.state(install) as Identity.State.Ready).publicKey
    private fun contacts(install: Context) = Identity.contacts(install)

    private fun invite(name: String = "Bea", now: Long = System.currentTimeMillis()): Pair<Invited.Made, List<String>> {
        val words = codes.generate()
        return (RemotePairing.makeInvite(a, name, words, now) as Invited.Made) to words
    }

    private fun answer(
        invite: ByteArray,
        words: List<String>,
        from: Context = b,
        now: Long = System.currentTimeMillis(),
    ): PairingSession.Answering =
        (RemotePairing.answerInvite(from, invite, words, "Alonzo", now) as Answered.Made).session

    private fun otherWords(words: List<String>): List<String> {
        while (true) codes.generate().let { if (it != words) return it }
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { i -> needle.indices.all { this[i + it] == needle[it] } }

    // --- the happy path ----------------------------------------------------------

    @Test
    fun twoInstallsPairAndEachHoldsTheOther() {
        val (made, words) = invite()
        assertEquals(RemotePairing.INVITE_BYTES, made.file.size)
        val answered = answer(made.file, words)
        assertEquals(RemotePairing.REPLY_BYTES, answered.reply.size)

        val opened = RemotePairing.openReply(a, made.session.sid, answered.reply) as Opened.Saved
        assertNull(opened.alreadyKnownAs)
        val confirmation = opened.confirmation!!
        assertEquals(RemotePairing.CONFIRM_BYTES, confirmation.size)
        assertEquals(listOf(Identity.Contact("Bea", publicKey(b))), contacts(a))

        val confirmed = RemotePairing.openConfirmation(b, answered.sid, confirmation)
        assertTrue(confirmed is Opened.Saved)
        assertEquals(listOf(Identity.Contact("Alonzo", publicKey(a))), contacts(b))

        assertFalse("B's finished session leaves no file", Identity.pairingFile(b).exists())
        assertTrue(Identity.pairingSessions(a)!!.single() is PairingSession.Confirming)
        RemotePairing.end(a, made.session.sid)
        assertFalse("A's finished session leaves no file", Identity.pairingFile(a).exists())
    }

    @Test
    fun aKnownContactStillReachesTheConfirmation() {
        val (made, words) = invite()
        Identity.add(a, publicKey(b), "Beatrice")
        val answered = answer(made.file, words)
        val opened = RemotePairing.openReply(a, made.session.sid, answered.reply) as Opened.Saved
        assertEquals("Beatrice", opened.alreadyKnownAs)
        assertNotNull("the other side can still add this phone", opened.confirmation)
        assertTrue(RemotePairing.openConfirmation(b, answered.sid, opened.confirmation!!) is Opened.Saved)
    }

    // --- one attempt per invite --------------------------------------------------

    @Test
    fun wrongWordsEndTheInviteAndSaveNothing() {
        val (made, words) = invite()
        val answered = answer(made.file, otherWords(words))
        assertEquals(Opened.Ended, RemotePairing.openReply(a, made.session.sid, answered.reply))
        assertEquals(emptyList<Identity.Contact>(), contacts(a))
        assertEquals(emptyList<PairingSession>(), Identity.pairingSessions(a))
    }

    @Test
    fun aForgedReplyEndsTheInviteAndTheRealOneFindsNothing() {
        val (made, words) = invite()
        val forged = answer(made.file, otherWords(words), from = c)
        val genuine = answer(made.file, words)
        assertEquals(Opened.Ended, RemotePairing.openReply(a, made.session.sid, forged.reply))
        assertEquals(
            "a second reply for that sid belongs to no waiting invite",
            Opened.Gone,
            RemotePairing.openReply(a, made.session.sid, genuine.reply),
        )
        assertEquals(emptyList<Identity.Contact>(), contacts(a))
    }

    @Test
    fun anUnrelatedFileEndsNothing() {
        val (made, words) = invite()
        val (other, otherCode) = invite("Cy")
        val otherReply = answer(other.file, otherCode)
        assertEquals(Opened.WrongFile, RemotePairing.openReply(a, made.session.sid, otherReply.reply))
        assertEquals(Opened.WrongFile, RemotePairing.openReply(a, made.session.sid, made.file))
        assertEquals(2, Identity.pairingSessions(a)!!.size)
        val reply = answer(made.file, words)
        assertTrue("the invite is still waiting", RemotePairing.openReply(a, made.session.sid, reply.reply) is Opened.Saved)
    }

    @Test
    fun everyByteOfTheReplyIsAuthenticated() {
        val (made, words) = invite()
        val reply = answer(made.file, words).reply
        for (i in reply.indices) {
            Identity.savePairing(a, listOf(made.session))
            val tampered = reply.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            val outcome = RemotePairing.openReply(a, made.session.sid, tampered)
            assertTrue("byte $i: $outcome", outcome == Opened.Ended || outcome == Opened.WrongFile)
        }
        assertEquals(emptyList<Identity.Contact>(), contacts(a))
    }

    @Test
    fun everyByteOfTheConfirmationIsAuthenticated() {
        val (made, words) = invite()
        val answered = answer(made.file, words)
        val confirmation = (RemotePairing.openReply(a, made.session.sid, answered.reply) as Opened.Saved).confirmation!!
        for (i in confirmation.indices) {
            Identity.savePairing(b, listOf(answered))
            val tampered = confirmation.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            val outcome = RemotePairing.openConfirmation(b, answered.sid, tampered)
            assertTrue("byte $i: $outcome", outcome == Opened.Ended || outcome == Opened.WrongFile)
            if (outcome == Opened.Ended) {
                assertEquals("a failed confirmation ends the session", emptyList<PairingSession>(), Identity.pairingSessions(b))
            }
        }
        assertEquals(emptyList<Identity.Contact>(), contacts(b))
    }

    @Test
    fun anIdentityPointShareEndsTheInvite() {
        val (made, words) = invite()
        val reply = answer(made.file, words).reply
        ByteArray(Cpace.POINT_BYTES).copyInto(reply, RemotePairing.SID_BYTES)
        assertEquals(Opened.Ended, RemotePairing.openReply(a, made.session.sid, reply))
        assertEquals(emptyList<PairingSession>(), Identity.pairingSessions(a))

        val identityInvite = made.session.sid + ByteArray(Cpace.POINT_BYTES)
        assertEquals(Answered.NotAnInvite, RemotePairing.answerInvite(b, identityInvite, words, "Al"))
        assertEquals(Answered.NotAnInvite, RemotePairing.answerInvite(b, made.file.copyOf(47), words, "Al"))
    }

    @Test
    fun aReplayAfterCompletionChangesNothing() {
        val (made, words) = invite()
        val answered = answer(made.file, words)
        val confirmation = (RemotePairing.openReply(a, made.session.sid, answered.reply) as Opened.Saved).confirmation!!
        assertEquals(Opened.Gone, RemotePairing.openReply(a, made.session.sid, answered.reply))
        assertEquals(1, contacts(a).size)
        RemotePairing.openConfirmation(b, answered.sid, confirmation)
        assertEquals(Opened.Gone, RemotePairing.openConfirmation(b, answered.sid, confirmation))
        assertEquals(1, contacts(b).size)
    }

    @Test
    fun thisPhonesOwnInviteIsRefused() {
        val (made, words) = invite()
        assertEquals(Answered.OwnInvite, RemotePairing.answerInvite(a, made.file, words, "Me"))
    }

    // --- the store ---------------------------------------------------------------

    @Test
    fun aSessionExpiresAfter24Hours() {
        val start = 1_000_000L
        val (made, words) = invite(now = start)
        val reply = answer(made.file, words, now = start).reply
        val late = start + Identity.PAIRING_LIFETIME_MS
        assertEquals(Opened.Expired, RemotePairing.openReply(a, made.session.sid, reply, now = late))
        assertEquals(emptyList<Identity.Contact>(), contacts(a))
        assertFalse(Identity.pairingFile(a).exists())

        val (second, _) = invite(now = start)
        assertEquals(1, Identity.pairingSessions(a, now = late - 1)!!.size)
        assertEquals("dropped on read", emptyList<PairingSession>(), Identity.pairingSessions(a, now = late))
        assertNull(Identity.loadPairing(a)!!.firstOrNull { it.sid.contentEquals(second.session.sid) })
    }

    @Test
    fun aWaitingInviteSurvivesARestart() {
        val (made, words) = invite()
        // Nothing is cached between calls: this read is the relaunch's read.
        val resumed = Identity.pairingSessions(a)!!.single() as PairingSession.Inviting
        assertEquals(words.joinToString(" "), resumed.words)
        assertEquals("Bea", resumed.name)
        assertArrayEquals("the invite is the same bytes again", made.file, RemotePairing.inviteFile(resumed))
        val reply = answer(RemotePairing.inviteFile(resumed), words)
        assertTrue(RemotePairing.openReply(a, resumed.sid, reply.reply) is Opened.Saved)
    }

    @Test
    fun finishedSessionsLeaveNoSecretBehind() {
        val (kept, _) = invite("Kept")
        val (cancelled, cancelledWords) = invite("Cancelled")
        RemotePairing.end(a, cancelled.session.sid)
        val (failed, failedWords) = invite("Failed")
        RemotePairing.openReply(a, failed.session.sid, answer(failed.file, otherWords(failedWords)).reply)

        val left = Identity.loadPairing(a)!!
        assertEquals(listOf("Kept"), left.map { it.name })
        assertArrayEquals(kept.session.sid, left.single().sid)
        val plain = Identity.encodeSessions(left)
        for ((session, words) in listOf(cancelled.session to cancelledWords, failed.session to failedWords)) {
            assertFalse(plain.contains(session.sid))
            assertFalse(plain.contains(session.scalar))
            assertFalse(plain.contains(words.joinToString(" ").toByteArray()))
        }
    }

    @Test
    fun atMostSixteenAreHeldAndTheOldestGoes() {
        val first = invite(now = 1_000L).first
        repeat(Identity.MAX_PAIRING_SESSIONS) { invite("N$it", now = 2_000L + it) }
        val held = Identity.loadPairing(a)!!
        assertEquals(Identity.MAX_PAIRING_SESSIONS, held.size)
        assertTrue(held.none { it.sid.contentEquals(first.session.sid) })
    }

    @Test
    fun anUnreadableStoreIsTheIdentitysUnreadableCondition() {
        invite()
        Identity.deleteKeyForTest()
        assertNull(Identity.pairingSessions(a))
        assertEquals(Identity.State.Unreadable, Identity.state(a))
        assertTrue("nothing is deleted on the user's behalf", Identity.pairingFile(a).exists())
    }

    // --- what travels ------------------------------------------------------------

    @Test
    fun theFilesCarryNoReadableKeyNameOrWords() {
        val (made, words) = invite()
        val answered = answer(made.file, words)
        val confirmation = (RemotePairing.openReply(a, made.session.sid, answered.reply) as Opened.Saved).confirmation!!
        val keyA = publicKey(a)
        val keyB = publicKey(b)
        for (file in listOf(made.file, answered.reply, confirmation)) {
            assertFalse(file.contains(keyA.copyOf(16)))
            assertFalse(file.contains(keyA.copyOfRange(keyA.size - 16, keyA.size)))
            assertFalse(file.contains(keyB.copyOf(16)))
            assertFalse(file.contains(keyB.copyOfRange(keyB.size - 16, keyB.size)))
            assertFalse(file.contains("Bea".toByteArray()))
            assertFalse(file.contains("Alonzo".toByteArray()))
            assertFalse(file.contains(words.first().toByteArray()))
            assertFalse(file.contains("vault".toByteArray()))
        }
    }

    @Test
    fun eachDerivedKeySealsOneMessage() {
        val isk = ByteArray(Cpace.ISK_BYTES) { it.toByte() }
        val keys = RemotePairing.keys(isk)
        assertFalse("k_reply and k_confirm differ", keys.reply.contentEquals(keys.confirm))

        val (made, words) = invite()
        val answered = answer(made.file, words)
        val confirmation = (RemotePairing.openReply(a, made.session.sid, answered.reply) as Opened.Saved).confirmation!!
        val confirming = Identity.pairingSessions(a)!!.single() as PairingSession.Confirming
        assertArrayEquals(
            "k_confirm only ever seals A's key: sent again, it is the same bytes",
            confirmation,
            RemotePairing.confirmationFile(a, confirming),
        )
        val again = answer(made.file, words)
        assertFalse(
            "a second answer is a fresh CPace run, so a fresh k_reply",
            again.reply.copyOfRange(RemotePairing.SID_BYTES, RemotePairing.REPLY_BYTES)
                .contentEquals(answered.reply.copyOfRange(RemotePairing.SID_BYTES, RemotePairing.REPLY_BYTES)),
        )
    }
}
