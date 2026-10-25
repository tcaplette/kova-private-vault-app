package com.app.vault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.app.vault.R
import com.app.vault.VaultFolder
import com.app.vault.VaultFolders
import com.app.vault.hex
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * Where something goes — `folder-system`. **One picker, two questions.**
 *
 * ***It was `AlbumScreen`.*** That screen offered a flat list of album names and a
 * text field that made a new one by putting items in it, because that was the only
 * way an album could come into being. Folders nest and exist on their own now, so a
 * flat list cannot express the destination and the text field has moved to
 * [FolderNameScreen], which creates one properly.
 *
 * **It walks rather than listing.** The picker shows one level at a time — the folders
 * inside where you are standing, with a way up — because a tree drawn flat with
 * indentation is a control that gets unreadable at exactly the depth nesting exists
 * for. The same walk the grid does, one surface along.
 *
 * **A folder being moved is not offered as a destination, and neither is anything
 * inside it.** `VaultFolders.reparent` refuses that move underneath; hiding it here as
 * well means the user is never offered something the app will then refuse.
 *
 * **A screen and not a dialog**, `AlbumScreen`'s reason before it: §6.7 keeps the item
 * delete confirmation as this app's only dialog, and this is a choice among an
 * unbounded list rather than a confirmation.
 *
 * Back cancels and keeps the selection — the user said no to the destination, not to
 * the twelve taps that built the set.
 */
@Composable
fun FolderPickerScreen(
    /**
     * What is moving: a count of items, or the folder being moved. The title says
     * which, and the list does not repeat it.
     */
    count: Int,
    movingFolder: VaultFolder?,
    folders: List<VaultFolder>,
    /** Where the thing being moved is now, marked so the user need not re-derive it. */
    currentFolderId: ByteArray?,
    /** The destination. Null is the home screen — the same commit either way. */
    onMove: (ByteArray?) -> Unit,
    /** Set when a move was refused — a name collision, or a move into a folder's own subtree. */
    problem: String?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The items span more than one destination, so no folder accepts them. Said
     * under the lead, because an empty list with no reason reads as broken.
     */
    mixedKinds: Boolean = false,
    /**
     * `intruder-captures-at-home` D12. A move is being written — the captures are decrypted and
     * sealed into another vault, which takes a moment. Every move control is disabled, so a second
     * tap cannot start a second move, and a bar says the work is under way.
     */
    busy: Boolean = false,
    /**
     * `intruder-captures-at-home`. The name of the vault whose top level the picker's root is —
     * set when captures move into a vault from the home screen, where "Home screen" would read as
     * the vault list. Null inside a vault, where the root is that vault's home screen.
     */
    rootName: String? = null,
) {
    // Where the picker is standing, which is not where the items are: the user walks
    // this independently of the grid behind, and starts at the top so the whole tree
    // is one walk away.
    var at by remember { mutableStateOf<ByteArray?>(null) }

    // Never a destination: a folder cannot go inside itself or inside anything it
    // holds. Empty when a selection is moving, which can go anywhere.
    //
    // Held as hex rather than as the key type `VaultFolders.subtree` returns: this is
    // a handful of folders on one screen, not a per-item path, and hex is what the
    // rows already compare by.
    val barred: Set<String> = remember(folders, movingFolder) {
        movingFolder
            ?.let { moving -> VaultFolders.subtreeIds(folders, moving.folderId) }
            ?: emptySet()
    }
    val here = at?.let { id -> folders.firstOrNull { it.id == hex(id) } }
    val offered = remember(folders, at, barred) {
        folders
            .filter { it.parentId?.let { p -> hex(p) } == at?.let { a -> hex(a) } }
            .filter { it.id !in barred }
            .sortedBy { it.name }
    }

    VaultScaffold(
        title = when {
            movingFolder != null -> stringResource(R.string.picker_move_folder, movingFolder.name)
            else -> pluralStringResource(R.plurals.picker_move_items, count, count)
        },
        modifier = modifier,
        onBack = onCancel,
    ) {
        Text(
            text = if (movingFolder != null) {
                stringResource(R.string.picker_folder_lead)
            } else {
                stringResource(R.string.picker_items_lead)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm, bottom = if (mixedKinds) Spacing.sm else Spacing.md),
        )

        // Guidance, not an error: it continues the lead in the lead's voice, and the
        // pair keeps the lead's gap below it.
        if (mixedKinds) {
            Text(
                text = stringResource(R.string.picker_mixed_kinds),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.md),
            )
        }

        Text(
            text = here?.name ?: rootName ?: stringResource(R.string.picker_home),
            style = MaterialTheme.typography.titleMedium,
            color = VaultColors.OnSurface,
        )

        if (busy) {
            LinearProgressIndicator(
                color = VaultColors.Primary,
                trackColor = VaultColors.SurfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm),
            )
        }

        if (problem != null) {
            Text(
                text = problem,
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.Error,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }

        // Up before the children, in the leading position back would occupy. Absent
        // at the top, where there is nowhere to climb to.
        if (here != null) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.picker_up), style = MaterialTheme.typography.bodyLarge) },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = null,
                    )
                },
                colors = rowColours(),
                modifier = Modifier.clickable { at = here.parentId },
            )
        }

        offered.forEach { folder ->
            ListItem(
                headlineContent = {
                    Text(folder.name, style = MaterialTheme.typography.bodyLarge)
                },
                supportingContent = if (folder.id == currentFolderId?.let { hex(it) }) {
                    // Said in words, because §6.4 keeps meaning off styling alone.
                    { Text(stringResource(R.string.picker_where_now), style = MaterialTheme.typography.bodyMedium) }
                } else {
                    null
                },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_folder),
                        contentDescription = null,
                    )
                },
                trailingContent = {
                    // Walking in and choosing are different acts on the same row, so
                    // they are different targets: the row moves into the folder, the
                    // control moves the thing there.
                    OutlinedButton(onClick = { onMove(folder.folderId) }, enabled = !busy) {
                        Text(stringResource(R.string.picker_move_here), style = MaterialTheme.typography.labelLarge)
                    }
                },
                colors = rowColours(),
                modifier = Modifier.clickable { at = folder.folderId },
            )
        }

        // The destination the picker is standing in, including the home screen, which
        // is what "out of any folder" means now.
        OutlinedButton(
            onClick = { onMove(at) },
            enabled = !busy && at?.let { hex(it) } !in barred,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.lg),
        ) {
            Text(
                text = when {
                    here != null -> stringResource(R.string.picker_move_into, here.name)
                    rootName != null -> stringResource(R.string.picker_move_into, rootName)
                    else -> stringResource(R.string.picker_move_home)
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun rowColours() = ListItemDefaults.colors(
    containerColor = VaultColors.Surface,
    headlineColor = VaultColors.OnSurface,
    supportingColor = VaultColors.OnSurfaceVariant,
    leadingIconColor = VaultColors.OnSurfaceVariant,
)
