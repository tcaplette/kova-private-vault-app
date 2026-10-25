package com.app.vault

import android.os.SystemClock
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
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
import java.io.File

/**
 * A vault's own intruder capture — `per-vault-intruder-capture`.
 *
 * A wrong passphrase on a vault's own sheet, from the home list and from Move to vault, captures
 * and seals to that vault under that vault's modes, never the home ones; nothing at home shows it;
 * the vault takes its captures in at its opening, once; and its captures go with it.
 *
 * The firing is driven on the real activity and the emulator's camera. What the activity does at
 * a vault's opening is [CaptureIngest.ingestVault], driven directly with sealed bytes, the way
 * `CaptureDeviceTest` drives the keyring's opening.
 *
 * **Gated**, as every suite that makes a keyring at `filesDir/vault.slots` is:
 *
 *   -Pandroid.testInstrumentationRunnerArguments.destructive=true
 *
 * Test-only state it makes and removes: a keyring with "My vault" and "Private" (its own
 * passphrase, removed by the `vault-passphrase-manage` cases), Private's enrolment and queue, the camera grant (cleared with the app's data).
 */
@RunWith(AndroidJUnit4::class)
class VaultCaptureDeviceTest {

    @Suppress("DEPRECATION")
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")
    private val store by lazy { BlobStore(blobsDir, incoming) }
    private val lifecycle by lazy { VaultLifecycle(slots, store) }
    private var created = false
    private var privatePosition = -1

    private companion object {
        const val PASS = "correct horse battery staple"
        const val PRIVATE = "Private"
        const val PRIVATE_PASS = "a different staple entirely"
        const val PLAIN = VaultKeyringSession.MIGRATED_NAME
        const val WAITING = "An intruder capture is waiting"
        const val VAULT_WHERE = "Vault passphrase: wrong passphrase"
    }

    @Before
    fun setUp() {
        assumeTrue(
            "gated: pass -Pandroid.testInstrumentationRunnerArguments.destructive=true to run it.",
            InstrumentationRegistry.getArguments().getString("destructive") == "true",
        )
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        Capture.resetForTest(ctx)
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        assertTrue(lifecycle.create(PASS.toByteArray(Charsets.UTF_8)).opened)
        created = true
        VaultKeyringSession.leaveVault()
        privatePosition = VaultKeyringSession.createVault(store, PRIVATE) { position, set ->
            VaultKeyringSession.entryPassphraseKek(PRIVATE_PASS.toByteArray(Charsets.UTF_8), position, set)!!
        }!!
        lockKeyringAndVault()
    }

    @After
    fun tearDown() {
        if (InstrumentationRegistry.getArguments().getString("destructive") != "true") return
        Capture.resetForTest(ctx)
        lockKeyringAndVault()
        ThumbnailCache.clear()
        WorkManager.getInstance(ctx).cancelAllWork()
        if (!created) return
        MetadataDb.awaitQuiescent()
        slots.delete()
        blobsDir.deleteRecursively()
        incoming.deleteRecursively()
    }

    // --- harness --------------------------------------------------------------------------

