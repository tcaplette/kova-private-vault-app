package com.app.vault.ui

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.app.vault.CloudMirror
import com.app.vault.R
import com.app.vault.Words
import com.app.vault.ui.theme.Radius
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import com.app.vault.words
import com.app.vault.localWords

/**
 * Where this vault's backup stands — `backup-screen-status-first` D3.
 *
 * Derived, never stored: every state but [Copying] and [Partial] comes from listing the
 * folder and comparing it with this vault's files (`CloudMirror.status`), and those two
 * come from the run in progress. `cloud-mirror` forbids new persisted state, and a
 * stored "last backup" would say when the app tried rather than whether the folder
 * matches.
 */
sealed interface BackupStatus {
    data object Off : BackupStatus
    data object Checking : BackupStatus
    data class UpToDate(val lastCopied: Long?) : BackupStatus
    data class Behind(val pending: Int, val lastCopied: Long?) : BackupStatus
    /**
     * A run in progress. A folder counts files; a backup file ([bytes]) counts bytes,
     * because it is one file — `backup-to-cloud-apps`.
     */
    data class Copying(val done: Long, val total: Long, val bytes: Boolean = false) : BackupStatus
    /** A run ended with files that did not transfer. The next run retries them. */
    data class Partial(val failed: Int) : BackupStatus
    /**
     * The destination could not be read — revoked, deleted, uninstalled, signed out. [file]
     * names which kind, because "the folder" is the wrong word for a cloud app's file.
     */
    data class Unreachable(val file: Boolean = false) : BackupStatus
    /**
     * A backup file run stopped before writing: the provider may stage a full copy while it
     * uploads, and the phone is [short] bytes short of that — `backup-to-cloud-apps` D4.
     */
    data class NoSpace(val short: Long) : BackupStatus
}

/** The restore screen's state — `backup-screen-status-first` D8. */
sealed interface RestoreState {
    data object Idle : RestoreState
    data class Copying(val done: Int, val total: Int) : RestoreState
    data class Problem(val message: String) : RestoreState
}

/**
 * A file chosen on the restore screen with no vault list on the device —
 * `switch-builds-without-losing-vaults`, D1 and D3. [vault] is false while its head is read
 * for its kind; a backup leaves for the recovery kit, so a file that stays is a `.vault`.
 * [problem] is the refusal under its passphrase field.
 */
data class RestoreFile(val name: String, val vault: Boolean, val problem: String? = null)

/**
 * The Backup screen — `backup-screen-status-first`, on `cloud-mirror`.
 *
 * **State first, then one action, then rows.** The shape iCloud Backup, Google's
 * Backup settings and Signal's chat backups share: the top answers *is this vault in
 * its folder?*, one filled button does the one thing the state calls for, the folder
 * and the explanation are rows, and what the user has to accept is said once, in the
 * sheet that turns backup on. Six blocks of explanation above the button was the
 * reverse of that, and it is gone.
 *
 * **No provider is named as a partner and none is integrated.** The picker shows
 * whatever `DocumentsProvider` the device has; that app does the uploading under its
 * own permissions, and there is still no `INTERNET` in the merged manifest.
 *
 * **Nothing here says "backed up".** The app can list the folder, so it says what the
 * folder holds — *up to date*, *last copied* — and never that bytes reached a
 * datacentre, which it cannot see.
 *
 * **With no vault open it is the restore screen** ([restoreMode]): a replacement
 * device has nothing to back up and one thing to do. With a vault open it offers no
 * restore — the store is never empty then, and restore only runs onto an empty one.
 */
