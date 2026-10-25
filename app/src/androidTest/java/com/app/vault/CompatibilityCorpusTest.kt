package com.app.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **The test that makes P2-25's other two rules mean anything** — it opens bytes a
 * release actually wrote.
 *
 * Every other compatibility check in this repo round-trips the current writer
 * against the current reader, which proves the two agree with each other and
 * nothing about whether either agrees with last year. This one reads ciphertext
 * committed by an earlier build and asserts the plaintext comes back.
 *
 * One corpus per released build, under `androidTest/assets/fixtures/<version>/`,
 * added to and **never edited**. Editing a committed corpus turns this back into a
 * round-trip test without changing a line of it, which is the failure mode the
 * whole mechanism exists to prevent. If a change makes a committed corpus
 * unreadable, the change is wrong — not the corpus.
 *
 * The corpus holds **ciphertext only** (I3): the plaintext every assertion below
 * compares against is recomputed from [CorpusFixture], not stored beside it.
 *
 * Chaff is deliberately absent — it is CSPRNG carrying no format, and 48 install
 * blobs spanning the bucket ladder came to ~200 MB. What is here is every structure
 * that *is* format: the slot file, the index, the item blobs at and around a chunk
 * boundary, a thumbnail pack, the tamper log and an archive.
 */
@RunWith(AndroidJUnit4::class)
class CompatibilityCorpusTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The corpus is committed under `androidTest/`, so it is packaged into the
     * **test** APK and reachable only through the instrumentation context. Reading
     * it from [ctx] finds the app under test's assets, which do not contain it.
     */
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private lateinit var dir: File
    private lateinit var store: BlobStore

    private val pass get() = CorpusFixture.PASSPHRASE.toByteArray(Charsets.UTF_8)

    /**
     * Assets are read-only and the store wants a directory, so the corpus is copied
     * out byte-for-byte per test. Copied and not linked: a test that could write
     * back into the corpus is a test that could repair it into passing.
     */
    @Before fun setUp() {
        dir = File(ctx.cacheDir, "corpus-${CorpusFixture.VERSION}")
            .also { it.deleteRecursively(); it.mkdirs() }
        copyAsset("fixtures/${CorpusFixture.VERSION}/vault.slots", File(dir, "vault.slots"))
        File(dir, "blobs").mkdirs()
        assets.list("fixtures/${CorpusFixture.VERSION}/blobs").orEmpty().forEach {
            copyAsset("fixtures/${CorpusFixture.VERSION}/blobs/$it", File(dir, "blobs/$it"))
        }
        copyAsset("fixtures/${CorpusFixture.VERSION}/vault.vault", File(dir, "vault.vault"))
        store = BlobStore(File(dir, "blobs"), File(dir, "incoming"))
    }

    @After fun tearDown() {
        // `vault-keyring`. **Both levels.** The archive test now leaves a keyring
        // open as well as a vault, and `VaultSession.lock()` alone clears only the
        // vault — leaving the keyring key and every decrypted vault name in memory
        // for whichever test runs next. `lockNow` is the lock that clears both, and
        // its own KDoc says there is deliberately no lock that clears one.
        VaultLifecycle.lockNow()
        dir.deleteRecursively()
    }

    private fun copyAsset(path: String, to: File) {
        to.parentFile?.mkdirs()
        assets.open(path).use { input -> to.outputStream().use { input.copyTo(it) } }
    }

    private fun readBack(item: VaultItem, vmk: ByteArray): ByteArray {
        val out = ByteArray(item.plaintextLength.toInt())
        store.reader(item.blobIds, vmk, item.plaintextLength).use { r ->
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

    // --- the slot file ---------------------------------------------------------

    /**
     * The corpus was written before the running build existed. Its recorded KDF
     * parameters — not this build's constant — are what must open it, which is the
     * single failure P2-25 was written to prevent.
     */
    @Test fun theSlotFileUnwrapsUnderItsOwnRecordedParameters() {
        val slots = SlotArray(File(dir, "vault.slots"))

        assertEquals(SlotArray.FILE_BYTES.toLong(), File(dir, "vault.slots").length())
        Log.i(TAG, "corpus KDF params: ${slots.kdfParams()}, this build: ${VaultKeys.ARGON2ID_PARAMS}")

        val outcome = VaultUnlock.unlock(pass, slots)
        assertTrue("the corpus vault did not open", outcome.opened)
        assertEquals("one derivation, as always", 1, outcome.derivations)
        assertEquals("every slot attempted, as always", SlotArray.SLOT_COUNT, outcome.slotsAttempted)
    }

    // --- the index and the blobs -----------------------------------------------

    @Test fun everyItemInTheCorpusComesBackByteIdentical() {
        val slots = SlotArray(File(dir, "vault.slots"))
        val vault = VaultUnlock.unlock(pass, slots).vault!!
        val db = MetadataDb.load(store, vault.metadataBlobId, vault.vmk)

        assertEquals(
            "the corpus holds one row per item, plus the image and the note",
            CorpusFixture.ITEMS.size + 2,
            db.items.size,
        )

        CorpusFixture.ITEMS.forEach { (name, size) ->
            val item = db.items.first { it.filename == name }
            assertEquals("$name length", size.toLong(), item.plaintextLength)
            assertArrayEquals(
                "$name must be byte-identical to what the corpus build wrote",
                CorpusFixture.content(name, size),
                readBack(item, vault.vmk),
            )
        }
    }

    /** The index's own fields, which are format rather than content. */
    @Test fun theIndexRowsSurviveWithEveryFieldIntact() {
        val slots = SlotArray(File(dir, "vault.slots"))
        val vault = VaultUnlock.unlock(pass, slots).vault!!
        val db = MetadataDb.load(store, vault.metadataBlobId, vault.vmk)

        val item = db.items.first { it.filename == CorpusFixture.ITEMS.first().first }
        assertEquals("Corpus", item.album)
        assertEquals(CorpusFixture.CREATED_AT, item.createdAt)
        assertEquals(CorpusFixture.IMPORTED_AT, item.importedAt)
        assertEquals(listOf("fixture"), item.tags)

        val note = db.items.first { it.kind == VaultKind.NOTE }
        assertEquals(CorpusFixture.NOTE_BODY, note.body)
        assertEquals("${CorpusFixture.NOTE_TITLE}.txt", note.filename)
        assertTrue("a note claims no blob", note.blobIds.isEmpty())
        assertNotNull("a note carries its own identity", note.noteId)
    }

    @Test fun theThumbnailPackAndTamperLogInTheCorpusStillOpen() {
        val slots = SlotArray(File(dir, "vault.slots"))
        val vault = VaultUnlock.unlock(pass, slots).vault!!
        val db = MetadataDb.load(store, vault.metadataBlobId, vault.vmk)

        assertTrue("the corpus carries a thumbnail pack", db.thumbPacks.isNotEmpty())
        val refs = db.items.mapNotNull { it.thumb }
        assertTrue("the corpus image must carry a ThumbRef", refs.isNotEmpty())
        val thumbs = ThumbnailStore(store)
        refs.forEach {
            assertTrue(
                "a thumbnail record read back empty",
                thumbs.read(db, it, vault.vmk).isNotEmpty(),
            )
        }

        val logId = db.logBlobId
        assertNotNull("the corpus carries a tamper log", logId)
        val scan = TamperLog.scan(store, logId!!, vault.vmk)
        assertTrue("the log must hold entries", scan.entries.isNotEmpty())
        // §9 finds the end of the log by reading until an entry fails to
        // authenticate — the blob is CSPRNG past the last write — so a non-null
        // `firstAuthFailure` at the append point is the log working, not damage.
        // What must hold is that the chain up to that point is unbroken.
        assertTrue("the chain must be unbroken", !scan.tampered)
        assertEquals(
            "the append point must sit exactly after the authenticated entries",
            scan.entries.size,
            scan.appendIndex,
        )
        Log.i(
            TAG,
            "corpus log: ${scan.entries.size} entries, chain intact, append at ${scan.appendIndex}",
        )
    }

    // --- the archive -----------------------------------------------------------

    /**
     * The artefact a later release can never reach — it is already in someone's
     * cloud folder — so it is the one whose compatibility matters most.
     */
    @Test fun theArchiveInTheCorpusImportsAndVerifies() {
        val into = File(ctx.cacheDir, "corpus-import").also { it.deleteRecursively(); it.mkdirs() }
        val target = BlobStore(File(into, "blobs"), File(into, "incoming"))
        val lifecycle = VaultLifecycle(File(into, "vault.slots"), target)

        // `recovery-own-slot-only`. An import joins the open list, so the device has
        // one of its own first, and opens nothing on it.
        assertTrue(lifecycle.create("a-new-passphrase-here".toByteArray(Charsets.UTF_8)).opened)
        VaultKeyringSession.leaveVault()
        val outcome = File(dir, "vault.vault").inputStream().use {
            lifecycle.importArchive(it, pass)
        }
        assertTrue("an archive from the corpus build must import", outcome.opened)

        // `vault-keyring`. **An import opens nothing**, so the vault this test is
        // about is opened from the list, where it is the last entry.
        val opened = VaultKeyringSession.openVault(VaultKeyringSession.vaults().last().position)
        assertNotNull("the imported vault must be on the list it joined", opened)

        val vmk = VaultSession.vmk()!!
        val db = MetadataDb.load(target, VaultSession.metadataBlobId()!!, vmk)
        CorpusFixture.ITEMS.forEach { (name, size) ->
            val item = db.items.first { it.filename == name }
            val out = ByteArray(item.plaintextLength.toInt())
            target.reader(item.blobIds, vmk, item.plaintextLength).use { r ->
                val buf = ByteArray(BlobStore.CHUNK)
                var at = 0
                for (i in 0 until r.chunkCount) {
                    val n = r.readChunk(i, buf)
                    buf.copyInto(out, at, 0, n)
                    at += n
                }
            }
            assertArrayEquals("$name must survive the archive", CorpusFixture.content(name, size), out)
        }

        into.deleteRecursively()
    }

    private companion object {
        const val TAG = "VaultCorpus"
    }
}
