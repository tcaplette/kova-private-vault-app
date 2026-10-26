package com.app.vault

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.WindowManager
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The README's eight screenshots — `public-repository`, design D6. Run alone, by name, against
 * the **release** build, so the screens are what a user's copy shows: no debuggable-build warning.
 * The release APK is signed with the local debug key, as the test APK is, so the test can drive
 * it; the test APK carries no app classes and runs against whichever build is installed:
 *
 *     ./gradlew :app:assembleGithubRelease :app:assembleGithubDebugAndroidTest
 *     apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
 *       --out app-release-local.apk app/build/outputs/apk/github/release/app-github-release-unsigned.apk
 *     adb -s emulator-5554 install -r app-release-local.apk
 *     adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/github/debug/app-github-debug-androidTest.apk
 *     adb -s emulator-5554 shell am instrument -w -e class com.app.vault.ReadmeScreenshots \
 *       com.app.vault.test/androidx.test.runner.AndroidJUnitRunner
 *
 * It builds a demo vault through the app's own vault APIs with the throwaway passphrase [PASS],
 * fills it from `assets/readme-demo/` (see `SOURCES.md` there), and captures through `uiAutomation`
 * with `FLAG_SECURE` cleared on each activity it drives, writing with `dd` to [DIR]. The covers do
 * not read the vault — the music player reads the device's `MediaStore`, the recorder its own
 * `recordings/` — so the demo tracks and memos go where each cover reads them. With each cover
 * worn, the launcher's all-apps page is captured too (`launcher-icon-artwork`, task 4.5).
 *
 * [cleanUp] removes all of it: the tracks' `MediaStore` rows, the recordings, every cover trigger,
 * and the demo vault, destroyed the way the app's Destroy Vault does; the identity goes back to
 * the vault.
 */
@RunWith(AndroidJUnit4::class)
class ReadmeScreenshots {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val assets = instr.context.assets
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobs get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")
    private val store by lazy { BlobStore(blobs, incoming) }

    private var created = false
    private val tracks = mutableListOf<Uri>()

    private companion object {
        /** The demo vault's passphrase. Throwaway: the vault exists only for this run. */
        const val PASS = "readme demo throwaway passphrase"
        const val DIR = "/data/local/tmp/readme-screenshots"

        val PHOTOS = listOf(
            "photo-01.jpg" to "Coastline.jpg", "photo-02.jpg" to "Hills.jpg",
            "photo-03.jpg" to "Steppe.jpg", "photo-04.jpg" to "Agave.jpg",
            "photo-05.jpg" to "Coreopsis.jpg", "photo-06.jpg" to "Water lily.jpg",
            "photo-07.jpg" to "Snowfield.jpg", "photo-08.jpg" to "Dunes.jpg",
            "photo-09.jpg" to "Sandstone.jpg", "photo-10.jpg" to "Gazania.jpg",
            "photo-11.jpg" to "Okra flower.jpg", "photo-12.jpg" to "Lakeshore.jpg",
        )
        val NOTES = listOf("trip-packing.md" to "Coast trip packing", "garden-plan.md" to "Garden plan")
        val TRACKS = listOf("harbour-light.mp3", "paper-lanterns.mp3", "slow-tide.mp3")
        val MEMOS = listOf(
            Triple("memo-1.m4a", "Bike shop opening hours", 34_000L),
            Triple("memo-2.m4a", "Grocery list", 21_000L),
            Triple("memo-3.m4a", "Ideas for the garden", 47_000L),
        )
        val PATTERN = Trigger.Pattern(listOf(0, 4, 8, 2, 6))
        val CALC = Trigger.Keys(listOf("7", "3", "sin"))
    }

    private fun bytes(path: String) = assets.open("readme-demo/$path").use { it.readBytes() }

