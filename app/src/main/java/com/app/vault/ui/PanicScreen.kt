package com.app.vault.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import com.app.vault.R
import com.app.vault.PassphraseStrength
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/** Which field a panic refusal belongs under — `panic-phrase`, D8. */
enum class PanicField { PASSPHRASE, CURRENT, PHRASE, ACTION }

/** A refusal and the field it is shown beneath. */
class PanicProblem(val field: PanicField, val message: String)

/** The sheet open over the screen while panic is on. */
private enum class PanicSheet { CHANGE, TURN_OFF }

/**
 * The panic phrase — P2-3, redesigned by `panic-phrase` (D8).
 *
 * The only screen in the app that names this feature. The lock screen does not change
 * by a character, the grid says nothing, and the digest is silent by design: a panic
 * unlock that announced itself would announce it to the person standing over the phone.
 *
 * **State first, then three facts, then only what is needed.** The state line is the
 * first thing below the title, because it is the answer to the question anyone opening
 * this screen has. The facts are onboarding's own `Fact` blocks — a heading and one
 * sentence each — so what panic does reads the same in both places. While off, the form
 * asks for the vault list's passphrase and the phrase twice; while on, changing and
 * turning off each open a sheet that asks for the current phrase first, because the slot
 * is written only once that phrase proves it (D5).
 *
 * **A refusal sits under the field it is about**, in one sentence that says what to do,
 * and clears only that field. The primary action stays disabled until the form could
 * succeed, and shows progress while the derivations run — each one is ~460 ms.
 */
@Composable
fun PanicScreen(
    enabled: Boolean,
    busy: Boolean,
    problem: PanicProblem?,
    /**
     * Bumped by the caller each time an operation succeeds, so an open sheet closes and
     * the fields empty. A success that leaves panic on — a change — has no state flip to
     * key off, which is why this is a counter and not a derivation from [enabled].
     */
    settled: Int,
    onSet: (passphrase: ByteArray, phrase: ByteArray) -> Unit,
    onChange: (current: ByteArray, passphrase: ByteArray, phrase: ByteArray) -> Unit,
    onTurnOff: (current: ByteArray) -> Unit,
    onForget: (passphrase: ByteArray) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * `cloud-mirror`. Whether a backup destination is configured — the fourth fact
     * describes a deletion that cannot happen without one, so it is drawn only where one
     * is.
     */
    backupConfigured: Boolean = false,
) {
    var sheet by remember { mutableStateOf<PanicSheet?>(null) }
    LaunchedEffect(settled) { sheet = null }

    NoClipboard {
        VaultScaffold(
            title = stringResource(R.string.drawer_panic),
            modifier = modifier,
            onBack = onCancel,
        ) {
            StatusLine(
                text = stringResource(if (enabled) R.string.panic_status_on else R.string.panic_status_off),
                icon = if (enabled) R.drawable.ic_check_circle else R.drawable.ic_info,
            )
            Spacer(Modifier.height(Spacing.section))

            Fact(
                stringResource(R.string.panic_fact_survives),
                stringResource(R.string.panic_fact_survives_body),
            )
            Fact(
                stringResource(R.string.panic_fact_way_in),
                stringResource(R.string.panic_fact_way_in_body),
            )
            Fact(
                stringResource(R.string.panic_fact_silent),
                stringResource(R.string.panic_fact_silent_body),
            )
            if (backupConfigured) {
                Fact(
                    stringResource(R.string.panic_fact_backup),
                    stringResource(R.string.panic_fact_backup_body),
                )
            }

            if (enabled) {
                OutlinedButton(
                    onClick = { sheet = PanicSheet.CHANGE },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                ) {
                    Text(stringResource(R.string.panic_change), style = MaterialTheme.typography.labelLarge)
                }
                TextButton(
                    onClick = { sheet = PanicSheet.TURN_OFF },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                ) {
                    Text(stringResource(R.string.panic_turn_it_off), style = MaterialTheme.typography.labelLarge)
                }
            } else {
                SetForm(busy = busy, problem = problem, settled = settled, onSet = onSet)
            }
        }

        when (sheet) {
            PanicSheet.CHANGE -> PanicPhraseSheet(
                title = stringResource(R.string.panic_change),
                action = stringResource(R.string.action_save),
                asksForNewPhrase = true,
                busy = busy,
                problem = problem,
                onSubmit = { current, passphrase, phrase -> onChange(current, passphrase!!, phrase!!) },
                onForget = onForget,
                onDismiss = { sheet = null },
            )
            PanicSheet.TURN_OFF -> PanicPhraseSheet(
                title = stringResource(R.string.panic_turn_off_title),
                action = stringResource(R.string.panic_turn_off),
                asksForNewPhrase = false,
                busy = busy,
                problem = problem,
                onSubmit = { current, _, _ -> onTurnOff(current) },
                onForget = onForget,
                onDismiss = { sheet = null },
            )
            null -> Unit
        }
    }
}

