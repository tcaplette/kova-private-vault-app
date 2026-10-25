package com.app.vault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.app.vault.Identity
import com.app.vault.R
import com.app.vault.ui.theme.Radius
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * Who this install has paired with — P4-4, `DESIGN.md` §7.21.
 *
 * **A list of names, and nothing else.** No key, no fingerprint, no hex, no
 * verified badge: a fingerprint here would invite a comparison against a second
 * channel that does not exist in an app with no network, and the scan across the
 * table — or, remotely, the four words — already was the verification.
 */
@Composable
fun ContactsScreen(
    state: Identity.State,
    /** Remote pairings in progress, newest first — `remote-pairing` D5. */
    waiting: List<Identity.PairingSession>,
    onResume: (Identity.PairingSession) -> Unit,
    /** Which contact the confirmation is open for, or null. */
    removing: Identity.Contact?,
    onAdd: () -> Unit,
    onAskRemove: (Identity.Contact) -> Unit,
    onConfirmRemove: () -> Unit,
    onDismissRemove: () -> Unit,
    /** Whether the confirmation before discarding this install's identity is open. */
    confirmingNewIdentity: Boolean,
    onAskNewIdentity: () -> Unit,
    onConfirmNewIdentity: () -> Unit,
    onDismissNewIdentity: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    VaultScaffold(title = stringResource(R.string.drawer_contacts), modifier = modifier, onBack = onBack) {
        when (state) {
            is Identity.State.Unreadable -> UnreadableState(onAskNewIdentity)
            is Identity.State.Ready -> {
                if (waiting.isNotEmpty()) WaitingSection(waiting, onResume)
                if (state.contacts.isEmpty()) EmptyState(onAdd)
                else ContactList(state.contacts, onAdd, onAskRemove)
            }
        }
    }

    if (removing != null) {
        RemoveConfirmation(removing, onConfirmRemove, onDismissRemove)
    }
    if (confirmingNewIdentity) {
        NewIdentityConfirmation(onConfirmNewIdentity, onDismissNewIdentity)
    }
}

/**
 * Remote pairings still in progress — `remote-pairing` D5. Above the list, because
 * each one is waiting on this phone's owner to do the next thing. A row says who and
 * what it is waiting for, in words; tapping it resumes that step. No words, no keys.
 */
@Composable
private fun WaitingSection(waiting: List<Identity.PairingSession>, onResume: (Identity.PairingSession) -> Unit) {
    Text(
        text = stringResource(R.string.contacts_waiting),
        style = MaterialTheme.typography.titleMedium,
        color = VaultColors.OnSurface,
        modifier = Modifier
            .padding(top = Spacing.sm, bottom = Spacing.xs)
            .semantics { heading() },
    )
    waiting.forEach { session ->
        NavigationRow(
            title = session.name,
            detail = when (session) {
                is Identity.PairingSession.Inviting -> stringResource(R.string.contacts_invite_sent)
                is Identity.PairingSession.Answering -> stringResource(R.string.contacts_reply_sent)
                is Identity.PairingSession.Confirming -> stringResource(R.string.contacts_confirmation_ready)
            },
            onClick = { onResume(session) },
        )
    }
    HorizontalDivider(
        color = VaultColors.OutlineVariant,
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.md),
    )
}

/**
 * A row that goes somewhere: a title, one line saying what it does, and a chevron.
 * The whole row is the target, at least 48 dp tall.
 */
@Composable
private fun NavigationRow(title: String, detail: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = VaultColors.OnSurface)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = VaultColors.OnSurfaceVariant)
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = VaultColors.OnSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** The three ways to add a contact — `remote-pairing` D5. */
enum class AddRoute { Together, NotTogether, HaveInvite }

/**
 * **Add a contact** asks how — `contact-pairing`. Three rows, each a title and one
 * line saying what it does. The in-person row is first and says it is the
 * strongest, because it is: the two codes are checked by the two people holding the
 * phones, and nothing else can swap them.
 */
