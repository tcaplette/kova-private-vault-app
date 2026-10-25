package com.app.vault.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.vault.Mnemonic
import com.app.vault.PassphraseStrength
import com.app.vault.R
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/** What a file chosen on Import turned out to be — `backup-file-recover-and-import`, D3. */
enum class ImportKind { VAULT_FILE, THIS_LIST, OTHER_LIST }

/** The two Import outcomes that end on a confirmation rather than on the list — D7.5. */
enum class ImportDone { VAULT_RESTORED, LIST_RESTORED }

/**
 * Bringing a vault back from a file — P1-14, redesigned by `recovery-own-slot-only` (D7) and
 * by `backup-file-recover-and-import` (D7).
 *
 * Reached from the list's drawer, so a list is always open. One screen takes a `.vault`, a
 * backup file from a cloud app, or an SD-card backup folder, and asks only for what opens
 * the one chosen: a `.vault`'s passphrase, nothing for a backup of this list, or another
 * list's 24 words — and, where that list is not on this phone, the passphrase it comes back
 * under as its own list.
 *
 * **No biometric or key-store reference anywhere in this file**, deliberately;
 * the JVM scan asserts it.
 */
@Composable
fun ArchiveImportScreen(
    mnemonic: Mnemonic,
    onPickFile: () -> Unit,
    onPickFolder: () -> Unit,
    /** A `.vault`'s passphrase. */
    onImport: (ByteArray) -> Unit,
    /** A backup of this list: no credential. */
    onRestoreThisList: () -> Unit,
    /** Another list's backup: its 24 words. */
    onWords: (String) -> Unit,
    /** Another list, not on this phone: its words and the passphrase it comes back under. */
    onRestoreAsList: (String, ByteArray) -> Unit,
    /** Back from the passphrase step to the words, which are kept. */
    onBackToWords: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    /** The chosen file's or folder's display name, or null before one is chosen. */
    fileChosen: String?,
    busy: Boolean,
    /** The `.vault` did not open — one outcome for every cause (FORMAT.md §11). */
    failed: Boolean,
    progress: Pair<Int, Int>?,
    modifier: Modifier = Modifier,
    /** What the chosen file is, or null while it is being read. */
    kind: ImportKind? = null,
    /** The choice is an SD-card folder. */
    folder: Boolean = false,
    /** The list has no room for another vault. */
    full: Boolean = false,
    /**
     * P1-16. Why the import did not start, when entitlement is the reason. A
     * plain string rather than any billing type on purpose: this screen learns
     * nothing about entitlement, which is what keeps it off the gated list.
     */
    blocked: String? = null,
    /** The words do not open the chosen backup. */
    wordsFailed: Boolean = false,
    /** A problem with the chosen backup itself, shown under its row. */
    backupProblem: String? = null,
    /** D4.5. The backup's list is not on this phone: the passphrase step. */
    needsPassphrase: Boolean = false,
    inUse: Boolean = false,
    noRoom: Boolean = false,
    /** The files are in and the passphrase is being sealed. */
    sealing: Boolean = false,
    done: ImportDone? = null,
) {
    var archivePassphrase by remember { mutableStateOf("") }
    var words by remember { mutableStateOf(emptyKit()) }

    fun submitArchive() {
        if (!busy && fileChosen != null && archivePassphrase.isNotEmpty()) {
            onImport(archivePassphrase.toByteArray(Charsets.UTF_8))
        }
    }

    NoClipboard {
        VaultScaffold(
            title = stringResource(R.string.import_title),
            modifier = modifier,
            // Back steps back through the flow, then to the list. Nothing moves while busy.
            onBack = {
                when {
                    busy -> Unit
                    done != null -> onDone()
                    needsPassphrase -> onBackToWords()
                    else -> onCancel()
                }
            },
        ) {
            if (done != null) {
                ImportConfirmation(done, onDone)
                return@VaultScaffold
            }
            if (needsPassphrase) {
                NewListPassphraseStep(
                    busy = busy,
                    progress = progress,
                    sealing = sealing,
                    inUse = inUse,
                    noRoom = noRoom,
                    onSubmit = { onRestoreAsList(words.joinToString(" "), it) },
                )
                return@VaultScaffold
            }

            Text(
                text = stringResource(R.string.import_lead),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.lg),
            )

            if (fileChosen == null) {
                // The Restore screen's own pair, so the two screens read alike.
                OutlinedButton(
                    onClick = onPickFile,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.import_choose_file), style = MaterialTheme.typography.labelLarge)
                }
                TextButton(
                    onClick = onPickFolder,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.sm),
                ) {
                    Text(stringResource(R.string.backup_on_sd_card), style = MaterialTheme.typography.labelLarge)
                }
                return@VaultScaffold
            }

            // Stays tappable once chosen, so a wrong pick is changed where it is shown.
            ChosenBackupRow(
                name = fileChosen,
                kind = when {
                    folder -> stringResource(R.string.kit_sd_backup)
                    kind == ImportKind.VAULT_FILE -> stringResource(R.string.import_vault_file)
                    kind == null -> null
                    else -> stringResource(R.string.kit_backup_file)
                },
                folder = folder,
                enabled = !busy,
                onClick = if (folder) onPickFolder else onPickFile,
            )
            if (backupProblem != null) {
                Refusal(backupProblem, Modifier.padding(top = Spacing.sm))
            } else if (kind == null) {
                // Reading the file's head — a cloud app may have to fetch it first.
                LinearProgressIndicator(
                    color = VaultColors.Primary,
                    trackColor = VaultColors.SurfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.md),
                )
            }

            when (kind) {
                null -> Unit
                ImportKind.VAULT_FILE -> {
                    // One message for every failure to open — the screen names both
                    // possibilities rather than guessing, because it genuinely cannot tell.
                    val refusal = blocked
                        ?: if (full) stringResource(R.string.import_list_full)
                        else if (failed) stringResource(R.string.import_failed)
                        else null
                    PassphraseField(
                        value = archivePassphrase,
                        onValueChange = { archivePassphrase = it },
                        label = stringResource(R.string.import_passphrase_label),
                        imeAction = ImeAction.Done,
                        enabled = !busy,
                        isError = refusal != null,
                        supporting = refusal,
                        announce = refusal != null,
                        onImeAction = ::submitArchive,
                        modifier = Modifier.padding(top = Spacing.lg),
                    )
                    PrimaryAction(
                        label = stringResource(if (busy) R.string.import_importing else R.string.import_action),
                        enabled = !busy && archivePassphrase.isNotEmpty(),
                        onClick = ::submitArchive,
                    )
                }
                ImportKind.THIS_LIST -> {
                    Text(
                        text = stringResource(R.string.import_backup_here),
                        style = MaterialTheme.typography.bodyLarge,
                        color = VaultColors.OnSurface,
                        modifier = Modifier.padding(top = Spacing.lg),
                    )
                    PrimaryAction(
                        label = stringResource(R.string.import_restore_vault),
                        enabled = !busy && backupProblem == null,
                        onClick = onRestoreThisList,
                    )
                }
                ImportKind.OTHER_LIST -> {
                    Text(
                        text = stringResource(R.string.import_other_list),
                        style = MaterialTheme.typography.bodyLarge,
                        color = VaultColors.OnSurface,
                        modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.lg),
                    )
                    MnemonicWordEntry(
                        mnemonic = mnemonic,
                        words = words,
                        onWordsChange = { words = it },
                        enabled = !busy,
                    )
                    if (wordsFailed) {
                        Refusal(stringResource(R.string.import_words_wrong_backup), Modifier.padding(top = Spacing.md))
                    }
                    val kitProblem = kitProblem(mnemonic, words)
                    // The reason sits with the action rather than the action simply being dead.
                    if (kitProblem != null) {
                        Text(
                            text = kitProblem,
                            style = MaterialTheme.typography.bodyMedium,
                            color = VaultColors.OnSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.lg),
                        )
                    }
                    PrimaryAction(
                        label = stringResource(R.string.action_continue),
                        enabled = !busy && kitProblem == null,
                        onClick = { onWords(words.joinToString(" ")) },
                    )
                }
            }

            if (busy) CopyProgress(progress, sealing)
        }
    }
}

