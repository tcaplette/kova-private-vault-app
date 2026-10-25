package com.app.vault.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The calculator cover's own colours — P2-25.
 *
 * **The cover stops drawing itself out of [VaultPalette], and that is the point of
 * this file.** A cover built from the host app's tokens changes when the host's
 * appearance changes: set the vault to light and the "calculator" turns light with
 * it, which is a tell with a switch attached. It also inherits the vault's identity
 * — an indigo accent on cool slate — and a calculator does not look like that. Every
 * calculator a person has used, on either platform, is a warm accent on a neutral
 * ground, and that convention is worth more here than consistency with the app this
 * screen exists to hide.
 *
 * **These are the app's only other colour literals**, and they are in this package
 * for the reason every colour is: `ThemeScanTest` fails the build on a `Color(0xFF…)`
 * outside `ui/theme`.
 *
 * **No Compose runtime import**, for [VaultPalette]'s reason — `DesignTokensTest`
 * recomputes every ratio below on the JVM, and an import here would push it onto a
 * device.
 *
 * **Key fills are not held to the 3:1 non-text floor against [ground], and that is
 * deliberate.** On a keypad the grid identifies a key, not the edge of its fill;
 * every shipping calculator sits its keys a shade or two off their background and no
 * further, and forcing 3:1 there would produce a chessboard nothing else looks like.
 * What *is* held to 3:1 is [chipOutline], because a chip's boundary is the only thing
 * saying a mode exists. Every **label** clears 4.5:1 against the fill it is drawn on,
 * in both schemes, and `DesignTokensTest` recomputes all of it.
 */
data class CalculatorPalette(

    /** One flat ground, edge to edge. Not pure black — OLED smear, and DESIGN.md 4.1. */
    val ground: Color,

    /**
     * Digits carry the **lightest** fill in both schemes.
     *
     * Google Calculator 9.0's change, and the cheapest single thing that stops a
     * keypad reading as a uniform grid: the keys a thumb goes to are the keys that
     * come forward.
     */
    val keyDigit: Color,

    /** The scientific block, `AC` and `DEL`. Recedes; these are read, not hunted for. */
    val keyFunction: Color,

    /** `÷ × − + %`. Tinted toward the accent without becoming it. */
    val keyOperator: Color,

    /**
     * The operator glyphs, the `AC`/`DEL` labels, and an engaged chip.
     *
     * Warm, because warm operators are the calculator signature — iOS and Samsung
     * have both carried it for decades and it is the fastest way a screen reads as a
     * calculator before a single key is pressed.
     */
    val accent: Color,

    /** `=` and nothing else. The only filled key on the screen. */
    val equalsFill: Color,

    /** Drawn on [accent] — an engaged mode chip. */
    val onAccent: Color,

    /**
     * Drawn on [equalsFill].
     *
     * Its own colour and not [onAccent], because the two fills want opposite inks. In
     * daylight the `=` key has to stay a *bright* orange — that is the key iOS and
     * Samsung both put in the corner, and a fill dark enough to carry white text comes
     * out brown. Dark ink on bright orange is how they solve it, and so does this.
     */
    val onEquals: Color,

    val textPrimary: Color,
    val textSecondary: Color,

    /** The result line when an expression failed. Never used as a fill. */
    val error: Color,

    /** A mode chip's boundary while idle. The one non-text pair held to 3:1. */
    val chipOutline: Color,
) {
    companion object {

        val Dark = CalculatorPalette(
            ground = Color(0xFF131316),
            keyDigit = Color(0xFF2C2E35),
            keyFunction = Color(0xFF1E2025),
            keyOperator = Color(0xFF3A2A1C),
            accent = Color(0xFFFFA35A),
            equalsFill = Color(0xFFFF8F3C),
            onAccent = Color(0xFF241203),
            onEquals = Color(0xFF241203),
            textPrimary = Color(0xFFF4F5F7),
            textSecondary = Color(0xFF9EA3AC),
            error = Color(0xFFFF8A80),
            chipOutline = Color(0xFF6E727B),
        )

        val Light = CalculatorPalette(
            ground = Color(0xFFE8E9ED),
            keyDigit = Color(0xFFFFFFFF),
            keyFunction = Color(0xFFD6D8DF),
            keyOperator = Color(0xFFFBE3CE),
            accent = Color(0xFF8F4307),
            equalsFill = Color(0xFFFF9330),
            onAccent = Color(0xFFFFFFFF),
            onEquals = Color(0xFF3A1B02),
            textPrimary = Color(0xFF1A1B1E),
            textSecondary = Color(0xFF55585F),
            error = Color(0xFFB3261E),
            chipOutline = Color(0xFF7A7E85),
        )

        /** Both schemes, so a check cannot iterate one and believe it covered them. */
        val All = mapOf("Dark" to Dark, "Light" to Light)
    }
}
