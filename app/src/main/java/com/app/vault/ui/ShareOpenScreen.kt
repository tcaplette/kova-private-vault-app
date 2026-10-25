package com.app.vault.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.app.vault.Mnemonic
import com.app.vault.R
import com.app.vault.ShareCode
import com.app.vault.ShareOpen
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import java.text.DateFormat
import java.util.Date

/**
 * Opening a received share — P4-3, `DESIGN.md` §7.20, and [ShareScreen]'s other end.
 *
 * **What this screen must never do is tell the user which part was wrong.** A wrong
 * code, a file that is not a share, a tampered file, a truncated file and a
 * container sealed to somebody else are one message, exactly as `FORMAT.md` §12
 * requires. The newer-version refusal and the two *policy* refusals are decided
 * after the container has authenticated, so none of them is a hint about the code.
 *
 * **The file cannot say which question to ask** — §12 v2 has no readable mode — so
 * the caller probes it with this phone's identity first and arrives here with the
 * answer: sealed here, or not. Anything not sealed here gets the code entry, led by
 * the line that says a code applies only if one was sent.
 *
 * The word entry is the recovery kit's own component at four fields rather than a
 * second word field, which is [MnemonicWordEntry]'s whole argument.
 */
@Composable
fun ShareOpenScreen(
    mnemonic: Mnemonic,
    words: List<String>,
    onWordsChange: (List<String>) -> Unit,
    /** True while Argon2id is running. */
    busy: Boolean,
    /** The one undifferentiated failure, or null. */
    failed: Boolean,
    /** A policy refusal, which is not a failure and says why. */
    refusal: ShareOpen.Refusal?,
    /** Set once a container is open: what it holds. */
    opened: ShareOpen.Outcome.Opened?,
    /** True while the picked file is being probed with this phone's identity. Nothing is asked meanwhile. */
    probing: Boolean,
    /**
     * The probe found the file sealed to this phone — P4-5. A contact share asks
     * nothing: this install's own key opens it, so the four fields never appear.
     */
    sealedHere: Boolean,
    /**
     * True when this phone cannot read its own identity, so no probe ran —
     * `DESIGN.md` §7.20. Decided **before** the file is read, so it is the same for
     * every file, which is what keeps it from being an oracle.
     */
    identityUnreadable: Boolean,
    onContacts: () -> Unit,
    onOpen: () -> Unit,
    onView: () -> Unit,
    onKeep: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * `one-time-purchase`. Whether adding to a vault is permitted — handed in, never
     * asked here: this file is on `EntitlementScanTest`'s protected list, because
     * opening a share is never gated. Only keeping, which is an add, reads it.
     */
    keepAllowed: Boolean = true,
    /** Play's localised price for the keep refusal's buy action, or null. */
    price: String? = null,
    onBuy: () -> Unit = {},
    purchaseProblem: String? = null,
) {
    NoClipboard {
        VaultScaffold(title = stringResource(R.string.share_open_title), modifier = modifier, onBack = onBack) {
            when {
                refusal != null -> RefusedState(refusal)
                opened != null -> OpenedState(
                    opened, onView, onKeep, keepAllowed, price, onBuy, purchaseProblem,
                )
                probing -> ProbingState()
                sealedHere -> ContactState(busy = busy, failed = failed, onOpen = onOpen)
                else -> CodeState(
                    mnemonic = mnemonic,
                    words = words,
                    onWordsChange = onWordsChange,
                    busy = busy,
                    failed = failed,
                    onOpen = onOpen,
                    identityUnreadable = identityUnreadable,
                    onContacts = onContacts,
                )
            }
        }
    }
}

/**
 * The probe is running. It asks nothing and says nothing in words — it is over in
 * well under a second on any phone this app supports — but a bar shows the screen
 * is working rather than empty, and a screen reader is told the same.
 */
@Composable
private fun ProbingState() {
    val reading = stringResource(R.string.share_open_reading)
    LinearProgressIndicator(
        color = VaultColors.Primary,
        trackColor = VaultColors.SurfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg)
            .semantics { contentDescription = reading },
    )
}

/**
 * Everything the probe did not open: a code share, a file that is not a share, a
 * share meant for another phone, and every file while this phone cannot read its
 * identity. The screen cannot tell them apart and does not try.
 *
 * **The lead line is conditional on purpose.** "If this was sent with a code" is
 * what makes a code entry an honest answer to a file the app could not identify:
 * it does not claim the file is a code share, and the failure's recovery line names
 * the other possibility — that this is not the file they were sent.
 */
