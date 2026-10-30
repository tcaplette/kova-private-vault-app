package com.app.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Device fixtures for manual end-to-end runs. **Skipped by default.**
 *
 * P1-8.6's headline claim — a swipe-away no longer loses an import — can only be
 * measured by hand on a device. Getting there needs a real vault in the app's
 * own `filesDir`, and driving a two-field passphrase form through `adb input`
 * proved unreliable in a way that has nothing to do with what is being tested.
 *
 * Guarded by a runner argument so the normal suite never creates a vault in the
 * app's real storage:
 *
 *     -Pandroid.testInstrumentationRunnerArguments.fixture=vault
 */
@RunWith(AndroidJUnit4::class)
class DeviceFixtures {

    @Test
    fun createRealVault() {
        val requested = InstrumentationRegistry.getArguments().getString("fixture")
        assumeTrue("fixture not requested", requested == "vault")

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val lifecycle = VaultLifecycle(
            File(ctx.filesDir, "vault.slots"),
            BlobStore(File(ctx.filesDir, "blobs"), File(ctx.filesDir, "incoming")),
        )
        assumeTrue("a vault already exists", !lifecycle.vaultExists())

        lifecycle.create("correcthorsebattery".toByteArray(Charsets.UTF_8))
        VaultSession.lock()
        Log.i("VaultFixture", "created a real vault in ${ctx.filesDir}")
    }