@Composable
fun AddContactScreen(onChoose: (AddRoute) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    VaultScaffold(title = stringResource(R.string.contacts_add), modifier = modifier, onBack = onBack) {
        NavigationRow(
            title = stringResource(R.string.contacts_together),
            detail = stringResource(R.string.contacts_together_detail),
            onClick = { onChoose(AddRoute.Together) },
        )
        NavigationRow(
            title = stringResource(R.string.contacts_not_together),
            detail = stringResource(R.string.contacts_not_together_detail),
            onClick = { onChoose(AddRoute.NotTogether) },
        )
        NavigationRow(
            title = stringResource(R.string.contacts_have_invite),
            detail = stringResource(R.string.contacts_have_invite_detail),
            onClick = { onChoose(AddRoute.HaveInvite) },
        )
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    // §6.5 — one sentence and one action. It says what pairing is *for*, because a
    // list of nobody explains nothing on its own.
    Text(
        text = stringResource(R.string.contacts_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.lg),
    )
    Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.contacts_add), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ContactList(
    contacts: List<Identity.Contact>,
    onAdd: () -> Unit,
    onAskRemove: (Identity.Contact) -> Unit,
) {
    // The list is short by construction — people you have paired with one by one — so it is
    // a column inside the scaffold's scroll rather than a lazy list with its own.
    contacts.forEach { contact ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = contact.name,
                style = MaterialTheme.typography.bodyLarge,
                color = VaultColors.OnSurface,
                modifier = Modifier.padding(end = Spacing.md),
            )
            // **A word, not a glyph** — §9. Icons replace labels only in bars that
            // cannot hold text, and that section enumerates every such control in
            // the app; a list row is not one of them and has the width for a word.
            // `ic_delete` would also be the wrong mark twice over: it means *moved
            // to the trash* on every other surface, and a contact goes to no trash.
            //
            // The name is in the accessibility description because a column of
            // identical "Remove" controls names nothing to a screen reader.
            val removeLabel = stringResource(R.string.contacts_remove_named, contact.name)
            TextButton(
                onClick = { onAskRemove(contact) },
                modifier = Modifier
                    .heightIn(min = Spacing.touchTarget)
                    .semantics { contentDescription = removeLabel },
            ) {
                Text(
                    stringResource(R.string.action_remove),
                    style = MaterialTheme.typography.labelLarge,
                    color = VaultColors.OnSurfaceVariant,
                )
            }
        }
    }

    // Separated from the last row by a section rather than a gap: a filled button
    // tight under a list of Remove controls is a mis-tap waiting for the moment
    // just after someone removed the wrong person.
    Button(
        onClick = onAdd,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.section),
    ) {
        Text(stringResource(R.string.contacts_add), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The file is there and this install cannot open it — §7.21, `FORMAT.md` §13.
 *
 * It names the cause, offers one action, and **nothing is deleted until that
 * action is taken**. The word *corrupt* does not appear: the file is intact and
 * the key that opened it is gone, which is a different sentence and the one the
 * user needs (§6.6a).
 */
@Composable
private fun UnreadableState(onAskNewIdentity: () -> Unit) {
    Text(
        text = stringResource(R.string.contacts_unreadable),
        style = MaterialTheme.typography.titleMedium,
        color = VaultColors.OnSurface,
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.md),
    )
    Text(
        text = stringResource(R.string.contacts_unreadable_body),
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(bottom = Spacing.lg),
    )
    // **Not the filled primary.** This action discards every contact on the phone,
    // and the app's affirmative colour is what a user taps to go forward safely —
    // §6.2 spends it on the trust action, never on a destroy. It is an outlined
    // control that asks §6.7's one question, and the red lives on that dialog's
    // confirm where every other destruction in the app puts it.
    OutlinedButton(onClick = onAskNewIdentity, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.contacts_new_identity), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * §6.7 again, on the one action here that destroys something.
 *
 * The removal confirmation above forgets one person; this forgets every one of
 * them **and** the identity everybody scanned, so it says both halves. Nothing on
 * this path deletes anything until this dialog is answered.
 */
@Composable
private fun NewIdentityConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        shape = RoundedCornerShape(Radius.container),
        containerColor = VaultColors.SurfaceContainerHigh,
        titleContentColor = VaultColors.OnSurface,
        textContentColor = VaultColors.OnSurfaceVariant,
        title = { Text(stringResource(R.string.contacts_new_identity_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Text(
                stringResource(R.string.contacts_new_identity_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.contacts_start_again),
                    style = MaterialTheme.typography.labelLarge,
                    color = VaultColors.Error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.keep), style = MaterialTheme.typography.labelLarge, color = VaultColors.OnSurface)
            }
        },
    )
}

/**
 * §6.7's one dialog, on the app's one destructive shape.
 *
 * **It does not overclaim.** Removing a contact cannot unsend a container already
 * sealed to their key — there is no server to revoke anything through — and a
 * confirmation that implied otherwise would be the revoke button §7.19 refused to
 * build, wearing a sentence.
 */
@Composable
private fun RemoveConfirmation(
    contact: Identity.Contact,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        shape = RoundedCornerShape(Radius.container),
        containerColor = VaultColors.SurfaceContainerHigh,
        titleContentColor = VaultColors.OnSurface,
        textContentColor = VaultColors.OnSurfaceVariant,
        title = {
            Text(stringResource(R.string.contacts_remove_title, contact.name), style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Text(
                stringResource(R.string.contacts_remove_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.action_remove),
                    style = MaterialTheme.typography.labelLarge,
                    color = VaultColors.Error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.keep),
                    style = MaterialTheme.typography.labelLarge,
                    color = VaultColors.OnSurface,
                )
            }
        },
    )
}
