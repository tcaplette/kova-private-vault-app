package com.app.vault.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.app.vault.R
import com.app.vault.VaultRename
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * A new name for the item on screen — P2-14.
 *
 * ***It was a screen.*** A rename is one field about the thing you are looking at, and
 * a screen took that thing off the display in order to ask. As a sheet the photograph
 * stays behind the field, which is the whole reason to move it: you can see what you
 * are naming while you name it.
 *
 * **A sheet and not a dialog.** §6.7 keeps the delete confirmation as the app's only
 * dialog. A sheet is neither a dialog nor a screen — it does not block on an answer,
 * it dismisses on a drag or a tap outside, and it can leave its subject visible where
 * a dialog cannot.
 *
 * **The content lifts above the keyboard** ([imePadding]). A field under the IME is a
 * field the user cannot read while typing into it, and this is the first surface in
 * the app whose insets are not `VaultScaffold`'s to handle.
 *
 * Cancelling leaves the same item open behind it: nothing about which item is showing
 * changes while this is up.
 *
 * **The extension is shown and is not editable.** It is the app's only type
 * information (see [VaultRename]), and the field beside it is the whole of what a
 * rename may change. Showing it rather than hiding it means the name here is the name
 * the export will write.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenameSheet(
    /** The name as stored. Split here so the caller passes an item's field, not two halves. */
    filename: String,
    onRename: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (stem, extension) = remember(filename) { VaultRename.split(filename) }

    // Pre-filled and selected: the common rename replaces the whole name, and a
    // caret at the end would make the user clear it first every time.
    var typed by remember(filename) {
        mutableStateOf(TextFieldValue(stem, TextRange(0, stem.length)))
    }
    var problem by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onCancel,
        modifier = modifier,
        containerColor = VaultColors.Surface,
    ) {
        Column(
            modifier = Modifier
                .imePadding()
                // **Scrollable, and that is not decoration.** With the keyboard up the
                // content lifts, and on a short screen the confirming button lifts
                // past the top of the window — unreachable by a thumb, and a tap aimed
                // at it lands on the scrim and dismisses the sheet instead. A test
                // caught it doing exactly that: typing a folder name, pressing Create,
                // and getting no folder and no error.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenInset)
                .padding(bottom = Spacing.section),
        ) {
            // Named for the act, and not the same string as the button below it, so
            // the heading and the action stay distinguishable to a reader and to a
            // test alike.
            Text(
                text = stringResource(R.string.rename_item),
                style = MaterialTheme.typography.titleLarge,
                color = VaultColors.OnSurface,
            )

            Text(
                text = stringResource(R.string.rename_lead),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.md),
            )

            OutlinedTextField(
                value = typed,
                onValueChange = {
                    typed = it
                    problem = null
                },
                label = { Text(stringResource(R.string.vault_name_label)) },
                // Fixed text, not a second field: the extension is displayed so the
                // user can read the whole name, and is never edited.
                suffix = { Text(extension) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            if (problem != null) {
                Text(
                    text = problem!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.Error,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }

            // The sheet's one primary action (§6.1). A refused name says so here and
            // commits nothing — null from `normalise` is a refusal, and repairing it
            // would name the item something the user did not type.
            val refusal = stringResource(R.string.rename_refused, VaultRename.MAX_NAME)
            Button(
                onClick = {
                    val name = VaultRename.normalise(typed.text, extension)
                    if (name == null) {
                        problem = refusal
                    } else {
                        onRename(name)
                    }
                },
                enabled = typed.text.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.lg),
            ) {
                Text(stringResource(R.string.rename), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
