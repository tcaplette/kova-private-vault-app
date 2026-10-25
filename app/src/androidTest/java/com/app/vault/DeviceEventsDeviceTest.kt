package com.app.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P2-5 on a device — a pending device event flushing into the log and the digest at
 * the next unlock, the app-data-clear case flushing at creation instead, and the
 * warn-never-block rule holding under every event at once.
 *
 * The detection arithmetic is `DeviceEventsTest`; this drives the recording side
 * through a real vault, so it needs the native library and a `Context`.
 */
@RunWith(AndroidJUnit4::class)
class DeviceEventsDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle

    private companion object {
        const val PASS = "the-device-events-passphrase"
    }

    private fun pass() = PASS.toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        VaultLifecycle.lockNow()
        TamperLog.clearPendingDeviceEventsForTest()
        DeviceEvents.resetForTest(ctx)
        root = File(ctx.filesDir, "devev-${System.nanoTime()}").apply { mkdirs() }
        store = BlobStore(File(root, "blobs"))
        vault = VaultLifecycle(File(root, "vault.slots"), store)
    }

    @After
    fun tearDown() {
        VaultLifecycle.lockNow()
        TamperLog.clearPendingDeviceEventsForTest()
        DeviceEvents.resetForTest(ctx)
        root.deleteRecursively()
    }

    private fun logCodes(): List<Int> {
        val vmk = VaultSession.vmk()!!
        val db = MetadataDb.load(store, VaultSession.metadataBlobId()!!, vmk)
        return TamperLog.scan(store, db.logBlobId!!, vmk).entries.map { it.event }
    }

    // --- 5.7 flush at unlock, before UNLOCK_SUCCEEDED, named in the digest ------

    @Test fun aDeviceEventFlushesAtUnlockAndIsNamedInTheDigest() {
        assertTrue(vault.create(pass()).opened)
        VaultLifecycle.lockNow()

        TamperLog.recordDeviceEvent(TamperLog.DEV_OPTIONS_ENABLED)
        assertTrue(vault.unlockAndOpen(pass()))

        val codes = logCodes()
        assertTrue("event not in log", codes.contains(TamperLog.DEV_OPTIONS_ENABLED))
        // Before the unlock-succeeded entry it belongs to.
        assertTrue(
            "device event must precede its unlock-succeeded entry",
            codes.indexOf(TamperLog.DEV_OPTIONS_ENABLED) < codes.lastIndexOf(TamperLog.UNLOCK_SUCCEEDED),
        )
        assertTrue(
            "digest did not name the event: ${TamperLog.digest}",
            TamperLog.digest.any { it.contains("Developer options") },
        )
        // Reported once: the next unlock, with nothing new, says nothing about it.
        VaultLifecycle.lockNow()
        assertTrue(vault.unlockAndOpen(pass()))
        assertTrue(TamperLog.digest.none { it.contains("Developer options") })
    }

    // --- 5.7 app-data-clear flushes at creation --------------------------------

    @Test fun theDataClearEventFlushesAtCreation() {
        TamperLog.recordDeviceEvent(TamperLog.DATA_CLEAR_DETECTED)
        assertTrue(vault.create(pass()).opened)
        assertTrue("data-clear event not in the created vault's log", logCodes().contains(TamperLog.DATA_CLEAR_DETECTED))
    }

    // --- 5.8 warn-never-block --------------------------------------------------

    @Test fun everyEventAtOnceDoesNotBlock() {
        assertTrue(vault.create(pass()).opened)
        VaultLifecycle.lockNow()

        listOf(
            TamperLog.DEV_OPTIONS_ENABLED, TamperLog.ROOT_STATUS_CHANGED,
            TamperLog.DATA_CLEAR_DETECTED, TamperLog.NEW_DEVICE_RESTORE, TamperLog.CLOCK_ROLLBACK,
        ).forEach { TamperLog.recordDeviceEvent(it) }

        // Unlock, import and export all behave exactly as on a clean device.
        assertTrue(vault.unlockAndOpen(pass()))
        val vmk = VaultSession.vmk()!!
        val id = VaultSession.metadataBlobId()!!
        val before = MetadataDb.load(store, id, vmk).items.size
        // A trivial in-memory item through the direct path, to prove writes still work.
        val item = store.writeItem("hello".byteInputStream(), vmk)
        val db = MetadataDb.load(store, id, vmk)
        db.items += VaultItem(item.blobIds, item.plaintextLength, null, "x.txt", null, null, System.currentTimeMillis(), emptyList())
        db.save(store, id, vmk)
        assertEquals(before + 1, MetadataDb.load(store, id, vmk).items.size)

        // All five are named in the digest, one line each.
        listOf("Developer options", "root status", "cleared", "different device", "clock moved")
            .forEach { phrase ->
                assertTrue("digest missing '$phrase': ${TamperLog.digest}", TamperLog.digest.any { it.contains(phrase) })
            }
    }
}