/** D4.5's step: the passphrase another list comes back under. */
@Composable
private fun NewListPassphraseStep(
    busy: Boolean,
    progress: Pair<Int, Int>?,
    sealing: Boolean,
    inUse: Boolean,
    noRoom: Boolean,
    onSubmit: (ByteArray) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var tooWeak by remember { mutableStateOf(false) }
    var mismatch by remember { mutableStateOf(false) }
    // A refusal belongs to the passphrase that was submitted; editing it retires the message.
    var edited by remember { mutableStateOf(false) }
    val verdict = rememberPassphraseVerdict(passphrase)

    fun submit() {
        if (busy) return
        val bytes = passphrase.toByteArray(Charsets.UTF_8)
        // Re-checked here rather than trusting the meter's verdict, which is debounced.
        tooWeak = !PassphraseStrength.clears(passphrase)
        mismatch = !tooWeak && passphrase != confirm
        if (tooWeak || mismatch) {
            bytes.fill(0)
            return
        }
        edited = false
        onSubmit(bytes)
    }

    Text(
        text = stringResource(R.string.import_own_list),
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.lg),
    )
    val firstError = when {
        tooWeak -> PassphraseTooWeak
        inUse && !edited -> stringResource(R.string.kit_passphrase_in_use)
        else -> null
    }
    PassphraseField(
        value = passphrase,
        onValueChange = {
            passphrase = it
            tooWeak = false
            mismatch = false
            edited = true
        },
        label = stringResource(R.string.kit_new_passphrase_label),
        imeAction = ImeAction.Next,
        enabled = !busy,
        isError = firstError != null,
        supporting = firstError ?: PassphraseRequirement,
        announce = firstError != null,
    )
    PassphraseStrengthMeter(
        verdict = verdict,
        modifier = Modifier.padding(top = Spacing.sm),
    )
    PassphraseField(
        value = confirm,
        onValueChange = { confirm = it; mismatch = false },
        label = stringResource(R.string.passphrase_again_label),
        imeAction = ImeAction.Done,
        enabled = !busy,
        isError = mismatch,
        supporting = if (mismatch) stringResource(R.string.kit_mismatch) else null,
        announce = mismatch,
        onImeAction = ::submit,
        modifier = Modifier.padding(top = Spacing.sm),
    )
    // Not about either field: it is the phone that has no slot left, so it sits with the action.
    if (noRoom && !edited) {
        Refusal(stringResource(R.string.import_no_room), Modifier.padding(top = Spacing.md))
    }
    PrimaryAction(
        label = stringResource(R.string.import_restore_list),
        enabled = !busy && passphrase.isNotEmpty(),
        onClick = ::submit,
    )
    if (busy) CopyProgress(progress, sealing)
}

