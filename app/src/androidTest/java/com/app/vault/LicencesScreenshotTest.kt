package com.app.vault

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.BiometricState
import com.app.vault.ui.LicencesScreen
import com.app.vault.ui.VaultDrawerSheet
import com.app.vault.ui.theme.VaultTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `third-party-notices`, task 3.5 — the drawer row, the list and the lazysodium-android licence,
 * in the light and the dark scheme. `CrashLogScreenshotTest`'s method: `FLAG_SECURE` cleared on a
 * throwaway activity, captures from `uiAutomation`, written with `dd` to [DIR].
 */
@RunWith(AndroidJUnit4::class)
class LicencesScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext

    private companion object {
        const val DIR = "/data/local/tmp/third-party-notices"
    }

    @Test
    fun light() = walk(ThemeChoice.Light, "light")

    @Test
    fun dark() = walk(ThemeChoice.Dark, "dark")

    private fun walk(scheme: ThemeChoice, tag: String) {
        val notices = Notices.read(ctx)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent {
                    VaultTheme(scheme) {
                        var screen by remember { mutableStateOf(false) }
                        var open by remember { mutableStateOf<String?>(null) }
                        if (!screen) {
                            VaultDrawerSheet(
                                onRecoverVault = {},
                                owned = false,
                                trialDaysRemaining = 30,
                                trialStarted = true,
                                onPurchase = {},
                                currentIdentity = LauncherIdentity.Vault,
                                onIdentity = {},
                                biometricState = BiometricState.Off,
                                onBiometric = {},
                                faceDownAvailable = true,
                                faceDownOn = false,
                                faceDownLockAfter = 0L,
                                onFaceDown = {},
                                decoySetUp = false,
                                onSetUpDecoy = {},
                                onPanicCredential = {},
                                onIntruderCapture = {},
                                onExport = {},
                                onLicences = { screen = true },
                                licenceCount = notices.size,
                                currentAppearance = scheme,
                                onChooseAppearance = {},
                            )
                        } else {
                            LicencesScreen(
                                components = notices,
                                open = open,
                                onOpen = { open = it },
                                onBack = { if (open != null) open = null else screen = false },
                            )
                        }
                    }
                }
            }
            compose.onNodeWithText("Open-source licences").performScrollTo()
            await("libraries and their licences")
            shot("$tag-01-drawer-row")

            compose.onNodeWithText("Open-source licences").performClick()
            await("is built with these libraries")
            shot("$tag-02-list")

            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("lazysodium-android"))
            compose.onNodeWithText("lazysodium-android").performClick()
            await("Mozilla Public License 2.0")
            shot("$tag-03-detail")

            // Back lands on the list where the tapped row was, so the lead at its top may be
            // scrolled away; the row is what is waited for.
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText("Mozilla Public License Version 2.0", substring = true))
                    .fetchSemanticsNodes().isEmpty()
            }
            await("lazysodium-android")
            shot("$tag-04-list-after-back")
        }
    }

    private fun await(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(600)
        val png = java.io.ByteArrayOutputStream().also {
            instr.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
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
}
