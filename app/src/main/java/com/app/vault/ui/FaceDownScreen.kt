package com.app.vault.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.app.vault.GridPreference
import com.app.vault.R
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * The Face-down lock screen — `face-down-lock-settings` D7.
 *
 * Android's gesture-screen layout: one line saying what it does, the main switch, and the one
 * setting the gesture has, **Lock after**, greyed while the switch is off so it can be seen before
 * it is turned on. Each choice applies on tap; there is no confirm button, as on every settings row
 * in the app.
 *
 * A choice at or past the phone's own screen timeout is greyed, with one line under the list
 * saying why — D4: the screen going off locks the vault first, so that choice would never be what
 * locks it. A stored choice the timeout greys stays selected; the setting is kept, and the line
 * says why it does not apply.
 *
 * Every input is held by the activity; nothing here reads the device.
 */
@Composable
fun FaceDownScreen(
    /** Whether the phone has an accelerometer. Without one the switch takes the unavailable form. */
    available: Boolean,
    on: Boolean,
    lockAfterMs: Long,
    /** `Settings.System.SCREEN_OFF_TIMEOUT`, read on `onStart`; [Long.MAX_VALUE] where unread. */
    screenTimeoutMs: Long,
    onToggle: () -> Unit,
    onLockAfter: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val moot = { ms: Long -> ms > 0L && ms >= screenTimeoutMs }
    VaultScaffold(
        title = stringResource(R.string.drawer_face_down),
        modifier = modifier,
        onBack = onBack,
    ) {
        Text(
            text = stringResource(R.string.face_down_lead),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.md),
        )
        // `ListItem`'s own 16dp inner padding, bled back, so the glyph stands on the lead's edge —
        // the Biometric unlock screen's rows, for the same reason.
        Column(modifier = Modifier.bleed(Spacing.lg)) {
            SwitchRow(
                name = stringResource(R.string.face_down_switch),
                supporting = stringResource(if (on) R.string.bio_on else R.string.bio_off),
                icon = R.drawable.ic_screen_lock_portrait,
                checked = on,
                onChange = { onToggle() },
                unavailableReason = if (available) null else stringResource(R.string.face_down_no_sensor),
            )
        }
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = stringResource(R.string.face_down_lock_after),
            style = MaterialTheme.typography.titleMedium,
            color = VaultColors.OnSurface,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.sm))
        Column(modifier = Modifier.selectableGroup()) {
            GridPreference.FACE_DOWN_LOCK_AFTER.forEach { ms ->
                ChoiceRow(
                    name = lockAfterName(ms),
                    detail = null,
                    selected = ms == lockAfterMs,
                    onClick = { onLockAfter(ms) },
                    enabled = on && available && !moot(ms),
                )
            }
        }
        if (GridPreference.FACE_DOWN_LOCK_AFTER.any(moot)) {
            Text(
                text = stringResource(R.string.face_down_timeout_line, durationName(screenTimeoutMs)),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.lg),
            )
        }
    }
}

/** A Lock after preset's name: "Immediately", or the period in words. */
@Composable
internal fun lockAfterName(ms: Long): String =
    if (ms == 0L) stringResource(R.string.face_down_immediately) else durationName(ms)

/**
 * A period in words — whole minutes as minutes, anything else as seconds. The presets are all one
 * or the other, and the screen timeout line is only drawn for a timeout of 5 minutes or less.
 */
@Composable
internal fun durationName(ms: Long): String {
    val seconds = (ms / 1_000L).toInt()
    return if (seconds >= 60 && seconds % 60 == 0) {
        pluralStringResource(R.plurals.face_down_minutes, seconds / 60, seconds / 60)
    } else {
        pluralStringResource(R.plurals.face_down_seconds, seconds, seconds)
    }
}

/** The drawer row's state line — D6: off, at once, after a period, or not supported. */
@Composable
internal fun faceDownDrawerLine(available: Boolean, on: Boolean, lockAfterMs: Long): String = when {
    !available -> stringResource(R.string.bio_drawer_not_supported)
    !on -> stringResource(R.string.bio_off)
    lockAfterMs == 0L -> stringResource(R.string.face_down_locks_at_once)
    else -> stringResource(R.string.face_down_locks_after, durationName(lockAfterMs))
}
