package com.app.vault.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import com.app.vault.BiometricWrap
import com.app.vault.R
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * Which biometric rows the device gets — `biometric-unlock-screen` D1, decided by
 * `BiometricWrap.layout` from the state, the two hardware features and whether every
 * sensor is strong.
 *
 * - **Fingerprint**: a strong fingerprint, and a face that is weak or absent.
 * - **Face**: a strong face and no fingerprint (Pixel 4).
 * - **Both**: face and fingerprint both strong (Pixel 8 and later). One row, because the
 *   key is bound to the strong class and Android accepts either at the prompt.
 * - **Unnamed**: a strong biometric that is neither declared feature.
 * - **None**: nothing strong.
 */
enum class BiometricLayout { Fingerprint, Face, Both, Unnamed, None }

/**
 * The Biometric unlock screen — `biometric-unlock-screen` D5, opened from the drawer's
 * Biometric unlock row.
 *
 * `IdentityScreen`'s shape, because it is the same kind of thing: one setting with more
 * to say than a row holds. The lead, the rows, then the facts. The rows say what works
 * here and why the rest does not; the facts say how it is used and what Android, not this
 * app, decides. The first-run step draws the same [BiometricRows] and [BiometricFacts], so
 * the two cannot say different things about one device.
 *
 * Every input is held by the activity and read on `onStart`; nothing here reads the device.
 */
@Composable
fun BiometricScreen(
    layout: BiometricLayout,
    state: BiometricState,
    hasFingerprint: Boolean,
    hasFace: Boolean,
    /** On and Off: the live row's switch. Off raises the enable prompt. */
    onToggle: () -> Unit,
    /** NotSetUp: Android's enrolment screen, and the prompt on the way back. */
    onSetUp: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    VaultScaffold(
        title = stringResource(BiometricWrap.NAME),
        modifier = modifier,
        onBack = onBack,
    ) {
        Text(
            text = stringResource(BiometricWrap.LEAD),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.md),
        )
        BiometricRows(layout, state, hasFingerprint, hasFace, onToggle, onSetUp)
        Spacer(Modifier.height(Spacing.lg))
        BiometricFacts(layout)
    }
}

/**
 * The rows for [layout], fingerprint before face on every device, so the eye finds the
 * same row in the same place — D2. At most one live row: one switch governs one wrap.
 *
 * The rows bleed into the parent's inset by `ListItem`'s own 16dp inner padding, so
 * their glyphs stand on the same edge as the lead above and the facts' markers below,
 * and a press lights the row to the screen's edge as Settings' rows do. Without it the
 * glyphs sit one step in from everything around them, on this screen and on the step.
 */
@Composable
fun BiometricRows(
    layout: BiometricLayout,
    state: BiometricState,
    hasFingerprint: Boolean,
    hasFace: Boolean,
    onToggle: () -> Unit,
    onSetUp: () -> Unit,
) {
    Column(modifier = Modifier.bleed(Spacing.lg)) {
        val live = BiometricWrap.liveName(layout)
        when (layout) {
            BiometricLayout.Fingerprint -> {
                LiveRow(live!!, R.drawable.ic_fingerprint, state, onToggle, onSetUp)
                UnavailableRow(face = true, hardwarePresent = hasFace)
            }
            BiometricLayout.Face -> {
                UnavailableRow(face = false, hardwarePresent = hasFingerprint)
                LiveRow(live!!, R.drawable.ic_face, state, onToggle, onSetUp)
            }
            BiometricLayout.Both, BiometricLayout.Unnamed ->
                LiveRow(live!!, R.drawable.ic_fingerprint, state, onToggle, onSetUp)
            BiometricLayout.None -> {
                UnavailableRow(face = false, hardwarePresent = hasFingerprint)
                UnavailableRow(face = true, hardwarePresent = hasFace)
            }
        }
    }
}

/** The facts under the rows — D5's table, through onboarding's [Fact]. */
@Composable
fun ColumnScope.BiometricFacts(layout: BiometricLayout) {
    BiometricWrap.facts(layout).forEach { (heading, body) -> Fact(stringResource(heading), stringResource(body)) }
}

/**
 * The row that governs the wrap — D3.
 *
 * On and Off: one `toggleable` node with the switch role, `SwitchRow`'s idiom, so the
 * name, the state and "switch" are announced once and a thumb that misses the switch
 * still lands on the row. NotSetUp: one clickable node with the button role and no
 * switch — nothing is there to turn on yet, and the line says what the tap does.
 * NotAvailableNow and NeedsUpdate: greyed, its switch off, nothing to tap.
 */
@Composable
private fun LiveRow(
    @StringRes name: Int,
    icon: Int,
    state: BiometricState,
    onToggle: () -> Unit,
    onSetUp: () -> Unit,
) {
    BiometricRow(
        name = stringResource(name),
        icon = icon,
        line = BiometricWrap.liveLine(state)?.let { stringResource(it) }.orEmpty(),
        checked = (state == BiometricState.On).takeUnless { state.fixable },
        greyed = !state.ready && !state.fixable,
        modifier = when {
            state.ready -> Modifier.toggleable(
                value = state == BiometricState.On,
                role = Role.Switch,
                onValueChange = { onToggle() },
            )
            state.fixable -> Modifier.clickable(role = Role.Button, onClick = onSetUp)
            else -> Modifier
        },
    )
}

/** A biometric this device cannot use: greyed, its switch off, saying so and why. */
@Composable
private fun UnavailableRow(face: Boolean, hardwarePresent: Boolean) {
    BiometricRow(
        name = stringResource(if (face) BiometricWrap.FACE_ROW else BiometricWrap.FINGERPRINT_ROW),
        icon = if (face) R.drawable.ic_face else R.drawable.ic_fingerprint,
        line = stringResource(BiometricWrap.unavailableLine(face, hardwarePresent)),
        checked = false,
        greyed = true,
    )
}

/**
 * One row: glyph, name, its line, and a switch where it has one.
 *
 * [greyed] is `unavailable-capability-states`' unavailable form: the name drops to
 * `OnSurfaceVariant`, the switch takes Material's disabled colours, and the row is one
 * merged node marked disabled, so TalkBack reads "name, line, disabled". The line stays
 * at full `OnSurfaceVariant` — the reason is the one thing on the row that has to be
 * read, and disabled alpha would drop it under 4.5:1. Lines wrap; `ListItem` grows.
 */
@Composable
private fun BiometricRow(
    name: String,
    icon: Int,
    line: String,
    checked: Boolean?,
    greyed: Boolean,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(name, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text(line, style = MaterialTheme.typography.bodyMedium) },
        leadingContent = {
            Icon(
                painter = painterResource(icon),
                // The name carries the meaning; the glyph repeats it.
                contentDescription = null,
            )
        },
        trailingContent = checked?.let { on ->
            { Switch(checked = on, onCheckedChange = null, enabled = !greyed) }
        },
        colors = ListItemDefaults.colors(
            containerColor = VaultColors.Surface,
            headlineColor = if (greyed) VaultColors.OnSurfaceVariant else VaultColors.OnSurface,
            supportingColor = VaultColors.OnSurfaceVariant,
            leadingIconColor = VaultColors.OnSurfaceVariant,
        ),
        modifier = modifier
            .heightIn(min = Spacing.touchTarget)
            .then(if (greyed) Modifier.semantics(mergeDescendants = true) { disabled() } else Modifier),
    )
}
