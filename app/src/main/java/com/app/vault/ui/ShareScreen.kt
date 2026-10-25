package com.app.vault.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.res.stringResource
import com.app.vault.ExifPolicy
import com.app.vault.PhotoDetail
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.app.vault.ui.theme.Radius
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.app.vault.R
import com.app.vault.Identity
import com.app.vault.ShareContainer
import com.app.vault.ShareSend
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import com.app.vault.ui.theme.VaultMono
import com.app.vault.words
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import com.app.vault.localWords

/**
 * How long an expiring share lasts — `DESIGN.md` §7.19.
 *
 * The standard triple of every link-sharing product. Nothing in this app decided
 * these before P4-2; they are a choice, recorded as one.
 */
enum class ShareLifespan(@StringRes val label: Int, val millis: Long) {
    Day(R.string.lifespan_day, TimeUnit.DAYS.toMillis(1)),
    Week(R.string.lifespan_week, TimeUnit.DAYS.toMillis(7)),
    Month(R.string.lifespan_month, TimeUnit.DAYS.toMillis(30)),
}

/** Which policy row is chosen. Distinct from [ShareContainer.Policy], which carries the instant. */
enum class SharePolicyChoice { NoLimit, OpenOnce, Expires }

/**
 * What the compose state has chosen — held by `MainActivity` rather than remembered
 * here, because **Pair with someone** leaves this screen for the pairing flow and
 * the selections must be intact on the way back (`remote-pairing` D6). A null
 * [recipient] is **Anyone with the code**.
 *
 * Never stored: it is made fresh, with the first contact selected, each time the
 * flow opens — `secure-share-send`.
 */
data class ShareSelections(
    val recipient: Identity.Contact?,
    val choice: SharePolicyChoice = SharePolicyChoice.NoLimit,
    val lifespan: ShareLifespan = ShareLifespan.Week,
    // **On, and not read from the import preference.** This is the way out, and the cost of
    // the wrong default is the sender's location on somebody else's phone. Someone who chose
    // to keep metadata inside their own vault has not thereby agreed to send it to anyone —
    // the two questions have different stakes, so they get asked separately. §7.19.
    val stripMetadata: Boolean = true,
) {
    companion object {
        /** A contact when there is one, otherwise the code — `remote-pairing` D6. */
        fun initial(contacts: List<Identity.Contact>) = ShareSelections(recipient = contacts.firstOrNull())
    }
}

/**
 * The send flow — P4-2, `DESIGN.md` §7.19.
 *
 * Three states in sequence — compose, sealing, send — each with one `primary`
 * action (§6.1) and each leavable by back. The state lives above this composable
 * for `ExportScreen`'s reason: the seal runs off the main thread and must survive
 * a recomposition, so what it reports comes back in as arguments.
 *
 * **The screen's entire job is that the code and the file travel separately.**
 * Everything else here is arrangement; that one sentence is the feature. It sits
 * above the send action rather than below it, because below it is after the
 * decision.
 *
 * **No confirmation dialog** — §6.7 reserves the app's one dialog for destruction,
 * and this destroys nothing. **No sent-shares record** — nothing here or anywhere
 * stores the code, the recipient or the fact of the share, because a revoke that
 * cannot revoke is worse than no button and a stored code puts both halves of every
 * share on the sender's own device.
 */
