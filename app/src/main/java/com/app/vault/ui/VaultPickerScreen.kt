package com.app.vault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.app.vault.R
import com.app.vault.VaultAppearance
import com.app.vault.VaultKeyring
import com.app.vault.ui.theme.Radius
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * Move to vault — `intruder-captures-at-home` D5, D12.
 *
 * A screen of its own, as the folder picker is. The keyring's vaults drawn exactly as the home
 * screen draws them — the vault's badge, its name, and on a vault with its own passphrase the lock
 * mark and its line — in one grouped block, so the vault is recognised by the face it already has.
 * Tapping one goes on to its passphrase sheet, or straight to its folders.
 */
@Composable
fun VaultPickerScreen(
    vaults: List<VaultKeyring.Entry>,
    /** How many captures are moving, said under the title. */
    count: Int,
    onPick: (VaultKeyring.Entry) -> Unit,
    onBack: () -> Unit,
    /** The move was refused or failed — said where the user is standing. */
    problem: String? = null,
    modifier: Modifier = Modifier,
) {
    VaultScaffold(
        title = stringResource(R.string.intruder_move_to_vault),
        onBack = onBack,
        modifier = modifier,
    ) {
        Text(
            text = pluralStringResource(R.plurals.intruder_capture_count, count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.lg),
        )

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.groupGap)) {
            vaults.forEachIndexed { index, entry ->
                val top = if (index == 0) Radius.groupOuter else Radius.groupInner
                val bottom = if (index == vaults.lastIndex) Radius.groupOuter else Radius.groupInner
                val supporting = if (entry.hasOwnPassphrase) {
                    stringResource(R.string.vault_row_locked)
                } else {
                    stringResource(R.string.vault_row_list_passphrase)
                }
                Surface(
                    shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
                    color = VaultColors.SurfaceContainer,
                ) {
                    ListItem(
                        headlineContent = { Text(entry.name, style = MaterialTheme.typography.titleMedium) },
                        supportingContent = {
                            Text(
                                text = supporting,
                                style = MaterialTheme.typography.bodyMedium,
                                color = VaultColors.OnSurfaceVariant,
                            )
                        },
                        leadingContent = {
                            VaultBadge(
                                name = entry.name,
                                appearance = VaultAppearance.of(entry.appearance),
                                locked = entry.hasOwnPassphrase,
                            )
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                            headlineColor = VaultColors.OnSurface,
                        ),
                        modifier = Modifier
                            .clickable { onPick(entry) }
                            .semantics { contentDescription = "${entry.name}, $supporting" },
                    )
                }
            }
        }

        if (problem != null) {
            Text(
                text = problem,
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.Error,
                modifier = Modifier.padding(top = Spacing.md),
            )
        }
    }
}
