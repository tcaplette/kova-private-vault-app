package com.app.vault

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.NoteScreen
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * A list built the way a person builds one — `note-list-continuation`, task 3.2.
 *
 * The JVM tests prove every rule in `NoteEdits` against a `TextFieldValue` it was handed.
 * This proves the field hands it one: the sequence below goes through the real toolbar,
 * the real `BasicTextField` and the real IME commit path, and the assertion reads the
 * text out of the field's own semantics rather than out of a function's return value.
 *
 * `OriginalsCardScreenshotTest`'s method for the capture: `FLAG_SECURE` cleared on a
 * throwaway `ComponentActivity`, the frame taken from `uiAutomation`, written to
 * `/data/local/tmp/note-list-continuation/`.
 */
@RunWith(AndroidJUnit4::class)
class NoteListContinuationDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()

    private fun body() = compose.onNodeWithContentDescription("Note")

    private fun bodyText(): String = body().fetchSemanticsNode()
        .config[SemanticsProperties.EditableText].text

    /**
     * The field's text, asserted **twice with a frame between** — `note-list-continuation-ime`.
     *
     * This is the assertion the old version of this test lacked and the reason it passed
     * against a build that did not work. The marker was written correctly and an input
     * method's next sync removed it two milliseconds later; a single read taken at the
     * first instant sees a feature that works and a user sees one that does not.
     */
    private fun assertSettled(expected: String) {
        assertEquals(expected, bodyText())
        compose.waitForIdle()
        Thread.sleep(120)
        compose.waitForIdle()
        assertEquals("the text changed after it was written", expected, bodyText())
    }

    @Test
    fun aListIsBuiltContinuedAndLeftFromTheToolbar() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent {
                    VaultTheme(ThemeChoice.Dark) {
                        NoteScreen(
                            title = "",
                            body = "",
                            onSave = { _, _ -> },
                            onCancel = {},
                            onShare = null,
                            onExport = null,
                        )
                    }
                }
            }
            compose.waitForIdle()

            // One tap on the numbered control, then nothing but typing.
            compose.onNodeWithContentDescription("Numbered list").performClick()
            compose.waitForIdle()
            body().performTextInput("Coffee")

            listOf("Tea", "Milk", "Sugar").forEach { item ->
                body().performTextInput("\n")
                compose.waitForIdle()
                body().performTextInput(item)
                compose.waitForIdle()
            }

            assertSettled("1. Coffee\n2. Tea\n3. Milk\n4. Sugar")

            // Two breaks leave the list: the first opens `5. `, the second takes it away.
            body().performTextInput("\n")
            compose.waitForIdle()
            assertSettled("1. Coffee\n2. Tea\n3. Milk\n4. Sugar\n5. ")
            body().performTextInput("\n")
            compose.waitForIdle()
            assertSettled("1. Coffee\n2. Tea\n3. Milk\n4. Sugar\n")

            // A heading, then the bulleted control on it: one marker replaces the other.
            compose.onNodeWithContentDescription("Heading").performClick()
            compose.waitForIdle()
            body().performTextInput("Monday")
            compose.waitForIdle()
            assertSettled("1. Coffee\n2. Tea\n3. Milk\n4. Sugar\n# Monday")

            compose.onNodeWithContentDescription("Bulleted list").performClick()
            compose.waitForIdle()
            assertSettled("1. Coffee\n2. Tea\n3. Milk\n4. Sugar\n- Monday")

            Thread.sleep(700)
            val png = ByteArrayOutputStream().also {
                instr.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
            save("list-continued.png", png)
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
        const val DIR = "/data/local/tmp/note-list-continuation"
    }
}