@Composable
fun ShareScreen(
    itemName: String,
    itemSize: Long,
    /** Why this item cannot be shared, from [ShareSend.refusal]. Null when it can. */
    refusal: String?,
    /**
     * Who this install has paired with — P4-5. Listed first in the recipient choice,
     * which is drawn whether or not this is empty: **Anyone with the code** is always
     * its last row (`remote-pairing` D6).
     */
    contacts: List<Identity.Contact>,
    selections: ShareSelections,
    onSelections: (ShareSelections) -> Unit,
    /** **Pair with someone**, from the code warning: the add-a-contact choice over this flow. */
    onPair: () -> Unit,
    /** The four words, once sealing has produced them. Null before that. */
    code: List<String>?,
    /** Set instead of [code] when the container was sealed to a contact — P4-5. */
    sealedTo: String?,
    busy: Boolean,
    /** Plaintext bytes sealed against the total, for the progress bar. */
    progress: Pair<Long, Long>?,
    /** What failed, in words. Null when nothing has. */
    failed: String?,
    /**
     * Seal it. The contact is null for a code share, and the recipient's key
     * otherwise — the screen chooses the mode, `ShareSend` performs it.
     */
    /**
     * `exif-strip-choice`. Whether this item is one the strip can reach — JPEG, and nothing
     * else. False hides the choice entirely rather than drawing a dead one.
     */
    strippable: Boolean,
    /**
     * `exif-strip-choice`. What removing takes out — the install's one selection, named on the
     * removing row. The share screen asks whether; it does not offer its own categories.
     */
    removed: Set<PhotoDetail> = ExifPolicy.PRIVACY,
    onCreate: (ShareContainer.Policy, Identity.Contact?, Boolean) -> Unit,
    onSend: () -> Unit,
    onCopyCode: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Save the container through the system's save dialog instead of sending it —
     * `FORMAT.md` §10 item 51. The app the person then attaches it in is started by
     * the file picker, not by this app, so it is never told which app made the file.
     */
    onSave: () -> Unit = {},
    /** The last save's result: true saved, false failed, null nothing saved yet. */
    saved: Boolean? = null,
    /**
     * `one-time-purchase`. The trial has ended and the purchase is not owned, so this
     * screen says so instead of composing a share. The screen is handed the answer;
     * it never asks whether the user has paid.
     */
    trialEnded: Boolean = false,
    /** Play's localised price for the buy action, or null until Play answers. */
    price: String? = null,
    onBuy: () -> Unit = {},
    purchaseProblem: String? = null,
) {
    val (recipient, choice, lifespan, stripMetadata) = selections

    NoClipboard {
        VaultScaffold(title = stringResource(R.string.share_securely), modifier = modifier, onBack = onBack) {
            when {
                code != null -> SendState(code, onSend, onCopyCode, onSave, saved)
                sealedTo != null -> ContactSendState(sealedTo, onSend, onSave, saved)
                trialEnded -> RefusedState(price, onBuy, purchaseProblem)
                else -> ComposeState(
                    itemName = itemName,
                    itemSize = itemSize,
                    refusal = refusal,
                    contacts = contacts,
                    recipient = recipient,
                    onRecipient = { onSelections(selections.copy(recipient = it)) },
                    choice = choice,
                    lifespan = lifespan,
                    busy = busy,
                    progress = progress,
                    failed = failed,
                    onChoice = { onSelections(selections.copy(choice = it)) },
                    onLifespan = { onSelections(selections.copy(lifespan = it)) },
                    strippable = strippable,
                    removed = removed,
                    stripMetadata = stripMetadata,
                    onStripMetadata = { onSelections(selections.copy(stripMetadata = it)) },
                    onPair = onPair,
                    onCreate = {
                        onCreate(
                            when (choice) {
                                SharePolicyChoice.NoLimit -> ShareContainer.Policy.Unmanaged
                                SharePolicyChoice.OpenOnce -> ShareContainer.Policy.SingleView
                                SharePolicyChoice.Expires -> ShareContainer.Policy.Expiry(
                                    System.currentTimeMillis() + lifespan.millis
                                )
                            },
                            recipient,
                            strippable && stripMetadata,
                        )
                    },
                )
            }
        }
    }
}

/**
 * The send flow after the trial — `one-time-purchase` D5.
 *
 * **A state of this screen, not a notice on the grid**, because share is reached
 * from the viewer and the note editor as well as the selection bar, and both of
 * those cover the grid. This is the one surface every entry point arrives at, so
 * the refusal is said where the person is looking. When the purchase lands the
 * caller stops passing `trialEnded` and the same item's compose state replaces this
 * one with no restart.
 *
 * The second sentence says what stays free before anything else, for `BLOCKED`'s
 * reason: someone who reads "ended" and concludes the shares they receive are
 * locked too has been told something false.
 */
@Composable
private fun ColumnScope.RefusedState(
    price: String?,
    onBuy: () -> Unit,
    purchaseProblem: String?,
) {
    Text(
        text = stringResource(R.string.share_trial_ended),
        style = MaterialTheme.typography.titleMedium,
        color = VaultColors.OnSurface,
        modifier = Modifier.padding(top = Spacing.sm),
    )
    Text(
        text = stringResource(R.string.share_trial_ended_body),
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm),
    )
    PurchaseOffer(price = price, onBuy = onBuy, purchaseProblem = purchaseProblem)
}