@Composable
private fun CodeState(
    mnemonic: Mnemonic,
    words: List<String>,
    onWordsChange: (List<String>) -> Unit,
    busy: Boolean,
    failed: Boolean,
    onOpen: () -> Unit,
    identityUnreadable: Boolean,
    onContacts: () -> Unit,
) {
    Text(
        text = stringResource(R.string.share_open_enter_code),
        style = MaterialTheme.typography.bodyLarge,
        color = VaultColors.OnSurface,
        modifier = Modifier.padding(top = Spacing.sm),
    )
    Text(
        text = stringResource(R.string.share_open_code_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.lg),
    )

    MnemonicWordEntry(
        mnemonic = mnemonic,
        words = words,
        onWordsChange = onWordsChange,
        enabled = !busy,
        count = ShareCode.WORD_COUNT,
    )

    if (failed) {
        // One message for every cryptographic outcome, directly under the field it
        // is about. Saying more would say which part was wrong, which is the
        // property §12 spends a format rule on — so the recovery line names both
        // things the reader can check and does not choose between them. Announced
        // politely, because the field it follows keeps the screen reader's focus.
        Column(
            modifier = Modifier
                .padding(top = Spacing.lg)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                text = stringResource(R.string.share_open_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.Error,
            )
            Text(
                text = stringResource(R.string.share_open_check),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }

    if (busy) {
        LinearProgressIndicator(
            color = VaultColors.Primary,
            trackColor = VaultColors.SurfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.lg),
        )
    }

    Button(
        onClick = onOpen,
        // The codec refuses an off-list token before any Argon2id runs, so the
        // screen refuses it before any wait rather than after one.
        enabled = !busy && ShareCode(mnemonic).normalise(words.joinToString(" ")) != null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg),
    ) {
        Text(
            text = stringResource(if (busy) R.string.vault_opening else R.string.share_open_action),
            style = MaterialTheme.typography.labelLarge,
        )
    }

    // This phone's condition, not the file's — `DESIGN.md` §7.20. Beneath the entry
    // rather than instead of it, because the flow can no longer know whether the
    // file wanted the identity: a code share still opens above, and a share sealed
    // to this phone is the one thing that cannot until Contacts is dealt with.
    if (identityUnreadable) {
        Text(
            text = stringResource(R.string.share_open_no_contacts),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurface,
            modifier = Modifier.padding(top = Spacing.xl, bottom = Spacing.sm),
        )
        OutlinedButton(onClick = onContacts, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.share_open_contacts), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ColumnScope.OpenedState(
    opened: ShareOpen.Outcome.Opened,
    onView: () -> Unit,
    onKeep: () -> Unit,
    keepAllowed: Boolean,
    price: String?,
    onBuy: () -> Unit,
    purchaseProblem: String?,
) {
    // `one-time-purchase`. The policy is the stronger reason — it holds whatever is
    // paid — so a limited share states only its policy, and this case is reached
    // only by a share that would otherwise be kept.
    val trialRefusesKeep = opened.canKeep && !keepAllowed
    Text(
        text = opened.filename,
        style = MaterialTheme.typography.titleMedium,
        color = VaultColors.OnSurface,
        modifier = Modifier.padding(top = Spacing.sm),
    )
    Text(
        text = sizeLabel(opened.plaintextLength),
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xs),
    )

    // Said on this side too, because the person reading it is not the person who
    // chose it — §7.20.
    limitSentence(opened)?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.lg),
        )
    }

    Button(
        onClick = onView,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg),
    ) {
        Text(text = stringResource(R.string.share_open_view), style = MaterialTheme.typography.labelLarge)
    }

    // **Absent, not disabled** — `share-keep-gate`. A disabled control says *not
    // yet*: it implies a condition the reader could go and satisfy. There is no
    // such condition here and there never will be, on this device or any other, so
    // a greyed-out button would be a dead affordance that invites a tap and teaches
    // nothing on the tap. It goes, and the line below says so in words rather than
    // leaving the reader to notice an absence.
    if (opened.canKeep && !trialRefusesKeep) {
        OutlinedButton(
            onClick = onKeep,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.sm),
        ) {
            Text(text = stringResource(R.string.share_open_keep), style = MaterialTheme.typography.labelLarge)
        }
    }

    Text(
        // The limit itself is already stated above by `limitSentence`, so this
        // carries only the consequence — the same fact twice, forty dp apart, is
        // one of them saying nothing. The second clause is deliberately the
        // unmanaged line's own words, so the two screens read as one screen with
        // one control missing rather than as two different promises.
        //
        // **"Nothing is saved" rather than "it's gone"**, because "gone" is false
        // for an expiring share: the recipient still holds a container that opens
        // until its date. What this app does is save nothing; what the container
        // does afterwards is the sentence above's business.
        text = if (trialRefusesKeep) {
            stringResource(R.string.share_open_keep_needs_purchase)
        } else if (opened.canKeep) {
            stringResource(R.string.share_open_leave)
        } else {
            stringResource(R.string.share_open_cannot_keep)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm),
    )

    // `one-time-purchase` D5. Unlike the policy's refusal, this one has a condition
    // the reader can satisfy, so the way to satisfy it is right here; when the
    // purchase lands, `keepAllowed` flips and the keep action replaces all of this.
    if (trialRefusesKeep) {
        PurchaseOffer(price = price, onBuy = onBuy, purchaseProblem = purchaseProblem)
    }

    // No onward share. An unmanaged received item becomes shareable by being kept,
    // at which point §7.19 applies to it as an ordinary vault item; a limited one
    // has no such route, which is what `share-keep-gate` closed. Either way a
    // forward button here would be the beginning of a messenger.
}

