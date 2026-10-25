package com.app.vault.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.app.vault.Cover
import com.app.vault.LauncherIdentity
import com.app.vault.R
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * Where an identity is committed, and where the switch's cost is stated — P2-24.
 *
 * ***The screen this replaced asked nothing.*** It was a full preview step carrying six
 * paragraphs for the music player, four of which described an identity the user had
 * just chosen from a row that already named it, and its only control was Continue. A
 * screen whose one control is Continue is a screen that is tapped through, and words on
 * it are not read. Two of those paragraphs decided something and have moved to where
 * the deciding happens — the switch's cost to here, the comparison between covers to
 * the chooser's music player row. The other two are reference material identical for
 * every identity and sit in the chooser's disclosure.
 *
 * **A sheet and not a dialog.** `DESIGN.md` §6.7 keeps the delete confirmation as the
 * app's only dialog, and a surface that previews and commits should be draggable away,
 * which a dialog is not.
 *
 * **A sheet and not nothing.** The vault's own identity has no grant to ask for and no
 * gesture to record — remove the preview step and it would commit on a row tap with its
 * destructive cost stated only afterwards. This is the smallest surface that can carry
 * that cost at the moment it is paid, and using it for all four rows means one commit
 * path rather than a cover path and a bare-tap path.
 *
 * ***The system permission dialog is raised by this sheet's button, with nothing in
 * between.*** The step that used to sit here read "Allow access" and its only effect
 * was to raise the dialog that asks the same question — two consents for one decision,
 * and it explained the application label as an apology for a dialog the user had
 * already asked for by choosing a music player. A refusal is answered *here*, after the
 * fact, which is the only point at which the platform will say whether explaining
 * yourself is warranted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityCommitSheet(
    identity: LauncherIdentity,
    /**
     * Which of the three things this sheet is currently saying. Supplied by the host,
     * never decided here: only `MainActivity` sees the permission result that moves it.
     */
    state: CommitState,
    /**
     * The one button. What it means is the host's business and follows [state] —
     * commit, ask again, or open this app's settings page.
     */
    onPrimary: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = VaultColors.Surface,
    ) {
        Column(
            modifier = Modifier
                // A tall identity label on a short screen must not push the button past
                // the top of the window, where a tap aimed at it lands on the scrim and
                // dismisses the sheet instead — the failure `RenameSheet` records.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenInset)
                .padding(bottom = Spacing.section),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IdentityIcon(identity)
                Text(
                    text = stringResource(identity.label),
                    style = MaterialTheme.typography.titleLarge,
                    color = VaultColors.OnSurface,
                    modifier = Modifier.padding(start = Spacing.lg),
                )
            }

            Text(
                text = body(identity, state),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )

            Button(
                onClick = onPrimary,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg),
            ) {
                Text(label(identity, state), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** What the sheet is saying. Moved by the host, on a permission result and nothing else. */
enum class CommitState {
    /** Nothing has been asked yet. The cost is stated and the button commits. */
    Commit,

    /** The grant was refused, and the platform says a further request may still be shown. */
    Rationale,

    /** The grant was refused and no further request will be shown from inside the app. */
    Blocked,
}

/**
 * The sheet's single paragraph.
 *
 * ***The pinned-shortcut cost is stated here and in no other file.*** `launcher-identity`
 * requires it once, and `CoverScanTest` fails if a second file says it.
 */
@Composable
private fun body(identity: LauncherIdentity, state: CommitState): String = when (state) {
    CommitState.Commit ->
        stringResource(R.string.identity_commit_body)

    // Not in the error colour, and not phrased as a mistake. Refusing was a choice, and
    // the sentence's job is to say what it costs, not to tell the user off for making it.
    CommitState.Rationale -> when (identity.cover) {
        Cover.MusicPlayer ->
            stringResource(R.string.identity_rationale_music)
        Cover.VoiceRecorder ->
            stringResource(R.string.identity_rationale_recorder)
        Cover.Calculator, null -> ""
    }

    // Where to look, and no claim about what is there: the platform reports a permanent
    // refusal and a device-policy block identically, and only one of those has a toggle
    // waiting at the end of it.
    CommitState.Blocked -> when (identity.cover) {
        Cover.MusicPlayer ->
            stringResource(R.string.identity_blocked_music)
        Cover.VoiceRecorder ->
            stringResource(R.string.identity_blocked_recorder)
        Cover.Calculator, null -> ""
    }
}

/** The button, named for the act it performs rather than for the direction it moves. */
@Composable
private fun label(identity: LauncherIdentity, state: CommitState): String = when (state) {
    CommitState.Commit -> stringResource(if (identity.cover == null) R.string.identity_use_icon else R.string.identity_set_up)
    CommitState.Rationale -> stringResource(R.string.identity_ask_again)
    CommitState.Blocked -> stringResource(R.string.identity_open_settings)
}
