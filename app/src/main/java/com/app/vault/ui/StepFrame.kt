package com.app.vault.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.app.vault.R
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * The shell a stepped flow reached from inside the app is drawn in — the decoy flow, New vault
 * and Add a passphrase (`new-vault-setup-flow`, D2).
 *
 * `OnboardingFrame`'s shape, for `OnboardingFrame`'s reasons: the counter and the rail say how
 * much is left, which is the whole reason several steps are cheaper than one screen carrying the
 * same work unordered. Everything sits in the content slot and one `padding(inner)` insets it
 * once — a `Column` built by hand handles none of its own insets, which is how a step counter
 * ends up under the status bar.
 *
 * The insets paid here are also **marked consumed**, `VaultScaffold`'s arrangement: a step that
 * hosts a whole screen — the vault flows' recovery kit — then reads zero for every side paid
 * here instead of paying the status bar a second time.
 *
 * [onBack] null draws no Back; [onCancel] null draws no Cancel. The count is centred on the bar
 * either way.
 */
@Composable
fun StepFrame(
    position: Int,
    total: Int,
    onBack: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    /** What Cancel says to a screen reader — "Leave decoy setup", or the plain "Cancel". */
    cancelDescription: String,
    /** False while a commit or a proof runs: Back and Cancel are drawn, and do nothing. */
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = position.toFloat() / total,
        // 240ms linear, `OnboardingFrame`'s value for its reason: a progress fill
        // that eases is a progress fill lying about its own rate.
        animationSpec = tween(durationMillis = 240, easing = LinearEasing),
        label = "stepProgress",
    )

    Scaffold(
        containerColor = VaultColors.Surface,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner),
        ) {
            val stepLabel = stringResource(R.string.decoy_step, position, total)
            // The labels name their colour, so a disabled button would not dim on its own:
            // Material's 38% disabled alpha, said here.
            val barText = if (enabled) VaultColors.OnSurfaceVariant else VaultColors.OnSurfaceVariant.copy(alpha = 0.38f)
            // `OnboardingFrame`'s bar: a fixed 48dp row with the count centred on it, not placed
            // between its neighbours. Back and Cancel come and go between steps; laid out beside
            // them the count would shift with each, and the kit step — which has neither — would
            // shrink the bar and lift the rail.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xl, vertical = Spacing.sm)
                    .height(Spacing.touchTarget),
            ) {
                if (onBack != null) {
                    TextButton(
                        onClick = onBack,
                        enabled = enabled,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .height(Spacing.touchTarget),
                    ) {
                        Text(
                            stringResource(R.string.action_back),
                            style = MaterialTheme.typography.labelLarge,
                            color = barText,
                        )
                    }
                }

                Text(
                    text = stepLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = VaultColors.OnSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )

                // Absent on the last step: the vault exists by then, and a control
                // labelled as leaving would imply there is something still to abandon.
                if (onCancel != null) {
                    TextButton(
                        onClick = onCancel,
                        enabled = enabled,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .height(Spacing.touchTarget)
                            .semantics { contentDescription = cancelDescription },
                    ) {
                        Text(
                            stringResource(R.string.action_cancel),
                            style = MaterialTheme.typography.labelLarge,
                            color = barText,
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xl)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(VaultColors.SurfaceContainerHigh)
                    // Announced as a value: a rail a screen reader cannot read is decoration.
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
