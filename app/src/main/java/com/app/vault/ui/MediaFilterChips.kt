package com.app.vault.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.app.vault.MediaFilter
import com.app.vault.R
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * Narrows Media to photographs or to video, without leaving Media — `vault-destinations`.
 *
 * ***A single-select chip row, and deliberately not an `Images | Videos` toggle.*** A
 * two-state toggle has no "both": it hides half the vault's media the moment the screen
 * opens and makes the user choose something they never asked to choose. There is always a
 * selection here and [MediaFilter.All] is what a vault opens on, so the default state of
 * this control is the state where it is doing nothing.
 *
 * **The check mark, not just the fill.** `ui-design-direction` forbids meaning carried by
 * colour alone, and a selected chip whose only difference is a tint says nothing in
 * greyscale. Material's leading check is the second signal, and `selected` is the third —
 * the one a screen reader reads.
 *
 * **Horizontally scrollable, though three short words will not need it.** The row is one
 * line at every text scale rather than wrapping to two and moving the first tile down; at
 * the largest scale on a narrow screen it scrolls instead of reflowing the grid.
 *
 * **View state.** Not persisted, and [MediaFilter.All] again after a lock: a vault that
 * reopened showing half its media would be answering a question asked in a session that is
 * over.
 *
 * Drawn where the sort control is drawn and withheld where it is withheld — beside it,
 * above the first tile, absent while selecting and absent in the trash.
 */
@Composable
fun MediaFilterChips(
    current: MediaFilter,
    onChoose: (MediaFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenInset, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        MediaFilter.entries.forEach { filter ->
            val selected = filter == current
            val spoken = stringResource(
                when (filter) {
                    MediaFilter.All -> R.string.media_filter_show_all
                    MediaFilter.Photos -> R.string.media_filter_show_photos
                    MediaFilter.Videos -> R.string.media_filter_show_videos
                }
            )
            FilterChip(
                selected = selected,
                onClick = { onChoose(filter) },
                label = {
                    Text(stringResource(filter.label), style = MaterialTheme.typography.labelLarge)
                },
                leadingIcon = if (selected) {
                    {
                        Icon(
                            painter = painterResource(R.drawable.ic_check_circle),
                            // Null: `selected` below is what announces the state, and the
                            // chip's own label is its name. A description here would have
                            // a screen reader say "selected" twice.
                            contentDescription = null,
                        )
                    }
                } else {
                    null
                },
                colors = FilterChipDefaults.filterChipColors(
                    // Structural, never `Primary` — that colour is the floating button's
                    // alone on this screen (`grid-fab`), and a chip row wearing it would
                    // be three more primary actions.
                    selectedContainerColor = VaultColors.Secondary,
                    selectedLabelColor = VaultColors.OnSecondary,
                    selectedLeadingIconColor = VaultColors.OnSecondary,
                    labelColor = VaultColors.OnSurfaceVariant,
                ),
                // Named as a whole, so the row reads as one control with three states
                // rather than three unrelated buttons.
                modifier = Modifier.semantics {
                    contentDescription = spoken
                },
            )
        }
    }
}
