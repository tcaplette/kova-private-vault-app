package com.app.vault.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset
import com.app.vault.ui.theme.Radius
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * One choice among several: a radio, the choice's name, and its detail beneath — `choice-rows`.
 *
 * ***Lifted, not invented*** — `exif-strip-choice`. This was `ShareScreen`'s private
 * `PolicyRow`; the Photo details sheet was the second surface that needed it, which is
 * `DESIGN.md` §6's condition for a wrapper. Every single choice in the app is drawn by it, so
 * Language, Photo details, Share, identity setup and the settings lists look and press alike.
 *
 * **The radio is the mark** — `language-picker-redesign` D7. Every row carries one, at its
 * start, where Material's list item and Android Settings' single-choice lists put it; it is
 * filled on the chosen row and empty on the rest, so a row with nothing on it never reads as a
 * label. The name stays `OnSurface` when chosen: a second mark in colour is what those lists
 * do not do.
 *
 * **One target, one node**, as [SwitchRow] does it. The row is `selectable` with the
 * radio-button role and the radio takes no clicks, so a thumb that lands on the detail line
 * chooses the row and a screen reader hears name, detail, state and "radio button" once.
 * Place a set of these in a `selectableGroup()`.
 *
 * **The pressed highlight is rounded and inset.** The row is measured [Spacing.sm] wider than
 * its caller gave it on each side and drawn back by the same, with that much padding inside,
 * so the radio and text stay where the caller's inset puts them — aligned with the headings
 * above — and the ripple runs 8dp past them, ending 8dp short of the screen or sheet edge.
 */
@Composable
fun ChoiceRow(
    name: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** A disabled row cannot be selected; its [detail] says why. */
    enabled: Boolean = true,
) = ChoiceRow(AnnotatedString(name), detail, selected, onClick, modifier, enabled)

/**
 * The same row, its name an [AnnotatedString] — `app-languages` D6, where each language's own
 * name carries its own locale so it is drawn in that language's glyph forms and a screen reader
 * speaks it in that language. No line is drawn under the name when [detail] is null.
 */
@Composable
fun ChoiceRow(
    name: AnnotatedString,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .layout { measurable, constraints ->
                val bleed = Spacing.sm.roundToPx()
                val placeable = measurable.measure(constraints.offset(horizontal = 2 * bleed))
                layout(constraints.constrainWidth(placeable.width - 2 * bleed), placeable.height) {
                    placeable.place(-bleed, 0)
                }
            }
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .clip(RoundedCornerShape(Radius.container))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = VaultColors.Primary,
                unselectedColor = VaultColors.OnSurfaceVariant,
            ),
        )
        Spacer(Modifier.width(Spacing.lg))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) VaultColors.OnSurface else VaultColors.OnSurfaceVariant,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurfaceVariant,
                )
            }
        }
    }
}
