package com.app.vault.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.app.vault.PhotoDetail
import com.app.vault.PhotoDetailsPreset
import com.app.vault.R
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * What a strip removes — `exif-strip-choice`, design D8.
 *
 * ***The one place the four categories are chosen.*** Import, export and share each ask only
 * *whether* to strip; what a strip removes is answered here, once, so the app has one definition
 * of "stripped" rather than three that can drift apart.
 *
 * **Modelled on the platform's own settings.** The choice is Android's Private DNS shape — a
 * short list of named answers whose custom fields appear only under Custom — and each category
 * row is its permission rows' shape: a glyph, a name, and the state in a word under it. Privacy
 * is the recommended answer and the shipped one, and under it no switch is drawn: the line on
 * the row says exactly what it removes, and a user who never opens Custom never has to read
 * four switches to learn what they already agreed to.
 *
 * **Every row is its name and one short line** — `photo-details-cleanup`. What every strip
 * always keeps and removes, and the formats it reaches, is said once, in the footer, last.
 *
 * **A sheet, not a screen.** A screen says the user has gone somewhere; here they have not —
 * this slides up over the grid or the vault list and dismisses by a drag, the scrim or back.
 * It opens fully expanded, so revealing Custom never pushes a category out of sight unannounced,
 * and it scrolls at large font sizes rather than truncating.
 *
 * **Every change applies as it is made.** There is no confirm button, as there is none on any
 * other settings surface in this app: a switch that waited for a second tap would be a switch
 * that lies about its state until then.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDetailsSheet(
    preset: PhotoDetailsPreset,
    /** What Custom removes. Drawn only while Custom is chosen. */
    custom: Set<PhotoDetail>,
    /** Whether imports are stripped too — off by default, and permanent for the stored copy. */
    stripOnImport: Boolean,
    onPreset: (PhotoDetailsPreset) -> Unit,
    onCategory: (PhotoDetail, Boolean) -> Unit,
    onStripOnImport: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = VaultColors.Surface,
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.photo_details_title),
                style = MaterialTheme.typography.titleLarge,
                color = VaultColors.OnSurface,
                modifier = Modifier
                    .padding(horizontal = Spacing.screenInset)
                    .semantics { heading() },
            )
            Text(
                text = stringResource(R.string.photo_details_lead),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(
                    start = Spacing.screenInset,
                    end = Spacing.screenInset,
                    top = Spacing.xs,
                    bottom = Spacing.sm,
                ),
            )

            Column(
                modifier = Modifier
                    .padding(horizontal = Spacing.screenInset)
                    .selectableGroup(),
            ) {
                ChoiceRow(
                    name = stringResource(R.string.photo_details_privacy_row),
                    detail = stringResource(R.string.photo_details_privacy_detail),
                    selected = preset == PhotoDetailsPreset.PRIVACY,
                    onClick = { onPreset(PhotoDetailsPreset.PRIVACY) },
                )
                ChoiceRow(
                    name = stringResource(R.string.photo_details_custom),
                    detail = stringResource(R.string.photo_details_custom_detail),
                    selected = preset == PhotoDetailsPreset.CUSTOM,
                    onClick = { onPreset(PhotoDetailsPreset.CUSTOM) },
                )
            }

            // §8's enter and exit, and the one animation on this sheet: it says where the four
            // rows came from. With the system's animator scale at zero Compose runs it in no
            // time at all, which is §8's reduced-motion rule honoured by the toolkit.
            AnimatedVisibility(
                visible = preset == PhotoDetailsPreset.CUSTOM,
                enter = expandVertically(tween(REVEAL_ENTER_MS, easing = EaseOut)) +
                    fadeIn(tween(REVEAL_ENTER_MS, easing = EaseOut)),
                exit = shrinkVertically(tween(REVEAL_EXIT_MS, easing = EaseIn)) +
                    fadeOut(tween(REVEAL_EXIT_MS, easing = EaseIn)),
            ) {
                Column {
                    // A switch that is on means removed, and the heading says so once
                    // rather than every row saying it again.
                    SettingsHeading(stringResource(R.string.photo_details_remove_heading))
                    CATEGORIES.forEach { category ->
                        val removed = category.detail in custom
                        SwitchRow(
                            name = stringResource(category.name),
                            supporting = stringResource(if (removed) R.string.photo_details_removed else R.string.photo_details_kept) +
                                " · " + stringResource(category.covers),
                            icon = category.icon,
                            checked = removed,
                            onChange = { onCategory(category.detail, it) },
                        )
                    }
                }
            }

            // The import is a different question — whether, not what — and a permanent one, so
            // it sits below a rule rather than among the categories it applies.
            HorizontalDivider(
                modifier = Modifier.padding(
                    start = Spacing.screenInset,
                    end = Spacing.screenInset,
                    top = Spacing.lg,
                    bottom = Spacing.sm,
                ),
                color = VaultColors.OnSurfaceVariant.copy(alpha = DIVIDER_ALPHA),
            )
            SettingsHeading(stringResource(R.string.photo_details_import_heading))
            SwitchRow(
                name = stringResource(R.string.photo_details_import_row),
                // The state in a word, as every settings control carries it; the permanence
                // only while it is on, which is when it is true of what is being imported.
                supporting = if (stripOnImport) {
                    stringResource(R.string.photo_details_import_on)
                } else {
                    stringResource(R.string.bio_off)
                },
                icon = R.drawable.ic_input,
                checked = stripOnImport,
                onChange = onStripOnImport,
            )

            // The fine print, once and last. The ceiling is on the control rather than in a
            // comment: a switch that claims to remove a home address and silently does nothing
            // for half a library is worse than no switch.
            Footer(
                stringResource(R.string.photo_details_footer),
            )

            // Room under the last line, so it does not sit on the gesture bar.
            Spacer(Modifier.height(Spacing.section))
        }
    }
}

/**
 * The platform's settings footer — SettingsLib's `FooterPreference`: the info glyph on a line of
 * its own and the text under it, both secondary, at the screen inset. Private: this sheet is the
 * only surface that draws one.
 */
@Composable
private fun Footer(text: String) {
    Column(
        modifier = Modifier.padding(
            start = Spacing.screenInset,
            end = Spacing.screenInset,
            top = Spacing.section,
        ),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_info),
            // The text is the content; the glyph marks it as the page's fine print.
            contentDescription = null,
            tint = VaultColors.OnSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.md),
        )
    }
}

private class Category(val detail: PhotoDetail, @StringRes val name: Int, @StringRes val covers: Int, val icon: Int)

/** The user's four, in the order they named them, each with its own glyph on this surface. */
private val CATEGORIES = listOf(
    Category(PhotoDetail.LOCATION, R.string.photo_details_location, R.string.photo_details_location_covers, R.drawable.ic_location_on),
    Category(PhotoDetail.CAMERA, R.string.photo_details_camera, R.string.photo_details_camera_covers, R.drawable.ic_photo_camera),
    Category(PhotoDetail.DATE, R.string.photo_details_date, R.string.photo_details_date_covers, R.drawable.ic_event),
    Category(PhotoDetail.SOFTWARE, R.string.photo_details_software, R.string.photo_details_software_covers, R.drawable.ic_history),
)

// `SettingsHeading`, `SwitchRow` and the reveal's durations are in `SettingRows.kt`, shared with
// the Media settings sheet since `media-settings`.

/** Visible in both schemes without competing with the text either side of it. */
private const val DIVIDER_ALPHA = 0.3f