@Composable
private fun ComposeState(
    itemName: String,
    itemSize: Long,
    refusal: String?,
    contacts: List<Identity.Contact>,
    recipient: Identity.Contact?,
    onRecipient: (Identity.Contact?) -> Unit,
    choice: SharePolicyChoice,
    lifespan: ShareLifespan,
    busy: Boolean,
    progress: Pair<Long, Long>?,
    failed: String?,
    onChoice: (SharePolicyChoice) -> Unit,
    onLifespan: (ShareLifespan) -> Unit,
    strippable: Boolean,
    removed: Set<PhotoDetail>,
    stripMetadata: Boolean,
    onStripMetadata: (Boolean) -> Unit,
    onPair: () -> Unit,
    onCreate: () -> Unit,
) {
    Text(
        text = itemName,
        style = MaterialTheme.typography.titleMedium,
        color = VaultColors.OnSurface,
        modifier = Modifier.padding(top = Spacing.sm),
    )
    Text(
        // The size is stated because it is what actually decides whether a
        // messenger will carry the file; the cap below is on length, because
        // length is the limit this feature was asked for. §7.19.
        text = sizeLabel(itemSize),
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xs),
    )

    if (refusal != null) {
        // Stated, not silently withheld: a control that vanishes teaches nothing.
        Text(
            text = refusal,
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.Error,
            modifier = Modifier.padding(top = Spacing.lg),
        )
        return
    }

    Text(
        text = if (recipient != null) {
            stringResource(R.string.share_lead_contact, recipient.name)
        } else {
            stringResource(R.string.share_lead_code)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.lg),
    )

    // **Who, above what they can do with it — P4-5, §7.19.** The recipient decides
    // whether a code exists at all; the policy applies either way. Always drawn:
    // contacts first, the secure choice, and the code last (`remote-pairing` D6).
    Text(
        text = stringResource(R.string.share_who),
        style = MaterialTheme.typography.titleMedium,
        color = VaultColors.OnSurface,
        modifier = Modifier.padding(top = Spacing.xl, bottom = Spacing.xs),
    )
    Column(modifier = Modifier.selectableGroup()) {
        contacts.forEach { contact ->
            ChoiceRow(
                name = contact.name,
                detail = stringResource(R.string.share_contact_detail, contact.name),
                selected = recipient?.publicKey.contentEquals(contact.publicKey),
                onClick = { onRecipient(contact) },
            )
        }
        ChoiceRow(
            name = stringResource(R.string.share_anyone),
            detail = stringResource(R.string.share_anyone_detail),
            selected = recipient == null,
            onClick = { onRecipient(null) },
        )
    }

    // The consequence next to the control that causes it — the screen's own rule.
    if (recipient == null) CodeWarning(onPair)

    Text(
        text = stringResource(R.string.share_how_long),
        style = MaterialTheme.typography.titleMedium,
        color = VaultColors.OnSurface,
        modifier = Modifier.padding(top = Spacing.xl, bottom = Spacing.xs),
    )

    Column(modifier = Modifier.selectableGroup()) {
        ChoiceRow(
            name = stringResource(R.string.share_no_limit),
            detail = stringResource(R.string.share_no_limit_detail),
            selected = choice == SharePolicyChoice.NoLimit,
            onClick = { onChoice(SharePolicyChoice.NoLimit) },
        )
        ChoiceRow(
            name = stringResource(R.string.share_open_once),
            detail = stringResource(R.string.share_open_once_detail),
            selected = choice == SharePolicyChoice.OpenOnce,
            onClick = { onChoice(SharePolicyChoice.OpenOnce) },
        )
        ChoiceRow(
            name = stringResource(R.string.share_expires),
            detail = stringResource(R.string.share_expires_detail, expiryLabel(lifespan)),
            selected = choice == SharePolicyChoice.Expires,
            onClick = { onChoice(SharePolicyChoice.Expires) },
        )
    }

    // Progressive disclosure: the durations appear only once expiry is the choice.
    if (choice == SharePolicyChoice.Expires) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.xl, top = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            ShareLifespan.entries.forEach { option ->
                OutlinedButton(onClick = { onLifespan(option) }) {
                    Text(
                        // Named, and the mark is a word rather than a colour — §6.4.
                        text = stringResource(option.label) + if (option == lifespan) " ✓" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (option == lifespan) VaultColors.Primary
                            else VaultColors.OnSurfaceVariant,
                    )
                }
            }
        }
    }

    Text(
        // The honest description of cooperative, client-side policy. Said once,
        // on the screen where the choice is made — §7.19.
        //
        // **And it says which phone, because the answer differs by mode.** The
        // ledger that spends a single-view share is per-install, so "Open once" means
        // once *per phone* in code mode. That fact is the code warning's to say
        // (`remote-pairing` D6), under the recipient rows, and is not said twice here.
        // Contact mode has no such gap, because the container is sealed to one public
        // key and the next phone cannot open it at all.
        text = if (recipient != null) {
            stringResource(R.string.share_limits_contact, recipient.name)
        } else {
            stringResource(R.string.share_limits_code)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.lg),
    )

    // **What the photograph carries** — `exif-strip-choice`.
    //
    // Third and last of the three questions, and it is below the limits sentence rather than
    // above it: that sentence explains the policy rows and has to stay beside them, which is
    // the rule that put it there. The screen reads who can open this, how long it opens for,
    // what that limit is worth, and then what the file itself holds.
    //
    // **Absent, not disabled, for an item the strip cannot reach** — a voice memo, a note, a
    // HEIC. Absence is what this app draws for a control with nothing behind it.
    if (strippable) {
        Text(
            text = stringResource(R.string.share_photo_carries),
            style = MaterialTheme.typography.titleMedium,
            color = VaultColors.OnSurface,
            modifier = Modifier.padding(top = Spacing.xl, bottom = Spacing.xs),
        )
        Column(modifier = Modifier.selectableGroup()) {
            ChoiceRow(
                name = stringResource(R.string.share_remove_details),
                // The install's Photo details selection, in words from the same function the
                // export control reads, so the copy and the strip cannot disagree.
                detail = ExifPolicy.shareSummary(localWords(), removed),
                selected = stripMetadata,
                onClick = { onStripMetadata(true) },
            )
            ChoiceRow(
                name = stringResource(R.string.share_as_is),
                detail = stringResource(R.string.share_as_is_detail),
                selected = !stripMetadata,
                onClick = { onStripMetadata(false) },
            )
        }
    }

    if (progress != null) {
        val (done, total) = progress
        Text(
            // The count, not the word: the button below already says "Sealing",
            // and the same word twice forty dp apart is one of them saying
            // nothing. `ExportScreen` labels its bar the same way.
            text = stringResource(R.string.backup_progress, sizeLabel(done), sizeLabel(total)),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurface,
            modifier = Modifier.padding(top = Spacing.lg),
        )
        LinearProgressIndicator(
            progress = { if (total <= 0L) 0f else done.toFloat() / total.toFloat() },
            color = VaultColors.Primary,
            trackColor = VaultColors.SurfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs),
        )
    }

    if (failed != null) {
        // A cause and a way forward. Never "corrupt" — §6.6a, and nothing here is.
        Text(
            text = failed,
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.Error,
            modifier = Modifier.padding(top = Spacing.lg),
        )
    }

    Button(
        onClick = onCreate,
        enabled = !busy,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg),
    ) {
        Text(
            text = when {
                busy -> stringResource(R.string.share_sealing)
                failed != null -> stringResource(R.string.share_try_again)
                else -> stringResource(R.string.share_create)
            },
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * The code warning — `remote-pairing` D6. Drawn while **Anyone with the code** is
 * selected, directly under the recipient rows, and absent while a contact is.
 *
 * `BackupStatusBlock`'s form: `ic_error` in the error colour with a title, so the
 * warning is a warning in words and shape and not in colour alone (§6.4), on the
 * tonal container. The four statements are the four ways a code share reaches
 * someone it was not meant for. It ends on the way out of all four, and is the only
 * place this screen invites pairing.
 */
@Composable
private fun CodeWarning(onPair: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.md)
            .clip(RoundedCornerShape(Radius.container))
            .background(VaultColors.SurfaceContainerHigh)
            .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.lg, bottom = Spacing.sm),
    ) {
        Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_error),
                    contentDescription = stringResource(R.string.warning),
                    tint = VaultColors.Error,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = stringResource(R.string.share_less_secure),
                    style = MaterialTheme.typography.titleSmall,
                    color = VaultColors.OnSurface,
                    modifier = Modifier.padding(start = Spacing.md),
                )
            }
            listOf(
                stringResource(R.string.share_risk_any_phone),
                stringResource(R.string.share_risk_once),
                stringResource(R.string.share_risk_same_chat),
                stringResource(R.string.share_risk_guessing),
            ).forEach { statement ->
                Row(modifier = Modifier.padding(top = Spacing.sm)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium, color = VaultColors.OnSurfaceVariant)
                    Text(
                        text = statement,
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColors.OnSurfaceVariant,
                        modifier = Modifier.padding(start = Spacing.sm),
                    )
                }
            }
            Text(
                text = stringResource(R.string.share_pair_first),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurface,
                modifier = Modifier.padding(top = Spacing.md),
            )
        }
        TextButton(
            onClick = onPair,
            modifier = Modifier.heightIn(min = Spacing.touchTarget),
        ) {
            Text(stringResource(R.string.share_pair), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SendState(
    code: List<String>,
    onSend: () -> Unit,
    onCopyCode: () -> Unit,
    onSave: () -> Unit,
    saved: Boolean?,
) {
    val codeLabel = stringResource(R.string.share_code_label, code.joinToString(", "))
    Text(
        text = stringResource(R.string.share_code_title),
        style = MaterialTheme.typography.titleMedium,
        color = VaultColors.OnSurface,
        modifier = Modifier.padding(top = Spacing.sm),
    )

    Text(
        // §5.1's monospace rule, and this is its second real case: the person
        // typing these words is on the other end of a phone call, and `0`/`O`
        // confusion costs them the file. Read as one phrase by a screen reader
        // rather than four unrelated words.
        text = code.joinToString(" "),
        style = VaultMono.titleLarge,
        color = VaultColors.OnSurface,
        modifier = Modifier
            .padding(top = Spacing.sm)
            .semantics { contentDescription = codeLabel },
    )

    Text(
        text = stringResource(R.string.share_code_separately),
        style = MaterialTheme.typography.titleMedium,
        color = VaultColors.OnSurface,
        modifier = Modifier.padding(top = Spacing.lg),
    )
    Text(
        text = stringResource(R.string.share_code_both),
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xs),
    )
    Text(
        text = stringResource(R.string.share_code_once),
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm),
    )

    Button(
        onClick = onSend,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg),
    ) {
        Text(text = stringResource(R.string.share_send_file), style = MaterialTheme.typography.labelLarge)
    }

    OutlinedButton(
        onClick = onCopyCode,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm),
    ) {
        Text(text = stringResource(R.string.share_copy_code), style = MaterialTheme.typography.labelLarge)
    }

    SaveRoute(onSave, saved)
}

