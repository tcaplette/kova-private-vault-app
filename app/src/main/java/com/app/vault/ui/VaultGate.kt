package com.app.vault.ui

import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
import com.app.vault.BiometricWrap
import com.app.vault.PassphraseStrength
import com.app.vault.R
import com.app.vault.VaultAppearance
import com.app.vault.VaultKeyring
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * The two screens that stand between the app and everything else — P1-8.5.
 *
 * Built to `DESIGN.md`: every colour, size and spacing value comes from the
 * theme, and neither screen declares one of its own.
 */

/**
 * A text toolbar that shows nothing — P1-10.
 *
 * A long press on a passphrase field otherwise offers Copy, Cut, Select all and
 * Paste, and Copy on a passphrase field puts the vault's only secret on a
 * clipboard every other app on the device can read. Nothing in this app needs
 * clipboard access to a secret, so the toolbar is given no way to appear.
 *
 * `status` stays `Hidden` and `showMenu` does nothing: selection itself still
 * works, so the field behaves normally for editing, but there is no action to
 * invoke on the selection.
 *
 * **What this does not set, stated rather than implied.** These fields do not set
 * `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` — the note editor does, through
 * `InterceptPlatformTextInput` (`notes-settings`). They rely on
 * `KeyboardType.Password` plus `autoCorrectEnabled = false`, which is the
 * platform's own signal that a field must not be learned from, and which every
 * IME honouring the contract already treats as no-personalisation.
 */
private object NoTextToolbar : TextToolbar {
    override val status: TextToolbarStatus = TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = Unit

    override fun hide() = Unit
}

/**
 * Wraps a screen that accepts a secret, so nothing typed into it can be copied.
 *
 * Applied at the screen rather than at each field: a field added later inherits
 * it, and forgetting to opt in is exactly how this kind of protection rots.
 */
@Composable
internal fun NoClipboard(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTextToolbar provides NoTextToolbar, content = content)
}

/**
 * Shown when no vault exists — `first-vault-naming` D8.
 *
 * Two stages: New vault's Name and look, then the passphrase fields. The name and look are
 * the host's, so going back from the fields finds them as they were left; the fields
 * themselves are `remember`ed here and empty on return, which is right for a secret.
 */
