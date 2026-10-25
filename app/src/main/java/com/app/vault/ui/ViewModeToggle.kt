package com.app.vault.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.app.vault.VaultViewMode
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * Switches a destination between rows and tiles — `destination-view-modes`.
 *
 * ***It shows the mode it switches to, not the one you are in.*** A two-state control
 * wearing its current state reads as a label; one wearing its destination reads as an
 * action, which is what a button is. Drive, Dropbox and Files all make the same call, and
 * the cost is named rather than hidden: the user reads which mode they are in from the
 * content, not from this control. The content is a better answer anyway — a screen of rows
 * is unmistakably a screen of rows.
 *
 * **Here, and not in the header.** `vault-grid` says the header carries four controls, is
 * measured at 320dp, and **SHALL NOT grow to a fifth** — and forbids an overflow menu, which
 * P2-7 already deleted once. That settles where this cannot go. Where it does go is the
 * trailing edge of the sort control's band: a full-width row with its button at the start, so
 * the other end is free, aligned with the screen inset, and already withheld in exactly the
 * two states a view toggle must be withheld in — while selecting, because that bar owns the
 * surface, and in the trash, which has no mode.
 *
 * **`Spacing.touchTarget` written down rather than inherited**, for the reason
 * `VaultListScreen`'s lock action recorded: `IconButton`'s own 40dp default is under the
 * minimum this app's chrome requires, and a component default is not a design decision.
 *
 * The name is the **action** — `VaultViewMode.actionLabel` — because an icon-only control in
 * a band is unreachable to a screen reader without one, and naming it by the state would make
 * a button announce itself as a label.
 */
@Composable
fun ViewModeToggle(
    current: VaultViewMode,
    onChoose: (VaultViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = current.other
    IconButton(
        onClick = { onChoose(target) },
        modifier = modifier.size(Spacing.touchTarget),
    ) {
        Icon(
            painter = painterResource(target.icon),
            contentDescription = stringResource(target.actionLabel),
            // The band's own weight. `OnSurfaceVariant` and not `Primary`: the primary
            // colour is the floating button's alone on this screen (`grid-fab`), and the
            // sort control beside this one already reads at this weight.
            tint = VaultColors.OnSurfaceVariant,
        )
    }
}
