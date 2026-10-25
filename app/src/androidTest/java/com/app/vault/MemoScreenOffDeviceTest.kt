package com.app.vault

import android.Manifest
import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.app.vault.ui.memoPlayer
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

/**
 * A memo keeps recording when the screen turns off — `destination-settings-and-slideshow` D5, D6,
 * `voice-memo`. In the real app, with the real lock: the screen is put to sleep with
 * `KEYCODE_SLEEP`, the vault locks and its key goes, and the recording carries on behind the
 * notification. **Stop is the notification's own action**, sent as the shade would send it.
 *
 * **A real vault in the app's own `filesDir`**, made only when none exists. The microphone and
 * notification grants are left for the run's cleanup: revoking one kills the process under test.
 */
@RunWith(AndroidJUnit4::class)
class MemoScreenOffDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")
    private val store get() = BlobStore(blobsDir, incoming)

    private var created = false
    private lateinit var indexId: ByteArray

    private companion object {
        const val PASS_TEXT = "memo screen off passphrase"
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instr.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8).trim() }

    @Before
    fun setUp() {
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        val lifecycle = VaultLifecycle(slots, store)
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        lifecycle.create(PASS_TEXT.toByteArray(Charsets.UTF_8))
        created = true
        indexId = VaultSession.metadataBlobId()!!.copyOf()
        lockKeyringAndVault()
        instr.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.RECORD_AUDIO)
        instr.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        wake()
    }

    @After
    fun tearDown() {
        if (!created) return
        wake()
        if (VoiceMemo.current != null) VoiceMemo.discard(ctx, store)
        MemoRecordingService.stop(ctx)
        MemoPending.resetForTest(ctx)
        lockKeyringAndVault()
        WorkManager.getInstance(ctx).cancelAllWork()
        MetadataDb.awaitQuiescent()
        slots.delete()
        blobsDir.deleteRecursively()
        incoming.deleteRecursively()
        // The grants stay until the task's own cleanup: revoking a runtime permission kills the
        // app's process, and the instrumentation runs in it.
    }

    private fun wake() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        val until = System.currentTimeMillis() + 5_000L
        while (!interactive() && System.currentTimeMillis() < until) Thread.sleep(100)
    }

    private fun interactive() = ctx.getSystemService(PowerManager::class.java).isInteractive

    private fun await(what: String, ms: Long = 45_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = ms, condition = condition)
        } catch (e: Throwable) {
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun showing(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun described(text: String) =
        compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()

    private fun unlock() {
        await("the lock screen") { showing("Welcome back") }
        val field = compose.onNode(hasText("Passphrase"))
        field.performClick()
        field.performTextInput(PASS_TEXT)
        await("the passphrase to register") {
            compose.onAllNodes(hasText("Unlock") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Unlock").performClick()
        compose.openFirstVaultFromTheList()
    }

    private fun startRecording() {
        await("the grid") { described("All vaults") }
        compose.onNodeWithContentDescription("Actions").performClick()
        await("the actions") { showing("Voice memo") }
        compose.onNodeWithText("Voice memo").performClick()
        await("the record control") { described("Start recording") }
        compose.onNodeWithContentDescription("Start recording").performClick()
        await("recording") { described("Stop recording") }
    }

    /** The screen goes off, the vault locks, and the key goes — while the memo records on. */
    private fun sleepAndAwaitTheLock() {
        shell("input keyevent KEYCODE_SLEEP")
        val deadline = System.currentTimeMillis() + 15_000L
        while ((interactive() || VaultSession.isUnlocked) && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertFalse("the screen went off", interactive())
        assertFalse("the vault locked", VaultSession.isUnlocked)
        assertEquals("no vault key is held", null, VaultSession.vmk())
        assertTrue("the memo is still recording", VoiceMemo.current?.recording == true)
    }

    /** The recording's notification, as the shade has it. */
    private fun recordingNotification() = ctx.getSystemService(NotificationManager::class.java)
        .activeNotifications.firstOrNull { it.id == MemoRecordingService.NOTIFICATION_ID }?.notification

    @UnstableApi
    @Test
    fun aMemoRecordsPastTheScreenTimeoutAndStopsFromTheShade() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            startRecording()
            await("the notification") { recordingNotification() != null }
            val shown = recordingNotification()!!
            assertEquals(ctx.getString(MemoRecordingService.TITLE), shown.extras.getString(android.app.Notification.EXTRA_TITLE))
            assertEquals("one action, Stop", listOf("Stop"), shown.actions.map { it.title.toString() })

            sleepAndAwaitTheLock()
            Thread.sleep(62_000L)
            assertTrue("still recording past a minute with the screen off", VoiceMemo.current?.recording == true)

            // The shade's Stop, exactly as the shade sends it.
            recordingNotification()!!.actions.single().actionIntent.send()
            val stopped = System.currentTimeMillis() + 15_000L
            while (VoiceMemo.current != null && System.currentTimeMillis() < stopped) Thread.sleep(200)
            assertEquals("stopped locked, nothing is held and the key is gone", null, VoiceMemo.current)
            assertEquals("the memo waits, sealed, for its vault", 1, MemoPending.ids(ctx).size)
            val gone = System.currentTimeMillis() + 10_000L
            while (recordingNotification() != null && System.currentTimeMillis() < gone) Thread.sleep(200)
            assertEquals("the notification goes", null, recordingNotification())

            wake()
            unlock()
            await("the grid") { described("All vaults") }
            val vmk = VaultSession.vmk()!!
            val memo = MetadataDb.load(store, indexId, vmk).items.single { it.kind == VaultKind.AUDIO }
            assertTrue("the record is gone", MemoPending.ids(ctx).isEmpty())
            val duration = durationOf(WrittenItem(memo.blobIds, memo.plaintextLength), vmk)
            assertTrue("the memo is longer than a minute: $duration ms", duration > 60_000L)
        }
    }

    @Test
    fun unlockingMidRecordingReturnsToTheRecordingScreen() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            startRecording()
            sleepAndAwaitTheLock()
            Thread.sleep(5_000L)
            wake()
            unlock()
            await("the recording screen") { described("Stop recording") }
            assertTrue("it says so in words", showing("Recording"))
            assertTrue("the clock carried on through the lock", (VoiceMemo.current?.elapsedMs() ?: 0L) > 5_000L)
            compose.onNodeWithContentDescription("Stop recording").performClick()
            await("listen-back") { showing("Recorded") }
        }
    }

    @UnstableApi
    private fun durationOf(item: WrittenItem, key: ByteArray): Long {
        lateinit var player: ExoPlayer
        instr.runOnMainSync { player = memoPlayer(ctx, store, item, key) }
        try {
            val until = System.currentTimeMillis() + 15_000L
            var state = Player.STATE_IDLE
            while (state != Player.STATE_READY && System.currentTimeMillis() < until) {
                instr.runOnMainSync { state = player.playbackState }
                Thread.sleep(50L)
            }
            var duration = 0L
            instr.runOnMainSync { duration = player.duration }
            return duration
        } finally {
            instr.runOnMainSync { player.release() }
        }
    }
}
