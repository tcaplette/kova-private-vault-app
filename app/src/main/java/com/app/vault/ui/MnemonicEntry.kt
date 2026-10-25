package com.app.vault.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.app.vault.Mnemonic
import com.app.vault.R
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import com.app.vault.ui.theme.VaultMono
import kotlinx.coroutines.launch

/**
 * A recovery kit, one field per word — `recovery-kit-entry`.
 *
 * ***This replaces a single free-text field, and the reason is not tolerance.*** That
 * field split on whitespace, so a user separating a list with commas produced `abandon,`
 * — not a word — and was told their correct transcription was invalid. Widening the
 * separator set fixed the failure and left the *cause*: nothing on screen said which
 * separators were accepted, so the user still had to guess. **A field per word has
 * nowhere to put a separator, so the question does not arise.**
 *
 * **One screen, and the words are not paginated.** 24 words is one value, not 24
 * decisions. A wizard is for a sequence of decisions — `OnboardingFrame` is that, and
 * this is not it. Splitting one value across pages hides the thing being entered.
 *
 * The precedent is a six-box authentication code rather than a text field: the structure
 * of the value is made visible in the inputs, and there is no format to guess.
 *
 * **Four things make 24 fields one continuous act rather than a form**, and none of them
 * is optional:
 *
 * - Space and Return advance, so typing the phrase the way it has always been typed
 *   produces the same keystrokes and lands each word in its own slot.
 * - The focused field is brought above the keyboard as focus moves. Without it, typing
 *   walks the user into fields they cannot see.
 * - A completion row, drawn from the wordlist by this app rather than by the keyboard —
 *   the fields stay `KeyboardType.Password` with autocorrect off, so no IME learns them.
 * - A whole phrase entered into the first field is spread across all 24, so anyone
 *   holding it in a password manager is not walked through 24 fields.
 *
 * Monospace, and never masked: these are transcribed by hand from paper, and `0`/`O` or
 * `1`/`l` confusion costs the vault (`DESIGN.md` §5.1). The screen that hosts this wraps
 * it in [NoClipboard] as every other secret-bearing screen does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MnemonicWordEntry(
    mnemonic: Mnemonic,
    words: List<String>,
    onWordsChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * How many fields — P4-3. Twenty-four is a recovery kit; four is a share code
     * (`FORMAT.md` §12), and both are the same act of typing wordlist words into
     * their own slots.
     *
     * **A second word field was the alternative and it is the defect this
     * component exists to prevent**: two places deciding where a word ends is how
     * `abandon,` came to be treated as a word. One component, one parser, one
     * completion source.
     */
    count: Int = Mnemonic.WORD_COUNT,
) {
    val requesters = remember(count) { List(count) { FocusRequester() } }
    val viewRequesters = remember(count) { List(count) { BringIntoViewRequester() } }
    val scope = rememberCoroutineScope()

    // Which field has focus, or -1. Drives the completion row, and the rule that a word
    // is not called wrong while it is still being typed.
    var focused by remember { mutableIntStateOf(-1) }

    fun focus(position: Int) {
        if (position in 0 until count) requesters[position].requestFocus()
    }

    /**
     * One field changed. Everything that is not a plain edit is a **spread**: the text is
     * split on the parser's own separators and laid out from this field onwards, which is
     * what makes typing-with-spaces and pasting-the-lot the same code path rather than
     * two behaviours that drift.
     */
    fun change(position: Int, raw: String) {
        val tokens = mnemonic.read(raw).words
        val finished = raw.isNotEmpty() && mnemonic.isSeparator(raw.last())

        if (tokens.size <= 1 && !finished) {
            onWordsChange(words.toMutableList().also { it[position] = tokens.firstOrNull() ?: "" })
            return
        }

        val updated = words.toMutableList()
        tokens.forEachIndexed { offset, word ->
            if (position + offset < count) updated[position + offset] = word
        }
        onWordsChange(updated)
        focus((position + tokens.size).coerceAtMost(count - 1))
    }

    Column(modifier = modifier.fillMaxWidth()) {
        (0 until count).chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                pair.forEach { position ->
                    val word = words[position]
                    // **On blur, never while typing.** A half-typed word is not a wrong
                    // word, and marking it as one turns every field red on the way in.
                    val wrong = enabled &&
                        focused != position &&
                        word.isNotEmpty() &&
                        mnemonic.completions(word, limit = 1).firstOrNull() != word
                    val wordLabel = stringResource(if (wrong) R.string.kit_word_wrong else R.string.kit_word, position + 1)

                    OutlinedTextField(
                        value = word,
                        onValueChange = { change(position, it) },
                        enabled = enabled,
                        singleLine = true,
                        isError = wrong,
                        // **Numbered only when the count needs numbering — P4-3.**
                        // Twenty-four words are numbered because a transcriber
                        // cannot see the order of a mnemonic; four words in a row
                        // are their own structure, and numbering them is furniture.
                        label = if (count == Mnemonic.WORD_COUNT) {
                            { Text("${position + 1}") }
                        } else {
                            null
                        },
                        textStyle = VaultMono.bodyLarge,
                        // The app's own completions come from the row below; the IME is
                        // told to learn nothing and correct nothing.
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                            imeAction = if (position == count - 1) {
                                ImeAction.Done
                            } else {
                                ImeAction.Next
                            },
                        ),
                        keyboardActions = KeyboardActions(onNext = { focus(position + 1) }),
                        supportingText = if (!wrong) null else {
                            // Named, not merely tinted — §6.4. A red outline alone is
                            // unreachable to a screen reader and invisible to a good
                            // number of people looking straight at it.
                            { Text(stringResource(R.string.kit_not_a_word)) }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = Spacing.xs)
                            .focusRequester(requesters[position])
                            .bringIntoViewRequester(viewRequesters[position])
                            .onFocusChanged {
                                if (it.isFocused) {
                                    focused = position
                                    // The whole reason 24 fields work on one screen.
                                    scope.launch { viewRequesters[position].bringIntoView() }
                                } else if (focused == position) {
                                    focused = -1
                                }
                            }
                            .semantics {
                                contentDescription = wordLabel
                            },
                    )
                }
            }
        }

        // The completion row. Present only for the field being typed into, and only once
        // something has been typed into it — a row of arbitrary words over an untouched
        // field is noise.
        val completions =
            if (focused < 0 || !enabled) emptyList() else mnemonic.completions(words[focused])
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            completions.forEach { candidate ->
                val at = focused
                TextButton(onClick = {
                    // The **whole** word, never the prefix that was typed. A prefix left
                    // in a field is still not a word, which is the line `Mnemonic` holds.
                    onWordsChange(words.toMutableList().also { it[at] = candidate })
                    focus(at + 1)
                }) {
                    Text(candidate, style = VaultMono.bodyLarge, color = VaultColors.Primary)
                }
            }
        }
    }
}

/**
 * What to say beside an action that cannot run yet — `recovery-kit-entry`.
 *
 * **A disabled button with no reason is the defect this exists to avoid.** Null when the
 * kit is complete and decodes; a sentence otherwise.
 *
 * The count comes first because it is actionable and specific. The checksum sentence is
 * last because it is the only one left once every field holds a real word, and it is the
 * one the old single message hid entirely — a user who transcribed correctly but out of
 * order used to be told the same thing as one who had typed three words.
 */
@Composable
internal fun kitProblem(mnemonic: Mnemonic, words: List<String>): String? {
    val outstanding = words.count { it.isEmpty() || mnemonic.completions(it, 1).firstOrNull() != it }
    return when {
        outstanding > 0 -> pluralStringResource(R.plurals.kit_words_to_check, outstanding, outstanding)
        !mnemonic.isValid(words.joinToString(" ")) -> stringResource(R.string.kit_checksum_failed)
        else -> null
    }
}

/** The 24 empty fields a screen starts with. */
internal fun emptyKit(): List<String> = List(Mnemonic.WORD_COUNT) { "" }