    /**
     * **P2-25's compatibility corpus.** Writes the bytes this build actually
     * produces, so that every later build can be tested against them.
     *
     * This is the mechanism the whole change rests on: round-tripping the current
     * writer against the current reader proves nothing about last year's bytes, and
     * a compatibility path no fixture covers is the one that rots. Generating and
     * committing a corpus is part of cutting a release.
     *
     * Everything is derived from a fixed seed, so the plaintext a later build must
     * recover is recomputable rather than stored — the corpus holds ciphertext only
     * and I3 is untouched.
     *
     * Output lands in the app's external files dir for `adb pull`, and the result
     * is committed to `app/src/androidTest/assets/fixtures/<version>/`.
     *
     *     ./gradlew :app:connectedPlayDebugAndroidTest \
     *       -Pandroid.testInstrumentationRunnerArguments.class=com.app.vault.DeviceFixtures \
     *       -Pandroid.testInstrumentationRunnerArguments.fixture=corpus
     */
    @Test
    fun generateCompatibilityCorpus() {
        val requested = InstrumentationRegistry.getArguments().getString("fixture")
        assumeTrue("fixture not requested", requested == "corpus")

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(ctx.cacheDir, "corpus-build").also { it.deleteRecursively(); it.mkdirs() }
        val store = BlobStore(File(work, "blobs"), File(work, "incoming"))
        val lifecycle = VaultLifecycle(File(work, "vault.slots"), store)

        lifecycle.create(CorpusFixture.PASSPHRASE.toByteArray(Charsets.UTF_8))
        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!

        // One item per size bucket the ladder has room for, plus a note, so the
        // corpus exercises the envelope at every stride and the index at both kinds
        // of row.
        val db = MetadataDb.load(store, indexId, vmk)
        CorpusFixture.ITEMS.forEach { (name, size) ->
            val blob = store.write(java.io.ByteArrayInputStream(CorpusFixture.content(name, size)), vmk)
            db.items += VaultItem(
                blobIds = listOf(blob.blobId),
                plaintextLength = blob.plaintextLength,
                thumb = null,
                filename = name,
                album = "Corpus",
                createdAt = CorpusFixture.CREATED_AT,
                importedAt = CorpusFixture.IMPORTED_AT,
                tags = listOf("fixture"),
            )
        }
        // A real image, so `backfill` below produces a real §8 pack.
        val png = java.io.ByteArrayOutputStream().also { out ->
            val bmp = android.graphics.Bitmap.createBitmap(
                CorpusFixture.IMAGE_SIDE, CorpusFixture.IMAGE_SIDE,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
            for (x in 0 until CorpusFixture.IMAGE_SIDE) {
                for (y in 0 until CorpusFixture.IMAGE_SIDE) {
                    bmp.setPixel(x, y, android.graphics.Color.rgb(x * 4, y * 4, (x + y) * 2))
                }
            }
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
        val imageBlob = store.write(java.io.ByteArrayInputStream(png), vmk)
        db.items += VaultItem(
            blobIds = listOf(imageBlob.blobId),
            plaintextLength = imageBlob.plaintextLength,
            thumb = null,
            filename = CorpusFixture.IMAGE_NAME,
            album = "Corpus",
            createdAt = CorpusFixture.CREATED_AT,
            importedAt = CorpusFixture.IMPORTED_AT,
            tags = listOf("fixture"),
        )
        db.items += VaultNote.create(store, vmk, CorpusFixture.NOTE_TITLE, CorpusFixture.NOTE_BODY)!!
        db.save(store, indexId, vmk)

        // A real thumbnail pack, so the corpus covers §8 and not only §5.
        // `backfill` mutates the index in memory — the `ThumbRef` and the pack table
        // are both on the db it is handed — so the save is not optional here.
        val withThumbs = MetadataDb.load(store, indexId, vmk)
        val filled = Thumbnails.backfill(store, ThumbnailStore(store), withThumbs, vmk)
        check(filled > 0) { "the corpus image produced no thumbnail" }
        withThumbs.save(store, indexId, vmk)

        // **Chaff is dropped from the corpus, and only chaff.** §6 chaff is CSPRNG
        // spanning the whole bucket ladder — 48 install blobs came to ~200 MB here —
        // and it carries no format at all: there is nothing in it a later build could
        // fail to read. What is kept is every structure that *is* format: the slot
        // file, the index, the item blobs, the thumbnail pack and the tamper log.
        // The index still names its owned chaff, and a corpus reader never asks for
        // blobs it did not come to read.
        // Cleared on this in-memory copy only — it is never saved back, and the
        // vault it was loaded from is a scratch directory this test deletes.
        val finished = MetadataDb.load(store, indexId, vmk)
        finished.ownedChaff.clear()
        val keep = buildSet {
            add(indexId.hex())
            finished.items.forEach { item -> item.blobIds.forEach { add(it.hex()) } }
            finished.thumbPacks.forEach { add(it.id.hex()) }
            finished.logBlobId?.let { add(it.hex()) }
        }

        val out = File(ctx.getExternalFilesDir(null), "fixtures/${CorpusFixture.VERSION}")
            .also { it.deleteRecursively(); it.mkdirs() }
        File(work, "vault.slots").copyTo(File(out, "vault.slots"))
        val blobsOut = File(out, "blobs").also { it.mkdirs() }
        File(work, "blobs").listFiles().orEmpty()
            .filter { it.name in keep }
            .forEach { it.copyTo(File(blobsOut, it.name)) }

        // The archive is written from the chaff-free index for the same reason, and
        // is otherwise an ordinary archive: same header, same table of contents,
        // same verbatim blobs. What a later build has to be able to open is the
        // format, and the format is all of it that is here.
        val archived = File(out, "vault.vault").outputStream().use { o ->
            VaultArchive.write(
                o, vmk, finished, store,
                CorpusFixture.PASSPHRASE.toByteArray(Charsets.UTF_8),
            )
        }
        VaultSession.lock()

        Log.i(
            "VaultFixture",
            "corpus ${CorpusFixture.VERSION}: slots ${File(out, "vault.slots").length()} B, " +
                "${blobsOut.listFiles()?.size} blobs kept of ${File(work, "blobs").listFiles()?.size}, " +
                "archive $archived blobs / ${File(out, "vault.vault").length()} B at $out"
        )
    }

    /**
     * **The share container's golden vectors** — `FORMAT.md` §12 version 2, the
     * corpus mechanism applied to the one file two *different* installs exchange.
     * Every input is fixed in [ShareVectorFixture] — the code words, the contact
     * recipient's X-Wing seed, the preludes, the policies and the plaintext — so the
     * assets hold ciphertext only and the plaintext a later build must recover is
     * recomputed, never stored.
     *
     * Output lands in the app's **cache dir**, because `run-as` reaches internal
     * storage only. **Run it through `am instrument`, not `connectedAndroidTest`**:
     * Gradle uninstalls the app when a connected run ends, and the cache dir goes
     * with it. With the emulator pinned:
     *
     *     ./gradlew :app:installDebug :app:installDebugAndroidTest
     *     adb -s emulator-5554 shell am instrument -w -e fixture share \
     *       -e class com.app.vault.DeviceFixtures#generateShareVectors \
     *       com.app.vault.test/androidx.test.runner.AndroidJUnitRunner
     *     adb -s emulator-5554 exec-out run-as com.app.vault cat cache/share-vectors/code.bin
     *
     * and commit to `app/src/androidTest/assets/fixtures/share/v2/`. **Add-only**: a
     * regenerated vector proves nothing, and a new container version gets
     * `share/v3/` beside this, never in its place.
     */
    @Test
    fun generateShareVectors() {
        val requested = InstrumentationRegistry.getArguments().getString("fixture")
        assumeTrue("fixture not requested", requested == "share")

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.cacheDir, "share-vectors")
            .also { it.deleteRecursively(); it.mkdirs() }

        File(out, "code.bin").outputStream().use { o ->
            ShareContainer.sealWithCode(
                java.io.ByteArrayInputStream(ShareVectorFixture.content()), o,
                ShareVectorFixture.CODE_PRELUDE,
                ShareContainer.Policy.Expiry(ShareVectorFixture.EXPIRY_MILLIS),
                ShareVectorFixture.CODE,
            )
        }
        File(out, "contact.bin").outputStream().use { o ->
            ShareContainer.sealToRecipient(
                java.io.ByteArrayInputStream(ShareVectorFixture.content()), o,
                ShareVectorFixture.CONTACT_PRELUDE,
                ShareContainer.Policy.SingleView,
                ShareVectorFixture.publicKey(),
            )
        }

        Log.i(
            "VaultFixture",
            "share vectors: code ${File(out, "code.bin").length()} B, " +
                "contact ${File(out, "contact.bin").length()} B at $out"
        )
    }

    /**
     * **Gate 1 → Phase 2.** Populates the app's real `filesDir` with everything the
     * adversarial self-review needs something to find: a vault, imports spanning
     * both bucket ladders, a real JPEG and a real MP4, thumbnails, a viewer read, a
     * playback read, and an export abandoned part-way.
     *
     * The gate is `adb shell run-as com.app.vault` against what this leaves behind.
     * Every source filename, plaintext size and magic byte is logged, so the scan
     * afterwards knows exactly what it is looking for rather than searching blind —
     * an adversary who has the app has the same knowledge.
     *
     * **Announced test-only state.** This writes a real vault into the app's real
     * storage. `adb shell pm clear com.app.vault` removes all of it, and Gate 1
     * closes by doing exactly that.
     *
     *     ./gradlew :app:connectedPlayDebugAndroidTest \
     *       -Pandroid.testInstrumentationRunnerArguments.class=com.app.vault.DeviceFixtures \
     *       -Pandroid.testInstrumentationRunnerArguments.fixture=gate1
     */
    @Test
    fun populateForGateOne() {
        val requested = InstrumentationRegistry.getArguments().getString("fixture")
        assumeTrue("fixture not requested", requested == "gate1")

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tag = "VaultGate1"
        val store = BlobStore(File(ctx.filesDir, "blobs"), File(ctx.filesDir, "incoming"))
        val lifecycle = VaultLifecycle(File(ctx.filesDir, "vault.slots"), store)
        assumeTrue("a vault already exists", !lifecycle.vaultExists())

        lifecycle.create("correcthorsebattery".toByteArray(Charsets.UTF_8))
        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!.copyOf()
        val rng = java.security.SecureRandom()

        // A JPEG the scan can hunt by magic bytes, and a distinctive filename it
        // can hunt as a string.
        val jpeg = java.io.ByteArrayOutputStream().also { out ->
            android.graphics.Bitmap
                .createBitmap(1200, 900, android.graphics.Bitmap.Config.ARGB_8888)
                .apply { eraseColor(android.graphics.Color.rgb(200, 30, 90)) }
                .compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
        }.toByteArray()

        // A real MP4, so `ftyp` is on disk somewhere if anything leaks it.
        val videoFile = File(ctx.cacheDir, "gate1-source.mp4")
        val video = if (VideoCorpus.generate(VideoCorpus.SPECS.first(), videoFile)) {
            videoFile.readBytes()
        } else {
            Log.w(tag, "no h264 encoder; falling back to random bytes for the video item")
            ByteArray(3 * 1024 * 1024).also { rng.nextBytes(it) }
        }

        class Source(val name: String, val bytes: ByteArray)

        val sources = listOf(
            Source("GATE1-holiday-photo.jpg", jpeg),
            Source("GATE1-clip.mp4", video),
            // Deliberately just over and just under bucket edges, so a size
            // correlation would show up as an obvious pairing if one existed.
            Source("GATE1-tiny.bin", ByteArray(9_000).also { rng.nextBytes(it) }),
            Source("GATE1-just-over-64k.bin", ByteArray(66_000).also { rng.nextBytes(it) }),
            Source("GATE1-just-under-1m.bin", ByteArray(1_040_000).also { rng.nextBytes(it) }),
            Source("GATE1-large-20m.bin", ByteArray(20 * 1024 * 1024).also { rng.nextBytes(it) }),
            // Spans blobs — the item `blob-size-cap` exists for. Before the cap
            // this was one 48 MiB blob alone in its bucket and provably real
            // content; now it is three at the top of the ladder, where chaff is.
            Source(
                "GATE1-split-40m.bin",
                ByteArray((BlobStore.SPLIT_PLAINTEXT * 2 + 5_000_000).toInt())
                    .also { rng.nextBytes(it) },
            ),
        )

        val db = MetadataDb.load(store, indexId, vmk)
        sources.forEach { source ->
            val blob = store.writeItem(java.io.ByteArrayInputStream(source.bytes), vmk)
            db.items += VaultItem(
                blob.blobIds,
                blob.plaintextLength,
                null,
                source.name,
                null,
                null,
                0,
                emptyList(),
            )
            Log.i(
                tag,
                "imported ${source.name}: plaintext=${source.bytes.size} " +
                    "blobs=${blob.blobIds.size} ids=${blob.blobIds.joinToString { it.hex() }}"
            )
        }
        db.save(store, indexId, vmk)

        // Thumbnails — §8's packs, which are blobs like any other.
        val thumbs = ThumbnailStore(store)
        val made = Thumbnails.backfill(store, thumbs, db, vmk)
        db.save(store, indexId, vmk)
        Log.i(tag, "thumbnails generated: $made")

        // A viewer read and a playback read, so anything either path might spill
        // has been given the chance to.
        val index = MetadataDb.load(store, indexId, vmk)
        val photo = index.items.first { it.filename == "GATE1-holiday-photo.jpg" }
        store.reader(photo.blobIds, vmk, photo.plaintextLength).use { r ->
            val buf = ByteArray(BlobStore.CHUNK)
            for (i in 0 until r.chunkCount) r.readChunk(i, buf)
        }
        val clip = index.items.first { it.filename == "GATE1-clip.mp4" }
        VaultDataSource(store, clip.blobIds, vmk, clip.plaintextLength).let { source ->
            source.open(
                androidx.media3.datasource.DataSpec(
                    android.net.Uri.parse("vault://${clip.itemId}")
                )
            )
            val buf = ByteArray(256 * 1024)
            var read = 0
            while (read >= 0 && read < buf.size) read = source.read(buf, 0, buf.size)
            source.close()
        }
        Log.i(tag, "viewer and playback reads done")

        // An export started and abandoned mid-item — I3's cancelled-export clause.
        val destination = MediaStoreDestination(ctx)
        val manifest = ExportManifest.openAt(destination)
        val entry = destination.create("GATE1-cancelled.bin", "application/octet-stream", null)
        destination.openOutput(entry).use { out ->
            out.write(ByteArray(64 * 1024))
            out.flush()
        }
        destination.discard(entry)
        destination.find(ExportManifest.NAME)?.let { destination.discard(it) }
        destination.sweep()
        Log.i(tag, "cancelled export left behind, manifest swept: $manifest")

        VaultSession.lock()
        Log.i(tag, "gate 1 fixture complete in ${ctx.filesDir}")
    }

    /**
     * **Gate 1, the path the other fixture bypasses.** A real import goes through
     * `WorkManager`, which persists a request's input `Data` and tags into
     * `no_backup/androidx.work.workdb`. On the first run of this gate that input
     * carried the source URI — and a `OpenMultipleDocuments` URI carries the
     * **display name** — plus the destination blob ID and the metadata index ID.
     * All three were read straight back out with `run-as`, which is what
     * `gate1-import-queue-leak` closed by moving them to [WorkJobs].
     *
     * The fixture stays: it is how the re-run proves the database is now empty of
     * them, and how any future change that puts them back gets caught.
     *
     *     -Pandroid.testInstrumentationRunnerArguments.fixture=gate1wm
     */
    @Test
    fun enqueueRealImportForGateOne() {
        val requested = InstrumentationRegistry.getArguments().getString("fixture")
        assumeTrue("fixture not requested", requested == "gate1wm")

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = android.net.Uri.parse(
            "content://com.android.externalstorage.documents/document/" +
                "primary%3ADCIM%2FGATE1WM-secret-holiday-name.jpg"
        )
        // Distinctive values, so what comes back out of the database is provably
        // these and not an artefact of the scan. The per-blob tag is what
        // MainActivity.importAll adds; without it the WorkTag half goes unmeasured.
        val blobId = ByteArray(BlobStore.BLOB_ID_BYTES) { 0xAB.toByte() }
        val indexId = ByteArray(BlobStore.BLOB_ID_BYTES) { 0xCD.toByte() }
        val request = androidx.work.OneTimeWorkRequestBuilder<ImportWorker>()
            .addTag(ImportWorker.IMPORT_TAG)
            .build()
        WorkJobs.register(request.id, WorkJobs.ImportJob(uri, CopyOnWriteArrayList(listOf(blobId)), indexId))
        androidx.work.WorkManager.getInstance(ctx).enqueue(request).result.get()
        Log.i("VaultGate1", "enqueued a real WorkManager import for $uri")
        // Let it run and finish (it will fail to open the URI — the input Data is
        // persisted either way, which is the point).
        Thread.sleep(4000)
        Log.i("VaultGate1", "work state: ${
            androidx.work.WorkManager.getInstance(ctx).getWorkInfoById(request.id).get()?.state
        }")
    }

    /**
     * A genuine kill mid-purge — `blob-ownership-by-record`, 6.5.
     *
     * Real vault, real rows, real files. `VaultDeletion.purge` runs on its own
     * thread so this test method returns immediately and the app process stays
     * alive with the purge still unlinking blobs, waiting for `adb shell am
     * force-stop` from the host to land somewhere inside the unlink loop.
     *
     *     -Pandroid.testInstrumentationRunnerArguments.fixture=purgekill
     */
    @Test
    fun startRealPurgeForKill() {
        val requested = InstrumentationRegistry.getArguments().getString("fixture")
        assumeTrue("fixture not requested", requested == "purgekill")

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BlobStore(File(ctx.filesDir, "blobs"), File(ctx.filesDir, "incoming"))
        val lifecycle = VaultLifecycle(File(ctx.filesDir, "vault.slots"), store)
        assumeTrue("a vault already exists", !lifecycle.vaultExists())

        lifecycle.create("correcthorsebattery".toByteArray(Charsets.UTF_8))
        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!.copyOf()
        val rng = java.security.SecureRandom()

        val items = (0 until 1500).map { i ->
            val blob = store.writeItem(
                java.io.ByteArrayInputStream(ByteArray(64).also { rng.nextBytes(it) }), vmk,
            )
            VaultItem(blob.blobIds, blob.plaintextLength, null, "kill$i.bin", null, null, 0, emptyList())
        }
        val db = MetadataDb.load(store, indexId, vmk)
        db.items += items
        db.save(store, indexId, vmk)

        // The exact set this purge is about to delete, so the check after the kill
        // can tell "this purge's leftovers" from every other blob the vault owns
        // (its index, its log, chaff no one is touching) — the whole store is not
        // this purge's business, only what it named.
        val doomed = items.flatMap { it.blobIds }
        File(ctx.filesDir, "purgekill-doomed.txt").writeText(doomed.joinToString("\n") { it.hex() })

        val blobsDir = File(ctx.filesDir, "blobs")
        val startCount = blobsDir.list()?.size ?: 0
        Log.i("VaultPurgeKill", "${items.size} items written, $startCount blob files, starting the real purge now")

        Thread { VaultDeletion.purge(store, indexId, vmk, items) }.apply { isDaemon = false }.start()

        // A real, unclean kill fired the instant the unlink loop is observed to have
        // started — the same signal `am force-stop` would send, at the earliest
        // moment that guarantees we are genuinely mid-loop rather than before it.
        while ((blobsDir.list()?.size ?: 0) >= startCount) { /* spin */ }
        val atKill = blobsDir.list()?.size ?: 0
        Log.i("VaultPurgeKill", "killing now: $atKill of $startCount blob files remain")
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /**
     * Reopens after [startRealPurgeForKill]'s real kill and checks the vault the
     * spec promises: it opens, every doomed row is gone, no row anywhere points at
     * a blob that is no longer on disk, and every surviving blob left over from the
     * interrupted delete loop is still named in `ownedChaff` rather than untracked.
     *
     *     -Pandroid.testInstrumentationRunnerArguments.fixture=purgekillcheck
     */
    @Test
    fun checkAfterRealPurgeKill() {
        val requested = InstrumentationRegistry.getArguments().getString("fixture")
        assumeTrue("fixture not requested", requested == "purgekillcheck")

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BlobStore(File(ctx.filesDir, "blobs"), File(ctx.filesDir, "incoming"))
        val lifecycle = VaultLifecycle(File(ctx.filesDir, "vault.slots"), store)
        val opened = lifecycle.unlock("correcthorsebattery".toByteArray(Charsets.UTF_8))
        check(opened.opened) { "the vault did not open after the kill" }
        val vault = VaultKeyringSession.openVault(0) ?: error("no vault at position 0")
        lifecycle.open(vault)

        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!.copyOf()
        val db = MetadataDb.load(store, indexId, vmk)

        check(db.items.none { it.filename.startsWith("kill") }) {
            "a purged row survived: ${db.items.count { it.filename.startsWith("kill") }} of the doomed rows remain"
        }
        val danglingRows = db.items.filter { item -> item.blobIds.any { !store.fileOf(it).exists() } }
        check(danglingRows.isEmpty()) { "${danglingRows.size} row(s) reference a deleted blob" }

        // Only this purge's own doomed blobs — not the rest of the store, which is
        // none of this check's business.
        val doomed = File(ctx.filesDir, "purgekill-doomed.txt").readLines().filter { it.isNotBlank() }
        val ownedHex = db.ownedChaff.map { it.hex() }.toSet()
        val stillOnDisk = doomed.filter { File(File(ctx.filesDir, "blobs"), it).exists() }
        val untracked = stillOnDisk.filter { it !in ownedHex }
        Log.i(
            "VaultPurgeCheck",
            "opened OK, 0 purged rows survive, 0 dangling rows, ${doomed.size} doomed blob(s), " +
                "${stillOnDisk.size} still on disk, ${untracked.size} of those untracked",
        )
        check(untracked.isEmpty()) {
            "${untracked.size} surviving blob(s) from the interrupted purge are untracked, not owned chaff"
        }
        Log.i("VaultPurgeCheck", "PASS: every leftover blob from the interrupted purge is recorded, not untracked")
    }

    /**
     * A genuine kill mid-import — `blob-ownership-by-record`, 6.6.
     *
     * Calls the exact sequence `ImportWorker` runs for a completed item — content
     * blobs written, then the `ownedChaff` commit, then a real thumbnail decode,
     * then the chaff write, then the commit that creates the row — on its own
     * thread, so the app process stays alive after this method returns while a
     * real `adb shell am force-stop` from the host aims for the window between
     * the first commit and the second.
     *
     *     -Pandroid.testInstrumentationRunnerArguments.fixture=importkill
     */
    @Test
    fun startRealImportForKill() {
        val requested = InstrumentationRegistry.getArguments().getString("fixture")
        assumeTrue("fixture not requested", requested == "importkill")

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BlobStore(File(ctx.filesDir, "blobs"), File(ctx.filesDir, "incoming"))
        val lifecycle = VaultLifecycle(File(ctx.filesDir, "vault.slots"), store)
        assumeTrue("a vault already exists", !lifecycle.vaultExists())

        lifecycle.create("correcthorsebattery".toByteArray(Charsets.UTF_8))
        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!.copyOf()

        val jpeg = java.io.ByteArrayOutputStream().also { out ->
            android.graphics.Bitmap
                .createBitmap(4000, 3000, android.graphics.Bitmap.Config.ARGB_8888)
                .apply { eraseColor(android.graphics.Color.rgb(40, 120, 200)) }
                .compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
        }.toByteArray()

        val committedOwnedChaff = java.util.concurrent.atomic.AtomicBoolean(false)

        Thread {
            val blob = store.writeItem(java.io.ByteArrayInputStream(jpeg), vmk)
            val item = VaultItem(
                blob.blobIds, blob.plaintextLength, null, "importkill.jpg", null, null, 0, emptyList(),
            )
            Log.i("VaultImportKill", "blobs written: ${item.blobIds.joinToString { it.hex() }}")
            File(ctx.filesDir, "importkill-blobids.txt")
                .writeText(item.blobIds.joinToString("\n") { it.hex() })

            MetadataDb.commit(store, indexId, vmk) { db -> db.ownedChaff += item.blobIds; db }
            committedOwnedChaff.set(true)
            Log.i("VaultImportKill", "ownedChaff commit done, now the slow thumbnail decode")

            // The kill fires as soon as it sees the flag above. If it is slower than
            // this decode, killing here still lands inside the target window — the
            // row commit is well past this point.
            val made = Thumbnails.generate(store, item, vmk)
            val rng = java.security.SecureRandom()
            val chaffCount = item.blobIds.sumOf { rng.nextInt(VaultLifecycle.IMPORT_CHAFF_MAX_PER_BLOB + 1) }
            val chaff = if (chaffCount > 0) store.writeChaff(chaffCount) else emptyList()

            MetadataDb.commit(store, indexId, vmk) { db ->
                val thumbs = ThumbnailStore(store)
                val staged = if (made.webp != null) {
                    item.withThumb(thumbs.stage(db, made.webp), made.durationMs).also { thumbs.flush(db, vmk) }
                } else item
                db.items += staged
                db.ownedChaff.removeAll { id -> item.blobIds.any { it.contentEquals(id) } }
                if (chaff.isNotEmpty()) db.ownedChaff += chaff
                db
            }
            Log.i("VaultImportKill", "row commit done — the kill missed the window if this printed")
        }.apply { isDaemon = false }.start()

        while (!committedOwnedChaff.get()) { /* spin */ }
        Log.i("VaultImportKill", "killing now, right after the ownedChaff commit")
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /**
     * Reopens after [startRealImportForKill]'s real kill and checks the vault the
     * spec promises: it opens, no item references the stranded content blob, and
     * that blob is recorded as this vault's owned chaff rather than left untracked.
     *
     *     -Pandroid.testInstrumentationRunnerArguments.fixture=importkillcheck
     */
    @Test
    fun checkAfterRealImportKill() {
        val requested = InstrumentationRegistry.getArguments().getString("fixture")
        assumeTrue("fixture not requested", requested == "importkillcheck")

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BlobStore(File(ctx.filesDir, "blobs"), File(ctx.filesDir, "incoming"))
        val lifecycle = VaultLifecycle(File(ctx.filesDir, "vault.slots"), store)
        val opened = lifecycle.unlock("correcthorsebattery".toByteArray(Charsets.UTF_8))
        check(opened.opened) { "the vault did not open after the kill" }
        val vault = VaultKeyringSession.openVault(0) ?: error("no vault at position 0")
        lifecycle.open(vault)

        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!.copyOf()
        val db = MetadataDb.load(store, indexId, vmk)

        check(db.items.none { it.filename == "importkill.jpg" }) {
            "the stranded item's row was committed despite the kill landing before it"
        }

        // Only the specific blob(s) this killed import wrote — not the rest of the
        // store, which this check has no business touching.
        val stranded = File(ctx.filesDir, "importkill-blobids.txt").readLines().filter { it.isNotBlank() }
        check(stranded.isNotEmpty()) { "the fixture never recorded which blob id(s) it wrote" }
        val ownedHex = db.ownedChaff.map { it.hex() }.toSet()
        val untracked = stranded.filter { it !in ownedHex }
        Log.i(
            "VaultImportCheck",
            "opened OK, item row absent, ${stranded.size} stranded blob(s), ${untracked.size} of those untracked",
        )
        check(untracked.isEmpty()) { "${untracked.size} blob(s) from the killed import are untracked, not owned chaff" }
        Log.i("VaultImportCheck", "PASS: the stranded content blob is recorded as owned chaff, not untracked")
    }

    /**
     * `blob-ownership-by-record`, 6.7 — same-vault selectivity. `churnChaff`
     * (`VaultLifecycle.kt`) reads only `db.ownedChaff`, the index already loaded
     * for the vault being opened; it cannot see another vault's blobs, and
     * `MultiVaultBlobTest` 1.1/1.2 already prove that byte-for-byte across two
     * vaults and two keyrings. What is left is same-vault selectivity: with one
     * vault holding both a live item and owned chaff, repeated unlocks must
     * rotate the chaff and leave the live item's blob exactly where it is.
     *
     * Reuses the vault [startRealImportForKill] left behind — one owned-chaff
     * entry and no items — and adds a real item to it.
     *
     *     -Pandroid.testInstrumentationRunnerArguments.fixture=churncheck
     */
    @Test
    fun churnTouchesOnlyOwnedChaff() {
        val requested = InstrumentationRegistry.getArguments().getString("fixture")
        assumeTrue("fixture not requested", requested == "churncheck")

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BlobStore(File(ctx.filesDir, "blobs"), File(ctx.filesDir, "incoming"))
        val lifecycle = VaultLifecycle(File(ctx.filesDir, "vault.slots"), store)
        val opened = lifecycle.unlock("correcthorsebattery".toByteArray(Charsets.UTF_8))
        check(opened.opened) { "the vault did not open" }
        val vault = VaultKeyringSession.openVault(0) ?: error("no vault at position 0")
        lifecycle.open(vault)

        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!.copyOf()
        val rng = java.security.SecureRandom()
        val content = ByteArray(4_096).also { rng.nextBytes(it) }
        val blob = store.write(java.io.ByteArrayInputStream(content), vmk)
        val liveItem = VaultItem(
            listOf(blob.blobId), blob.plaintextLength, null, "churn-live.bin", null, null, 0, emptyList(),
        )
        MetadataDb.commit(store, indexId, vmk) { db -> db.items += liveItem; db }

        val before = MetadataDb.load(store, indexId, vmk)
        check(before.ownedChaff.isNotEmpty()) { "no owned chaff to churn — nothing to prove" }
        val liveBytesBefore = store.fileOf(liveItem.blobIds.single()).readBytes()
        val chaffBefore = before.ownedChaff.map { it.hex() }.toSet()

        repeat(5) { lifecycle.open(vault) }

        val after = MetadataDb.load(store, indexId, vmk)
        val liveBytesAfter = store.fileOf(liveItem.blobIds.single()).readBytes()
        val chaffAfter = after.ownedChaff.map { it.hex() }.toSet()

        check(liveBytesAfter.contentEquals(liveBytesBefore)) { "the live item's blob was rewritten by the churn" }
        check(after.items.single().itemId == liveItem.itemId) { "the live item's row is gone" }
        check(chaffAfter != chaffBefore) { "5 opens never rotated the single owned-chaff entry" }
        Log.i(
            "VaultChurnCheck",
            "PASS: 5 opens rotated owned chaff ($chaffBefore -> $chaffAfter) and left the live blob untouched",
        )
    }
}
