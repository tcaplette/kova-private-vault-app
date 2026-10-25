package com.app.vault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import com.app.vault.FolderColour
import com.app.vault.FolderView
import com.app.vault.ThumbRef
import com.app.vault.R
import com.app.vault.VaultItem
import com.app.vault.VaultKind
import com.app.vault.VaultNote
import com.app.vault.ui.theme.Radius
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import com.app.vault.ui.theme.VaultMono
import java.text.DateFormat
import java.util.Date

/**
 * How Files, Notes and Memos draw an item — `vault-destinations`.
 *
 * ***The half of the change that fixes what was actually wrong.*** Splitting the
 * navigation and keeping one mosaic behind all four destinations would have moved the mess
 * into four rooms. A document has no picture, so its tile was a rectangle with a filename
 * in it; a recording's tile was a glyph and a duration in a square sized for a photograph.
 * Neither was a designed result — each was the mosaic apologising for an item it was not
 * built for.
 *
 * **Three presentations, one skeleton.** [DestinationRow] is the shared body: a leading
 * mark or swatch, a title line, a second line of that kind's own metadata, and the tile's
 * exact gesture pair. The three public composables differ in what goes on the second line
 * and nothing else, which is what keeps them from drifting apart the way two copies of the
 * mosaic's video-extension set once did.
 *
 * **The gesture is the tile's, unchanged.** Tap opens, or toggles while a selection runs;
 * long-press begins a selection; a tap never does both. Selection, move, rename, delete,
 * export and the trash behave identically here and in the mosaic — what differs between
 * destinations is the shape of a row, never what a row can do.
 *
 * **The date is small, muted and second.** The section headings above still carry the date
 * the list is ordered by, so this is file metadata on a file row and not a restatement of
 * the heading — different size, different weight, different colour. Under a name ordering
 * there are no headings and this is the only date on screen, which is the same bargain the
 * mosaic already makes.
 */
private val rowDate: DateFormat
    @Composable get() = DateFormat.getDateInstance(
        DateFormat.SHORT,
        LocalConfiguration.current.locales[0],
    )

/**
 * A document — `vault-destinations`.
 *
 * Kind glyph, name, size, date. [VaultKind.FILE] means *bytes this app cannot interpret*,
 * so the row says the three things that are true about it without opening it, and offers
 * the export that is the only thing it can do.
 */
