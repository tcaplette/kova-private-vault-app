package com.app.vault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.app.vault.PassphraseStrength
import com.app.vault.R
import androidx.compose.ui.unit.dp
import com.app.vault.Mnemonic
import com.app.vault.VaultKeyring
import com.app.vault.Words
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import com.app.vault.ui.theme.VaultMono
import com.app.vault.words
import com.app.vault.localWords

/** The phrase the user must type. Not a checkbox — see [RecoveryKitScreen]. */
val ACKNOWLEDGEMENT = R.string.kit_acknowledgement

/**
 * The recovery kit, shown immediately after the vault is created — P1-12 — and in its review
 * form for each of the three later views `recovery-kit-three-views` allows.
 *
 * The words render in [VaultMono] with tabular figures and carry their position,
 * because `DESIGN.md` §5.1 names this screen: the user transcribes 24 words by
 * hand, and `0`/`O` or `1`/`l` confusion here costs them the vault.
 *
 * The acknowledgement is typed rather than ticked. A checkbox is dismissed by
 * reflex, and reflex is the failure mode this screen exists to prevent — the
 * whole point is that the user has understood something irreversible.
 *
 * One `primary` action (`DESIGN.md` §6.1): continue. Print is outlined beside it.
 */
@Composable
fun RecoveryKitScreen(
    words: List<String>,
    onPrint: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The vault these words are for, or null when they are the keyring's —
     * `per-vault-passphrases`.
     *
     * **Two texts, one composable**, which is `DESIGN.md` §6.7's rule for the delete
     * dialog applied here for its reason: there are now two acts, and what changes is
     * which sentence is true. A keyring's words open the vaults the master passphrase
     * opens; a vault's words open that one vault. The old text claimed the first
     * covered everything on the list, and that stopped being true the moment a vault
     * could carry its own passphrase.
     */
    vaultName: String? = null,
    /**
     * `recovery-kit-three-views`, D4. Non-null is the **review** form — a later view from the
     * drawer — and is the line above the words saying what is left after it. That form has no
     * acknowledgement to type (it was typed at creation), offers Done at once, and back is Done:
     * the view was spent when the words appeared, so leaving loses nothing.
     */
    status: KitStatus? = null,
    /**
     * At creation, the views the menu will still offer — `vault-passphrase-manage`, D3. Three for a
     * new kit; fewer when a vault's passphrase is added again and its count was carried.
     */
    createdViewsLeft: Int = com.app.vault.VaultKeyring.KIT_VIEWS,
    /**
     * `recovery-practice` D5. The creation form reopened from the kit check: the acknowledgement
     * was already typed for these words, so it starts typed and Continue is available at once.
     */
    acknowledged: Boolean = false,
) {
    // `app-languages`: the phrase in the app's language; the instruction and this read the same one.
    val phrase = stringResource(ACKNOWLEDGEMENT)
    var typed by remember { mutableStateOf(if (acknowledged && status == null) phrase else "") }
    val ready = status != null || typed.trim().equals(phrase, ignoreCase = false)

    // At creation: no back affordance, and `MainActivity.dismissTop` does not list this
    // form. A gesture that dismissed the words before they were written down would cost
    // one of only three later views. Leaving is `onContinue`, after the acknowledgement.
    VaultScaffold(
        title = if (vaultName == null) stringResource(R.string.kit_title_list) else stringResource(R.string.kit_title_vault, vaultName),
        modifier = modifier,
        onBack = if (status != null) onContinue else null,
    ) {
        Text(
            // Every clause here is a fact the design forced, not a warning added
            // for tone. The words are the key (they have to be, to survive a
            // wiped install), so the page is the vault and cannot be revoked.
            text = if (vaultName == null) {
                // The keyring's kit. **It no longer claims to cover every vault on the
                // list**, because it cannot: a vault sealed under its own passphrase is
                // not opened by the keyring's key, and a kit that overstates what it is
                // worth is the kit someone throws away.
                stringResource(R.string.kit_body_list) + remainingViews(status, createdViewsLeft) + "\n\n" +
                    // `first-run-onboarding` 9.1 / `cloud-mirror` D3. Said whether or not
                    // a backup exists yet: at this point in first run the backup step has
                    // not run, and these are the words that will read a backup the user
                    // has not made. The slot file a passphrase unwraps is deliberately
                    // never mirrored, which is what makes a cloud copy safe to keep — and
                    // what makes this page the only way to read one.
                    stringResource(R.string.kit_body_list_backup)
            } else {
                // One vault's kit. It says the smaller true thing, and it says the part
                // that matters most here: the master passphrase is not a way back in.
                stringResource(R.string.kit_body_vault, vaultName) + remainingViews(status, createdViewsLeft)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.lg),
        )

        // D4. What this view leaves, directly above the words it is about. The last view takes
        // the error colour, and the sentence says it without the colour.
        if (status != null) {
            Text(
                text = status.line(localWords()),
                style = MaterialTheme.typography.titleMedium,
                color = if (status.left == 0) VaultColors.Error else VaultColors.OnSurface,
                modifier = Modifier.padding(bottom = Spacing.md),
            )
        }

        WordGrid(words)

        if (status == null) {
            Text(
                text = stringResource(R.string.kit_type_acknowledgement, phrase),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xl, bottom = Spacing.sm),
            )
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onPrint) { Text(stringResource(R.string.kit_print)) }
            Button(onClick = onContinue, enabled = ready) {
                Text(stringResource(if (status != null) R.string.action_done else R.string.action_continue))
            }
        }
    }
}