@Composable
fun VaultSetup(
    onCreate: (ByteArray, (Int, Int) -> Unit) -> Unit,
    busy: Boolean,
    progress: Pair<Int, Int>?,
    name: String,
    onName: (String) -> Unit,
    appearance: VaultAppearance,
    onAppearance: (VaultAppearance) -> Unit,
    /** Name and look's refusal, under its field. */
    nameError: String?,
    /** Runs the name's checks; true moves on to the passphrase fields. */
    onNameContinue: () -> Boolean,
    modifier: Modifier = Modifier,
    /** Why the last attempt did not produce a vault, said on the stage that tried. */
    problem: String? = null,
    /**
     * `cloud-mirror`. Restoring a backup, from the one screen a replacement device
     * actually lands on.
     *
     * This is the *setup* screen and not the lock screen, because a device whose
     * data was cleared has no slot file, so `vaultExists()` is false and the lock
     * screen is never drawn. Offering restore there would put it on the one screen
     * the user in question cannot reach.
     *
     * Always present: whether an install *has* a
     * backup to restore is not knowable from disk, and hiding the row would answer
     * that question for anyone holding the device.
     */
    onRestoreBackup: (() -> Unit)? = null,
    /** `switch-builds-without-losing-vaults` D4. What a `.vault` file chosen on restore brought in. */
    restored: String? = null,
) {
    // Plain `remember`, like the name it pairs with: nothing about this screen is saved.
    var naming by remember { mutableStateOf(true) }

    if (naming) {
        // The first stage carries restore, so a replacement device still finds it on the
        // first screen it lands on. No bar: there is nothing behind this, and the step's
        // own heading names it — the bar's "Choose a passphrase" belongs to the next stage.
        VaultScaffold(
            title = stringResource(R.string.vault_flow_name_title),
            modifier = modifier,
            scrollable = false,
            topBar = false,
        ) {
            NameStep(
                name = name,
                onName = onName,
                appearance = appearance,
                onAppearance = onAppearance,
                accepts = VaultKeyring::fits,
                error = nameError,
                onContinue = { if (onNameContinue()) naming = false },
                secondaryLabel = onRestoreBackup?.let { stringResource(R.string.setup_restore_backup) },
                onSecondary = onRestoreBackup,
                restored = restored,
            )
        }
        return
    }

    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var localProblem by remember { mutableStateOf<String?>(null) }
    val shown = localProblem ?: problem
    val verdict = rememberPassphraseVerdict(passphrase)

    // Back returns to Name and look, with the name and look kept. Not while the vault is
    // being written: it cannot be left half made.
    BackHandler(enabled = !busy) { naming = true }

    // The content used to be vertically centred; P2-6 made it scroll instead, which is
    // what keeps the passphrase field reachable once the keyboard is up — centring
    // a screen that owns a text field is a layout arguing with the IME.
    NoClipboard {
    VaultScaffold(
        title = stringResource(R.string.setup_title),
        modifier = modifier,
        onBack = { if (!busy) naming = true },
        backEnabled = !busy,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            // P1-12 built the kit, so the old sentence — "it cannot be reset or
            // recovered" — is now false and has to change. What must not soften is
            // the ending: the kit is the *only* other way in, and losing both is
            // still permanent.
            text = stringResource(R.string.setup_lead),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xl),
        )

        if (busy) {
            // The chaff write is the wait. Determinate, because it has a known
            // total and a frozen first run reads as a broken app.
            val (done, total) = progress ?: (0 to 1)
            Text(
                text = stringResource(R.string.setup_preparing),
                style = MaterialTheme.typography.titleMedium,
                color = VaultColors.OnSurface,
            )
            LinearProgressIndicator(
                progress = { if (total > 0) done.toFloat() / total else 0f },
                color = VaultColors.Primary,
                trackColor = VaultColors.SurfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md),
            )
            return@VaultScaffold
        }

        PassphraseField(
            value = passphrase,
            onValueChange = { passphrase = it; localProblem = null },
            label = stringResource(R.string.passphrase_label),
            imeAction = ImeAction.Next,
            supporting = PassphraseRequirement,
        )
        PassphraseStrengthMeter(
            verdict = verdict,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        PassphraseField(
            value = confirm,
            onValueChange = { confirm = it; localProblem = null },
            label = stringResource(R.string.passphrase_again_label),
            imeAction = ImeAction.Done,
            modifier = Modifier.padding(top = Spacing.md),
        )

        // Specific on purpose. Nothing secret exists yet, so this is the one
        // screen where naming the problem costs nothing.
        shown?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.Error,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }

        val tooWeak = PassphraseTooWeak
        val mismatch = stringResource(R.string.passphrase_mismatch)
        Button(
            onClick = {
                val bytes = passphrase.toByteArray(Charsets.UTF_8)
                localProblem = when {
                    // Re-checked here rather than trusting the meter, which is debounced.
                    !PassphraseStrength.clears(passphrase) -> tooWeak
                    passphrase != confirm -> mismatch
                    else -> null
                }
                if (localProblem == null) {
                    passphrase = ""
                    confirm = ""
                    onCreate(bytes) { _, _ -> }
                } else {
                    bytes.fill(0)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xl),
        ) {
            Text(stringResource(R.string.setup_create), style = MaterialTheme.typography.labelLarge)
        }
    }
    }
}

/**
 * Shown when a vault exists and no key is held — `unlock-screen-redesign`.
 *
 * The conventional login layout: a brand mark, a heading and the field at the top;
 * the actions pinned to the bottom, where the scaffold's `safeDrawing` inset puts
 * them directly above the keyboard when it is up and above the gesture bar when it
 * is not. The upper column scrolls, so at the largest font scale with the keyboard
 * up nothing is covered or pushed off screen.
 */
