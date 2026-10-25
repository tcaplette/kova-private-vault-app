package com.app.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * P1-11 — the tamper log, against `FORMAT.md` §9 and OUTLINE P1-11's Verify:
 * delete or edit any middle entry and chain verification fails, reporting the
 * index.
 *
 * Instrumented rather than JVM because the log is libsodium all the way down —
 * AEAD per entry and `crypto_auth` per link — and `lazysodium-android` is JNI.
 * Every other crypto test in this repo is instrumented for the same reason.
 */
@RunWith(AndroidJUnit4::class)
class TamperLogTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var store: BlobStore
    private lateinit var vmk: ByteArray
    private lateinit var db: MetadataDb
    private lateinit var indexId: ByteArray
    private val rng = SecureRandom()

    private companion object { const val TAG = "VaultTamperLog" }

    @Before fun setUp() {
        dir = File(ctx.filesDir, "tamperlog-test").apply { mkdirs() }
        store = BlobStore(dir, File(ctx.filesDir, "tamperlog-incoming"))
        vmk = VaultKeys.newVmk()
        indexId = ByteArray(BlobStore.BLOB_ID_BYTES).also { rng.nextBytes(it) }
        db = MetadataDb.empty()
        db.logBlobId = TamperLog.create(store)
        db.save(store, indexId, vmk)
        TamperLog.closed()
    }

    @After fun tearDown() {
        TamperLog.closed()
        dir.deleteRecursively()
        File(ctx.filesDir, "tamperlog-incoming").deleteRecursively()
        vmk.fill(0)
    }

    private fun logId() = db.logBlobId!!

    private fun append(event: Int, payload: ByteArray = ByteArray(0)) =
        TamperLog.append(store, db, indexId, vmk, event, payload)

    private fun scan() = TamperLog.scan(store, logId(), vmk)

    /** Overwrites entry [index] in place — the "delete" half of the Verify. */
    private fun clobber(index: Int) {
        RandomAccessFile(store.fileOf(logId()), "rw").use { raf ->
            raf.seek(index.toLong() * TamperLog.ENTRY_BYTES)
            raf.write(ByteArray(TamperLog.ENTRY_BYTES).also { rng.nextBytes(it) })
        }
    }

    /** Flips one bit inside entry [index]'s ciphertext — the "edit" half. */
    private fun flipByte(index: Int) {
        RandomAccessFile(store.fileOf(logId()), "rw").use { raf ->
            val at = index.toLong() * TamperLog.ENTRY_BYTES + 40
            raf.seek(at)
            val b = raf.readByte()
            raf.seek(at)
            raf.writeByte((b.toInt() xor 0x01))
        }
    }

    // --- 6.1 round trip --------------------------------------------------------

    @Test fun entriesSurviveARoundTrip() {
        val events = listOf(
            TamperLog.VAULT_CREATED,
            TamperLog.UNLOCK_SUCCEEDED,
            TamperLog.IMPORT_COMPLETED,
            TamperLog.IMPORT_COMPLETED,
        )
        val before = System.currentTimeMillis()
        events.forEachIndexed { i, e -> append(e, TamperLog.countPayload(i + 1)) }
        val after = System.currentTimeMillis()

        val scan = scan()
        assertEquals("entry count", events.size, scan.entries.size)
        assertEquals("append index", events.size, scan.appendIndex)
        assertNull("no chain break", scan.chainBreakAt)

        scan.entries.forEachIndexed { i, entry ->
            assertEquals("index $i", i, entry.index)
            assertEquals("event $i", events[i], entry.event)
            assertEquals("payload $i", i + 1, entry.count)
            assertTrue(
                "timestamp $i in range",
                entry.timestamp in before..after
            )
        }
        Log.i(TAG, "round trip: ${scan.entries.size} entries, append index ${scan.appendIndex}")
    }

    // --- 6.2 a fresh log holds nothing -----------------------------------------

    @Test fun freshLogIsEmpty() {
        val scan = scan()
        assertEquals("no entries", 0, scan.entries.size)
        assertEquals("append index 0", 0, scan.appendIndex)
        assertEquals("CSPRNG authenticates nowhere", 0, scan.firstAuthFailure)
        assertNull("pre-fill is not a break", scan.chainBreakAt)
    }

    // --- 6.3 the Verify line ---------------------------------------------------

    @Test fun aClobberedMiddleEntryIsReportedByIndex() {
        repeat(10) { append(TamperLog.IMPORT_COMPLETED, TamperLog.countPayload(it)) }
        clobber(4)

        val scan = scan()
        assertEquals("break reported at 4", 4, scan.chainBreakAt)
        assertTrue("scan reports tampering", scan.tampered)
        assertEquals("authentication first fails at 4", 4, scan.firstAuthFailure)

        // The distinguishing half: entries after the hole still authenticate, so
        // this cannot be the end of the log.
        val recovered = scan.entries.map { it.index }
        assertFalse("entry 4 is gone", recovered.contains(4))
        assertEquals("entries 5..9 survive", (5..9).toList(), recovered.filter { it > 4 })
        Log.i(TAG, "clobbered middle: break=${scan.chainBreakAt} authFail=${scan.firstAuthFailure}")
    }

    @Test fun anEditedMiddleEntryIsReportedByIndex() {
        repeat(10) { append(TamperLog.IMPORT_COMPLETED, TamperLog.countPayload(it)) }
        flipByte(4)

        val scan = scan()
        assertEquals("break reported at 4", 4, scan.chainBreakAt)
        assertEquals("authentication first fails at 4", 4, scan.firstAuthFailure)
        assertEquals("entries 5..9 survive", (5..9).toList(), scan.entries.map { it.index }.filter { it > 4 })
    }

    @Test fun anIntactLogReportsNoBreak() {
        repeat(10) { append(TamperLog.IMPORT_COMPLETED) }
        val scan = scan()
        assertNull("no break", scan.chainBreakAt)
        assertEquals("authentication fails only past the end", 10, scan.firstAuthFailure)
        assertEquals(10, scan.entries.size)
    }

    // --- 6.4 the recorded leak, measured ---------------------------------------

    @Test fun truncatingFromTheTailIsNotDetected() {
        repeat(10) { append(TamperLog.IMPORT_COMPLETED) }
        clobber(9)
        clobber(8)

        val scan = scan()
        assertNull("FORMAT.md §10 item 16: tail truncation is invisible", scan.chainBreakAt)
        assertFalse("and reads as a clean, shorter history", scan.tampered)
        assertEquals("which is all it can be", 8, scan.entries.size)
        Log.i(TAG, "tail truncation: ${scan.entries.size} entries, no break reported")
    }

    // --- 6.5 another vault's key -----------------------------------------------

    @Test fun theWrongKeyOpensNothing() {
        repeat(5) { append(TamperLog.IMPORT_COMPLETED) }

        val other = VaultKeys.newVmk()
        try {
            val scan = TamperLog.scan(store, logId(), other)
            assertEquals("no entry authenticates", 0, scan.entries.size)
            assertEquals("an empty log, not an error", 0, scan.appendIndex)
        } finally {
            other.fill(0)
        }
    }

    // --- 6.6 growth ------------------------------------------------------------

    @Test fun aFullLogGrowsToTheNextBucket() {
        val old = logId().copyOf()
        val capacity = (store.fileOf(old).length() / TamperLog.ENTRY_BYTES).toInt()

        repeat(capacity) { append(TamperLog.IMPORT_COMPLETED, TamperLog.countPayload(it)) }
        assertEquals("filled to capacity", capacity, scan().entries.size)

        append(TamperLog.UNLOCK_SUCCEEDED)

        val fresh = logId()
        assertFalse("a new blob", fresh.contentEquals(old))
        assertFalse("the old blob is gone", store.fileOf(old).exists())
        assertEquals(
            "grown to the next bucket",
            2L * 1024 * 1024,
            store.fileOf(fresh).length()
        )

        val scan = scan()
        assertEquals("every earlier entry survived", capacity + 1, scan.entries.size)
        assertNull("the chain verifies across the copy", scan.chainBreakAt)
        assertEquals("payloads intact", 7, scan.entries[7].count)
        Log.i(TAG, "growth: $capacity -> ${scan.entries.size} entries, ${store.fileOf(fresh).length()} B")
    }

    // --- 6.7 concurrency -------------------------------------------------------

    @Test fun concurrentAppendsDoNotCollide() {
        val start = CountDownLatch(1)
        val threads = (0 until 2).map {
            thread {
                start.await()
                append(TamperLog.IMPORT_COMPLETED, TamperLog.countPayload(it))
            }
        }
        start.countDown()
        threads.forEach { it.join() }

        val scan = scan()
        assertEquals("two distinct entries", 2, scan.entries.size)
        assertEquals("at consecutive indices", listOf(0, 1), scan.entries.map { it.index })
        assertNull("chain verifies", scan.chainBreakAt)
    }

    // --- 6.8 one entry per burst of failures -----------------------------------

    @Test fun failedUnlocksAreOneEntryPerBurst() {
        repeat(3) { TamperLog.recordFailedUnlock() }
        assertEquals("counted in the process", 3, TamperLog.pendingFailures())

        TamperLog.opened(store, db, indexId, vmk)

        val failures = scan().entries.filter { it.event == TamperLog.UNLOCK_FAILED }
        assertEquals("exactly one entry", 1, failures.size)
        assertEquals("carrying the count", 3, failures.single().count)
        assertEquals("and drained", 0, TamperLog.pendingFailures())

        val events = scan().entries.map { it.event }
        assertEquals(
            "failures ordered before the success they ended with",
            listOf(TamperLog.UNLOCK_FAILED, TamperLog.UNLOCK_SUCCEEDED),
            events
        )
    }

    // --- 6.10 the I8 claim, asserted -------------------------------------------

    @Test fun theLogBlobLooksLikeEveryOtherBlob() {
        append(TamperLog.VAULT_CREATED)

        val file = store.fileOf(logId())
        assertEquals(
            "size is a bucket value",
            BlobStore.bucketFor(file.length()),
            file.length()
        )

        // The unused region past the one written entry must be the CSPRNG pre-fill,
        // not zeroes: a run of zeroes would bound the entry count from above.
        val tail = ByteArray(4096)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(TamperLog.ENTRY_BYTES.toLong())
            raf.readFully(tail)
        }
        assertFalse("unused space is not zeroed", tail.all { it.toInt() == 0 })
        assertTrue("and is high-entropy", tail.toSet().size > 200)
    }

    // --- the digest ------------------------------------------------------------

    @Test fun theDigestReportsTheIntervalAndNothingWhenQuiet() {
        // First unlock: nothing before it.
        assertEquals("quiet vault says nothing", emptyList<String>(), TamperLog.opened(store, db, indexId, vmk))

        repeat(2) { TamperLog.recordFailedUnlock() }
        append(TamperLog.IMPORT_COMPLETED, TamperLog.countPayload(3))

        val digest = TamperLog.opened(store, db, indexId, vmk)
        assertTrue("names the failures: $digest", digest.any { it == "2 unlock attempts failed." })
        assertTrue("names the items: $digest", digest.any { it == "3 items imported." })

        // The next unlock has nothing new between it and the last.
        assertEquals("interval is since the previous unlock", emptyList<String>(), TamperLog.opened(store, db, indexId, vmk))
    }

    @Test fun aBrokenChainIsNamedInTheDigestAndRecordedOnce() {
        repeat(6) { append(TamperLog.IMPORT_COMPLETED) }
        clobber(2)

        val first = TamperLog.opened(store, db, indexId, vmk)
        assertTrue("names the index: $first", first.any { it.contains("altered at entry 2") })

        val reports = scan().entries.count { it.event == TamperLog.CHAIN_VERIFICATION_FAILED }
        assertEquals("recorded once", 1, reports)

        val second = TamperLog.opened(store, db, indexId, vmk)
        assertTrue("still reported by the scan: $second", second.any { it.contains("altered at entry 2") })
        assertEquals(
            "and not restated in the log",
            1,
            scan().entries.count { it.event == TamperLog.CHAIN_VERIFICATION_FAILED }
        )
    }

    /**
     * P1-11 task 7.2. The scan runs once per unlock and reads the whole blob, so
     * its cost has to be a number rather than an argument — measured against the
     * Argon2id derivation that happens on the same unlock.
     */
    @Test fun aFullLogScansFasterThanTheDerivationItRidesOn() {
        val capacity = (store.fileOf(logId()).length() / TamperLog.ENTRY_BYTES).toInt()
        repeat(capacity) { append(TamperLog.IMPORT_COMPLETED) }

        scan()   // warm the page cache; the unlock path is never the first read
        val scanMs = (1..3).minOf {
            val t = System.nanoTime()
            val s = scan()
            assertEquals(capacity, s.entries.size)
            (System.nanoTime() - t) / 1_000_000
        }

        val salt = ByteArray(VaultKeys.SALT_BYTES).also { rng.nextBytes(it) }
        val kdfMs = run {
            val t = System.nanoTime()
            VaultKeys.deriveKek("correcthorsebattery".toByteArray(), salt, VaultKeys.ARGON2ID_PARAMS)
                .fill(0)
            (System.nanoTime() - t) / 1_000_000
        }

        Log.i(TAG, "scan of $capacity entries: ${scanMs} ms; Argon2id: ${kdfMs} ms")
        assertTrue(
            "a full-log scan ($scanMs ms) must stay under the derivation ($kdfMs ms)",
            scanMs < kdfMs
        )
    }

    @Test fun paddingIsNotConfusedForPayload() {
        append(TamperLog.VAULT_CREATED)
        val entry = scan().entries.single()
        assertEquals("an empty payload stays empty", 0, entry.payload.size)
        assertArrayEquals(ByteArray(0), entry.payload)
    }
}
