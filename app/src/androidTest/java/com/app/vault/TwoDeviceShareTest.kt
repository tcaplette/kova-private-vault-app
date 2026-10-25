package com.app.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

/**
 * A contact share between **two real installs** — P4-5's rig, task 6.4.
 *
 * `ContactShareDeviceTest` seals to a keypair it holds; that proves the format and
 * the flow, and it cannot prove the thing a user cares about: that a container
 * written on one phone opens on another, with two separate identity files, two
 * separate Keystores, and no shared memory anywhere.
 *
 * The two halves run as separate instrumentation invocations, one per device, and
 * `scripts/two-device-contact-share.sh` moves the container between them:
 *
 *   B: [printThisInstallsPairingCode] — B's real public key, from its own store
 *   A: [sealForRecipient]             — seals to that key, writes the container
 *   host: adb moves the file A → B
 *   B: [openAsRecipient]              — opens it with B's own identity
 *
 * **The plaintext never travels.** Both sides derive the same bytes from a fixed
 * seed, so B asserting equality is B decrypting what A encrypted rather than the
 * script carrying an answer between them.
 *
 * **Announced test-only state**: this install's real identity and one container in
 * `filesDir`. The script removes both at the end of a run.
 */
@RunWith(AndroidJUnit4::class)
class TwoDeviceShareTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    /**
     * Where the container is staged, and **why it is not in `filesDir`**.
     *
     * AGP uninstalls the app after every instrumentation run, taking app-private
     * storage with it, so a file written by one invocation is gone before the next
     * one starts. `/data/local/tmp` survives that: the shell writes it, and the
     * directory's traversal bit lets an app read a mode-644 file inside it by
     * exact path. Nothing here is user content — it is a container the rig made.
     */
    private fun container() = File(STAGE, CONTAINER)

    /**
     * The identity this device must hold across two separate runs, pinned by the
     * rig for the same uninstall-wipes-everything reason.
     */
    private fun pinIdentity() {
        val hex = args.getString("identity.seed") ?: return
        val seed = ByteArray(hex.length / 2) {
            ((Character.digit(hex[it * 2], 16) shl 4) or Character.digit(hex[it * 2 + 1], 16)).toByte()
        }
        Identity.installForTest(ctx, seed)
    }

    /** The bytes A sends, derived rather than transferred — see the class comment. */
    private fun plaintext(): ByteArray = ByteArray(96_000).also { Random(SEED).nextBytes(it) }

    @Test
    fun sealForRecipient() {
        val payload = args.getString("recipient.payload")
        assumeTrue("no -Precipient.payload given; this is the A side of the rig", payload != null)

        // The rig carries the binary payload as hex.
        val bytes = ByteArray(payload!!.length / 2) {
            ((Character.digit(payload[it * 2], 16) shl 4) or Character.digit(payload[it * 2 + 1], 16)).toByte()
        }
        val parsed = Identity.PairingCode.parse(bytes)
        assertTrue(
            "the recipient's payload must be a pairing code",
            parsed is Identity.PairingCode.Parsed.Key,
        )
        val recipient = (parsed as Identity.PairingCode.Parsed.Key).publicKey

        val dir = File(ctx.filesDir, "two-device-test").apply { mkdirs() }
        val store = BlobStore(dir)
        val vmk = VaultKeys.newVmk()
        try {
            val bytes = plaintext()
            val written = store.write(ByteArrayInputStream(bytes), vmk)
            val item = VaultItem(
                blobIds = listOf(written.blobId),
                plaintextLength = written.plaintextLength,
                thumb = null,
                filename = "across the room.jpg",
                album = null,
                createdAt = null,
                importedAt = System.currentTimeMillis(),
                tags = emptyList(),
            )
            val sealed = ShareSend.sealToContact(
                ctx, store, item, vmk, ShareContainer.Policy.Unmanaged, recipient,
            )
            // Written where the script can reach it: an app cannot write
            // /data/local/tmp, so it goes to app storage and the script pulls it.
            val staged = File(ctx.filesDir, CONTAINER)
            sealed.copyTo(staged, overwrite = true)
            sealed.delete()
            android.util.Log.i(TAG, "SEALED ${staged.length()} bytes as ${sealed.name}")

            assertTrue(
                "what A handed over is named like any binary file",
                sealed.name.matches(Regex("[0-9a-f]{8}\\.bin")),
            )
        } finally {
            vmk.fill(0)
            dir.deleteRecursively()
        }
    }

    @Test
    fun openAsRecipient() {
        pinIdentity()
        assumeTrue("no container staged on this device", container().isFile)

        val dir = File(ctx.filesDir, "two-device-test").apply { mkdirs() }
        val store = BlobStore(dir)
        val vmk = VaultKeys.newVmk()
        try {
            assertTrue("this install has no identity to open with", Identity.seed(ctx) != null)
            assertEquals(
                "B's probe finds the file sealed here, so B is asked nothing",
                ShareContainer.Probe.SEALED_HERE,
                ShareOpen.probe(container(), Identity.seed(ctx)!!),
            )

            val outcome = ShareOpen.openWithIdentity(ctx, container(), Identity.seed(ctx)!!, store, vmk)
            val opened = outcome as ShareOpen.Outcome.Opened
            assertEquals("across the room.jpg", opened.details.displayName)

            val read = ByteArrayOutputStream()
            store.reader(listOf(opened.blobId), vmk, opened.plaintextLength).use { r ->
                val buf = ByteArray(BlobStore.CHUNK)
                for (i in 0 until r.chunkCount) read.write(buf, 0, r.readChunk(i, buf))
            }
            assertArrayEquals(
                "the bytes B decrypted are the bytes A encrypted, and no code was typed",
                plaintext(),
                read.toByteArray(),
            )
            android.util.Log.i(TAG, "OPENED ${opened.plaintextLength} bytes with this install's key")
        } finally {
            vmk.fill(0)
            dir.deleteRecursively()
        }
    }

    /** The other half of the claim: a third install holds a key that does not open it. */
    @Test
    fun aStrangerOnThisDeviceCannotOpenIt() {
        assumeTrue("no container staged on this device", container().isFile)

        val dir = File(ctx.filesDir, "two-device-stranger").apply { mkdirs() }
        val store = BlobStore(dir)
        val vmk = VaultKeys.newVmk()
        try {
            val stranger = ByteArray(XWing.SEED_BYTES).also { java.security.SecureRandom().nextBytes(it) }
            assertEquals(
                ShareContainer.Probe.NOT_HERE,
                ShareOpen.probe(container(), stranger.copyOf()),
            )
            try {
                ShareOpen.openWithIdentity(ctx, container(), stranger, store, vmk)
                throw AssertionError("a stranger's key must not open this container")
            } catch (_: ShareContainer.ShareFailure) {
            }
        } finally {
            vmk.fill(0)
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val TAG = "TwoDeviceShare"
        const val CONTAINER = "two-device.bin"
        const val STAGE = "/data/local/tmp"
        const val SEED = 20260905L
    }
}
