package com.app.vault.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import com.app.vault.BiometricWrap
import kotlinx.coroutines.delay
import com.app.vault.R
import com.app.vault.ui.theme.Radius
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/** Which of the two practice screens is up — `recovery-practice` D3, D4. */
enum class PracticeStage { Check, NewPassphrase }

/**
 * Everything the two practice screens hold, kept by the activity — `recovery-practice`.
 *
 * Held outside the composition so Back from the new-passphrase screen finds the practice
 * screen in its wrong state, and so a lock can clear both at once ([clear]).
 */
class PracticeState {
    var stage by mutableStateOf(PracticeStage.Check)
    var entry by mutableStateOf(TextFieldValue(""))

    /** The last entry was wrong: the field's error line. Cleared by typing. */
    var wrong by mutableStateOf(false)

    /** Any entry in this check was wrong: the panel stays, and the step goes back to 0. */
    var hadWrong by mutableStateOf(false)
    var checking by mutableStateOf(false)

    /** Bumped on each wrong entry, so the field takes focus again even when already wrong. */
    var wrongCount by mutableIntStateOf(0)

    val draft = NewPassphrase()
    var saving by mutableStateOf(false)

    /** Under Set passphrase: the confirmation was cancelled or failed. */
    var confirmNeeded by mutableStateOf(false)

    /** The entry was wrong: select it all, so the next keystroke replaces it. */
    fun markWrong() {
        wrong = true
        hadWrong = true
        wrongCount++
        entry = entry.copy(selection = TextRange(0, entry.text.length))
    }

    /** Back to nothing typed — the end of the check, and a lock. */
    fun clear() {
        stage = PracticeStage.Check
        entry = TextFieldValue("")
        wrong = false
        hadWrong = false
        checking = false
        wrongCount = 0
        draft.clear()
        saving = false
        confirmNeeded = false
    }
}

/**
 * The frame both practice screens are drawn in: the safe-drawing insets, and a Back on the
 * bar where the screen has one — [OnboardingFrame]'s bar, with no step to count.
 */
@Composable
private fun PracticeFrame(onBack: (() -> Unit)?, backEnabled: Boolean, content: @Composable () -> Unit) {
    Scaffold(
        containerColor = VaultColors.Surface,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xl, vertical = Spacing.sm)
                    .height(Spacing.touchTarget),
            ) {
                if (onBack != null) {
                    TextButton(
                        onClick = onBack,
                        enabled = backEnabled,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .height(Spacing.touchTarget),
                    ) {
                        Text(
                            stringResource(R.string.action_back),
                            style = MaterialTheme.typography.labelLarge,
                            color = VaultColors.OnSurfaceVariant,
                        )
                    }
                }
            }
            content()
        }
    }
}

/**
 * Practice your passphrase — `recovery-practice` D3.
 *
 * Drawn after a fingerprint unlock and before the vault list, so the list never appears and
 * is then covered. Check is the one primary; Skip sits above it, outlined, and the system
 * Back is Skip too (the activity's `dismissTop`). A wrong entry is said in words under the
 * field, announced politely, and reveals the panel that offers a new passphrase — the user
 * is already inside the list, so they need no kit to choose one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PassphrasePracticeScreen(
    state: PracticeState,
    /** `BiometricWrap.practiceWords` for this device. */
    words: BiometricWrap.Companion.PracticeWords,
    onCheck: () -> Unit,
    onSkip: () -> Unit,
    onSetNew: () -> Unit,
) {
    val context = LocalContext.current
    val reduced = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val field = remember { FocusRequester() }
    // The panel appears below a field that keeps focus, so the keyboard stays up and the
    // panel can open out of sight. It is brought into view once it has expanded.
    val panel = remember { BringIntoViewRequester() }
    LaunchedEffect(state.wrongCount) {
        if (state.wrongCount > 0) {
            field.requestFocus()
            delay(if (reduced) 0L else PANEL_MS.toLong())
            panel.bringIntoView()
        }
    }
    val submit = { if (!state.checking && state.entry.text.isNotEmpty()) onCheck() }

    NoClipboard {
        PracticeFrame(onBack = null, backEnabled = true) {
            OnboardingScaffold(
                title = stringResource(R.string.practice_title),
                lead = stringResource(words.lead),
                icon = R.drawable.ic_shield_lock,
                onContinue = submit,
                continueLabel = stringResource(if (state.checking) R.string.practice_checking else R.string.practice_check),
                continueEnabled = state.entry.text.isNotEmpty(),
                continueBusy = state.checking,
                secondaryLabel = stringResource(R.string.practice_skip),
                onSecondary = { if (!state.checking) onSkip() },
            ) {
                PassphraseField(
                    value = state.entry,
                    onValueChange = {
                        if (it.text != state.entry.text) state.wrong = false
                        state.entry = it
                    },
                    label = stringResource(R.string.passphrase_label),
                    enabled = !state.checking,
                    isError = state.wrong,
                    supporting = if (state.wrong) stringResource(R.string.practice_wrong) else null,
                    announce = state.wrong,
                    onImeAction = submit,
                    modifier = Modifier.focusRequester(field),
                )
                AnimatedVisibility(
                    visible = state.hadWrong,
                    enter = if (reduced) EnterTransition.None
                    else fadeIn(tween(PANEL_MS)) + expandVertically(tween(PANEL_MS)),
                ) {
                    Column(
                        modifier = Modifier
                            .bringIntoViewRequester(panel)
                            .padding(top = Spacing.md)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.container))
                            .background(VaultColors.SurfaceContainer)
                            .padding(Spacing.lg),
                    ) {
                        Text(
                            text = stringResource(words.panel),
                            style = MaterialTheme.typography.bodyMedium,
                            color = VaultColors.OnSurface,
                        )
                        OutlinedButton(
                            onClick = onSetNew,
                            enabled = !state.checking,
                            modifier = Modifier
                                .padding(top = Spacing.md)
                                .fillMaxWidth()
                                .heightIn(min = Spacing.touchTarget),
                        ) {
                            Text(
                                text = stringResource(R.string.practice_set_new),
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * New passphrase — `recovery-practice` D4.
 *
 * The fields every new passphrase gets: the floor, the meter, the confirm entry and the match
 * line. Set passphrase raises a fresh biometric confirmation before anything is written; a
 * cancelled one keeps the fields and says why under the button. Back returns to the practice
 * screen as it was left.
 */
@Composable
fun PracticeNewPassphraseScreen(
    state: PracticeState,
    words: BiometricWrap.Companion.PracticeWords,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val tooWeak = PassphraseTooWeak
    val mismatch = stringResource(R.string.passphrase_mismatch)
    NoClipboard {
        PracticeFrame(onBack = onBack, backEnabled = !state.saving) {
            OnboardingScaffold(
                title = stringResource(R.string.practice_new_title),
                lead = stringResource(R.string.practice_new_lead),
                icon = R.drawable.ic_shield_lock,
                // The floor and the match first, key-free; only then the confirmation and the seal.
                onContinue = { if (!state.saving && state.draft.check(tooWeak, mismatch)) onSave() },
                continueLabel = stringResource(if (state.saving) R.string.practice_saving else R.string.kit_set_passphrase),
                continueBusy = state.saving,
                footnote = if (state.confirmNeeded && !state.saving) stringResource(words.confirm) else null,
            ) {
                NewPassphraseFields(state.draft, enabled = !state.saving)
            }
        }
    }
}

private const val PANEL_MS = 200
