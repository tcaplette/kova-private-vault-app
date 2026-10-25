package com.app.vault

import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.vault.ui.NoteScreen
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A note that was **closed and opened again** — the state `NoteListContinuationDeviceTest`
 * never covered, because it only ever drove a note whose body started empty.
 */
@RunWith(AndroidJUnit4::class)
class NoteReopenedListDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun body() = compose.onNodeWithContentDescription("Note")

    private fun bodyText(): String = body().fetchSemanticsNode()
        .config[SemanticsProperties.EditableText].text

    private fun open(title: String, body: String, block: () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent {
                    VaultTheme(ThemeChoice.Dark) {
                        NoteScreen(
                            title = title,
                            body = body,
                            onSave = { _, _ -> },
                            onCancel = {},
                            onShare = null,
                            onExport = null,
                        )
                    }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    @Test
    fun continuingAListInAReopenedNote() {
        open("Shopping", "1. Coffee") {
            // The editor opens rendered, not as a field: tap the page to get a caret.
            // A reopened note arrives rendered; the tap on its text is what opens the field.
            compose.onNodeWithText("Coffee").performClick()
            compose.waitForIdle()
            Log.i(TAG, "after tap, editable = ${runCatching { bodyText() }}")

            body().performTextInputSelection(TextRange(9))
            compose.waitForIdle()
            body().performTextInput("\n")
            compose.waitForIdle()
            Log.i(TAG, "after break = ${bodyText().replace("\n", "\\n")}")
            compose.waitForIdle()
            Thread.sleep(120)
            assertEquals("1. Coffee\n2. ", bodyText())
        }
    }

    @Test
    fun theToolbarStillMarksALineInAReopenedNote() {
        open("Shopping", "Coffee") {
            compose.onNodeWithText("Coffee").performClick()
            compose.waitForIdle()
            body().performTextInputSelection(TextRange(6))
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Numbered list").performClick()
            compose.waitForIdle()
            Log.i(TAG, "after numbered tap = ${bodyText().replace("\n", "\\n")}")
            compose.waitForIdle()
            Thread.sleep(120)
            assertEquals("1. Coffee", bodyText())
        }
    }

    private companion object {
        const val TAG = "NoteReopen"
    }
}
