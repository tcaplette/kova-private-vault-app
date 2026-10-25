package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of `intruder-capture-record` that need no device.
 *
 * The orientation, which is arithmetic; the timeline's rows, which are built from log
 * entries. Both are places where being wrong is invisible in review and obvious in
 * the hand — a photograph on its side, or a line that names the wrong time.
 */
class IntruderRecordTest {

    // --- the orientation ------------------------------------------------------

    @Test
    fun anIntruderStillUsesTheSameAngleTheInAppCameraWould() {
        // The capture request passes deviceRotation = 0 because it fires with no
        // activity on screen. This is that call, and it must agree with the camera's
        // own front-lens case rather than being a second implementation.
        listOf(0, 90, 180, 270).forEach { sensor ->
            assertEquals(
                "sensor $sensor",
                VaultCamera.jpegOrientation(sensor, deviceRotation = 0, front = true),
                sensor,
            )
        }
    }

    @Test
    fun theFrontLensSubtractsWhereTheBackLensAdds() {
        // The sign that is wrong in half of every hand-rolled version of this, and
        // the reason intruder capture calls the camera's function instead of its own.
        assertEquals(180, VaultCamera.jpegOrientation(270, deviceRotation = 90, front = true))
        assertEquals(0, VaultCamera.jpegOrientation(270, deviceRotation = 90, front = false))
    }

    // --- the timeline's rows — `intruder-captures-at-home` D4 ----------------------------------

    private var next = 0

    private fun entry(at: Long, event: Int = TamperLog.INTRUDER_CAPTURED, payload: ByteArray = ByteArray(0)) =
        LogEntry(index = next++, timestamp = at, event = event, payload = payload)

    private fun capture(at: Long, name: String) = VaultItem(
        blobIds = listOf(ByteArray(16)),
        plaintextLength = 1L,
        thumb = null,
        filename = name,
        album = null,
        createdAt = at,
        importedAt = at,
        tags = emptyList(),
    )

    private fun left(at: Long, outcome: Int, mode: Capture.Mode? = Capture.Mode.PHOTO, vault: String? = null) =
        entry(at + 1, TamperLog.INTRUDER_LEFT, TamperLog.Left.leftPayload(at, outcome, mode, vault))

    @Test
    fun aRowCarriesItsItemAndTheModeFromItsExtension() {
        val at = 1_700_000_000_000L
        fun modeOf(ext: String) = CaptureLog.rows(listOf(entry(at)), listOf(capture(at, "intruder-$at.$ext"))).single()
        assertEquals(Capture.Mode.PHOTO, modeOf("jpg").mode)
        assertEquals(Capture.Mode.AUDIO, modeOf("aac").mode)
        assertEquals(Capture.Mode.VIDEO, modeOf("ts").mode)
        assertEquals("intruder-$at.jpg", modeOf("jpg").item?.filename)
        assertEquals(null, modeOf("jpg").left)
    }

    @Test
    fun aCaptureIsMatchedToTheNearestFilenameNotAnyOther() {
        // Two captures a minute apart. Each row must hold its own file, not the other's — the
        // match is on the millisecond the filename carries.
        val first = 1_700_000_000_000L
        val second = first + 60_000L
        val rows = CaptureLog.rows(
            listOf(entry(first), entry(second)),
            listOf(capture(first, "intruder-$first.jpg"), capture(second, "intruder-$second.aac")),
        )
        assertEquals(2, rows.size)
        // Newest first.
        assertEquals("intruder-$second.aac", rows[0].item?.filename)
        assertEquals("intruder-$first.jpg", rows[1].item?.filename)
    }

    @Test
    fun aCaptureThatHasLeftNeverBorrowsItsNeighboursFile() {
        // A photo and an audio clip of one attempt, three seconds apart — inside the tolerance.
        // The photo has been moved; its row must not claim the clip that is still held.
        val photo = 1_700_000_000_000L
        val audio = photo + 3_000L
        val rows = CaptureLog.rows(
            listOf(entry(photo), entry(audio), left(photo, TamperLog.LEFT_MOVED, vault = "Personal")),
            listOf(capture(audio, "intruder-$audio.aac")),
        )
        assertEquals("intruder-$audio.aac", rows[0].item?.filename)
        assertEquals(null, rows[1].item)
        assertEquals(TamperLog.LEFT_MOVED, rows[1].left?.outcome)
    }

