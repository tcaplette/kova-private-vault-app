package com.app.vault.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.app.vault.MediaSettings
import com.app.vault.MemoSettings
import com.app.vault.NoteSettings
import com.app.vault.PlaybackSpeed
import com.app.vault.RecordingQuality
import com.app.vault.SkipLength
import com.app.vault.VideoOrientation
import com.app.vault.R
import com.app.vault.VaultDestination
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * One destination's own settings — raised by the grid header's `ic_tune` on Media, Notes and
 * Memos. Files has no settings and no control (`destination-settings-and-slideshow` D1).
 *
 * **Titled with the destination**, because one mark on three screens opens three different
 * sets, and a sheet that did not say whose settings it held would be about whichever screen
 * the user believes they are on.
 *
 * **A sheet, not a screen**, `FolderSheet`'s argument: the user has not gone anywhere, and it
 * slides over their own list and dismisses on a drag, a tap outside, or back. It opens fully
 * expanded, so revealing the speeds never pushes a row out of sight unannounced, and scrolls at
 * large font sizes rather than truncating. **Every change applies as it is made** — no settings
 * surface in this app waits for a second tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationSettingsSheet(
    destination: VaultDestination,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** `media-settings`. Media's nine, read only on Media. */
    media: MediaSettings = MediaSettings(),
    onMediaSettings: (MediaSettings) -> Unit = {},
    /** `notes-settings`. Read only on Notes. */
    notes: NoteSettings = NoteSettings(),
    onNoteSettings: (NoteSettings) -> Unit = {},
    /** `memos-settings`. Read only on Memos. */
    memos: MemoSettings = MemoSettings(),
    onMemoSettings: (MemoSettings) -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = VaultColors.Surface,
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.grid_destination_settings, stringResource(destination.label)),
                style = MaterialTheme.typography.titleLarge,
                color = VaultColors.OnSurface,
                modifier = Modifier
                    .padding(start = Spacing.screenInset, end = Spacing.screenInset)
                    .semantics { heading() },
            )
            when (destination) {
                VaultDestination.Media -> MediaSettingsContent(media, onMediaSettings)
                VaultDestination.Notes -> NoteSettingsContent(notes, onNoteSettings)
                VaultDestination.Memos -> MemoSettingsContent(memos, onMemoSettings)
                // Files has no settings, so its header has no control to raise this — D1.
                VaultDestination.Files -> Unit
            }
            // Room under the last line, so it does not sit on the sheet's edge or the gesture bar.
            Spacer(Modifier.height(Spacing.section))
        }
    }
}

/**
 * Media's nine — `media-settings` design D8, grown by `destination-settings-and-slideshow` D8.
 * Three groups: what happens while viewing, how a photo is drawn, and how a video plays. Each row says its state in a word and what that state
 * means, so the sheet reads the same with colour disregarded.
 */
@Composable
private fun MediaSettingsContent(media: MediaSettings, onChange: (MediaSettings) -> Unit) {
    Intro(stringResource(R.string.media_settings_intro))

    SettingsHeading(stringResource(R.string.media_settings_viewing))
    SwitchRow(
        name = stringResource(R.string.media_keep_on),
        // The cost is on the row: the vault stays open for as long as the screen does.
        supporting = if (media.keepScreenOn) {
            stringResource(R.string.media_keep_on_on)
        } else {
            stringResource(R.string.media_keep_on_off)
        },
        icon = R.drawable.ic_timer_off,
        checked = media.keepScreenOn,
        onChange = { onChange(media.copy(keepScreenOn = it)) },
    )
    SwitchRow(
        name = stringResource(R.string.media_brightness),
        supporting = if (media.fullBrightness) {
            stringResource(R.string.media_brightness_on)
        } else {
            stringResource(R.string.media_brightness_off)
        },
        icon = R.drawable.ic_brightness_high,
        checked = media.fullBrightness,
        onChange = { onChange(media.copy(fullBrightness = it)) },
    )

    SettingsHeading(stringResource(R.string.media_filter_photos))
    SwitchRow(
        name = stringResource(R.string.media_stretch),
        supporting = if (media.stretchSmall) {
            stringResource(R.string.media_stretch_on)
        } else {
            stringResource(R.string.media_stretch_off)
        },
        icon = R.drawable.ic_fit_screen,
        checked = media.stretchSmall,
        onChange = { onChange(media.copy(stretchSmall = it)) },
    )
    SwitchRow(
        name = stringResource(R.string.media_quality),
        supporting = if (media.fullQuality) {
            stringResource(R.string.media_quality_on)
        } else {
            stringResource(R.string.media_quality_off)
        },
        icon = R.drawable.ic_high_quality,
        checked = media.fullQuality,
        onChange = { onChange(media.copy(fullQuality = it)) },
    )

    SettingsHeading(stringResource(R.string.media_filter_videos))
    RevealChoice(
        name = stringResource(R.string.playback_speed),
        icon = R.drawable.ic_speed,
        current = media.speed,
        choices = PlaybackSpeed.entries,
        label = { stringResource(it.label) },
        detail = { stringResource(it.detail) },
        one = stringResource(R.string.reveal_speed),
        many = stringResource(R.string.reveal_speeds),
        onChoose = { onChange(media.copy(speed = it)) },
        footnote = stringResource(R.string.media_speed_footnote),
    )
    SwitchRow(
        name = stringResource(R.string.media_resume),
        // Off keeps nothing: the row says so, because the setting is also a privacy one.
        supporting = if (media.resume) {
            stringResource(R.string.media_resume_on)
        } else {
            stringResource(R.string.media_resume_off)
        },
        icon = R.drawable.ic_resume,
        checked = media.resume,
        onChange = { onChange(media.copy(resume = it)) },
    )
    SwitchRow(
        name = stringResource(R.string.media_repeat),
        supporting = if (media.repeat) {
            stringResource(R.string.media_repeat_on)
        } else {
            stringResource(R.string.media_repeat_off)
        },
        icon = R.drawable.ic_repeat_one,
        checked = media.repeat,
        onChange = { onChange(media.copy(repeat = it)) },
    )
    RevealChoice(
        name = stringResource(R.string.media_skip),
        icon = R.drawable.ic_touch_double,
        current = media.skip,
        choices = SkipLength.entries,
        label = { pluralStringResource(R.plurals.seconds, it.seconds, it.seconds) },
        detail = { stringResource(it.detail) },
        one = stringResource(R.string.reveal_length),
        many = stringResource(R.string.reveal_lengths),
        onChoose = { onChange(media.copy(skip = it)) },
        footnote = stringResource(R.string.media_skip_footnote),
    )
    RevealChoice(
        name = stringResource(R.string.media_orientation),
        icon = R.drawable.ic_screen_rotation,
        current = media.orientation,
        choices = VideoOrientation.entries,
        label = { stringResource(it.label) },
        detail = { stringResource(it.detail) },
        one = stringResource(R.string.reveal_way),
        many = stringResource(R.string.reveal_ways),
        onChoose = { onChange(media.copy(orientation = it)) },
    )
}

