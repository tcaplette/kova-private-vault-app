package com.app.vault

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The home screen's Intruder capture screen, on the real activity — `intruder-captures-at-home`.
 *
 * The timeline as the log, the viewer's three actions, the move into a vault on the keyring — with
 * and without a passphrase of its own — delete, export, both empty states, and the badge.
 *
 * **Gated, as `CaptureSheetDeviceTest` is**, because it makes a keyring at `filesDir/vault.slots`:
 *
 *   -Pandroid.testInstrumentationRunnerArguments.destructive=true
 *
 * Test-only state it makes and removes: a keyring with two vaults — "My vault", and "Locked" with
 * its own passphrase — a folder in each, the capture enrolment, and, for the export, one photograph
 * and the manifest in the device's gallery, discarded at close.
 */
@RunWith(AndroidJUnit4::class)
class IntruderCaptureScreenDeviceTest {

    // The v1 rule every UI suite here runs on; the v2 dispatcher queues what this harness expects to run.
    @Suppress("DEPRECATION")
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")
    private val store by lazy { BlobStore(blobsDir, incoming) }
    private val lifecycle by lazy { VaultLifecycle(slots, store) }
    private var created = false
    private val exported = mutableListOf<String>()

    private companion object {
        const val PASS = "correct horse battery staple"
        const val LOCKED_PASS = "a different staple entirely"
        const val PLAIN = VaultKeyringSession.MIGRATED_NAME
        const val LOCKED = "Locked"
        const val TRIPS = "Trips"
        const val PHOTO_WHERE = "Music player: wrong pattern on the shown dots"
        const val AUDIO_WHERE = "Unlock screen: wrong passphrase"
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
        // Out of the vault the creation opened, onto its keyring.
        VaultKeyringSession.leaveVault()
        // A second vault, with its own passphrase.
        val locked = VaultKeyringSession.createVault(store, LOCKED) { position, set ->
            VaultKeyringSession.entryPassphraseKek(LOCKED_PASS.toByteArray(Charsets.UTF_8), position, set)!!
        }
        assertNotNull(locked)
        // A Media folder in the plain vault, for a photograph to be moved into.
        VaultKeyringSession.withVaultKey(0, null) { vmk, id ->
            VaultFolders.create(store, id, vmk, null, TRIPS, VaultDestination.Media)
        }
        lockKeyringAndVault()
    }

