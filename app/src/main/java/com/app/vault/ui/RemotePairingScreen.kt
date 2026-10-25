package com.app.vault.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.app.vault.Identity
import com.app.vault.Mnemonic
import com.app.vault.R
import com.app.vault.ShareCode
import com.app.vault.ui.theme.Radius
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import com.app.vault.ui.theme.VaultMono

/** Why a remote pairing stopped — each with its own cause and way forward, `remote-pairing` D5. */
enum class RemoteEnd { ReplyDidNotMatch, ConfirmationDidNotMatch, Expired, AlreadyEnded, OwnInvite, Unreadable }

/**
 * Where a remote pairing is, for one side. The state lives in `MainActivity`, because
 * the share sheet and the picker both leave the app and a lock may come between.
 */
sealed interface RemoteStep {
    /** "Step n of 3" for the side this is. */
    val number: Int

    /** The other person, as this phone's owner named them. */
    val name: String

    // --- inviting (A) ------------------------------------------------------------

    data class Naming(override val name: String) : RemoteStep {
        override val number = 1
    }

    /** The invite and its words. [sent] moves the primary from sending to opening the reply. */
    data class Inviting(
        val sid: ByteArray,
        override val name: String,
        val words: List<String>,
        val endsAt: Long,
        val sent: Boolean,
        val problem: String?,
    ) : RemoteStep {
        override val number = 2
    }

    /** B is a contact; the confirmation is to send. */
    data class Confirming(
        val sid: ByteArray,
        override val name: String,
        val alreadyKnownAs: String?,
        val sent: Boolean,
    ) : RemoteStep {
        override val number = 3
    }

    // --- answering (B) -----------------------------------------------------------

    /** Opening the invite; once [picked], naming and the four words. */
    data class Opening(
        val picked: Boolean,
        override val name: String,
        val words: List<String>,
        val problem: String?,
    ) : RemoteStep {
        override val number = 1
    }

    data class Replying(
        val sid: ByteArray,
        override val name: String,
        val endsAt: Long,
        val sent: Boolean,
        val problem: String?,
    ) : RemoteStep {
        override val number = 2
    }

    data class Answered(override val name: String, val alreadyKnownAs: String?) : RemoteStep {
        override val number = 3
    }

    // --- either ------------------------------------------------------------------

    data class Ended(
        val reason: RemoteEnd,
        override val name: String,
        val inviter: Boolean,
        override val number: Int,
    ) : RemoteStep
}

/** True for the side that made the invite. */
val RemoteStep.inviter: Boolean
    get() = when (this) {
        is RemoteStep.Naming, is RemoteStep.Inviting, is RemoteStep.Confirming -> true
        is RemoteStep.Opening, is RemoteStep.Replying, is RemoteStep.Answered -> false
        is RemoteStep.Ended -> inviter
    }

/** The 1–64 UTF-8 byte rule the in-person name state and the store both hold. */
internal fun validContactName(name: String): Boolean =
    name.trim().toByteArray(Charsets.UTF_8).size in 1..Identity.MAX_NAME_BYTES

/**
 * Remote pairing — `remote-pairing` D5, `DESIGN.md` §7.21.
 *
 * Two sides of three steps each, on one screen. Every state carries one filled
 * action; a file once handed to the share sheet stays one tap away as an outlined
 * "again", because the sheet reports nothing back and a cancelled send must be
 * recoverable. The words and the strict line under them are the screen's centre:
 * the four words are what this pairing rests on, and the line says plainly what
 * they prove and what they do not.
 */