    @Test
    fun theNewestCaptureIsFirst() {
        val rows = CaptureLog.rows(listOf(entry(1_000_000_000_000L), entry(1_700_000_000_000L)), emptyList())
        assertEquals(listOf(1_700_000_000_000L, 1_000_000_000_000L), rows.map { it.takenAt })
    }

    @Test
    fun onlyCaptureEntriesAreRows() {
        val rows = CaptureLog.rows(
            listOf(
                entry(1_700_000_000_000L, event = TamperLog.UNLOCK_FAILED),
                entry(1_700_000_100_000L, event = TamperLog.PANIC_TRIGGERED),
                entry(1_700_000_200_000L),
                left(1_700_000_200_000L, TamperLog.LEFT_DELETED),
            ),
            emptyList(),
        )
        assertEquals("the timeline is about captures and nothing else", 1, rows.size)
    }

    @Test
    fun aMovedCaptureKeepsItsRowAndNamesTheVault() {
        val at = 1_700_000_000_000L
        val row = CaptureLog.rows(listOf(entry(at), left(at, TamperLog.LEFT_MOVED, vault = "Personal")), emptyList()).single()
        assertEquals(at, row.takenAt)
        assertEquals(null, row.item)
        assertEquals(TamperLog.LEFT_MOVED, row.left?.outcome)
        assertEquals("Personal", row.left?.vaultName)
        assertEquals("the mode outlives the item", Capture.Mode.PHOTO, row.mode)
    }

    @Test
    fun anExportedCaptureKeepsItsRow() {
        val at = 1_700_000_000_000L
        val row = CaptureLog.rows(listOf(entry(at), left(at, TamperLog.LEFT_EXPORTED, Capture.Mode.VIDEO)), emptyList()).single()
        assertEquals(TamperLog.LEFT_EXPORTED, row.left?.outcome)
        assertEquals(null, row.left?.vaultName)
        assertEquals(Capture.Mode.VIDEO, row.mode)
    }

    @Test
    fun aDeletedCaptureKeepsItsRow() {
        // **The property that makes this evidence.** The rows are built from the log's entries,
        // so deleting the media does not delete the record of it.
        val at = 1_700_000_000_000L
        val row = CaptureLog.rows(listOf(entry(at), left(at, TamperLog.LEFT_DELETED, Capture.Mode.AUDIO)), emptyList()).single()
        assertEquals(TamperLog.LEFT_DELETED, row.left?.outcome)
        assertEquals(Capture.Mode.AUDIO, row.mode)
    }

    @Test
    fun theLeftPayloadRoundTripsALongestName() {
        val name = "ü".repeat(47)
        assertEquals("the longest name a keyring holds", VaultKeyring.MAX_NAME_BYTES, name.toByteArray(Charsets.UTF_8).size)
        val payload = TamperLog.Left.leftPayload(1_700_000_000_123L, TamperLog.LEFT_MOVED, Capture.Mode.AUDIO, name)
        assertTrue("fits a log entry", payload.size <= TamperLog.PAYLOAD_MAX)
        val read = TamperLog.Left.readLeft(payload)!!
        assertEquals(1_700_000_000_123L, read.takenAt)
        assertEquals(TamperLog.LEFT_MOVED, read.outcome)
        assertEquals(Capture.Mode.AUDIO, read.mode)
        assertEquals(name, read.vaultName)
        assertEquals("too short to be one", null, TamperLog.Left.readLeft(ByteArray(9)))
    }

    // --- `cover-fingerprint-dots` D6a, `cover-hold-dots` D7: the stage record and the log's words