    @After
    fun tearDown() {
        if (InstrumentationRegistry.getArguments().getString("destructive") != "true") return
        val gallery = MediaStoreDestination(ctx)
        exported.forEach { name -> gallery.find(name)?.let { gallery.discard(it) } }
        gallery.find(ExportManifest.NAME)?.let { gallery.discard(it) }
        gallery.sweep()
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
                    compose.onAllNodes(isRoot())[i].printToLog("IntruderCapture")
                }
            }
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun described(text: String) =
        compose.onAllNodes(hasContentDescription(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun text(label: String) =
        compose.onAllNodes(hasText(label, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** With the keyring open in-process, for setting state up or reading it back. */
    private fun <T> withKeyring(block: () -> T): T {
        val wasOpen = VaultKeyringSession.isOpen
        if (!wasOpen) assertTrue(lifecycle.unlock(PASS.toByteArray(Charsets.UTF_8)).opened)
        return try {
            block()
        } finally {
            if (!wasOpen) lockKeyringAndVault()
        }
    }

    /**
     * A photo on the music player's shown dots two minutes ago, and a passphrase's audio one minute
     * ago. Returns the photo's filename, which carries the moment it was taken.
     */
    private fun stageTwo(): String = withKeyring {
        // The two modes on, with the permissions turning them on asks for — `per-vault-intruder-
        // capture` shows a mode without its permission as not recording, which is not this timeline.
        listOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO).forEach {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(ctx.packageName, it)
        }
        val now = System.currentTimeMillis()
        stageKeyringCapture(
            ctx, store, Capture.Mode.PHOTO, capturePhotoBytes(),
            CaptureRecord.Stage(CaptureRecord.STAGE_COVER_PATTERN, CaptureRecord.FLAG_DOTS_SHOWN, CaptureRecord.COVER_MUSIC),
            takenAt = now - 120_000,
        )
        stageKeyringCapture(
            ctx, store, Capture.Mode.AUDIO, ByteArray(6_000) { it.toByte() },
            takenAt = now - 60_000,
        )
        "intruder-${now - 120_000}.jpg"
    }

    /** The keyring's capture store, read back with the keyring the activity opened. */
    private fun storeIndex(): MetadataDb {
        val key = VaultKeyringSession.captureStoreKey()!!
        return try {
            MetadataDb.load(store, VaultKeys.metadataBlobId(key), key)
        } finally {
            key.fill(0)
        }
    }

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

    /** Unlocks, waits for the keyring-open effect to take both captures in, and opens the screen. */
    private fun toTheTimeline() {
        unlock()
        await("both captures taken in") { runCatching { storeIndex().items.size == 2 }.getOrDefault(false) }
        compose.openIntruderCaptureFromHome()
        await("the rows") { described(PHOTO_WHERE) && described(AUDIO_WHERE) }
    }

    private fun row(where: String) = compose.onNode(hasContentDescription(where, substring = true))

    private fun noVaultIsOpen() = !VaultSession.isUnlocked && VaultKeyringSession.openVaultPosition() < 0

    /** The items of the vault at [position], read with its key and left closed. */
    private fun vaultItems(position: Int, passphrase: String? = null): List<VaultItem> {
        val entry = VaultKeyringSession.vaults().single { it.position == position }
        val kek = passphrase?.let {
            VaultKeyringSession.entryPassphraseKek(it.toByteArray(Charsets.UTF_8), position, entry.kdfSet)
        }
        return try {
            VaultKeyringSession.withVaultKey(position, kek) { vmk, id -> MetadataDb.load(store, id, vmk).items }!!
        } finally {
            kek?.fill(0)
        }
    }

    private fun vaultFolders(position: Int): List<VaultFolder> =
        VaultKeyringSession.withVaultKey(position, null) { vmk, id -> MetadataDb.load(store, id, vmk).folders }!!

    // --- the timeline ---------------------------------------------------------------------

    @Test
    fun rowsReadStageTimeAndModeNewestFirstWithThePhotosThumbnail() {
        stageTwo()
        ActivityScenario.launch(MainActivity::class.java).use {
            toTheTimeline()
            val photo = row(PHOTO_WHERE).fetchSemanticsNode()
            val audio = row(AUDIO_WHERE).fetchSemanticsNode()
            val photoWords = photo.config[SemanticsProperties.ContentDescription].joinToString()
            val audioWords = audio.config[SemanticsProperties.ContentDescription].joinToString()
            assertTrue("the photo row names its mode: $photoWords", photoWords.contains("Photo"))
            assertTrue("the audio row names its mode: $audioWords", audioWords.contains("Audio"))
            assertTrue("each row names its time: $photoWords", Regex("[0-9]{1,2}:[0-9]{2}").containsMatchIn(photoWords))
            assertTrue(
                "newest first: the audio a minute ago above the photo two minutes ago",
                audio.positionInRoot.y < photo.positionInRoot.y,
            )
            assertTrue("a day heading heads them", text("Today"))
            // The tile is the photograph's thumbnail: the store holds one for it.
            val photoItem = storeIndex().items.single { it.filename.endsWith(".jpg") }
            assertNotNull("the photo capture has a thumbnail to draw as its tile", photoItem.thumb)
        }
    }

    @Test
    fun aTapOpensTheViewerWithOnlyMoveExportAndDelete() {
        stageTwo()
        ActivityScenario.launch(MainActivity::class.java).use {
            toTheTimeline()
            row(PHOTO_WHERE).performClick()
            await("the viewer") { !described("Capture settings") }
            compose.waitForIdle()
            compose.onRoot().performClick()
            await("the viewer's actions") { described("Move to vault") }
            listOf("Move to vault", "Export", "Delete").forEach {
                assertTrue("the viewer offers \"$it\"", described(it))
            }
            listOf("Rename", "Rotate", "Edit image", "Share securely", "More").forEach {
                assertFalse("the viewer must not offer \"$it\"", described(it))
            }
        }
    }

    // --- move to vault --------------------------------------------------------------------

    @Test
    fun aPhotoMovesIntoAVaultsFolderAndItsRowSaysWhere() {
        stageTwo()
        ActivityScenario.launch(MainActivity::class.java).use {
            toTheTimeline()
            row(PHOTO_WHERE).performClick()
            await("the viewer") { !described("Capture settings") }
            compose.onRoot().performClick()
            await("Move to vault") { described("Move to vault") }
            compose.onNodeWithContentDescription("Move to vault").performClick()

            await("Move to vault's list") { text("1 capture") && text(LOCKED) }
            compose.onNode(hasText(PLAIN) and hasClickAction()).performClick()
            await("the folder picker") { text(TRIPS) }
            compose.onNode(hasText("Move here") and hasClickAction()).performClick()

            await("the notice") { text("Moved to $PLAIN") }
            await("the row says where it went") { described("Moved to $PLAIN") }
            assertTrue("no vault may be left open", noVaultIsOpen())

            val trips = vaultFolders(0).single { it.name == TRIPS }
            val moved = vaultItems(0).single { it.filename.startsWith("intruder-") }
            assertTrue(moved.filename.endsWith(".jpg"))
            assertTrue("in the chosen folder", moved.folderId?.contentEquals(trips.folderId) == true)
            assertEquals("the store holds the audio alone", 1, storeIndex().items.size)
        }
    }

    @Test
    fun aCaptureMovesIntoAProtectedVaultAfterAWrongThenARightPassphrase() {
        stageTwo()
        ActivityScenario.launch(MainActivity::class.java).use {
            toTheTimeline()
            row(AUDIO_WHERE).performTouchInput { longClick() }
            await("the selection bar") { text("1 selected") }
            compose.onNodeWithContentDescription("Move to vault").performClick()
            await("Move to vault's list") { text(LOCKED) }
            compose.onNode(hasText(LOCKED) and hasClickAction()).performClick()

            await("the vault's passphrase sheet") { text("Open $LOCKED") }
            compose.onNode(hasSetTextAction()).performClick()
            compose.waitForIdle()
            compose.onNode(hasSetTextAction()).performTextInput("not the passphrase")
            compose.onNode(hasText("Open vault") and hasClickAction()).performScrollTo().performClick()
            await("the wrong passphrase's error") { text("That passphrase did not open $LOCKED.") }
            assertTrue("a wrong passphrase writes nothing", vaultItems(1, LOCKED_PASS).isEmpty())

            compose.onNode(hasSetTextAction()).performTextReplacement(LOCKED_PASS)
            compose.onNode(hasText("Open vault") and hasClickAction()).performScrollTo().performClick()
            // The root is the vault's top level, named as the vault — never "Home screen".
            await("the folder picker") { text("Move into \"$LOCKED\"") }
            assertFalse("the root must not read as the vault list", text("Home screen") || text("home screen"))
            compose.onNode(hasText("Move into \"$LOCKED\"") and hasClickAction()).performClick()

            await("the row says where it went") { described("Moved to $LOCKED") }
            assertTrue("no vault may be left open", noVaultIsOpen())
            val moved = vaultItems(1, LOCKED_PASS).single()
            assertTrue(moved.filename.startsWith("intruder-") && moved.filename.endsWith(".aac"))
            assertEquals("at the vault's top level", null, moved.folderId)
        }
    }

    @Test
    fun backingOutOfTheMoveWritesNothing() {
        stageTwo()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            toTheTimeline()
            row(PHOTO_WHERE).performTouchInput { longClick() }
            await("the selection bar") { text("1 selected") }
            compose.onNodeWithContentDescription("Move to vault").performClick()
            await("Move to vault's list") { text(PLAIN) }
            compose.onNode(hasText(PLAIN) and hasClickAction()).performClick()
            await("the folder picker") { text(TRIPS) }

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await("back on Move to vault") { text(LOCKED) }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await("back on the timeline") { described("Capture settings") || text("1 selected") }

            assertEquals("both captures are still held", 2, storeIndex().items.size)
            assertTrue("no vault changed", vaultItems(0).none { it.filename.startsWith("intruder-") })
            assertTrue("no vault may be left open", noVaultIsOpen())
        }
    }

    // --- delete and export ----------------------------------------------------------------

    @Test
    fun deleteConfirmsAndTheRowReadsDeleted() {
        stageTwo()
        ActivityScenario.launch(MainActivity::class.java).use {
            toTheTimeline()
            row(PHOTO_WHERE).performTouchInput { longClick() }
            await("the selection bar") { text("1 selected") }
            compose.onNodeWithContentDescription("Delete").performClick()
            await("the confirmation") { text("Delete 1 capture forever?") }
            assertTrue("it says the capture cannot be restored", text("cannot be restored"))
            compose.onNode(hasText("Delete forever") and hasClickAction()).performClick()

            await("the row reads Deleted") { described("Deleted") }
            assertEquals(1, storeIndex().items.size)
            assertTrue(storeIndex().items.single().filename.endsWith(".aac"))
        }
    }

    /**
     * To the Gallery, through the export screen and the keyring's passphrase. The folder target is
     * the same worker writing through the system's folder picker, which this harness cannot drive.
     */
    @Test
    fun anExportVerifiesAndTheRowReadsExported() {
        val name = stageTwo().also { exported += it }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            toTheTimeline()
            row(PHOTO_WHERE).performTouchInput { longClick() }
            await("the selection bar") { text("1 selected") }
            compose.onNodeWithContentDescription("Export").performClick()
            await("the export screen") { text("removed from Intruder capture") }
            // Focus first, then type — `performTextInput` on an unfocused field lands nowhere.
            val field = compose.onNode(hasSetTextAction())
            field.performClick()
            compose.waitForIdle()
            field.performTextInput(PASS)
            await("the export button to enable") {
                compose.onAllNodes(hasText("Export 1 capture") and hasClickAction() and isEnabled())
                    .fetchSemanticsNodes().isNotEmpty()
            }
            // Below the fold with the keyboard up: scrolled to, so the tap lands on the button.
            compose.onNode(hasText("Export 1 capture") and hasClickAction()).performScrollTo().performClick()
            await("the export to finish", timeoutMs = 90_000) { text("1 of 1 out") }

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await("the row reads Exported") { described("Exported") }
            assertTrue("the photograph is in the gallery", MediaStoreDestination(ctx).find(name) != null)
            assertEquals(1, storeIndex().items.size)
        }
    }

    // --- empty states ---------------------------------------------------------------------

    @Test
    fun bothEmptyStatesSayWhatIsTrueAndOfferOneAction() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            compose.openIntruderCaptureFromHome()
            await("the empty state with nothing on") { text("Nothing captured") }
            assertTrue(text("Turn on a mode and the phone quietly records whoever fails to unlock it."))
            assertTrue(compose.onAllNodes(hasText("Choose what to record") and hasClickAction()).fetchSemanticsNodes().size == 1)

            // Photo on, nothing captured yet.
            val key = VaultKeyringSession.captureStoreKey()!!
            VaultKeys.captureKeypair(key).let { kp ->
                Capture.setDestination(ctx, kp.publicKey)
                kp.zero()
            }
            key.fill(0)
            Capture.setEnabled(ctx, Capture.Mode.PHOTO, true)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await("the vault list") { described("Menu") }
            compose.openIntruderCaptureFromHome()
            await("the empty state with a mode on") { text("Nothing captured yet") }
            assertTrue(text("Photo on. A capture appears here after a failed unlock."))
            assertTrue(compose.onAllNodes(hasText("Change what is recorded") and hasClickAction()).fetchSemanticsNodes().size == 1)
        }
    }

    // --- the badge ------------------------------------------------------------------------

    @Test
    fun theBadgeIsOnTheHomeDrawerControlAndRowAndOnlyTheScreenClearsIt() {
        stageTwo()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            await("the home bar's badge") { described("An intruder capture is waiting") }

            compose.onNodeWithContentDescription("Menu").performClick()
            await("the drawer's row") { text("Intruder capture") }
            assertTrue("the row carries the badge in words", text("An intruder capture is waiting"))
            // Opening the drawer is not looking.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            assertTrue("closing the drawer leaves the badge", described("An intruder capture is waiting"))

            compose.openIntruderCaptureFromHome()
            await("the rows") { described(PHOTO_WHERE) }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await("the vault list") { described("Menu") }
            await("the badge to clear") { !described("An intruder capture is waiting") }
            assertTrue((storeIndex().capturesSeenAt ?: 0L) >= storeIndex().items.maxOf { it.importedAt })
        }
    }

    // --- `per-vault-intruder-capture` ------------------------------------------------------

    private val instr get() = InstrumentationRegistry.getInstrumentation()

    /** Photo on at home, sealed to this keyring — what turning its switch on leaves. */
    private fun homePhotoOn() = withKeyring {
        val key = VaultKeyringSession.captureStoreKey()!!
        VaultKeys.captureKeypair(key).let { Capture.setDestination(ctx, it.publicKey); it.zero() }
        key.fill(0)
        Capture.setEnabled(ctx, Capture.Mode.PHOTO, true)
    }

    private fun cameraHeld() =
        ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Android's unused-app reset applies to this app (true) or it is exempt (false). */
    private fun autoReset(on: Boolean) {
        instr.uiAutomation.executeShellCommand(
            "appops set ${ctx.packageName} AUTO_REVOKE_PERMISSIONS_IF_UNUSED ${if (on) "allow" else "ignore"}",
        ).close()
    }

    private fun autoResetDefault() {
        instr.uiAutomation.executeShellCommand(
            "appops set ${ctx.packageName} AUTO_REVOKE_PERMISSIONS_IF_UNUSED default",
        ).close()
    }

    private val lost = "Photo is not recording. The camera permission is off."
    private val reset = "Android may remove this app's permissions if it goes unused for a few months, which would stop capture."

    @Test
    fun aVaultsTimelineCarriesItsNameAndItsVaultPassphraseRows() {
        withKeyring {
            val entry = VaultKeyringSession.vaults().single { it.name == LOCKED }
            val kek = VaultKeyringSession.entryPassphraseKek(LOCKED_PASS.toByteArray(Charsets.UTF_8), entry.position, entry.kdfSet)!!
            val id = try {
                enrolVaultCapture(store, entry.position, kek, setOf(Capture.Mode.PHOTO))
            } finally {
                kek.fill(0)
            }
            val now = System.currentTimeMillis()
            queueVaultCapture(store, id, Capture.Mode.PHOTO, capturePhotoBytes(), now - 120_000)
            queueVaultCapture(store, id, Capture.Mode.PHOTO, capturePhotoBytes(), now - 60_000)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            compose.openProtectedVaultFromTheList(LOCKED, LOCKED_PASS)
            compose.openVaultIntruderCapture()
            await("the vault's rows") {
                compose.onAllNodes(hasContentDescription("Vault passphrase: wrong passphrase", substring = true))
                    .fetchSemanticsNodes().size == 2
            }
            assertTrue("the vault is named under the title", described("Vault: $LOCKED"))
            assertFalse("home's timeline is not this one", described(PHOTO_WHERE))
        }
    }

    @Test
    fun aModeWithoutItsPermissionRaisesTheBannerAndTurnBackOnAsks() {
        assumeTrue("the camera permission is held; this needs it missing", !cameraHeld())
        autoReset(on = false)
        homePhotoOn()
        val asked = instr.addMonitor(android.content.IntentFilter("android.content.pm.action.REQUEST_PERMISSIONS"), null, true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                unlock()
                compose.openIntruderCaptureFromHome()
                await("the permission banner") { text(lost) }
                assertFalse("exempt from the reset, so no second banner", text(reset))
                compose.onNode(hasText("Turn back on") and hasClickAction()).performClick()
                await("the permission request") { asked.hits > 0 }
            }
        } finally {
            instr.removeMonitor(asked)
            autoResetDefault()
        }
    }

    @Test
    fun theAutoResetBannerOpensAndroidsSetting() {
        instr.uiAutomation.grantRuntimePermission(ctx.packageName, android.Manifest.permission.CAMERA)
        autoReset(on = true)
        assumeTrue("the app still reads as exempt", !ctx.packageManager.isAutoRevokeWhitelisted)
        homePhotoOn()
        // The intent carries the app as `package:` data, so the filter names the scheme to match it.
        val opened = instr.addMonitor(
            android.content.IntentFilter(android.content.Intent.ACTION_AUTO_REVOKE_PERMISSIONS).apply { addDataScheme("package") },
            null, true,
        )
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                unlock()
                compose.openIntruderCaptureFromHome()
                await("the auto-reset banner") { text(reset) }
                assertFalse("the camera is held, so no permission banner", text(lost))
                compose.onNode(hasText("Keep permissions") and hasClickAction()).performClick()
                await("Android's setting") { opened.hits > 0 }
            }
        } finally {
            instr.removeMonitor(opened)
            autoResetDefault()
        }
    }

    @Test
    fun withBothDueThePermissionBannerComesFirst() {
        assumeTrue("the camera permission is held; this needs it missing", !cameraHeld())
        autoReset(on = true)
        try {
            assumeTrue("the app still reads as exempt", !ctx.packageManager.isAutoRevokeWhitelisted)
            homePhotoOn()
            ActivityScenario.launch(MainActivity::class.java).use {
                unlock()
                compose.openIntruderCaptureFromHome()
                await("both banners") { text(lost) && text(reset) }
                val first = compose.onNode(hasText(lost)).fetchSemanticsNode().positionInRoot.y
                val second = compose.onNode(hasText(reset)).fetchSemanticsNode().positionInRoot.y
                assertTrue("lost permission first, then the reset", first < second)
            }
        } finally {
            autoResetDefault()
        }
    }
}
