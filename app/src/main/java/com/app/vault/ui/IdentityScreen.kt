package com.app.vault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.app.vault.CalculatorMethod
import com.app.vault.Cover
import com.app.vault.LauncherIdentity
import com.app.vault.R
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * Which launcher entry the app presents — P2-22.
 *
 * **A screen and not a dialog**, `AlbumScreen`'s reason: `DESIGN.md` §6.7 keeps the
 * delete confirmation as the app's only dialog, and this is a choice among four.
 *
 * **Reached from the drawer and from nowhere else.** The grid's bar row is at four
 * controls and `ScreenChromeTest` fails at five (P2-15, P2-16). An identity is a
 * setting, and every other setting in this app is reached from the drawer.
 *
 * The active row is marked **in words** — §6.4 keeps meaning off colour alone, and
 * this is the screen where that rule earns the most: the rows differ by name and
 * icon, and a tick that is only a tint would leave the one fact that matters carried
 * by nothing.
 *
 * ***Two things sit here that used to sit on the setup flow's preview step, and both
 * are here because this is where they are acted on.***
 *
 * The **music player's weakness** is a comparison, and a comparison means nothing on a
 * screen reached after the choice has been made. Here the three covers are side by
 * side, which is the only place a user can act on knowing that one of them is worse.
 * It is on that row alone: a warning on the weakest option does not require a rating
 * on the other two.
 *
 * The **boundary** — that Settings, the app-info page and notifications keep showing
 * the application label — is one fact, identical for all four identities. Stated per
 * setup it was read once and skipped forever after; behind a disclosure at the foot of
 * the list it is findable at the only moment anybody wants it, which is when they are
 * wondering exactly this.
 *
 * What is **not** here is the switch's cost, which belongs on the commit rather than in
 * the list — `IdentityCommitSheet` carries it, and `CoverScanTest` fails if a second
 * file starts saying it too.
 *
 * **Every row opens the commit sheet**, including the one already active. Choosing the
 * identity already shown remains a no-op — the guard lives on the switch, so a stray
 * tap still writes nothing and disables nothing — but the sheet is the only place the
 * cost of a switch is stated, so no row may skip it.
 */
@Composable
fun IdentityScreen(
    current: LauncherIdentity,
    onChoose: (LauncherIdentity) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * P2-24. Which identities this device can offer — the flashlight is absent where
     * there is no flash, the fingerprint and face-down idiom.
     */
    available: List<LauncherIdentity> = LauncherIdentity.entries,
    /** P2-24. Whether each cover already has a trigger, so the row can say so. */
    configured: (LauncherIdentity) -> Boolean = { true },
    /** `calculator-dots-methods` D8. The calculator's stored method, null where it is not set up. */
    calculatorMethod: CalculatorMethod? = null,
) {
    VaultScaffold(
        title = stringResource(R.string.identity_screen_title),
        modifier = modifier,
        onBack = onCancel,
    ) {
        Text(
            text = stringResource(R.string.identity_screen_lead),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.md),
        )

        available.forEach { identity ->
            ListItem(
                // The icon the launcher will actually draw, from the resource the
                // manifest names — a row that described an icon in words could drift
                // from the one the user ends up looking at.
                leadingContent = { IdentityIcon(identity, size = ROW_ICON) },
                headlineContent = {
                    Text(stringResource(identity.label), style = MaterialTheme.typography.bodyLarge)
                },
                // In words, never by tint — §6.4. The cover rows say what they are as
                // well as whether they are active, because "opens a calculator first"
                // is the whole difference between them and the vault's own entry.
                supportingContent = {
                    Column {
                        Text(
                            text = when {
                                identity == current -> stringResource(R.string.identity_current)
                                identity.cover == null -> stringResource(R.string.identity_opens_vault)
                                configured(identity) -> stringResource(opensFirst(identity.cover, needsSetUp = false))
                                else -> stringResource(opensFirst(identity.cover, needsSetUp = true))
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // On this row whatever else it is saying, because the weakness
                        // is a property of the cover and not of its current state.
                        //
                        // `pattern-cover-trigger` moved this from the music player's row:
                        // its gesture and the recorder's are now a drawn pattern, worth
                        // more than the calculator's keystrokes, so the calculator is
                        // the weakest cover rather than the player. A sentence that
                        // stayed on the player after the player stopped being the
                        // weakest would be worse than no sentence at all.
                        //
                        // `calculator-dots-methods` D8, for the same reason: only **Button
                        // sequence** is weak. The two pattern methods carry no sentence, and an
                        // unset calculator keeps the warning in front of whoever is choosing.
                        if (identity.cover == Cover.Calculator) {
                            calculatorWeakness(calculatorMethod)?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = Spacing.xs),
                                )
                            }
                        }
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = VaultColors.Surface,
                    headlineColor = VaultColors.OnSurface,
                    supportingColor = VaultColors.OnSurfaceVariant,
                ),
                modifier = Modifier.clickable { onChoose(identity) },
            )
        }

        Boundary()
    }
}

/** D8. The calculator row's sentence for its stored [method]; null where there is none to say. */
@Composable
private fun calculatorWeakness(method: CalculatorMethod?): String? = when (method) {
    CalculatorMethod.Sequence -> stringResource(R.string.identity_calculator_weak)
    CalculatorMethod.SequenceThenPattern, CalculatorMethod.HoldThenPattern -> null
    null -> stringResource(R.string.identity_calculator_weak_unset)
}

/**
 * What an identity does not change, folded away until somebody asks.
 *
 * A `TextButton` and not a row with a chevron: the app draws its icons from its own
 * drawables, and adding one for a disclosure would be an asset bought to decorate a
 * control whose label already says what it does. The expanded state is announced
 * rather than left to the glyph that is not there.
 */
@Composable
private fun Boundary() {
    var open by remember { mutableStateOf(false) }

    val state = stringResource(if (open) R.string.state_expanded else R.string.state_collapsed)
    TextButton(
        onClick = { open = !open },
        modifier = Modifier
            .padding(top = Spacing.md)
            .semantics { stateDescription = state },
    ) {
        Text(stringResource(R.string.identity_boundary_title))
    }

    if (open) {
        Text(
            text = stringResource(R.string.identity_boundary_body),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(
                start = Spacing.md,
                end = Spacing.md,
                bottom = Spacing.md,
            ),
        )
    }
}

/**
 * What the row says the identity opens. Not the launcher label lowercased: "Music" made "Opens a
 * music first". A whole sentence per cover, so each language words it its own way.
 */
private fun opensFirst(cover: Cover?, needsSetUp: Boolean): Int = when (cover) {
    Cover.Calculator -> if (needsSetUp) R.string.identity_opens_calculator_setup else R.string.identity_opens_calculator
    Cover.MusicPlayer -> if (needsSetUp) R.string.identity_opens_music_setup else R.string.identity_opens_music
    Cover.VoiceRecorder -> if (needsSetUp) R.string.identity_opens_recorder_setup else R.string.identity_opens_recorder
    null -> R.string.identity_opens_vault
}

/** Smaller than the sheet's, which is the one that has to be looked at properly. */
private val ROW_ICON = 40.dp