/**
 * A container sealed to this phone — P4-5, `DESIGN.md` §7.20.
 *
 * **No word entry, because there is no word to enter.** Asking for four words and
 * then ignoring them would be a form asking a question whose answer it already
 * has. The failure here is the same single message the code path shows: a
 * container sealed to somebody else must not be distinguishable from a wrong code,
 * or every container becomes an oracle for *is this the recipient's phone*.
 *
 * **No sender is named**, and none can be: the X-Wing encapsulation spends an
 * ephemeral key, so `FORMAT.md` §12's container names nobody.
 */
@Composable
private fun ContactState(busy: Boolean, failed: Boolean, onOpen: () -> Unit) {
    Text(
        text = stringResource(R.string.share_open_sealed_here),
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.lg),
    )

    if (failed) {
        Text(
            text = stringResource(R.string.share_open_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.Error,
            modifier = Modifier.padding(bottom = Spacing.lg),
        )
    }

    Button(
        onClick = onOpen,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(if (busy) R.string.vault_opening else R.string.share_open_open),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun RefusedState(refusal: ShareOpen.Refusal) {
    if (refusal is ShareOpen.Refusal.NewerVersion) {
        // Not a failure and not the reader's mistake, and nothing on this phone
        // can change it except an update — so it says that and offers nothing else.
        Text(
            text = stringResource(R.string.share_open_newer),
            style = MaterialTheme.typography.bodyLarge,
            color = VaultColors.OnSurface,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        return
    }
    Text(
        text = when (refusal) {
            is ShareOpen.Refusal.AlreadyOpened ->
                stringResource(R.string.share_open_already_opened)
            is ShareOpen.Refusal.Expired ->
                stringResource(
                    R.string.share_open_expired,
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(refusal.atEpochMillis)),
                )
            is ShareOpen.Refusal.NewerVersion -> error("handled above")
        },
        style = MaterialTheme.typography.bodyLarge,
        color = VaultColors.OnSurface,
        modifier = Modifier.padding(top = Spacing.sm),
    )
    Text(
        // Not an error and not the user's mistake: the code was right. Saying so
        // is what stops them retyping a correct code.
        text = stringResource(R.string.share_open_code_correct),
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm),
    )
}

/** What the container's own policy means for whoever is holding it now. */
@Composable
private fun limitSentence(opened: ShareOpen.Outcome.Opened): String? =
    when (val policy = opened.details.policy) {
        is com.app.vault.ShareContainer.Policy.SingleView -> stringResource(R.string.share_open_limit_once)
        is com.app.vault.ShareContainer.Policy.Expiry -> stringResource(
            R.string.share_open_limit_expiry,
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(policy.atEpochMillis)),
        )
        else -> null
    }
