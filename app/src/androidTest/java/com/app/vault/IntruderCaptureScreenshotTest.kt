package com.app.vault

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `intruder-captures-at-home`, task 10.3 — real captures of every surface the change draws, in
 * the dark and the light scheme: the home drawer with the badged row, the timeline with photo,
 * audio, moved and deleted rows, both empty states, the capture sheet over the screen, the viewer's
 * three actions, Move to vault, the protected vault's passphrase sheet and the folder picker.
 *
 * The real activity, `FLAG_SECURE` cleared on it, captures from `uiAutomation`, written with `dd`
 * to [DIR]. Gated like `IntruderCaptureScreenDeviceTest`, for the keyring it makes and removes.
 */
@RunWith(AndroidJUnit4::class)
class IntruderCaptureScreenshotTest {

    // The v1 rule every UI suite here runs on.
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

    private companion object {
        const val DIR = "/data/local/tmp/intruder-captures-at-home"
        const val PASS = "correct horse battery staple"
        const val LOCKED_PASS = "a different staple entirely"
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
        lifecycle.create(PASS.toByteArray(Charsets.UTF_8))
        created = true
        VaultKeyringSession.leaveVault()
        VaultKeyringSession.createVault(store, "Locked") { position, set ->
            VaultKeyringSession.entryPassphraseKek(LOCKED_PASS.toByteArray(Charsets.UTF_8), position, set)!!
        }
        VaultKeyringSession.withVaultKey(0, null) { vmk, id ->
            VaultFolders.create(store, id, vmk, null, "Trips", VaultDestination.Media)
        }
        lockKeyringAndVault()
    }

    @After
    fun tearDown() {
        if (InstrumentationRegistry.getArguments().getString("destructive") != "true") return
        Capture.resetForTest(ctx)
        lockKeyringAndVault()
        ThumbnailCache.clear()
        WorkManager.getInstance(ctx).cancelAllWork()
        GridPreference.setAppearance(ctx, ThemeChoice.Default)
        if (!created) return
        MetadataDb.awaitQuiescent()
        slots.delete()
        blobsDir.deleteRecursively()
        incoming.deleteRecursively()
    }

    /**
     * Four captures taken in, one since moved and one deleted — so the timeline holds a photo, an
     * audio clip, a moved row and a deleted row, unseen.
     */
    private fun fillTheTimeline() {
        assertOpened()
        val now = System.currentTimeMillis()
        val dots = CaptureRecord.Stage(CaptureRecord.STAGE_COVER_PATTERN, CaptureRecord.FLAG_DOTS_SHOWN, CaptureRecord.COVER_MUSIC)
        val hidden = CaptureRecord.Stage(CaptureRecord.STAGE_COVER_PATTERN, 0, CaptureRecord.COVER_MUSIC)
        val limited = CaptureRecord.Stage(CaptureRecord.STAGE_COVER_PATTERN, CaptureRecord.FLAG_LIMITED or CaptureRecord.FLAG_DOTS_SHOWN, CaptureRecord.COVER_RECORDER)
        stageKeyringCapture(ctx, store, Capture.Mode.PHOTO, capturePhotoBytes(android.graphics.Color.rgb(110, 84, 60)), hidden, now - 240_000)
        stageKeyringCapture(ctx, store, Capture.Mode.PHOTO, capturePhotoBytes(android.graphics.Color.rgb(60, 90, 120)), limited, now - 180_000)
        stageKeyringCapture(ctx, store, Capture.Mode.AUDIO, ByteArray(6_000) { it.toByte() }, takenAt = now - 120_000)
        stageKeyringCapture(ctx, store, Capture.Mode.PHOTO, capturePhotoBytes(), dots, now - 60_000)
        val key = VaultKeyringSession.captureStoreKey()!!
        try {
            val (vault, _) = CaptureStore.takeIn(ctx, store, key)!!
            val items = MetadataDb.load(store, vault.metadataBlobId, key).items.sortedBy { it.createdAt }
            CaptureStore.remove(store, vault, listOf(items[0]), TamperLog.LEFT_MOVED, VaultKeyringSession.MIGRATED_NAME)
            CaptureStore.remove(store, vault, listOf(items[1]), TamperLog.LEFT_DELETED)
            MetadataDb.commit(store, vault.metadataBlobId, key) { db -> Thumbnails.backfill(store, ThumbnailStore(store), db, key); db }
        } finally {
            key.fill(0)
        }
        lockKeyringAndVault()
    }

