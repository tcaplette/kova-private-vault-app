package com.app.vault

import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.OriginalsCard
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * The originals card as a user sees it — `remove-originals-on-import`, `original-removal`'s
 * design requirement: its words, its semantics, its targets, and a real capture in both schemes.
 *
 * `MediaSettingsScreenshotTest`'s method: `FLAG_SECURE` cleared on a throwaway `ComponentActivity`,
 * captures from `uiAutomation`, landing in `/data/local/tmp/remove-originals/` on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class OriginalsCardScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext

    private val offer = OriginalRemoval.Offer(
        photos = (1..3).map { Uri.parse("content://media/external/images/media/$it") },
        videos = listOf(Uri.parse("content://media/external/video/media/9")),
        unavailable = 2,
    )

    private fun shot(
        name: String,
        theme: ThemeChoice,
        removing: Boolean = false,
        onKeep: () -> Unit = {},
        onRemove: () -> Unit = {},
        check: () -> Unit,
    ) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent {
                    VaultTheme(theme) {
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            OriginalsCard(offer, removing, onKeep, onRemove)
                        }
                    }
                }
            }
            compose.waitForIdle()
            check()
            compose.waitForIdle()
            // The enter animation and the frame both have to reach the display.
            Thread.sleep(700)
            val png = ByteArrayOutputStream().also {
                instr.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
            save("$name.png", png)
        }
    }

    private fun save(name: String, bytes: ByteArray) {
        val ui = instr.uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("mkdir -p $DIR")).use { it.readBytes() }
        // `dd`, not `tee`: tee echoes the bytes to stdout, read only after the write, so a
        // large PNG (the keyboard up) fills the pipes and both ends block. HangWatch caught it.
        val (stdout, stdin) = ui.executeShellCommandRw("dd of=$DIR/$name")
        ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(bytes) }
        ParcelFileDescriptor.AutoCloseInputStream(stdout).use { it.readBytes() }
    }

    private companion object {
        const val DIR = "/data/local/tmp/remove-originals"
        const val BODY = "3 photos and 1 video are now in this vault. Android will ask you to confirm. " +
            "2 others can't be removed from here and will stay in your gallery."
    }

    @Test
    fun theCardInTheDarkScheme() = shot("card-dark", ThemeChoice.Dark) {
        compose.onNodeWithText(ctx.getString(OriginalRemoval.TITLE)).assertExists()
        compose.onNodeWithText(BODY).assertExists()
        compose.onNodeWithText("Keep").assertIsEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Remove").assertIsEnabled().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun theCardInTheLightScheme() = shot("card-light", ThemeChoice.Light) {
        compose.onNodeWithText(ctx.getString(OriginalRemoval.TITLE)).assertExists()
    }

    @Test
    fun whileAndroidsDialogIsOpenBothActionsWait() = shot("card-in-flight", ThemeChoice.Dark, removing = true) {
        compose.onNodeWithText("Keep").assertIsNotEnabled()
        compose.onNodeWithText("Remove").assertIsNotEnabled()
    }

    @Test
    fun theTitleIsAHeadingAndTheCardIsAPoliteLiveRegion() = shot("card-semantics", ThemeChoice.Dark) {
        compose.onNode(
            hasText(ctx.getString(OriginalRemoval.TITLE)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            useUnmergedTree = true,
        ).assertExists()
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        ).assertExists()
    }

    @Test
    fun eachActionReportsItself() {
        var kept = 0
        var removed = 0
        shot("card-actions", ThemeChoice.Dark, onKeep = { kept++ }, onRemove = { removed++ }) {
            compose.onNodeWithText("Keep").performClick()
            compose.onNodeWithText("Remove").performClick()
            compose.waitForIdle()
            assertEquals(1, kept)
            assertEquals(1, removed)
        }
    }
}