@Composable
fun RemotePairingScreen(
    step: RemoteStep,
    mnemonic: Mnemonic,
    onNameChange: (String) -> Unit,
    onWordsChange: (List<String>) -> Unit,
    /** Naming → invite, or entry → reply, whichever this side's step 1 makes. */
    onMake: () -> Unit,
    /** Hands this step's file to the share sheet. */
    onSend: () -> Unit,
    /** Opens the picker for the file this step waits for. */
    onPick: () -> Unit,
    onCancel: () -> Unit,
    onNewInvite: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NoClipboard {
        VaultScaffold(
            title = stringResource(if (step.inviter) R.string.remote_invite_title else R.string.remote_answer_title),
            modifier = modifier,
            onBack = onBack,
        ) {
            StepIndicator(step.number)
            when (step) {
                is RemoteStep.Naming -> NamingState(step, onNameChange, onMake)
                is RemoteStep.Inviting -> InvitingState(step, onSend, onPick, onCancel)
                is RemoteStep.Confirming -> ConfirmingState(step, onSend, onDone)
                is RemoteStep.Opening -> OpeningState(step, mnemonic, onPick, onNameChange, onWordsChange, onMake)
                is RemoteStep.Replying -> ReplyingState(step, onSend, onPick, onCancel)
                is RemoteStep.Answered -> AnsweredState(step, onDone)
                is RemoteStep.Ended -> EndedState(step, onNewInvite, onDone)
            }
        }
    }
}

/**
 * "Step n of 3", in words and as three segments. The words carry it; the segments
 * are there to be seen at a glance, and are read as part of the one phrase.
 */
@Composable
private fun StepIndicator(number: Int) {
    val label = stringResource(R.string.decoy_step, number, 3)
    Column(
        modifier = Modifier
            .padding(top = Spacing.sm)
            .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = VaultColors.OnSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            repeat(3) { i ->
                Spacer(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i < number) VaultColors.Primary else VaultColors.SurfaceContainerHigh),
                )
            }
        }
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = VaultColors.OnSurface,
        modifier = Modifier
            .padding(top = Spacing.xl)
            .semantics { heading() },
    )
}

@Composable
private fun Body(text: String, top: androidx.compose.ui.unit.Dp = Spacing.sm) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = top),
    )
}

/**
 * A strict line: what the words prove, said in words and marked with a glyph so it
 * reads as a caution without relying on colour (§6.4).
 */
@Composable
private fun StrictLine(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg)
            .clip(RoundedCornerShape(Radius.container))
            .background(VaultColors.SurfaceContainerHigh)
            .padding(Spacing.lg)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_info),
            contentDescription = stringResource(R.string.remote_important),
            tint = VaultColors.OnSurface,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = VaultColors.OnSurface,
        )
    }
}

/** A file-level problem, directly under the action it is about, announced politely. */
@Composable
private fun Problem(text: String?) {
    if (text == null) return
    Row(
        modifier = Modifier
            .padding(top = Spacing.md)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_error),
            contentDescription = null,
            tint = VaultColors.Error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = VaultColors.OnSurface)
    }
}

/** "Waiting for …. This … ends at …", the status every waiting state carries. */
@Composable
private fun WaitingStatus(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurface,
        modifier = Modifier
            .padding(top = Spacing.xl)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun Primary(text: String, onClick: () -> Unit, enabled: Boolean = true, top: androidx.compose.ui.unit.Dp = Spacing.xl) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = top)
            .heightIn(min = Spacing.touchTarget),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Secondary(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm)
            .heightIn(min = Spacing.touchTarget),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Quiet(text: String, onClick: () -> Unit, top: androidx.compose.ui.unit.Dp = Spacing.sm) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = top)
            .heightIn(min = Spacing.touchTarget),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = VaultColors.OnSurfaceVariant)
    }
}

/** The name field, with the in-person name state's helper line and the same byte rule. */
@Composable
private fun NameField(label: String, name: String, onNameChange: (String) -> Unit, onDone: (() -> Unit)?) {
    val tooLong = name.trim().toByteArray(Charsets.UTF_8).size > Identity.MAX_NAME_BYTES
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text(label) },
        singleLine = true,
        isError = tooLong,
        supportingText = {
            Text(stringResource(if (tooLong) R.string.remote_name_too_long else R.string.remote_name_yours))
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg),
    )
}

