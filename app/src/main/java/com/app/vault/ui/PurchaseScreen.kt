package com.app.vault.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.app.vault.R
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import com.app.vault.ui.theme.VaultMono

/**
 * What the user has and what it costs — `settings-separation`, made a one-time
 * purchase by `one-time-purchase`.
 *
 * **Why it is a screen and not rows in the drawer.** Every other app setting is
 * one row that names its state and opens the thing that changes it — the identity,
 * the decoy, the panic credential, backup. This one is a countdown, a paragraph the
 * app is obliged to make, and a purchase; pouring that into the drawer would be the
 * drawer becoming the dumping ground `settings-separation` D2 exists to prevent. So
 * it follows the pattern the other rows already follow: a row that says the state,
 * and the prose one tap behind it.
 *
 * **The purchase is outlined and not primary — I4.** The entitlement never gates
 * reads or exports, and emphasising *Buy* would imply you buy to get your files
 * out. That is the one impression this app cannot give, and it is why the drawer's
 * one primary colour is on Unvault Everything instead.
 */
@Composable
fun PurchaseScreen(
    /** Days left in the trial. Ignored once [owned] or [pending] is true. */
    daysRemaining: Int,
    owned: Boolean,
    /** Play holds a payment that has not cleared. Not ownership; see `Billing.pending`. */
    pending: Boolean,
    trialStarted: Boolean,
    /** Play's localised price, or null until Play has answered. */
    price: String?,
    onBuy: () -> Unit,
    /** Set when Play could not offer the purchase; says so instead of failing silently. */
    purchaseProblem: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    VaultScaffold(
        title = stringResource(R.string.purchase_title),
        modifier = modifier,
        onBack = onBack,
    ) {
        Text(
            text = purchaseState(owned, pending, trialStarted, daysRemaining, countdown = false),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm),
        )

        // Absent for an owner and for a payment in flight, because neither is
        // counting down to anything.
        if (!owned && !pending) {
            Text(
                text = when {
                    !trialStarted -> pluralStringResource(R.plurals.purchase_days_from_first, daysRemaining, daysRemaining)
                    else -> daysRemaining.coerceAtLeast(0).let { pluralStringResource(R.plurals.purchase_days_left, it, it) }
                },
                style = VaultMono.bodyLarge,
                color = VaultColors.OnSurface,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }

        Text(
            text = when {
                owned ->
                    stringResource(R.string.purchase_owned_body)
                pending ->
                    stringResource(R.string.purchase_pending_body)
                else ->
                    stringResource(R.string.purchase_trial_body)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.md),
        )

        // An owner is offered nothing, and a pending payment is not offered a second
        // one on top of it; the lines above still say what each has.
        if (!owned && !pending) {
            PurchaseOffer(price = price, onBuy = onBuy, purchaseProblem = purchaseProblem)
        }

        Spacer(Modifier.height(Spacing.section))
    }
}

/**
 * The buy action with the one sentence that makes it a one-time purchase —
 * `one-time-purchase` D7. One composable because three surfaces carry it: this
 * screen, the send flow's refusal and the opened share's keep refusal. A refusal
 * with no way forward is a dead end, and three hand-made copies of the way forward
 * would drift apart.
 *
 * Outlined, never primary, for the I4 reason on [PurchaseScreen]. The price is
 * Play's, localised; until Play answers the button says "Buy" and nothing else,
 * rather than a figure written into the app that a currency would make false.
 */
@Composable
fun ColumnScope.PurchaseOffer(
    price: String?,
    onBuy: () -> Unit,
    purchaseProblem: String?,
) {
    if (purchaseProblem != null) {
        Text(
            text = purchaseProblem,
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
    Text(
        text = stringResource(R.string.purchase_one_payment),
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.lg),
    )
    OutlinedButton(
        onClick = onBuy,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm),
    ) {
        Text(
            text = if (price != null) stringResource(R.string.purchase_buy_for, price) else stringResource(R.string.purchase_buy),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * The entitlement in the fewest words that are still true — the drawer row's state
 * and this screen's first line. One function, so the two can never disagree.
 */
@Composable
fun purchaseState(
    owned: Boolean,
    pending: Boolean,
    trialStarted: Boolean,
    days: Int,
    countdown: Boolean = true,
): String = when {
    owned -> stringResource(R.string.purchase_state_owned)
    pending -> stringResource(R.string.purchase_state_pending)
    !trialStarted -> stringResource(R.string.purchase_state_not_started)
    days > 0 -> if (countdown) pluralStringResource(R.plurals.purchase_state_trial_left, days, days) else stringResource(R.string.purchase_state_trial)
    else -> stringResource(R.string.purchase_state_ended)
}