/** The off state's form: the vault list's passphrase, then the phrase twice. */
@Composable
private fun ColumnScope.SetForm(
    busy: Boolean,
    problem: PanicProblem?,
    settled: Int,
    onSet: (ByteArray, ByteArray) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var phrase by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    ClearOn(problem, settled, clear = { field ->
        when (field) {
            PanicField.PASSPHRASE -> passphrase = ""
            PanicField.PHRASE -> { phrase = ""; again = "" }
            PanicField.CURRENT, PanicField.ACTION -> Unit
            null -> { passphrase = ""; phrase = ""; again = "" }
        }
    })

    Text(
        text = stringResource(R.string.panic_set_title),
        style = MaterialTheme.typography.titleSmall,
        color = VaultColors.OnSurface,
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.sm),
    )
    PassphraseField(
        value = passphrase,
        onValueChange = { passphrase = it },
        label = stringResource(R.string.panic_your_passphrase),
        imeAction = ImeAction.Next,
        enabled = !busy,
        isError = problem?.field == PanicField.PASSPHRASE,
        supporting = problem.messageFor(PanicField.PASSPHRASE) ?: stringResource(R.string.panic_passphrase_hint),
    )
    PhrasePair(
        phrase = phrase,
        again = again,
        onPhrase = { phrase = it },
        onAgain = { again = it },
        label = stringResource(R.string.panic_phrase_label),
        busy = busy,
        problem = problem,
    )
    PrimaryAction(
        label = stringResource(R.string.panic_turn_on),
        enabled = passphrase.isNotEmpty() && phraseReady(phrase, again),
        busy = busy,
        problem = problem,
        onClick = { onSet(passphrase.toByteArray(Charsets.UTF_8), phrase.toByteArray(Charsets.UTF_8)) },
    )
}

