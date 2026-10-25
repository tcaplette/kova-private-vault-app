package com.app.vault.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.app.vault.OriginalRemoval
import com.app.vault.R
import com.app.vault.ui.theme.Radius
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import com.app.vault.words
import com.app.vault.localWords

/**
 * The question after a photo-picker import — `remove-originals-on-import`,
 * `DESIGN.md` §7.24. Remove the originals from the gallery, or keep them?
 *
 * **A card, not a dialog.** Android's own delete dialog is the confirmation and
 * cannot be skipped; a dialog of ours in front of it would be two in a row, and the
 * first would be tapped through unread (§6.7). **Not a snackbar either:** it times
 * out, and a user who looked away from a forty-video import would lose the choice
 * without knowing it had been offered. The card waits, and the grid stays usable
 * beneath it — the copies can be looked at before the originals are let go.
 *
 * Neither action is primary-coloured: the grid carries no primary action (§6.2).
 */
@Composable
fun OriginalsCard(
    offer: OriginalRemoval.Offer?,
    removing: Boolean,
    onKeep: () -> Unit,
    onRemove: () -> Unit,
) {
    // Held so the card still has its words while it animates out after the offer
    // has gone.
    var last by remember { mutableStateOf(offer) }
    if (offer != null) last = offer

    AnimatedVisibility(
        visible = offer != null,
        // §8: enters 250ms EaseOut, exits 150ms EaseIn.
        enter = expandVertically(tween(250, easing = EaseOut)) + fadeIn(tween(250, easing = EaseOut)),
        exit = shrinkVertically(tween(150, easing = EaseIn)) + fadeOut(tween(150, easing = EaseIn)),
    ) {
        val shown = last ?: return@AnimatedVisibility
        Surface(
            color = VaultColors.SurfaceContainerHigh,
            shape = RoundedCornerShape(Radius.container),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenInset, vertical = Spacing.sm)
                // Announced when it appears, without taking focus from where the user is.
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Column(
                Modifier.padding(
                    start = Spacing.lg,
                    top = Spacing.lg,
                    end = Spacing.lg,
                    bottom = Spacing.sm,
                )
            ) {
                Row {
                    Icon(
                        painter = painterResource(R.drawable.ic_image),
                        // Decorative: the title says what the card is about.
                        contentDescription = null,
                        tint = VaultColors.OnSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Column {
                        Text(
                            text = stringResource(OriginalRemoval.TITLE),
                            style = MaterialTheme.typography.titleSmall,
                            color = VaultColors.OnSurface,
                            modifier = Modifier.semantics { heading() },
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = shown.body(localWords()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = VaultColors.OnSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                ) {
                    val colors = ButtonDefaults.textButtonColors(contentColor = VaultColors.OnSurface)
                    // Disabled while Android's dialog is up: its answer is the one pending.
                    TextButton(
                        onClick = onKeep,
                        enabled = !removing,
                        colors = colors,
                        modifier = Modifier.heightIn(min = Spacing.touchTarget),
                    ) { Text(stringResource(R.string.keep)) }
                    TextButton(
                        onClick = onRemove,
                        enabled = !removing,
                        colors = colors,
                        modifier = Modifier.heightIn(min = Spacing.touchTarget),
                    ) { Text(stringResource(R.string.action_remove)) }
                }
            }
        }
    }
}
