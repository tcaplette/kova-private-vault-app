package com.app.vault

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.app.vault.ui.CaptureStepScreen
import com.app.vault.ui.OnboardingFrame
import com.app.vault.ui.theme.VaultTheme
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `per-vault-intruder-capture`, task 8.3 — real captures of what the change draws, in the dark and
 * the light scheme: a protected vault's drawer with the badged row, an unprotected vault's drawer,
 * the vault's timeline with its subtitle, its "Vault passphrase" rows, a "Came out blank" row and a
 * permission row; both banners together; the sheet's "Not recording" row; and first run's capture
 * step with the auto-reset line.
 *
 * The real activity with `FLAG_SECURE` cleared, captured from `uiAutomation` and written with `dd`
 * to [DIR]; the capture step in a plain host, as `UnavailableStatesScreenshotTest` draws it. Gated
 * like `VaultCaptureDeviceTest`. It sets the app's unused-app reset app-op to `allow` for the
 * banners and puts it back to `default`.
 */
@RunWith(AndroidJUnit4::class)
class PerVaultCaptureScreenshotTest {

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
        const val DIR = "/data/local/tmp/per-vault-intruder-capture"
        const val PASS = "correct horse battery staple"
        const val PRIVATE = "Private"
        const val PRIVATE_PASS = "a different staple entirely"
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
        privatePosition = VaultKeyringSession.createVault(store, PRIVATE) { position, set ->
            VaultKeyringSession.entryPassphraseKek(PRIVATE_PASS.toByteArray(Charsets.UTF_8), position, set)!!
        }!!
        lockKeyringAndVault()
    }

    @After
    fun tearDown() {
        if (InstrumentationRegistry.getArguments().getString("destructive") != "true") return
        shell("appops set ${ctx.packageName} AUTO_REVOKE_PERMISSIONS_IF_UNUSED default")
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

    private fun assertOpened() = check(lifecycle.unlock(PASS.toByteArray(Charsets.UTF_8)).opened)

    private fun privateKek(): ByteArray {
        val entry = VaultKeyringSession.vaults().single { it.position == privatePosition }
        return VaultKeyringSession.entryPassphraseKek(PRIVATE_PASS.toByteArray(Charsets.UTF_8), privatePosition, entry.kdfSet)!!
    }

    /**
     * Private enrolled for photo, with a wrong passphrase two minutes ago, one a minute ago that came
     * out blank, and a camera permission it lost before them — all waiting for its opening.
     */
    private fun fillPrivate() {
        assertOpened()
        val kek = privateKek()
        val id = try {
            enrolVaultCapture(store, privatePosition, kek, setOf(Capture.Mode.PHOTO))
        } finally {
            kek.fill(0)
        }
        val now = System.currentTimeMillis()
        queueVaultCapture(store, id, Capture.Mode.PHOTO, capturePhotoBytes(), now - 120_000)
        queueVaultCapture(
            store, id, Capture.Mode.PHOTO, capturePhotoBytes(android.graphics.Color.BLACK), now - 60_000,
            CaptureRecord.Stage(CaptureRecord.STAGE_VAULT_PASSPHRASE, CaptureRecord.FLAG_BLANK),
        )
        val key = VaultKeyringSession.captureStoreKey()!!
        try {
            CaptureIngest.recordPermissions(
                store, key,
                listOf(CapturePermissions.Transition(TamperLog.PERMISSION_CAMERA, TamperLog.PERMISSION_LOST, now - 180_000)),
                homeModes = emptySet(),
            )
        } finally {
            key.fill(0)
        }
        lockKeyringAndVault()
    }

    /** Opening the vault's timeline was looking; the next pass needs its badge back. */
    private fun unseePrivate() {
        assertOpened()
        val kek = privateKek()
        val opened = try {
            VaultKeyringSession.openVaultWithPassphrase(privatePosition, kek)!!
        } finally {
            kek.fill(0)
        }
        val key = VaultKeys.captureStoreKey(opened.vmk)
        try {
            val id = VaultKeys.metadataBlobId(key)
            if (store.fileOf(id).exists()) MetadataDb.commit(store, id, key) { it.capturesSeenAt = null; it }
        } finally {
            key.fill(0)
        }
        lockKeyringAndVault()
    }

    @Test
    fun theVaultsSurfaces() {
        fillPrivate()
        listOf(ThemeChoice.Dark, ThemeChoice.Light).forEach { theme ->
            unseePrivate()
            GridPreference.setAppearance(ctx, theme)
            val p = theme.name.lowercase()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                unlock()

                compose.openFirstVaultFromTheList()
                await("the grid") { described("All vaults") }
                compose.onNodeWithContentDescription("Trash and settings").performClick()
                await("the drawer") { text("Unvault Everything") }
                shot("$p-01-unprotected-vault-drawer")
                back(scenario)
                compose.onNodeWithContentDescription("All vaults").performClick()
                await("the vault list") { described("Menu") }

                compose.openProtectedVaultFromTheList(PRIVATE, PRIVATE_PASS)
                await("the badge") { described("An intruder capture is waiting") }
                compose.onNodeWithContentDescription("Trash and settings").performClick()
                await("the drawer") { text("Intruder capture") }
                shot("$p-02-protected-vault-drawer-badged")
                back(scenario)

                compose.openVaultIntruderCapture()
                await("the vault's rows") { described("Vault passphrase: wrong passphrase") && described("Came out blank") }
                shot("$p-03-vault-timeline")
                back(scenario)
                compose.onNodeWithContentDescription("All vaults").performClick()
                await("the vault list") { described("Menu") }
            }
            lockKeyringAndVault()
        }
    }

    @Test
    fun theHomeBannersAndTheSheet() {
        shell("appops set ${ctx.packageName} AUTO_REVOKE_PERMISSIONS_IF_UNUSED allow")
        assertOpened()
        val key = VaultKeyringSession.captureStoreKey()!!
        VaultKeys.captureKeypair(key).let { Capture.setDestination(ctx, it.publicKey); it.zero() }
        key.fill(0)
        Capture.setEnabled(ctx, Capture.Mode.PHOTO, true)
        lockKeyringAndVault()
        listOf(ThemeChoice.Dark, ThemeChoice.Light).forEach { theme ->
            GridPreference.setAppearance(ctx, theme)
            val p = theme.name.lowercase()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                unlock()
                compose.openIntruderCaptureFromHome()
                await("both banners") { text("Photo is not recording") && text("unused for a few months") }
                shot("$p-04-both-banners")
                compose.onNodeWithContentDescription("Capture settings").performClick()
                await("the sheet") { text("Not recording · camera permission off") }
                shot("$p-05-sheet-not-recording")
                backKey()
            }
            lockKeyringAndVault()
        }
    }

    @Test
    fun theCaptureStepsAutoResetLine() {
        var theme by mutableStateOf(ThemeChoice.Dark)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent {
                    VaultTheme(theme) {
                        OnboardingFrame(step = Onboarding.Step.Capture, onBack = null) {
                            CaptureStepScreen(
                                enabled = setOf(Capture.Mode.PHOTO), hasFrontCamera = true,
                                onEnable = {}, onDisable = {}, problem = null, onContinue = {},
                                autoResetRisk = true, onKeepPermissions = {},
                            )
                        }
                    }
                }
            }
            listOf(ThemeChoice.Dark, ThemeChoice.Light).forEach { t ->
                instr.runOnMainSync { theme = t }
                await("the auto-reset line") { text("Keep permissions") }
                compose.onNodeWithText("Keep permissions").performScrollTo()
                shot("${t.name.lowercase()}-06-capture-step-auto-reset")
            }
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

    private fun shell(command: String) = instr.uiAutomation.executeShellCommand(command).use { drain(it) }

    /** A real back key — a sheet is its own window, which the activity's dispatcher never reaches. */
    private fun backKey() {
        shell("input keyevent KEYCODE_BACK")
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
