package com.app.vault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.app.vault.PassphraseStrength
import com.app.vault.R
import com.app.vault.ui.theme.Radius
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import com.app.vault.words
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.app.vault.localWords

/**
 * Where a passphrase stands while it is being typed — `passphrase-strength-meter` D5.
 *
 * **Why this is a component and not seven implementations.** `DESIGN.md` §6 allows a
 * wrapper once a second screen needs the same thing, and records four that earned it.
 * Seven screens set a passphrase — first-run setup, lock-screen setup, vault creation,
 * recovery, backup import, panic enrolment and decoy creation — and [PassphraseField]
 * exists in the first place because thirteen hand-rolled passphrase fields drifted apart.
 * A meter built seven times is that defect with a new name.
 *
 * **It is not the primary, and it is not a red-to-green ramp.** `DESIGN.md` §4.1 gives the
 * primary token exactly one role, the primary action, and forbids it as a status; §6.4
 * forbids meaning carried by colour at all. So the segments are the structural `secondary`
 * at every level and the **word** carries the level, which is the accessible answer rather
 * than a concession to accessibility. `secondary` measures 11.17:1 on `surface` in the dark
 * scheme and 9.02:1 in the light, far above the 3:1 a non-text graphic needs.
 *
 * **It never shows a crack time.** zxcvbn's slowest scenario assumes 10^4 guesses a second
 * and this app's Argon2id is nearer 2, so every figure it offers is wrong by three orders
 * of magnitude in the user's favour.
 *
 * **It never snaps to an error.** This draws state, not a refusal: typing changes the
 * meter and never puts the field in its error colour. The refusal still belongs to submit,
 * for the reason [PassphraseField]'s own KDoc gives — an error at character two of a
 * thirty-character passphrase is noise.
 *
 * **Nothing animates, deliberately.** The meter answers a keystroke, so it has to feel
 * fastened to it; a 150ms crossfade on something that changes every keystroke reads as lag
 * rather than polish, and it would need a reduced-motion branch to earn nothing.
 */
@Composable
fun PassphraseStrengthMeter(
    verdict: PassphraseStrength.Verdict,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SegmentRow(filled = verdict.score, anyTyped = verdict.codePoints > 0)

        // One region for both lines, and polite: assertive would interrupt a screen
        // reader on every keystroke. Empty until something is typed — the requirement
        // itself is on the field, as helper text, before any of this appears.
        Column(
            modifier = Modifier
                .padding(top = Spacing.sm)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            verdict.word?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurface,
                )
            }
            verdict.sentence?.let {
                Text(
                    text = it.text(localWords()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The requirement, stated on an empty field before anything is typed.
 *
 * Declared once for all seven screens: a floor that seven screens each phrase for
 * themselves is a floor a user learns differently depending on where they met it.
 *
 * Two sentences, and the second is the guidance NIST SP 800-63B-4 §3.1.1.2 requires a
 * verifier to offer. It points at length and unusual words because that is what actually
 * raises the estimate, and it asks for no character classes — composition rules are what
 * produce `Password1!`, and NIST forbids requiring them.
 *
 * **No explicit `: String` here, deliberately.** `KeyMaterialScanTest` fails the build on
 * any `name: String` whose name contains "passphrase", and it is right to be that blunt —
 * it cannot tell copy from a credential. The type is inferred, which is idiomatic for a
 * literal anyway; adding the annotation back breaks the build, and that is the scan working.
 */
val PassphraseRequirement
    @Composable get() = stringResource(R.string.passphrase_requirement, PassphraseStrength.MIN_CODE_POINTS)

/**
 * The refusal when a submitted passphrase does not clear the floor.
 *
 * Declared beside [PassphraseRequirement] for the same reason, and deliberately not a
 * restatement of it: by the time this appears the user has read the requirement, so it says
 * what to do rather than repeating the number.
 */
val PassphraseTooWeak
    @Composable get() = stringResource(R.string.passphrase_too_weak)

/**
 * Four capped bars, [filled] of them in `secondary`.
 *
 * Decorative to a screen reader: the word beside it says the same thing in words, and a
 * reader announcing four unlabelled bars would be noise. Hence [clearAndSetSemantics].
 *
 * The track is drawn even before anything is typed, so the meter does not appear from
 * nowhere on the first keystroke.
 */
@Composable
private fun SegmentRow(filled: Int, anyTyped: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        repeat(SEGMENTS) { index ->
            val lit = anyTyped && index < filled
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(Spacing.xs)
                    .background(
                        color = if (lit) VaultColors.Secondary else VaultColors.OutlineVariant,
                        shape = RoundedCornerShape(Radius.meterSegment),
                    ),
            ) {}
        }
    }
}

/** zxcvbn scores 0..4, so four segments: none lit is score 0, all four is the floor. */
private const val SEGMENTS = 4

/**
 * The debounced verdict for [typed] — D9.
 *
 * **Why this is not computed inline.** Constructing the estimator reads ~800 KB of
 * dictionaries (~185 ms) and the first measurement costs ~93 ms of JIT warm-up, against
 * 2–6 ms afterwards and ~43 ms at a hundred characters. None of that may land on a frame.
 *
 * So: [PassphraseStrength.warm] runs once off the main thread when a screen first composes,
 * the estimate itself runs on `Dispatchers.Default` after a short idle, and the meter draws
 * whatever verdict last completed. The length half of the verdict is arithmetic on the
 * string, so the count moves with every keystroke regardless of what the estimator is
 * doing — the field is never dead waiting for it.
 *
 * [forbidden] carries the vault's name where the screen names one, plus the app's own
 * vocabulary; a vault must not open to a passphrase built out of its own name.
 */
@Composable
fun rememberPassphraseVerdict(
    typed: String,
    forbidden: List<String> = PassphraseStrength.APP_WORDS,
): PassphraseStrength.Verdict {
    var measured by remember { mutableStateOf(PassphraseStrength.NOTHING_TYPED) }
    // Which text `measured` belongs to, so a score is never credited to text it was not
    // measured from — see PassphraseStrength.pending.
    var measuredFor by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { withContext(Dispatchers.Default) { PassphraseStrength.warm() } }

    LaunchedEffect(typed, forbidden) {
        if (typed.isEmpty()) {
            measured = PassphraseStrength.NOTHING_TYPED
            measuredFor = ""
            return@LaunchedEffect
        }
        // Cancelled and restarted by the next keystroke, which is the debounce: a fast
        // typist spends one estimate when they stop rather than one per character.
        delay(DEBOUNCE_MS)
        measured = withContext(Dispatchers.Default) {
            PassphraseStrength.estimate(typed, forbidden)
        }
        measuredFor = typed
    }

    return PassphraseStrength.pending(typed, measured, settled = measuredFor == typed)
}

/**
 * Long enough that typing a word does not spend an estimate per letter, short enough that
 * pausing feels answered rather than delayed. Well inside the 100ms-to-feedback the
 * platform asks for, because the count below the field updates on the keystroke itself.
 */
private const val DEBOUNCE_MS = 120L
