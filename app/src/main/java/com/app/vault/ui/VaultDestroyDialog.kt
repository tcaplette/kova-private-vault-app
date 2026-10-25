package com.app.vault.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.app.vault.R
import com.app.vault.ui.theme.Radius
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * What destroying a vault costs, and the one choice inside it — `delete-vault`.
 *
 * ***It was a screen.*** `vault-keyring` gave the vault removal a `VaultScaffold` with a
 * title bar and a back-stack entry, and argued that a dialog was too light for the
 * largest destructive act in the app. `DESIGN.md` §6.7 already said otherwise — *"an
 * action that destroys user content asks once, in a modal dialog"* — so the screen was
 * the deviation, not the dialog. The question is one question, the answer returns to
 * exactly where it was asked, and pushing a screen over the vault the user is standing
 * in to ask about that vault is worse than asking over it.
 *
 * **This is §6.7's second dialog, which that section calls a decision rather than a
 * precedent.** The decision: destroying a vault is the same *kind* of act as destroying
 * an item — asked once, answered in place — and it is the larger of the two. If the
 * smaller earns a dialog, the larger cannot need a screen. [DeleteConfirmation] is the
 * first, and this borrows its corner and its `FLAG_SECURE` for the reasons recorded
 * there.
 *
 * **Two outcomes, and neither is the primary.** §6.1 allows one primary-coloured action
 * per surface and neither of these earns it over the other: the app must not lean on
 * someone choosing to keep their files, and it must not lean on their destroying them.
 * Two outlined buttons of equal weight, stacked so neither is the one on the right that
 * a thumb finds first.
 *
 * **The return is offered first**, which is the only asymmetry and is deliberate: it is
 * the outcome that loses nothing, and a user who has not thought about their files
 * should meet that sentence before the other one.
 *
 * **Dismissal is not an outcome.** Tapping outside, pressing back or *Keep vault* all do
 * nothing at all — the vault, its contents and its backup are exactly as they were.
 *
 * **The same dialog serves both routes** — the vault list's control and the vault
 * settings sheet's *Destroy Vault* row. One act reached from two places, so one place
 * that states what it means; two dialogs would be two sets of these sentences, free to
 * drift apart.
 *
 * **The gate comes first** — `protected-vault-destroy-gate` D3. Before either outcome is
 * offered, the passphrase that opens this vault is asked for: its own where it has one, the
 * vault list's otherwise. Both steps are one modal whose content crossfades, so the scrim
 * never flashes between them. The backup line in the second step is read with the key the
 * gate unsealed, which is why the question comes before the choice rather than beside it.
 * Nothing in the first step is destructive, so nothing in it is red.
 */
/** Which step of [VaultDestroyDialog] is showing — `protected-vault-destroy-gate` D3. */
enum class DestroyStep { Gate, Choice }

@Composable
fun VaultDestroyDialog(
    name: String,
    /**
     * Whether this vault carries a mirror destination — `delete-vault` D3.
     *
     * The folder is destroyed with the vault, and that is said **before** the choice
     * rather than reported after it: a folder holds this vault's blobs and the keyring
     * blob, so the folder plus the 24 words reconstitutes the vault, and a user who was
     * not told would believe a copy survived that does not.
     */
    hasBackup: Boolean,
    /** Everything is written out, verified, and only then is the vault destroyed. */
    onReturnThenDestroy: () -> Unit,
    /** Nothing is written out. */
    onDestroyEverything: () -> Unit,
    onDismiss: () -> Unit,
    /** `backup-to-cloud-apps`. A cloud app's backup file rather than a folder — deleted, not emptied. */
    backupIsFile: Boolean = false,
    step: DestroyStep,
    /** The vault has its own passphrase, so the gate asks for that and not the list's. */
    ownPassphrase: Boolean,
    /** A protected vault reached from the list: the gate says its 24 words bring it back. */
    showLost: Boolean,
    /** The gate's derivation is running. Drives the indicator, never the outcome. */
    checking: Boolean,
    /** The last passphrase did not open this vault. */
    wrong: Boolean,
    /** The gate's passphrase. The array is the caller's to zero. */
    onSubmit: (ByteArray) -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    // Once a refusal has shown, its line stays reserved — empty while the next check runs —
    // so the dialog does not resize under the thumb, and the next refusal is announced anew.
    var refusedOnce by remember { mutableStateOf(false) }
    if (wrong) refusedOnce = true
    LaunchedEffect(step) { if (step == DestroyStep.Choice) typed = "" }
    val submit = { if (typed.isNotEmpty() && !checking) onSubmit(typed.toByteArray(Charsets.UTF_8)) }

    NoClipboard {
        AlertDialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            shape = RoundedCornerShape(Radius.container),
            containerColor = VaultColors.SurfaceContainerHigh,
            titleContentColor = VaultColors.OnSurface,
            textContentColor = VaultColors.OnSurfaceVariant,
            title = {
                Text(stringResource(R.string.destroy_title, name), style = MaterialTheme.typography.titleMedium)
            },
            text = {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = { fadeIn(tween(STEP_FADE_MS)) togetherWith fadeOut(tween(STEP_FADE_MS)) },
                    label = "destroy step",
                ) { showing ->
                    when (showing) {
                        DestroyStep.Gate -> GateStep(
                            name = name,
                            typed = typed,
                            onTyped = { typed = it },
                            ownPassphrase = ownPassphrase,
                            showLost = showLost,
                            checking = checking,
                            wrong = wrong,
                            refusedOnce = refusedOnce,
                            onDone = submit,
                        )
                        DestroyStep.Choice -> ChoiceStep(
                            hasBackup = hasBackup,
                            backupIsFile = backupIsFile,
                            onReturnThenDestroy = onReturnThenDestroy,
                            onDestroyEverything = onDestroyEverything,
                        )
                    }
                }
            },
            // The gate's confirm slot is Continue, which is not an outcome. The choice's is
            // empty on purpose: a confirm slot would make one of the two outcomes the
            // dialog's answer, and they are peers — see the KDoc.
            confirmButton = {
                if (step == DestroyStep.Gate) {
                    TextButton(onClick = submit, enabled = typed.isNotEmpty() && !checking) {
                        Text(
                            stringResource(if (checking) R.string.destroy_checking else R.string.destroy_continue),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(
                        stringResource(R.string.destroy_keep),
                        style = MaterialTheme.typography.labelLarge,
                        color = VaultColors.OnSurface,
                    )
                }
            },
        )
    }
}