/**
 * The send state for a contact share — P4-5, `DESIGN.md` §7.19.
 *
 * **None of [SendState]'s three sentences appear here**, and that is the whole
 * point of a second shape rather than a nullable code: *send the code a different
 * way*, *anyone who has both can open it* and *this code is shown once* each exist
 * because a secret exists. Teaching a rule about a secret this flow did not create
 * would be worse than saying nothing.
 *
 * The limits sentence is not repeated here either — it lives on the compose state
 * beside the policy rows it describes, which is true in both modes.
 */
@Composable
private fun ContactSendState(sealedTo: String, onSend: () -> Unit, onSave: () -> Unit, saved: Boolean?) {
    Text(
        text = stringResource(R.string.share_sealed_to, sealedTo),
        style = MaterialTheme.typography.titleMedium,
        color = VaultColors.OnSurface,
        modifier = Modifier.padding(top = Spacing.sm),
    )
    Text(
        text = stringResource(R.string.share_sealed_body, sealedTo),
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm),
    )

    Button(
        onClick = onSend,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg),
    ) {
        Text(text = stringResource(R.string.share_send_file), style = MaterialTheme.typography.labelLarge)
    }

    SaveRoute(onSave, saved)
}

/**
 * The second way out — `FORMAT.md` §10 item 51.
 *
 * **Why it exists:** handing the file over with the share sheet tells the app it goes
 * to which app sent it — Android starts that app as this one, and no file format can
 * hide that. Saving it through the system's save dialog and attaching it from Files
 * means the messenger is started by the file picker instead. Send stays the primary
 * action because it is the one most people want; this is a text button beneath it,
 * the Drive and Photos arrangement of *Share* beside *Download*, and the line under it
 * says what it buys rather than leaving the reader to guess why there are two ways.
 *
 * The result replaces that line in place and is announced, so a save that finished
 * while the system dialog was closing is not a silent one.
 */
@Composable
private fun SaveRoute(onSave: () -> Unit, saved: Boolean?) {
    TextButton(
        onClick = onSave,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm),
    ) {
        Text(text = stringResource(R.string.share_save_device), style = MaterialTheme.typography.labelLarge)
    }
    Text(
        text = when (saved) {
            true -> stringResource(R.string.share_saved)
            false -> stringResource(R.string.share_save_failed)
            null -> stringResource(R.string.share_save_hint)
        },
        style = MaterialTheme.typography.bodySmall,
        color = when (saved) {
            true -> VaultColors.OnSurface
            false -> VaultColors.Error
            null -> VaultColors.OnSurfaceVariant
        },
        modifier = Modifier
            .padding(top = Spacing.xs)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** The date an expiring share stops opening, in the reader's own locale. */
@Composable
private fun expiryLabel(lifespan: ShareLifespan): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM)
        .format(Date(System.currentTimeMillis() + lifespan.millis))