/**
 * When a session ends, in the reader's locale — "5:28 PM", said by the sentence it sits in as
 * today or tomorrow: a session lasts 24 hours, so it is never later than tomorrow.
 */
@Composable
private fun endTime(endsAt: Long): String =
    DateUtils.formatDateTime(LocalContext.current, endsAt, DateUtils.FORMAT_SHOW_TIME)

// --- inviting -------------------------------------------------------------------

@Composable
private fun ColumnScope.NamingState(step: RemoteStep.Naming, onNameChange: (String) -> Unit, onMake: () -> Unit) {
    StepTitle(stringResource(R.string.remote_who_inviting))
    val ready = validContactName(step.name)
    NameField(stringResource(R.string.vault_name_label), step.name, onNameChange, onDone = { if (ready) onMake() })
    Primary(stringResource(R.string.remote_make_invite), onMake, enabled = ready, top = Spacing.lg)
}

@Composable
private fun ColumnScope.InvitingState(
    step: RemoteStep.Inviting,
    onSend: () -> Unit,
    onPick: () -> Unit,
    onCancel: () -> Unit,
) {
    StepTitle(stringResource(R.string.remote_invite_ready))
    Body(
        stringResource(R.string.remote_invite_send, step.name)
    )
    val wordsLabel = stringResource(R.string.remote_your_words, step.words.joinToString(", "))
    Text(
        // §5.1's monospace rule: these are read aloud and typed on the other end.
        text = step.words.joinToString(" "),
        style = VaultMono.titleLarge,
        color = VaultColors.OnSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg)
            .clip(RoundedCornerShape(Radius.container))
            .background(VaultColors.SurfaceContainer)
            .padding(horizontal = Spacing.lg, vertical = Spacing.xl)
            .semantics { contentDescription = wordsLabel },
    )
    StrictLine(
        stringResource(R.string.remote_invite_strict, step.name)
    )
    if (step.sent) {
        Primary(stringResource(R.string.remote_open_reply, step.name), onPick)
        Problem(step.problem)
        Secondary(stringResource(R.string.remote_send_invite_again), onSend)
        WaitingStatus(
            stringResource(
                if (DateUtils.isToday(step.endsAt)) R.string.remote_waiting_reply_today else R.string.remote_waiting_reply_tomorrow,
                step.name, endTime(step.endsAt),
            )
        )
    } else {
        Primary(stringResource(R.string.remote_send_invite), onSend)
    }
    Quiet(stringResource(R.string.remote_cancel_invite), onCancel, top = Spacing.lg)
}

@Composable
private fun ColumnScope.ConfirmingState(step: RemoteStep.Confirming, onSend: () -> Unit, onDone: () -> Unit) {
    StepTitle(
        if (step.alreadyKnownAs != null) stringResource(R.string.pairing_already_known, step.alreadyKnownAs)
        else stringResource(R.string.remote_now_contact, step.name)
    )
    Body(stringResource(R.string.remote_send_confirmation_body))
    if (step.sent) {
        Primary(stringResource(R.string.action_done), onDone)
        Secondary(stringResource(R.string.remote_send_confirmation_again), onSend)
    } else {
        Primary(stringResource(R.string.remote_send_confirmation), onSend)
        Quiet(stringResource(R.string.action_done), onDone)
    }
}

// --- answering ------------------------------------------------------------------

