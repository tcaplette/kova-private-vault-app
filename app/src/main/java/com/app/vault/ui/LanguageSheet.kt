package com.app.vault.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.intl.LocaleList as ComposeLocaleList
import androidx.compose.ui.text.withStyle
import com.app.vault.AppLanguage
import com.app.vault.R
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long a chosen row's filled radio is shown before the sheet leaves — D1, §8's shortest step. */
private const val MARK_HOLD_MS = 150L

/**
 * The app's language — `app-languages` D6, redesigned by `language-picker-redesign`.
 *
 * **A picker, in Android's per-app language page's shape**: the title, a line saying this
 * changes the app and not the phone, then **Suggested** — System default and the phone's own
 * languages among the nine, in the phone's order — and **All languages**, the rest in the
 * platform picker's order. One selectable group of [ChoiceRow]s; no language appears twice,
 * since two rows for one language would both show a filled radio.
 *
 * **Each language is named in its own script, and that name is never translated** — it is what
 * someone who cannot read the screen they landed on looks for. The line under it is its name in
 * the language on screen now, on every row including that language's own, so no row ever
 * changes height. The name carries its own locale, so 日本語 is drawn in Japanese forms inside a
 * Chinese screen and a screen reader that honours the locale says it in Japanese.
 *
 * **One tap finishes: mark, close, then apply** (D1). The tapped row's radio fills, the sheet
 * holds [MARK_HOLD_MS] so the fill is seen as the cause, hides, and only then is [onChoose]
 * called — so the sheet is never drawn in the language just chosen and the list never moves
 * under the finger. The screen behind redraws, and the activity's snackbar offers Undo in the
 * language the user came from. Tapping the row already chosen closes with no change; taps after
 * the first are ignored. Back, a drag or the scrim dismiss with no change.
 *
 * **It opens on the current choice** (D6): the chosen row is brought into view once it has been
 * laid out, which scrolls the sheet only when that row is below the fold.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LanguageSheet(
    /** A language, or null for the device's own — which then follows the device. */
    onChoose: (AppLanguage.Choice?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // The sheet draws in a window of its own, which provides its own configuration. On Android
    // 11–12 a choice reaches only the activity's (`MainActivity.setLocalizedContent`), so the
    // one in effect here is handed in — or the sheet would open in the device's language.
    val configuration = LocalConfiguration.current
    val chosen = remember(configuration) { AppLanguage.current(context) }
    val device = remember(configuration) { AppLanguage.systemResolved(context) }
    val suggested = remember(configuration) { AppLanguage.suggested(context) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // D1. Set by the first tap; from then on the radios read it, and further taps do nothing.
    var tapped by remember { mutableStateOf(false) }
    var marked by remember { mutableStateOf<AppLanguage.Choice?>(null) }
    val selectedTag = if (tapped) marked?.tag else chosen?.tag

    fun choose(choice: AppLanguage.Choice?) {
        if (tapped) return
        tapped = true
        marked = choice
        val unchanged = choice?.tag == chosen?.tag
        // On completion, not after `hide()` returns: a drag that dismisses the sheet during the
        // hold takes this scope with it, and the tap already made the choice.
        scope.launch {
            delay(MARK_HOLD_MS)
            sheetState.hide()
        }.invokeOnCompletion { if (unchanged) onDismiss() else onChoose(choice) }
    }

    // D6. Requested once, after the chosen row's first layout — before it there is nothing to
    // bring into view.
    val chosenRow = remember { BringIntoViewRequester() }
    var chosenPlaced by remember { mutableStateOf(false) }
    LaunchedEffect(chosenPlaced) { if (chosenPlaced) chosenRow.bringIntoView() }
    fun Modifier.chosenInView(isChosen: Boolean) =
        if (!isChosen) this
        else bringIntoViewRequester(chosenRow).onPlaced { chosenPlaced = true }

    val rowInset = Modifier.padding(horizontal = Spacing.screenInset)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = VaultColors.Surface,
    ) {
        CompositionLocalProvider(LocalConfiguration provides configuration) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.language_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = VaultColors.OnSurface,
                    modifier = Modifier
                        .padding(horizontal = Spacing.screenInset)
                        .semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.language_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.screenInset, end = Spacing.screenInset, top = Spacing.xs),
                )
                Spacer(Modifier.height(Spacing.sm))
                LanguageChoices(
                    selectedTag = selectedTag,
                    device = device,
                    suggested = suggested,
                    rowModifier = { rowInset.chosenInView(chosen?.tag == it?.tag) },
                    onChoose = ::choose,
                )
                Spacer(Modifier.height(Spacing.md))
            }
        }
    }
}

/**
 * The list itself — **Suggested**, then **All languages**, in one selectable group — shared by
 * the sheet and first run's language screen (`onboarding-language-screen`), so the two can never
 * differ. [rowModifier] is given each row's language, null for System default.
 */
@Composable
internal fun LanguageChoices(
    selectedTag: String?,
    device: AppLanguage.Choice,
    suggested: List<AppLanguage.Choice>,
    rowModifier: (AppLanguage.Choice?) -> Modifier,
    onChoose: (AppLanguage.Choice?) -> Unit,
) {
    Column(modifier = Modifier.selectableGroup()) {
        SettingsHeading(stringResource(R.string.language_suggested))
        // Its line names what "default" means on this device right now.
        ChoiceRow(
            name = stringResource(R.string.language_system_default),
            detail = stringResource(R.string.language_system_default_detail, stringResource(device.nameRes)),
            selected = selectedTag == null,
            onClick = { onChoose(null) },
            modifier = rowModifier(null),
        )
        suggested.forEach { LanguageRow(it, selectedTag, rowModifier(it), onChoose) }
        Spacer(Modifier.height(Spacing.sm))
        SettingsHeading(stringResource(R.string.language_all))
        AppLanguage.supported.filterNot { it in suggested }.forEach {
            LanguageRow(it, selectedTag, rowModifier(it), onChoose)
        }
    }
}

/** One language: its own name, in its own locale, over its name in the language on screen. */
@Composable
private fun LanguageRow(
    choice: AppLanguage.Choice,
    selectedTag: String?,
    modifier: Modifier,
    onChoose: (AppLanguage.Choice) -> Unit,
) = ChoiceRow(
    name = buildAnnotatedString {
        withStyle(SpanStyle(localeList = ComposeLocaleList(choice.tag))) { append(choice.ownName) }
    },
    detail = stringResource(choice.nameRes),
    selected = selectedTag == choice.tag,
    onClick = { onChoose(choice) },
    modifier = modifier,
)