    @Test
    fun theStageRecordReadsBackWhatWasWritten() {
        val media = byteArrayOf(9, 8, 7)
        val plain = CaptureRecord.prepend(
            CaptureRecord.Stage(CaptureRecord.STAGE_COVER_PATTERN, CaptureRecord.FLAG_DOTS_SHOWN, CaptureRecord.COVER_MUSIC),
            1_700_000_000_123L,
            media,
        )
        assertEquals(CaptureRecord.BYTES + media.size, plain.size)
        assertEquals("the version leads", CaptureRecord.VERSION, plain[0].toInt())
        val read = CaptureRecord.read(plain)!!
        assertEquals(CaptureRecord.STAGE_COVER_PATTERN, read.stage.stage)
        assertEquals(CaptureRecord.FLAG_DOTS_SHOWN, read.stage.flags)
        assertEquals(CaptureRecord.COVER_MUSIC, read.stage.cover)
        assertEquals(1_700_000_000_123L, read.takenAt)
        assertEquals(listOf<Byte>(9, 8, 7), plain.drop(CaptureRecord.BYTES))
    }

    /** `cover-hold-dots` D7. Stage 1, the cover's fingerprint, is retired: nothing can write it. */
    @Test
    fun stageOneIsNotWritten() {
        val stages = CaptureRecord::class.java.declaredFields
            .filter { it.name.startsWith("STAGE_") }
            .map { it.isAccessible = true; it.getInt(null) }
        assertEquals(setOf(2, 3, 4, 5), stages.toSet())
    }

    @Test
    fun aShortOrUnknownRecordIsNotACaptureOfOurs() {
        assertEquals(null, CaptureRecord.read(ByteArray(CaptureRecord.BYTES - 1) { 1 }))
        val plain = CaptureRecord.prepend(CaptureRecord.Stage(CaptureRecord.STAGE_PASSPHRASE), 1L, ByteArray(4))
        plain[0] = 2
        assertEquals("another version", null, CaptureRecord.read(plain))
        // What an earlier build sealed: a JPEG's first byte, where the version would be.
        assertEquals(null, CaptureRecord.read(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(20)))
    }

    @Test
    fun thePayloadIsThreeBytesSoTheCountStaysOne() {
        val payload = CaptureRecord.Stage(
            CaptureRecord.STAGE_COVER_PATTERN, CaptureRecord.FLAG_DOTS_SHOWN, CaptureRecord.COVER_RECORDER,
        ).payload()
        assertEquals(3, payload.size)
        assertEquals(1, LogEntry(0, 0L, TamperLog.INTRUDER_CAPTURED, payload).count)
    }

    private fun staged(at: Long, stage: Int, flags: Int = 0, cover: Int = CaptureRecord.COVER_NONE) =
        LogEntry(next++, at, TamperLog.INTRUDER_CAPTURED, CaptureRecord.Stage(stage, flags, cover).payload())

    @Test
    fun eachRowSaysWhereTheAttemptFailed() {
        val t = 1_700_000_000_000L
        val rows = CaptureLog.rows(
            listOf(
                staged(t + 1_000, CaptureRecord.STAGE_COVER_PATTERN, cover = CaptureRecord.COVER_RECORDER),
                staged(t + 2_000, CaptureRecord.STAGE_COVER_PATTERN, CaptureRecord.FLAG_DOTS_SHOWN, CaptureRecord.COVER_MUSIC),
                staged(t + 3_000, CaptureRecord.STAGE_COVER_PATTERN, CaptureRecord.FLAG_LIMITED, CaptureRecord.COVER_MUSIC),
                staged(
                    t + 4_000, CaptureRecord.STAGE_COVER_PATTERN,
                    CaptureRecord.FLAG_LIMITED or CaptureRecord.FLAG_DOTS_SHOWN, CaptureRecord.COVER_RECORDER,
                ),
                staged(t + 5_000, CaptureRecord.STAGE_UNLOCK_BIOMETRIC),
                staged(t + 6_000, CaptureRecord.STAGE_PASSPHRASE),
            ),
            emptyList(),
        )
        val lines = rows.map { CaptureLog.where(TestWords, it, com.app.vault.ui.BiometricLayout.Both) }
        assertTrue("no line names a fingerprint on the cover", lines.none { it.contains("matching fingerprint") })
        // Newest first.
        listOf(
            "Unlock screen: wrong passphrase",
            "Unlock screen: wrong face or fingerprint",
            "Voice recorder: pattern tried during the lockout on the shown dots",
            "Music player: pattern tried during the lockout",
            "Music player: wrong pattern on the shown dots",
            "Voice recorder: wrong pattern",
        ).forEachIndexed { i, where -> assertEquals("row $i", where, lines[i]) }
    }