    private fun await(what: String, timeoutMs: Long = 45_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = timeoutMs, condition = condition)
        } catch (e: Throwable) {
            runCatching {
                compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { i ->
                    compose.onAllNodes(isRoot())[i].printToLog("VaultCapture")
                }
            }
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun described(text: String) =
        compose.onAllNodes(hasContentDescription(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun text(label: String) =
        compose.onAllNodes(hasText(label, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun <T> withKeyring(block: () -> T): T {
        val wasOpen = VaultKeyringSession.isOpen
        if (!wasOpen) assertTrue(lifecycle.unlock(PASS.toByteArray(Charsets.UTF_8)).opened)
        return try {
            block()
        } finally {
            if (!wasOpen) lockKeyringAndVault()
        }
    }

    private fun privateKek(): ByteArray {
        val entry = VaultKeyringSession.vaults().single { it.position == privatePosition }
        return VaultKeyringSession.entryPassphraseKek(PRIVATE_PASS.toByteArray(Charsets.UTF_8), privatePosition, entry.kdfSet)!!
    }

    /** Private enrolled with [modes]; returns its index ID. */
    private fun enrolPrivate(vararg modes: Capture.Mode): ByteArray = withKeyring {
        val kek = privateKek()
        try {
            enrolVaultCapture(store, privatePosition, kek, modes.toSet())
        } finally {
            kek.fill(0)
        }
    }

    /** Private opened as the session's vault, as its sheet opens it. */
    private fun openPrivate(): OpenVault {
        val kek = privateKek()
        return try {
            VaultKeyringSession.openVaultWithPassphrase(privatePosition, kek)!!
        } finally {
            kek.fill(0)
        }
    }

    private fun grantCamera() =
        instr.uiAutomation.grantRuntimePermission(ctx.packageName, android.Manifest.permission.CAMERA)

    private fun unlock() {
        await("the lock screen") { text("Welcome back") }
        compose.waitForIdle()
        val field = compose.onNode(hasText("Passphrase"))
        field.performClick()
        compose.waitForIdle()
        field.performTextInput(PASS)
        await("the passphrase to register") {
            compose.onAllNodes(hasText("Unlock") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Unlock").performClick()
        await("the vault list") { described("Menu") }
        compose.waitForIdle()
    }

    /** What the queued blob opens to, under Private's capture store's keypair — or null. */
    private fun openedByPrivate(pending: VaultPending): CaptureRecord.Read? {
        val opened = openPrivate()
        try {
            val key = VaultKeys.captureStoreKey(opened.vmk)
            val kp = VaultKeys.captureKeypair(key)
            try {
                val plain = Capture.open(kp.secretKey, kp.publicKey, store.readSealed(pending.blobId, pending.sealedLength))
                return plain?.let { CaptureRecord.read(it) }.also { plain?.fill(0) }
            } finally {
                kp.zero()
                key.fill(0)
            }
        } finally {
            VaultKeyringSession.leaveVault()
        }
    }

    private fun assertHomeShowsNothing() {
        assertFalse("the home bar must not be badged by a vault's capture", described(WAITING))
        compose.openIntruderCaptureFromHome()
        await("the home timeline") { text("Nothing captured") }
        assertFalse("the home timeline names no vault's capture", described(VAULT_WHERE))
        compose.onNodeWithContentDescription("Capture settings").performClick()
        await("the home sheet") { text("A still from the front camera") }
        assertTrue("every home switch reads off", compose.onAllNodes(isOn()).fetchSemanticsNodes().isEmpty())
    }

    // --- firing, on either sheet ------------------------------------------------------------

    @Test
    fun aWrongPassphraseOnTheHomeListsSheetQueuesOneCaptureSealedToThatVault() {
        val id = enrolPrivate(Capture.Mode.PHOTO)
        grantCamera()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            compose.enterVaultPassphrase(PRIVATE, "not the passphrase")
            await("the sheet's error, as with capture off") { text("That passphrase did not open $PRIVATE.") }
            await("the capture queued", timeoutMs = 30_000) { vaultQueue(store, id).size == 1 }
            SystemClock.sleep(3_000)
            assertEquals("one attempt, one capture", 1, vaultQueue(store, id).size)

            val pending = vaultQueue(store, id).single()
            assertEquals(Capture.Mode.PHOTO, pending.mode)
            val record = openedByPrivate(pending)
            assertNotNull("Private's own key opens it", record)
            assertEquals(CaptureRecord.STAGE_VAULT_PASSPHRASE, record!!.stage.stage)
            Capture.Mode.entries.forEach { assertEquals("the home queue is untouched", 0, Capture.pending(ctx, it).size) }

            // Nothing at home differs: no badge, an empty timeline, every switch off.
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("input keyevent KEYCODE_BACK").close()
            await("the sheet to go") { !text("That passphrase did not open") }
            assertHomeShowsNothing()
        }
    }

    @Test
    fun aWrongPassphraseOnMoveToVaultsSheetQueuesOneCaptureSealedToThatVault() {
        val id = enrolPrivate(Capture.Mode.PHOTO)
        // A home capture to move, with every home mode off again afterwards.
        withKeyring {
            stageKeyringCapture(ctx, store, Capture.Mode.AUDIO, ByteArray(6_000) { it.toByte() })
            Capture.Mode.entries.forEach { Capture.setEnabled(ctx, it, false) }
        }
        grantCamera()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            await("the home capture taken in") { keyringCaptureStore(store).items.size == 1 }
            compose.openIntruderCaptureFromHome()
            await("the home row") { described("Unlock screen: wrong passphrase") }
            compose.onNode(hasContentDescription("Unlock screen: wrong passphrase", substring = true))
                .performTouchInput { longClick() }
            await("the selection bar") { text("1 selected") }
            compose.onNodeWithContentDescription("Move to vault").performClick()
            await("Move to vault's list") { text(PRIVATE) }
            compose.onNode(hasText(PRIVATE) and hasClickAction()).performClick()
            compose.submitVaultPassphrase("not the passphrase")
            await("the sheet's error") { text("That passphrase did not open $PRIVATE.") }
            await("the capture queued", timeoutMs = 30_000) { vaultQueue(store, id).size == 1 }
            SystemClock.sleep(3_000)
            assertEquals("one attempt, one capture", 1, vaultQueue(store, id).size)
            assertNotNull("sealed to Private", openedByPrivate(vaultQueue(store, id).single()))
            assertEquals("the home store gained nothing", 1, keyringCaptureStore(store).items.size)
        }
    }

    @Test
    fun aVaultWithItsModesOffCapturesNothingWithEveryHomeModeOn() {
        withKeyring {
            val key = VaultKeyringSession.captureStoreKey()!!
            VaultKeys.captureKeypair(key).let { Capture.setDestination(ctx, it.publicKey); it.zero() }
            key.fill(0)
            Capture.Mode.entries.forEach { Capture.setEnabled(ctx, it, true) }
        }
        grantCamera()
        instr.uiAutomation.grantRuntimePermission(ctx.packageName, android.Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            compose.enterVaultPassphrase(PRIVATE, "not the passphrase")
            await("the sheet's error") { text("That passphrase did not open $PRIVATE.") }
            SystemClock.sleep(10_000)
            assertTrue("nothing queued for any vault", keyringCaptureStore(store).vaultPending.isEmpty())
            Capture.Mode.entries.forEach { assertEquals("the home switches are not consulted", 0, Capture.pending(ctx, it).size) }
        }
    }

    // --- taken in at the vault's opening ------------------------------------------------------

    @Test
    fun openingTheVaultTakesTheCapturesInOnceAndAnInterruptedIngestLeavesNoDuplicate() {
        val id = enrolPrivate(Capture.Mode.PHOTO)
        withKeyring {
            val takenAt = System.currentTimeMillis() - 60_000
            val photo = capturePhotoBytes()
            val sealedBlob = queueVaultCapture(store, id, Capture.Mode.PHOTO, photo, takenAt)
            val opened = openPrivate()
            try {
                val landed = withKeyringCaptureStore(store) { CaptureIngest.ingestVault(store, it, opened) }
                assertEquals(1, landed)
                val (db, log) = vaultCaptureStore(store, opened)!!
                val item = db.items.single()
                assertEquals(takenAt, item.createdAt)
                assertEquals(CaptureRecord.STAGE_VAULT_PASSPHRASE, log.single { it.event == TamperLog.INTRUDER_CAPTURED }.payload[0].toInt())
                assertTrue("the queue is empty", vaultQueue(store, id).isEmpty())
                assertFalse("the sealed blob is gone", store.fileOf(sealedBlob).exists())
                val own = MetadataDb.load(store, opened.metadataBlobId, opened.vmk)
                assertTrue("the vault's own index holds no capture", own.items.none { it.filename.startsWith("intruder-") })
                assertTrue("the home store holds none", keyringCaptureStore(store).items.isEmpty())

                // Killed after the vault's commit and before the keyring's: the row is still
                // queued while the store holds it. The next opening adds nothing.
                queueVaultCapture(store, id, Capture.Mode.PHOTO, photo, takenAt)
                assertEquals(0, withKeyringCaptureStore(store) { CaptureIngest.ingestVault(store, it, opened) })
                assertEquals("each capture once", 1, vaultCaptureStore(store, opened)!!.first.items.size)
                assertTrue("and nothing left queued", vaultQueue(store, id).isEmpty())
            } finally {
                VaultKeyringSession.leaveVault()
            }
        }
    }

    @Test
    fun anotherVaultLeavesThemQueued() {
        val id = enrolPrivate(Capture.Mode.PHOTO)
        withKeyring {
            queueVaultCapture(store, id, Capture.Mode.PHOTO, capturePhotoBytes())
            val plain = VaultKeyringSession.openVault(0)!!
            try {
                assertEquals(0, withKeyringCaptureStore(store) { CaptureIngest.ingestVault(store, it, plain) })
                assertNull("no store is made for the other vault", vaultCaptureStore(store, plain))
            } finally {
                VaultKeyringSession.leaveVault()
            }
            assertEquals("still queued for Private", 1, vaultQueue(store, id).size)
        }
    }

    @Test
    fun thePerVaultCapHoldsAtFiftyAndTouchesNoOtherVault() {
        val id = enrolPrivate(Capture.Mode.PHOTO)
        val other = ByteArray(16) { 9 }
        withKeyring {
            val otherBlob = Capture.newBlobId().also { store.writeSealed(it, ByteArray(64)) }
            withKeyringCaptureStore(store) { CaptureStore.queue(store, it, other, Capture.Mode.PHOTO, otherBlob, 64) }
            val first = queueVaultCapture(store, id, Capture.Mode.PHOTO, ByteArray(500) { 1 })
            repeat(CaptureStore.VAULT_PER_MODE_CAP) { queueVaultCapture(store, id, Capture.Mode.PHOTO, ByteArray(500) { 2 }) }
            assertEquals(CaptureStore.VAULT_PER_MODE_CAP, vaultQueue(store, id).size)
            assertFalse("the oldest was evicted by deleting its sealed blob", store.fileOf(first).exists())
            assertEquals("no other vault's queue changed", 1, vaultQueue(store, other).size)
            assertTrue(store.fileOf(otherBlob).exists())
        }
    }

    // --- the badge, inside the vault only -------------------------------------------------------

    @Test
    fun theVaultBadgeIsInsideTheVaultAndOnlyItsTimelineClearsIt() {
        val id = enrolPrivate(Capture.Mode.PHOTO)
        withKeyring { queueVaultCapture(store, id, Capture.Mode.PHOTO, capturePhotoBytes()) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            SystemClock.sleep(2_000)
            assertFalse("no badge at home", described(WAITING))

            compose.openProtectedVaultFromTheList(PRIVATE, PRIVATE_PASS)
            await("the grid's drawer control badged") { described(WAITING) }
            compose.onNodeWithContentDescription("Trash and settings").performClick()
            await("the drawer's row") { text("Intruder capture") }
            assertTrue("the row says it in words", text(WAITING))
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            assertTrue("opening the drawer is not looking", described(WAITING))

            compose.openVaultIntruderCapture()
            await("the vault's row") { described(VAULT_WHERE) }
            assertTrue("the vault is named under the title", described("Vault: $PRIVATE"))
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await("the badge to clear") { !described(WAITING) }

            compose.onNodeWithContentDescription("All vaults").performClick()
            await("the vault list") { described("Menu") }
            assertFalse("the home bar never carried it", described(WAITING))
        }
    }

    // --- a vault's captures go with it --------------------------------------------------------

    @Test
    fun destroyingFromInsideForgetsEverything() {
        val id = enrolPrivate(Capture.Mode.PHOTO)
        val (queued, heldBlobs, storeId) = withKeyring {
            queueVaultCapture(store, id, Capture.Mode.PHOTO, capturePhotoBytes(), System.currentTimeMillis() - 5_000)
            val opened = openPrivate()
            val held: List<ByteArray>
            val ownId: ByteArray
            try {
                withKeyringCaptureStore(store) { CaptureIngest.ingestVault(store, it, opened) }
                val (db, _) = vaultCaptureStore(store, opened)!!
                held = db.items.flatMap { it.blobIds } + listOfNotNull(db.logBlobId)
                ownId = VaultKeys.captureStoreKey(opened.vmk).let { k -> VaultKeys.metadataBlobId(k).also { k.fill(0) } }
            } finally {
                VaultKeyringSession.leaveVault()
            }
            val stillQueued = queueVaultCapture(store, id, Capture.Mode.PHOTO, capturePhotoBytes())
            val key = VaultKeyringSession.captureStoreKey()!!
            try {
                CaptureIngest.recordPermissions(
                    store, key,
                    listOf(CapturePermissions.Transition(TamperLog.PERMISSION_CAMERA, TamperLog.PERMISSION_LOST, 1_000L)),
                    homeModes = emptySet(),
                )
            } finally {
                key.fill(0)
            }
            assertEquals("a notice queued for Private", 1, keyringCaptureStore(store).vaultNotices.size)
            Triple(stillQueued, held, ownId)
        }
        grantCamera()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            compose.openProtectedVaultFromTheList(PRIVATE, PRIVATE_PASS)
            compose.onNodeWithContentDescription("Trash and settings").performClick()
            await("Destroy Vault") { text("Destroy Vault") }
            compose.onNode(hasText("Destroy Vault") and hasClickAction()).performClick()
            await("the confirmation") { text("Destroy everything in it") }
            compose.onNode(hasText("Destroy everything in it") and hasClickAction()).performClick()
            await("Private gone from the list") { VaultKeyringSession.vaults().none { it.name == PRIVATE } }

            val ring = keyringCaptureStore(store)
            assertNull("no enrolment", CaptureStore.enrolment(ring, id))
            assertTrue("no queued capture", ring.vaultPending.none { it.indexBlobId.contentEquals(id) })
            assertTrue("no queued notice", ring.vaultNotices.none { it.indexBlobId.contentEquals(id) })
            assertFalse("the queued blob is gone", store.fileOf(queued).exists())
            assertFalse("its capture store's index is gone", store.fileOf(storeId).exists())
            heldBlobs.forEach { assertFalse("its capture store's blobs are gone", store.fileOf(it).exists()) }
        }
    }

    // --- no row without a lock ------------------------------------------------------------------

    @Test
    fun aVaultWithoutItsOwnPassphraseHasNoIntruderCaptureRow() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            compose.openFirstVaultFromTheList(PLAIN)
            await("the grid") { described("All vaults") }
            compose.onNodeWithContentDescription("Trash and settings").performClick()
            await("the drawer") { text("Unvault Everything") }
            assertTrue("no Intruder capture row", compose.onAllNodes(hasText("Intruder capture")).fetchSemanticsNodes().isEmpty())
        }
    }

    // --- its passphrase removed, its timeline home — `vault-passphrase-manage` D4 ----------------

    /**
     * Private's timeline of three: one capture held, one moved out to "My vault", one still queued.
     * Returns their moments taken, in that order.
     */
    private fun privateTimelineOfThree(): List<Long> {
        val id = enrolPrivate(Capture.Mode.PHOTO, Capture.Mode.AUDIO)
        val now = System.currentTimeMillis()
        val held = now - 300_000
        val moved = now - 200_000
        val queued = now - 100_000
        withKeyring {
            queueVaultCapture(store, id, Capture.Mode.PHOTO, capturePhotoBytes(), held)
            queueVaultCapture(store, id, Capture.Mode.AUDIO, ByteArray(4_000) { it.toByte() }, moved)
            val opened = openPrivate()
            try {
                assertEquals(2, withKeyringCaptureStore(store) { CaptureIngest.ingestVault(store, it, opened) })
                val key = VaultKeys.captureStoreKey(opened.vmk)
                try {
                    val own = OpenVault(key, VaultKeys.metadataBlobId(key), -1)
                    val items = MetadataDb.load(store, own.metadataBlobId, key).items.filter { it.createdAt == moved }
                    CaptureStore.remove(store, own, items, TamperLog.LEFT_MOVED, PLAIN)
                } finally {
                    key.fill(0)
                }
            } finally {
                VaultKeyringSession.leaveVault()
            }
            queueVaultCapture(store, id, Capture.Mode.PHOTO, capturePhotoBytes(), queued)
        }
        return listOf(held, moved, queued)
    }

    private fun removePrivatesPassphrase() =
        assertEquals(VaultLifecycle.PassphraseAct.DONE, lifecycle.removeVaultPassphrase(privatePosition, PRIVATE_PASS.toByteArray(Charsets.UTF_8)))

    /** The home timeline's rows — the store's log over its items, as the screen reads them. */
    private fun homeRows(): List<CaptureLog.Row> {
        val key = VaultKeyringSession.captureStoreKey()!!
        return try {
            val db = MetadataDb.load(store, VaultKeys.metadataBlobId(key), key)
            CaptureLog.rows(db.logBlobId?.let { TamperLog.scan(store, it, key).entries }.orEmpty(), db.items)
        } finally {
            key.fill(0)
        }
    }

    @Test
    fun removingThePassphraseMovesHeldMovedOutAndQueuedCapturesHome() {
        val (held, moved, queued) = privateTimelineOfThree()
        val id = withKeyring { VaultKeyringSession.vaults().single { it.position == privatePosition }.indexBlobId }
        withKeyring {
            removePrivatesPassphrase()

            val rows = homeRows()
            assertEquals("one row per capture", listOf(queued, moved, held), rows.map { it.takenAt })
            assertNotNull("the held capture has its media", rows.single { it.takenAt == held }.item)
            assertNotNull("the queued one was taken in on the way", rows.single { it.takenAt == queued }.item)
            val left = rows.single { it.takenAt == moved }
            assertNull("the moved one reads as moved", left.item)
            assertEquals(TamperLog.LEFT_MOVED, left.left!!.outcome)
            assertEquals(PLAIN, left.left!!.vaultName)

            val ring = keyringCaptureStore(store)
            assertNull("its enrolment is gone", CaptureStore.enrolment(ring, id))
            assertTrue("nothing queued for it", vaultQueue(store, id).isEmpty())
            val opened = VaultKeyringSession.openVault(privatePosition)
            assertNotNull("it opens from the list", opened)
            assertNull("its own capture store is gone", vaultCaptureStore(store, opened!!))
            VaultKeyringSession.leaveVault()
        }
    }

    @Test
    fun anInterruptedRemovalRepeatedLeavesEachCaptureHomeOnce() {
        val (held, moved, queued) = privateTimelineOfThree()
        withKeyring {
            // Killed after the captures reached home and before the re-seal: step 2 ran, nothing after it.
            val kek = privateKek()
            try {
                VaultKeyringSession.withVaultKey(privatePosition, kek) { vmk, indexId ->
                    val home = VaultKeyringSession.captureStoreKey()!!
                    try {
                        CaptureIngest.ingestVault(store, OpenVault(home, VaultKeys.metadataBlobId(home), -1), OpenVault(vmk, indexId, -1))
                        assertEquals(2, CaptureIngest.moveVaultHome(store, home, vmk))
                    } finally {
                        home.fill(0)
                    }
                }
            } finally {
                kek.fill(0)
            }
            assertTrue("still protected", VaultKeyringSession.vaults().single { it.position == privatePosition }.hasOwnPassphrase)
            assertNull("and not the list's to open", VaultKeyringSession.openVault(privatePosition))

            removePrivatesPassphrase()
            val rows = homeRows()
            assertEquals("each capture once", listOf(queued, moved, held), rows.map { it.takenAt })
            assertEquals("each held item once", 2, keyringCaptureStore(store).items.size)
        }
    }

    @Test
    fun aRemovedPassphraseBadgesHomeAndLeavesNoIntruderCaptureRow() {
        privateTimelineOfThree()
        // Home capture never turned on: the keyring is not the destination, and the badge still shows.
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            SystemClock.sleep(2_000)
            assertFalse("no badge at home before", described(WAITING))

            compose.openEditVault(PRIVATE)
            compose.onNode(hasText("Remove passphrase") and hasClickAction()).performScrollTo().performClick()
            await("Remove passphrase") { text("Its intruder captures move home") }
            val field = compose.onNode(hasText("Current passphrase") and hasSetTextAction())
            field.performClick()
            field.performTextInput(PRIVATE_PASS)
            compose.onAllNodes(hasText("Remove passphrase") and hasClickAction()).onLast().performScrollTo().performClick()
            await("the status line", timeoutMs = 30_000) { text("Passphrase removed.") }

            compose.onNodeWithContentDescription("Back").performClick()
            await("the badge at home") { described(WAITING) }

            compose.openFirstVaultFromTheList(PRIVATE)
            await("the grid") { described("All vaults") }
            compose.onNodeWithContentDescription("Trash and settings").performClick()
            await("the drawer") { text("Unvault Everything") }
            assertTrue("no Intruder capture row", compose.onAllNodes(hasText("Intruder capture")).fetchSemanticsNodes().isEmpty())
        }
    }
}
