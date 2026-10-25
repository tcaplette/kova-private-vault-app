package com.app.vault.ui

import android.graphics.Bitmap
import android.icu.text.ListFormatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.app.vault.Capture
import com.app.vault.CaptureLog
import com.app.vault.CapturePermissions
import com.app.vault.R
import com.app.vault.TamperLog
import com.app.vault.VaultItem
import com.app.vault.localWords
import com.app.vault.ui.theme.Radius
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import com.app.vault.ui.theme.VaultMono
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

/**
 * The Intruder capture screen — `intruder-captures-at-home` D4, D12.
 *
 * **The timeline is the log.** One row per `0x41` entry in the keyring's capture store, newest
 * first, under day headings. A row names where the attempt failed, when, and by which mode, with
 * the photograph as its tile — so a line points at its capture by being it. A capture that has
 * left keeps its line, at full contrast, and says where it went; it is evidence, and what happened
 * to the media does not remove it.
 *
 * Reached from the home drawer's Intruder capture row. Back returns to the vault list.
 *
 * `per-vault-intruder-capture` D6, D11. The same screen is a vault's timeline, reached from that
 * vault's drawer: [subtitle] names the vault under the title, so the two are never mistaken for
 * each other. Up to two banners head the list — a mode not recording for want of its permission,
 * then Android's unused-app reset — each with the one action that fixes it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IntruderCaptureScreen(
    rows: List<CaptureLog.Row>,
    /** False until the store has been read, so the empty state never flashes over captures. */
    loaded: Boolean,
    /** The modes on for this keyring — empty on a keyring that is not the destination (D8). */
    enabledModes: Set<Capture.Mode>,
    layout: BiometricLayout,
    thumbnailFor: (VaultItem) -> Bitmap?,
    selected: Set<String>,
    onOpen: (VaultItem) -> Unit,
    onBeginSelection: (VaultItem) -> Unit,
    onToggleSelection: (VaultItem) -> Unit,
    onClearSelection: () -> Unit,
    onMoveSelected: () -> Unit,
    onExportSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    /** "Moved to {vault}", "Deleted", a refusal — the screen's notice slot. */
    notice: String?,
    modifier: Modifier = Modifier,
    /** The open vault's name in a vault's timeline; null at home. */
    subtitle: String? = null,
    /**
     * Enabled modes not recording, by the permission they lack (`TamperLog.PERMISSION_*`) — each
     * mode under the first it lacks. Empty when nothing is missing, and then no banner.
     */
    lacking: Map<Int, List<Capture.Mode>> = emptyMap(),
    onTurnBackOn: () -> Unit = {},
    /** A mode is on here and Android may reset the app's permissions — D9. */
    autoResetRisk: Boolean = false,
    onKeepPermissions: () -> Unit = {},
) {
    val selecting = selected.isNotEmpty()
    val words = localWords()
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val timeFormat = remember(locale) { android.text.format.DateFormat.getTimeFormat(context) }
    val zone = remember { ZoneId.systemDefault() }

    VaultScaffold(
        title = if (selecting) {
            stringResource(R.string.grid_selected, selected.size)
        } else {
            stringResource(R.string.drawer_intruder_capture)
        },
        titleStyle = if (selecting) VaultMono.titleLarge else null,
        // The grid's selection bar while selecting: close in the leading slot, back otherwise.
        onBack = if (selecting) null else onBack,
        leadingAction = { CloseSelectionAction(onClearSelection) },
        actions = {
            if (selecting) {
                // Named icon buttons, none behind an overflow. Delete keeps the far end.
                BarAction(R.drawable.ic_drive_file_move, stringResource(R.string.intruder_move_to_vault), onMoveSelected)
                BarAction(R.drawable.ic_output, stringResource(R.string.intruder_export), onExportSelected)
                BarAction(R.drawable.ic_delete, stringResource(R.string.delete), onDeleteSelected)
            } else {
                BarAction(R.drawable.ic_tune, stringResource(R.string.intruder_capture_settings), onSettings)
            }
        },
        largeTitle = !selecting,
        scrollable = false,
        modifier = modifier,
    ) {
        if (subtitle != null) {
            val described = stringResource(R.string.intruder_subtitle_description, subtitle)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = Spacing.screenInset)
                    .padding(bottom = Spacing.sm)
                    .clearAndSetSemantics { contentDescription = described },
            )
        }
        if (notice != null) {
            Text(
                text = notice,
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurface,
                modifier = Modifier
                    .padding(horizontal = Spacing.screenInset, vertical = Spacing.sm)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        val banners: @Composable () -> Unit = {
            if (lacking.isNotEmpty()) {
                val sentences = mutableListOf<String>()
                for ((permission, modes) in lacking.entries.sortedBy { it.key }) sentences += bannerSentence(permission, modes)
                CaptureBanner(
                    glyph = R.drawable.ic_error,
                    tint = VaultColors.Error,
                    text = sentences.joinToString(" "),
                    action = stringResource(R.string.capture_turn_back_on),
                    onAction = onTurnBackOn,
                )
            }
            if (autoResetRisk) {
                CaptureBanner(
                    glyph = R.drawable.ic_info,
                    tint = VaultColors.OnSurfaceVariant,
                    text = stringResource(R.string.capture_auto_reset),
                    action = stringResource(R.string.capture_keep_permissions),
                    onAction = onKeepPermissions,
                )
            }
        }

        if (loaded && rows.isEmpty()) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                banners()
                EmptyState(enabledModes, onSettings)
            }
            return@VaultScaffold
        }

        val sections = remember(rows, locale) { daySections(rows, zone) }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // D11. Above the first section, and scrolling with the list.
            item(key = "banners") { banners() }
            sections.forEach { (day, dayRows) ->
                item(key = "day-$day") {
                    Text(
                        text = dayLabel(words, day, zone, locale),
                        style = MaterialTheme.typography.titleSmall,
                        color = VaultColors.OnSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = Spacing.screenInset, end = Spacing.screenInset, top = Spacing.lg, bottom = Spacing.xs)
                            .semantics { heading() },
                    )
                }
                items(dayRows) { row ->
                    val permission = row.permission
                    if (permission != null) {
                        PermissionRow(permission, row, timeFormat.format(Date(row.takenAt)))
                        return@items
                    }
                    val item = row.item
                    val headline = CaptureLog.where(words, row, layout)
                    val time = timeFormat.format(Date(row.takenAt))
                    val mode = row.mode?.let { modeName(it) }
                    val outcome = row.left?.let { leftWords(it) }
                    // D10. The result, never a cause.
                    val blank = if (row.blank) {
                        stringResource(if (row.mode == Capture.Mode.AUDIO) R.string.intruder_silent else R.string.intruder_blank)
                    } else {
                        null
                    }
                    val supporting = listOfNotNull(time, mode, outcome, blank).joinToString(" · ")
                    val modeSpoken = listOfNotNull(mode, blank).joinToString(", ")
                    val description = if (outcome == null) {
                        stringResource(R.string.intruder_row_description, headline, time, modeSpoken)
                    } else {
                        stringResource(R.string.intruder_row_description_left, headline, time, modeSpoken, outcome)
                    }
                    val isSelected = item != null && item.itemId in selected
                    val tap: () -> Unit = {
                        if (item != null) {
                            if (selecting) onToggleSelection(item) else onOpen(item)
                        }
                    }
                    val press: () -> Unit = {
                        if (item != null) {
                            if (selecting) onToggleSelection(item) else onBeginSelection(item)
                        }
                    }
                    ListItem(
                        headlineContent = {
                            // Wraps; never truncated. The line is the record.
                            Text(headline, style = MaterialTheme.typography.bodyLarge)
                        },
                        supportingContent = {
                            Text(
                                text = supporting,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                                color = VaultColors.OnSurfaceVariant,
                            )
                        },
                        leadingContent = { Tile(row, isSelected, thumbnailFor) },
                        colors = ListItemDefaults.colors(
                            containerColor = if (isSelected) VaultColors.SurfaceContainerHigh else VaultColors.Surface,
                            headlineColor = VaultColors.OnSurface,
                        ),
                        modifier = Modifier
                            .heightIn(min = 72.dp)
                            .then(
                                if (item != null) Modifier.combinedClickable(onClick = tap, onLongClick = press)
                                else Modifier
                            )
                            // One element to assistive technology: where, when, which mode, and
                            // where it went. A departed row carries no action at all.
                            .clearAndSetSemantics {
                                contentDescription = description
                                if (item != null) {
                                    if (selecting) this.selected = isSelected
                                    onClick { tap(); true }
                                    onLongClick { press(); true }
                                }
                            },
                    )
                }
            }
        }
    }
}