/**
 * Notes' two — `notes-settings`. One group each, because they are about two different things: what
 * a checklist does, and what the keyboard is told.
 */
@Composable
private fun NoteSettingsContent(notes: NoteSettings, onChange: (NoteSettings) -> Unit) {
    Intro(stringResource(R.string.notes_settings_intro))

    SettingsHeading(stringResource(R.string.notes_checklists))
    SwitchRow(
        name = stringResource(R.string.notes_move_checked),
        supporting = if (notes.moveChecked) {
            stringResource(R.string.notes_move_checked_on)
        } else {
            stringResource(R.string.notes_move_checked_off)
        },
        icon = R.drawable.ic_low_priority,
        checked = notes.moveChecked,
        onChange = { onChange(notes.copy(moveChecked = it)) },
    )

    SettingsHeading(stringResource(R.string.notes_keyboard))
    SwitchRow(
        name = stringResource(R.string.notes_incognito),
        // The limit is on the row: the app asks, and the keyboard decides.
        supporting = if (notes.incognito) {
            stringResource(R.string.notes_incognito_on)
        } else {
            stringResource(R.string.notes_incognito_off)
        },
        icon = R.drawable.ic_keyboard_lock,
        checked = notes.incognito,
        onChange = { onChange(notes.copy(incognito = it)) },
    )
}

/**
 * Memos' five — `memos-settings`. Recording, then playback: the order a memo lives in.
 */
@Composable
private fun MemoSettingsContent(memos: MemoSettings, onChange: (MemoSettings) -> Unit) {
    Intro(stringResource(R.string.memos_settings_intro))

    SettingsHeading(stringResource(R.string.notification_recording))
    RevealChoice(
        name = stringResource(R.string.memos_quality),
        icon = R.drawable.ic_high_res,
        current = memos.quality,
        choices = RecordingQuality.entries,
        label = { stringResource(it.label) },
        detail = { stringResource(it.detail) },
        one = stringResource(R.string.reveal_quality),
        many = stringResource(R.string.reveal_qualities),
        onChoose = { onChange(memos.copy(quality = it)) },
        footnote = stringResource(R.string.memos_quality_footnote),
    )
    SwitchRow(
        name = stringResource(R.string.memos_stereo),
        supporting = if (memos.stereo) {
            stringResource(R.string.memos_stereo_on)
        } else {
            stringResource(R.string.memos_stereo_off)
        },
        icon = R.drawable.ic_surround_sound,
        checked = memos.stereo,
        onChange = { onChange(memos.copy(stereo = it)) },
    )
    SwitchRow(
        name = stringResource(R.string.memos_bluetooth),
        // Said in both states: what a headset costs is the reason to leave this off.
        supporting = if (memos.bluetooth) {
            stringResource(R.string.memos_bluetooth_on)
        } else {
            stringResource(R.string.memos_bluetooth_off)
        },
        icon = R.drawable.ic_headset_mic,
        checked = memos.bluetooth,
        onChange = { onChange(memos.copy(bluetooth = it)) },
    )

    SettingsHeading(stringResource(R.string.memos_playback))
    RevealChoice(
        name = stringResource(R.string.playback_speed),
        icon = R.drawable.ic_avg_pace,
        current = memos.speed,
        choices = PlaybackSpeed.entries,
        label = { stringResource(it.label) },
        detail = { stringResource(it.detail) },
        one = stringResource(R.string.reveal_speed),
        many = stringResource(R.string.reveal_speeds),
        onChoose = { onChange(memos.copy(speed = it)) },
        footnote = stringResource(R.string.memos_speed_footnote),
    )
    SwitchRow(
        name = stringResource(R.string.memos_skip_silence),
        supporting = if (memos.skipSilence) {
            stringResource(R.string.memos_skip_silence_on)
        } else {
            stringResource(R.string.memos_skip_silence_off)
        },
        icon = R.drawable.ic_graphic_eq,
        checked = memos.skipSilence,
        onChange = { onChange(memos.copy(skipSilence = it)) },
    )
}

/** The sentence under a sheet's title that says what the sheet governs. */
@Composable
private fun Intro(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(
            start = Spacing.screenInset,
            end = Spacing.screenInset,
            top = Spacing.xs,
            bottom = Spacing.sm,
        ),
    )
}
