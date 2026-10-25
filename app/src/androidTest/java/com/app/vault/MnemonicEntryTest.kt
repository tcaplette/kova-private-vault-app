package com.app.vault

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.MnemonicWordEntry
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One field per word — `recovery-kit-entry`.
 *
 * **The bug this exists for.** The 24 words went into a single free-text field that split
 * on whitespace alone, so a user separating them with commas produced `abandon,` — not a
 * word — and was told their correct transcription was invalid. Widening the separators
 * fixed that failure and left its cause: nothing on screen said which separators were
 * accepted, so the user still had to guess. A field per word has nowhere to put one.
 *
 * What is asserted here is the behaviour that makes 24 fields usable rather than a form:
 * that typing the phrase as one sequence still works, and that entering the lot at once
 * spreads it.
 */
@RunWith(AndroidJUnit4::class)
class MnemonicEntryTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val mnemonic =
        Mnemonic.fromResources(InstrumentationRegistry.getInstrumentation().targetContext)

    private fun phrase() = mnemonic.encode(ByteArray(Mnemonic.ENTROPY_BYTES))

    private fun launch(capture: (List<String>) -> Unit, body: () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    VaultTheme {
                        var words by remember { mutableStateOf(List(Mnemonic.WORD_COUNT) { "" }) }
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            MnemonicWordEntry(
                                mnemonic = mnemonic,
                                words = words,
                                onWordsChange = { words = it; capture(it) },
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            body()
        }
    }

    /**
     * The fast path: the whole kit entered into the first box lands one word per box.
     * Someone holding the phrase in a password manager is not walked through 24 fields.
     */
    @Test
    fun aWholePhraseInTheFirstBoxSpreadsAcrossAllTwentyFour() {
        var words = emptyList<String>()
        val expected = phrase()

        launch({ words = it }) {
            compose.onNodeWithText("1").performScrollTo().performTextInput(expected.joinToString(" "))
            compose.waitForIdle()
        }

        assertEquals("every word lands in its own box, in order", expected, words)
    }

    /** Commas were the reported failure. Spreading reads them as separators too. */
    @Test
    fun theSpreadTakesCommasAsWellAsSpaces() {
        var words = emptyList<String>()
        val expected = phrase()

        launch({ words = it }) {
            compose.onNodeWithText("1").performScrollTo().performTextInput(expected.joinToString(", "))
            compose.waitForIdle()
        }

        assertEquals(expected, words)
    }

    /**
     * Typing word-space-word-space, which is how the phrase has always been typed, still
     * works — each word simply lands in its own box.
     */
    @Test
    fun aSpaceFinishesAWordAndMovesOn() {
        var words = emptyList<String>()
        val expected = phrase()

        launch({ words = it }) {
            compose.onNodeWithText("1").performScrollTo()
                .performTextInput("${expected[0]} ${expected[1]} ")
            compose.waitForIdle()
        }

        assertEquals(expected[0], words[0])
        assertEquals(expected[1], words[1])
        assertTrue("nothing beyond what was typed is filled in", words[2].isEmpty())
    }

    /**
     * A word that is not in the list is called out on its own box, not as a verdict on
     * the whole phrase — which is what the single field could only do.
     */
    @Test
    fun aWordThatIsNotInTheListIsMarkedOnItsOwnBox() {
        var words = emptyList<String>()

        launch({ words = it }) {
            // The trailing space commits box 1 and moves focus on, which is what makes
            // the mark appear: a half-typed word is never called wrong.
            compose.onNodeWithText("1").performScrollTo().performTextInput("abandonn ")
            compose.waitForIdle()
            compose.onNodeWithText("not a word").performScrollTo().assertIsDisplayed()
        }

        assertEquals("abandonn", words[0])
    }

    /** Selecting a completion writes the whole word, never the prefix that was typed. */
    @Test
    fun aCompletionFillsTheWholeWord() {
        var words = emptyList<String>()

        launch({ words = it }) {
            compose.onNodeWithText("1").performScrollTo().performTextInput("aband")
            compose.waitForIdle()
            compose.onNodeWithText("abandon").performScrollTo().performClick()
            compose.waitForIdle()
        }

        assertEquals("abandon", words[0])
    }
}