    @Before
    fun setUp() {
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        val lifecycle = VaultLifecycle(slots, store)
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        lifecycle.create(PASS.toByteArray(Charsets.UTF_8))
        created = true

        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!.copyOf()
        val thumbs = ThumbnailStore(store)
        val start = System.currentTimeMillis() - PHOTOS.size * 3_600_000L
        MetadataDb.commit(store, indexId, vmk) { db ->
            PHOTOS.forEachIndexed { i, (asset, name) ->
                val photo = bytes("photos/$asset")
                val written = store.writeItem(ByteArrayInputStream(photo), vmk)
                // No capture date: the files carry none, and P1-6 forbids inventing one.
                var item = VaultItem(
                    blobIds = written.blobIds,
                    plaintextLength = written.plaintextLength,
                    thumb = null,
                    filename = name,
                    album = null,
                    createdAt = null,
                    importedAt = start + i * 3_600_000L,
                    tags = emptyList(),
                )
                Thumbnails.generate(store, item, vmk).webp?.let { item = item.withThumb(thumbs.stage(db, it)) }
                db.items += item
            }
            NOTES.forEachIndexed { i, (asset, title) ->
                // The heading is the note's title; the body is what follows it.
                val body = String(bytes("notes/$asset"), Charsets.UTF_8).lines().drop(2).joinToString("\n").trim()
                db.items += VaultNote.create(store, vmk, title, body, now = start + (PHOTOS.size + i) * 3_600_000L)!!
            }
            thumbs.flush(db, vmk)
        }
        lockKeyringAndVault()

        instr.uiAutomation.grantRuntimePermission(ctx.packageName, MusicLibrary.permission)
        TRACKS.forEach { tracks += publishTrack(it) }
        MEMOS.forEachIndexed { i, (asset, title, ms) ->
            val at = System.currentTimeMillis() - (MEMOS.size - i) * 86_400_000L
            val file = RecorderStore.newFile(ctx, at)
            file.writeBytes(bytes("memos/$asset"))
            RecorderStore.add(ctx, file, ms, at)
            RecorderStore.rename(ctx, RecorderStore.recordings(ctx).single { it.name == file.name }, title)
        }
    }

