package com.app.vault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.app.vault.Notices
import com.app.vault.R
import com.app.vault.ui.theme.JetBrainsMono
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * Open-source licences — `third-party-notices`, design D3.
 *
 * One row in the home drawer opens the list; a row in the list opens that component's licence.
 * The activity holds which is showing, so its one back handler unwinds detail, then list, onto the
 * drawer the list was opened from.
 *
 * **The list keeps its place.** Its scroll state is held here, above the switch between list and
 * detail, so back from a licence lands on the row that was tapped rather than at the top of 120.
 *
 * **Nothing is selectable and nothing is a link.** The upstream address is shown in full as text
 * and opens nothing: the app has no network path and opens no browser. Not selectable, as nothing
 * in the app is (`KeyMaterialScanTest`): the clipboard is readable by every app on the device, and
 * a library's name or a GitHub address left there says which app the phone has.
 *
 * Names and licence texts are shown as their authors published them, in every language; the words
 * around them are the app's own and translated — `app-languages`.
 */
@Composable
fun LicencesScreen(
    components: List<Notices.Component>,
    /** The open component's id, or null for the list. */
    open: String?,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val component = open?.let { id -> components.firstOrNull { it.id == id } }
    if (component != null) {
        LicenceDetail(component, onBack, modifier)
        return
    }
    VaultScaffold(
        title = stringResource(R.string.licences_title),
        onBack = onBack,
        // Owns its scroll so 120 rows are lazy; the rows carry ListItem's own 16dp inset,
        // which is the screen inset.
        scrollable = false,
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = READING_MEASURE),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.licences_lead, stringResource(R.string.app_name)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = VaultColors.OnSurfaceVariant,
                        modifier = Modifier.padding(
                            start = Spacing.screenInset,
                            end = Spacing.screenInset,
                            top = Spacing.sm,
                            bottom = Spacing.md,
                        ),
                    )
                }
                items(components, key = { it.id }) { c ->
                    ListItem(
                        headlineContent = { Text(c.name, style = MaterialTheme.typography.bodyLarge) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.licences_row_line,
                                    c.version,
                                    c.licences.joinToString { it.name },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        trailingContent = {
                            Icon(
                                painter = painterResource(R.drawable.ic_chevron_right),
                                // The row says where it goes; the chevron repeats it.
                                contentDescription = null,
                            )
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = VaultColors.Surface,
                            headlineColor = VaultColors.OnSurface,
                            supportingColor = VaultColors.OnSurfaceVariant,
                            trailingIconColor = VaultColors.OnSurfaceVariant,
                        ),
                        modifier = Modifier
                            .heightIn(min = Spacing.touchTarget)
                            .clickable(onClickLabel = stringResource(R.string.licences_open_action)) { onOpen(c.id) },
                    )
                }
                item { Box(Modifier.padding(bottom = Spacing.section)) }
            }
        }
    }
}

@Composable
private fun LicenceDetail(c: Notices.Component, onBack: () -> Unit, modifier: Modifier) {
    // The scaffold's own scroll: one column, licence and all, saved across rotation.
    VaultScaffold(
        title = c.name,
        onBack = onBack,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.related),
            modifier = Modifier.widthIn(max = READING_MEASURE).fillMaxWidth(),
        ) {
            // The name again, full, because the bar's title is one line and a long name
            // ends in an ellipsis there.
            Text(
                c.name,
                style = MaterialTheme.typography.titleLarge,
                color = VaultColors.OnSurface,
            )
            Text(
                stringResource(R.string.licences_version, c.version),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
            )
            c.coordinate?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono),
                    color = VaultColors.OnSurfaceVariant,
                )
            }
            c.website?.let { url ->
                Field(stringResource(R.string.licences_source), url)
            }
            c.licences.forEach { licence ->
                Text(
                    stringResource(R.string.licences_licence),
                    style = MaterialTheme.typography.titleMedium,
                    color = VaultColors.OnSurface,
                    modifier = Modifier
                        .padding(top = Spacing.section)
                        .semantics { heading() },
                )
                Text(
                    licence.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = VaultColors.OnSurface,
                )
                // Line height 1.5×: a licence is read, not scanned.
                Text(
                    reflow(licence.text),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.5f,
                    ),
                    color = VaultColors.OnSurface,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
            Box(Modifier.padding(bottom = Spacing.section))
        }
    }
}

/** A label over its value: the upstream address, as text that opens nothing. */
@Composable
private fun Field(label: String, value: String) {
    Column(Modifier.padding(top = Spacing.sm)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = VaultColors.OnSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = VaultColors.OnSurface)
    }
}

/**
 * The licence text with its hard line breaks taken out of running sentences.
 *
 * SPDX texts are wrapped at about 80 columns; on a phone each such line breaks again where the
 * screen ends, so a sentence reads in ragged halves. Lines are rejoined within a paragraph, and
 * kept apart at a blank line, a numbered or lettered item, a bullet, and an underline rule. The
 * words are untouched — only whitespace changes — and the stored text stays verbatim.
 */
internal fun reflow(text: String): String {
    val item = Regex("""^(\d+(\.\d+)*\.?|\([a-zA-Z0-9]+\)|[a-z]\.|[*•-])\s""")
    val rule = Regex("""^[=\-_*]{3,}$""")
    return text.trim().split(Regex("""\n\s*\n""")).joinToString("\n\n") { paragraph ->
        val out = StringBuilder()
        var previous = ""
        paragraph.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val joins = out.isNotEmpty() && !item.containsMatchIn(line) && !rule.matches(line) &&
                !rule.matches(previous)
            if (out.isNotEmpty()) out.append(if (joins) " " else "\n")
            out.append(line)
            previous = line
        }
        out.toString()
    }
}

/** D3: a readable measure on a tablet. Phones are narrower and never reach it. */
private val READING_MEASURE = 600.dp