/**
 * A banner at the head of the timeline — `per-vault-intruder-capture` D11. The glyph and the
 * words carry the meaning, never the colour alone; the text wraps and is never cut; the action
 * sits on the trailing edge under it at a full touch target. Announced once, politely, when it
 * appears.
 */
@Composable
internal fun CaptureBanner(
    glyph: Int,
    tint: androidx.compose.ui.graphics.Color,
    text: String,
    action: String,
    onAction: () -> Unit,
    /** The screen gutter on a list; none where the host already insets its content. */
    inset: androidx.compose.ui.unit.Dp = Spacing.screenInset,
) {
    Surface(
        color = VaultColors.SurfaceContainerHigh,
        shape = RoundedCornerShape(Radius.container),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = inset, vertical = Spacing.xs)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Icon(painter = painterResource(glyph), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                Text(text = text, style = MaterialTheme.typography.bodyMedium, color = VaultColors.OnSurface)
            }
            TextButton(
                onClick = onAction,
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = Spacing.touchTarget),
            ) {
                Text(action, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** "{Modes} {is/are} not recording. The {camera/microphone} permission is off." */
@Composable
private fun bannerSentence(permission: Int, modes: List<Capture.Mode>): String {
    val locale = LocalConfiguration.current.locales[0]
    val names = ListFormatter.getInstance(locale).format(modes.map { modeName(it) })
    val camera = permission == TamperLog.PERMISSION_CAMERA
    return stringResource(
        when {
            camera && modes.size == 1 -> R.string.capture_banner_camera_one
            camera -> R.string.capture_banner_camera_many
            modes.size == 1 -> R.string.capture_banner_microphone_one
            else -> R.string.capture_banner_microphone_many
        },
        names,
    )
}

/**
 * A `0x48` row — D8. The departed row's form: a glyph tile, a headline, a supporting line, and
 * neither clickable nor selectable.
 */
@Composable
private fun PermissionRow(permission: TamperLog.Permission, row: CaptureLog.Row, time: String) {
    val lost = permission.state == TamperLog.PERMISSION_LOST
    val camera = permission.permission == TamperLog.PERMISSION_CAMERA
    val headline = stringResource(
        when {
            camera && lost -> R.string.intruder_permission_camera_off
            camera -> R.string.intruder_permission_camera_on
            lost -> R.string.intruder_permission_microphone_off
            else -> R.string.intruder_permission_microphone_on
        }
    )
    val locale = LocalConfiguration.current.locales[0]
    val names = row.permissionModes.map { modeName(it) }
    val supporting = if (names.isEmpty()) {
        stringResource(R.string.intruder_permission_noticed, time)
    } else {
        // Mid-sentence, so in the language's case for a common noun: German capitalises nouns.
        val list = ListFormatter.getInstance(locale).format(names)
            .let { if (locale.language == "de") it else it.lowercase(locale) }
        stringResource(if (lost) R.string.intruder_permission_stopped else R.string.intruder_permission_resumed, time, list)
    }
    ListItem(
        headlineContent = { Text(headline, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = VaultColors.OnSurfaceVariant,
            )
        },
        leadingContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(TILE)
                    .clip(RoundedCornerShape(Radius.control))
                    .background(VaultColors.SurfaceContainerHigh),
            ) {
                Icon(
                    painter = painterResource(if (lost) R.drawable.ic_error else R.drawable.ic_check_circle),
                    contentDescription = null,
                    tint = VaultColors.OnSurfaceVariant,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = VaultColors.Surface, headlineColor = VaultColors.OnSurface),
        modifier = Modifier
            .heightIn(min = 72.dp)
            .clearAndSetSemantics { contentDescription = "$headline. $supporting." },
    )
}

/** A bar action: a named 48dp icon button. */
@Composable
private fun BarAction(icon: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(Spacing.touchTarget)) {
        Icon(painter = painterResource(icon), contentDescription = label, tint = VaultColors.OnSurface)
    }
}

/**
 * The row's 56dp tile: the photograph, cropped to fill; the mode's glyph for audio and video;
 * the outcome's glyph once the capture has left. Selected, it carries the grid's check.
 */
@Composable
private fun Tile(row: CaptureLog.Row, selected: Boolean, thumbnailFor: (VaultItem) -> Bitmap?) {
    val item = row.item
    val bitmap = item?.takeIf { row.mode == Capture.Mode.PHOTO }?.let(thumbnailFor)
    val glyph = when {
        row.left?.outcome == TamperLog.LEFT_MOVED -> R.drawable.ic_drive_file_move
        row.left?.outcome == TamperLog.LEFT_EXPORTED -> R.drawable.ic_output
        row.left != null || item == null -> R.drawable.ic_delete
        row.mode == Capture.Mode.AUDIO -> R.drawable.ic_mic
        row.mode == Capture.Mode.VIDEO -> R.drawable.ic_videocam
        else -> R.drawable.ic_photo_camera
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(TILE)
            .clip(RoundedCornerShape(Radius.control))
            .background(VaultColors.SurfaceContainerHigh),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(painter = painterResource(glyph), contentDescription = null, tint = VaultColors.OnSurfaceVariant)
        }
        if (selected) {
            Box(Modifier.fillMaxSize().background(VaultColors.Scrim.copy(alpha = 0.45f)))
            Icon(
                painter = painterResource(R.drawable.ic_check_circle),
                contentDescription = null,
                tint = VaultColors.OnSurface,
            )
        }
    }
}

