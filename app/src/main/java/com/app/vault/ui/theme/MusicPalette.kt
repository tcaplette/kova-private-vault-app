package com.app.vault.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The music player cover's own colours — `music-recorder-covers`.
 *
 * **The cover does not draw itself out of [VaultPalette]**, for the reason
 * [CalculatorPalette] gives: a cover built from the host's tokens changes appearance
 * when the host's appearance changes, which is a tell with a switch attached. It
 * would also inherit the vault's identity — an indigo accent on cool slate — and no
 * music player looks like that.
 *
 * **Content-first and dark-leaning.** A player's screen is mostly artwork and a list,
 * so the ground recedes and one accent carries the play state and the seek progress
 * and nothing else. That single-accent discipline is what every shipped player has in
 * common; a player that tints six things is a stand-in.
 *
 * **No Compose runtime import**, for [VaultPalette]'s reason — `DesignTokensTest`
 * recomputes every ratio below on the JVM, and an import here would push it onto a
 * device.
 *
 * Text clears 4.5:1 against the ground it is drawn on in both schemes, and [accent]
 * clears 3:1 as a meaningful glyph. [outline] is a divider and is held to neither: a
 * separator forced to 3:1 draws a ladder across a list nothing else on the device
 * draws.
 */
data class MusicPalette(

    /** The screen behind everything. Not pure black — OLED smear, and DESIGN.md 4.1. */
    val ground: Color,

    /** Rows, the mini-player bar, and the artwork placeholder's ground. */
    val surface: Color,

    /**
     * The play state, the seek progress, and the selected tab. **Nothing else.**
     *
     * Green, because a transport's play affordance has been green on every platform
     * long enough that a player accenting something else reads as a brand exercise —
     * which is the one thing a cover cannot afford to look like.
     */
    val accent: Color,

    /** Drawn on [accent] — the play glyph inside the filled control. */
    val onAccent: Color,

    val textPrimary: Color,

    /** Artist and album lines, elapsed time, and an idle transport glyph. */
    val textSecondary: Color,

    /** Row separators and the unfilled half of the seek track. Held to no floor. */
    val outline: Color,
) {
    companion object {

        val Dark = MusicPalette(
            ground = Color(0xFF0E0F12),
            surface = Color(0xFF1A1C21),
            accent = Color(0xFF4ADE80),
            onAccent = Color(0xFF06210F),
            textPrimary = Color(0xFFF2F4F7),
            textSecondary = Color(0xFFA2A9B4),
            outline = Color(0xFF2A2D34),
        )

        val Light = MusicPalette(
            ground = Color(0xFFF5F6F8),
            surface = Color(0xFFFFFFFF),
            accent = Color(0xFF0F7A3D),
            onAccent = Color(0xFFFFFFFF),
            textPrimary = Color(0xFF14161A),
            textSecondary = Color(0xFF565C66),
            outline = Color(0xFFD7DAE0),
        )

        /** Both schemes, so a check cannot iterate one and believe it covered them. */
        val All = mapOf("Dark" to Dark, "Light" to Light)
    }
}
