package com.app.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The identity file against a real Keystore — P4-4, `FORMAT.md` §13.
 *
 * The wrap is the half that cannot be tested on the JVM: a Keystore key exists on
 * a device or it does not, and the state this feature has to get right is the one
 * where the file outlives the key.
 *
 * **Announced test-only state.** These tests write the app's real `identity.bin`
 * and the real `vault.identity` alias, both of which are install-wide by design.
 * `tearDown` removes both, including on failure, so the emulator ends where it
 * started.
 */
@RunWith(AndroidJUnit4::class)
class IdentityDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun file() = File(ctx.filesDir, "identity.bin")
    private fun temp() = File(ctx.filesDir, "identity.bin.tmp")

    private fun clear() {
        Identity.deleteKeyForTest()
        file().delete()
        temp().delete()
    }

    @Before fun setUp() = clear()

    @After fun tearDown() = clear()

    private fun ready(): Identity.State.Ready =
        Identity.state(ctx) as Identity.State.Ready

    @Test
    fun firstUseCreatesOneIdentityAndKeepsIt() {
        assertFalse("no file before pairing is opened", file().exists())
        val first = ready()
        assertTrue("the file is written on first use", file().exists())
        assertEquals(Identity.PUBLIC_KEY_BYTES, first.publicKey.size)
        assertEquals(emptyList<Identity.Contact>(), first.contacts)

        // A second read is a relaunch as far as this object is concerned: nothing
        // is cached between calls, so the same key coming back is the file's doing.
        assertArrayEquals(first.publicKey, ready().publicKey)
    }

    @Test
    fun theKeypairMatchesTheCodeItShows() {
        val shown = ready().publicKey
        val keys = Identity.keypair(ctx)
        assertNotNull(keys)
        assertArrayEquals(
            "the public half derived for the code must be the one that opens containers",
            shown,
            keys!!.publicKey,
        )
        keys.secretKey.fill(0)
    }

    @Test
    fun contactsSurviveARelaunchAndAreSortedByName() {
        val own = ready().publicKey
        assertEquals(Identity.AddOutcome.Added, Identity.add(ctx, ByteArray(Identity.PUBLIC_KEY_BYTES) { 7 }, "Zoe"))
        assertEquals(Identity.AddOutcome.Added, Identity.add(ctx, ByteArray(Identity.PUBLIC_KEY_BYTES) { 8 }, "  Ana "))

        val after = ready()
        assertArrayEquals("the identity does not change when a contact is added", own, after.publicKey)
        assertEquals(listOf("Ana", "Zoe"), after.contacts.map { it.name })
        assertArrayEquals(ByteArray(Identity.PUBLIC_KEY_BYTES) { 8 }, after.contacts.first().publicKey)
    }

    @Test
    fun theOwnKeyAndADuplicateAreRefusedAgainstTheRealStore() {
        val own = ready().publicKey
        assertEquals(Identity.AddOutcome.OwnCode, Identity.add(ctx, own, "Me"))
        assertEquals(Identity.AddOutcome.Added, Identity.add(ctx, ByteArray(Identity.PUBLIC_KEY_BYTES) { 3 }, "Mum"))
        assertEquals(
            Identity.AddOutcome.AlreadyKnown("Mum"),
            Identity.add(ctx, ByteArray(Identity.PUBLIC_KEY_BYTES) { 3 }, "Mother"),
        )
        assertEquals(1, ready().contacts.size)
    }

    @Test
    fun removalTakesOneContactAndLeavesTheRest() {
        ready()
        Identity.add(ctx, ByteArray(Identity.PUBLIC_KEY_BYTES) { 1 }, "Ana")
        Identity.add(ctx, ByteArray(Identity.PUBLIC_KEY_BYTES) { 2 }, "Bea")
        Identity.remove(ctx, ByteArray(Identity.PUBLIC_KEY_BYTES) { 1 })

        assertEquals(listOf("Bea"), ready().contacts.map { it.name })
        // And it survives the relaunch, which is the half a removal held in memory
        // would pass without doing anything.
        assertEquals(listOf("Bea"), ready().contacts.map { it.name })
    }

    // ── the restore case ──────────────────────────────────────────────────────

    @Test
    fun aMissingKeystoreKeyIsTheNamedStateAndDeletesNothing() {
        ready()
        Identity.add(ctx, ByteArray(Identity.PUBLIC_KEY_BYTES) { 5 }, "Mum")
        val before = file().readBytes()

        // What a restore onto another handset leaves: the file, and no key.
        Identity.deleteKeyForTest()

        assertEquals(Identity.State.Unreadable, Identity.state(ctx))
        assertTrue("the file must still be there", file().exists())
        assertArrayEquals("nothing may be rewritten or deleted", before, file().readBytes())
        // Reading it twice does not quietly repair it either.
        assertEquals(Identity.State.Unreadable, Identity.state(ctx))
        assertArrayEquals(before, file().readBytes())
    }

    @Test
    fun aVersionOneFileIsTheNamedStateAndIsLeftAlone() {
        // `share-format-v2`: a version-1 file held an X25519 seed and 32-byte
        // contact keys. This build cannot use either, and says so through the
        // same state a lost Keystore key reaches — the file is not deleted until
        // the user takes that state's one action.
        ready()
        val v1 = byteArrayOf(1) + ByteArray(12 + 32 + 2 + 16) { 0x33 }
        file().writeBytes(v1)

        assertEquals(Identity.State.Unreadable, Identity.state(ctx))
        assertEquals("the receive flow reads no seed from it", null, Identity.seed(ctx))
        assertArrayEquals("nothing may be rewritten or deleted", v1, file().readBytes())

        Identity.startNewIdentity(ctx)
        assertEquals(Identity.FILE_VERSION, file().readBytes()[0].toInt())
    }

    @Test
    fun startingANewIdentityIsTheOnlyThingThatDeletesIt() {
        val original = ready().publicKey
        Identity.add(ctx, ByteArray(Identity.PUBLIC_KEY_BYTES) { 6 }, "Mum")
        Identity.deleteKeyForTest()
        assertEquals(Identity.State.Unreadable, Identity.state(ctx))

        Identity.startNewIdentity(ctx)

        val fresh = ready()
        assertFalse(
            "a new identity means a new code",
            fresh.publicKey.contentEquals(original),
        )
        assertEquals("and no contacts", emptyList<Identity.Contact>(), fresh.contacts)
        assertFalse("the temp file is not left behind", temp().exists())
    }

    @Test
    fun anInterruptedWriteLeavesThePreviousStoreReadable() {
        ready()
        Identity.add(ctx, ByteArray(Identity.PUBLIC_KEY_BYTES) { 4 }, "Mum")
        val committed = file().readBytes()

        // A write that died before its rename: the temp file is there, the real
        // file is the old one. Both halves of the claim are asserted — the store
        // still reads, and the debris does not become the store.
        temp().writeBytes(ByteArray(64) { 0x5A })

        val after = ready()
        assertEquals(listOf("Mum"), after.contacts.map { it.name })
        assertArrayEquals(committed, file().readBytes())
    }

    // ── what the file gives up ────────────────────────────────────────────────

    @Test
    fun theFileHoldsNoPlaintextNameOrKey() {
        ready()
        val marker = "Aunt Hildegarde"
        Identity.add(ctx, ByteArray(Identity.PUBLIC_KEY_BYTES) { 2 }, marker)

        val bytes = file().readBytes()
        assertFalse(
            "a contact's name must not be readable in the file",
            String(bytes, Charsets.ISO_8859_1).contains(marker),
        )

        val keys = Identity.keypair(ctx)!!
        try {
            assertFalse("the secret key must not be in the clear", bytes.contains(keys.secretKey))
            assertFalse("nor the public half", bytes.contains(keys.publicKey))
        } finally {
            keys.secretKey.fill(0)
        }

        // The version byte is the only thing outside the AEAD, and the IV follows.
        assertEquals(Identity.FILE_VERSION, bytes[0].toInt())
        assertTrue("version ‖ IV ‖ ciphertext ‖ tag", bytes.size > 1 + 12 + 16)
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