    /** One demo track into `Music/Kova demo/`, as the app's own row, scanned by the provider. */
    private fun publishTrack(asset: String): Uri {
        val resolver = ctx.contentResolver
        val uri = resolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, asset)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Kova demo/")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            },
        )!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes("music/$asset")) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    @After
    fun cleanUp() {
        runCatching { compose.waitForIdle() }
        tracks.forEach { runCatching { ctx.contentResolver.delete(it, null, null) } }
        RecorderStore.recordings(ctx).forEach { RecorderStore.delete(ctx, it) }
        Cover.entries.forEach {
            CoverTrigger.clear(ctx, it)
            CoverTrigger.clearAttempts(ctx, it)
        }
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        LauncherIdentity.reconcile(ctx)
        lockKeyringAndVault()
        if (!created) return
        // Destroy Vault's own path: the gate's key for the vault, then its removal from the list.
        val lifecycle = VaultLifecycle(slots, store)
        if (lifecycle.unlock(PASS.toByteArray(Charsets.UTF_8)).opened) {
            VaultKeyringSession.vaults().map { it.position }.forEach { position ->
                lifecycle.destroyKey(position, PASS.toByteArray(Charsets.UTF_8))?.let { key ->
                    VaultKeyringSession.removeVault(store, position, key)
                    key.fill(0)
                }
            }
            assertTrue("the demo vault is gone from the list", VaultKeyringSession.vaults().isEmpty())
        }
        lockKeyringAndVault()
        slots.delete()
        blobs.deleteRecursively()
        incoming.deleteRecursively()
    }

    // --- capture -------------------------------------------------------------

    private fun await(what: String, timeout: Long = 45_000, condition: () -> Boolean) = try {
        compose.waitUntil(timeout, condition)
    } catch (e: Throwable) {
        // What was on screen instead, for the run's log and as a picture.
        runCatching { save("timeout", instr.uiAutomation.takeScreenshot()) }
        runCatching {
            compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().indices.forEach {
                compose.onAllNodes(androidx.compose.ui.test.isRoot())[it].printToLog("ReadmeShots")
            }
        }
        throw AssertionError("timed out waiting for $what", e)
    }

    private fun showing(text: String) =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun described(text: String) =
        compose.onAllNodes(hasContentDescription(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun launch(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }

    private fun shot(name: String) {
        compose.waitForIdle()
        pause(1_200)
        save(name, instr.uiAutomation.takeScreenshot())
    }

    private fun save(name: String, bmp: Bitmap) {
        val png = java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val ui = instr.uiAutomation
        ui.executeShellCommand("mkdir -p $DIR").use { drain(it) }
        // `dd`, not `tee` — CLAUDE.md rule 21.
        val (stdout, stdin) = ui.executeShellCommandRw("dd of=$DIR/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(png) }
        drain(stdout)
    }

    private fun drain(fd: android.os.ParcelFileDescriptor) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    private fun shell(command: String) = instr.uiAutomation.executeShellCommand(command).use { drain(it) }

    /** The launcher's all-apps page with [identity] worn — the entry a person sees. */
    private fun launcherShot(identity: LauncherIdentity) {
        instr.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        Thread.sleep(2_000)
        shell("input swipe 540 1900 540 400 300")
        Thread.sleep(2_000)
        save("launcher-${identity.name.lowercase()}", instr.uiAutomation.takeScreenshot())
        instr.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        Thread.sleep(1_000)
    }

    private fun wear(identity: LauncherIdentity) {
        identity.cover?.let { CoverTrigger.set(ctx, it, if (it == Cover.Calculator) CALC else PATTERN, area = if (it == Cover.Calculator) null else 0) }
        LauncherIdentity.switchTo(ctx, identity)
        LauncherIdentity.reconcile(ctx)
        launcherShot(identity)
    }

    private fun unlockToList() {
        await("the lock screen") { showing("Welcome back") }
        compose.waitForIdle()
        val field = compose.onNode(hasText("Passphrase"))
        field.performClick()
        compose.waitForIdle()
        field.performTextInput(PASS)
        await("the passphrase to register") {
            compose.onAllNodes(hasText("Unlock") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Unlock").performClick()
    }

    /** A calculator key: one clickable node, its label merged into it. */
    private fun key(symbol: String) =
        compose.onAllNodes(hasText(symbol) and hasClickAction()).onLast().performClick()

    /**
     * Real time passing with the Compose clock running. `Thread.sleep` would freeze that clock,
     * and with it the player's elapsed time and every animation on screen.
     */
    private fun pause(ms: Long) {
        val until = System.currentTimeMillis() + ms
        compose.waitUntil(ms + 5_000) { System.currentTimeMillis() >= until }
    }

    @Test
    fun theEightReadmeScreens() {
        launch().use { scenario ->
            // 1. Unlock — with the keyboard put away, so the screen itself is what is shown.
            await("the lock screen") { showing("Welcome back") }
            pause(1_000)
            scenario.onActivity { activity ->
                activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            }
            pause(1_000)
            shot("1-unlock")

            // 2. The vault grid, once every photograph's thumbnail is drawn.
            unlockToList()
            compose.openFirstVaultFromTheList()
            // The grid is lazy: the newest photograph is the one on screen to wait for.
            await("the grid") { described("Lakeshore.jpg") }
            pause(2_000)
            shot("2-grid")

            // 3. A photograph.
            compose.onNode(hasContentDescription("Gazania.jpg")).performClick()
            await("the viewer") { !described("All vaults") }
            pause(1_500)
            shot("3-photo")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await("the grid again") { described("All vaults") }

            // 4. A note, from the Notes tab.
            compose.onNodeWithText("Notes").performClick()
            await("the notes") { showing("Garden plan") }
            compose.onAllNodes(hasText("Garden plan", substring = true)).onLast().performClick()
            await("the note") { showing("sweet peas") }
            shot("4-note")
        }
        lockKeyringAndVault()

        // 5. The music player, playing a demo track.
        wear(LauncherIdentity.MusicPlayer)
        launch().use {
            await("the demo tracks") { showing("Harbour Light") }
            compose.onAllNodes(hasText("Harbour Light")).onLast().performClick()
            await("playback") { described("Pause") || showing("Kova Demo") }
            pause(1_000)
            compose.onAllNodes(hasText("Harbour Light")).onLast().performClick()
            await("the player") { described("Close player") }
            pause(4_000)
            shot("5-music")
        }

        // 6. The calculator.
        wear(LauncherIdentity.Calculator)
        launch().use {
            await("the calculator") { showing("×") }
            listOf("1", "2", "8", "×", "4", "5", "=").forEach { key(it) }
            pause(500)
            shot("6-calculator")
        }

        // 7. The voice recorder.
        wear(LauncherIdentity.VoiceRecorder)
        launch().use {
            await("the recordings") { showing("Grocery list") }
            shot("7-recorder")
        }

        // 8. The identity picker, from the vault's own home drawer.
        wear(LauncherIdentity.Vault)
        launch().use {
            unlockToList()
            await("the home screen") { described("Menu") }
            compose.onNode(hasContentDescription("Menu")).performClick()
            await("the drawer") { showing("App identity") }
            compose.onAllNodes(hasText("App identity", substring = true)).onLast().performClick()
            await("the identity picker") { showing("Voice recorder") || showing("Calculator") }
            shot("8-identity")
        }
    }
}
