package com.app.vault.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.app.vault.R
import com.app.vault.ui.theme.VaultColors

/**
 * The viewer's overflow — `viewer-chrome-and-editor`.
 *
 * ***P2-7 deleted an overflow and this is not it.*** That one hid the grid's most
 * reached actions — add and lock — behind a glyph naming neither. This holds the two
 * *occasional* acts on the item, while everything a user reaches for is on the face
 * of the rows beside it. The glyph came back for that, and for nothing else:
 * `ScreenChromeTest` still fails a menu on the grid's header.
 *
 * **Not the grid's dropdown idiom either.** The sort control is one value in six
 * states, anchored to the control that displays it. This is two different acts on one
 * object, which is the folder sheet's shape — but a sheet cannot be anchored to a
 * control, and here there is one to anchor to.
 *
 * A row is absent rather than disabled when its act does not apply.
 *
 * **`player-controls-and-menu` gave it three more, and only on an item that plays.**
 * A player needs the bottom edge for its scrubber, so rename, share and delete come
 * here rather than competing for it. They stay on the row for a still image, where
 * the edge is free — the argument above is about hiding acts a user *reaches* for,
 * and on a screen whose primary act is watching, an act on the item is occasional.
 *
 * **The order is placement, then the item, then destruction.** Delete is last and
 * behind a divider: P2-10's rule that the destructive control does not sit where a
 * finger reaching for something else lands, carried from a row into a list.
 *
 * **`slideshow` puts *Slideshow* first**, on a photo or video in Media only: it is about
 * looking at the list, and every row below it is about organising the item.
 *
 * **`video-frame-thumbnail` puts *Use this frame as the thumbnail* first among the
 * item's acts** — how it looks, then what it is called, then where it goes — and only
 * on a settled video, where there is a frame on screen to take.
 */
@Composable
internal fun ViewerMenu(
    /** `slideshow`. First: it is the one act here that is about looking, not organising. */
    onSlideshow: (() -> Unit)? = null,
    onSetCover: (() -> Unit)?,
    onMoveToFolder: (() -> Unit)?,
    onUseFrame: (() -> Unit)? = null,
    /** `intruder-captures-at-home`. A capture's move into a vault on the keyring; absent when null. */
    onMoveToVault: (() -> Unit)? = null,
    /** `intruder-captures-at-home`. A capture's export, on a playing item where the bottom row is gone. */
    onExport: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }

    Box {
        ViewerAction(R.drawable.ic_more_vert, stringResource(R.string.more)) { open = true }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = VaultColors.Surface,
        ) {
            if (onSlideshow != null) {
                Item(stringResource(R.string.slideshow), onSlideshow) { open = false }
            }
            if (onSetCover != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.viewer_set_folder_cover),
                            style = MaterialTheme.typography.bodyLarge,
                            color = VaultColors.OnSurface,
                        )
                    },
                    onClick = {
                        open = false
                        onSetCover()
                    },
                )
            }
            if (onMoveToFolder != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.move_to_folder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = VaultColors.OnSurface,
                        )
                    },
                    onClick = {
                        open = false
                        onMoveToFolder()
                    },
                )
            }
            if (onUseFrame != null) {
                Item(stringResource(R.string.viewer_use_frame), onUseFrame) { open = false }
            }
            if (onMoveToVault != null) {
                Item(stringResource(R.string.intruder_move_to_vault), onMoveToVault) { open = false }
            }
            if (onExport != null) {
                Item(stringResource(R.string.intruder_export), onExport) { open = false }
            }
            if (onRename != null) {
                Item(stringResource(R.string.rename), onRename) { open = false }
            }
            if (onShare != null) {
                Item(stringResource(R.string.share_securely), onShare) { open = false }
            }
            if (onDelete != null) {
                // Set apart, not merely last: a divider is what stops a finger
                // travelling down the list from arriving on it by momentum.
                HorizontalDivider(color = VaultColors.OnSurfaceVariant.copy(alpha = 0.2f))
                Item(stringResource(R.string.delete), onDelete) { open = false }
            }
        }
    }
}

/** One act in the menu. The two original rows predate it and are left as they are. */
@Composable
private fun Item(label: String, act: () -> Unit, close: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = VaultColors.OnSurface,
            )
        },
        onClick = {
            close()
            act()
        },
    )
}
