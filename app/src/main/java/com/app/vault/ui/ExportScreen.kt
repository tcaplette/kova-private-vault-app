package com.app.vault.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.app.vault.ExifPolicy
import com.app.vault.PhotoDetail
import com.app.vault.R
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import com.app.vault.words
import com.app.vault.localWords

/** Where an export is going. `MediaStore` unless the user picks a folder. */
enum class ExportTarget { Gallery, Folder }

/**
 * "Unvault Everything" — P1-13.
 *
 * The confirmation, the destination choice, the EXIF toggle, and the passphrase.
 * One screen rather than a wizard: every one of these is a fact about the same
 * decision, and splitting them would let a user confirm before knowing where the
 * files go.
 *
 * **This file references no biometric or key-store API, deliberately.** A
 * fingerprint is something a sleeping owner has taken from them, and this is the
 * one irreversible action in the app. A JVM scan asserts the absence.
 */
@Composable
fun ExportScreen(
    itemCount: Int,
    /**
     * True when this run is scoped to a selection — P2-11.
     *
     * [itemCount] is then the size of the selection rather than the vault's, and
     * every sentence on the screen says so. §6.2's fixed label "Unvault
     * Everything" belongs to the app-level action in the drawer, which is
     * unchanged; a screen that said "Everything" while exporting twelve of two
     * hundred items would be that label lying, which is the opposite of what §6.2
     * protects.
     */
    scoped: Boolean = false,
    /**
     * `exif-strip-choice`. What the strip removes — the install's Photo details selection,
     * named under the switch so a user reads what leaves with the files before it does.
     */
    removed: Set<PhotoDetail> = ExifPolicy.PRIVACY,
    onExport: (ByteArray, ExportTarget, Boolean) -> Unit,
    onPickFolder: () -> Unit,
    onCancel: () -> Unit,
    folderChosen: String?,
    busy: Boolean,
    failed: Boolean,
    progress: Triple<Int, Int, Int>?,
    /** P1-14. Offered alongside, not instead of — the plaintext action keeps §6.2's emphasis. */
    /**
     * `vault-keyring`. **Two passphrases**: the one that authorises the export — the
     * passphrase that opens this vault, proved by a real derivation: its own where it has
     * one, the vault list's otherwise — and the one the archive file is
     * sealed under, which exists only for that file. One argument could not carry both
     * without one of them silently standing in for the other.
     */
    onArchive: (authorising: ByteArray, forTheFile: ByteArray) -> Unit = { _, _ -> },
    /**
     * `intruder-captures-at-home` D9. The export's source is the keyring's capture store, not a
     * vault: every sentence that says "vault" says Intruder capture instead, and the passphrase is
     * the one that opens the list. Always scoped, so the archive is never offered.
     */
    captures: Boolean = false,
    /**
     * `protected-vault-destroy-gate` D5. The run "Give my files back, then destroy" starts. The
     * destroy dialog's gate proved this vault's passphrase moments earlier, with a real
     * derivation, so no field is shown and [onExport] receives an empty array. The archive is
     * absent too: it empties nothing, and the outcome chosen was getting the files back.
     */
    authorised: Boolean = false,
    /** The vault being returned and then destroyed, named under the body when [authorised]. */
    vaultName: String = "",
    /**
     * D8. The open vault has its own passphrase, so the sentence above the field asks for that
     * one and says the vault list's will not do.
     */
    ownPassphrase: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var passphrase by remember { mutableStateOf("") }

    /**
     * `vault-keyring`. The passphrase the **archive file** is sealed under, which is
     * not the one that opens the vault. Held separately so neither field can be
     * mistaken for the other, and cleared with it.
     */
    var archivePassphrase by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(ExportTarget.Gallery) }
    // `exif-strip-choice`. **On by default**: this is the way out, and the cost of the wrong
    // default here is a home address on a file the user handed to someone.
    var stripExif by remember { mutableStateOf(true) }

    NoClipboard {
        // The title is the action's own words. §6.2 makes "Unvault Everything" a
        // fixed label the app is accountable to, so the screen it opens is named
        // the same thing rather than paraphrased into "Export".
        VaultScaffold(
            title = when {
                captures -> pluralStringResource(R.plurals.intruder_export_title, itemCount, itemCount)
                scoped -> pluralStringResource(R.plurals.unvault_items, itemCount, itemCount)
                else -> stringResource(R.string.drawer_unvault_everything)
            },
            modifier = modifier,
            onBack = onCancel,
        ) {
            Text(
                // Says what happens, in words, including the part the user will
                // mind. Not styled as a warning: DESIGN.md §6.2 is explicit that
                // this action is the opposite of destructive, even though it does
                // empty the vault.
                text = when {
                    captures -> pluralStringResource(R.plurals.intruder_export_body, itemCount, itemCount) +
                        "\n\n" + stringResource(R.string.intruder_export_checked)
                    scoped -> pluralStringResource(R.plurals.unvault_selected_body, itemCount, itemCount) +
                        "\n\n" + stringResource(R.string.unvault_checked)
                    else -> pluralStringResource(R.plurals.unvault_all_body, itemCount, itemCount) +
                        "\n\n" + stringResource(R.string.unvault_checked)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.lg),
            )
            if (authorised) {
                Text(
                    text = stringResource(R.string.destroy_return_then, vaultName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurface,
                    modifier = Modifier.padding(bottom = Spacing.lg),
                )
            }

            Text(
                text = stringResource(R.string.unvault_where),
                style = MaterialTheme.typography.titleMedium,
                color = VaultColors.OnSurface,
            )
            Row(
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { target = ExportTarget.Gallery }) {
                    Text(
                        text = stringResource(R.string.unvault_gallery) + if (target == ExportTarget.Gallery) " ✓" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (target == ExportTarget.Gallery) VaultColors.Primary else VaultColors.OnSurfaceVariant,
                    )
                }
                TextButton(onClick = { target = ExportTarget.Folder; onPickFolder() }) {
                    Text(
                        text = if (target == ExportTarget.Folder) stringResource(R.string.unvault_folder_chosen) + " ✓" else stringResource(R.string.unvault_choose_folder),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (target == ExportTarget.Folder) VaultColors.Primary else VaultColors.OnSurfaceVariant,
                    )
                }
            }
            if (target == ExportTarget.Folder && folderChosen != null) {
                Text(
                    text = folderChosen,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurfaceVariant,
                    modifier = Modifier.padding(bottom = Spacing.sm),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.unvault_remove_details),
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColors.OnSurface,
                    )
                    Text(
                        // **State in words, both ways** — the settings-screen rule, and it
                        // matters more now the switch starts on: a user who reads only the
                        // title cannot tell a default-on switch from a default-off one.
                        //
                        // `exif-strip-choice`: what is removed is the Photo details selection's,
                        // in words from the one function the share screen also reads — so the
                        // copy and the strip cannot disagree about what "removed" means.
                        //
                        // JPEG is named because the strip reaches no further, and a control
                        // that implies it cleans a whole library when it cleans half of one is
                        // worse than no control.
                        text = if (stripExif) {
                            ExifPolicy.exportSummary(localWords(), removed)
                        } else {
                            stringResource(R.string.unvault_details_kept)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColors.OnSurfaceVariant,
                    )
                }
                Switch(checked = stripExif, onCheckedChange = { stripExif = it })
            }

            if (!authorised) {
            Text(
                text = stringResource(
                    if (ownPassphrase) R.string.unvault_passphrase_needed_own else R.string.unvault_passphrase_needed
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.sm),
            )
            PassphraseField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = stringResource(R.string.passphrase_label),
                            imeAction = ImeAction.Done,
                            enabled = !busy,
                        )
            }

            if (failed && !busy && !authorised) {
                Text(
                    text = stringResource(
                        if (captures) R.string.intruder_export_wrong_passphrase else R.string.unvault_wrong_passphrase
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }

            // Read from WorkManager, so leaving the screen and coming back shows
            // where the export actually is rather than restarting a local counter.
            if (progress != null) {
                val (done, total, failedCount) = progress
                Text(
                    text = if (failedCount > 0) {
                        stringResource(
                            if (captures) R.string.intruder_export_progress_left else R.string.unvault_progress_left,
                            done, total, failedCount,
                        )
                    } else {
                        stringResource(R.string.unvault_progress, done, total)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurface,
                    modifier = Modifier.padding(top = Spacing.lg),
                )
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else done.toFloat() / total },
                    color = VaultColors.Primary,
                    trackColor = VaultColors.SurfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xs),
                )
            }

            Button(
                onClick = {
                    val bytes = passphrase.toByteArray(Charsets.UTF_8)
                    passphrase = ""
                    onExport(bytes, target, stripExif)
                },
                enabled = !busy && (authorised || passphrase.isNotEmpty()) &&
                    (target == ExportTarget.Gallery || folderChosen != null),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.lg),
            ) {
                Text(
                    text = when {
                        busy && captures -> stringResource(R.string.intruder_export_busy)
                        busy -> stringResource(R.string.unvault_busy)
                        captures -> pluralStringResource(R.plurals.intruder_export_title, itemCount, itemCount)
                        scoped -> pluralStringResource(R.plurals.unvault_items, itemCount, itemCount)
                        else -> stringResource(R.string.drawer_unvault_everything)
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            // P1-14, offered alongside. Deliberately below the primary action and
            // in the same weight as Back: it is a different job, not a competing
            // one, and `DESIGN.md` §6.2 gives the screen's emphasis to the export
            // above.
            //
            // **Absent when the screen is scoped to a selection — P2-11.** The
            // archive is a whole-vault artifact and is deliberately not scoped
            // (the outline scopes delete and the export path, and the archive is
            // neither), so offering it here would put a whole-vault action on a
            // screen whose every other sentence is about twelve items.
            if (!scoped && !authorised) {
            Text(
                text = stringResource(R.string.archive_title),
                style = MaterialTheme.typography.titleMedium,
                color = VaultColors.OnSurface,
                modifier = Modifier.padding(top = Spacing.xl),
            )
            Text(
                text = stringResource(R.string.archive_body),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sm),
            )
            // `vault-keyring`. **A passphrase for the file, not the one above.** Sealing
            // an archive under the passphrase that opens this vault — the list's, for most
            // vaults — would hand whoever received the file a credential the app relies
            // on. The field above authorises the export; this one protects what leaves.
            PassphraseField(
                            value = archivePassphrase,
                            onValueChange = { archivePassphrase = it },
                            label = stringResource(R.string.archive_passphrase_label),
                            imeAction = ImeAction.Done,
                            modifier = Modifier.padding(bottom = Spacing.sm),
                        )
            OutlinedButton(
                onClick = {
                    val authorising = passphrase.toByteArray(Charsets.UTF_8)
                    val forTheFile = archivePassphrase.toByteArray(Charsets.UTF_8)
                    archivePassphrase = ""
                    passphrase = ""
                    onArchive(authorising, forTheFile)
                },
                // Both: the field above proves who is asking, this one seals the file.
                enabled = !busy && passphrase.isNotEmpty() && archivePassphrase.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.archive_save), style = MaterialTheme.typography.labelLarge)
            }
            }
        }
    }
}
