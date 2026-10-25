package com.app.vault.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp
import com.app.vault.R

/**
 * The type system from `DESIGN.md` section 5. Two families, no third.
 *
 * Both are variable fonts carrying a `wght` axis, so one file per family covers
 * every weight below: Compose passes the requested [FontWeight] through to the
 * axis by default, which is why there is no `Font(...)` per weight here.
 */

/** The interface face. */
val Inter = FontFamily(
    // W300 is P2-25's addition and it exists for one role: the calculator's readout.
    // A `wght` axis serves it from the same file, but Compose resolves a requested
    // weight to the *nearest declared* one, so an undeclared W300 would silently draw
    // W400 and the readout would come out the weight it was meant to leave behind.
    Font(R.font.inter_variable, FontWeight.W300),
    Font(R.font.inter_variable, FontWeight.W400),
    Font(R.font.inter_variable, FontWeight.W500),
    Font(R.font.inter_variable, FontWeight.W600),
)

/**
 * The technical face. Used wherever the user reads a value character by
 * character — see [VaultMono].
 */
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_variable, FontWeight.W400),
    Font(R.font.jetbrains_mono_variable, FontWeight.W500),
)

/**
 * Titles and headings break between phrases — `app-languages` D7. Phrase breaking applies
 * only to Japanese, on API 33+, so a Japanese title is never split inside a word while every
 * other language breaks exactly as `Paragraph` always has.
 */
private val PHRASE_BREAK = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)

/**
 * Material 3 type roles remapped onto Inter. Sizes follow the M3 scale; only
 * family, weight and tracking change.
 *
 * Screens read `MaterialTheme.typography` and never hard-code a size — that is
 * what makes system text scaling work without per-screen fixes.
 */
val VaultTypography = Typography(
    /**
     * The voice memo's running time — `voice-memo-layout`. M3's size on the app's face;
     * undefined, the role fell back to Roboto. Tabular, so a counting clock does not
     * shift the digits beside the one that changed.
     */
    displayMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W600,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    displaySmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W600,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W600,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.25).sp,
        lineBreak = PHRASE_BREAK,
    ),
    titleLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W600,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
        lineBreak = PHRASE_BREAK,
    ),
    titleMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W500,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
        lineBreak = PHRASE_BREAK,
    ),
    bodyLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W400,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W400,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W500,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W500,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

/**
 * The monospace styles, per `DESIGN.md` section 5.1.
 *
 * Use these for anything the user reads character by character: recovery
 * mnemonic words, hashes, blob IDs, tamper-log timestamps, counting numerals.
 * This is a correctness control — P1-12 asks the user to transcribe 24 words by
 * hand, and `0`/`O` confusion there costs them the vault.
 *
 * `tnum` is on so digits do not reflow the layout as they change, which is what
 * P1-16's countdown needs.
 */
object VaultMono {

    /**
     * A bar title that is a **number** — P2-11's selection count.
     *
     * `titleLarge`'s size and weight in the mono face, so a count that changes on
     * every tap does not reflow the title it sits in. Same rule as the trial
     * countdown, one type scale up.
     */
    val titleLarge = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.W600,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    )

    val bodyLarge = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.W400,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    )

    val bodyMedium = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.W400,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    )
}

private const val TABULAR_FIGURES = "tnum"

/**
 * The calculator cover's type — P2-25.
 *
 * **The readout leaves the monospace face, and that is the point of this object.**
 * It used to be `VaultMono.displayLarge`, a role that existed for it alone and was
 * deleted with it. JetBrains Mono is a *code* face: on a
 * calculator it reads as a terminal, and it is also the vault's own technical face,
 * so the cover was wearing the fingerprint of the app it exists to hide. Google
 * Calculator 9.0 moved its numerals to a narrower face for the first of those two
 * reasons; this moves to [Inter], which is already loaded and costs nothing.
 *
 * **Tabular figures are kept**, and they matter more here than anywhere else in the
 * app: a digit that changes width as the answer changes shifts the whole readout,
 * which is exactly the sort of wrongness that makes somebody look twice at a cover.
 *
 * Negative tracking on both display roles. Large numerals set at default tracking
 * read as a spreadsheet; every calculator on either store tightens them.
 */
