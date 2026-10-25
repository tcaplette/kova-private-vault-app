package com.app.vault

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.vault.ui.NoteScreen
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The note editor's reported faults, as tests.
 *
 * A note was created, a title typed, and then the body could not be typed into at all —
 * "Start writing" flashing as the field appeared and vanished. Pressing back saved
 * **two** copies, and opening either said the item could not be opened.
 *
 * Three separate defects, one test each.
 */
@RunWith(AndroidJUnit4::class)
class NoteEditorTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val saves = mutableListOf<Pair<String, String>>()
    private var cancelled = 0
    private var discarded = 0
    private var shares = 0
    private var exports = 0

    private fun editor(
        title: String = "",
        body: String = "",
        onShare: (() -> Unit)? = null,
        onExport: (() -> Unit)? = null,
        assertions: () -> Unit,
    ) {
        saves.clear(); cancelled = 0; discarded = 0; shares = 0; exports = 0
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    VaultTheme {
                        NoteScreen(
                            title = title,
                            body = body,
                            onSave = { t, b -> saves += t to b },
                            onCancel = { cancelled++ },
                            onDiscard = { discarded++ },
                            onShare = onShare,
                            onExport = onExport,
                        )
                    }
                }
            }
            compose.waitForIdle()
            assertions()
        }
    }

    /**
     * ***Typing a title must not close the body.*** It used to: the title taking focus
     * dropped the screen back to the rendered view, and a rendered empty note has no
     * height, so there was nothing left to tap.
     */
    @Test
    fun theBodyStaysWritableAfterATitleIsTyped() {
        editor {
            compose.onNodeWithContentDescription("Title").performTextInput("Shopping")
            compose.waitForIdle()

            // The body's placeholder is the field. If it is gone, the field is gone.
            compose.onNodeWithContentDescription("Note").assertIsDisplayed()
            compose.onNodeWithContentDescription("Note").performTextInput("milk and bread")
            compose.waitForIdle()

            compose.onAllNodes(hasText("milk and bread", substring = true))[0].assertIsDisplayed()
        }
    }

    /** And the formatting toolbar is up the whole time, because the field is. */
    @Test
    fun theToolbarSurvivesTypingATitle() {
        editor {
            compose.onNodeWithContentDescription("Title").performTextInput("Shopping")
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Checklist").assertIsDisplayed()
        }
    }

    /**
     * ***Back saves once.*** `leave` commits and then the screen goes away, and the
     * safety net on dispose used to commit a second time — after the host had cleared
     * the row being edited, so it took the new-note path and inserted a second copy.
     */
    @Test
    fun leavingSavesExactlyOnce() {
        editor {
            compose.onNodeWithContentDescription("Title").performTextInput("Shopping")
            compose.onNodeWithContentDescription("Note").performTextInput("milk")
            compose.waitForIdle()

            val before = saves.size
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()
        }
        // The scenario is closed here, so the disposal has happened too.
        assertEquals("leaving must save once and the disposal must not save again",
            1, saves.count { it.first == "Shopping" && it.second == "milk" })
        assertEquals(1, cancelled)
    }

    /**
     * ***Share seals the words on screen, not the words the autosave last saw.***
     *
     * The autosave is 800ms behind a hand that is still typing, so a share taken
     * straight after a keystroke would hand over the previous version. The control
     * commits first; this asserts the commit lands before the callback does.
     */
    @Test
    fun sharingCommitsTheNoteFirst() {
        var savesAtShare = -1
        editor(onShare = { savesAtShare = saves.size; shares++ }) {
            compose.onNodeWithContentDescription("Title").performTextInput("Shopping")
            compose.onNodeWithContentDescription("Note").performTextInput("milk and bread")
            compose.waitForIdle()

            compose.onNodeWithContentDescription("Share securely").performClick()
            compose.waitForIdle()
        }
        assertEquals("the share control must call through exactly once", 1, shares)
        assertTrue("the note must be committed before the share is handed the row", savesAtShare >= 1)
        assertTrue(
            "what is committed must be the text on screen: ${saves.lastOrNull()}",
            saves.any { it.second.contains("and bread") },
        )
    }

    /** An empty untitled note leaves nothing behind, on back or on disposal. */
    @Test
    fun anEmptyNoteIsDiscardedAndNeverSaved() {
        editor {
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()
        }
        assertTrue("an empty note must not be saved: $saves", saves.isEmpty())
        assertEquals(1, discarded)
        assertEquals(0, cancelled)
    }

    /** An existing note opens rendered, and one tap on the page starts editing. */
    @Test
    fun anExistingNoteOpensRenderedAndOneTapEditsIt() {
        editor(title = "Shopping", body = "# Friday\nmilk") {
            // Rendered: the field's placeholder is absent and the words are there.
            compose.onAllNodes(hasText("Friday", substring = true))[0].assertIsDisplayed()
            compose.onAllNodes(hasText("Friday", substring = true))[0].performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Note").assertIsDisplayed()
        }
    }

    /**
     * ***One toolbar, not two.***
     *
     * A software capture of the editor showed a row of the formatting controls at the
     * top of the screen as well as at the bottom. `VaultScaffold` has one `bottomBar`
     * slot and `LocalScaffoldBottomBar` has one consumer, so a real duplicate would put
     * two of every control in the tree. This is the assertion that says which it is.
     */
    @Test
    fun theFormattingToolbarIsRenderedExactlyOnce() {
        editor {
            compose.onNodeWithContentDescription("Title").performTextInput("Shopping")
            compose.waitForIdle()

            // "Attach an image" was on this list and is gone with the feature
            // (`remove-note-attachments`). The rest are the formatting controls, which
            // are what this test was ever about — a duplicated toolbar would put two of
            // each in the tree.
            listOf("Checklist", "Bulleted list", "Numbered list", "Heading",
                   "Bold", "Italic", "Strikethrough")
                .forEach { control ->
                    compose.onAllNodesWithContentDescription(control).assertCountEquals(1)
                }

            // And the removed control is absent rather than duplicated to zero by
            // accident: a bar that stopped rendering entirely would also pass above.
            compose.onAllNodesWithContentDescription("Attach an image").assertCountEquals(0)
        }
    }

    /**
     * `captures-folder-and-log`. Export sits beside share on the bar, named and
     * visible — not behind an overflow, which `ScreenChromeTest` would fail the build
     * for on this file.
     */
    @Test
    fun anOpenNoteOffersExportBesideShare() {
        editor(title = "Notes", body = "something", onExport = { exports++ }) {
            compose.onNodeWithContentDescription("Export").assertExists()
            compose.onNodeWithContentDescription("Share securely").assertExists()
            compose.onNodeWithContentDescription("Export").performClick()
            compose.waitForIdle()
            assertEquals("export must reach the caller", 1, exports)
        }
    }

    /** Null until the note has a row, which is share's idiom on the same bar. */
    @Test
    fun exportIsOfferedButInertUntilThereIsARowToExport() {
        editor(title = "", body = "", onExport = null) {
            compose.onNodeWithContentDescription("Export").assertExists()
            compose.onNodeWithContentDescription("Export").assertIsNotEnabled()
        }
    }
}
