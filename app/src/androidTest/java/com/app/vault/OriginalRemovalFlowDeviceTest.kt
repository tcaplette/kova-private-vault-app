package com.app.vault

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * `remove-originals-on-import` end to end — tasks 4.2 and 4.3. The real photo picker, the
 * real MediaStore, and Android's real delete dialog, driven from here.
 *
 * **The originals are the shell's, as a camera's photographs are someone else's.** Each test
 * copies master files from `/data/local/tmp/vaulttest` into the gallery as the shell user,
 * dates them in a year of its own so they lead the picker and can be picked by it, and has
 * MediaStore scan them. Teardown deletes whatever is left.
 *
 * **The app's own `filesDir`**, because that is the only vault `MainActivity` opens — and
 * skipped, like every screen suite, rather than overwriting a vault already there.
 *
 * The picker and Android's dialog are other apps' windows: they are reached through
 * `uiAutomation`'s accessibility tree and tapped with `input`, because the Compose rule
 * sees only this app.
 */
@RunWith(AndroidJUnit4::class)
class OriginalRemovalFlowDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")

    private var created = false
    private var identityBefore: LauncherIdentity? = null
    private lateinit var store: BlobStore
    private lateinit var indexId: ByteArray
    private val staged = mutableListOf<String>()

    private companion object {
        const val TAG = "OriginalRemovalFlow"
        const val PASS_TEXT = "correct horse battery staple"
        const val SHOTS = "/data/local/tmp/remove-originals"
        const val MASTER = "/data/local/tmp/vaulttest"
        const val SCRIPT = "/data/local/tmp/remove-originals.sh"
        const val PREFIX = "ro_"
    }

    @Before
    fun setUp() {
        store = BlobStore(blobsDir, incoming)
        val lifecycle = VaultLifecycle(slots, store)
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        assumeTrue("the master media is not on the device", sh("ls $MASTER").contains("photo.jpg"))
        // `ViewerChromeDeviceTest`'s reason: after a cover suite, the launch is a cover's.
        // Put back in teardown, because it is the installed app's preference.
        identityBefore = GridPreference.identity(ctx)
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        lifecycle.create(PASS_TEXT.toByteArray(Charsets.UTF_8))
        created = true
        indexId = VaultSession.metadataBlobId()!!.copyOf()
        lockKeyringAndVault()
        // Every window, not only the "active" one: the picker is a sheet over this app,
        // and the active window is not reliably the one on top.
        val ui = instr.uiAutomation
        ui.serviceInfo = ui.serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
    }

    @After
    fun tearDown() {
        lockKeyringAndVault()
        staged.forEach { sh("rm -f '$it'\nam broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file://$it'") }
        staged.clear()
        if (created) {
            WorkManager.getInstance(ctx).cancelAllWork()
            MetadataDb.awaitQuiescent()
            slots.delete()
            blobsDir.deleteRecursively()
            incoming.deleteRecursively()
        }
        identityBefore?.let {
            LauncherIdentity.switchTo(ctx, it)
            // The switch only enables; the departure pass leaves exactly one entry.
            LauncherIdentity.reconcile(ctx)
        }
    }

    // --- the shell ------------------------------------------------------------

    /**
     * Runs [script] through `sh`. `executeShellCommand` splits on spaces and honours no
     * quoting, so anything with a quote in it goes through a script file.
     */
    private fun sh(script: String): String {
        val ui = instr.uiAutomation
        // `dd`, not `tee`: tee echoes the bytes to stdout, read only after the write, so a
        // large write fills the pipes and both ends block — the screenshot hang, same shape.
        val (out, input) = ui.executeShellCommandRw("dd of=$SCRIPT")
        ParcelFileDescriptor.AutoCloseOutputStream(input).use { it.write(script.toByteArray()) }
        ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
        return ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("sh $SCRIPT"))
            .use { String(it.readBytes()) }
    }

    private fun galleryRows(): String = sh(
        "content query --uri content://media/external/file " +
            "--projection _id:_display_name:date_modified --where \"_display_name like '$PREFIX%'\""
    )

    /** How many of [names] MediaStore still holds. */
    private fun present(names: List<String>): Int {
        val rows = galleryRows()
        return names.count { rows.contains("_display_name=$it,") }
    }

    /**
     * Puts a photograph or video into the gallery as the shell user, dated in [year] so it
     * leads the picker and is picked by it, and waits until MediaStore has scanned it.
     */
    private fun stage(year: Int, minute: Int, video: Boolean = false): String {
        val name = "$PREFIX${year}_$minute.${if (video) "mp4" else "jpg"}"
        val dir = if (video) "/sdcard/Movies/VaultTest" else "/sdcard/Pictures/VaultTest"
        val path = "$dir/$name"
        sh(
            "mkdir -p $dir\n" +
                "cp $MASTER/${if (video) "video.mp4" else "photo.jpg"} $path\n" +
                "touch -d ${year}-01-01T12:${"%02d".format(minute)}:00 $path\n" +
                "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$path"
        )
        staged += path
        waitFor("MediaStore to scan $name") {
            galleryRows().lines().any { it.contains("_display_name=$name,") && !it.contains("date_modified=NULL") }
        }
        return name
    }

    // --- other apps' windows ----------------------------------------------------

    /**
     * [node] and everything under it, each **refreshed** as it is visited. The cached tree
     * is stale for another app's window: the picker's Done bar was on screen, in a capture,
     * while the cached nodes still described the sheet before anything was selected.
     */
    private fun nodes(node: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (node == null || !node.refresh()) return@sequence
        yield(node)
        for (i in 0 until node.childCount) yieldAll(nodes(node.getChild(i)))
    }

    /** Every node on screen, in every app's windows. */
    private fun activeNodes(): Sequence<AccessibilityNodeInfo> {
        val roots = instr.uiAutomation.windows.mapNotNull { it.root }
            .ifEmpty { listOfNotNull(instr.uiAutomation.rootInActiveWindow) }
        return roots.asSequence().flatMap { nodes(it) }
    }

    private fun waitFor(what: String, timeoutMs: Long = 45_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        var last: Throwable? = null
        while (SystemClock.uptimeMillis() < end) {
            val result = runCatching(condition)
            if (result.getOrDefault(false)) return
            result.exceptionOrNull()?.let { if (last?.javaClass != it.javaClass) Log.w(TAG, "$what: $it") }
            last = result.exceptionOrNull()
            SystemClock.sleep(250)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val r = Rect().also { node.getBoundsInScreen(it) }
        sh("input tap ${r.centerX()} ${r.centerY()}")
    }

    /**
     * Clicks [node] through the accessibility tree — its first clickable ancestor, which in
     * the picker is the tile around the described image — and taps its centre only if no
     * ancestor takes the click. A coordinate lands wherever the sheet has moved to since.
     */
    private fun press(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) tap(node)
    }

    /** The picker's tiles for [year] — "Photo taken on Jan 1, 2031 11:03 AM". */
    private fun tiles(year: Int) = activeNodes().filter {
        val d = it.contentDescription?.toString().orEmpty()
        d.contains("taken on") && d.contains(", $year ")
    }.toList()

    private fun button(label: String) =
        activeNodes().firstOrNull { it.text?.toString() == label }

    /** Media's `+`, the picker, the [count] tiles dated [year], and Done. */
    private fun pickFromGallery(year: Int, count: Int) {
        compose.onAllNodes(hasContentDescription("Add photos or videos") or hasText("Add media"))[0]
            .performClick()
        try {
            waitFor("the picker to show $count items from $year") {
                val found = tiles(year).size
                Log.i(
                    TAG,
                    "windows ${instr.uiAutomation.windows.map { it.root?.packageName }}, " +
                        "$found tiles for $year, descriptions " +
                        activeNodes().mapNotNull { it.contentDescription?.toString() }
                            .filter { "taken" in it }.take(6).toList(),
                )
                found == count
            }
        } catch (e: AssertionError) {
            shot("picker-timeout")
            throw e
        }
        // The sheet slides up after its tiles exist, so bounds read at once are where the
        // tile was, not where it lands. And the picker takes a tap, not an accessibility
        // click, as a selection. So: let it settle, read the bounds again, tap.
        SystemClock.sleep(1_500)
        tiles(year).forEach { tap(it); SystemClock.sleep(400) }
        try {
            waitFor("the picker's Done") { button("Done") != null }
        } catch (e: AssertionError) {
            Log.i(TAG, "on screen: " + activeNodes().mapNotNull { it.text?.toString() }.toList())
            shot("done-timeout")
            throw e
        }
        press(button("Done")!!)
    }

    /** Answers Android's delete dialog — MediaProvider's `allow` and `deny`. */
    private fun answerAndroid(label: String, shotName: String? = null) {
        waitFor("Android's dialog") { button(label) != null }
        shotName?.let { shot(it) }
        press(button(label)!!)
    }

    // --- this app ----------------------------------------------------------------

    private fun has(text: String) =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun showing(description: String) =
        compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()

    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 60_000, condition = condition)
        } catch (e: Throwable) {
            runCatching {
                compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { i ->
                    compose.onAllNodes(isRoot())[i].printToLog(TAG)
                }
            }
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun unlock() {
        await("the lock screen") { has("Welcome back") }
        compose.waitForIdle()
        val field = compose.onNode(hasText("Passphrase"))
        field.performClick()
        compose.waitForIdle()
        field.performTextInput(PASS_TEXT)
        await("the passphrase to register") {
            compose.onAllNodes(hasText("Unlock") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Unlock").performClick()
        compose.openFirstVaultFromTheList()
        await("the grid") { showing("All vaults") }
        compose.waitForIdle()
    }

    private fun launched(body: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            unlock()
            body()
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        // The frame has to reach the display before uiAutomation reads it.
        SystemClock.sleep(700)
        val png = ByteArrayOutputStream().also {
            instr.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        sh("mkdir -p $SHOTS")
        // `dd`, not `tee`: tee echoes the bytes to stdout, read only after the write, so a
        // large PNG (the keyboard up) fills the pipes and both ends block. HangWatch caught it.
        val (stdout, stdin) = instr.uiAutomation.executeShellCommandRw("dd of=$SHOTS/$name.png")
        ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(png) }
        ParcelFileDescriptor.AutoCloseInputStream(stdout).use { it.readBytes() }
    }

    /** The `0x07` entries in this vault's log. */
    private fun sourceDeletions(): List<LogEntry> {
        val vmk = VaultSession.vmk()!!
        val db = MetadataDb.load(store, indexId, vmk)
        return TamperLog.scan(store, db.logBlobId!!, vmk).entries
            .filter { it.event == TamperLog.SOURCE_DELETED }
    }

    // --- 4.2 and 4.3 ---------------------------------------------------------------

    @Test
    fun cancellingChangesNothingAndAllowingRemovesThem() = launched {
        val names = listOf(stage(2031, 1), stage(2031, 2), stage(2031, 3), stage(2031, 4, video = true))
        pickFromGallery(2031, 4)

        await("the card") { has(ctx.getString(OriginalRemoval.TITLE)) }
        assertTrue(has("3 photos and 1 video are now in this vault. Android will ask you to confirm."))
        shot("flow-card")

        // Cancelled: nothing deleted, nothing logged, the card as it was.
        compose.onNodeWithText("Remove").performClick()
        answerAndroid("Deny", shotName = "flow-android-dialog")
        await("the card to be answerable again") {
            compose.onAllNodes(hasText("Remove") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(has(ctx.getString(OriginalRemoval.TITLE)))
        assertEquals("every original is still in the gallery", 4, present(names))
        assertTrue(sourceDeletions().isEmpty())

        // Allowed: the originals are gone, the line says so, the log has one entry of four.
        compose.onNodeWithText("Remove").performClick()
        answerAndroid("Allow")
        await("the removed line") { has(ctx.getString(OriginalRemoval.REMOVED)) }
        assertFalse(has(ctx.getString(OriginalRemoval.TITLE)))
        waitFor("MediaStore to drop the originals") { present(names) == 0 }
        waitFor("the log entry") { sourceDeletions().isNotEmpty() }
        val logged = sourceDeletions()
        assertEquals(1, logged.size)
        assertEquals(4, logged.single().count)
        shot("flow-removed")
        Log.i(TAG, "removed ${names.size}; gallery rows after: ${galleryRows().trim()}")
    }

    @Test
    fun keepLeavesTheOriginalsAndSaysNothing() = launched {
        val names = listOf(stage(2032, 1))
        pickFromGallery(2032, 1)

        await("the card") { has(ctx.getString(OriginalRemoval.TITLE)) }
        assertTrue(has("1 photo is now in this vault. Android will ask you to confirm."))
        compose.onNodeWithText("Keep").performClick()
        await("the card to leave") { !has(ctx.getString(OriginalRemoval.TITLE)) }
        assertFalse(has(ctx.getString(OriginalRemoval.REMOVED)))
        assertEquals(1, present(names))
        assertTrue(sourceDeletions().isEmpty())
        shot("flow-kept")
    }

    @Test
    fun lockingDiscardsTheOffer() = launched {
        val names = listOf(stage(2033, 1))
        pickFromGallery(2033, 1)
        await("the card") { has(ctx.getString(OriginalRemoval.TITLE)) }

        // Home to the list, and its Lock.
        compose.onNodeWithContentDescription("All vaults").performClick()
        await("the list's Lock") { showing("Lock") }
        compose.onNodeWithContentDescription("Lock").performClick()
        unlock()

        assertFalse("the offer did not survive the lock", has(ctx.getString(OriginalRemoval.TITLE)))
        assertEquals(1, present(names))
        shot("flow-after-lock")
    }

    @Test
    fun aSecondBatchJoinsTheOffer() = launched {
        stage(2034, 1)
        stage(2034, 2)
        pickFromGallery(2034, 2)
        await("the card") { has("2 photos are now in this vault. Android will ask you to confirm.") }

        stage(2035, 1)
        pickFromGallery(2035, 1)
        await("the merged card") { has("3 photos are now in this vault. Android will ask you to confirm.") }
        assertEquals(1, compose.onAllNodes(hasText(ctx.getString(OriginalRemoval.TITLE))).fetchSemanticsNodes().size)
        shot("flow-merged")
    }
}