    /**
     * `calculator-dots-methods` D5. The calculator has its own cover code, and a capture on its
     * shown dots names it — wrong, and tried during the lockout.
     */
    @Test
    fun theCalculatorIsNamedOnItsShownDots() {
        assertEquals(3, CaptureRecord.COVER_CALCULATOR)
        assertEquals(CaptureRecord.COVER_CALCULATOR, CaptureRecord.coverCode(Cover.Calculator))
        assertEquals(CaptureRecord.COVER_NONE, CaptureRecord.coverCode(null))
        val t = 1_700_000_000_000L
        val rows = CaptureLog.rows(
            listOf(
                staged(t + 1_000, CaptureRecord.STAGE_COVER_PATTERN, CaptureRecord.FLAG_DOTS_SHOWN, CaptureRecord.COVER_CALCULATOR),
                staged(
                    t + 2_000, CaptureRecord.STAGE_COVER_PATTERN,
                    CaptureRecord.FLAG_LIMITED or CaptureRecord.FLAG_DOTS_SHOWN, CaptureRecord.COVER_CALCULATOR,
                ),
            ),
            emptyList(),
        )
        val lines = rows.map { CaptureLog.where(TestWords, it, com.app.vault.ui.BiometricLayout.Fingerprint) }
        assertEquals(
            listOf(
                "Calculator: pattern tried during the lockout on the shown dots",
                "Calculator: wrong pattern on the shown dots",
            ),
            lines,
        )
    }

    @Test
    fun anEntryWithNoPayloadReadsAsThePassphrase() {
        val row = CaptureLog.rows(listOf(entry(1_700_000_000_000L)), emptyList()).single()
        assertEquals(
            "Unlock screen: wrong passphrase",
            CaptureLog.where(TestWords, row, com.app.vault.ui.BiometricLayout.Unnamed),
        )
    }

    /**
     * The spec's own example: a wrong pattern on the hidden dots at 14:01 and one on the dots the
     * hold brought up at 14:02, read at 18:00 — each row carries the moment taken, never the moment
     * the keyring was opened.
     */
    @Test
    fun theRowCarriesTheMomentTakenNotTheUnlock() {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        val at1401 = stamp.parse("2026-10-04 14:01")!!.time
        val at1402 = stamp.parse("2026-10-04 14:02")!!.time
        val rows = CaptureLog.rows(
            listOf(
                staged(at1401, CaptureRecord.STAGE_COVER_PATTERN, cover = CaptureRecord.COVER_MUSIC),
                staged(at1402, CaptureRecord.STAGE_COVER_PATTERN, CaptureRecord.FLAG_DOTS_SHOWN, CaptureRecord.COVER_MUSIC),
            ),
            emptyList(),
        )
        assertEquals(listOf(at1402, at1401), rows.map { it.takenAt })
        val layout = com.app.vault.ui.BiometricLayout.Unnamed
        assertEquals("Music player: wrong pattern on the shown dots", CaptureLog.where(TestWords, rows[0], layout))
        assertEquals("Music player: wrong pattern", CaptureLog.where(TestWords, rows[1], layout))
    }

    // --- `per-vault-intruder-capture` --------------------------------------------------------

    @Test
    fun aVaultPassphraseRowReadsAsTheVaultsOwnSheet() {
        val row = CaptureLog.rows(listOf(staged(1_700_000_000_000L, CaptureRecord.STAGE_VAULT_PASSPHRASE)), emptyList()).single()
        assertEquals(5, CaptureRecord.STAGE_VAULT_PASSPHRASE)
        assertEquals(
            "Vault passphrase: wrong passphrase",
            CaptureLog.where(TestWords, row, com.app.vault.ui.BiometricLayout.Unnamed),
        )
    }

