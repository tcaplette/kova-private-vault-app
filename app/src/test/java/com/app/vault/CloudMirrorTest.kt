package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The mirror's whole decision, on the JVM — `cloud-mirror` D4.
 *
 * `CloudMirror.plan` takes two listings and returns three lists. No SAF, no
 * emulator, no provider: the part that decides what happens to a user's backup is
 * arithmetic over two maps, and it is asserted as arithmetic.
 *
 * **The second half of this file used to keep "runs with the vault locked" true**,
 * by scanning `CloudMirror.kt` for three vault symbols it was forbidden to name.
 * `settings-separation` spends that guarantee deliberately: selecting one vault's
 * blobs requires its index, which requires its key, so the mirror now opens a vault
 * and this file names `MetadataDb`. The scan is inverted rather than deleted — what
 * is asserted now is the half that survived, that **nothing is written to disk that
 * maps a blob to a vault**. The folders are the mapping, and the backup screen says
 * so.
 */
class CloudMirrorTest {

    private fun entries(vararg pairs: Pair<String, Pair<Long, Long>>) =
        pairs.associate { (name, sizeAndTime) ->
            name to CloudMirror.Entry(sizeAndTime.first, sizeAndTime.second)
        }

    private fun local(vararg pairs: Pair<String, Pair<Long, Long>>) = entries(*pairs)

    private fun remote(vararg pairs: Pair<String, Pair<Long, Long>>) = entries(*pairs)

    // --- the status line — `backup-screen-status-first` D1 ---------------------

    @Test
    fun aMatchingFolderIsUpToDate() {
        val both = arrayOf("aa" to (10L to 5L), "bb" to (20L to 7L))
        val status = CloudMirror.status(local(*both), remote(*both))
        assertEquals(0, status.pending)
    }

    @Test
    fun oneNewOneChangedAndOneRemovedAreThreeChanges() {
        val status = CloudMirror.status(
            local("aa" to (10L to 5L), "bb" to (20L to 9L), "new" to (30L to 9L)),
            remote("aa" to (10L to 5L), "bb" to (20L to 5L), "gone" to (40L to 5L)),
        )
        assertEquals("upload, overwrite and delete each count once", 3, status.pending)
    }

    @Test
    fun lastCopiedIsTheNewestTimeInTheFolder() {
        val status = CloudMirror.status(
            local("aa" to (10L to 1L)),
            remote("aa" to (10L to 100L), "bb" to (10L to 300L), "cc" to (10L to 200L)),
        )
        assertEquals(300L, status.lastCopied)
    }

    @Test
    fun aProviderThatReportsNoTimesHasNoLastCopied() {
        val status = CloudMirror.status(local(), remote("aa" to (10L to -1L)))
        assertEquals(null, status.lastCopied)
    }

    @Test
    fun anEmptyFolderIsBehindByEveryFile() {
        val status = CloudMirror.status(local("aa" to (10L to 1L), "bb" to (20L to 1L)), emptyMap())
        assertEquals(2, status.pending)
        assertEquals(null, status.lastCopied)
    }

    // --- the planner ----------------------------------------------------------

