package com.app.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val roots = mutableListOf<File>()

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
