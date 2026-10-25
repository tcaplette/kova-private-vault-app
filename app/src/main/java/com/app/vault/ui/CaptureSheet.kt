package com.app.vault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.app.vault.Capture
import com.app.vault.R
import com.app.vault.TamperLog
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * Enrolling intruder capture — P2-4, a sheet since `intruder-capture-sheet`.
 *
 * **A sheet and not a screen.** It changes three reversible things and comes back,
 * which is [FolderSheet]'s shape and the shape every such surface in this app
 * already has. It opens over the Intruder capture screen — `intruder-captures-at-home` —
 * so the timeline stays behind it.
 *
 * **Switches, not buttons.** Each mode used to be a title, a description and a
 * full-width button reading "Turn on" — a control naming the *opposite* of what is
 * true, which makes the reader work out which way round the state is. A switch shows
 * what is true now and changes it in one gesture. The state is in words as well, so
 * the switch is the smaller of two ways to say the same thing rather than the only
 * one (§6.4).
 *
 * **No explanation of the feature.** Five paragraphs used to sit above the toggles.
 * `CaptureStepScreen` teaches intruder capture in the first-run flow at the same
 * depth — it is a different composable and this change does not touch it — so the
 * prose here was a second reading for someone who had already had one, in a surface
 * with no room for it. What is left is one short line per mode.
 *
 * **No log row and no destination sentence** — `intruder-captures-at-home` D8. The log is the
 * screen behind the sheet. A sentence saying turning a mode on moves the destination would,
 * opened on a decoy, tell whoever holds it that another keyring exists.
 *
 * **A mode that has lost its permission says so** — `per-vault-intruder-capture` D8. It is on, but
 * records nothing: its switch reads off and its line says why. Turning it on asks for the
 * permission again, or opens Android's settings page where Android no longer asks. The same
 * sheet sets a vault's own modes, opened from that vault's timeline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureSheet(
    enabled: Set<Capture.Mode>,
    hasFrontCamera: Boolean,
    onEnable: (Capture.Mode) -> Unit,
    onDisable: (Capture.Mode) -> Unit,
    onDismiss: () -> Unit,
    problem: String?,
    modifier: Modifier = Modifier,
    /** Each enabled mode lacking a permission, and the first it lacks (`TamperLog.PERMISSION_*`). */
    lacking: Map<Capture.Mode, Int> = emptyMap(),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = VaultColors.Surface,
    ) {
        Text(
            text = stringResource(R.string.drawer_intruder_capture),
            style = MaterialTheme.typography.titleLarge,
            color = VaultColors.OnSurface,
            modifier = Modifier
                .padding(start = Spacing.screenInset, end = Spacing.screenInset)
                .semantics { heading() },
        )
        Text(
            text = stringResource(R.string.capture_sheet_lead),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(
                start = Spacing.screenInset,
                end = Spacing.screenInset,
                top = Spacing.xs,
                bottom = Spacing.sm,
            ),
        )

        ModeRow(
            stringResource(R.string.capture_mode_photo), stringResource(R.string.capture_sheet_photo),
            Capture.Mode.PHOTO, enabled, available = hasFrontCamera, onEnable, onDisable, lacking,
        )
        ModeRow(
            stringResource(R.string.capture_mode_audio), stringResource(R.string.capture_sheet_audio),
            Capture.Mode.AUDIO, enabled, available = true, onEnable, onDisable, lacking,
        )
        ModeRow(
            stringResource(R.string.capture_mode_video), stringResource(R.string.capture_sheet_video),
            Capture.Mode.VIDEO, enabled, available = hasFrontCamera, onEnable, onDisable, lacking,
        )

        if (!hasFrontCamera) {
            Text(
                text = stringResource(R.string.capture_sheet_no_front),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(
                    start = Spacing.screenInset,
                    end = Spacing.screenInset,
                    top = Spacing.sm,
                ),
            )
        }

        // A refused permission, not explanation — this is the one line that has to
        // stay whatever else is cut.
        problem?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.Error,
                modifier = Modifier.padding(
                    start = Spacing.screenInset,
                    end = Spacing.screenInset,
                    top = Spacing.sm,
                ),
            )
        }

        // The sheet ends clear of the gesture area rather than under it.
        Spacer(modifier = Modifier.height(Spacing.xl))
    }
}

/**
 * One mode, as a row with a switch.
 *
 * The whole row is the target as well as the switch, so §7.1 is satisfied by the row
 * rather than by a control the size of a thumb — the shape `DrawerRow` already uses.
 *
 * An unavailable mode is not offered: with no front camera photo and video cannot
 * fire, and a switch that moves and does nothing is worse than an absent one. It
 * stays present while it is *on*, so a mode enrolled before the lens was lost can
 * still be turned off.
 */
@Composable
private fun ModeRow(
    title: String,
    description: String,
    mode: Capture.Mode,
    enabled: Set<Capture.Mode>,
    available: Boolean,
    onEnable: (Capture.Mode) -> Unit,
    onDisable: (Capture.Mode) -> Unit,
    lacking: Map<Capture.Mode, Int>,
) {
    val enrolled = mode in enabled
    if (!available && !enrolled) return
    // On but not recording: the switch says what is true, and turning it on asks again.
    val missing = lacking[mode]
    val on = enrolled && missing == null

    val toggle = { if (on) onDisable(mode) else onEnable(mode) }
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        // The state in words under the line that names what the mode records, so it
        // is never carried by the control alone.
        supportingContent = {
            Text(
                text = if (enrolled && missing != null) {
                    stringResource(
                        if (missing == TamperLog.PERMISSION_CAMERA) R.string.capture_sheet_no_camera
                        else R.string.capture_sheet_no_microphone
                    )
                } else {
                    "$description  " + stringResource(if (on) R.string.bio_on else R.string.bio_off)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = { Switch(checked = on, onCheckedChange = { toggle() }) },
        colors = ListItemDefaults.colors(
            containerColor = VaultColors.Surface,
            headlineColor = VaultColors.OnSurface,
            supportingColor = VaultColors.OnSurfaceVariant,
        ),
        modifier = Modifier.clickable(onClick = toggle),
    )
}