/** §6.5: text and one action, in two states. */
@Composable
private fun EmptyState(enabledModes: Set<Capture.Mode>, onSettings: () -> Unit) {
    val on = enabledModes.isNotEmpty()
    val locale = LocalConfiguration.current.locales[0]
    val names = Capture.Mode.entries.filter { it in enabledModes }.map { modeName(it) }
    Column(modifier = Modifier.padding(Spacing.screenInset)) {
        Text(
            text = stringResource(if (on) R.string.intruder_empty_on_title else R.string.intruder_empty_off_title),
            style = MaterialTheme.typography.bodyLarge,
            color = VaultColors.OnSurface,
        )
        Text(
            text = if (on) {
                stringResource(R.string.intruder_empty_on_body, ListFormatter.getInstance(locale).format(names))
            } else {
                stringResource(R.string.intruder_empty_off_body)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        val buttonModifier = Modifier.fillMaxWidth().padding(top = Spacing.lg)
        if (on) {
            FilledTonalButton(onClick = onSettings, modifier = buttonModifier) {
                Text(stringResource(R.string.intruder_empty_on_action), style = MaterialTheme.typography.labelLarge)
            }
        } else {
            Button(onClick = onSettings, modifier = buttonModifier) {
                Text(stringResource(R.string.intruder_empty_off_action), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun modeName(mode: Capture.Mode): String = stringResource(
    when (mode) {
        Capture.Mode.PHOTO -> R.string.capture_mode_photo
        Capture.Mode.AUDIO -> R.string.capture_mode_audio
        Capture.Mode.VIDEO -> R.string.capture_mode_video
    }
)

@Composable
private fun leftWords(left: TamperLog.Left): String = when (left.outcome) {
    TamperLog.LEFT_MOVED -> stringResource(R.string.intruder_left_moved, left.vaultName ?: "")
    TamperLog.LEFT_EXPORTED -> stringResource(R.string.intruder_left_exported)
    else -> stringResource(R.string.intruder_left_deleted)
}

/** Consecutive runs of rows sharing a local day, newest first as [rows] already are. */
private fun daySections(rows: List<CaptureLog.Row>, zone: ZoneId): List<Pair<LocalDate, List<CaptureLog.Row>>> =
    rows.groupBy { Instant.ofEpochMilli(it.takenAt).atZone(zone).toLocalDate() }.toList()

/** "Today", "Yesterday", then the language's own long date — the `DateSections` idiom. */
private fun dayLabel(words: com.app.vault.Words, day: LocalDate, zone: ZoneId, locale: java.util.Locale): String {
    val today = LocalDate.now(zone)
    return when (day) {
        today -> words.get(R.string.intruder_today)
        today.minusDays(1) -> words.get(R.string.intruder_yesterday)
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(day)
    }
}

private val TILE = 56.dp
