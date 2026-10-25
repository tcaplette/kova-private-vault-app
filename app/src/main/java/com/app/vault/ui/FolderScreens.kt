package com.app.vault.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import com.app.vault.FolderColour
import com.app.vault.R
import com.app.vault.ThumbRef
import com.app.vault.VaultFolder
import com.app.vault.VaultFolders
import com.app.vault.VaultItem
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * Naming a folder — `folder-system`. Creating and renaming are the same sheet.
 *
 * ***It was a screen*** and became a sheet with [RenameSheet], whose reasons hold
 * here unchanged: one field is not a destination, a sheet is neither a dialog nor a
 * screen, and the content lifts above the keyboard rather than sitting under it. The
 * grid stays visible behind, which for a creation is the folder list being added to.
 *
 * **The refusal is in place and not a toast.** A name is refused for one of two
 * reasons — it is blank or too long, or a sibling already has it — and both are things
 * the user fixes by editing the field they are looking at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderNameSheet(
    /** The heading says which act this is: creating one, or renaming one. */
    creating: Boolean,
    /** What the field starts with — the current name when renaming, empty when not. */
    initial: String,
    /** Set when the last attempt was refused, and cleared by the caller on success. */
    problem: String?,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var typed by remember { mutableStateOf(initial) }

    ModalBottomSheet(
        onDismissRequest = onCancel,
        modifier = modifier,
        containerColor = VaultColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .imePadding()
                // **Scrollable, and that is not decoration.** With the keyboard up the
                // content lifts, and on a short screen the confirming button lifts
                // past the top of the window — unreachable by a thumb, and a tap aimed
                // at it lands on the scrim and dismisses the sheet instead. A test
                // caught it doing exactly that: typing a folder name, pressing Create,
                // and getting no folder and no error.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenInset)
                .padding(bottom = Spacing.section),
        ) {
            Text(
                text = stringResource(if (creating) R.string.folder_new else R.string.folder_rename),
                style = MaterialTheme.typography.titleLarge,
                color = VaultColors.OnSurface,
            )

            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                label = { Text(stringResource(R.string.vault_name_label)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md),
            )

            if (problem != null) {
                Text(
                    text = problem,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.Error,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }

            Button(
                onClick = { onConfirm(typed) },
                enabled = VaultFolders.normalise(typed) != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.lg),
            ) {
                Text(
                    text = stringResource(if (creating) R.string.folder_create else R.string.rename),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * Everything a folder can have done to it — `folder-system`. Raised by long-pressing
 * its tile.
 *
 * ***It was a screen.*** `folder-system` gave the folder its own `VaultScaffold`, so
 * annotating a folder cost a push and a pop around four rows. A screen says the user
 * has gone somewhere; here they have not — they are still looking at the grid and
 * pointing at one tile in it. Every Android file app offering this — Drive, Dropbox,
 * My Files, OneDrive — raises a sheet over the list, for that reason.
 *
 * **A sheet is not the app's second dialog** and §6.7 is untouched. A dialog asks a
 * question and blocks until it is answered; this offers a set of actions and dismisses
 * on a drag or a tap outside, having done nothing.
 *
 * **A sheet and not the grid's dropdown.** The two divide by what they hold: a
 * dropdown is *one value in N states*, anchored to the control displaying it — the
 * sort control's ordering; a sheet is *N different actions on one object*, and has no
 * control to anchor to, because the object is a tile anywhere on the screen.
 *
 * **Colour is chosen here and not through another surface.** It is one value in six
 * and all six fit on a row of swatches, so it needs nothing behind it. The three that
 * stay rows do: move needs the folder tree, cover needs the folder's thumbnails,
 * rename needs a keyboard.
 *
 * **Delete is last and outlined, not primary.** §6.1 allows one primary-coloured
 * action per surface and nothing here earns it: this is a set of equals. It is not
 * styled as destructive either, because it is not — the folder's contents move up to
 * where the folder was, and the confirmation says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSheet(
    folder: VaultFolder,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onColour: (FolderColour) -> Unit,
    onCover: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = VaultColors.Surface,
    ) {
        // The folder being acted on, named — the sheet covers the grid, and a set of
        // verbs with no subject is a set of verbs about whatever the user remembers
        // touching. This is the title the screen carried in its bar.
        Text(
            text = folder.name,
            style = MaterialTheme.typography.titleLarge,
            color = VaultColors.OnSurface,
            modifier = Modifier.padding(
                start = Spacing.screenInset,
                end = Spacing.screenInset,
                bottom = Spacing.sm,
            ),
        )

        FolderRow(stringResource(R.string.rename), R.drawable.ic_edit, onRename)
        FolderRow(stringResource(R.string.folder_move), R.drawable.ic_drive_file_move, onMove)
        FolderRow(stringResource(R.string.folder_cover), R.drawable.ic_image, onCover)

        ColourRow(current = folder.colour, onChoose = onColour)

        OutlinedButton(
            onClick = onDelete,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = Spacing.screenInset,
                    end = Spacing.screenInset,
                    top = Spacing.section,
                    bottom = Spacing.section,
                ),
        ) {
            Text(stringResource(R.string.folder_delete), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * The six folder colours, in place — see [FolderSheet].
 *
 * **The current one carries a tick and not only a tint**, §6.4: on a row whose entire
 * content is colour, a selection shown by colour alone is shown by nothing.
 *
 * **Each swatch names its colour to a screen reader**, the same rule read the other
 * way — a name is the only form of this a screen reader can convey at all. "No colour"
 * is drawn from the same palette call as the rest, so the row is six entries and not
 * five and a gap.
 */
@Composable
internal fun ColourRow(current: FolderColour, onChoose: (FolderColour) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenInset, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FolderColour.entries.forEach { colour ->
            val colourName = stringResource(colour.label)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(Spacing.touchTarget)
                    .background(VaultColors.folder(colour), CircleShape)
                    .clickable { onChoose(colour) }
                    .semantics {
                        contentDescription = colourName
                        selected = colour == current
                    },
            ) {
                if (colour == current) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check_circle),
                        // The swatch's own semantics say which colour this is and
                        // that it is selected; this glyph would say it twice.
                        contentDescription = null,
                        tint = VaultColors.OnSurface,
                    )
                }
            }
        }
    }
}

/**
 * What deleting a folder will do, before it does it — `folder-system`.
 *
 * **A screen and not a dialog**, and the distinction matters here more than anywhere:
 * §6.7's one dialog is the item delete, which destroys something. This destroys
 * nothing, and dressing it in the same chrome would say it does.
 *
 * The sentence names where the contents will land, because that is the fact the user
 * needs and cannot see from the grid.
 */
@Composable
fun FolderDeleteScreen(
    folder: VaultFolder,
    /** The folder that will inherit the contents, or null for the home screen. */
    parentName: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    VaultScaffold(title = stringResource(R.string.folder_delete), modifier = modifier, onBack = onCancel) {
        Text(
            text = stringResource(R.string.folder_delete_named, folder.name),
            style = MaterialTheme.typography.titleMedium,
            color = VaultColors.OnSurface,
            modifier = Modifier.padding(top = Spacing.md),
        )
        Text(
            text = if (parentName != null) {
                stringResource(R.string.folder_delete_moves_to, parentName)
            } else {
                stringResource(R.string.folder_delete_moves_home)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm),
        )

        Button(
            onClick = onConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.lg),
        ) {
            Text(stringResource(R.string.folder_delete), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * A folder's cover — `folder-system`.
 *
 * The folder's own items, the current cover marked, and a way to go back to the
 * fallback. **Its own items only**: a cover is a picture of what is in this folder,
 * and offering the whole vault would let a folder show something it does not contain.
 */
@Composable
fun FolderCoverScreen(
    folder: VaultFolder,
    items: List<VaultItem>,
    thumbnailFor: (ThumbRef) -> android.graphics.Bitmap?,
    onChoose: (VaultItem?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chosen = folder.coverItemId?.let { com.app.vault.hex(it) }

    VaultScaffold(title = stringResource(R.string.folder_cover), modifier = modifier, onBack = onBack, scrollable = false) {
        OutlinedButton(
            onClick = { onChoose(null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenInset, vertical = Spacing.md),
        ) {
            Text(stringResource(R.string.folder_cover_recent), style = MaterialTheme.typography.labelLarge)
        }

        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.folder_cover_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.screenInset),
            )
            return@VaultScaffold
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(Spacing.gridGutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.gridGutter),
        ) {
            items(items, key = { it.itemId }) { item ->
                val bitmap = item.thumb?.let(thumbnailFor)
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .background(VaultColors.SurfaceContainer)
                        .clickable { onChoose(item) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = item.filename,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            text = item.filename,
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColors.OnSurfaceVariant,
                            modifier = Modifier.padding(Spacing.sm),
                        )
                    }
                    if (item.itemId == chosen) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check_circle),
                            contentDescription = stringResource(R.string.folder_cover_current),
                            tint = VaultColors.OnSurface,
                            modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.xs),
                        )
                    }
                }
            }
        }
    }
}

/** One row on [FolderSheet]. `SettingsRow`'s shape, one surface along. */
@Composable
private fun FolderRow(label: String, icon: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingContent = {
            Icon(
                painter = painterResource(icon),
                // The label carries the meaning; the glyph repeats it.
                contentDescription = null,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = VaultColors.Surface,
            headlineColor = VaultColors.OnSurface,
            leadingIconColor = VaultColors.OnSurfaceVariant,
        ),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