    @Test
    fun anEmptyDestinationTakesEverything() {
        val plan = CloudMirror.plan(
            local("aa" to (10L to 1L), "bb" to (20L to 1L)),
            emptyMap(),
        )
        assertEquals(listOf("aa", "bb"), plan.upload.sorted())
        assertTrue(plan.overwrite.isEmpty())
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun anEmptyStoreEmptiesTheDestination() {
        val plan = CloudMirror.plan(emptyMap(), remote("aa" to (10L to 1L)))
        assertTrue(plan.upload.isEmpty())
        assertEquals(listOf("aa"), plan.delete)
    }

    @Test
    fun identicalSidesDoNothing() {
        val both = arrayOf("aa" to (10L to 5L), "bb" to (20L to 5L))
        val plan = CloudMirror.plan(local(*both), remote(*both))
        assertTrue("a matching store must not re-upload", plan.empty)
    }

    @Test
    fun disjointSidesUploadAndDelete() {
        val plan = CloudMirror.plan(
            local("aa" to (10L to 1L)),
            remote("bb" to (20L to 1L)),
        )
        assertEquals(listOf("aa"), plan.upload)
        assertEquals(listOf("bb"), plan.delete)
        assertTrue(plan.overwrite.isEmpty())
    }

    @Test
    fun aChangedSizeIsRewritten() {
        // The index blob crossing a 64 KiB chunk boundary.
        val plan = CloudMirror.plan(
            local("aa" to (131072L to 5L)),
            remote("aa" to (65536L to 5L)),
        )
        assertEquals(listOf("aa"), plan.overwrite)
        assertTrue(plan.upload.isEmpty())
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun aNewerLocalCopyIsRewrittenAtTheSameSize() {
        // The index blob rewritten inside one chunk boundary: only time moves.
        val plan = CloudMirror.plan(
            local("aa" to (65536L to 900L)),
            remote("aa" to (65536L to 500L)),
        )
        assertEquals(listOf("aa"), plan.overwrite)
    }

    @Test
    fun anOlderLocalCopyIsLeftAlone() {
        // The ordinary case after any successful upload: the provider stamps its
        // own time, which is at or after ours. This must not loop forever.
        val plan = CloudMirror.plan(
            local("aa" to (65536L to 500L)),
            remote("aa" to (65536L to 900L)),
        )
        assertTrue("a stamped-later remote must not re-upload every run", plan.empty)
    }

    @Test
    fun aProviderThatReportsNothingIsRewritten() {
        val plan = CloudMirror.plan(
            local("aa" to (10L to 5L)),
            remote("aa" to (-1L to -1L)),
        )
        assertEquals(
            "no size and no time is not evidence the copy is current",
            listOf("aa"), plan.overwrite
        )
    }

    // --- the destination guard ------------------------------------------------

    @Test
    fun anEmptyFolderIsFree() {
        assertTrue(CloudMirror.destinationIsFree(setOf("aa"), emptySet()))
    }

    @Test
    fun thisVaultsOwnFolderIsFree() {
        assertTrue(
            "re-picking a folder this vault already filled is a subset, not a conflict",
            CloudMirror.destinationIsFree(setOf("aa", "bb"), setOf("aa"))
        )
    }

    @Test
    fun aFolderHoldingSomethingElseIsRefused() {
        assertFalse(
            "a foreign file means someone else's mirror, and mirroring would delete it",
            CloudMirror.destinationIsFree(setOf("aa"), setOf("zz"))
        )
    }

    /**
     * `settings-separation`. A folder holding **another vault of this install** is
     * refused, and told apart from a folder holding another install's files.
     *
     * The two are one blob-name lookup apart: a foreign name that is nonetheless a
     * blob on this device belongs to a vault here. The refusals differ because the
     * remedies do — one folder per vault, against one folder per install — and a
     * refusal that said the wrong one would send the user to fix the wrong thing.
     */
    @Test
    fun aFolderHoldingAnotherVaultOfThisInstallIsNamedAsSuch() {
        assertEquals(
            CloudMirror.Refusal.AnotherVault,
            CloudMirror.destinationRefusal(
                vaultNames = setOf("aa"),
                localNames = setOf("aa", "bb"),
                remote = setOf("bb"),
            )
        )
    }

    @Test
    fun aFolderHoldingAnotherInstallsFilesIsNamedAsSuch() {
        assertEquals(
            CloudMirror.Refusal.AnotherInstall,
            CloudMirror.destinationRefusal(
                vaultNames = setOf("aa"),
                localNames = setOf("aa", "bb"),
                remote = setOf("zz"),
            )
        )
    }

    @Test
    fun aFolderThisVaultFilledCarriesNoRefusal() {
        assertEquals(
            null,
            CloudMirror.destinationRefusal(
                vaultNames = setOf("aa", "bb"),
                localNames = setOf("aa", "bb", "cc"),
                remote = setOf("aa"),
            )
        )
    }

    // --- what is on disk ------------------------------------------------------

    @Test
    fun onlyBlobNamesAreListed() {
        val dir = java.nio.file.Files.createTempDirectory("mirror").toFile()
        try {
            val blob = "a".repeat(BlobStore.BLOB_ID_BYTES * 2)
            File(dir, blob).writeText("x")
            // The shapes that must never be uploaded, in the directory itself.
            File(dir, "vault.slots").writeText("x")
            File(dir, "vault.bio").writeText("x")
            File(dir, "$blob.partial").writeText("x")
            File(dir, "NOTHEX${"a".repeat(26)}").writeText("x")

            assertEquals(setOf(blob), CloudMirror.localEntries(dir).keys)
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- what this file may not write down -----------------------------------

    private companion object {
        /**
         * The mirror may **not** persist a name-to-vault mapping. Anything that reads
         * or writes a file, a preference or a database on its own account is how such
         * a mapping would get written down.
         *
         * Not the vault symbols any more. Until `settings-separation` this list was
         * `VaultSession`, `MetadataDb` and `VaultKeys`, and naming one was the defect:
         * a mirror that could not open a vault could not know which blob was whose, so
         * "runs with every vault locked" held by construction. Per-vault backup buys
         * exactly what that forbade, and the cost is stated in `cloud-mirror` and on
         * the backup screen rather than hidden here.
         *
         * What did not change is that the answer is computed and thrown away. The
         * upload set is derived from the open index in memory, at the moment of the
         * run; nothing on disk records it, which is why the exclusions below still
         * matter and are still checked.
         */
        val LEDGER_SYMBOLS = listOf(
            "SharedPreferences", "openFileOutput", "getDatabasePath", "SQLite", "Room",
        )
    }

    private fun sourceFile(name: String): File {
        var dir: File? = File(".").absoluteFile
        val rel = "src/main/java/com/app/vault"
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isDirectory }
                ?.let { return File(it, name) }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the source package from ${File(".").absolutePath}")
    }

    private fun stripped(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")

    /**
     * The mirror keeps no store of its own: a destination lives inside the vault's encrypted
     * index, and the pre-`settings-separation` preference is gone with the migration that read
     * it — there is no installed base to migrate.
     */
    @Test
    fun theMirrorWritesNoLedgerOfItsOwn() {
        val source = sourceFile("CloudMirror.kt")
        assertTrue("CloudMirror.kt not found at ${source.absolutePath}", source.isFile)
        val code = stripped(source.readText())

        val found = (LEDGER_SYMBOLS + "getSharedPreferences")
            .filter { Regex("""\b$it\b""").containsMatchIn(code) }
        assertEquals(
            "cloud-mirror: nothing on disk may map a blob to a vault, so the mirror keeps no " +
                "store of its own. Found: $found",
            emptyList<String>(), found
        )
    }

    /**
     * The upload set is exactly what the open index names, and nothing else in the
     * store — `settings-separation`.
     *
     * The chaff is what this is really about. It is in `blobs/` and it is this vault's,
     * and it is deliberately *not* uploaded: chaff hides how much of the store is real
     * on the device, and at a folder holding one vault it hides nothing while costing
     * the user every byte of it.
     */
    @Test
    fun theUploadSetIsTheIndexAndTheKeyring() {
        val db = MetadataDb.empty()
        val itemBlob = ByteArray(16) { 1 }
        val packBlob = ByteArray(16) { 2 }
        val indexBlob = ByteArray(16) { 3 }
        val keyringBlob = ByteArray(16) { 4 }
        val chaffBlob = ByteArray(16) { 5 }

        db.items += VaultItem(
            blobIds = listOf(itemBlob),
            plaintextLength = 1L,
            thumb = null,
            filename = "a",
            album = null,
            createdAt = null,
            importedAt = 0L,
            tags = emptyList(),
        )
        db.thumbPacks += ThumbPack(packBlob, 1)
        db.ownedChaff += chaffBlob

        val names = CloudMirror.vaultNames(db, indexBlob, keyringBlob)

        assertEquals(
            setOf(itemBlob, packBlob, indexBlob, keyringBlob).map { hex(it) }.toSet(),
            names,
        )
        assertFalse(
            "chaff hides the store's size on the device and would hide nothing at a folder " +
                "that holds one vault",
            hex(chaffBlob) in names,
        )
    }

    /** A vault with no keyring blob to name — the one the archive import produces. */
    @Test
    fun aMissingKeyringBlobIsSimplyNotInTheSet() {
        val indexBlob = ByteArray(16) { 3 }
        val names = CloudMirror.vaultNames(MetadataDb.empty(), indexBlob, null)
        assertEquals(setOf(hex(indexBlob)), names)
    }

    /**
     * `per-vault-intruder-capture`. A vault's own capture store travels with it: its index, its
     * items, its thumbnail packs — and its log, which is its timeline.
     */
    @Test
    fun theVaultsCaptureStoreIsInTheSet() {
        val indexBlob = ByteArray(16) { 3 }
        val storeId = ByteArray(16) { 6 }
        val captureBlob = ByteArray(16) { 7 }
        val capturePack = ByteArray(16) { 8 }
        val captureLog = ByteArray(16) { 9 }
        val store = MetadataDb.empty()
        store.items += VaultItem(
            blobIds = listOf(captureBlob),
            plaintextLength = 1L,
            thumb = null,
            filename = "intruder-1.jpg",
            album = null,
            createdAt = 1L,
            importedAt = 1L,
            tags = emptyList(),
        )
        store.thumbPacks += ThumbPack(capturePack, 1)
        store.logBlobId = captureLog

        val names = CloudMirror.vaultNames(MetadataDb.empty(), indexBlob, null, CloudMirror.CaptureStoreIndex(storeId, store))

        assertEquals(setOf(indexBlob, storeId, captureBlob, capturePack, captureLog).map { hex(it) }.toSet(), names)
        assertEquals(
            "its index and log are saved in place, like the vault's own index",
            setOf(indexBlob, storeId, captureLog).map { hex(it) }.toSet(),
            CloudMirror.mutableNames(MetadataDb.empty(), indexBlob, null, CloudMirror.CaptureStoreIndex(storeId, store)),
        )
    }

    @Test
    fun theScanWouldCatchAViolation() {
        // Negative control. A scan that has stopped matching passes every run.
        val code = stripped("val db = openFileOutput(\"ledger\", 0)")
        assertTrue(
            "the scan no longer detects what it exists to detect",
            LEDGER_SYMBOLS.any { Regex("""\b$it\b""").containsMatchIn(code) }
        )
    }
}
