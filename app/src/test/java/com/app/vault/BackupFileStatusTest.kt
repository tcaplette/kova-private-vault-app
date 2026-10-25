package com.app.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup file's arithmetic, on the JVM — `backup-to-cloud-apps` D4, D5.
 *
 * Status, the daily gate and the expected size decide what the screen says and whether a
 * run writes at all. None of it touches Sodium, so none of it needs a device.
 */
class BackupFileStatusTest {

    private val D = ByteArray(32) { 7 }
    private val INDEX = "%032x".format(0)

    private fun local(vararg blobs: Pair<Long, Long>) =
        blobs.withIndex().associate { (i, b) -> "%032x".format(i) to CloudMirror.Entry(b.first, b.second) }

    @Test
    fun theTableIsPaddedSoItsLengthIsNotTheCount() {
        assertEquals(BackupFile.TOC_STEP, BackupFile.tocPlainLength(1))
        assertEquals("one blob and a thousand read the same", BackupFile.tocPlainLength(1), BackupFile.tocPlainLength(1000))
        assertEquals(2 * BackupFile.TOC_STEP, BackupFile.tocPlainLength(3000))
    }

    @Test
    fun aCompleteUnchangedFileIsUpToDate() {
        val blobs = local(65536L to 100L, 131072L to 200L)
        val record = BackupFile.Record(writtenAt = 500L, size = 999L, indexDigest = D)
        val status = BackupFile.status(blobs, record, remoteSize = 999L, indexName = INDEX, indexDigest = D)
        assertEquals(0, status.pending)
        assertEquals("last copied is this app's own record, not the provider's time", 500L, status.lastCopied)
    }

    @Test
    fun blobsNewerThanTheWriteAreTheChanges() {
        val blobs = local(65536L to 100L, 65536L to 900L)
        assertEquals(1, BackupFile.status(blobs, BackupFile.Record(500L, 10L, D), 10L, INDEX, D).pending)
    }

    @Test
    fun aFileOfTheWrongSizeDidNotFinish() {
        val blobs = local(65536L to 100L, 65536L to 100L, 65536L to 100L)
        val status = BackupFile.status(blobs, BackupFile.Record(500L, 10L, D), remoteSize = 4L, indexName = INDEX, indexDigest = D)
        assertEquals("an interrupted or replaced file leaves everything to copy", 3, status.pending)
    }

    @Test
    fun noRecordIsNeverBackedUp() {
        val blobs = local(65536L to 100L)
        val status = BackupFile.status(blobs, null, remoteSize = 0L, indexName = INDEX, indexDigest = D)
        assertEquals(1, status.pending)
        assertNull(status.lastCopied)
    }

    @Test
    fun aProviderThatReportsNoSizeIsJudgedByTimeAlone() {
        val blobs = local(65536L to 100L)
        assertEquals(0, BackupFile.status(blobs, BackupFile.Record(500L, 10L, D), remoteSize = -1L, indexName = INDEX, indexDigest = D).pending)
    }

    @Test
    fun anIndexRewrittenWithTheSameContentIsNotAChange() {
        // Blob 0 is the index, saved after the backup by the unlock's chaff rotation.
        val blobs = local(65536L to 900L, 65536L to 100L)
        val status = BackupFile.status(blobs, BackupFile.Record(500L, 10L, D), 10L, INDEX, D.copyOf())
        assertEquals("a newer index whose content digest matches is still up to date", 0, status.pending)
    }

    @Test
    fun anIndexWithNewContentIsOneChange() {
        val blobs = local(65536L to 900L, 65536L to 100L)
        val status = BackupFile.status(blobs, BackupFile.Record(500L, 10L, D), 10L, INDEX, ByteArray(32))
        assertEquals(1, status.pending)
    }

    @Test
    fun theDigestIgnoresChaffAndTheLogPointer() {
        val db = MetadataDb.empty()
        val before = BackupFile.contentDigest(db)
        db.ownedChaff += ByteArray(16) { 1 }
        db.logBlobId = ByteArray(16) { 2 }
        assertArrayEquals(before, BackupFile.contentDigest(db))
        assertEquals("the chaff list is put back", 1, db.ownedChaff.size)
        assertArrayEquals("the log pointer is put back", ByteArray(16) { 2 }, db.logBlobId)
    }

}