@Composable
fun VaultUnlockScreen(
    onUnlock: (ByteArray) -> Unit,
    busy: Boolean,
    failed: Boolean,
    /** P1-9. Null when there is no wrap to open, which is the default install. */
    onBiometric: (() -> Unit)? = null,
    /**
     * `biometric-naming`. What the platform calls the method this control raises —
     * `getButtonLabel()`, the narrow one, because this control is about to show the
     * prompt. Read in the activity and handed down; never fetched here.
     */
    biometricLabel: String? = null,
    /**
     * `unlock-screen-redesign`, D9. True when the device's biometric layout is
     * face-only, which puts `ic_face` on the control; `ic_fingerprint` otherwise.
     * Chosen from the layout and never from [biometricLabel].
     */
    biometricFace: Boolean = false,
    /** P1-9. Said in text when the wrap has just been thrown away. */
    biometricNotice: String? = null,
    /**
     * P1-10. What the app can see about this device. Shown **above** the field,
     * because the only moment a warning can change what the user does is before
     * they type their passphrase into a device they now know is watched.
     */
    warnings: List<String> = emptyList(),
    /** P1-12. Opens the 24-word recovery screen. */
    onRecover: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /**
     * `recents-shows-identity` D3. Drawn as the recent-apps card's picture: the screen at
     * rest. No focus, so it never raises the keyboard or pulls focus from the live field; no
     * submit; the biometric control drawn but disabled; no warning or notice lines, which are
     * about this session's device state.
     */
    still: Boolean = false,
) {
    var passphrase by remember { mutableStateOf("") }
    val submit = {
        if (!still && !busy && passphrase.isNotEmpty()) {
            val bytes = passphrase.toByteArray(Charsets.UTF_8)
            passphrase = ""
            onUnlock(bytes)
        }
    }

    // D6. The field takes focus — and so raises the keyboard — when the screen appears
    // and again the moment an attempt ends. Nothing is keyed on window focus: the field
    // keeps its focus while a biometric prompt or a screen-off takes the window away,
    // and the platform brings the keyboard back with the window. Requesting focus at
    // that instant as well raced the window transition — the keyboard's animation was
    // cancelled and the screen never settled.
    val focus = remember { FocusRequester() }
    LaunchedEffect(busy, still) {
        if (!busy && !still) {
            // One frame first: `Scaffold` composes its content during measure, after
            // this effect has started, so the field's focus target is not attached yet
            // and an immediate request is refused.
            withFrameNanos { }
            focus.requestFocus()
        }
    }

    // D7. Identical whether the passphrase was wrong or no vault occupies any slot.
    // It says an attempt failed and nothing whatsoever about why — a message that
    // distinguished the two would be an occupancy oracle. The shake and the haptic
    // begin once the attempt has ended, so neither touches the indicator (D8).
    val showFailure = failed && !busy
    val view = LocalView.current
    val context = LocalContext.current
    val reduced = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val shake = remember { Animatable(0f) }
    // Only on the transition into failure: coming back from the recovery screen with
    // the last attempt's failure still showing replays nothing.
    var lastFailure by remember { mutableStateOf(showFailure) }
    LaunchedEffect(showFailure) {
        val fresh = showFailure && !lastFailure
        lastFailure = showFailure
        if (!fresh) return@LaunchedEffect
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        if (!reduced) {
            shake.animateTo(0f, keyframes {
                durationMillis = 240
                -12f at 40
                12f at 100
                -8f at 160
                8f at 200
            })
        }
    }

    // No back affordance: this is where the app starts when a vault exists, and
    // there is nothing behind it. Back leaves the activity, which is correct for a
    // lock screen.
    NoClipboard {
    VaultScaffold(
        title = stringResource(R.string.unlock_welcome),
        modifier = modifier,
        topBar = false,
        scrollable = false,
    ) {
        val scroll = rememberScrollState()
        val primary = VaultColors.Primary
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // D3's glow, drawn here rather than on the mark so the scroll
                // container's clip does not cut it off in a hard line at the top of
                // the screen. Centred on the mark, and moved with the scroll so it
                // stays behind it. Static: nothing on it animates.
                .drawBehind {
                    val radius = 160.dp.toPx()
                    val centre = Offset(
                        size.width / 2,
                        (Spacing.xxxl + Spacing.lockMark / 2).toPx() - scroll.value,
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(primary.copy(alpha = 0.12f), Color.Transparent),
                            center = centre,
                            radius = radius,
                        ),
                        radius = radius,
                        center = centre,
                    )
                }
                .verticalScroll(scroll)
                .padding(horizontal = Spacing.screenInset)
                .padding(top = Spacing.xxxl),
        ) {
            // D3. Decorative — the heading below names the screen.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(Spacing.lockMark)
                    .background(primary.copy(alpha = 0.16f), CircleShape),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lock),
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(32.dp),
                )
            }

            Text(
                text = stringResource(R.string.unlock_welcome),
                style = MaterialTheme.typography.headlineMedium,
                color = VaultColors.OnSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = Spacing.xl)
                    .semantics { heading() },
            )

            Text(
                text = stringResource(R.string.unlock_lead),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.sm),
            )

            // One line per finding, named in words — DESIGN.md §6.4, so the screen
            // reads correctly with colour disregarded. Nothing is gated on these and
            // nothing is dismissible: they describe the device, not an error.
            if (!still) warnings.forEach { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.Error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }

            PassphraseField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = stringResource(R.string.passphrase_label),
                imeAction = ImeAction.Done,
                enabled = !busy,
                isError = showFailure,
                supporting = if (showFailure) stringResource(R.string.unlock_failed) else null,
                announce = true,
                onImeAction = submit,
                modifier = Modifier
                    .padding(top = Spacing.xl)
                    .offset { IntOffset(shake.value.dp.roundToPx(), 0) }
                    .let { if (still) it else it.focusRequester(focus) },
            )

            // P1-12, D10. Where every login screen puts it. Always offered — whether
            // an install has a kit is not knowable from disk, and hiding it would
            // answer that question.
            if (onRecover != null) {
                TextButton(
                    onClick = { if (!still) onRecover() },
                    enabled = !busy,
                    modifier = Modifier
                        .align(Alignment.End)
                        .heightIn(min = Spacing.touchTarget),
                ) {
                    Text(stringResource(R.string.unlock_forgot), style = MaterialTheme.typography.labelLarge)
                }
            }

            // P1-9: an enrolment change or a wiped key destroyed the wrap. It names
            // what happened and what still works, and it is not an error the user can
            // act on — the passphrase above is the action.
            biometricNotice?.takeIf { !still }?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Spacing.md),
                )
            }
        }

        // D4. Pinned: the scaffold's `safeDrawing` inset includes the IME, so this
        // sits directly above the keyboard when it is up — no `imePadding` here.
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenInset)
                .padding(top = Spacing.lg, bottom = Spacing.lg),
        ) {
            // D8. One indicator, inside the button, driven by *whether an attempt is
            // running* and never by its result (DESIGN.md 8.1): P1-3 made unlock
            // constant-time by construction, and an indicator that ended when the
            // answer arrived would hand that back. The label stays in place at zero
            // alpha so the button keeps its size, and the button keeps its enabled
            // colours — a disabled container would put the spinner at 38%.
            val busyLabel = stringResource(R.string.unlock_busy)
            Button(
                enabled = busy || passphrase.isNotEmpty(),
                onClick = submit,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { if (busy) stateDescription = busyLabel },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.unlock_action),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.alpha(if (busy) 0f else 1f),
                    )
                    if (busy) {
                        CircularProgressIndicator(
                            color = VaultColors.OnPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // D9. Secondary to the passphrase, and only ever a way to ask for the
            // prompt again — the automatic one has already fired by the time this
            // screen is idle. Nothing here re-fires it on its own: a prompt that
            // reappears every time it is dismissed is a screen the user cannot leave.
            if (onBiometric != null) {
                OutlinedButton(
                    onClick = onBiometric,
                    enabled = !busy && !still,
                    border = BorderStroke(1.dp, VaultColors.Outline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VaultColors.Primary),
                    // Full width, so a platform label longer than the button can never
                    // make this control wider than Unlock above it. The label wraps
                    // beside its glyph and the button grows in height —
                    // `biometric-naming`. Never truncated: the clipped word would be
                    // the one naming the method.
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(if (biometricFace) R.drawable.ic_face else R.drawable.ic_fingerprint),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = biometricLabel ?: stringResource(BiometricWrap.BUTTON_FALLBACK),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(start = Spacing.sm)
                            .weight(1f, fill = false),
                    )
                }
            }
        }
    }
    }
}
