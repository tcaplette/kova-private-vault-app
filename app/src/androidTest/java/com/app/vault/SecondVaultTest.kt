package com.app.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Creating a second vault beside an open one — the proof-of-open gate.
 *
 * `VaultLifecycle.create` refuses unless one of the supplied passphrases opens what is
 * already on screen, proved by a real derivation through `ExportAuth.confirms`
 * rather than a session flag. This reproduces that gate directly.
 */
@RunWith(AndroidJUnit4::class)
class SecondVaultTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var slotsFile: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle

    private companion object {
        const val FIRST = "the-first-vault-passphrase"
        const val SECOND = "the-second-vault-passphrase"
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    @Before fun setUp() {
        VaultLifecycle.lockNow()
        root = File(ctx.filesDir, "second-${System.nanoTime()}").apply { mkdirs() }
        slotsFile = File(root, "vault.slots")
        store = BlobStore(File(root, "blobs"), File(root, "incoming"))
        vault = VaultLifecycle(slotsFile, store)
    }

    @After fun tearDown() {
        VaultLifecycle.lockNow()
        root.deleteRecursively()
    }

    /** The exact state onboarding leaves behind: one vault, created and open. */
    @Test fun theFirstVaultsPassphraseProvesItIsOpen() {
        assertTrue("the first vault must create", vault.create(pass(FIRST)).opened)
        assertNotNull("onboarding leaves a vault open", VaultKeyringSession.openVault(0))

        assertTrue(
            "the passphrase that made this vault must prove it is open — this is the " +
                "check behind the decoy flow's authorisation sheet — it proves the user " +
                "owns what is on screen, and no longer stands in for preserving it",
            ExportAuth.confirms(slotsFile, pass(FIRST)),
        )
    }

    @Test fun aStrangerPassphraseDoesNotProveIt() {
        assertTrue(vault.create(pass(FIRST)).opened)
        assertNotNull(VaultKeyringSession.openVault(0))
        assertTrue(!ExportAuth.confirms(slotsFile, pass(SECOND)))
    }

    /** The whole operation the user is performing. */
    @Test fun aSecondVaultCanBeCreatedBesideTheFirst() {
        assertTrue(vault.create(pass(FIRST)).opened)
        assertNotNull(VaultKeyringSession.openVault(0))

        val outcome = vault.create(pass(SECOND), listOf(pass(FIRST)))
        assertTrue("a second vault must be creatable beside the first", outcome.opened)
    }
}