@Composable
fun FileRow(
    item: VaultItem,
    onOpen: (VaultItem) -> Unit,
    selected: Boolean,
    selecting: Boolean,
    onBeginSelection: (VaultItem) -> Unit,
    onToggleSelection: (VaultItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    DestinationRow(
        item = item,
        icon = R.drawable.ic_description,
        detail = listOfNotNull(sizeLabel(item.plaintextLength), dateLabel(item)),
        onOpen = onOpen,
        selected = selected,
        selecting = selecting,
        onBeginSelection = onBeginSelection,
        onToggleSelection = onToggleSelection,
        modifier = modifier,
    )
}

/**
 * A recording — `vault-destinations`.
 *
 * Audio glyph, name, length, date. **Where the length is not known no length is drawn** and
 * no placeholder stands in for it — `vault-grid`'s rule for a video's duration, which is
 * the same rule about the same absence. [durationLabel] returns null and the row simply
 * has one thing fewer on its second line.
 *
 * The length renders in the monospace face with tabular figures, so a column of them does
 * not shift as the digits change.
 */
@Composable
fun MemoRow(
    item: VaultItem,
    onOpen: (VaultItem) -> Unit,
    selected: Boolean,
    selecting: Boolean,
    onBeginSelection: (VaultItem) -> Unit,
    onToggleSelection: (VaultItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    DestinationRow(
        item = item,
        icon = R.drawable.ic_audiotrack,
        detail = listOfNotNull(dateLabel(item)),
        // Separated from the rest of the second line because it is the one value that
        // must not reflow: see [DestinationRow].
        monospaceDetail = durationLabel(item.durationMs),
        onOpen = onOpen,
        selected = selected,
        selecting = selecting,
        onBeginSelection = onBeginSelection,
        onToggleSelection = onToggleSelection,
        modifier = modifier,
    )
}

/**
 * A note — `vault-destinations`.
 *
 * ***A card, because a note's content is the only thing that identifies it.*** A filename
 * is not what a note is called; the first line of it is. The excerpt comes from the index,
 * so drawing a screen of these reads no blob and unwraps no key — the same property the
 * mosaic's note tile already relied on.
 *
 * The folder colour is the note's own, carried as a swatch rather than as the whole
 * background: over a full-bleed fill a preview line needs a contrast pair per colour per
 * scheme, and `ui-design-direction` puts the floor where that argument ends.
 */
@Composable
fun NoteCard(
    item: VaultItem,
    onOpen: (VaultItem) -> Unit,
    selected: Boolean,
    selecting: Boolean,
    onBeginSelection: (VaultItem) -> Unit,
    onToggleSelection: (VaultItem) -> Unit,
    /** The note's own sheet — pin, move, colour, delete. Null in the trash. */
    onOptions: ((VaultItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    DestinationRow(
        item = item,
        icon = R.drawable.ic_sticky_note_2,
        swatch = VaultColors.folder(item.colour),
        // The body's first line, from the index. Empty for a note with nothing in it yet,
        // in which case the row carries the date alone rather than an empty second line.
        title = VaultNote.titleOf(item),
        detail = listOfNotNull(item.preview?.takeIf { it.isNotBlank() }, dateLabel(item)),
        pinned = item.pinned,
        // `notes-as-documents`. The three-dot mark and not a long-press: a long-press on
        // an item has begun a selection since P2-11, so the note's own surface needs a
        // target of its own. Withheld in the trash, where the caller passes null.
        onOptions = onOptions,
        onOpen = onOpen,
        selected = selected,
        selecting = selecting,
        onBeginSelection = onBeginSelection,
        onToggleSelection = onToggleSelection,
        modifier = modifier,
    )
}

/**
 * The shared body of the three — see the note at the top of this file.
 *
 * [monospaceDetail] is separated from [detail] for one reason: a duration must render in
 * the monospace face with tabular figures so a column of them does not shift as the digits
 * change, and mixing faces inside one `Text` would mean an `AnnotatedString` built per row
 * per frame to say one number.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DestinationRow(
    item: VaultItem,
    icon: Int,
    detail: List<String>,
    /** What the row is called. A filename for a stored file; a note's first line for a note. */
    title: String = item.filename,
    onOpen: (VaultItem) -> Unit,
    selected: Boolean,
    selecting: Boolean,
    onBeginSelection: (VaultItem) -> Unit,
    onToggleSelection: (VaultItem) -> Unit,
    monospaceDetail: String? = null,
    /**
     * The item's own picture, where it has one — `destination-view-modes`.
     *
     * Only Media passes it. Drawn in the leading slot **instead of** [icon], at the same size,
     * so a row of photographs and a row of documents have their text on the same left edge.
     * Null falls back to the glyph rather than to a blank square: a thumbnail that has not
     * been generated yet is not a row that failed, and the two must not look identical.
     */
    thumbnail: android.graphics.Bitmap? = null,
    swatch: androidx.compose.ui.graphics.Color? = null,
    pinned: Boolean = false,
    onOptions: ((VaultItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = modifier
            .fillMaxWidth()
            // A row is a touch target for its whole width. `Spacing.touchTarget` as a
            // floor and not a fixed height: a long name wraps to two lines and the row
            // grows, rather than the name being cut to keep a number honest.
            .heightIn(min = Spacing.touchTarget)
            .background(
                if (selected) VaultColors.Secondary else VaultColors.Surface
            )
            // MosaicTile's gesture pair, verbatim. A tap never does both.
            .combinedClickable(
                onClick = { if (selecting) onToggleSelection(item) else onOpen(item) },
                onLongClick = { if (!selecting) onBeginSelection(item) },
                onLongClickLabel = stringResource(R.string.select),
            )
            .semantics { if (selected) this.selected = true }
            .padding(horizontal = Spacing.screenInset, vertical = Spacing.md),
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                // The name beside it identifies the item; this is the item's own picture,
                // not a second label for it — the call `MosaicTile` makes for its cover.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(Spacing.xxxl)
                    .clip(RoundedCornerShape(Radius.control))
                    // The stored turn, applied at draw as the tile does it: the thumbnail
                    // on disk is unrotated and regenerating it would spend a blob write to
                    // change a number the index already holds.
                    .graphicsLayer { rotationZ = item.rotation.toFloat() },
            )
        } else if (swatch != null) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(Spacing.xl)
                    .clip(RoundedCornerShape(Radius.control))
                    .background(swatch),
            )
        } else {
            Icon(
                painter = painterResource(icon),
                // Null: the name beside it identifies the item, and the destination
                // already said what kind it is. A screen reader saying "document,
                // document" is the glyph naming itself twice.
                contentDescription = null,
                tint = VaultColors.OnSurfaceVariant,
            )
        }

        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                if (pinned) {
                    Icon(
                        painter = painterResource(R.drawable.ic_push_pin),
                        contentDescription = stringResource(R.string.pinned),
                        tint = VaultColors.OnSurfaceVariant,
                        modifier = Modifier.size(Spacing.lg),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = VaultColors.OnSurface,
                    // Two lines and then ellipsis: wrapping is preferred to truncating,
                    // but a row is not a paragraph.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (detail.isNotEmpty() || monospaceDetail != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    if (monospaceDetail != null) {
                        Text(
                            text = monospaceDetail,
                            // `VaultMono.bodyMedium` — the exact style the mosaic's
                            // duration label uses, so one number does not render two ways
                            // one destination apart.
                            style = VaultMono.bodyMedium,
                            color = VaultColors.OnSurfaceVariant,
                        )
                    }
                    if (detail.isNotEmpty()) {
                        Text(
                            text = detail.joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = VaultColors.OnSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (onOptions != null && !selecting) {
            IconButton(
                onClick = { onOptions(item) },
                modifier = Modifier.size(Spacing.touchTarget),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.vault_row_options, item.filename),
                    tint = VaultColors.OnSurfaceVariant,
                )
            }
        }
    }
}

/** The row's date, small and muted — see the note at the top of this file. */
@Composable
private fun dateLabel(item: VaultItem): String =
    rowDate.format(Date(item.createdAt ?: item.importedAt))

/**
 * A folder, in a list — `destination-view-modes`.
 *
 * ***This is what the change was asked for.*** A folder was a square colour block with its
 * name beneath, in every destination, because the mosaic was the only thing that had ever
 * drawn one — so in Notes it read as a coloured card sitting among notes rather than as a way
 * into somewhere. Dropbox, Drive and Files all answer it identically and the answer is not a
 * different tile: **a folder in a list is a row.**
 *
 * Glyph leading, name beside it **vertically centred against the glyph**, count trailing.
 *
 * **The colour tints the glyph and never fills the row.** A filled row reads as content, and
 * a folder is a route to content, not content. It also dodges the contrast problem
 * `FolderTile` had to solve by putting its name *below* the fill: a label over a colour needs
 * a contrast pair per colour per scheme, and the tint needs none.
 *
 * The count keeps the monospace face with tabular figures that the tile already gives it, for
 * that file's stated reason: a count that reflows the row as it changes is a row that moves
 * while it is being read.
 *
 * **The gesture is the tile's, exactly.** Tap enters, long-press opens the folder's own
 * surface, same label. A gesture that changed with the view would be the view teaching two
 * habits for one folder.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderListRow(
    folder: FolderView,
    onOpen: () -> Unit,
    onSurface: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onSurface,
                onLongClickLabel = stringResource(R.string.folder_options),
            )
            .padding(horizontal = Spacing.screenInset, vertical = Spacing.md),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_folder),
            // Null: the name beside it is what identifies this folder, and every row in
            // this region is a folder. A screen reader saying "folder, Trips" says the
            // obvious half twice.
            contentDescription = null,
            // ***`None` is the default and it cannot be a tint.***
            //
            // `VaultColors.folder` maps [FolderColour.None] to `SurfaceContainerHigh`, which
            // is right where the tile uses it — as a **fill** behind a glyph — and measures
            // 1.27:1 against the surface in dark and 1.21:1 in light when used as the glyph
            // itself. That is an invisible folder, on the colour every folder has until
            // somebody changes it.
            //
            // So an uncoloured folder takes the chrome's own weight, and a coloured one takes
            // its colour: every one of the five clears 3.0:1 against the surface in both
            // schemes (3.03 is the tightest, light amber). Measured, not assumed —
            // `ui-design-direction` sets the floor and the numbers are in this change's tasks.
            tint = if (folder.colour == FolderColour.None) {
                VaultColors.OnSurfaceVariant
            } else {
                VaultColors.folder(folder.colour)
            },
        )
        Text(
            text = folder.name,
            style = MaterialTheme.typography.bodyLarge,
            color = VaultColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = folder.count.toString(),
            style = VaultMono.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
        )
    }
}

/**
 * An image or a video, in a list — `destination-view-modes`.
 *
 * **The thumbnail is the point.** A photograph identified in a row by its filename alone
 * leaves the user worse off than the mosaic they just left, so the picture leads the row at a
 * fixed size and the text follows it. Where no thumbnail has been made yet the kind's glyph
 * stands in its place, so the leading square is never simply blank — the rule `vault-grid`
 * already sets for a tile with no picture, one shape along.
 *
 * A video's duration renders in the monospace face with tabular figures, and **no duration is
 * drawn where none is known**: a placeholder standing in for a value the app does not have
 * reads as a broken app.
 */
@Composable
fun MediaRow(
    item: VaultItem,
    thumbnailFor: (ThumbRef) -> android.graphics.Bitmap?,
    onOpen: (VaultItem) -> Unit,
    selected: Boolean,
    selecting: Boolean,
    onBeginSelection: (VaultItem) -> Unit,
    onToggleSelection: (VaultItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    DestinationRow(
        item = item,
        icon = if (item.kind == VaultKind.VIDEO) {
            R.drawable.ic_play_circle
        } else {
            R.drawable.ic_image
        },
        thumbnail = item.thumb?.let(thumbnailFor),
        detail = listOfNotNull(dateLabel(item)),
        monospaceDetail = durationLabel(item.durationMs),
        onOpen = onOpen,
        selected = selected,
        selecting = selecting,
        onBeginSelection = onBeginSelection,
        onToggleSelection = onToggleSelection,
        modifier = modifier,
    )
}