/**
 * A later view's count — `recovery-kit-three-views`. [left] is the views left after this one;
 * [line] is D5's status sentence for it. Carried through a lock by the held-kit mechanism.
 */
class KitStatus(val left: Int) {
    fun line(words: Words): String =
        if (left == 0) words.get(R.string.kit_last_view) else words.plural(R.plurals.kit_views_left_after, left, left)
}

/** D5's setup sentence — at creation only; a later view has its own status line instead. */
@Composable
private fun remainingViews(status: KitStatus?, left: Int): String = when {
    status != null -> ""
    left >= com.app.vault.VaultKeyring.KIT_VIEWS -> " " + stringResource(R.string.kit_remaining_views)
    left > 0 -> " " + pluralStringResource(R.plurals.kit_remaining_views_fewer, left, left)
    else -> " " + stringResource(R.string.kit_remaining_views_none)
}

/** The drawer row's count — D5. */
@Composable
fun kitViewsLabel(left: Int): String = stringResource(
    R.string.drawer_kit,
    if (left == 0) stringResource(R.string.kit_cannot_show) else pluralStringResource(R.plurals.kit_views_left, left, left),
)

/**
 * A later view of a recovery kit, before the words — `recovery-kit-three-views`, D4.
 *
 * Reached from the drawer row. With views left: the hero, the warning for this count (the last
 * view has its own), the passphrase the kit stands in for, and **Show recovery kit**. With none:
 * the hero, why it can't be shown, and Done. Only the passphrase is accepted — the kit is the
 * credential that replaces it, so a fingerprint does not stand in for it.
 *
 * [vaultName] is null for the list's own kit.
 */