/**
 * D7.4. The copy as a determinate bar with "done of total" in tabular figures, then the seal
 * as an indeterminate one. A polite live region, so a screen reader hears it move on.
 */
@Composable
internal fun CopyProgress(progress: Pair<Int, Int>?, sealing: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        if (sealing) {
            Text(
                text = stringResource(R.string.kit_setting_passphrase),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurface,
            )
            LinearProgressIndicator(
                color = VaultColors.Primary,
                trackColor = VaultColors.SurfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs),
            )
        } else {
            val (done, total) = progress ?: (0 to 0)
            Text(
                text = if (total == 0) stringResource(R.string.import_copying) else stringResource(R.string.restore_copying, done, total),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = VaultColors.OnSurface,
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
    }
}

/** D7.5. For the outcomes not visible on the open list. */
@Composable
private fun ImportConfirmation(done: ImportDone, onDone: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xxxl),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_check_circle),
            // The headline beneath says what happened; the glyph repeats it.
            contentDescription = null,
            tint = VaultColors.Primary,
            modifier = Modifier.size(72.dp),
        )
        Text(
            text = stringResource(if (done == ImportDone.VAULT_RESTORED) R.string.import_vault_restored else R.string.import_list_restored),
            style = MaterialTheme.typography.headlineSmall,
            color = VaultColors.OnSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = Spacing.xl)
                .semantics { heading(); liveRegion = LiveRegionMode.Polite },
        )
        Text(
            text = if (done == ImportDone.VAULT_RESTORED) {
                stringResource(R.string.import_vault_restored_body)
            } else {
                stringResource(R.string.import_list_restored_body)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = VaultColors.OnSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
    PrimaryAction(label = stringResource(R.string.action_done), enabled = true, onClick = onDone, top = Spacing.xxl)
}

/**
 * The chosen file or folder — its name, and what it is beneath. Import's row is tappable to
 * choose again; Recover's carries a Remove action instead (D8).
 */
@Composable
internal fun ChosenBackupRow(
    name: String,
    kind: String?,
    folder: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(start = Spacing.lg, end = if (trailing != null) Spacing.xs else Spacing.lg),
        ) {
            Icon(
                painter = painterResource(if (folder) R.drawable.ic_folder else R.drawable.ic_description),
                contentDescription = null,
                tint = VaultColors.OnSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(Spacing.lg))
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = Spacing.md),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = VaultColors.OnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                if (kind != null) {
                    Text(
                        text = kind,
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColors.OnSurfaceVariant,
                    )
                }
            }
            trailing?.invoke()
        }
    }
    val border = BorderStroke(1.dp, VaultColors.OutlineVariant)
    val shape = MaterialTheme.shapes.medium
    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            border = border,
            color = VaultColors.Surface,
            modifier = modifier.fillMaxWidth(),
            content = content,
        )
    } else {
        Surface(shape = shape, border = border, color = VaultColors.Surface, modifier = modifier.fillMaxWidth(), content = content)
    }
}

/** A refusal: the error colour, announced politely to a screen reader. */
@Composable
internal fun Refusal(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.Error,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun PrimaryAction(label: String, enabled: Boolean, onClick: () -> Unit, top: androidx.compose.ui.unit.Dp = Spacing.lg) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = top),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

