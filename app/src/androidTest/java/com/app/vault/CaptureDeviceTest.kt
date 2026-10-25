package com.app.vault

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/**
 * P2-4 end to end, on a device — `intruder-captures-at-home`. A capture pending against a locked
 * keyring becoming an item and a log entry in that keyring's capture store when the keyring next
 * opens, with no vault opened; nothing of it reaching a keyring that is not the destination; and no
 * vault's index or log ever holding one.
 *
 * The keyring opening is driven through [CaptureStore.takeIn], the one function the activity's
 * keyring-open effect calls, so this holds the code the app runs rather than a copy of it.
 *
 * The camera and microphone are exercised separately and best-effort ([theEngine…]);
 * everything an emulator cannot reliably produce a real frame for is driven with a
 * synthetic sealed capture, which is exactly what [MainActivity.fireIntruderCaptures]
 * produces once [IntruderCapture] has handed back the bytes.
 */
@RunWith(AndroidJUnit4::class)
class CaptureDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var slotsFile: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle
    private val rng = SecureRandom()

    private companion object {
        const val PASS_A = "the-destination-keyring-passphrase"
        const val PASS_B = "the-other-keyring-passphrase"
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        VaultLifecycle.lockNow()
        Capture.resetForTest(ctx)
        root = File(ctx.filesDir, "capture-dev-${System.nanoTime()}").apply { mkdirs() }
        slotsFile = File(root, "vault.slots")
        store = BlobStore(File(root, "blobs"))
        vault = VaultLifecycle(slotsFile, store)
    }

    @After
    fun tearDown() {
        VaultLifecycle.lockNow()
        Capture.resetForTest(ctx)
        root.deleteRecursively()
    }

    /** Enrols the open keyring as the destination and turns [mode] on — what the sheet does. */
    private fun enrol(mode: Capture.Mode) {
        val key = VaultKeyringSession.captureStoreKey()!!
        val kp = VaultKeys.captureKeypair(key)
        Capture.setDestination(ctx, kp.publicKey)
        Capture.setEnabled(ctx, mode, true)
        kp.zero()
        key.fill(0)
    }

    /**
     * What the keyring opening does — [CaptureStore.takeIn], on the open keyring. Returns the
     * store's index after it, or null when the keyring took nothing in.
     */
    private fun keyringOpens(into: BlobStore = store): MetadataDb? {
        val key = VaultKeyringSession.captureStoreKey()!!
        return try {
            CaptureStore.takeIn(ctx, into, key)?.let { (s, _) -> MetadataDb.load(into, s.metadataBlobId, key) }
        } finally {
            key.fill(0)
        }
    }

    /** The open keyring's capture store's log. */
    private fun storeLog(db: MetadataDb, into: BlobStore = store): List<LogEntry> {
        val key = VaultKeyringSession.captureStoreKey()!!
        return try {
            TamperLog.scan(into, db.logBlobId!!, key).entries
        } finally {
            key.fill(0)
        }
    }

    /**
     * What the activity does with the camera's bytes, minus the camera — sealed behind the stage
     * record `cover-fingerprint-dots` D6a puts ahead of the media, as a failed passphrase's.
     */
    private fun stageCapture(
        mode: Capture.Mode,
        bytes: ByteArray,
        stage: CaptureRecord.Stage = CaptureRecord.Stage(CaptureRecord.STAGE_PASSPHRASE),
        takenAt: Long = System.currentTimeMillis(),
    ): ByteArray {
        val sealed = Capture.seal(Capture.destinationKey(ctx)!!, CaptureRecord.prepend(stage, takenAt, bytes))
        val blobId = Capture.newBlobId()
        store.writeSealed(blobId, sealed)
        Capture.add(ctx, mode, blobId, sealed.size, store)
        return blobId
    }

    /** No item and no capture entry in the vault at [position] — opened, read, and left. */
    private fun assertNoVaultHoldsACapture(position: Int = 0) {
        val opened = VaultKeyringSession.openVault(position)!!
        try {
            val db = MetadataDb.load(store, opened.metadataBlobId, opened.vmk)
            assertTrue("a vault holds a capture item", db.items.none { it.filename.startsWith("intruder-") })
            val entries = db.logBlobId?.let { TamperLog.scan(store, it, opened.vmk).entries }.orEmpty()
            assertTrue(
                "a vault's log holds a capture entry",
                entries.none { it.event == TamperLog.INTRUDER_CAPTURED || it.event == TamperLog.INTRUDER_LEFT },
            )
        } finally {
            VaultKeyringSession.leaveVault()
        }
    }

    // --- a capture becomes a store item when the keyring opens ---------------------------

    @Test fun aPendingCaptureBecomesAStoreItemWhenTheKeyringOpens() {
        assertTrue(vault.create(pass(PASS_A)).opened)
        enrol(Capture.Mode.PHOTO)
        val photo = ByteArray(24_000).also { rng.nextBytes(it) }
        val pendingBlob = stageCapture(Capture.Mode.PHOTO, photo)
        VaultLifecycle.lockNow()

        // The keyring, and no vault.
        assertTrue(vault.unlock(pass(PASS_A)).opened)
        val db = keyringOpens()!!
        assertFalse("no vault may be opened to take captures in", VaultSession.isUnlocked)

        val item = db.items.single()
        assertTrue(item.filename.startsWith("intruder-") && item.filename.endsWith(".jpg"))
        assertEquals("captures land at the top level of the store", null, item.folderId)
        val key = VaultKeyringSession.captureStoreKey()!!
        val readBack = store.reader(item.blobIds, key, item.plaintextLength).use {
            it.readRange(0, item.plaintextLength.toInt())
        }
        key.fill(0)
        assertTrue("capture content did not survive", photo.contentEquals(readBack))
        assertFalse("pending blob still present", store.fileOf(pendingBlob).exists())
        assertEquals(0, Capture.pending(ctx, Capture.Mode.PHOTO).size)

        // One 0x41 in the store's own log.
        assertEquals(1, storeLog(db).count { it.event == TamperLog.INTRUDER_CAPTURED })
        assertTrue(TamperLog.INTRUDER_CAPTURED in 0x40..0x7F && TamperLog.INTRUDER_LEFT in 0x40..0x7F)

        // The store is no vault: the list is what it was, and no vault holds the capture.
        assertEquals(1, VaultKeyringSession.vaults().size)
        assertNoVaultHoldsACapture()
        assertTrue(
            "the digest must not name captures: ${TamperLog.digest}",
            TamperLog.digest.none { it.contains("intruder capture", ignoreCase = true) },
        )
    }

    // --- the unseen mark -----------------------------------------------------------------

    @Test fun aCaptureIsUnseenUntilTheMarkMoves() {
        assertTrue(vault.create(pass(PASS_A)).opened)
        enrol(Capture.Mode.PHOTO)
        stageCapture(Capture.Mode.PHOTO, "a-face".toByteArray())
        VaultLifecycle.lockNow()
        assertTrue(vault.unlock(pass(PASS_A)).opened)
        var db = keyringOpens()!!

        val newest = db.items.maxOf { it.importedAt }
        assertEquals("a fresh store has seen nothing", null, db.capturesSeenAt)
        assertTrue("the capture must be newer than the mark", newest > (db.capturesSeenAt ?: 0L))

        // What opening the Intruder capture screen does.
        val key = VaultKeyringSession.captureStoreKey()!!
        val id = VaultKeys.metadataBlobId(key)
        MetadataDb.commit(store, id, key) { it.capturesSeenAt = newest; it }
        db = MetadataDb.load(store, id, key)
        key.fill(0)
        assertFalse("nothing is unseen once the mark has passed it", newest > (db.capturesSeenAt ?: 0L))
    }

    @Test fun theMarkSurvivesALockAndUnlock() {
        assertTrue(vault.create(pass(PASS_A)).opened)
        enrol(Capture.Mode.PHOTO)
        stageCapture(Capture.Mode.PHOTO, "a-face".toByteArray())
        VaultLifecycle.lockNow()
        assertTrue(vault.unlock(pass(PASS_A)).opened)
        keyringOpens()!!
        VaultKeyringSession.captureStoreKey()!!.let { key ->
            MetadataDb.commit(store, VaultKeys.metadataBlobId(key), key) { it.capturesSeenAt = 1_700_000_000_000L; it }
            key.fill(0)
        }

        // In the store's index, so it comes back with the keyring and not with the process.
        VaultLifecycle.lockNow()
        assertTrue(vault.unlock(pass(PASS_A)).opened)
        assertEquals(1_700_000_000_000L, keyringOpens()!!.capturesSeenAt)
    }

    // --- only the destination keyring takes captures in --------------------------------------

    @Test fun aKeyringThatIsNotTheDestinationLeavesCapturesPending() {
        // Keyring A is the destination; the capture is sealed to its store.
        assertTrue(vault.create(pass(PASS_A)).opened)
        enrol(Capture.Mode.PHOTO)
        stageCapture(Capture.Mode.PHOTO, ByteArray(8_000).also { rng.nextBytes(it) })

        // Keyring B beside it, preserving A.
        assertTrue(vault.create(pass(PASS_B), preserve = listOf(pass(PASS_A))).opened)
        VaultLifecycle.lockNow()

        // Opening B takes nothing in, makes no store, and leaves the capture pending.
        assertTrue(vault.unlock(pass(PASS_B)).opened)
        assertEquals(null, keyringOpens())
        VaultKeyringSession.captureStoreKey()!!.let { key ->
            assertFalse("B must not get a store", store.fileOf(VaultKeys.metadataBlobId(key)).exists())
            key.fill(0)
        }
        assertEquals("B must leave the capture pending", 1, Capture.pending(ctx, Capture.Mode.PHOTO).size)
        VaultLifecycle.lockNow()

        // Opening A takes it in.
        assertTrue(vault.unlock(pass(PASS_A)).opened)
        assertEquals(1, keyringOpens()!!.items.size)
        assertEquals(0, Capture.pending(ctx, Capture.Mode.PHOTO).size)
    }

    @Test fun openingAVaultTakesNothingIn() {
        assertTrue(vault.create(pass(PASS_A)).opened)
        enrol(Capture.Mode.PHOTO)
        stageCapture(Capture.Mode.PHOTO, ByteArray(4_000).also { rng.nextBytes(it) })
        VaultLifecycle.lockNow()

        // A vault opened as the app opens one, with no keyring-open ingest.
        assertTrue(vault.unlockAndOpen(pass(PASS_A)))
        assertEquals("opening a vault must leave the capture pending", 1, Capture.pending(ctx, Capture.Mode.PHOTO).size)
        VaultKeyringSession.leaveVault()
        assertNoVaultHoldsACapture()
    }

    // --- 7.4: FIFO cap, per mode, through the real store -----------------------

    @Test fun theCapHoldsAtFiftyPerMode() {
        assertTrue(vault.create(pass(PASS_A)).opened)
        enrol(Capture.Mode.PHOTO)
        repeat(Capture.PER_MODE_CAP + 5) {
            stageCapture(Capture.Mode.PHOTO, ByteArray(2_000).also { b -> rng.nextBytes(b) })
        }
        assertEquals(Capture.PER_MODE_CAP, Capture.pending(ctx, Capture.Mode.PHOTO).size)
    }

    // --- 7.1: core is unaffected when capture is off ---------------------------

    @Test fun withCaptureOffAFailedUnlockRecordsNothing() {
        assertTrue(vault.create(pass(PASS_A)).opened)
        VaultLifecycle.lockNow()
        // No enrolment. A wrong passphrase is a failed unlock and must stage nothing.
        assertFalse(vault.unlockAndOpen(pass("this-is-the-wrong-passphrase")))
        Capture.Mode.entries.forEach { assertEquals(0, Capture.pending(ctx, it).size) }
        // A correct unlock takes nothing in — no destination — and makes no store.
        assertTrue(vault.unlock(pass(PASS_A)).opened)
        assertEquals(null, keyringOpens())
        assertTrue(TamperLog.digest.none { it.contains("intruder capture", ignoreCase = true) })
    }

    // --- 7.5: nothing plaintext survives ingest --------------------------------

    @Test fun nothingPlaintextIsLeftAfterIngest() {
        assertTrue(vault.create(pass(PASS_A)).opened)
        enrol(Capture.Mode.PHOTO)
        // A recognisable marker: if it survives in the clear anywhere, the scan finds it.
        val marker = "PLAINTEXT-CAPTURE-MARKER-9f3a".toByteArray()
        val photo = ByteArray(6_000).also { rng.nextBytes(it) }
        marker.copyInto(photo)
        stageCapture(Capture.Mode.PHOTO, photo)
        VaultLifecycle.lockNow()
        assertTrue(vault.unlock(pass(PASS_A)).opened)
        keyringOpens()!!

        var found = false
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            if (f.readBytes().let { indexOf(it, marker) >= 0 }) found = true
        }
        assertFalse("capture plaintext found on disk in ${root}", found)
    }

    // --- 7.6 / engine: capture returns null without a permission or camera -----

    @Test fun theEngineDoesNotThrowAndHonoursPermissions() {
        // On an emulator the front camera may or may not exist and the permission may
        // or may not be granted; either way capture must return bytes-or-null and
        // never throw. When a permission is absent, it is null.
        if (!IntruderCapture.audioPermitted(ctx)) {
            assertTrue(IntruderCapture.capture(ctx, Capture.Mode.AUDIO) == null)
        }
        if (!IntruderCapture.cameraPermitted(ctx)) {
            assertTrue(IntruderCapture.capture(ctx, Capture.Mode.PHOTO) == null)
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // --- `cover-fingerprint-dots` D6: every stage of a live unlock -------------------
    //
    // The real activity, the emulator's camera and its sensor — so these run only with
    // `-e finger relay` and the host relaying the touches `LiveCover` asks for, with finger 1
    // enrolled and finger 2 not. Everything else they need — a vault in the app's storage among
    // it, since the unlock screen is only reached where one exists — they make, and remove after.

    private fun showing(text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            compose.waitForIdle()
            if (condition()) return
            check(SystemClock.elapsedRealtime() < until) { "timed out waiting: $what" }
            SystemClock.sleep(200)
        }
    }

    private fun photos() = Capture.pending(ctx, Capture.Mode.PHOTO).size

    private fun drawPattern(target: androidx.compose.ui.test.SemanticsNodeInteraction, vararg nodes: Int) {
        target.performTouchInput {
            fun at(n: Int) = Offset((n % 3 + 0.5f) * width / 3f, (n / 3 + 0.5f) * height / 3f)
            down(at(nodes[0]))
            nodes.drop(1).forEach { moveTo(at(it)) }
            up()
        }
        compose.waitForIdle()
    }

    /** Photo capture on, sealed to the open keyring, with the camera granted as setup would have. */
    private fun enrolLivePhoto() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(ctx.packageName, android.Manifest.permission.CAMERA)
        enrol(Capture.Mode.PHOTO)
    }

    private val musicPattern = Trigger.Pattern(listOf(0, 4, 8, 2, 6))

    private fun assumeLive() {
        LiveCover.assumeRelay()
        DeviceLock.awake()
        assumeTrue(
            "a wrap is already on the device; this run would replace it",
            !File(ctx.filesDir, BiometricWrap.FILE_NAME).exists(),
        )
    }

    private fun dotsShown(): Boolean =
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag(com.app.vault.ui.PATTERN_GRID_TAG))
            .fetchSemanticsNodes().isNotEmpty()

    /** One finger held at the centre of hold [area] for [ms] — `cover-hold-dots` D1's cells. */
    private fun hold(area: Int, ms: Long = 2_500) {
        compose.onRoot().performTouchInput {
            down(Offset((area % HOLD_COLUMNS + 0.5f) * width / HOLD_COLUMNS, (area / HOLD_COLUMNS + 0.5f) * height / HOLD_ROWS))
            advanceEventTime(ms)
            up()
        }
        compose.waitForIdle()
    }

    /** The music player worn with [musicPattern] and the top-left cell as its hold. */
    private fun wearMusicPlayer() {
        CoverTrigger.set(ctx, Cover.MusicPlayer, musicPattern, area = 0)
        CoverTrigger.clearAttempts(ctx, Cover.MusicPlayer)
        LauncherIdentity.switchTo(ctx, LauncherIdentity.MusicPlayer)
    }

    /**
     * The timeline's rows — where each attempt failed, newest first — after [keyring] opens and
     * takes in what is pending, as the activity's keyring-open effect does.
     */
    private fun ingestedRows(keyring: LiveCover.Keyring): List<Pair<Long, String>> {
        assertTrue(keyring.lifecycle.unlock(pass(LiveCover.PASSPHRASE)).opened)
        val db = keyringOpens(keyring.store)!!
        return CaptureLog.rows(storeLog(db, keyring.store), db.items).map {
            it.takenAt to CaptureLog.where(ctx.words(), it, com.app.vault.ui.BiometricLayout.Fingerprint)
        }
    }

    /**
     * `cover-hold-dots` D7. A hold in a wrong area fires nothing — it does nothing; a wrong pattern
     * on dots the hold brought up captures one, flagged as on the shown dots, and the log says so.
     */
    @Test fun aWrongPatternOnShownDotsCapturesOneFlaggedAndAHoldElsewhereNone() {
        DeviceLock.awake()
        val identityBefore = GridPreference.identity(ctx)
        val musicBefore = CoverTrigger.triggerFor(ctx, Cover.MusicPlayer)
        val holdBefore = CoverTrigger.holdFor(ctx, Cover.MusicPlayer)
        val keyring = LiveCover.openKeyring()
        try {
            enrolLivePhoto()
            wearMusicPlayer()
            VaultLifecycle.lockNow()

            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForIdle()
                assertTrue("the music player was not shown", showing("Tracks"))
                hold(area = 5)
                SystemClock.sleep(3_000)
                assertEquals("a hold in a wrong area took a capture", 0, photos())
                assertFalse(dotsShown())

                hold(area = 0)
                assertTrue("the hold did not bring up the dots", dotsShown())
                drawPattern(compose.onRoot(), 1, 3, 5, 7, 4)
                await("a capture of the wrong pattern on shown dots") { photos() == 1 }
                SystemClock.sleep(2_000)
                assertEquals("one wrong pattern took more than one capture", 1, photos())
            }

            val rows = ingestedRows(keyring)
            assertEquals(listOf("Music player: wrong pattern on the shown dots"), rows.map { it.second })
        } finally {
            keyring.close()
            CoverTrigger.clear(ctx, Cover.MusicPlayer)
            musicBefore?.let { CoverTrigger.set(ctx, Cover.MusicPlayer, it, area = holdBefore) }
            CoverTrigger.clearAttempts(ctx, Cover.MusicPlayer)
            LauncherIdentity.switchTo(ctx, identityBefore)
            LauncherIdentity.reconcile(ctx)
        }
    }

    /**
     * `calculator-dots-methods` D5. On **Button sequence, then pattern** a wrong sequence captures
     * nothing; a wrong pattern on the dots the right sequence brought up captures one, and the log
     * names the calculator and the shown dots — its cover code and its flag.
     */
    @Test fun aWrongPatternOnTheCalculatorsDotsCapturesOneAndAWrongSequenceNone() {
        DeviceLock.awake()
        val identityBefore = GridPreference.identity(ctx)
        val keysBefore = CoverTrigger.triggerFor(ctx, Cover.Calculator)
        val patternBefore = CoverTrigger.patternFor(ctx, Cover.Calculator)
        val holdBefore = CoverTrigger.holdFor(ctx, Cover.Calculator)
        val keys = Trigger.Keys(listOf("7", "3", "sin"))
        val keyring = LiveCover.openKeyring()
        try {
            enrolLivePhoto()
            assertTrue(CoverTrigger.set(ctx, Cover.Calculator, keys, dots = musicPattern))
            CoverTrigger.clearAttempts(ctx, Cover.Calculator)
            LauncherIdentity.switchTo(ctx, LauncherIdentity.Calculator)
            VaultLifecycle.lockNow()

            fun key(label: String) =
                compose.onAllNodes(hasText(label) and androidx.compose.ui.test.hasClickAction())[0]
            fun sequence(vararg labels: String) {
                labels.forEach { key(it).performClick() }
                compose.waitForIdle()
                key("=").performTouchInput {
                    down(center)
                    advanceEventTime(2_500)
                    up()
                }
                compose.waitForIdle()
            }

            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForIdle()
                assertTrue("the calculator was not shown", showing("sin"))
                sequence("1", "1", "1")
                SystemClock.sleep(3_000)
                assertEquals("a wrong sequence took a capture", 0, photos())
                assertFalse(dotsShown())

                sequence("AC", *keys.keys.toTypedArray())
                assertTrue("the right sequence did not bring up the dots", dotsShown())
                drawPattern(compose.onRoot(), 1, 3, 5, 7, 4)
                await("a capture of the wrong pattern on the calculator's dots") { photos() == 1 }
                SystemClock.sleep(2_000)
                assertEquals("one wrong pattern took more than one capture", 1, photos())
            }

            val rows = ingestedRows(keyring)
            assertEquals(listOf("Calculator: wrong pattern on the shown dots"), rows.map { it.second })
        } finally {
            keyring.close()
            CoverTrigger.clear(ctx, Cover.Calculator)
            keysBefore?.let { CoverTrigger.set(ctx, Cover.Calculator, it, dots = patternBefore) }
                ?: patternBefore?.let { CoverTrigger.set(ctx, Cover.Calculator, it, area = holdBefore) }
            CoverTrigger.clearAttempts(ctx, Cover.Calculator)
            LauncherIdentity.switchTo(ctx, identityBefore)
            LauncherIdentity.reconcile(ctx)
        }
    }

    /**
     * A wrong pattern on the hidden dots, a hold in a wrong area, a wrong pattern on dots the hold
     * brought up, a pattern during the limiter's lockout, a wrong finger on the unlock screen's own
     * sheet, and a wrong passphrase: one photo each but the hold, which takes none, the cover never
     * changing for the hidden ones. At the next keyring open the timeline names each stage, with the
     * moment it was taken. Run with `-e finger relay`: the unlock screen's sheet needs a finger.
     */
    @Test fun everyStageOfALiveUnlockCapturesOnceAndTheTimelineSaysWhere() {
        assumeLive()
        val identityBefore = GridPreference.identity(ctx)
        val musicBefore = CoverTrigger.triggerFor(ctx, Cover.MusicPlayer)
        val holdBefore = CoverTrigger.holdFor(ctx, Cover.MusicPlayer)
        val keyring = LiveCover.openKeyring()
        try {
            enrolLivePhoto()
            // The vault's own fingerprint unlock, so the unlock screen raises its sheet.
            LiveCover.link(BiometricWrap(ctx))
            wearMusicPlayer()
            VaultLifecycle.lockNow()
            val started = System.currentTimeMillis()

            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForIdle()
                assertTrue("the music player was not shown", showing("Tracks"))

                drawPattern(compose.onRoot(), 1, 3, 5, 7, 4)
                await("a capture of the wrong hidden pattern") { photos() == 1 }
                assertTrue("a wrong hidden pattern changed the cover", showing("Tracks"))
                assertFalse(dotsShown())

                hold(area = 5)
                SystemClock.sleep(2_000)
                assertEquals("a hold in a wrong area took a capture", 1, photos())

                hold(area = 0)
                assertTrue("the hold did not bring up the dots", dotsShown())
                drawPattern(compose.onRoot(), 1, 3, 5, 7, 4)
                await("a capture of the wrong pattern on shown dots") { photos() == 2 }
                SystemClock.sleep(1_000)

                // The limiter's lockout, as five failures would leave it.
                ctx.getSharedPreferences("covers", android.content.Context.MODE_PRIVATE).edit()
                    .putLong("${Cover.MusicPlayer.name}locked", System.currentTimeMillis() + Attempts.LOCKOUT_MS)
                    .commit()
                drawPattern(compose.onRoot(), *musicPattern.dots.toIntArray())
                await("a capture of the refused pattern") { photos() == 3 }

                CoverTrigger.clearAttempts(ctx, Cover.MusicPlayer)
                drawPattern(compose.onRoot(), *musicPattern.dots.toIntArray())
                await("the unlock screen") { showing("Enter your passphrase") }
                // Its own sheet follows; a wrong finger there.
                SystemClock.sleep(2_500)
                LiveCover.touch(2)
                await("a capture of the wrong finger on the unlock screen's sheet") { photos() == 4 }
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
                SystemClock.sleep(1_500)

                compose.onNode(hasSetTextAction()).performTextInput("not-the-passphrase")
                compose.onNodeWithText("Unlock").performClick()
                await("a capture of the wrong passphrase") { photos() == 5 }
            }

            val ingestAt = System.currentTimeMillis()
            val rows = ingestedRows(keyring)
            val words = rows.map { it.second }
            fun count(where: String) = words.count { it == where }
            assertEquals(words.toString(), 5, rows.size)
            assertEquals(words.toString(), 1, count("Music player: wrong pattern"))
            assertEquals(words.toString(), 1, count("Music player: wrong pattern on the shown dots"))
            assertEquals(words.toString(), 1, count("Music player: pattern tried during the lockout"))
            assertEquals(words.toString(), 1, count("Unlock screen: wrong fingerprint"))
            assertEquals(words.toString(), 1, count("Unlock screen: wrong passphrase"))
            rows.forEach { (at, where) ->
                assertTrue("not the moment taken: $where", at in (started - 1_000)..ingestAt)
            }
        } finally {
            keyring.close()
            CoverTrigger.clear(ctx, Cover.MusicPlayer)
            musicBefore?.let { CoverTrigger.set(ctx, Cover.MusicPlayer, it, area = holdBefore) }
            CoverTrigger.clearAttempts(ctx, Cover.MusicPlayer)
            LauncherIdentity.switchTo(ctx, identityBefore)
            LauncherIdentity.reconcile(ctx)
        }
    }

    // --- `per-vault-intruder-capture` D8: permission changes ---------------------------------

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).let { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
        }

    private val cameraTransition = CapturePermissions.Transition(TamperLog.PERMISSION_CAMERA, TamperLog.PERMISSION_LOST, 1_700_000_000_000L)

    @Test fun aPermissionChangeIsRecordedAtStart() {
        CapturePermissions.resetForTest(ctx)
        try {
            assertFalse("the first observation only sets the baseline", CapturePermissions.observe(ctx))
            assertTrue(CapturePermissions.transitions(ctx).isEmpty())
            // The baseline says the opposite of what is true now — as after a revocation, or a grant
            // in Settings, while the app was not running.
            val held = CapturePermissions.held(ctx, TamperLog.PERMISSION_CAMERA)
            ctx.getSharedPreferences("capture_permissions", android.content.Context.MODE_PRIVATE).edit()
                .putBoolean("held_${TamperLog.PERMISSION_CAMERA}", !held).commit()
            val before = System.currentTimeMillis()
            ActivityScenario.launch(MainActivity::class.java).use { compose.waitForIdle() }
            val noticed = CapturePermissions.transitions(ctx).single()
            assertEquals(TamperLog.PERMISSION_CAMERA, noticed.permission)
            assertEquals(if (held) TamperLog.PERMISSION_RESTORED else TamperLog.PERMISSION_LOST, noticed.state)
            assertTrue("stamped with the moment it was noticed", noticed.at >= before)
            assertFalse("nothing new on the next look", CapturePermissions.observe(ctx))
        } finally {
            CapturePermissions.resetForTest(ctx)
        }
    }

    @Test fun aHomeRowOnlyWhenAHomeModeNeedsThePermission() {
        assertTrue(vault.create(pass(PASS_A)).opened)
        val key = VaultKeyringSession.captureStoreKey()!!
        try {
            CaptureIngest.recordPermissions(store, key, listOf(cameraTransition), setOf(Capture.Mode.AUDIO))
            assertFalse("audio does not need the camera: no row and no store", store.fileOf(VaultKeys.metadataBlobId(key)).exists())

            CaptureIngest.recordPermissions(store, key, listOf(cameraTransition), setOf(Capture.Mode.PHOTO))
            CaptureIngest.recordPermissions(store, key, listOf(cameraTransition), setOf(Capture.Mode.PHOTO))
            val db = MetadataDb.load(store, VaultKeys.metadataBlobId(key), key)
            val rows = CaptureLog.rows(storeLog(db), db.items, setOf(Capture.Mode.PHOTO))
            val row = rows.single()
            assertEquals("once, however often the keyring opens", TamperLog.PERMISSION_LOST, row.permission?.state)
            assertEquals(cameraTransition.at, row.takenAt)
            assertEquals(listOf(Capture.Mode.PHOTO), row.permissionModes)
        } finally {
            key.fill(0)
        }
    }

    @Test fun aVaultNoticeIsQueuedAndShownAtTheVaultsOpening() {
        assertTrue(vault.create(pass(PASS_A)).opened)
        VaultKeyringSession.leaveVault()
        val kek = ByteArray(32) { 7 }
        val position = VaultKeyringSession.createVault(store, "Private") { _, _ -> kek.copyOf() }!!
        val id = enrolVaultCapture(store, position, kek, setOf(Capture.Mode.PHOTO))
        val key = VaultKeyringSession.captureStoreKey()!!
        try {
            CaptureIngest.recordPermissions(store, key, listOf(cameraTransition), homeModes = emptySet())
        } finally {
            key.fill(0)
        }
        val ring = keyringCaptureStore(store)
        assertEquals(1, ring.vaultNotices.count { it.indexBlobId.contentEquals(id) })
        assertTrue(
            "the home timeline gains no row on a vault's behalf",
            storeLog(ring).none { it.event == TamperLog.CAPTURE_PERMISSION },
        )

        val opened = VaultKeyringSession.openVaultWithPassphrase(position, kek)!!
        withKeyringCaptureStore(store) { CaptureIngest.ingestVault(store, it, opened) }
        val (db, log) = vaultCaptureStore(store, opened)!!
        val row = CaptureLog.rows(log, db.items, setOf(Capture.Mode.PHOTO)).single()
        assertEquals(TamperLog.PERMISSION_CAMERA, row.permission?.permission)
        assertEquals(listOf(Capture.Mode.PHOTO), row.permissionModes)
        assertTrue("taken in, so no longer queued", keyringCaptureStore(store).vaultNotices.isEmpty())
    }

    // --- `per-vault-intruder-capture` D10: blank and silent ------------------------------------

    /** With Android's camera access off, the emulator's camera hands back black frames. */
    @Test fun aPhotoWithTheCameraBlockedIsFlaggedBlank() {
        assumeTrue("no front camera", IntruderCapture.hasFrontCamera(ctx))
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(ctx.packageName, android.Manifest.permission.CAMERA)
        shell("cmd sensor_privacy enable 0 camera")
        try {
            SystemClock.sleep(1_000)
            val photo = IntruderCapture.capture(ctx, Capture.Mode.PHOTO)
            assertTrue("no photo came back", photo != null)
            val blank = IntruderCapture.isBlank(Capture.Mode.PHOTO, photo!!)
            assertTrue("a black frame reads blank", blank)
            val stage = CaptureRecord.Stage(CaptureRecord.STAGE_PASSPHRASE).blankIf(blank)
            assertEquals(CaptureRecord.FLAG_BLANK, CaptureRecord.read(CaptureRecord.prepend(stage, 1L, photo))!!.stage.flags)
        } finally {
            shell("cmd sensor_privacy disable 0 camera")
        }
    }

    /** With Android's microphone access off, the microphone delivers zeros. */
    @Test fun audioWithTheMicrophoneBlockedIsFlaggedSilent() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(ctx.packageName, android.Manifest.permission.RECORD_AUDIO)
        shell("cmd sensor_privacy enable 0 microphone")
        try {
            SystemClock.sleep(1_000)
            val clip = IntruderCapture.capture(ctx, Capture.Mode.AUDIO)
            assertTrue("no clip came back", clip != null)
            assertTrue("a clip of zeros reads silent", IntruderCapture.isBlank(Capture.Mode.AUDIO, clip!!))
        } finally {
            shell("cmd sensor_privacy disable 0 microphone")
        }
    }
}
