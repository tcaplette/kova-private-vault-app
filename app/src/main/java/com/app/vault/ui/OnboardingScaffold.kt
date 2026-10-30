package com.app.vault.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.app.vault.Onboarding
import com.app.vault.R
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * The shell every first-run step is drawn in — `first-run-onboarding`.
 *
 * **Deliberately not [VaultScaffold].** That wrapper exists for the app's working
 * surfaces. It is right for a settings screen someone went looking for and wrong
 * for the first screens a person ever sees.
 *
 * **Every step ends in one forward action, and that is not decoration.** The first
 * version of this file gave a step a primary action only while something was
 * *unset* — so a user who turned a capture mode on, or finished choosing a folder,
 * was left with "Skip" as the only control pointing forward, and Skip reads as
 * *discard what I just did*. There was no way to complete the flow at all.
 *
 * The fix at the time added a Continue beside the Skip, which left every step with
 * two controls that called the same function. So the Skip is gone and [continueLabel]
 * carries what it was there to say: "Not now" while the step's work is undone,
 * "Continue" once it is done. One control, and it states its own outcome.
 */
@Composable
fun OnboardingScaffold(
    /** The step's one-line promise. Large, and the first thing read. */
    title: String,
    /** One or two sentences under it. Never a paragraph — that is what [content] is for. */
    lead: String,
    /** The step's glyph. Present for recognition, never as the only carrier of meaning. */
    icon: Int,
    /** Moves to the next step. The primary action on every screen, always present. */
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * What the forward action says here, and it is load-bearing rather than
     * cosmetic: it is the only thing distinguishing *I did this step* from *I am
     * passing it*. "Not now" while the step's work is undone, "Continue" once it is
     * done, "Finish setup" on the last step, which ends the flow either way.
     */
    continueLabel: String = stringResource(R.string.action_continue),
    continueEnabled: Boolean = true,
    /**
     * `recovery-practice` D3/D4. The forward action is running: a progress ring before
     * [continueLabel], which then names the work ("Checking…"), and the button does not take a
     * second press.
     */
    continueBusy: Boolean = false,
    /**
     * A sentence under the forward action, announced politely — why the last press did not
     * finish, said where the press was made.
     */
    footnote: String? = null,
    /** The step's own work — enrol, choose, set up. Secondary to Continue. */
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    /**
     * Marks the step as consequential — the decoy and the panic credential. Tints
     * the glyph and the secondary action in the error role, because a step that can
     * destroy something must not look like one that turns on a fingerprint.
     */
    consequential: Boolean = false,
    /**
     * `new-vault-setup-flow`, D3. Drawn in place of the glyph tile when the step's picture is
     * the thing being made — the live vault badge. Null draws [icon] in its tile, as every
     * first-run step does.
     */
    hero: (@Composable () -> Unit)? = null,
    /** The title's modifier — the vault flows request accessibility focus on it (D10). */
    titleModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl),
        ) {
            Spacer(Modifier.height(Spacing.lg))

            if (hero != null) {
                hero()
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (consequential) VaultColors.Error.copy(alpha = 0.12f)
                            else VaultColors.Primary.copy(alpha = 0.12f)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(icon),
                        // The title carries the meaning; the glyph repeats it.
                        contentDescription = null,
                        tint = if (consequential) VaultColors.Error else VaultColors.Primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = VaultColors.OnSurface,
                modifier = titleModifier.semantics { heading() },
            )

            Spacer(Modifier.height(Spacing.sm))

            Text(
                text = lead,
                style = MaterialTheme.typography.bodyLarge,
                color = VaultColors.OnSurfaceVariant,
            )

            Spacer(Modifier.height(Spacing.lg))

            content()

            Spacer(Modifier.height(Spacing.lg))
        }

        // The action pair, pinned. Never scrolled off — a Continue the user has to
        // hunt for is the same bug as no Continue at all.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        ) {
            // A **minimum**, not a fixed height: at the largest font scale a fixed
            // 48dp clips a label that wraps. Every label here is short and sits at
            // 48dp at the default scale.
            if (secondaryLabel != null && onSecondary != null) {
                OutlinedButton(
                    onClick = onSecondary,
                    colors = if (consequential) {
                        ButtonDefaults.outlinedButtonColors(contentColor = VaultColors.Error)
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.touchTarget),
                ) {
                    Text(
                        text = secondaryLabel,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
            }

            Button(
                onClick = onContinue,
                enabled = continueEnabled && !continueBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.touchTarget),
            ) {
                if (continueBusy) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(
                    text = continueLabel,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
            }
            footnote?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.Error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.sm)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/**
 * Where you are, and the way back — `first-run-onboarding`.
 *
 * **There is no way out of the flow here.** Every user walks the eight steps; each
 * step's own forward action ("Not now" / "Continue") is how a step is declined, and
 * the last step's "Finish setup" is the only thing that ends the flow. The bar
 * holds the step count and, from the second drawn step after the kit, Back.
 *
 * **The count is centred on the bar, not placed between its neighbours.** Back
 * comes and goes between steps; a count laid out beside it would shift by half of
 * Back's width every time it did. The number is the one thing that changes from
 * step to step, so it is the one thing that must not move.
 *
 * **All eight steps are inside the frame.** The first version excluded the
 * passphrase and the kit on the grounds that a counter over the front door was
 * chrome. The result was a rail that first appeared reading "Step 3 of 8", already
 * a third full, on a flow the user thought had just started. If a step counts, it
 * counts from the first screen.
 *
 * **Everything is in the content slot, not in `topBar`/`bottomBar`.** Material's
 * `Scaffold` insets the content slot from `contentWindowInsets`, but a bar built
 * from a plain `Column` handles none of its own — which put the step counter under
 * the status bar and the actions under the gesture bar. One `padding(inner)` here
 * insets everything once, the same arrangement `VaultScaffold` uses, and it needs
 * no inset modifier of its own (`ScreenChromeTest` keeps those in one file).
 */
@Composable
fun OnboardingFrame(
    step: Onboarding.Step,
    onBack: (() -> Unit)?,
    /** False while the step's work is running: Back stays drawn, disabled, so the bar does not jump. */
    backEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val total = Onboarding.Step.entries.size
    val progress by animateFloatAsState(
        targetValue = step.position.toFloat() / total,
        // 240ms sits inside Material's 150–300ms band. Linear, because a progress
        // fill that eases is a progress fill lying about its own rate.
        animationSpec = tween(durationMillis = 240, easing = LinearEasing),
        label = "onboardingProgress",
    )

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

                Text(
                    text = stringResource(R.string.decoy_step, step.position, total),
                    style = MaterialTheme.typography.labelMedium,
                    color = VaultColors.OnSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            val stepLabel = stringResource(R.string.decoy_step, step.position, total)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xl)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(VaultColors.SurfaceContainerHigh)
                    // Announced as a value: a rail a screen reader cannot read is
                    // decoration, and the count beside it is the same fact twice.
                    .semantics { contentDescription = stepLabel },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(VaultColors.Primary),
                )
            }

            Box(modifier = Modifier.weight(1f)) { content() }
        }
    }
}