@Composable
fun BackupScreen(
    restoreMode: Boolean,
    status: BackupStatus,
    /**
     * The destination as the user would name it — "Google Drive · backup.bin" or
     * "SD card › Vault backup". Null with none.
     */
    folderName: String?,
    /** Whether the destination is a cloud app's single file rather than a folder. */
    fileDestination: Boolean,
    /** This vault's files, in bytes — the folder holds one vault, not the store. */
    storeBytes: Long,
    /** A refusal from the last folder chosen, said under the status. */
    problem: String?,
    restoreState: RestoreState,
    /**
     * `backup-to-cloud-apps` D13. Restore mode reached from an open vault list: the backup is
     * one of its vaults, and the list's own key opens it, so no words are asked for.
     */
    restoreIntoList: Boolean = false,
    /**
     * `backup-to-cloud-apps` D1. What this device can back up to, read when the list is
     * shown rather than when the screen is, so an app installed a moment ago is there.
     */
    destinations: () -> List<CloudMirror.Destination>,
    /** A row of the "Back up to" list was chosen. */
    onChooseDestination: (CloudMirror.Destination) -> Unit,
    onBackUpNow: () -> Unit,
    /** Called once the stop dialog is confirmed. */
    onStop: () -> Unit,
    onChooseRestoreFile: () -> Unit,
    onChooseRestoreFolder: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The file chosen with no vault list on the device — `switch-builds-without-losing-vaults`. */
    restoreFile: RestoreFile? = null,
    /** A chosen `.vault`'s passphrase. */
    onOpenVaultFile: (ByteArray) -> Unit = {},
) {
    if (restoreMode) {
        RestoreBody(
            restoreState, restoreIntoList, restoreFile,
            onChooseRestoreFile, onChooseRestoreFolder, onOpenVaultFile, onBack, modifier,
        )
        return
    }

    // Held here rather than in the activity: a lock replaces this screen, and the
    // sheets and the dialog leave with it.
    var turningOn by rememberSaveable { mutableStateOf(false) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var explaining by rememberSaveable { mutableStateOf(false) }
    var stopping by rememberSaveable { mutableStateOf(false) }

    val on = status != BackupStatus.Off

    VaultScaffold(
        title = stringResource(R.string.drawer_backup),
        modifier = modifier,
        onBack = onBack,
    ) {
        Spacer(Modifier.height(Spacing.sm))
        BackupStatusBlock(status, storeBytes, folderName, compact = false)

        problem?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.Error,
                modifier = Modifier.padding(top = Spacing.md),
            )
        }

        Spacer(Modifier.height(Spacing.section))
        // Off asks first — the turn-on sheet. Unreachable while a destination exists is a
        // destination to choose again, and the points were accepted already.
        when (status) {
            BackupStatus.Off -> PrimaryButton(stringResource(R.string.backup_turn_on), true) { turningOn = true }
            is BackupStatus.Unreachable -> PrimaryButton(stringResource(R.string.backup_choose_where), true) { choosing = true }
            else -> PrimaryButton(
                stringResource(R.string.backup_now),
                enabled = status !is BackupStatus.Checking && status !is BackupStatus.Copying,
                onClick = onBackUpNow,
            )
        }
        Text(
            text = backupCaption(on, fileDestination),
            style = MaterialTheme.typography.bodySmall,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm),
        )

        Spacer(Modifier.height(Spacing.section))
        HorizontalDivider(color = VaultColors.OutlineVariant)
        if (on) {
            BackupRow(
                icon = if (fileDestination) R.drawable.ic_cloud_upload else R.drawable.ic_folder,
                label = stringResource(R.string.backup_backed_up_to),
                supporting = folderName ?: "",
                onClick = { choosing = true },
            )
        }
        BackupRow(
            icon = R.drawable.ic_info,
            label = stringResource(R.string.backup_how_it_works),
            supporting = null,
            onClick = { explaining = true },
        )
        HorizontalDivider(color = VaultColors.OutlineVariant)
        if (on) {
            TextButton(
                onClick = { stopping = true },
                modifier = Modifier.padding(top = Spacing.sm),
            ) {
                Text(
                    stringResource(R.string.backup_stop),
                    style = MaterialTheme.typography.labelLarge,
                    color = VaultColors.Error,
                )
            }
        }
        Spacer(Modifier.height(Spacing.section))
    }

    if (turningOn || choosing) {
        BackupTurnOnSheet(
            // Changing where backups go needs the list, not the points again.
            showPoints = turningOn,
            destinations = destinations,
            onChoose = { turningOn = false; choosing = false; onChooseDestination(it) },
            onDismiss = { turningOn = false; choosing = false },
        )
    }
    if (explaining) BackupExplainedSheet(onDismiss = { explaining = false })
    if (stopping) {
        StopBackupDialog(
            onConfirm = { stopping = false; onStop() },
            onDismiss = { stopping = false },
        )
    }
}