@Composable
private fun ColumnScope.OpeningState(
    step: RemoteStep.Opening,
    mnemonic: Mnemonic,
    onPick: () -> Unit,
    onNameChange: (String) -> Unit,
    onWordsChange: (List<String>) -> Unit,
    onMake: () -> Unit,
) {
    if (!step.picked) {
        StepTitle(stringResource(R.string.remote_open_invite))
        Primary(stringResource(R.string.remote_open_invite), onPick)
        Problem(step.problem)
        return
    }
    StepTitle(stringResource(R.string.remote_who_sent))
    NameField(stringResource(R.string.vault_name_label), step.name, onNameChange, onDone = null)
    Text(
        text = stringResource(R.string.remote_their_words),
        style = MaterialTheme.typography.titleMedium,
        color = VaultColors.OnSurface,
        modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.sm),
    )
    MnemonicWordEntry(
        mnemonic = mnemonic,
        words = step.words,
        onWordsChange = onWordsChange,
        count = ShareCode.WORD_COUNT,
    )
    StrictLine(stringResource(R.string.remote_know_sender))
    val ready = validContactName(step.name) &&
        step.words.size == ShareCode.WORD_COUNT &&
        step.words.all { it.isNotBlank() && mnemonic.read(it).unknown.isEmpty() && mnemonic.read(it).words.size == 1 }
    Primary(stringResource(R.string.remote_make_reply), onMake, enabled = ready)
    Problem(step.problem)
}

@Composable
private fun ColumnScope.ReplyingState(
    step: RemoteStep.Replying,
    onSend: () -> Unit,
    onPick: () -> Unit,
    onCancel: () -> Unit,
) {
    StepTitle(stringResource(R.string.remote_send_reply_title))
    Body(stringResource(R.string.remote_send_reply_body, step.name))
    if (step.sent) {
        Primary(stringResource(R.string.remote_open_confirmation, step.name), onPick)
        Problem(step.problem)
        Secondary(stringResource(R.string.remote_send_reply_again), onSend)
        WaitingStatus(
            stringResource(
                if (DateUtils.isToday(step.endsAt)) R.string.remote_waiting_confirmation_today else R.string.remote_waiting_confirmation_tomorrow,
                step.name, endTime(step.endsAt),
            )
        )
    } else {
        Primary(stringResource(R.string.remote_send_reply), onSend)
    }
    Quiet(stringResource(R.string.action_cancel), onCancel, top = Spacing.lg)
}

@Composable
private fun ColumnScope.AnsweredState(step: RemoteStep.Answered, onDone: () -> Unit) {
    StepTitle(
        if (step.alreadyKnownAs != null) stringResource(R.string.pairing_already_known, step.alreadyKnownAs)
        else stringResource(R.string.remote_now_contact, step.name)
    )
    Primary(stringResource(R.string.action_done), onDone)
}

// --- ended ----------------------------------------------------------------------

@Composable
private fun ColumnScope.EndedState(step: RemoteStep.Ended, onNewInvite: () -> Unit, onDone: () -> Unit) {
    val (title, body) = when (step.reason) {
        RemoteEnd.ReplyDidNotMatch ->
            stringResource(R.string.remote_ended) to stringResource(R.string.remote_ended_reply)
        RemoteEnd.ConfirmationDidNotMatch ->
            stringResource(R.string.remote_ended_this) to stringResource(R.string.remote_ended_confirmation, step.name)
        RemoteEnd.Expired -> stringResource(R.string.remote_ended) to stringResource(R.string.remote_ended_expired)
        RemoteEnd.AlreadyEnded -> stringResource(R.string.remote_ended) to stringResource(R.string.remote_ended_already)
        RemoteEnd.OwnInvite -> stringResource(R.string.remote_own_invite) to stringResource(R.string.remote_own_invite_body)
        RemoteEnd.Unreadable ->
            stringResource(R.string.contacts_unreadable) to stringResource(R.string.remote_unreadable_body)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xl)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_error),
            contentDescription = null,
            tint = VaultColors.Error,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(24.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, color = VaultColors.OnSurface)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
    val offersNewInvite = step.inviter && step.reason in
        setOf(RemoteEnd.ReplyDidNotMatch, RemoteEnd.Expired, RemoteEnd.AlreadyEnded)
    if (offersNewInvite) {
        Primary(stringResource(R.string.remote_new_invite), onNewInvite)
        Quiet(stringResource(R.string.action_done), onDone)
    } else {
        Primary(stringResource(R.string.action_done), onDone)
    }
}