    @Test
    fun theBlankFlagIsSealedReadAndLabelled() {
        assertEquals("bit 2", 4, CaptureRecord.FLAG_BLANK)
        val stage = CaptureRecord.Stage(CaptureRecord.STAGE_VAULT_PASSPHRASE).blankIf(true)
        val read = CaptureRecord.read(CaptureRecord.prepend(stage, 1L, ByteArray(2)))!!
        assertEquals(CaptureRecord.FLAG_BLANK, read.stage.flags)
        assertEquals("not blank leaves the flags alone", 0, CaptureRecord.Stage(CaptureRecord.STAGE_PASSPHRASE).blankIf(false).flags)

        val t = 1_700_000_000_000L
        val rows = CaptureLog.rows(
            listOf(
                staged(t, CaptureRecord.STAGE_PASSPHRASE, CaptureRecord.FLAG_BLANK),
                staged(t + 1_000, CaptureRecord.STAGE_PASSPHRASE),
            ),
            emptyList(),
        )
        assertEquals(listOf(false, true), rows.map { it.blank })
        assertEquals("Came out blank", TestWords.get(R.string.intruder_blank))
        assertEquals("Came out silent", TestWords.get(R.string.intruder_silent))
    }

    @Test
    fun thePermissionPayloadRoundTrips() {
        for (permission in listOf(TamperLog.PERMISSION_CAMERA, TamperLog.PERMISSION_MICROPHONE)) {
            for (state in listOf(TamperLog.PERMISSION_LOST, TamperLog.PERMISSION_RESTORED)) {
                val payload = TamperLog.Permission.permissionPayload(permission, state)
                assertEquals("two bytes, so the count stays one", 1, LogEntry(0, 0L, TamperLog.CAPTURE_PERMISSION, payload).count)
                val read = TamperLog.Permission.readPermission(payload)!!
                assertEquals(permission, read.permission)
                assertEquals(state, read.state)
            }
        }
        assertEquals(0x48, TamperLog.CAPTURE_PERMISSION)
        assertEquals("not one", null, TamperLog.Permission.readPermission(byteArrayOf(3, 0)))
        assertEquals("not one", null, TamperLog.Permission.readPermission(ByteArray(3)))
    }

    @Test
    fun aPermissionEntryIsARowNamingTheTimelinesModesItServes() {
        val t = 1_700_000_000_000L
        val camera = entry(t + 2_000, TamperLog.CAPTURE_PERMISSION, TamperLog.Permission.permissionPayload(TamperLog.PERMISSION_CAMERA, TamperLog.PERMISSION_LOST))
        val mic = entry(t + 3_000, TamperLog.CAPTURE_PERMISSION, TamperLog.Permission.permissionPayload(TamperLog.PERMISSION_MICROPHONE, TamperLog.PERMISSION_RESTORED))
        val rows = CaptureLog.rows(
            listOf(staged(t, CaptureRecord.STAGE_PASSPHRASE), camera, mic),
            emptyList(),
            enabledModes = setOf(Capture.Mode.VIDEO, Capture.Mode.PHOTO),
        )
        assertEquals("newest first, among the captures", listOf(t + 3_000, t + 2_000, t), rows.map { it.takenAt })
        assertEquals(TamperLog.PERMISSION_MICROPHONE, rows[0].permission?.permission)
        assertEquals(listOf(Capture.Mode.VIDEO), rows[0].permissionModes)
        assertEquals(TamperLog.PERMISSION_LOST, rows[1].permission?.state)
        assertEquals(listOf(Capture.Mode.PHOTO, Capture.Mode.VIDEO), rows[1].permissionModes)
        assertEquals("a permission row holds no capture", null, rows[1].item)
        assertEquals(null, rows[2].permission)
        assertEquals(
            "Noticed 18:00 · photo and video stopped",
            TestWords.get(R.string.intruder_permission_stopped, "18:00", "photo and video"),
        )
    }

    // The digest's own removal is asserted on the device, in `CaptureDeviceTest`:
    // `TamperLog` initialises libsodium through JNA, which has no native library on
    // the JVM, so `digestOf` cannot be called from here at all.
}
