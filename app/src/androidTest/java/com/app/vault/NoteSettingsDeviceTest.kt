package com.app.vault

import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.NoteScreen
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `notes-settings` on a device — what the keyboard is told, and what one undo puts back.
 *
 * Rendered in a throwaway activity with no vault: the editor is given a body and reports its
 * saves, and nothing on the install is read or written.
 */
@RunWith(AndroidJUnit4::class)
class NoteSettingsDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()

    private fun editor(
        body: String,
        settings: NoteSettings,
        /** Empty, with an empty body, opens a new note straight into editing — the body field. */
        title: String = "List",
        saves: MutableList<String> = mutableListOf(),
        assertions: (view: () -> View) -> Unit,
    ) {
        var view: View? = null
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    view = LocalView.current
                    VaultTheme {
                        NoteScreen(
                            title = title,
                            body = body,
                            onSave = { _, b -> synchronized(saves) { saves += b } },
                            onCancel = {},
                            onShare = null,
                            onExport = null,
                            settings = settings,
                        )
                    }
                }
            }
            compose.waitForIdle()
            assertions { view!! }
        }
    }

    /** The editor info the focused field hands an input method — what a keyboard reads. */
    private fun imeOptions(view: View): Int {
        var options = 0
        instr.runOnMainSync {
            val info = EditorInfo()
            view.onCreateInputConnection(info)
            options = info.imeOptions
        }
        return options
    }

    private fun incognito(on: Boolean): Boolean {
        var flagged = false
        editor(body = "", settings = NoteSettings(incognito = on), title = "") { view ->
            compose.onNodeWithContentDescription("Note").performClick()
            compose.waitForIdle()
            flagged = imeOptions(view()) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        }
        return flagged
    }

    @Test
    fun theBodyAsksTheKeyboardNotToLearnWhenOn() {
        assertTrue("on: the body's editor info carries the flag", incognito(on = true))
    }

    @Test
    fun theBodyDoesNotAskWhenOff() {
        assertTrue("off: the flag is not set", !incognito(on = false))
    }

    @Test
    fun aNewInstallAsksByDefault() {
        assertTrue("the default is on — D4", NoteSettings().incognito)
    }

    /** A tick that moves the item is one edit: one undo restores the mark and the place. */
    @Test
    fun oneUndoRestoresTheMarkAndThePlace() {
        val list = "- [ ] a\n- [ ] b\n- [ ] c"
        val saves = mutableListOf<String>()
        editor(body = list, settings = NoteSettings(moveChecked = true), saves = saves) {
            compose.onNodeWithContentDescription("a").performClick()
            compose.waitUntil(5_000) { synchronized(saves) { saves.lastOrNull() } == "- [ ] b\n- [ ] c\n- [x] a" }

            compose.onNodeWithContentDescription("Undo").performClick()
            compose.waitUntil(5_000) { synchronized(saves) { saves.lastOrNull() } == list }
            assertEquals(list, synchronized(saves) { saves.last() })
        }
    }

    /** Off, a tick changes the mark and nothing moves. */
    @Test
    fun offTheTickedItemStaysWhereItIs() {
        val saves = mutableListOf<String>()
        editor(body = "- [ ] a\n- [ ] b", settings = NoteSettings(moveChecked = false), saves = saves) {
            compose.onNodeWithContentDescription("a").performClick()
            compose.waitUntil(5_000) { synchronized(saves) { saves.lastOrNull() } == "- [x] a\n- [ ] b" }
        }
    }
}
