package com.app.vault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.app.vault.Mnemonic
import com.app.vault.R
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import java.security.SecureRandom

/** Which frame the kit check is drawn in — `recovery-practice` D5. */
enum class KitCheckFraming {
    /** First run's step 2: [OnboardingScaffold], inside the step's frame. */
    Onboarding,

    /** Every other creation kit: [VaultScaffold], the frame those kits use. */
    Standalone,
}

/** How many words the check asks for. */
private const val CHECKED_WORDS = 3

/** Three distinct positions from the 24, ascending — `recovery-practice` D5. */
internal fun drawCheckPositions(random: SecureRandom = SecureRandom()): List<Int> {
    val chosen = mutableSetOf<Int>()
    while (chosen.size < CHECKED_WORDS) chosen += random.nextInt(Mnemonic.WORD_COUNT)
    return chosen.sorted()
}

/**
 * Check your copy — `recovery-practice` D5.
 *
 * After the typed acknowledgement on a kit shown at creation, three of its words are asked
 * for, from the paper. The positions are drawn here, held across rotation, and drawn afresh
 * each time the check is entered from the words — so "See the words again" cannot be used to
 * memorise three words and come back.
 *
 * The comparison is case-insensitive after the field's own normalisation, and nothing is
 * written or logged: right continues the flow that made the kit, wrong marks each field that
 * does not match with a sentence of its own and leaves the matching ones as typed.
 */
@Composable
fun KitCheckScreen(
    mnemonic: Mnemonic,
    words: List<String>,
    onCorrect: () -> Unit,
    onSeeWords: () -> Unit,
    framing: KitCheckFraming,
) {
    val positions by rememberSaveable { mutableStateOf(drawCheckPositions()) }
    var typed by rememberSaveable { mutableStateOf(List(CHECKED_WORDS) { "" }) }
    // The positions the last Check found wrong. Editing a field retires its own line only.
    var wrong by rememberSaveable { mutableStateOf(emptyList<Int>()) }

    val outstanding = typed.count { it.isEmpty() || mnemonic.completions(it, 1).firstOrNull() != it }
    val problem = if (outstanding > 0) {
        pluralStringResource(R.plurals.kit_words_to_check, outstanding, outstanding)
    } else {
        null
    }
    val errors = typed.indices.map { i ->
        if (i in wrong) stringResource(R.string.kit_check_mismatch, positions[i] + 1) else null
    }

    fun check() {
        if (outstanding > 0) return
        val misses = typed.indices.filter { i ->
            !typed[i].trim().equals(words[positions[i]].trim(), ignoreCase = true)
        }
        if (misses.isEmpty()) onCorrect() else wrong = misses
    }

    val entry: @Composable () -> Unit = {
        MnemonicWordEntry(
            mnemonic = mnemonic,
            words = typed,
            onWordsChange = { updated ->
                wrong = wrong.filter { updated[it] == typed[it] }
                typed = updated
            },
            count = CHECKED_WORDS,
            positions = positions,
            errors = errors,
        )
    }

    NoClipboard {
        when (framing) {
            KitCheckFraming.Onboarding -> OnboardingScaffold(
                title = stringResource(R.string.kit_check_title),
                lead = stringResource(R.string.kit_check_lead),
                icon = R.drawable.ic_vpn_key,
                onContinue = ::check,
                continueLabel = stringResource(R.string.kit_check_action),
                continueEnabled = outstanding == 0,
            ) {
                entry()
                problem?.let { ProblemLine(it) }
                SeeWordsAgain(onSeeWords)
            }

            KitCheckFraming.Standalone -> VaultScaffold(
                title = stringResource(R.string.kit_check_title),
                onBack = onSeeWords,
            ) {
                Text(
                    text = stringResource(R.string.kit_check_lead),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.lg),
                )
                entry()
                problem?.let { ProblemLine(it) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Button(
                        onClick = ::check,
                        enabled = outstanding == 0,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.touchTarget),
                    ) {
                        Text(
                            stringResource(R.string.kit_check_action),
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center,
                        )
                    }
                    SeeWordsAgain(onSeeWords)
                }
            }
        }
    }
}

/** The secondary action, a text button in both frames. */
@Composable
private fun SeeWordsAgain(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm)
            .heightIn(min = Spacing.touchTarget),
    ) {
        Text(
            stringResource(R.string.kit_check_see_again),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/** Why Check words cannot run yet, beside it — [kitProblem]'s count sentence. */
@Composable
private fun ProblemLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier
            .padding(top = Spacing.sm)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}
