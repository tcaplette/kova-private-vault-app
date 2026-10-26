package com.app.vault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.intl.LocaleList as ComposeLocaleList
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.app.vault.AppLanguage
import com.app.vault.BiometricWrap
import com.app.vault.Capture
import com.app.vault.PassphraseStrength
import com.app.vault.R
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import com.app.vault.ui.theme.VaultMono

/**
 * The eight first-run steps, and the welcome before them —
 * `first-run-onboarding`.
 *
 * **These replace the Settings screens the flow used to host.** Reusing those was
 * the mistake: they are reference material written for someone who went looking,
 * dense and neutral, and shown in sequence they read as a settings menu with a
 * counter bolted on. Settings keeps them unchanged; first run gets its own voice.
 *
 * Every step is the same shape — one promise, one supporting sentence, the facts
 * that bear on the decision, then Continue — because a flow whose screens share a
 * shape is one the eye stops having to re-parse.
 *
 * **The prose is written for someone who has never seen this app.** The settings
 * copy explains mechanisms to a user who already knows what a decoy is. These say
 * what the thing is for, what it costs, and what it cannot do, in that order.
 *
 * Passphrase input is [PassphraseField] everywhere, never a hand-rolled
 * `OutlinedTextField`: there is one reveal control and one set of keyboard flags
 * in this app, defined once.
 */

// --- the welcome, before step 1 ---------------------------------------------

/**
 * What this app is, before anyone is asked to commit to it.
 *
 * **Not a ninth step, and not counted as one.** The named flow is eight steps and
 * stays eight. This is the door, and its job is that nobody meets a passphrase
 * field as the first thing they ever see.
 *
 * Drawn **outside** [OnboardingFrame]: inside it, the counter read "Step 1 of 8"
 * on a screen that is not one of the eight. Its own scaffold is also what insets
 * it, so nothing here names an inset modifier.
 *
 * **It carries the app's language** — `welcome-language-choice`. Someone who cannot
 * read the language first run opened in has no other way out before the eight steps
 * end. The control sits in the top-end corner, where Android puts a setting of the
 * screen rather than its task, and **outside the scroll**: at a large font scale the
 * welcome scrolls, which is exactly when its text is longest and the control is most
 * needed. It names the language on screen, in that language's own script — not
 * "System default", which in a script the reader cannot read tells them nothing.
 */
@Composable
fun OnboardingWelcomeScreen(onBegin: () -> Unit, onRestore: () -> Unit, onLanguage: () -> Unit) {
    Scaffold(
        containerColor = VaultColors.Surface,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            WelcomeLanguage(
                onClick = onLanguage,
                modifier = Modifier.align(Alignment.End).padding(top = Spacing.sm, end = Spacing.md),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.xl),
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(VaultColors.Primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lock),
                        contentDescription = null,
                        tint = VaultColors.Primary,
                        modifier = Modifier.size(32.dp),
                    )
                }

                Spacer(Modifier.height(Spacing.xl))

                Text(
                    text = stringResource(R.string.onboarding_welcome_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = VaultColors.OnSurface,
                )

                Spacer(Modifier.height(Spacing.md))

                Text(
                    text = stringResource(R.string.onboarding_welcome_lead),
                    style = MaterialTheme.typography.bodyLarge,
                    color = VaultColors.OnSurfaceVariant,
                )

                Spacer(Modifier.height(Spacing.lg))

                Text(
                    // "You can skip any of them" named a control that no longer exists,
                    // and named it before the user had seen a single step. What is true
                    // is the promise underneath it: none of the eight are required.
                    text = stringResource(R.string.onboarding_welcome_steps),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurfaceVariant,
                )

                Spacer(Modifier.height(Spacing.xxl))

                Button(
                    onClick = onBegin,
                    modifier = Modifier.fillMaxWidth().height(Spacing.touchTarget),
                ) {
                    Text(stringResource(R.string.onboarding_get_started), style = MaterialTheme.typography.labelLarge)
                }

                TextButton(
                    onClick = onRestore,
                    modifier = Modifier.fillMaxWidth().height(Spacing.touchTarget),
                ) {
                    Text(stringResource(R.string.onboarding_have_backup), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * The welcome's language control: the globe, the shown language's own name, a chevron
 * that says it opens a choice. The name carries its own locale, as on [LanguageSheet],
 * so 日本語 is drawn in Japanese forms. Spoken as the drawer row is — "Language · English"
 * — so a screen reader says what the control is for, not just a language's name.
 */
@Composable
private fun WelcomeLanguage(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shown = remember(LocalConfiguration.current) {
        AppLanguage.current(context) ?: AppLanguage.systemResolved(context)
    }
    val description = stringResource(R.string.drawer_language, shown.ownName)
    TextButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = Spacing.touchTarget)
            .semantics { contentDescription = description },
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_language),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(localeList = ComposeLocaleList(shown.tag))) { append(shown.ownName) }
            },
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.width(Spacing.xs))
        Icon(
            painter = painterResource(R.drawable.ic_expand_more),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
    }
}

