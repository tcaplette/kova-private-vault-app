package com.app.vault.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.app.vault.R
import com.app.vault.ThemeChoice
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * Which colour scheme the app draws, chosen in the drawer.
 *
 * ***It used to be a screen.*** A drawer row opened a `VaultScaffold` holding three
 * `ListItem` rows and two paragraphs, and reaching it cost a row, a drawer close, a
 * navigation, a choice and a back — for a setting that is one boolean with an auto
 * mode. The drawer already stated the rule that made this wrong, on the face-down row
 * two positions below: *a toggle rather than a screen, because a screen exists to hold
 * a choice among several and this is one boolean.*
 *
 * **A segmented control and not a tap-to-cycle row.** Cycling is right for the two
 * boolean rows beside this one and wrong here: with three states you cannot see what
 * the next tap gives you, and overshooting costs two more taps and two more full
 * re-themes. All three are visible, and any of them is one tap from any other.
 *
 * **The drawer does not close on a choice** — the one callback in this sheet that is
 * not wrapped in `closingDrawer`. That is the point of moving the setting here: the
 * grid behind re-themes under the user's finger, and a second thought costs one tap on
 * a control still there.
 *
 * **No preview swatch and no caption.** The screen this replaced was right about the
 * swatch and is more right now: the app *is* the preview, and the surface behind the
 * sheet is what changes. The caption went for a blunter reason — this control is
 * pinned to the bottom of the sheet, under the drawer's rows, and three lines of
 * prose there eat height that list needs on a short screen.
 *
 * **No heading over it.** The three segments name themselves; a word above them is
 * the same information twice. `contentDescription` on the row keeps the name for a
 * screen reader, which cannot read the segments as a set without it.
 *
 * The selected segment is marked by a **check**, not by a tint — §6.4 keeps meaning
 * off colour alone, and this is the one control in the app where a tint-only marker
 * would be changing colour at the instant it is supposed to be identifying something.
 * The active container is a surface step and never [VaultColors.Primary]: the primary
 * token holds the primary-action role in this app and nothing else, in both schemes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceControl(
    current: ThemeChoice,
    onChoose: (ThemeChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    // No visible heading: three segments reading "System", "Light" and "Dark" say
    // what they are, and a word above them repeats it. The name survives for a
    // screen reader, which otherwise reaches three buttons with nothing saying what
    // they set.
    val appearanceLabel = stringResource(R.string.appearance)
    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.md)
            .semantics { contentDescription = appearanceLabel },
    ) {
        // Iterated rather than hand-listed: an order written out separately from the
        // enum is an order that can drift from it with nothing failing.
        ThemeChoice.entries.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = choice == current,
                // Choosing what is already in effect writes nothing and re-themes
                // nothing — the caller drops it.
                onClick = { onChoose(choice) },
                shape = SegmentedButtonDefaults.itemShape(index, ThemeChoice.entries.size),
                colors = vaultSegmentColors(),
                // One line, shrinking only as far as the segment needs: at the largest font a
                // third of the drawer is narrower than "System", and a word broken mid-way
                // across two lines reads as two words. The user's scale holds until the
                // segment runs out of width.
                label = {
                    Text(
                        stringResource(choice.label),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        // Past the floor, a cut word ends in an ellipsis rather than mid-letter.
                        overflow = TextOverflow.Ellipsis,
                        autoSize = TextAutoSize.StepBased(
                            // 10sp and not 12: the floor scales with the user's font too,
                            // and at 2x a 12sp floor is still wider than a third of the
                            // drawer — measured, "System" clipped to "Syste".
                            minFontSize = 10.sp,
                            maxFontSize = MaterialTheme.typography.bodyMedium.fontSize,
                        ),
                    )
                },
            )
        }
    }
}

/**
 * The app's segmented control colours. One definition, so a second segmented control cannot
 * drift from this one.
 */
@Composable
internal fun vaultSegmentColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = VaultColors.Surface,
    activeContentColor = VaultColors.OnSurface,
    activeBorderColor = VaultColors.Outline,
    inactiveContainerColor = VaultColors.SurfaceContainerHigh,
    inactiveContentColor = VaultColors.OnSurfaceVariant,
    inactiveBorderColor = VaultColors.Outline,
)
