package com.app.vault

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.FolderPickerScreen
import com.app.vault.ui.theme.VaultTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `move-picker-own-screen-folders`, task 3.3 — the picker for a selection spanning two
 * destinations: no folder offered, the line saying why, "Move to the home screen" still there.
 * Both themes, because the line is secondary text and its contrast is checked in each.
 *
 * `CrashLogScreenshotTest`'s method: `FLAG_SECURE` cleared on a throwaway activity, captures
 * from `uiAutomation`, saved to [DIR].
 */
@RunWith(AndroidJUnit4::class)
class MovePickerMixedKindsScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()

    private companion object {
        const val DIR = "/data/local/tmp/move-picker-own-screen-folders"
    }

    @Test
    fun theMixedKindsLine() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val theme = mutableStateOf(ThemeChoice.Dark)
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent {
                    VaultTheme(theme.value) {
                        FolderPickerScreen(
                            count = 2,
                            movingFolder = null,
                            folders = emptyList(),
                            currentFolderId = null,
                            onMove = {},
                            problem = null,
                            onCancel = {},
                            mixedKinds = true,
                        )
                    }
                }
            }
            await("select items of one kind")
            shot("01-dark")

            theme.value = ThemeChoice.Light
            await("select items of one kind")
            shot("02-light")
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
        // `dd`, not `tee` — see `CrashLogScreenshotTest`.
        val (stdout, stdin) = ui.executeShellCommandRw("dd of=$DIR/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(png) }
        drain(stdout)
    }

    private fun drain(fd: android.os.ParcelFileDescriptor) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }
}