// --- step 1: passphrase -----------------------------------------------------

/**
 * The passphrase, as step 1 of 8.
 *
 * Separate from [VaultSetup], which still serves recovery and second-vault
 * creation. This one is inside the frame, so the counter runs from the first step
 * rather than appearing at "Step 3 of 8" already a third full.
 */
@Composable
fun PassphraseStepScreen(
    onCreate: (ByteArray) -> Unit,
    busy: Boolean,
    progress: Pair<Int, Int>?,
    /** Why the last attempt did not produce a vault. Nothing here is silent. */
    problem: String? = null,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var localProblem by remember { mutableStateOf<String?>(null) }
    val shown = localProblem ?: problem

    val verdict = rememberPassphraseVerdict(passphrase)
    val matches = passphrase.isNotEmpty() && passphrase == confirm
    val ready = verdict.clears && matches && !busy

    val tooWeak = PassphraseTooWeak
    val mismatch = stringResource(R.string.passphrase_mismatch)
    NoClipboard {
        OnboardingScaffold(
            title = stringResource(R.string.setup_title),
            lead = stringResource(R.string.onboarding_passphrase_lead),
            icon = R.drawable.ic_lock,
            onContinue = {
                val bytes = passphrase.toByteArray(Charsets.UTF_8)
                localProblem = when {
                    // Re-checked on the click rather than trusting the meter's verdict,
                    // which is debounced and may be a keystroke behind.
                    !PassphraseStrength.clears(passphrase) -> tooWeak
                    passphrase != confirm -> mismatch
                    else -> null
                }
                if (localProblem == null) {
                    passphrase = ""
                    confirm = ""
                    onCreate(bytes)
                } else {
                    bytes.fill(0)
                }
            },
            continueLabel = stringResource(R.string.setup_create),
            continueEnabled = ready,
        ) {
            if (busy) {
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
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
                )
                return@OnboardingScaffold
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

            // Positive confirmation only, and live. A passphrase that cannot be
            // reset is worth being sure about, and the only other signal is a
            // Continue that silently refuses to enable. The mismatch case is
            // deliberately not reported per keystroke — an error at character two
            // of a thirty-character phrase is noise, and the submit path still
            // names a mismatch if one survives.
            if (matches) {
                Spacer(Modifier.height(Spacing.md))
                PassphraseMatchLine()
            }

            shown?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.Error,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }

            Spacer(Modifier.height(Spacing.lg))

            Fact(
                stringResource(R.string.onboarding_fact_no_reset),
                stringResource(R.string.onboarding_fact_no_reset_body)
            )
            Fact(
                stringResource(R.string.onboarding_fact_kit_next),
                stringResource(R.string.onboarding_fact_kit_next_body)
            )
        }
    }
}

// --- step 2: recovery kit ---------------------------------------------------

/**
 * The 24 words, as step 2 of 8.
 *
 * The typed acknowledgement and the print action are unchanged from
 * [RecoveryKitScreen] — they are the safety property and this screen does not get
 * to soften them. What differs is the frame, the voice, and that Continue is the
 * primary action rather than a button beside Print.
 */
