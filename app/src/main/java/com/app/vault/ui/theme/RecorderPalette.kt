package com.app.vault.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The voice recorder cover's own colours — `music-recorder-covers`.
 *
 * Same argument as [MusicPalette] and [CalculatorPalette]: a cover drawn from the
 * host's tokens changes when the host's appearance changes, and inherits an identity
 * that belongs to a vault.
 *
 * **[record] is the recorder's one loud colour and it means one thing.** Red on the
 * record control, on the level meter while capture is live, and nowhere else — a
 * recorder that tinted its list red would have spent the only colour the screen uses
 * to say *this is being recorded right now*. Playback gets [accent] instead, which is
 * why there are two.
 *
 * Text clears 4.5:1 against the ground it is drawn on in both schemes; [record] and
 * [accent] clear 3:1 as meaningful glyphs, and [onRecord] clears 4.5:1 against
 * [record].
 */
data class RecorderPalette(

    /** The screen behind everything. */
    val ground: Color,

    /** Recording rows and the transport surface beneath the list. */
    val surface: Color,

    /** The record control, and the level meter while recording. Nothing else. */
    val record: Color,

    /** Drawn on [record] — the stop glyph inside the live control. */
    val onRecord: Color,

    /** Playback: the position handle and the played half of a seek track. */
    val accent: Color,

    val textPrimary: Color,

    /** Dates, durations, sizes, and the idle level meter. */
    val textSecondary: Color,

    /** Row separators and the unplayed half of a seek track. Held to no floor. */
    val outline: Color,
) {
    companion object {

        val Dark = RecorderPalette(
            ground = Color(0xFF101114),
            surface = Color(0xFF1B1D22),
            record = Color(0xFFFF5A4E),
            onRecord = Color(0xFF250604),
            accent = Color(0xFF8FB6FF),
            textPrimary = Color(0xFFF2F4F7),
            textSecondary = Color(0xFFA2A9B4),
            outline = Color(0xFF2B2E35),
        )

        val Light = RecorderPalette(
            ground = Color(0xFFF4F5F7),
            surface = Color(0xFFFFFFFF),
            record = Color(0xFFC42B21),
            onRecord = Color(0xFFFFFFFF),
            accent = Color(0xFF1B58B8),
            textPrimary = Color(0xFF16181C),
            textSecondary = Color(0xFF545962),
            outline = Color(0xFFD6D9DF),
        )

        /** Both schemes, so a check cannot iterate one and believe it covered them. */
        val All = mapOf("Dark" to Dark, "Light" to Light)
    }
}