/**
 * The line under the primary action — `backup-to-cloud-apps` D10, D11. Every change is
 * copied without a button: a folder within seconds, a cloud app's whole-vault file once the
 * person pauses, and either at once when the vault locks.
 */
@Composable
internal fun backupCaption(on: Boolean, file: Boolean = false): String = stringResource(
    when {
        !on -> R.string.backup_caption_off
        file -> R.string.backup_caption_file
        else -> R.string.backup_caption_folder
    }
)

/**
 * The status, in words and a glyph — shared by this screen, onboarding's backup step
 * and (as a line) the drawer row, so the three cannot disagree.
 *
 * [compact] is onboarding's form: the step's own glyph already heads the screen, so
 * the circle goes and the words sit in a tonal container instead.
 *
 * Headline and supporting line are one node and a polite live region: a screen reader
 * hears "Copying… 4 of 12" advance, and hears the result when the run ends.
 */
@Composable
internal fun BackupStatusBlock(
    status: BackupStatus,
    storeBytes: Long,
    folderName: String?,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val (headline, supporting) = statusWords(status, storeBytes, folderName)
    val glyph = when (status) {
        BackupStatus.Off -> R.drawable.ic_cloud_off
        BackupStatus.Checking, is BackupStatus.Copying -> R.drawable.ic_cloud_sync
        is BackupStatus.UpToDate -> R.drawable.ic_cloud_done
        is BackupStatus.Behind -> R.drawable.ic_cloud_upload
        is BackupStatus.Partial, is BackupStatus.Unreachable, is BackupStatus.NoSpace -> R.drawable.ic_error
    }
    val tint = when (status) {
        BackupStatus.Off -> VaultColors.OnSurfaceVariant
        is BackupStatus.Partial, is BackupStatus.Unreachable, is BackupStatus.NoSpace -> VaultColors.Error
        else -> VaultColors.Primary
    }

    val words = @Composable {
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            Text(
                text = headline,
                style = if (compact) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.headlineSmall,
                color = VaultColors.OnSurface,
            )
            if (supporting.isNotEmpty()) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }
        StatusProgress(status)
    }

    if (compact) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.container))
                .background(VaultColors.SurfaceContainerHigh)
                .padding(Spacing.lg),
        ) { words() }
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(VaultColors.SurfaceContainerHigh),
            ) {
                Icon(
                    painter = painterResource(glyph),
                    // The headline says the state; the glyph repeats it (§6.4).
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.height(Spacing.lg))
            words()
        }
    }
}

@Composable
private fun StatusProgress(status: BackupStatus) {
    when (status) {
        BackupStatus.Checking -> LinearProgressIndicator(
            color = VaultColors.Primary,
            trackColor = VaultColors.SurfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.md),
        )
        is BackupStatus.Copying -> LinearProgressIndicator(
            progress = { if (status.total == 0L) 0f else status.done.toFloat() / status.total },
            color = VaultColors.Primary,
            trackColor = VaultColors.SurfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.md),
        )
        else -> Unit
    }
}

/** The headline and the supporting line for [status] — D4's table. */
@Composable
internal fun statusWords(status: BackupStatus, storeBytes: Long, folderName: String?): Pair<String, String> {
    val size = sizeLabel(storeBytes)
    val words = localWords()
    val copied = { at: Long? -> at?.let { words.get(R.string.backup_last_copied, relative(words, it), size) } ?: size }
    val headline = backupDrawerLine(words, status).first
    return when (status) {
        BackupStatus.Off -> stringResource(R.string.backup_is_off) to stringResource(R.string.backup_only_here, size)
        BackupStatus.Checking -> stringResource(R.string.backup_checking) to (folderName ?: "")
        is BackupStatus.UpToDate -> headline to copied(status.lastCopied)
        is BackupStatus.Behind -> headline to copied(status.lastCopied)
        is BackupStatus.Copying -> headline to
            if (status.bytes) stringResource(R.string.backup_progress, sizeLabel(status.done), sizeLabel(status.total))
            else stringResource(R.string.backup_progress, status.done.toString(), status.total.toString())
        is BackupStatus.Partial -> headline to stringResource(R.string.backup_partial_line)
        is BackupStatus.Unreachable -> headline to stringResource(R.string.backup_unreachable_line)
        is BackupStatus.NoSpace -> headline to stringResource(R.string.backup_no_space_line, sizeLabel(status.short))
    }
}