@Composable
fun RecoveryKitViewScreen(
    viewsLeft: Int,
    vaultName: String?,
    busy: Boolean,
    /** The passphrase did not open this kit — said under the field. */
    wrongPassphrase: Boolean,
    /** The count could not be saved, so nothing was shown and nothing spent. */
    failed: Boolean,
    onShow: (ByteArray) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var passphrase by remember { mutableStateOf("") }
    // The refusal belongs to the passphrase that was submitted; editing retires it.
    var editedSinceSubmit by remember { mutableStateOf(false) }

    fun submit() {
        if (busy || passphrase.isEmpty()) return
        editedSinceSubmit = false
        onShow(passphrase.toByteArray(Charsets.UTF_8))
    }

    NoClipboard {
        VaultScaffold(
            title = if (vaultName == null) stringResource(R.string.kit_title_short) else stringResource(R.string.kit_title_vault, vaultName),
            modifier = modifier,
            onBack = onDone,
        ) {
            Spacer(Modifier.height(Spacing.sm))
            KitHero(exhausted = viewsLeft <= 0)
            Spacer(Modifier.height(Spacing.lg))

            if (viewsLeft <= 0) {
                Text(
                    text = stringResource(R.string.kit_exhausted),
                    style = MaterialTheme.typography.bodyLarge,
                    color = VaultColors.OnSurface,
                )
                Button(
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.section),
                ) {
                    Text(stringResource(R.string.action_done), style = MaterialTheme.typography.labelLarge)
                }
                return@VaultScaffold
            }

            val enter = if (vaultName == null) {
                stringResource(R.string.kit_enter_list)
            } else {
                stringResource(R.string.kit_enter_vault, vaultName)
            }
            Text(
                text = if (viewsLeft == 1) {
                    stringResource(R.string.kit_last_time, enter)
                } else {
                    pluralStringResource(R.plurals.kit_views_more, viewsLeft, viewsLeft, viewsLeft - 1, enter)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = VaultColors.OnSurface,
            )

            val refusal = when {
                wrongPassphrase && !editedSinceSubmit ->
                    if (vaultName == null) stringResource(R.string.kit_wrong_list)
                    else stringResource(R.string.kit_wrong_vault)
                else -> null
            }
            PassphraseField(
                value = passphrase,
                onValueChange = {
                    passphrase = it
                    editedSinceSubmit = true
                },
                label = if (vaultName == null) stringResource(R.string.panic_your_passphrase) else stringResource(R.string.kit_passphrase_for, vaultName),
                enabled = !busy,
                isError = refusal != null,
                supporting = refusal,
                announce = refusal != null,
                onImeAction = ::submit,
                modifier = Modifier.padding(top = Spacing.xl),
            )

            if (failed && !editedSinceSubmit) {
                Text(
                    text = stringResource(R.string.kit_open_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.Error,
                    modifier = Modifier
                        .padding(top = Spacing.md)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            // One derivation, about half a second: busy is drawn on the button itself.
            Button(
                onClick = ::submit,
                enabled = !busy && passphrase.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.lg),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(stringResource(if (busy) R.string.decoy_checking else R.string.kit_show), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** The Restore screen's hero form: a 72dp circle holding the kit's glyph. Muted when none are left. */
@Composable
private fun KitHero(exhausted: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(VaultColors.SurfaceContainerHigh),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_description),
            // The bar's title names the screen; the glyph repeats it.
            contentDescription = null,
            tint = if (exhausted) VaultColors.OnSurfaceVariant else VaultColors.Primary,
            modifier = Modifier.size(40.dp),
        )
    }
}


/**
 * 24 words, numbered, two to a row — `recovery-kit-entry`.
 *
 * One composable for all three screens that show words. It was the kit screen's alone;
 * the two recovery screens now draw the *reading* of what was typed through it, so the
 * user compares the app's list against the paper in their hand in the layout they wrote
 * it down from.
 *
 * Monospace for §5.1's reason: these are transcribed by hand, and `0`/`O` or `1`/`l`
 * confusion here costs the vault.
 *
 * Read-only. Its editable twin is [MnemonicWordEntry], which owns marking a word as
 * unrecognised because it is the only one of the two where a word can be wrong.
 */
@Composable
private fun WordGrid(words: List<String>) {
    words.chunked(2).forEachIndexed { row, pair ->
        Row(modifier = Modifier.fillMaxWidth()) {
            pair.forEachIndexed { column, word ->
                val position = row * 2 + column
                Text(
                    text = "${position + 1}. $word",
                    style = VaultMono.bodyLarge,
                    color = VaultColors.OnSurface,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = Spacing.xs),
                )
            }
        }
    }
}

/**
 * Opening a vault list from 24 words — P1-12, rebuilt by `recovery-kit-entry` and
 * `recovery-own-slot-only`.
 *
 * Reached from the lock screen, secondary to the passphrase, and after a backup is
 * restored onto an empty phone.
 *
 * **Two steps.** Transcribing the words comes first, on a screen that does nothing else;
 * the second is a passphrase form (D6): what happens, the new passphrase, the same again,
 * one action. Recovery writes only the recovered list's own slot, so there is nothing
 * else to warn about or to ask for — and nothing here names other lists, decoys or a
 * panic phrase, because this is reachable by whoever holds the phone.
 *
 * A failure caused by the words returns to the words, with the message under them: shown
 * on the passphrase step, it sat beside the field that was not the problem.
 */
@Composable
fun RecoverScreen(
    mnemonic: Mnemonic,
    onRecover: (String, ByteArray) -> Unit,
    onCancel: () -> Unit,
    busy: Boolean,
    /** The words opened nothing here, or a backup file refused them — shown on step 1. */
    failed: Boolean,
    /**
     * What [failed] says. `backup-to-cloud-apps` D7: restoring a backup file checks the
     * words against that file, so the refusal names the file rather than the device.
     */
    failedMessage: String = stringResource(R.string.kit_words_open_nothing),
    /** The new passphrase already opens something here — shown under the first field. */
    inUse: Boolean = false,
    /** A backup restored onto an empty phone, rather than a forgotten passphrase. */
    restoring: Boolean = false,
    modifier: Modifier = Modifier,
    /**
     * `backup-file-recover-and-import`, D8. The backup chosen to bring files back from — its
     * name, and whether it is an SD-card folder — or null.
     */
    backupName: String? = null,
    backupFolder: Boolean = false,
    onAddBackupFile: () -> Unit = {},
    onAddBackupFolder: () -> Unit = {},
    /** Null hides Remove — the empty-phone restore arrived with its backup and keeps it. */
    onRemoveBackup: (() -> Unit)? = null,
    /** A refusal about the backup: shown under its row, back on the words step. */
    backupProblem: String? = null,
    /** The backup's files being copied, on step 2. */
    progress: Pair<Int, Int>? = null,
    /** The files are in and the passphrase is being sealed. */
    sealing: Boolean = false,
) {
    var words by remember { mutableStateOf(emptyKit()) }
    var settingPassphrase by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var tooWeak by remember { mutableStateOf(false) }
    var mismatch by remember { mutableStateOf(false) }
    // The in-use refusal belongs to the passphrase that was submitted; editing it retires
    // the message rather than leaving it under a passphrase it was never about.
    var editedSinceSubmit by remember { mutableStateOf(false) }
    val verdict = rememberPassphraseVerdict(passphrase)

    val kitProblem = kitProblem(mnemonic, words)

    // D6. The words were the problem — or the backup was: back to them, words kept,
    // passphrases cleared.
    val backToWords = failed || backupProblem != null
    LaunchedEffect(backToWords) {
        if (backToWords) {
            settingPassphrase = false
            passphrase = ""
            confirm = ""
            tooWeak = false
            mismatch = false
        }
    }

    fun submit() {
        if (busy) return
        val bytes = passphrase.toByteArray(Charsets.UTF_8)
        // Re-checked here rather than trusting the meter's verdict, which is debounced
        // and may be a keystroke behind what is in the field.
        tooWeak = !PassphraseStrength.clears(passphrase)
        mismatch = !tooWeak && passphrase != confirm
        if (tooWeak || mismatch) {
            bytes.fill(0)
            return
        }
        editedSinceSubmit = false
        onRecover(words.joinToString(" "), bytes)
    }

    NoClipboard {
        VaultScaffold(
            title = stringResource(if (settingPassphrase) R.string.kit_new_passphrase_title else R.string.kit_enter_title),
            modifier = modifier,
            // Back from the second step returns to the words rather than leaving, so a
            // user who wants to check a word against the paper does not lose the 24 they
            // have already typed.
            onBack = { if (settingPassphrase) settingPassphrase = false else onCancel() },
        ) {
            if (!settingPassphrase) {
                Text(
                    text = stringResource(R.string.kit_enter_lead),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.lg),
                )

                MnemonicWordEntry(
                    mnemonic = mnemonic,
                    words = words,
                    onWordsChange = { words = it },
                    enabled = !busy,
                )

                if (failed) {
                    Text(
                        text = failedMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColors.Error,
                        modifier = Modifier
                            .padding(top = Spacing.md)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }

                // D8. A backup brings the vault's files back as well as the passphrase.
                if (backupName == null) {
                    OutlinedButton(
                        onClick = onAddBackupFile,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.xl),
                    ) {
                        Text(stringResource(R.string.kit_add_backup_file), style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(
                        onClick = onAddBackupFolder,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.sm),
                    ) {
                        Text(stringResource(R.string.backup_on_sd_card), style = MaterialTheme.typography.labelLarge)
                    }
                    Text(
                        text = stringResource(R.string.kit_lost_files),
                        style = MaterialTheme.typography.bodySmall,
                        color = VaultColors.OnSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                } else {
                    ChosenBackupRow(
                        name = backupName,
                        kind = stringResource(if (backupFolder) R.string.kit_sd_backup else R.string.kit_backup_file),
                        folder = backupFolder,
                        enabled = !busy,
                        modifier = Modifier.padding(top = Spacing.xl),
                        trailing = onRemoveBackup?.let { remove ->
                            {
                                TextButton(onClick = remove, enabled = !busy) {
                                    Text(stringResource(R.string.action_remove), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        },
                    )
                    if (backupProblem != null) {
                        Refusal(backupProblem, Modifier.padding(top = Spacing.sm))
                    }
                }

                // The reason sits with the action rather than the action simply being
                // dead — `recovery-kit-entry`.
                if (kitProblem != null) {
                    Text(
                        text = kitProblem,
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColors.OnSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.lg),
                    )
                }
                Button(
                    onClick = { settingPassphrase = true },
                    enabled = !busy && kitProblem == null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.sm),
                ) {
                    Text(stringResource(R.string.action_continue))
                }
                return@VaultScaffold
            }

            Text(
                text = stringResource(if (restoring) R.string.kit_new_passphrase_restoring else R.string.kit_new_passphrase_lead),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.lg),
            )

            val firstError = when {
                tooWeak -> PassphraseTooWeak
                inUse && !editedSinceSubmit ->
                    stringResource(R.string.kit_passphrase_in_use)
                else -> null
            }
            PassphraseField(
                value = passphrase,
                onValueChange = {
                    passphrase = it
                    tooWeak = false
                    mismatch = false
                    editedSinceSubmit = true
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

            // D8. A backup's files are copied before the passphrase is set, and the copy shows;
            // then the button says the passphrase is being set, as it always has.
            val copying = busy && progress != null && !sealing
            if (copying) CopyProgress(progress, sealing = false)

            // The derivation takes about half a second, and a button that doesn't respond
            // looks broken — so busy is drawn on the button itself.
            Button(
                onClick = ::submit,
                enabled = !busy && passphrase.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.lg),
            ) {
                if (busy && !copying) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(
                    stringResource(if (busy && !copying) R.string.kit_setting_passphrase else R.string.kit_set_passphrase),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * Recovering **one vault** onto the keyring that is already open —
 * `per-vault-passphrases`.
 *
 * [RecoverScreen]'s sibling, and deliberately not the same screen. That one is reached
 * from the lock screen by someone who has lost the passphrase to everything; it rewrites
 * the whole slot array and has to warn about what that destroys. This one is reached
 * from the vault list by someone who is already in, and it destroys nothing at all — the
 * vault it recovers joins the list beside the others.
 *
 * **The user is never asked which kind of kit they hold.** The field is the same field
 * and the words are the same 24 words; where they were entered decides how they are
 * read. A screen that asked would be asking the user to know something the app can
 * work out.
 *
 * The failure text is the one every other recovery failure gives. A wrong checksum, a
 * vault whose blobs are not on this device, and words for a keyring rather than a vault
 * are one outcome — telling them apart would say what is and is not here.
 */
@Composable
fun RecoverVaultScreen(
    mnemonic: Mnemonic,
    onRecover: (String, String) -> Unit,
    onCancel: () -> Unit,
    busy: Boolean,
    failed: Boolean,
    modifier: Modifier = Modifier,
) {
    var words by remember { mutableStateOf(emptyKit()) }
    var name by remember { mutableStateOf("") }

    val kitProblem = kitProblem(mnemonic, words)

    NoClipboard {
        VaultScaffold(
            title = stringResource(R.string.kit_recover_title),
            modifier = modifier,
            onBack = onCancel,
        ) {
            Text(
                text = stringResource(R.string.kit_recover_lead),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.lg),
            )

            MnemonicWordEntry(
                mnemonic = mnemonic,
                words = words,
                onWordsChange = { words = it },
                enabled = !busy,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { if (VaultKeyring.fits(it) || it.isEmpty()) name = it },
                label = { Text(stringResource(R.string.kit_recover_name)) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.lg),
            )
            // The name is this list's, not the vault's: a name lives in a keyring entry
            // and the words carry no entry with them. Said, so a user who expects the
            // old name back is not left wondering where it went.
            Text(
                text = stringResource(R.string.kit_recover_name_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )

            if (failed) {
                Text(
                    text = stringResource(R.string.kit_words_open_nothing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.Error,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }

            // The reason beside the action, never a dead button — `recovery-kit-entry`.
            val reason = kitProblem ?: if (name.isBlank()) stringResource(R.string.vault_name_empty) else null
            if (reason != null) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.lg),
                )
            }

            Button(
                onClick = { onRecover(words.joinToString(" "), name.trim()) },
                enabled = !busy && reason == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm),
            ) {
                Text(stringResource(if (busy) R.string.kit_recovering else R.string.kit_recover_action))
            }
        }
    }
}

