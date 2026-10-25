package com.app.vault.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.app.vault.R
import com.app.vault.TrashPurge
import com.app.vault.VaultItem
import com.app.vault.ui.theme.Radius
import com.app.vault.ui.theme.VaultColors

/**
 * The confirmation before an item is destroyed — P2-10, `DESIGN.md` §6.7.
 *
 * **The app's first dialog, and the argument for it is the shape of the
 * question.** Every other flow in the app is a screen, which is right when the
 * user is going somewhere. Here they are not: the question is one sentence, the
 * answer returns to exactly where it was asked, and navigating away from the
 * photograph being deleted in order to ask about it is worse than asking over it.
 *
 * **Not a `VaultDialog` wrapper.** §6 permits a wrapper when a *second* screen
 * needs the same thing and it records its own argument. This is one composable
 * with one call site in `MainActivity`, which hosts both the surfaces that raise
 * it. Whoever adds the third dialog makes the wrapper argument then, or does not.
 *
 * Two things Material does not give correctly on its own:
 *
 * 1. **The corner.** M3's dialog default is 28dp; §7.1 says containers are 12dp.
 *    Set from the token, for the reason §7.1 records about `IconButton`'s 40dp —
 *    a component default is not a design decision.
 * 2. **`FLAG_SECURE`.** A dialog is its own window, and `VaultApplication`'s
 *    per-activity callback never sees it. `Inherit` would in fact pick the flag up
 *    from the activity, but the whole point of that callback is that the guarantee
 *    does not depend on someone noticing; this window states it.
 */
@Composable
internal fun DeleteConfirmation(
    /**
     * What is being destroyed. One item names the file; several state the count —
     * P2-11. A selection of one is the single-item case, so there is one dialog
     * and one confirmation for both.
     */
    items: List<VaultItem>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * Whether this destroys rather than trashes — P2-12.
     *
     * False is the grid's and the viewer's delete: the items move to the trash and
     * are destroyed when their window expires. True is the trash's own action,
     * which is the irreversible one P2-10 built, and it keeps P2-10's words.
     *
     * The reversible text must not claim the copies cannot be recovered, because
     * for the length of the window that is false — and a confirmation that
     * overstates is a confirmation the user learns to dismiss unread.
     */
    permanent: Boolean = false,
    /**
     * `intruder-captures-at-home` D10. Intruder captures, which have no trash: always permanent,
     * named by count, and the body says their lines stay on the timeline.
     */
    captures: Boolean = false,
) {
    if (items.isEmpty()) return
    val title = when {
        captures -> pluralStringResource(R.plurals.intruder_delete_title, items.size, items.size)
        permanent && items.size == 1 -> stringResource(R.string.delete_one_forever, items.first().filename)
        permanent -> pluralStringResource(R.plurals.delete_many_forever, items.size, items.size)
        items.size == 1 -> stringResource(R.string.delete_one, items.first().filename)
        else -> pluralStringResource(R.plurals.delete_many, items.size, items.size)
    }
    val body = when {
        captures -> pluralStringResource(R.plurals.intruder_delete_body, items.size)
        permanent && items.size == 1 -> stringResource(R.string.delete_body_one_forever)
        permanent -> stringResource(R.string.delete_body_many_forever)
        else -> stringResource(R.string.delete_body_trash, TrashPurge.WINDOW_DAYS)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        shape = RoundedCornerShape(Radius.container),
        containerColor = VaultColors.SurfaceContainerHigh,
        titleContentColor = VaultColors.OnSurface,
        textContentColor = VaultColors.OnSurfaceVariant,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Text(
                // What it does and nothing more. It says nothing about the
                // device gallery, because a delete does not touch the originals
                // out there — `deletable` is a different word about a different
                // file (I6).
                body,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            // Named, not merely coloured — §6.4. Neither button is the screen's
            // primary action, so §6.1 is untouched.
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(if (permanent || captures) R.string.delete_forever else R.string.delete),
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
