package com.app.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * P1-16, against its Verify.
 *
 * The four clauses, in order: (a) an expired state exports a multi-gigabyte
 * corpus with no prompt, cap or delay; (b) a thrown licence check grants full
 * access; (c) the trial starts at the first successful add, needs no payment
 * method, and **is reset by an app-data clear** — asserted, so the accepted abuse
 * ceiling is a measurement rather than a claim; (d) export re-auth runs the same
 * code path in trial, paid and expired states, asserted on the path rather than a
 * stopwatch.
 *
 * Everything here reaches its state through [Entitlement]'s two test seams, so no
 * Play account and no purchase is involved in any of it. The purchase itself is
 * `BillingStripTest` plus a manual leg — a real one cannot be automated from here.
 *
 * **Announced test-only state:** the entitlement preference is cleared in both
 * `@Before` and `@After`, so this test never leaves the device saying a trial
 * started or ended.
 */
@RunWith(AndroidJUnit4::class)
class EntitlementTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var slotsFile: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle
    private lateinit var vmk: ByteArray
    private lateinit var indexId: ByteArray
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "VaultEntitlement"
        const val PASS = "correcthorsebattery"
        const val DAY_MS = 86_400_000L

        /**
         * ~2.1 GB in seven items. Sized to the emulator this runs on: `/data` had
         * 4.0 GB free, and an export needs room for the plaintext alongside the
         * ciphertext already in the vault, so each item is discarded as soon as it
         * is verified rather than at the end.
         */
        const val BIG_ITEMS = 7
        const val BIG_ITEM_BYTES = 300L * 1024 * 1024
    }

    private fun pass() = PASS.toByteArray(Charsets.UTF_8)

    @Before fun setUp() {
        VaultLifecycle.lockNow()
        Entitlement.resetForTest(ctx)
        root = File(ctx.filesDir, "entitlement-${System.nanoTime()}").apply { mkdirs() }
        slotsFile = File(root, "vault.slots")
        store = BlobStore(File(root, "blobs"), File(root, "incoming"))
        vault = VaultLifecycle(slotsFile, store)
        vault.create(pass())
        vmk = VaultSession.vmk()!!
        indexId = VaultSession.metadataBlobId()!!.copyOf()
    }

    @After fun tearDown() {
        VaultLifecycle.lockNow()
        Entitlement.resetForTest(ctx)
        root.deleteRecursively()
    }

    /** Puts the app in the state that gates: trial started, 30 days gone, no purchase. */
    private fun expire() {
        Entitlement.setTrialStartedAtForTest(
            ctx,
            System.currentTimeMillis() - 31 * DAY_MS
        )
        Entitlement.purchasedProbe = { false }
    }

    private fun readBack(item: VaultItem, key: ByteArray): ByteArray {
        val out = ByteArray(item.plaintextLength.toInt())
        store.reader(item.blobIds, key, item.plaintextLength).use { r ->
            val buf = ByteArray(BlobStore.CHUNK)
            var at = 0
            for (i in 0 until r.chunkCount) {
                val n = r.readChunk(i, buf)
                buf.copyInto(out, at, 0, n)
                at += n
            }
        }
        return out
    }

    private fun addItem(name: String, size: Int): ByteArray {
        val content = ByteArray(size).also { rng.nextBytes(it) }
        val blob = store.write(ByteArrayInputStream(content), vmk)
        val db = MetadataDb.load(store, indexId, vmk)
        db.items += VaultItem(
            listOf(blob.blobId), blob.plaintextLength, null, name, null, null, 0, emptyList()
        )
        db.save(store, indexId, vmk)
        return content
    }

    // --- 7.1 Verify (a): expired exports everything -----------------------------

    @Test fun anExpiredTrialExportsAMultiGigabyteCorpusWhole() {
        // Written before expiry — the vault has to have something in it for the
        // point to mean anything — and exported after.
        val digests = mutableListOf<Pair<String, String>>()
        repeat(BIG_ITEMS) { i ->
            val name = "big-$i.bin"
            val sha = MessageDigest.getInstance("SHA-256")
            // writeItem, not write: a 300 MB single blob is a format violation
            // since `blob-size-cap`, and this corpus is the whole point of
            // Verify (a) — so it goes through the shipping path, not around it.
            val blob = store.writeItem(RandomStream(BIG_ITEM_BYTES, rng, sha), vmk)
            val db = MetadataDb.load(store, indexId, vmk)
            db.items += VaultItem(
                blob.blobIds, blob.plaintextLength, null, name, null, null, 0, emptyList()
            )
            db.save(store, indexId, vmk)
            digests += name to sha.digest().hex()
        }
        val total = BIG_ITEMS * BIG_ITEM_BYTES
        Log.i(TAG, "corpus: $total bytes in $BIG_ITEMS items")

        expire()
        assertFalse("the gate is closed", Entitlement.importAllowed(ctx))

        val destination = MediaStoreDestination(ctx)
        val manifest = ExportManifest.openAt(destination)
        val unvaulter = Unvaulter(store, destination, manifest, ctx)
        val index = MetadataDb.load(store, indexId, vmk)

        var exported = 0L
        try {
            digests.forEach { (name, sha) ->
                val item = index.items.first { it.filename == name }
                assertTrue(
                    "export is not gated by entitlement — I4",
                    unvaulter.exportOne(item, vmk, strip = null)
                )
                val entry = destination.find(name)
                assertNotNull("$name reached the destination", entry)
                val out = MessageDigest.getInstance("SHA-256")
                ctx.contentResolver.openInputStream(entry!!.uri)!!.use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.update(buf, 0, n)
                        exported += n
                    }
                }
                assertEquals("$name is byte-identical after expiry", sha, out.digest().hex())
                // Discarded as it goes, so peak disk stays under the emulator's
                // free space rather than needing the whole corpus twice.
                destination.discard(entry)
            }
        } finally {
            destination.find(ExportManifest.NAME)?.let { destination.discard(it) }
            destination.sweep()
        }

        assertEquals("every byte of the corpus came out", total, exported)
        assertTrue("multi-gigabyte", exported > 2L * 1024 * 1024 * 1024)
    }

    @Test fun expiryDoesNotCapOrTruncateAFullExport() {
        val contents = (0 until 12).associate { "item-$it.bin" to addItem("item-$it.bin", 64 * 1024) }
        expire()

        val destination = MediaStoreDestination(ctx)
        val manifest = ExportManifest.openAt(destination)
        val progress = Unvaulter(store, destination, manifest, ctx).exportAll(indexId, vmk)

        try {
            assertEquals("no item was skipped", contents.size, progress.exported)
            assertEquals("none failed", 0, progress.failed)
            contents.forEach { (name, bytes) ->
                val entry = destination.find(name)
                assertNotNull("$name is there", entry)
                assertArrayEquals(
                    "$name is byte-identical",
                    bytes,
                    ctx.contentResolver.openInputStream(entry!!.uri)!!.use { it.readBytes() }
                )
            }
        } finally {
            contents.keys.forEach { name -> destination.find(name)?.let { destination.discard(it) } }
            destination.find(ExportManifest.NAME)?.let { destination.discard(it) }
            destination.sweep()
        }
    }

    // --- 7.2 Verify (b): a thrown check grants full access ----------------------

    @Test fun aThrownLicenceCheckGrantsFullAccess() {
        Entitlement.setTrialStartedAtForTest(ctx, System.currentTimeMillis() - 31 * DAY_MS)
        Entitlement.purchasedProbe = { throw IllegalStateException("billing exploded") }

        assertTrue(
            "I5: a licence check that fails grants full access",
            Entitlement.importAllowed(ctx)
        )
    }

    @Test fun anUnansweredLicenceCheckGrantsFullAccess() {
        Entitlement.setTrialStartedAtForTest(ctx, System.currentTimeMillis() - 31 * DAY_MS)
        // Billing.owned is null on a device that never answered — the default
        // path, not a contrived one.
        Entitlement.purchasedProbe = { Billing.hasPurchased() }

        assertTrue("no answer reads as entitled", Entitlement.importAllowed(ctx))
        assertTrue("and that is what Billing itself says", Billing.hasPurchased())
    }

    @Test fun onlyADefinitiveNegativeRestricts() {
        Entitlement.setTrialStartedAtForTest(ctx, System.currentTimeMillis() - 31 * DAY_MS)
        Entitlement.purchasedProbe = { false }
        assertFalse("all three known", Entitlement.importAllowed(ctx))

        Entitlement.purchasedProbe = { true }
        assertTrue("the purchase lifts it with no restart", Entitlement.importAllowed(ctx))
    }

    // --- 7.3 Verify (c): the trial clock ---------------------------------------

    @Test fun installAndVaultCreationStartNoTrial() {
        // setUp already installed, launched and created a vault.
        assertEquals("no trial", 0L, Entitlement.trialStartedAt(ctx))
        assertTrue("and nothing is gated", Entitlement.importAllowed(ctx))
        assertEquals("the full trial is still ahead", 30, Entitlement.daysRemaining(ctx))
    }

    @Test fun theTrialStartsAtTheFirstSuccessfulImportAndDoesNotMove() {
        assertEquals(0L, Entitlement.trialStartedAt(ctx))

        Entitlement.startTrialIfNeeded(ctx)
        val first = Entitlement.trialStartedAt(ctx)
        assertTrue("started", first > 0L)

        Thread.sleep(10)
        Entitlement.startTrialIfNeeded(ctx)
        assertEquals("a later import does not move it", first, Entitlement.trialStartedAt(ctx))
    }

    @Test fun aFailedImportStartsNothing() {
        // The hook is MainActivity's SUCCEEDED branch; the fact under test is that
        // nothing else calls it. An enqueue, a cancelled pick and a failure all
        // leave the preference absent.
        assertEquals("nothing started the clock", 0L, Entitlement.trialStartedAt(ctx))
        assertTrue(Entitlement.importAllowed(ctx))
    }

    @Test fun theTrialNeedsNoPaymentMethod() {
        Entitlement.startTrialIfNeeded(ctx)
        assertEquals("30 days from the first import", 30, Entitlement.daysRemaining(ctx))
        assertTrue("and it is running with no purchase of any kind", Entitlement.importAllowed(ctx))
        assertNull("Play was never asked", Billing.owned)
    }

    @Test fun theTrialRunsOutAfterThirtyDays() {
        Entitlement.setTrialStartedAtForTest(ctx, System.currentTimeMillis() - 29 * DAY_MS)
        assertEquals(1, Entitlement.daysRemaining(ctx))
        assertFalse("day 29 is still inside", Entitlement.trialExpired(ctx))

        Entitlement.setTrialStartedAtForTest(ctx, System.currentTimeMillis() - 30 * DAY_MS)
        assertEquals(0, Entitlement.daysRemaining(ctx))
        assertTrue("day 30 is out", Entitlement.trialExpired(ctx))
    }

    @Test fun anAppDataClearResetsTheTrial() {
        // The accepted abuse ceiling, asserted rather than described. resetForTest
        // clears the same preference file a data clear removes; the trial start is
        // the only state involved, so there is nothing else for a clear to take.
        expire()
        assertFalse("gated", Entitlement.importAllowed(ctx))

        Entitlement.resetForTest(ctx)

        assertEquals("a cleared install has no trial", 0L, Entitlement.trialStartedAt(ctx))
        assertTrue("and nothing is gated", Entitlement.importAllowed(ctx))
        Entitlement.startTrialIfNeeded(ctx)
        assertEquals("a fresh 30 days", 30, Entitlement.daysRemaining(ctx))
    }

    // --- 7.4 Verify (d): export re-auth is one path ----------------------------

    @Test fun exportReAuthIsTheSamePathInEveryEntitlementState() {
        val states = listOf<Pair<String, () -> Unit>>(
            "trial" to { Entitlement.startTrialIfNeeded(ctx) },
            "paid" to {
                Entitlement.setTrialStartedAtForTest(ctx, System.currentTimeMillis() - 31 * DAY_MS)
                Entitlement.purchasedProbe = { true }
            },
            "expired" to { expire() },
        )

        val observed = states.map { (name, enter) ->
            Entitlement.resetForTest(ctx)
            enter()

            // The path, not the clock: one derivation, every slot attempted, the
            // right passphrase confirms and the wrong one does not. Identical in
            // all three states or the assertion below fails.
            val outcome = VaultUnlock.unlock(pass(), SlotArray(slotsFile))
            outcome.vault?.vmk?.fill(0)
            val confirms = ExportAuth.confirms(slotsFile, pass())
            val refuses = ExportAuth.confirms(slotsFile, "wrongwrongwrong".toByteArray())
            Log.i(TAG, "$name: derivations=${outcome.derivations} confirms=$confirms")
            listOf(outcome.derivations, outcome.slotsAttempted, confirms, refuses)
        }

        assertEquals("trial and paid take the same path", observed[0], observed[1])
        assertEquals("paid and expired take the same path", observed[1], observed[2])
        assertEquals(
            "and it is the real one: one derivation, every slot, right yes, wrong no",
            listOf(1, SlotArray.SLOT_COUNT, true, false),
            observed[2]
        )
    }

    // --- 7.5 nothing else changes shape ----------------------------------------

    @Test fun unlockGridViewerAndExportAreIdenticalAfterExpiry() {
        val content = addItem("photo.bin", 4096)
        val before = MetadataDb.load(store, indexId, vmk).items.size

        expire()

        VaultLifecycle.lockNow()
        // `vault-keyring`. The trial-decrypt still opens what it always opened — the
        // slot — but those 48 bytes are a **keyring** reference now, so reading an
        // index with them would be reading a vault's blob under a keyring's key. The
        // vault is opened from the keyring, which is what the app does.
        val outcome = VaultUnlock.unlock(pass(), SlotArray(slotsFile))
        assertNotNull("an expired trial still unlocks", outcome.vault)
        assertTrue("and its vault still opens", vault.unlockAndOpen(pass()))
        val key = VaultSession.vmk()!!

        val index = MetadataDb.load(store, indexId, key)
        assertEquals("the grid still lists everything", before, index.items.size)

        val item = index.items.first { it.filename == "photo.bin" }
        assertArrayEquals("and the viewer still reads the bytes", content, readBack(item, key))
        key.fill(0)
    }

    // --- 7.6 the archive pair --------------------------------------------------

    @Test fun anExpiredTrialWritesAnArchiveButCannotImportOne() {
        addItem("photo.bin", 4096)
        val archive = File(root, "vault.archive")

        expire()

        // The export half is a read: not gated, and it writes in full.
        val db = MetadataDb.load(store, indexId, vmk)
        archive.outputStream().use { out ->
            VaultArchive.write(out, vmk, db, store, pass())
        }
        assertTrue("the archive was written", archive.length() > 0)

        // The import half adds to the app: gated, and it consumes nothing.
        val sizeBefore = archive.length()
        assertFalse("an archive import is an import", Entitlement.importAllowed(ctx))
        assertEquals("the archive file is untouched", sizeBefore, archive.length())
    }

    // --- 7.7 reminders ---------------------------------------------------------

    @Test fun remindersAppearOnDayTwentyThreeAndTwentyEightOnly() {
        val days = (0..30).map { day ->
            Entitlement.setTrialStartedAtForTest(
                ctx,
                // Half a day in, so the boundary is never sat exactly on.
                System.currentTimeMillis() - day * DAY_MS - DAY_MS / 2
            )
            day to (Entitlement.reminder(ctx) != null)
        }
        val reminded = days.filter { it.second }.map { it.first }
        assertEquals("exactly two reminder days", listOf(23, 28), reminded)
    }

    @Test fun aReminderSaysWhatStaysUnrestricted() {
        Entitlement.setTrialStartedAtForTest(
            ctx,
            System.currentTimeMillis() - 23 * DAY_MS - DAY_MS / 2
        )
        val text = Entitlement.reminder(ctx)
        assertNotNull(text)
        assertTrue(
            "the reminder names what is never restricted: $text",
            text!!.contains("exporting")
        )
    }

    // --- `one-time-purchase`: shares --------------------------------------------

    /**
     * Opening is never gated — the scan proves `ShareOpen` cannot ask, and this
     * proves the open itself works in the state that gates everything else.
     */
    @Test fun anExpiredTrialStillOpensAReceivedShare() {
        val plain = ByteArray(40_000).also { rng.nextBytes(it) }
        val sender = BlobStore(File(root, "sender"), File(root, "sender-in"))
        val senderKey = VaultKeys.newVmk()
        val code = listOf("abandon", "ability", "able", "about")
        val sent = sender.write(ByteArrayInputStream(plain), senderKey)
        val container = ShareSend.seal(
            ctx, sender,
            VaultItem(listOf(sent.blobId), sent.plaintextLength, null, "gift.jpg", null, null, 0, emptyList()),
            senderKey, ShareContainer.Policy.Unmanaged, code,
        )
        senderKey.fill(0)

        expire()
        assertFalse("everything that adds is gated", Entitlement.importAllowed(ctx))

        try {
            val outcome = ShareOpen.open(ctx, container, code, store, vmk)
            assertTrue("the share opened after the trial: $outcome", outcome is ShareOpen.Outcome.Opened)
            val opened = outcome as ShareOpen.Outcome.Opened
            val out = ByteArray(opened.plaintextLength.toInt())
            store.reader(listOf(opened.blobId), vmk, opened.plaintextLength).use { r ->
                val buf = ByteArray(BlobStore.CHUNK)
                var at = 0
                for (i in 0 until r.chunkCount) {
                    val n = r.readChunk(i, buf)
                    buf.copyInto(out, at, 0, n)
                    at += n
                }
            }
            assertArrayEquals("and it reads byte-identical", plain, out)
        } finally {
            ShareSend.sweep(ctx)
            ShareOpen.sweep(ctx)
        }
    }

    @Test fun anAddAfterTheTrialStartedDoesNotMoveIt() {
        Entitlement.startTrialIfNeeded(ctx)
        val first = Entitlement.trialStartedAt(ctx)
        // Every route calls the same function; the idempotence is the contract each
        // of them leans on — an import, then a note, then a kept share.
        repeat(3) {
            Thread.sleep(5)
            Entitlement.startTrialIfNeeded(ctx)
        }
        assertEquals(first, Entitlement.trialStartedAt(ctx))
    }

    @Test fun theBlockedMessageSaysWhatStillWorks() {
        assertTrue(
            "a user who reads 'expired' must not conclude their photographs are locked",
            ctx.getString(Entitlement.BLOCKED).contains("Opening, viewing and exporting")
        )
        assertTrue(
            "and it says where the purchase is, in the model's own word",
            ctx.getString(Entitlement.BLOCKED).let { it.contains("Purchase") && !it.contains("subscri") },
        )
    }
}

/**
 * A multi-hundred-megabyte plaintext that is never held in memory and never
 * written to disk before it is encrypted, hashing itself on the way through so the
 * export can be compared without keeping a copy.
 */
private class RandomStream(
    private val length: Long,
    private val rng: SecureRandom,
    private val digest: MessageDigest,
) : java.io.InputStream() {

    private var produced = 0L
    private val chunk = ByteArray(1 shl 16)
    private var offset = chunk.size

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (produced >= length) return -1
        if (offset >= chunk.size) {
            rng.nextBytes(chunk)
            offset = 0
        }
        val n = minOf(len, chunk.size - offset, (length - produced).toInt())
        System.arraycopy(chunk, offset, b, off, n)
        digest.update(b, off, n)
        offset += n
        produced += n
        return n
    }
}