/**
 * The first step: what destroying means, which passphrase is wanted, and the field. Scrolls,
 * because with the keyboard up on a small phone the lost-passphrase line would otherwise push
 * the buttons off the dialog.
 */
@Composable
private fun GateStep(
    name: String,
    typed: String,
    onTyped: (String) -> Unit,
    ownPassphrase: Boolean,
    showLost: Boolean,
    checking: Boolean,
    wrong: Boolean,
    refusedOnce: Boolean,
    onDone: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Focused with the keyboard up when the step appears, and again after a refusal — the
    // field was disabled while it was checked, which took the focus away.
    LaunchedEffect(checking) {
        if (!checking) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.verticalScroll(rememberScrollState()),
    ) {
        Text(stringResource(R.string.destroy_body), style = MaterialTheme.typography.bodyMedium)
        Text(
            if (ownPassphrase) stringResource(R.string.destroy_gate_own, name)
            else stringResource(R.string.destroy_gate_list),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurface,
        )
        Column {
            PassphraseField(
                value = typed,
                onValueChange = onTyped,
                label = stringResource(if (ownPassphrase) R.string.passphrase_label else R.string.vault_passphrase_list_label),
                imeAction = ImeAction.Done,
                enabled = !checking,
                isError = wrong,
                supporting = when {
                    wrong -> if (ownPassphrase) stringResource(R.string.vault_open_wrong, name)
                        else stringResource(R.string.panic_wrong_passphrase)
                    refusedOnce -> ""
                    else -> null
                },
                announce = true,
                onImeAction = onDone,
                modifier = Modifier
                    .padding(top = Spacing.xs)
                    .focusRequester(focus),
            )
            // One indicator, driven by whether a check is running and never by its result —
            // `DESIGN.md` §8.1. The height is reserved so the dialog does not resize.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Spacing.xl)
                    .padding(top = Spacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                if (checking) {
                    LinearProgressIndicator(
                        color = VaultColors.Primary,
                        trackColor = VaultColors.Surface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (ownPassphrase && showLost) {
            Text(stringResource(R.string.destroy_gate_lost), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The second step: today's choice, exactly. */
@Composable
private fun ChoiceStep(
    hasBackup: Boolean,
    backupIsFile: Boolean,
    onReturnThenDestroy: () -> Unit,
    onDestroyEverything: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            stringResource(R.string.destroy_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.destroy_others_safe),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (hasBackup) {
            Text(
                stringResource(if (backupIsFile) R.string.destroy_backup_file else R.string.destroy_backup_folder),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // The choice itself, in the body rather than the button row: these are
        // two peers, and the dialog's confirm slot is the shape that says one of
        // them is the answer. `Keep vault` keeps the dismiss slot, where a
        // dismissal belongs.
        OutlinedButton(
            onClick = onReturnThenDestroy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.sm),
        ) {
            Text(
                stringResource(R.string.destroy_give_back),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        OutlinedButton(
            onClick = onDestroyEverything,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Named for what it leaves behind, never a bare "Confirm" — §6.4
            // keeps the meaning in the word rather than the colour or position.
            Text(
                stringResource(R.string.destroy_everything),
                style = MaterialTheme.typography.labelLarge,
                color = VaultColors.Error,
            )
        }
    }
}

private const val STEP_FADE_MS = 200
