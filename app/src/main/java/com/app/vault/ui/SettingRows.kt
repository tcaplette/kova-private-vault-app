package com.app.vault.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.app.vault.R
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/*
 * The rows a settings sheet is built from — lifted out of `PhotoDetailsSheet` by `media-settings`.
 *
 * ***Lifted, not invented.*** The Media settings sheet is the second surface drawing these rows,
 * which is `DESIGN.md` §6's condition for a shared component: two copies of one kind of row are two
 * rows that drift. Photo details' appearance is unchanged.
 */

/**
 * A group's heading on a settings sheet. Not `SectionHeading` — the grid already has one of
 * those, for its date headings.
 */
@Composable
internal fun SettingsHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = VaultColors.OnSurface,
        modifier = Modifier
            .padding(start = Spacing.screenInset, end = Spacing.screenInset, top = Spacing.sm, bottom = Spacing.xs)
            .semantics { heading() },
    )
}

/**
 * One boolean, one row: glyph, name, the state in a word, and a switch at the end.
 *
 * **The whole row is the target, and it is one node.** The row is `toggleable` with the switch
 * role and the switch itself takes no clicks, so a screen reader announces the name, the state
 * and "switch" once, and a thumb that misses the switch still lands on the row.
 *
 * [unavailableReason] is `unavailable-capability-states`' unavailable form, the Biometric unlock
 * screen's: the reason replaces [supporting], the name drops to `OnSurfaceVariant`, the switch is
 * greyed and off, and the row is one merged node marked disabled with no click. The reason stays
 * at full `OnSurfaceVariant` — disabled alpha would drop it under 4.5:1 — and wraps.
 */
@Composable
internal fun SwitchRow(
    name: String,
    supporting: String,
    icon: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    unavailableReason: String? = null,
) {
    val greyed = unavailableReason != null
    ListItem(
        headlineContent = { Text(name, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            Text(unavailableReason ?: supporting, style = MaterialTheme.typography.bodyMedium)
        },
        leadingContent = {
            Icon(
                painter = painterResource(icon),
                // The name carries the meaning; the glyph repeats it.
                contentDescription = null,
            )
        },
        trailingContent = { Switch(checked = checked && !greyed, onCheckedChange = null, enabled = !greyed) },
        colors = ListItemDefaults.colors(
            containerColor = VaultColors.Surface,
            headlineColor = if (greyed) VaultColors.OnSurfaceVariant else VaultColors.OnSurface,
            supportingColor = VaultColors.OnSurfaceVariant,
            leadingIconColor = VaultColors.OnSurfaceVariant,
        ),
        modifier = if (greyed) {
            Modifier
                .heightIn(min = Spacing.touchTarget)
                .semantics(mergeDescendants = true) { disabled() }
        } else {
            Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
        },
    )
}

/**
 * One choice of several: a row naming the value in effect, which reveals the choices beneath it
 * in place — `media-settings`' Playback speed, lifted when `destination-settings-and-slideshow`
 * gave it four more uses (recording quality, memo speed, skip length, orientation, slideshow speed).
 *
 * **Revealed in place, not a second sheet or a segmented control.** Several segments across a
 * sheet break at the largest font — the appearance chooser's lesson — and a sheet over a sheet is
 * a place, which this is not. §8's enter and exit; with the animator scale at zero Compose runs
 * them in no time and the rows simply appear. Choosing applies at once and folds the list, so the
 * row reads the new value.
 */
@Composable
internal fun <T> RevealChoice(
    name: String,
    icon: Int,
    current: T,
    choices: List<T>,
    label: @Composable (T) -> String,
    detail: @Composable (T) -> String,
    /** One choice, and the choices, in words — "speed", "speeds" — for the row's spoken action and state. */
    one: String,
    many: String,
    onChoose: (T) -> Unit,
    /** A line under the revealed choices, when they need one. */
    footnote: String? = null,
) {
    var choosing by remember { mutableStateOf(false) }
    val action = stringResource(if (choosing) R.string.reveal_close else R.string.reveal_choose, if (choosing) many else one)
    val state = stringResource(
        if (choosing) R.string.reveal_shown else R.string.reveal_hidden,
        many.replaceFirstChar { it.uppercase() },
    )
    ListItem(
        headlineContent = { Text(name, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text(label(current), style = MaterialTheme.typography.bodyMedium) },
        leadingContent = {
            Icon(
                painter = painterResource(icon),
                // The name carries the meaning; the glyph repeats it.
                contentDescription = null,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = VaultColors.Surface,
            headlineColor = VaultColors.OnSurface,
            supportingColor = VaultColors.OnSurfaceVariant,
            leadingIconColor = VaultColors.OnSurfaceVariant,
        ),
        modifier = Modifier
            .clickable(onClickLabel = action) { choosing = !choosing }
            // Whether the choices are showing, said to a screen reader in words.
            .semantics { stateDescription = state },
    )
    AnimatedVisibility(
        visible = choosing,
        enter = expandVertically(tween(REVEAL_ENTER_MS, easing = EaseOut)) +
            fadeIn(tween(REVEAL_ENTER_MS, easing = EaseOut)),
        exit = shrinkVertically(tween(REVEAL_EXIT_MS, easing = EaseIn)) +
            fadeOut(tween(REVEAL_EXIT_MS, easing = EaseIn)),
    ) {
        Column {
            Column(
                modifier = Modifier
                    .padding(horizontal = Spacing.screenInset)
                    .selectableGroup(),
            ) {
                choices.forEach { choice ->
                    ChoiceRow(
                        name = label(choice),
                        detail = detail(choice),
                        selected = choice == current,
                        onClick = {
                            onChoose(choice)
                            choosing = false
                        },
                    )
                }
            }
            if (footnote != null) {
                Text(
                    text = footnote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurfaceVariant,
                    modifier = Modifier.padding(
                        start = Spacing.screenInset,
                        end = Spacing.screenInset,
                        top = Spacing.xs,
                    ),
                )
            }
        }
    }
}

/** §8: a reveal enters in 250ms and leaves in 150ms — exits are shorter than enters. */
internal const val REVEAL_ENTER_MS = 250
internal const val REVEAL_EXIT_MS = 150