@Composable
fun RecoveryKitStepScreen(
    words: List<String>,
    onPrint: () -> Unit,
    onContinue: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    // `app-languages`: the phrase in the app's language, and the comparison reads the same one.
    val phrase = stringResource(ACKNOWLEDGEMENT)
    val acknowledged = typed.trim() == phrase

    OnboardingScaffold(
        title = stringResource(R.string.onboarding_kit_title),
        lead = stringResource(R.string.onboarding_kit_lead),
        icon = R.drawable.ic_vpn_key,
        onContinue = onContinue,
        continueEnabled = acknowledged,
        secondaryLabel = stringResource(R.string.kit_print),
        onSecondary = onPrint,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Spacing.md))
                .background(VaultColors.SurfaceContainer)
                .padding(Spacing.lg),
        ) {
            words.chunked(2).forEachIndexed { row, pair ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    pair.forEachIndexed { column, word ->
                        Text(
                            text = "${row * 2 + column + 1}. $word",
                            style = VaultMono.bodyLarge,
                            color = VaultColors.OnSurface,
                            modifier = Modifier.weight(1f).padding(vertical = Spacing.xs),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.lg))

        Fact(
            stringResource(R.string.onboarding_fact_anyone),
            stringResource(R.string.onboarding_fact_anyone_body)
        )
        Fact(
            stringResource(R.string.onboarding_fact_backup_words),
            stringResource(R.string.onboarding_fact_backup_words_body)
        )

        Text(
            text = stringResource(R.string.onboarding_kit_type, phrase),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(bottom = Spacing.sm),
        )
        // A plain field, deliberately: this is an acknowledgement, not a secret,
        // and masking it would hide the one word the user has to get right.
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// --- step 3: biometric unlock -----------------------------------------------

/**
 * The biometric step — `first-run-onboarding`, `biometric-unlock-screen` D7.
 *
 * **The Biometric unlock screen's rows and facts, in the flow's frame.** The same
 * [BiometricRows] and [BiometricFacts], so the step and the screen cannot say different
 * things about one device. The rows are the step's work, as the capture step's switches
 * are: there is no secondary button and no status line, because the live row's switch
 * and its line already say whether it is on.
 *
 * **The title is fixed**, "Biometric unlock" on every device and in every state — a
 * header that changed by device would look broken — with the screen's lead and the
 * generic biometric glyph. The rows below it name the face and the fingerprint.
 *
 * The forward action reads "Not now" while there is something to turn on (Off,
 * NotSetUp) and "Continue" otherwise: on, or nothing this device can do now, because
 * "Not now" promises a later that does not exist.
 *
 * The composable and `Step.Fingerprint` keep their old names: renaming them would churn
 * three test files for nothing a user can see, and `Step`'s order is read from
 * `ordinal`, so the rename buys no structure either.
 */
@Composable
fun FingerprintStepScreen(
    layout: BiometricLayout,
    state: BiometricState,
    hasFingerprint: Boolean,
    hasFace: Boolean,
    /** On and Off: the live row's switch. Off raises the enable prompt. */
    onToggle: () -> Unit,
    /** NotSetUp: Android's enrolment screen, and the prompt on the way back. */
    onSetUp: () -> Unit,
    onContinue: () -> Unit,
) {
    // Nothing left to put off: on, or nothing this device can do now.
    val settled = state != BiometricState.Off && !state.fixable
    OnboardingScaffold(
        title = stringResource(BiometricWrap.NAME),
        lead = stringResource(BiometricWrap.LEAD),
        icon = R.drawable.ic_fingerprint,
        onContinue = onContinue,
        continueLabel = if (settled) stringResource(R.string.action_continue) else stringResource(R.string.action_not_now),
    ) {
        BiometricRows(layout, state, hasFingerprint, hasFace, onToggle, onSetUp)
        Spacer(Modifier.height(Spacing.lg))
        BiometricFacts(layout)
    }
}

// --- step 4: intruder capture -----------------------------------------------

@Composable
fun CaptureStepScreen(
    enabled: Set<Capture.Mode>,
    hasFrontCamera: Boolean,
    onEnable: (Capture.Mode) -> Unit,
    onDisable: (Capture.Mode) -> Unit,
    problem: String?,
    onContinue: () -> Unit,
    /** `per-vault-intruder-capture` D9. Android may reset the app's permissions if it goes unused. */
    autoResetRisk: Boolean = false,
    onKeepPermissions: () -> Unit = {},
) {
    OnboardingScaffold(
        title = stringResource(R.string.onboarding_capture_title),
        lead = stringResource(R.string.onboarding_capture_lead),
        icon = R.drawable.ic_photo_camera,
        onContinue = onContinue,
        continueLabel = if (enabled.isNotEmpty()) stringResource(R.string.action_continue) else stringResource(R.string.action_not_now),
    ) {
        Fact(
            stringResource(R.string.onboarding_fact_failed_unlock),
            stringResource(R.string.onboarding_fact_failed_unlock_body)
        )
        Fact(
            stringResource(R.string.onboarding_fact_permission),
            stringResource(R.string.onboarding_fact_permission_body)
        )

        Spacer(Modifier.height(Spacing.sm))

        CaptureModeRows(enabled, hasFrontCamera, onEnable, onDisable, autoResetRisk, onKeepPermissions, problem)
    }
}

/**
 * The three mode rows, the auto-reset banner and the problem line — first run's capture step and
 * the vault flows' (`new-vault-setup-flow`, D4) draw this one piece, so the two cannot say
 * different things about one device.
 */
@Composable
internal fun CaptureModeRows(
    enabled: Set<Capture.Mode>,
    hasFrontCamera: Boolean,
    onEnable: (Capture.Mode) -> Unit,
    onDisable: (Capture.Mode) -> Unit,
    /** `per-vault-intruder-capture` D9. Android may reset the app's permissions if it goes unused. */
    autoResetRisk: Boolean,
    onKeepPermissions: () -> Unit,
    problem: String?,
) {
    // Every mode is drawn. With no front camera, Photo and Video are drawn unusable
    // with the reason on each row — `unavailable-capability-states`. No sentence
    // below them: the two facts already sit above, and a third block would say it
    // twice.
    Capture.Mode.entries.forEach { mode ->
        val available = mode == Capture.Mode.AUDIO || hasFrontCamera
        ToggleRow(
            label = stringResource(
                when (mode) {
                    Capture.Mode.PHOTO -> R.string.capture_mode_photo
                    Capture.Mode.AUDIO -> R.string.capture_mode_audio
                    Capture.Mode.VIDEO -> R.string.capture_mode_video
                }
            ),
            supporting = stringResource(
                when {
                    !available -> NO_FRONT_CAMERA
                    mode == Capture.Mode.PHOTO -> R.string.capture_mode_photo_line
                    mode == Capture.Mode.AUDIO -> R.string.capture_mode_audio_line
                    else -> R.string.capture_mode_video_line
                }
            ),
            checked = mode in enabled,
            onChange = { on -> if (on) onEnable(mode) else onDisable(mode) },
            available = available,
        )
    }

    // D9. Once a mode is on, the one thing that would quietly stop it later — the timeline's
    // banner, said here first.
    if (enabled.isNotEmpty() && autoResetRisk) {
        Spacer(Modifier.height(Spacing.md))
        CaptureBanner(
            glyph = R.drawable.ic_info,
            tint = VaultColors.OnSurfaceVariant,
            text = stringResource(R.string.capture_auto_reset),
            action = stringResource(R.string.capture_keep_permissions),
            onAction = onKeepPermissions,
            inset = 0.dp,
        )
    }

    problem?.let {
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.Error,
        )
    }
}

// --- step 5: decoy ----------------------------------------------------------

@Composable
fun DecoyStepScreen(
    configured: Boolean,
    onSetUp: () -> Unit,
    onContinue: () -> Unit,
    /** `distribution-flavors`. False on the `github` build, which has no trial to start. */
    showsTrial: Boolean = true,
) {
    OnboardingScaffold(
        title = stringResource(R.string.onboarding_decoy_title),
        lead = stringResource(R.string.onboarding_decoy_lead),
        icon = R.drawable.ic_visibility_off,
        onContinue = onContinue,
        continueLabel = if (configured) stringResource(R.string.action_continue) else stringResource(R.string.action_not_now),
        secondaryLabel = stringResource(R.string.drawer_set_up_decoy),
        onSecondary = onSetUp,
        consequential = true,
    ) {
        Fact(
            stringResource(R.string.decoy_fact_used),
            stringResource(R.string.onboarding_fact_used_body)
        )
        Fact(
            stringResource(R.string.onboarding_fact_choose),
            stringResource(R.string.onboarding_fact_choose_body)
        )
        if (showsTrial) {
            Fact(
                stringResource(R.string.onboarding_fact_trial),
                stringResource(R.string.onboarding_fact_trial_body)
            )
        }
    }
}

// --- step 6: backup ---------------------------------------------------------

/**
 * `backup-screen-status-first` D13. The Backup screen's parts, in the flow's frame:
 * the status in its compact form (the step's own glyph already heads the screen),
 * one caption, the "How backup works" row, and the same turn-on sheet before the
 * picker. The five blocks of explanation it used to open on are in that sheet now.
 *
 * The forward label is the flow's contract, not this step's: "Not now" until a
 * folder is chosen, "Continue" once one is.
 */
@Composable
fun BackupStepScreen(
    status: BackupStatus,
    folderName: String?,
    storeBytes: Long,
    /** A refusal from the destination just chosen. */
    problem: String?,
    /** `backup-to-cloud-apps` D1. Read when the sheet opens. */
    destinations: () -> List<com.app.vault.CloudMirror.Destination>,
    onChooseDestination: (com.app.vault.CloudMirror.Destination) -> Unit,
    onContinue: () -> Unit,
) {
    var turningOn by rememberSaveable { mutableStateOf(false) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var explaining by rememberSaveable { mutableStateOf(false) }
    val on = status != BackupStatus.Off

    OnboardingScaffold(
        title = stringResource(R.string.onboarding_backup_title),
        lead = stringResource(R.string.onboarding_backup_lead),
        icon = R.drawable.ic_cloud_upload,
        onContinue = onContinue,
        continueLabel = if (on) stringResource(R.string.action_continue) else stringResource(R.string.action_not_now),
        secondaryLabel = stringResource(if (on) R.string.backup_choose_where else R.string.backup_turn_on),
        onSecondary = if (on) ({ choosing = true }) else ({ turningOn = true }),
    ) {
        BackupStatusBlock(status, storeBytes, folderName, compact = true)
        problem?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.Error,
                modifier = Modifier.padding(top = Spacing.md),
            )
        }
        Text(
            text = backupCaption(on = false),
            style = MaterialTheme.typography.bodySmall,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.sm),
        )
        BackupRow(
            icon = R.drawable.ic_info,
            label = stringResource(R.string.backup_how_it_works),
            supporting = null,
            onClick = { explaining = true },
        )
    }

    if (turningOn || choosing) {
        BackupTurnOnSheet(
            showPoints = turningOn,
            destinations = destinations,
            onChoose = { turningOn = false; choosing = false; onChooseDestination(it) },
            onDismiss = { turningOn = false; choosing = false },
        )
    }
    if (explaining) BackupExplainedSheet(onDismiss = { explaining = false })
}

