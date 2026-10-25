package com.app.vault

import androidx.annotation.StringRes

/**
 * The Notes destination's settings — `destination-settings-and-slideshow`, `notes-settings`.
 *
 * **Incognito is on by default, and that is the one default here that changes what the app did
 * before** (D4). A learning keyboard copies what is typed into its own dictionary, outside the
 * vault, and everything typed into a note here is something the owner put in a vault. Moving
 * checked items is off: ticking used to leave the line where it was.
 */
data class NoteSettings(
    /** Ticking an item moves it below the unticked ones in its list; unticking moves it back up. */
    val moveChecked: Boolean = false,
    /** The note's title and body ask the keyboard not to learn from what is typed. */
    val incognito: Boolean = true,
)

/**
 * The Memos destination's settings — `memos-settings`. Every default is how memos were recorded
 * and played before the sheet existed: 64 kbps mono from the phone's microphone, played at Normal.
 */
data class MemoSettings(
    val quality: RecordingQuality = RecordingQuality.Standard,
    /** Two channels. A device that cannot record two records one, and the memo still succeeds. */
    val stereo: Boolean = false,
    /** A connected Bluetooth headset's microphone, when one is connected as recording starts. */
    val bluetooth: Boolean = false,
    /** Audio items play at this speed — not Media's, which governs video. */
    val speed: PlaybackSpeed = PlaybackSpeed.Default,
    /** The player skips silent passages. */
    val skipSilence: Boolean = false,
)

/**
 * Three AAC-LC rates — Apple's and Samsung's three tiers. Standard is the 64 kbps, 44.1 kHz the
 * recorder always used. Stored by **name**, for [PlaybackSpeed]'s reason.
 */
enum class RecordingQuality(val bitRate: Int, val sampleRate: Int, @StringRes val label: Int, @StringRes val detail: Int) {
    Standard(64_000, 44_100, R.string.quality_standard, R.string.quality_standard_detail),
    High(128_000, 48_000, R.string.quality_high, R.string.quality_high_detail),
    Highest(256_000, 48_000, R.string.quality_highest, R.string.quality_highest_detail);

    companion object {
        val Default = Standard

        /** The stored quality, or Standard for a name this build does not know. */
        fun of(name: String?): RecordingQuality = entries.firstOrNull { it.name == name } ?: Default
    }
}
