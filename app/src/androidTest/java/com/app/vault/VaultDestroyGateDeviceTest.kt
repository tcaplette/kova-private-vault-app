package com.app.vault

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The destroy dialog's gate, driven through the real activity — `protected-vault-destroy-gate`.
 *
 * Every case makes a keyring at `filesDir/vault.slots`, so the suite is gated as every such suite
 * is:
 *
 *   -Pandroid.testInstrumentationRunnerArguments.destructive=true
 *
 * Test-only state, removed after each case: a keyring opened by [GOOD] holding "My vault", "Papers"
 * with its own passphrase [OWN], and "Trip" with one photo; and the photo "Trip" returns to the
 * gallery, deleted again at close.
 */
@RunWith(AndroidJUnit4::class)
class VaultDestroyGateDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val slots = File(ctx.filesDir, "vault.slots")
    private val blobsDir = File(ctx.filesDir, "blobs")
    private val incoming = File(ctx.filesDir, "incoming")
    private val store = BlobStore(blobsDir, incoming)
    private val lifecycle = VaultLifecycle(slots, store)

    private var papers = -1
    private var trip = -1
    private lateinit var papersBlobs: List<ByteArray>

    private companion object {
        const val GOOD = "correct horse battery staple"
        const val OWN = "lantern quarry obsidian meadow"
        const val PHOTO = "trip-gate-test.jpg"
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    @Before fun setUp() {
        assumeTrue(
            "gated: pass -Pandroid.testInstrumentationRunnerArguments.destructive=true to run it.",
            InstrumentationRegistry.getArguments().getString("destructive") == "true",
        )
        lockKeyringAndVault()
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        assertTrue(lifecycle.create(pass(GOOD)).opened)
        VaultKeyringSession.leaveVault()

        papers = VaultKeyringSession.createVault(store, "Papers") { p, set ->
            VaultKeyringSession.entryPassphraseKek(pass(OWN), p, set)!!
        }!!
        val kek = VaultKeyringSession.entryPassphraseKek(pass(OWN), papers, VaultKeys.RELEASED_KDF_PARAMS.lastIndex)!!
        assertNotNull(VaultKeyringSession.openVaultWithPassphrase(papers, kek))
        kek.fill(0)
        papersBlobs = putItems(store, listOf(ByteArray(3_000) { 5 })).flatMap { it.blobIds } +
            listOf(VaultSession.metadataBlobId()!!.copyOf())
        VaultKeyringSession.leaveVault()

        trip = VaultKeyringSession.createVault(store, "Trip")!!
        assertNotNull(VaultKeyringSession.openVault(trip))
        val photo = capturePhotoBytes()
        val written = store.write(ByteArrayInputStream(photo), VaultSession.vmk()!!)
        MetadataDb.commit(store, VaultSession.metadataBlobId()!!, VaultSession.vmk()!!) { db ->
            db.items += VaultItem(listOf(written.blobId), written.plaintextLength, null, PHOTO, null, null, 0L, emptyList())
        }
        lockKeyringAndVault()
    }

    @After fun tearDown() {
        lockKeyringAndVault()
        MetadataDb.awaitQuiescent()
        val gallery = MediaStoreDestination(ctx)
        gallery.find(PHOTO)?.let { gallery.discard(it) }
        gallery.find(ExportManifest.NAME)?.let { gallery.discard(it) }
        gallery.sweep()
        slots.delete()
        blobsDir.deleteRecursively()
        incoming.deleteRecursively()
    }

    // --- driving -----------------------------------------------------------------------------

    private fun await(text: String, timeout: Long = 30_000) = compose.waitUntil(timeout) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** In the dialog only — the list's closed drawer is composed too, and carries a row of the same words. */
    private fun inDialog(text: String) =
        compose.onAllNodes(hasText(text, substring = true) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()

    private fun unlocked(block: (ActivityScenario<MainActivity>) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await("Welcome back", 45_000)
            val field = compose.onNode(hasText("Passphrase") and hasSetTextAction())
            field.performClick()
            field.performTextInput(GOOD)
            compose.onNode(hasText("Unlock") and hasClickAction()).performClick()
            block(scenario)
        }
    }

    private fun remove(name: String) {
        val options = hasContentDescription("Options for $name")
        compose.waitUntil(45_000) { compose.onAllNodes(options).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(options).performClick()
        val remove = hasText("Remove") and hasClickAction()
        compose.waitUntil(15_000) { compose.onAllNodes(remove).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(remove).performClick()
        await("Destroy “$name”?")
    }

    private fun gate(label: String, passphrase: String) {
        compose.onNode(hasText(label) and hasSetTextAction()).performTextReplacement(passphrase)
        compose.onNode(hasText("Continue") and hasClickAction()).performClick()
    }

    /** The key the activity holds between a passed gate and a choice — D4's subject. */
    private fun heldKey(scenario: ActivityScenario<MainActivity>): ByteArray? {
        var key: ByteArray? = null
        scenario.onActivity { activity ->
            key = MainActivity::class.java.getDeclaredField("destroyKey").apply { isAccessible = true }.get(activity) as ByteArray?
        }
        return key
    }

    private fun listed(position: Int) = VaultKeyringSession.vaults().any { it.position == position }

    // --- the cases -----------------------------------------------------------------------------

    @Test fun aProtectedVaultRefusesTheListsPassphraseAndGoesWithItsOwn() = unlocked {
        remove("Papers")
        assertTrue("a protected vault from the list says how to get back in", inDialog("Recover a vault with its kit"))
        gate("Passphrase", GOOD)
        await("That passphrase did not open Papers.")
        assertFalse("the outcomes are not offered", shown("Destroy everything in it"))
        assertTrue("nothing changed", listed(papers))

        gate("Passphrase", OWN)
        await("Destroy everything in it")
        compose.onNode(hasText("Destroy everything in it") and hasClickAction()).performClick()
        compose.waitUntil(30_000) { !listed(papers) }

        papersBlobs.forEach { assertFalse("its blobs and index are deleted", store.fileOf(it).exists()) }
        assertTrue("the others stay", listed(0) && listed(trip))
    }

    @Test fun giveMyFilesBackFromTheListOpensTheTargetExportsItAndDestroysOnlyIt() = unlocked {
        remove("Trip")
        assertFalse("the lost line is the protected vault's", inDialog("Recover a vault with its kit"))
        gate("Vault list passphrase", GOOD)
        await("Give my files back, then destroy")
        compose.onNode(hasText("Give my files back, then destroy") and hasClickAction()).performClick()

        await("When every file is out and checked, Trip is destroyed.")
        assertEquals("the target is the open vault", trip, VaultKeyringSession.openVaultPosition())
        assertFalse("no passphrase is asked for again", shown("Enter your passphrase"))
        assertFalse("no archive is offered", shown("Save encrypted archive"))
        compose.onNode(hasText("Unvault Everything") and hasClickAction()).performScrollTo().performClick()

        compose.waitUntil(60_000) { !listed(trip) }
        assertNotNull("its photo came out", MediaStoreDestination(ctx).find(PHOTO))
        assertTrue("the others stay", listed(0) && listed(papers))
    }

    @Test fun dismissingWhileTheCheckRunsHoldsNoKey() = unlocked { scenario ->
        remove("My vault")
        gate("Vault list passphrase", GOOD)
        compose.onNode(hasText("Keep vault") and hasClickAction()).performClick()
        // Long enough for the derivation to land after the dialog closed. Waited through the
        // rule rather than slept, which would freeze the Compose clock.
        val until = android.os.SystemClock.elapsedRealtime() + 3_000
        compose.waitUntil(10_000) { android.os.SystemClock.elapsedRealtime() > until }

        assertNull("no key is held", heldKey(scenario))
        assertFalse("the dialog is gone", shown("Destroy “My vault”?"))
        assertTrue("the vault is unchanged", listed(0))
    }

    @Test fun aLockAtTheChoiceClosesTheDialog() = unlocked { scenario ->
        remove("My vault")
        gate("Vault list passphrase", GOOD)
        await("Destroy everything in it")
        assertNotNull("the gate's key is held for the choice", heldKey(scenario))

        scenario.onActivity { activity ->
            MainActivity::class.java.getDeclaredMethod("lockVault").apply { isAccessible = true }.invoke(activity)
        }
        await("Welcome back")
        assertFalse("the dialog went with the lock", shown("Destroy everything in it"))
        assertNull("and so did the key", heldKey(scenario))
    }
}
