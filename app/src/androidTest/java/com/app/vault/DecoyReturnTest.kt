package com.app.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
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
 * You come back from a decoy — `decoy-flow-overhaul`.
 *
 * **This class exists because a suite of 886 tests reported green over a bug that
 * made first run enrol the wrong vault.** Building a decoy opens its keyring and
 * closes the one it was called from, and nothing brought the user back: the app sat
 * in the decoy, and onboarding's remaining steps — backup, panic credential, app
 * identity — all act on the open vault, so the backup would have mirrored the decoy
 * and the panic credential would have been set on it.
 *
 * Nothing caught it, and the reason is worth stating rather than fixing quietly:
 *
 *  - `DecoyTest` proves the arithmetic on the JVM, over thousands of sets.
 *  - `MultiVaultTest` proves the slot and keyring layer, driving `VaultLifecycle`.
 *  - `DecoyFlowScreenTest` proves what the screens *say*, rendering composables with
 *    stub callbacks.
 *
 * Three layers, and the defect was in the seam between them — the activity's own
 * wiring, which no test in the suite executes. `DecoyDeviceTest` looks like it
 * covers this and does not: its `runDecoyFlow` is a *copy* of the production
 * sequence written into the test, so it verifies its own copy. That copy had
 * already drifted, which is provable — adding `firstVaultName` to production
 * required hand-editing the helper to match, and the helper had been passing.
 *
 * So the round trip moved into `VaultLifecycle.reopen`, and this calls **the same
 * function the activity calls**. That is the whole design of this file: no
 * reimplementation, or it proves nothing again.
 */
