package com.app.vault.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.app.vault.R
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * Every field in this app that takes a passphrase — one implementation.
 *
 * **There were thirteen of these, hand-rolled across six files.** Setup, unlock,
 * recovery, decoy creation, archive import, export re-authentication and the panic
 * enrolment list each built their own `OutlinedTextField` with the same four
 * arguments. Adding a reveal control to one of them would have made the app
 * inconsistent — a vault where one passphrase box can be checked and five cannot
 * is worse than one where none can, because the user cannot predict which is
 * which. Adding it to all thirteen by hand would have been thirteen chances to
 * diverge. So the field is a component, and the behaviour is defined once.
 *
 * **What it carries, and why each part is not optional:**
 *
 * - `KeyboardType.Password` and `autoCorrectEnabled = false` — the platform's own
 *   signal that a field must not be learned from or suggested into (P1-10). Every
 *   call site had these and none may lose them.
 * - A **reveal toggle**, hidden by default. A passphrase that cannot be reset is
 *   one somebody has to be certain they typed, and a confirm field only proves two
 *   blind entries agree — which is also what happens when the same typo is made
 *   twice. `FLAG_SECURE` already blocks screenshots, recents thumbnails and screen
 *   recording app-wide, so shoulder-surfing is the whole residual risk and it is
 *   the user's to judge in the moment. Hence a control, not a setting.
 * - The toggle's description **changes with its state**: one that announces itself
 *   identically in both positions tells a screen-reader user nothing.
 *
 * The reveal is per field and never persists — it dies with the composition, the
 * same way [NoClipboard] governs what can be lifted out of one.
 */
@Composable
fun PassphraseField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    enabled: Boolean = true,
    isError: Boolean = false,
    /**
     * Text under the field — helper text, or the refusal about this field, which then
     * takes the error colour through [isError]. `panic-phrase`: a refusal sits under the
     * field it concerns.
     */
    supporting: String? = null,
    /**
     * `recovery-own-slot-only`. Announces [supporting] to a screen reader when it changes —
     * a polite live region, for a refusal that appears after the user pressed something.
     */
    announce: Boolean = false,
    /** Runs on the keyboard's action key, for a field whose Done submits the form. */
    onImeAction: (() -> Unit)? = null,
) {
    var revealed by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        supportingText = supporting?.let {
            {
                Text(
                    it,
                    modifier = if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    else Modifier,
                )
            }
        },
        keyboardActions = onImeAction?.let { run -> KeyboardActions(onAny = { run() }) }
            ?: KeyboardActions.Default,
        visualTransformation =
            if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(
                onClick = { revealed = !revealed },
                // §7.1's floor, met by padding around the glyph rather than by
                // inflating it — the icon stays 24dp.
                modifier = Modifier.size(Spacing.touchTarget),
            ) {
                Icon(
                    painter = painterResource(
                        if (revealed) R.drawable.ic_visibility_off else R.drawable.ic_visibility
                    ),
                    // Fixed wording, not "Show $label": the labels include
                    // "Enter it again", and "Show Enter it again" is not a sentence
                    // a screen reader should have to say. Which field the control
                    // belongs to is carried by its position inside that field.
                    contentDescription = stringResource(if (revealed) R.string.passphrase_hide else R.string.passphrase_show),
                    tint = VaultColors.OnSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * That two entries agree, said the moment they do.
 *
 * Icon **and** words, never the tick alone: colour may not carry meaning by
 * itself, and a green check with no label is exactly that. The words also say
 * something the tick cannot — that this is about the two boxes matching, not about
 * the passphrase being any good.
 *
 * Positive only, and live. The mismatch case is deliberately absent: an error that
 * appears at character two of a thirty-character phrase is noise, and every screen
 * that uses this already names a mismatch when the form is submitted.
 */
@Composable
fun PassphraseMatchLine(text: String = stringResource(R.string.passphrase_match)) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_check_circle),
            contentDescription = null,
            tint = VaultColors.Primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(Spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurface,
        )
    }
}