object CalculatorType {

    /** The answer. The largest thing on the screen and the only role this size. */
    val result = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W300,
        fontSize = 56.sp,
        lineHeight = 64.sp,
        letterSpacing = (-1.12).sp,
        fontFeatureSettings = TABULAR_FIGURES,
    )

    /** The expression as typed, above the answer. */
    val expression = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W400,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.26).sp,
        fontFeatureSettings = TABULAR_FIGURES,
    )

    /** `0`–`9`, `.`, `±`. Tabular so the keypad's columns do not shift between digits. */
    val keyDigit = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W400,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    )

    /** `÷ × − + %` and `=`. A glyph, so no tabular figures to ask for. */
    val keyOperator = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W400,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
    )

    /** The scientific block, `AC` and `DEL`. Read, not hunted for — so smaller and heavier. */
    val keyFunction = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W500,
        fontSize = 15.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    )

    /** `DEG`/`RAD` and `INV`. Tracked out, because a three-letter chip set tight reads as one word. */
    val chip = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W500,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    )
}

/**
 * The music player cover's type — `music-recorder-covers`.
 *
 * Inter, already loaded, so no new font file and no APK cost. **Not
 * `MaterialTheme.typography`**: the cover reads no vault token, and a shared type
 * scale is a token — a screen that inherits the host's roles changes when the host's
 * roles change, which is [CalculatorType]'s argument about the readout arriving one
 * door along.
 *
 * **Times are tabular.** An elapsed counter whose digits change width shifts the seek
 * row under a finger that is dragging it, which is the sort of wrongness that makes
 * somebody look twice at a cover.
 */
object MusicType {

    /** The now-playing title. The largest text on the screen, under the artwork. */
    val nowPlayingTitle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W600,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.4).sp,
    )

    /** The artist beneath it, and the album line under that. */
    val nowPlayingArtist = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W400,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    )

    /** A library row's first line: a track title, an album name, an artist name. */
    val rowTitle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W500,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    )

    /** A library row's second line, and the mini-player's artist. */
    val rowSecondary = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W400,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    )

    /** Tracks / Albums / Artists. */
    val tab = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W500,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    )

    /** Elapsed and total time, either side of the seek track. */
    val time = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W400,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    )
}

/**
 * The voice recorder cover's type — `music-recorder-covers`.
 *
 * [MusicType]'s reasoning, with one addition: the recording timer is the largest
 * thing on the screen while capture runs, and it is the one readout in this app a
 * person watches digit by digit. It is tabular and tracked in, for the reason the
 * calculator's result is.
 */
object RecorderType {

    /** `00:00.0` while recording. The screen's largest element for as long as it runs. */
    val timer = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W300,
        fontSize = 44.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.88).sp,
        fontFeatureSettings = TABULAR_FIGURES,
    )

    /** A recording's name in the list. */
    val rowTitle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W500,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    )

    /** Its date, duration and size — one line, tabular so the columns hold. */
    val rowMeta = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W400,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    )

    /** The search field's text and its placeholder. */
    val search = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W400,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    )

    /** The empty state, and the total-size line beneath the list. */
    val note = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W400,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    )

    /**
     * The "15" inside the skip glyph — `voice-recorder-layout`. Set inside `ic_replay`'s
     * circle, as Material sets the 10 in `replay_10`. 8 and not 10: measured on the emulator,
     * a 10 touched the ring on both sides of its 14dp interior at the 28dp glyph.
     */
    val skip = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.W600,
        fontSize = 8.sp,
        lineHeight = 8.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    )
}