// --- step 7: panic ----------------------------------------------------------

@Composable
fun PanicStepScreen(
    enabled: Boolean,
    onSetUp: () -> Unit,
    backupConfigured: Boolean,
    onContinue: () -> Unit,
) {
    OnboardingScaffold(
        title = stringResource(R.string.onboarding_panic_title),
        lead = stringResource(R.string.onboarding_panic_lead),
        icon = R.drawable.ic_vpn_key,
        onContinue = onContinue,
        continueLabel = if (enabled) stringResource(R.string.action_continue) else stringResource(R.string.action_not_now),
        secondaryLabel = if (enabled) null else stringResource(R.string.onboarding_panic_set_up),
        onSecondary = if (enabled) null else onSetUp,
        consequential = true,
    ) {
        Fact(
            stringResource(R.string.onboarding_fact_ordinary),
            stringResource(R.string.onboarding_fact_ordinary_body)
        )
        Fact(
            stringResource(R.string.panic_fact_way_in),
            stringResource(R.string.onboarding_fact_way_in_body)
        )
        if (backupConfigured) {
            Fact(
                stringResource(R.string.panic_fact_backup),
                stringResource(R.string.panic_fact_backup_body)
            )
        }
        if (enabled) {
            Spacer(Modifier.height(Spacing.md))
            StatusLine(stringResource(R.string.panic_status_on))
        }
    }
}