/**
 * Change or turn off, each over the screen. Asks for the current panic phrase first;
 * "Forgot your panic phrase?" swaps the sheet to the vault list's passphrase alone,
 * which clears the record and writes no slot (D5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PanicPhraseSheet(
    title: String,
    action: String,
    asksForNewPhrase: Boolean,
    busy: Boolean,
    problem: PanicProblem?,
    onSubmit: (current: ByteArray, passphrase: ByteArray?, phrase: ByteArray?) -> Unit,
    onForget: (ByteArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var forgot by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var phrase by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        // Fully open: the change sheet is four fields tall, and half-open it left the
        // last field under the gesture bar with Save below the fold.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = VaultColors.Surface,
        // **Inherit, which is secure here**: `VaultApplication` sets `FLAG_SECURE` on every
        // activity, and material3 1.4's sheet takes the parent window's bit under `Inherit`
        // (`shouldApplySecureFlag`, read from the bytecode). Not `SecureOn`, which would
        // also blacken the one screenshot harness that clears the flag on purpose.
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.Inherit),
    ) {
        NoClipboard {
            Column(
                modifier = Modifier
                    .imePadding()
                    // Scrollable for `RenameSheet`'s reason: with the keyboard up the
                    // action lifts past the top on a short screen.
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenInset)
                    .padding(bottom = Spacing.section),
            ) {
                ClearOn(problem, settled = 0, clear = { field ->
                    when (field) {
                        PanicField.CURRENT -> current = ""
                        PanicField.PASSPHRASE -> passphrase = ""
                        PanicField.PHRASE -> { phrase = ""; again = "" }
                        PanicField.ACTION, null -> Unit
                    }
                })

                Text(
                    text = if (forgot) stringResource(R.string.panic_forgot_title) else title,
                    style = MaterialTheme.typography.titleLarge,
                    color = VaultColors.OnSurface,
                    modifier = Modifier.padding(bottom = Spacing.md),
                )

                if (forgot) {
                    Text(
                        text = stringResource(R.string.panic_forgot_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColors.OnSurfaceVariant,
                        modifier = Modifier.padding(bottom = Spacing.md),
                    )
                    PassphraseField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = stringResource(R.string.panic_your_passphrase),
                        enabled = !busy,
                        isError = problem?.field == PanicField.PASSPHRASE,
                        supporting = problem.messageFor(PanicField.PASSPHRASE)
                            ?: stringResource(R.string.panic_passphrase_hint),
                    )
                    PrimaryAction(
                        label = stringResource(R.string.panic_clear),
                        enabled = passphrase.isNotEmpty(),
                        busy = busy,
                        problem = problem,
                        onClick = { onForget(passphrase.toByteArray(Charsets.UTF_8)) },
                    )
                    return@Column
                }

                PassphraseField(
                    value = current,
                    onValueChange = { current = it },
                    label = stringResource(R.string.panic_current_label),
                    imeAction = if (asksForNewPhrase) ImeAction.Next else ImeAction.Done,
                    enabled = !busy,
                    isError = problem?.field == PanicField.CURRENT,
                    supporting = problem.messageFor(PanicField.CURRENT),
                )
                TextButton(
                    onClick = { forgot = true },
                    enabled = !busy,
                    modifier = Modifier.padding(bottom = Spacing.sm),
                ) {
                    Text(stringResource(R.string.panic_forgot), style = MaterialTheme.typography.labelLarge)
                }

                if (asksForNewPhrase) {
                    PassphraseField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = stringResource(R.string.panic_your_passphrase),
                        imeAction = ImeAction.Next,
                        enabled = !busy,
                        isError = problem?.field == PanicField.PASSPHRASE,
                        supporting = problem.messageFor(PanicField.PASSPHRASE)
                            ?: stringResource(R.string.panic_passphrase_hint),
                    )
                    PhrasePair(
                        phrase = phrase,
                        again = again,
                        onPhrase = { phrase = it },
                        onAgain = { again = it },
                        label = stringResource(R.string.panic_new_label),
                        busy = busy,
                        problem = problem,
                    )
                }

                PrimaryAction(
                    label = action,
                    enabled = current.isNotEmpty() &&
                        (!asksForNewPhrase || (passphrase.isNotEmpty() && phraseReady(phrase, again))),
                    busy = busy,
                    problem = problem,
                    onClick = {
                        onSubmit(
                            current.toByteArray(Charsets.UTF_8),
                            if (asksForNewPhrase) passphrase.toByteArray(Charsets.UTF_8) else null,
                            if (asksForNewPhrase) phrase.toByteArray(Charsets.UTF_8) else null,
                        )
                    },
                )
            }
        }
    }
}

/** The phrase and its repeat. The mismatch is said once the second entry is as long as the first. */
@Composable
private fun PhrasePair(
    phrase: String,
    again: String,
    onPhrase: (String) -> Unit,
    onAgain: (String) -> Unit,
    label: String,
    busy: Boolean,
    problem: PanicProblem?,
) {
    val verdict = rememberPassphraseVerdict(phrase)
    PassphraseField(
        value = phrase,
        onValueChange = onPhrase,
        label = label,
        imeAction = ImeAction.Next,
        enabled = !busy,
        isError = problem?.field == PanicField.PHRASE,
        // The floor, then the one thing this screen alone has to say: the panic phrase is
        // also checked against the passphrase entered beside it, and against nothing else.
        supporting = problem.messageFor(PanicField.PHRASE)
            ?: stringResource(R.string.panic_phrase_requirement, PassphraseRequirement),
        modifier = Modifier.padding(top = Spacing.sm),
    )
    PassphraseStrengthMeter(
        verdict = verdict,
        modifier = Modifier.padding(top = Spacing.sm),
    )
    val mismatch = again.isNotEmpty() && again.length >= phrase.length && again != phrase
    PassphraseField(
        value = again,
        onValueChange = onAgain,
        label = stringResource(R.string.panic_label_again, label),
        enabled = !busy,
        isError = mismatch,
        supporting = if (mismatch) stringResource(R.string.passphrase_mismatch) else null,
        modifier = Modifier.padding(top = Spacing.sm),
    )
}

/** Full width, disabled until the form could succeed, progress in place of its label while busy. */
@Composable
private fun PrimaryAction(
    label: String,
    enabled: Boolean,
    busy: Boolean,
    problem: PanicProblem?,
    onClick: () -> Unit,
) {
    problem.messageFor(PanicField.ACTION)?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.Error,
            modifier = Modifier
                .padding(top = Spacing.md)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = VaultColors.OnSurfaceVariant,
                )
            } else {
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun phraseReady(phrase: String, again: String): Boolean =
    PassphraseStrength.clears(phrase) && phrase == again

private fun PanicProblem?.messageFor(field: PanicField): String? =
    this?.takeIf { it.field == field }?.message

/**
 * Empties the field a refusal names when it arrives, and every field when [settled]
 * moves or the form leaves composition. A `String` cannot be zeroed; dropping the last
 * reference to it is what the app's other passphrase fields do too, and the bytes built
 * from it at submit are zeroed by `VaultLifecycle`.
 */
@Composable
private fun ClearOn(problem: PanicProblem?, settled: Int, clear: (PanicField?) -> Unit) {
    LaunchedEffect(problem) { problem?.let { clear(it.field) } }
    LaunchedEffect(settled) { if (settled != 0) clear(null) }
    DisposableEffect(Unit) { onDispose { clear(null) } }
}