    /** The previous pass opened the screen, which is looking; this pass needs the badge back. */
    private fun unseeAll() {
        assertOpened()
        val key = VaultKeyringSession.captureStoreKey()!!
        try {
            MetadataDb.commit(store, VaultKeys.metadataBlobId(key), key) { it.capturesSeenAt = null; it }
        } finally {
            key.fill(0)
        }
        lockKeyringAndVault()
    }

    private fun assertOpened() = check(lifecycle.unlock(PASS.toByteArray(Charsets.UTF_8)).opened)

    @Test
    fun theTimelineAndItsFlows() {
        fillTheTimeline()
        listOf(ThemeChoice.Dark, ThemeChoice.Light).forEach { theme ->
            unseeAll()
            GridPreference.setAppearance(ctx, theme)
            val p = theme.name.lowercase()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                unlock()
                await("the badge") { described("An intruder capture is waiting") }
                shot("$p-01-home-badge")

                compose.onNodeWithContentDescription("Menu").performClick()
                await("the drawer") { text("Intruder capture") }
                shot("$p-02-home-drawer-badged-row")
                compose.onAllNodes(hasText("Intruder capture"))[0].performClick()
                await("the timeline") { described("Music player: wrong pattern on the shown dots") }
                shot("$p-03-timeline")

                compose.onNodeWithContentDescription("Capture settings").performClick()
                await("the sheet") { text("A still from the front camera") }
                shot("$p-04-capture-sheet")
                backKey()
                await("the sheet to go") { !text("A still from the front camera") }

                compose.onNode(hasContentDescription("Music player: wrong pattern on the shown dots", substring = true)).performClick()
                await("the viewer") { !described("Capture settings") }
                compose.onRoot().performClick()
                await("the viewer's actions") { described("Move to vault") }
                shot("$p-05-viewer-actions")

                compose.onNodeWithContentDescription("Move to vault").performClick()
                await("Move to vault") { text("1 capture") }
                shot("$p-06-move-to-vault")

                compose.onNode(hasText("Locked") and hasClickAction()).performClick()
                await("the passphrase sheet") { text("Open Locked") }
                shot("$p-07-passphrase-sheet")
                backKey()
                await("the sheet to go") { !text("Open Locked") }

                compose.onNode(hasText(VaultKeyringSession.MIGRATED_NAME) and hasClickAction()).performClick()
                await("the folder picker") { text("Trips") }
                shot("$p-08-folder-picker")
                back(scenario)
                back(scenario)
                back(scenario)
            }
            lockKeyringAndVault()
        }
    }

    @Test
    fun bothEmptyStates() {
        listOf(ThemeChoice.Dark, ThemeChoice.Light).forEach { theme ->
            Capture.resetForTest(ctx)
            GridPreference.setAppearance(ctx, theme)
            val p = theme.name.lowercase()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                unlock()
                compose.openIntruderCaptureFromHome()
                await("nothing on") { text("Nothing captured") }
                shot("$p-09-empty-nothing-on")

                val key = VaultKeyringSession.captureStoreKey()!!
                VaultKeys.captureKeypair(key).let { kp -> Capture.setDestination(ctx, kp.publicKey); kp.zero() }
                key.fill(0)
                Capture.setEnabled(ctx, Capture.Mode.PHOTO, true)
                back(scenario)
                await("the vault list") { described("Menu") }
                compose.openIntruderCaptureFromHome()
                await("a mode on") { text("Nothing captured yet") }
                shot("$p-10-empty-mode-on")
            }
            lockKeyringAndVault()
        }
    }

    // --- harness --------------------------------------------------------------------------

    private fun await(what: String, condition: () -> Boolean) = try {
        compose.waitUntil(timeoutMillis = 45_000, condition = condition)
    } catch (e: Throwable) {
        throw AssertionError("timed out waiting for $what", e)
    }

    private fun described(text: String) =
        compose.onAllNodes(hasContentDescription(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun text(label: String) =
        compose.onAllNodes(hasText(label, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** A real back key — a sheet is its own window, which the activity's dispatcher never reaches. */
    private fun backKey() {
        instr.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").use { drain(it) }
        compose.waitForIdle()
    }

    private fun back(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
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

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(700)
        val png = java.io.ByteArrayOutputStream().also {
            instr.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val ui = instr.uiAutomation
        ui.executeShellCommand("mkdir -p $DIR").use { drain(it) }
        // `dd`, not `tee` — `tee` echoes the PNG back and deadlocks on a large one.
        val (stdout, stdin) = ui.executeShellCommandRw("dd of=$DIR/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(png) }
        drain(stdout)
    }

    private fun drain(fd: android.os.ParcelFileDescriptor) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }
}