// --- step 8: app identity ---------------------------------------------------

@Composable
fun IdentityStepScreen(onChoose: () -> Unit, onContinue: () -> Unit) {
    OnboardingScaffold(
        title = stringResource(R.string.onboarding_identity_title),
        lead = stringResource(R.string.onboarding_identity_lead),
        icon = R.drawable.ic_apps,
        onContinue = onContinue,
        // The last step, so Continue says where it goes.
        continueLabel = stringResource(R.string.onboarding_finish),
        secondaryLabel = stringResource(R.string.onboarding_choose_identity),
        onSecondary = onChoose,
    ) {
        Fact(
            stringResource(R.string.onboarding_fact_disguise),
            stringResource(R.string.onboarding_fact_disguise_body)
        )
        Fact(
            stringResource(R.string.onboarding_fact_hides),
            stringResource(R.string.onboarding_fact_hides_body)
        )
        Fact(
            stringResource(R.string.onboarding_fact_rehearse),
            stringResource(R.string.onboarding_fact_rehearse_body)
        )
    }
}

// --- shared pieces ----------------------------------------------------------

/**
 * One fact: what it is, then what it means.
 *
 * A heading and a sentence rather than a bullet, because these are the things the
 * decision turns on and a bullet list flattens them into scenery. The dot is a
 * mark, never the carrier — meaning lives in the words.
 */