/**
 * The drawer row's supporting line and whether it is badged — D12. Null [status] is a
 * vault whose folder has not been listed yet this session.
 *
 * Only the two faults are badged. Off is a choice, not a fault.
 */
internal fun backupDrawerLine(words: Words, status: BackupStatus?): Pair<String, Boolean> = when (status) {
    null, BackupStatus.Checking -> words.get(R.string.bio_on) to false
    BackupStatus.Off -> words.get(R.string.bio_off) to false
    is BackupStatus.UpToDate -> words.get(R.string.backup_up_to_date) to false
    is BackupStatus.Behind -> words.plural(R.plurals.backup_changes_to_copy, status.pending, status.pending) to false
    is BackupStatus.Copying -> words.get(R.string.backup_copying) to false
    is BackupStatus.Partial -> words.plural(R.plurals.backup_files_failed, status.failed, status.failed) to true
    is BackupStatus.Unreachable ->
        words.get(if (status.file) R.string.backup_unreachable_file else R.string.backup_unreachable_folder) to true
    is BackupStatus.NoSpace -> words.get(R.string.backup_no_space) to true
}

/**
 * "2 hours ago", or "just now" inside the first minute — `DateUtils` at minute
 * resolution says "0 minutes ago" there. Clamped to now: a provider stamping server
 * time a minute ahead of the device would otherwise read "in 1 minute".
 */
private fun relative(words: Words, at: Long): String {
    val now = System.currentTimeMillis()
    if (now - at < DateUtils.MINUTE_IN_MILLIS) return words.get(R.string.backup_just_now)
    return DateUtils.getRelativeTimeSpanString(
        minOf(at, now), now, DateUtils.MINUTE_IN_MILLIS,
    ).toString().replaceFirstChar { it.lowercase() }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * A row that leads somewhere: glyph, name, its value, and a chevron. The whole row is
 * the target — 56dp with a value, never under 48dp.
 */
@Composable
internal fun BackupRow(icon: Int, label: String, supporting: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (supporting == null) Spacing.touchTarget else 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Spacing.sm),
    ) {
        Icon(
            painter = painterResource(icon),
            // The label carries the meaning; the glyph repeats it.
            contentDescription = null,
            tint = VaultColors.OnSurfaceVariant,
        )
        Spacer(Modifier.width(Spacing.lg))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = VaultColors.OnSurface)
            supporting?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = VaultColors.OnSurfaceVariant)
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = VaultColors.OnSurfaceVariant,
        )
    }
}

/**
 * What the user accepts before anything is copied, then where it goes — `backup-screen`,
 * `backup-to-cloud-apps` D6.
 *
 * [showPoints] is off when backup is already on and the person is only choosing again:
 * they accepted the points once. The list is read here, when the sheet opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupTurnOnSheet(
    showPoints: Boolean = true,
    destinations: () -> List<CloudMirror.Destination>,
    onChoose: (CloudMirror.Destination) -> Unit,
    onDismiss: () -> Unit,
) {
    val available = remember { destinations() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = VaultColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenInset)
                .padding(bottom = Spacing.section),
        ) {
            if (showPoints) {
                Text(
                    text = stringResource(R.string.backup_before_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = VaultColors.OnSurface,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(Spacing.lg))
                TurnOnPoint(
                    R.drawable.ic_vpn_key,
                    stringResource(R.string.backup_before_words),
                    stringResource(R.string.backup_before_words_body),
                )
                TurnOnPoint(
                    R.drawable.ic_visibility,
                    stringResource(R.string.backup_before_provider),
                    stringResource(R.string.backup_before_provider_body),
                )
                TurnOnPoint(
                    R.drawable.ic_folder,
                    stringResource(R.string.backup_before_one_vault),
                    stringResource(R.string.backup_before_one_vault_body),
                )
            }
            Text(
                text = stringResource(R.string.backup_back_up_to),
                style = MaterialTheme.typography.titleMedium,
                color = VaultColors.OnSurface,
                modifier = Modifier
                    .padding(top = if (showPoints) Spacing.sm else 0.dp, bottom = Spacing.sm)
                    .semantics { heading() },
            )
            BackupDestinationList(available, onChoose)
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md),
            ) {
                Text(stringResource(R.string.action_not_now), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * The "Back up to" rows — `backup-to-cloud-apps` D1, D6. A cloud app is drawn with its own
 * launcher icon, because that is how the person recognises it; a removable volume with the
 * folder glyph. Each row says what the destination will get, so the difference between a
 * daily whole-vault file and a folder of changes is known before it is chosen.
 */