@RunWith(AndroidJUnit4::class)
class DecoyReturnTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var slotsFile: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle

    private companion object {
        const val MINE = "the-owners-own-passphrase"
        const val DECOY = "the-decoy-passphrase"
        const val MY_VAULT = "Mine"
        const val DECOY_VAULT = "Holiday photos"
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        VaultLifecycle.lockNow()
        root = File(ctx.filesDir, "decoyreturn-${System.nanoTime()}").apply { mkdirs() }
        slotsFile = File(root, "vault.slots")
        store = BlobStore(File(root, "blobs"), File(root, "incoming"))
        vault = VaultLifecycle(slotsFile, store)
    }

    @After
    fun tearDown() {
        VaultLifecycle.lockNow()
        root.deleteRecursively()
    }

    /** The state the decoy flow is entered from: the owner's vault, open. */
    private fun openMyVault() {
        assertTrue(vault.unlock(pass(MINE)).opened)
        val opened = VaultKeyringSession.openVault(0)
        assertNotNull("the owner's vault must open", opened)
        vault.open(opened!!)
    }

    private fun createMine() {
        assertTrue(vault.create(pass(MINE), firstVaultName = MY_VAULT).opened)
        VaultLifecycle.lockNow()
    }

    /**
     * What the flow does between the authorisation sheet and the handover step,
     * driven through the production calls rather than around them.
     */
    private fun buildDecoy(): Int {
        val position = VaultKeyringSession.openVaultPosition()
        assertTrue(
            "the decoy must create",
            vault.create(pass(DECOY), firstVaultName = DECOY_VAULT).opened,
        )
        return position
    }

    // --- the bug, stated as an assertion --------------------------------------

    /**
     * The one that would have caught it: after the flow, the open vault is the one
     * that was open before, not the decoy.
     */
    @Test
    fun theOwnersVaultIsOpenAgainAfterBuildingADecoy() {
        createMine()
        openMyVault()
        val position = buildDecoy()

        // Mid-flow: the session really is in the decoy. Asserted, because if this
        // ever stops being true the test below would pass for the wrong reason.
        assertEquals(
            "the decoy is open while the flow runs",
            listOf(DECOY_VAULT),
            VaultKeyringSession.vaults().map { it.name },
        )

        val back = vault.reopen(pass(MINE), position)

        assertTrue("the owner's keyring must reopen", back.keyringOpen)
        assertNotNull("and the vault they were in must reopen with it", back.vault)
        assertEquals(
            "the vault list is the owner's again, not the decoy's",
            listOf(MY_VAULT),
            VaultKeyringSession.vaults().map { it.name },
        )
        assertEquals(
            "and the vault open is the one they left",
            position,
            VaultKeyringSession.openVaultPosition(),
        )
    }

    /**
     * The consequence, said as the thing that actually broke: whatever runs next
     * enrols against the owner's vault.
     *
     * First run's steps 6, 7 and 8 all act on the open vault. This asserts the
     * property they depend on — that after the decoy, the index reachable from the
     * session is the owner's — rather than asserting each step in turn.
     */
    @Test
    fun whateverRunsNextActsOnTheOwnersVaultAndNotTheDecoy() {
        createMine()
        openMyVault()

        // A marker only the owner's vault carries, so the two are told apart by
        // content and not by a name a test could have got right by luck.
        val mine = VaultSession.metadataBlobId()!!.copyOf()

        val position = buildDecoy()
        val decoy = VaultSession.metadataBlobId()!!.copyOf()
        assertFalse(
            "the decoy must have an index of its own, or this proves nothing",
            mine.contentEquals(decoy),
        )

        vault.reopen(pass(MINE), position)

        assertTrue(
            "the index the app would write to is the owner's — this is the assertion " +
                "the backup, panic and identity steps each depend on",
            mine.contentEquals(VaultSession.metadataBlobId()!!),
        )
    }

    /** The decoy is left properly, not merely navigated away from. */
    @Test
    fun theDecoysKeyIsGoneAfterTheReturn() {
        createMine()
        openMyVault()
        val position = buildDecoy()

        val decoyKey = VaultSession.vmk()!!
        val watched = decoyKey.copyOf()

        vault.reopen(pass(MINE), position)

        assertFalse("the decoy's key must not survive the return", decoyKey.contentEquals(watched))
        assertTrue("every byte of it is zero", decoyKey.all { it == 0.toByte() })
        assertEquals("and no decrypted pixels either", 0, ThumbnailCache.size())
    }

    /** A wrong passphrase leaves nothing open rather than leaving the decoy open. */
    @Test
    fun aFailedReturnLeavesNothingOpen() {
        createMine()
        openMyVault()
        val position = buildDecoy()

        val back = vault.reopen(pass("not-the-owners-passphrase"), position)

        assertFalse(back.keyringOpen)
        assertNull(back.vault)
        assertFalse(
            "leaving the decoy open would be the bug wearing a different hat",
            VaultKeyringSession.isOpen,
        )
        assertFalse(VaultSession.isUnlocked)
    }

    /**
     * Entered without a vault open — from the list rather than from inside a vault —
     * the return gives back the keyring and no vault, which is the list.
     */
    @Test
    fun aFlowStartedWithoutAnOpenVaultComesBackToTheList() {
        createMine()
        assertTrue(vault.unlock(pass(MINE)).opened)
        assertEquals(-1, VaultKeyringSession.openVaultPosition())

        val position = buildDecoy()
        assertEquals("nothing was open to come back to", -1, position)

        val back = vault.reopen(pass(MINE), position)

        assertTrue(back.keyringOpen)
        assertNull("no vault is opened, which is the vault list", back.vault)
        assertEquals(listOf(MY_VAULT), VaultKeyringSession.vaults().map { it.name })
    }

    /**
     * The count the first-run step used to read, asserted as the thing it cannot be.
     *
     * `DecoyStepScreen` decided whether a decoy existed from `vaultEntries.size > 1`.
     * A decoy is a second *slot* with a keyring of its own, so the open keyring's
     * count is 1 both before and after — the step redrew as untouched and read as a
     * failure, which is how this was found. Locked down here so the shortcut cannot
     * come back.
     */
    @Test
    fun theOpenKeyringsVaultCountIsNotADecoySignal() {
        createMine()
        openMyVault()
        assertEquals(1, VaultKeyringSession.vaults().size)

        val position = buildDecoy()
        assertEquals(
            "the decoy's keyring holds one vault, exactly as the owner's does",
            1,
            VaultKeyringSession.vaults().size,
        )

        vault.reopen(pass(MINE), position)
        assertEquals(
            "and one again afterwards — the count never changes, so it can never say " +
                "whether a decoy was built",
            1,
            VaultKeyringSession.vaults().size,
        )
    }
}