@Composable
internal fun ColumnScope.Fact(heading: String, body: String) {
    Row(modifier = Modifier.padding(bottom = Spacing.lg)) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp, end = Spacing.md)
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(VaultColors.OutlineVariant),
        )
        Column {
            Text(
                text = heading,
                style = MaterialTheme.typography.titleSmall,
                color = VaultColors.OnSurface,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
            )
        }
    }
}

/**
 * A settled state, said plainly. Icon and words — never colour carrying meaning.
 *
 * [icon] is the check by default; `panic-phrase`'s screen also states an *off* state,
 * which a check would misreport.
 */
@Composable
internal fun StatusLine(text: String, icon: Int = R.drawable.ic_check_circle) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Spacing.md))
            .background(VaultColors.SurfaceContainer)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = VaultColors.Primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(Spacing.md))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurface,
        )
    }
}

/** The capture step's reason on Photo and Video, with no front camera. */
internal val NO_FRONT_CAMERA = R.string.capture_no_front_camera

/**
 * A mode the user turns on. Label always present; the switch is not the label.
 *
 * Not [available] is `unavailable-capability-states`' form, the drawer row's: the switch
 * greyed out and off, the name in `OnSurfaceVariant`, [supporting] as the reason in
 * `bodyMedium` at full alpha, one merged node marked disabled. A minimum height rather
 * than a fixed one, so a reason that wraps at a large font scale grows the row instead of
 * being clipped.
 */
@Composable
private fun ToggleRow(
    label: String,
    supporting: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    available: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget + Spacing.md)
            .padding(vertical = Spacing.xs)
            .then(if (available) Modifier else Modifier.semantics(mergeDescendants = true) { disabled() }),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (available) VaultColors.OnSurface else VaultColors.OnSurfaceVariant,
            )
            Text(
                text = supporting,
                style = if (available) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
            )
        }
        if (available) {
            Switch(checked = checked, onCheckedChange = onChange)
        } else {
            Switch(checked = false, onCheckedChange = null, enabled = false)
        }
    }
}