@Composable
internal fun BackupDestinationList(
    destinations: List<CloudMirror.Destination>,
    onChoose: (CloudMirror.Destination) -> Unit,
) {
    if (destinations.isEmpty()) {
        Text(
            text = stringResource(R.string.backup_no_destinations),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(vertical = Spacing.sm),
        )
        return
    }
    destinations.forEach { d ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = { onChoose(d) })
                .padding(vertical = Spacing.sm),
        ) {
            val icon = (d as? CloudMirror.Destination.CloudApp)?.icon
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(VaultColors.SurfaceContainerHigh),
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon.asImageBitmap(),
                        // The label beside it names the app.
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                } else {
                    Icon(
                        painter = painterResource(
                            if (d is CloudMirror.Destination.Volume) R.drawable.ic_folder else R.drawable.ic_cloud_upload
                        ),
                        contentDescription = null,
                        tint = VaultColors.OnSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(Spacing.lg))
            Column(Modifier.weight(1f)) {
                Text(d.label, style = MaterialTheme.typography.bodyLarge, color = VaultColors.OnSurface)
                Text(
                    text = when (d) {
                        is CloudMirror.Destination.CloudApp -> stringResource(R.string.backup_dest_cloud)
                        is CloudMirror.Destination.Volume -> stringResource(R.string.backup_dest_volume)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurfaceVariant,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = VaultColors.OnSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TurnOnPoint(icon: Int, heading: String, body: String) {
    Row(modifier = Modifier.padding(bottom = Spacing.lg)) {
        Icon(
            painter = painterResource(icon),
            // The heading says it; the glyph repeats it.
            contentDescription = null,
            tint = VaultColors.OnSurfaceVariant,
        )
        Spacer(Modifier.width(Spacing.lg))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(heading, style = MaterialTheme.typography.titleSmall, color = VaultColors.OnSurface)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = VaultColors.OnSurfaceVariant)
        }
    }
}

/**
 * The whole account, one tap from the screen — D6. What the six blocks used to say,
 * with what the audit found missing from what the provider can see.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupExplainedSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = VaultColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenInset)
                .padding(bottom = Spacing.section),
        ) {
            Text(
                text = stringResource(R.string.backup_how_it_works),
                style = MaterialTheme.typography.titleLarge,
                color = VaultColors.OnSurface,
                modifier = Modifier
                    .padding(bottom = Spacing.lg)
                    .semantics { heading() },
            )
            Explained(
                stringResource(R.string.backup_how_copied),
                stringResource(R.string.backup_how_copied_body),
            )
            Explained(
                stringResource(R.string.backup_how_when),
                stringResource(R.string.backup_how_when_body),
            )
            Explained(
                stringResource(R.string.backup_how_restoring),
                stringResource(R.string.backup_how_restoring_body),
            )
            Explained(
                stringResource(R.string.backup_how_provider),
                stringResource(R.string.backup_how_provider_body),
            )
            Explained(
                stringResource(R.string.backup_how_per_vault),
                stringResource(R.string.backup_how_per_vault_body),
            )
            Explained(
                stringResource(R.string.backup_how_destinations),
                stringResource(R.string.backup_how_destinations_body),
            )
            Explained(
                stringResource(R.string.backup_how_deleting),
                stringResource(R.string.backup_how_deleting_body),
            )
        }
    }
}

@Composable
private fun Explained(heading: String, body: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier.padding(bottom = Spacing.lg),
    ) {
        Text(
            text = heading,
            style = MaterialTheme.typography.titleSmall,
            color = VaultColors.OnSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(body, style = MaterialTheme.typography.bodyMedium, color = VaultColors.OnSurfaceVariant)
    }
}

/** D7 — confirms once (§6.7). Stopping destroys nothing, so the confirm is not red. */
@Composable
private fun StopBackupDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        shape = RoundedCornerShape(Radius.container),
        containerColor = VaultColors.SurfaceContainerHigh,
        titleContentColor = VaultColors.OnSurface,
        textContentColor = VaultColors.OnSurfaceVariant,
        title = { Text(stringResource(R.string.backup_stop_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Text(
                stringResource(R.string.backup_stop_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.notification_stop), style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.backup_keep), style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}

/**
 * Restore from a backup — D8. What a replacement device sees, and nothing about
 * backing up: there is no vault here to back up.
 */
@Composable
private fun RestoreBody(
    state: RestoreState,
    intoList: Boolean,
    file: RestoreFile?,
    onChooseFile: () -> Unit,
    onChooseFolder: () -> Unit,
    onOpenVaultFile: (ByteArray) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    NoClipboard {
        VaultScaffold(
            title = stringResource(R.string.setup_restore_backup),
            modifier = modifier,
            onBack = onBack,
        ) {
            Spacer(Modifier.height(Spacing.sm))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(VaultColors.SurfaceContainerHigh),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_history),
                    // The bar's title names the screen; the glyph repeats it.
                    contentDescription = null,
                    tint = VaultColors.Primary,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = if (intoList) {
                    stringResource(R.string.restore_into_list)
                } else {
                    stringResource(R.string.restore_lead)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = VaultColors.OnSurface,
            )

            Spacer(Modifier.height(Spacing.section))
            val copying = state is RestoreState.Copying
            if (file == null) {
                PrimaryButton(
                    // With no list a `.vault` comes in here too, so the action names no kind (D1).
                    stringResource(if (intoList) R.string.restore_choose_file else R.string.import_choose_file),
                    enabled = !copying,
                    onClick = onChooseFile,
                )
                TextButton(
                    onClick = onChooseFolder,
                    enabled = !copying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.sm),
                ) {
                    Text(stringResource(R.string.backup_on_sd_card), style = MaterialTheme.typography.labelLarge)
                }
            } else {
                VaultFileState(file, copying, onChooseFile, onOpenVaultFile)
            }

            when (state) {
                is RestoreState.Copying -> Column(
                    modifier = Modifier
                        .padding(top = Spacing.lg)
                        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                ) {
                    LinearProgressIndicator(
                        progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                        color = VaultColors.Primary,
                        trackColor = VaultColors.SurfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.restore_copying, state.done, state.total),
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColors.OnSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
                is RestoreState.Problem -> Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.Error,
                    modifier = Modifier.padding(top = Spacing.lg),
                )
                RestoreState.Idle -> Unit
            }
        }
    }
}

/**
 * D3. The chosen file in place of the two actions — the import screen's row, tappable to choose
 * again — and, once it reads as a `.vault`, its passphrase and "Open file". The field is emptied
 * as it is submitted, so a refusal lands on an empty field with the row kept, ready to retype.
 */
@Composable
private fun VaultFileState(
    file: RestoreFile,
    copying: Boolean,
    onChooseFile: () -> Unit,
    onOpen: (ByteArray) -> Unit,
) {
    var passphrase by remember(file.name) { mutableStateOf("") }

    fun submit() {
        if (copying || passphrase.isEmpty()) return
        val bytes = passphrase.toByteArray(Charsets.UTF_8)
        passphrase = ""
        onOpen(bytes)
    }

    ChosenBackupRow(
        name = file.name,
        kind = if (file.vault) stringResource(R.string.import_vault_file) else null,
        folder = false,
        enabled = !copying,
        onClick = onChooseFile,
    )
    if (!file.vault) {
        // Reading the file's head — a cloud app may have to fetch it first.
        LinearProgressIndicator(
            color = VaultColors.Primary,
            trackColor = VaultColors.SurfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.md),
        )
        return
    }
    PassphraseField(
        value = passphrase,
        onValueChange = { passphrase = it },
        label = stringResource(R.string.import_passphrase_label),
        imeAction = ImeAction.Done,
        enabled = !copying,
        isError = file.problem != null,
        supporting = file.problem,
        announce = file.problem != null,
        onImeAction = ::submit,
        modifier = Modifier.padding(top = Spacing.lg),
    )
    Button(
        onClick = ::submit,
        enabled = !copying && passphrase.isNotEmpty(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg),
    ) {
        Text(stringResource(R.string.restore_open_file), style = MaterialTheme.typography.labelLarge)
    }
}
