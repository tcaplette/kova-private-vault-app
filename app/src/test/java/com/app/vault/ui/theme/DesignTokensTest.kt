package com.app.vault.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * `DESIGN.md` section 10.
 *
 * `FLAG_SECURE` returns pure black to every screenshot (measured in P0-1), so the
 * design direction cannot be reviewed by looking at it. This reads the tokens
 * instead: the contrast numbers in `DESIGN.md` are **recomputed here**, never
 * copied, so the table cannot silently rot when a colour is edited.
 *
 * **P2-23 made this stricter, not looser.** It used to check one palette and ban the
 * symbol `lightColorScheme`. It now iterates **every** palette that exists — so a
 * scheme added later with untuned pairs fails rather than being ignored — and the
 * symbol ban is replaced by a count, because one light scheme is now correct and two
 * would not be. The dynamic-colour ban is untouched; that was never P2-23's to relax.
 *
 * JVM scope on purpose — these are constants and arithmetic. No emulator.
 */
class DesignTokensTest {

    // --- WCAG 2.1 relative luminance and contrast ---------------------------

    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun hex(c: Color): String = "#%02X%02X%02X".format(
        (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()
    )

    /** A pair from the DESIGN.md section 4 table, with the floor it must clear. */
    private data class Pair(
        val fgName: String, val fg: Color,
        val bgName: String, val bg: Color,
        val floor: Double,
    )

    private val textFloor = 4.5
    private val nonTextFloor = 3.0

    /**
     * The pairs, derived from a palette rather than written per scheme.
     *
     * Built from the palette passed in, so **adding a scheme adds its pairs
     * automatically**. A list written once per palette would let a new scheme ship
     * with no pairs at all and a green suite — the `themePackageIsNotEmpty` failure
     * mode, one level up.
     */
    private fun textPairs(p: VaultPalette) = listOf(
        Pair("onSurface", p.onSurface, "surface", p.surface, textFloor),
        Pair("onSurface", p.onSurface, "surfaceContainerLow", p.surfaceContainerLow, textFloor),
        Pair("onSurface", p.onSurface, "surfaceContainer", p.surfaceContainer, textFloor),
        Pair("onSurface", p.onSurface, "surfaceContainerHigh", p.surfaceContainerHigh, textFloor),
        Pair("onSurfaceVariant", p.onSurfaceVariant, "surface", p.surface, textFloor),
        Pair("onSurfaceVariant", p.onSurfaceVariant, "surfaceContainer", p.surfaceContainer, textFloor),
        Pair("onSurfaceVariant", p.onSurfaceVariant, "surfaceContainerHigh", p.surfaceContainerHigh, textFloor),
        Pair("primary", p.primary, "surface", p.surface, textFloor),
        Pair("primary", p.primary, "surfaceContainer", p.surfaceContainer, textFloor),
        // `language-picker-redesign` D2. The Undo snackbar's action, on its container.
        Pair("primary", p.primary, "surfaceContainerHigh", p.surfaceContainerHigh, textFloor),
        Pair("onPrimary", p.onPrimary, "primary", p.primary, textFloor),
        Pair("secondary", p.secondary, "surface", p.surface, textFloor),
        Pair("onSecondary", p.onSecondary, "secondary", p.secondary, textFloor),
        Pair("error", p.error, "surface", p.surface, textFloor),
        Pair("error", p.error, "surfaceContainer", p.surfaceContainer, textFloor),
        Pair("onError", p.onError, "error", p.error, textFloor),
        // The viewer's ground does not follow the scheme, so its foregrounds are
        // pinned. Checked in both palettes anyway: the point is that they clear the
        // floor on black, and a change to either would have to survive this.
        Pair("onViewerGround", p.onViewerGround, "viewerGround", p.viewerGround, textFloor),
        Pair("onViewerGroundVariant", p.onViewerGroundVariant, "viewerGround", p.viewerGround, textFloor),
    )

    private fun boundsPairs(p: VaultPalette) = listOf(
        // `camera-conventions`. The record red is a fill and a dot — the shutter in
        // video mode, and the marker beside the elapsed time. **It is never text**:
        // the time itself is drawn in `onViewerGround`, so 3:1 is the floor that
        // applies and the light scheme's AAA rule is not one it has to answer.
        Pair("record", p.record, "viewerGround", p.viewerGround, nonTextFloor),
        // `voice-memo-layout`. The memo screen draws the record control and the live
        // waveform on the app's own ground, not the viewer's; and a recorded memo's
        // played bars are `secondary`, its unplayed bars `outline` (below). The
        // waveform is a seek control, so all three are held to the bounds floor.
        Pair("record", p.record, "surface", p.surface, nonTextFloor),
        Pair("secondary", p.secondary, "surface", p.surface, nonTextFloor),
        Pair("outline", p.outline, "surface", p.surface, nonTextFloor),
        Pair("outline", p.outline, "surfaceContainer", p.surfaceContainer, nonTextFloor),
        Pair("outline", p.outline, "surfaceContainerHigh", p.surfaceContainerHigh, nonTextFloor),
        // `vault-appearance`. The badge's glyph — a letter repeating the name beside it, or an
        // icon — pinned white on each of the five folder fills, and `onSurfaceVariant` on the
        // No colour badge's `surfaceContainerHigh`. Decorative to a screen reader, so the
        // non-text floor applies; the name beside it carries the text floor.
        Pair("onBadge", p.onBadge, "folderRed", p.folderRed, nonTextFloor),
        Pair("onBadge", p.onBadge, "folderAmber", p.folderAmber, nonTextFloor),
        Pair("onBadge", p.onBadge, "folderTeal", p.folderTeal, nonTextFloor),
        Pair("onBadge", p.onBadge, "folderBlue", p.folderBlue, nonTextFloor),
        Pair("onBadge", p.onBadge, "folderPink", p.folderPink, nonTextFloor),
        Pair("onSurfaceVariant", p.onSurfaceVariant, "surfaceContainerHigh", p.surfaceContainerHigh, nonTextFloor),
    )

    private fun report(scheme: String, p: Pair, ratio: Double) =
        "%-5s %-22s %s on %-20s %s  %5.2f:1  (floor %.1f)".format(
            scheme, p.fgName, hex(p.fg), p.bgName, hex(p.bg), ratio, p.floor
        )

    // --- 1. contrast, in every scheme ---------------------------------------

    @Test
    fun contrastFormulaIsCorrect() {
        // Guards the arithmetic itself: if this drifts, every number below is noise.
        assertEquals(21.0, contrast(Color(0xFFFFFFFF), Color(0xFF000000)), 0.01)
        assertEquals(1.0, contrast(VaultPalette.Dark.surface, VaultPalette.Dark.surface), 0.001)
    }

    /**
     * Without this, the two checks below would iterate an empty map and pass having
     * measured nothing — the `themePackageIsNotEmpty` argument applied to schemes,
     * and the reason those checks iterate `All` rather than naming palettes.
     */
    @Test
    fun everyDeclaredPaletteIsChecked() {
        assertEquals(
            "a palette exists that the contrast checks do not iterate",
            setOf("Dark", "Light"),
            VaultPalette.All.keys,
        )
    }

    @Test
    fun everyTextPairClearsItsFloorInEveryScheme() {
        val failures = mutableListOf<String>()
        VaultPalette.All.forEach { (scheme, palette) ->
            textPairs(palette).forEach { p ->
                val ratio = contrast(p.fg, p.bg)
                println(report(scheme, p, ratio))
                if (ratio < p.floor) failures += report(scheme, p, ratio)
            }
        }
        assertTrue("Text pairs below 4.5:1:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun everyInteractiveBoundClearsTheNonTextFloorInEveryScheme() {
        val failures = mutableListOf<String>()
        VaultPalette.All.forEach { (scheme, palette) ->
            boundsPairs(palette).forEach { p ->
                val ratio = contrast(p.fg, p.bg)
                println(report(scheme, p, ratio))
                if (ratio < p.floor) failures += report(scheme, p, ratio)
            }
        }
        assertTrue("Bounds below 3:1:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun decorativeDividerStaysBelowTheControlFloorInEveryScheme() {
        // outlineVariant is exempt from the 3:1 floor *because* it is decorative.
        // Assert it actually is, in each scheme, so a future edit cannot quietly
        // promote a hairline divider into something that reads as a control boundary
        // — in either one.
        VaultPalette.All.forEach { (scheme, p) ->
            val ratio = contrast(p.outlineVariant, p.surface)
            println(
                "$scheme outlineVariant ${hex(p.outlineVariant)} on surface ${hex(p.surface)}  " +
                    "%5.2f:1  (decorative, must stay < 3.0)".format(ratio)
            )
            assertTrue(
                "$scheme outlineVariant reads as a control boundary at %.2f:1 — either drop it ".format(ratio) +
                    "below 3:1 or move it into boundsPairs and hold it to the floor.",
                ratio < nonTextFloor
            )
        }
    }

    /**
     * **The light scheme carries no AAA exception.**
     *
     * The dark palette documents one — muted text on a raised container at 6.92:1 —
     * and the light palette was derived until it needed none, bottoming out at
     * 7.41:1. That is a deliberate property rather than luck: the first light set
     * that cleared AA sat at 5.50:1 and was rejected. Asserted so it cannot be lost
     * to a later "small" tweak that only checks the AA floor.
     */
    @Test
    fun theLightSchemeClearsAaaOnEveryTextPair() {
        val aaa = 7.0
        val below = textPairs(VaultPalette.Light)
            .map { it to contrast(it.fg, it.bg) }
            .filter { (_, ratio) -> ratio < aaa }
        println("light scheme lowest text pair: %.2f:1".format(
            textPairs(VaultPalette.Light).minOf { contrast(it.fg, it.bg) }
        ))
        assertTrue(
            "The light scheme is meant to carry no AAA exception. Below 7:1: " +
                below.map { (p, r) -> "${p.fgName} on ${p.bgName} %.2f".format(r) },
            below.isEmpty()
        )
    }

    // --- 1b. the calculator cover's own palette -----------------------------

    /**
     * **The cover draws itself from [CalculatorPalette], so it gets the same
     * treatment.** P2-25 took the calculator off the vault's tokens; without this it
     * would have taken it off the vault's contrast floors too, and a cover nobody can
     * read in daylight is a cover somebody looks at twice.
     *
     * **Every pair here is a label against the fill it is actually drawn on**, not a
     * label against the ground. That distinction is the whole reason the key fills are
     * exempt from the 3:1 non-text floor: on a keypad the grid identifies the key, and
     * every shipping calculator sits its keys a shade off their background and no
     * further. [CalculatorPalette.chipOutline] is held to 3:1 because a chip's
     * boundary is the only thing saying the mode exists.
     */
    private fun calculatorTextPairs(p: CalculatorPalette) = listOf(
        Pair("textPrimary", p.textPrimary, "keyDigit", p.keyDigit, textFloor),
        Pair("textPrimary", p.textPrimary, "ground", p.ground, textFloor),
        Pair("textSecondary", p.textSecondary, "ground", p.ground, textFloor),
        Pair("textSecondary", p.textSecondary, "keyFunction", p.keyFunction, textFloor),
        // The operator glyphs, and the AC/DEL labels one block over.
        Pair("accent", p.accent, "keyOperator", p.keyOperator, textFloor),
        Pair("accent", p.accent, "keyFunction", p.keyFunction, textFloor),
        // The equals key, and an engaged mode chip — one on-colour serves both.
        Pair("onEquals", p.onEquals, "equalsFill", p.equalsFill, textFloor),
        Pair("onAccent", p.onAccent, "accent", p.accent, textFloor),
        Pair("error", p.error, "ground", p.ground, textFloor),
    )

    private fun calculatorBoundsPairs(p: CalculatorPalette) = listOf(
        Pair("chipOutline", p.chipOutline, "ground", p.ground, nonTextFloor),
    )

    /** [everyDeclaredPaletteIsChecked]'s argument, for the second palette. */
    @Test
    fun everyDeclaredCalculatorPaletteIsChecked() {
        assertEquals(
            "a calculator palette exists that the contrast checks do not iterate",
            setOf("Dark", "Light"),
            CalculatorPalette.All.keys,
        )
    }

    @Test
    fun everyCalculatorPairClearsItsFloorInEveryScheme() {
        val failures = mutableListOf<String>()
        CalculatorPalette.All.forEach { (scheme, palette) ->
            (calculatorTextPairs(palette) + calculatorBoundsPairs(palette)).forEach { p ->
                val ratio = contrast(p.fg, p.bg)
                println("calc " + report(scheme, p, ratio))
                if (ratio < p.floor) failures += "calc " + report(scheme, p, ratio)
            }
        }
        assertTrue(
            "Calculator pairs below their floor — move the fill, never the accent hue:\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /**
     * **Digits carry the lightest fill in both schemes.** Google Calculator 9.0's
     * change, and the cheapest single thing that stops a keypad reading as a uniform
     * grid — so it is asserted rather than left to a later "tidy-up" that evens the
     * fills out again.
     */
    @Test
    fun theDigitKeysAreTheLightestFillInEveryScheme() {
        CalculatorPalette.All.forEach { (scheme, p) ->
            val digit = luminance(p.keyDigit)
            listOf("keyFunction" to p.keyFunction, "keyOperator" to p.keyOperator, "ground" to p.ground)
                .forEach { (name, other) ->
                    assertTrue(
                        "$scheme keyDigit ${hex(p.keyDigit)} is not lighter than $name ${hex(other)}",
                        digit > luminance(other),
                    )
                }
        }
    }

    /**
     * **The accent stays warm in both schemes.** It is the calculator signature — the
     * orange iOS and Samsung have carried for decades — and it is the one thing that
     * makes the screen read as a calculator before a key is pressed. A later tweak
     * that drifts it toward the vault's indigo would pass every contrast check above.
     */
    @Test
    fun theCalculatorAccentIsWarmInEveryScheme() {
        CalculatorPalette.All.forEach { (scheme, p) ->
            listOf("accent" to p.accent, "equalsFill" to p.equalsFill).forEach { (name, c) ->
                val h = hue(c)
                println("$scheme $name ${hex(c)} hue %.0f deg sat %.2f".format(h, saturation(c)))
                assertTrue(
                    "$scheme $name ${hex(c)} is not warm — hue %.0f deg is outside 15..45".format(h),
                    saturation(c) >= saturationFloor && h in 15.0..45.0,
                )
            }
        }
    }

    // --- 1c. the music player's and the recorder's palettes ------------------

    /**
     * **The two covers added by `music-recorder-covers` get the calculator's
     * treatment**, and for the calculator's reason: taking a cover off the vault's
     * tokens must not take it off the vault's contrast floors. A player nobody can
     * read on a bright pavement is a player somebody looks at twice.
     *
     * Every pair is a label against the fill it is actually drawn on. `outline` is
     * exempt from the 3:1 non-text floor in both palettes, and deliberately: it is a
     * row separator and the unplayed half of a seek track, and a separator forced to
     * 3:1 draws a ladder across a list that nothing else on the device draws.
     */
    private fun musicPairs(p: MusicPalette) = listOf(
        Pair("textPrimary", p.textPrimary, "ground", p.ground, textFloor),
        Pair("textPrimary", p.textPrimary, "surface", p.surface, textFloor),
        Pair("textSecondary", p.textSecondary, "ground", p.ground, textFloor),
        Pair("textSecondary", p.textSecondary, "surface", p.surface, textFloor),
        // The playing row's title, and the tab label on its unselected ground.
        Pair("accent", p.accent, "ground", p.ground, textFloor),
        Pair("accent", p.accent, "surface", p.surface, textFloor),
        // The play glyph inside the one filled control on the screen.
        Pair("onAccent", p.onAccent, "accent", p.accent, textFloor),
    )

    private fun recorderPairs(p: RecorderPalette) = listOf(
        Pair("textPrimary", p.textPrimary, "ground", p.ground, textFloor),
        Pair("textPrimary", p.textPrimary, "surface", p.surface, textFloor),
        Pair("textSecondary", p.textSecondary, "ground", p.ground, textFloor),
        Pair("textSecondary", p.textSecondary, "surface", p.surface, textFloor),
        Pair("accent", p.accent, "surface", p.surface, textFloor),
        // The record control is a meaningful glyph on the transport's own surface,
        // and its ink has to survive being drawn on it.
        Pair("record", p.record, "surface", p.surface, nonTextFloor),
        Pair("record", p.record, "ground", p.ground, nonTextFloor),
        Pair("onRecord", p.onRecord, "record", p.record, textFloor),
    )

    /** [everyDeclaredPaletteIsChecked]'s argument, for the cover palettes. */
    @Test
    fun everyDeclaredCoverPaletteIsChecked() {
        assertEquals(
            "a music palette exists that the contrast checks do not iterate",
            setOf("Dark", "Light"),
            MusicPalette.All.keys,
        )
        assertEquals(
            "a recorder palette exists that the contrast checks do not iterate",
            setOf("Dark", "Light"),
            RecorderPalette.All.keys,
        )
    }

    @Test
    fun everyCoverPairClearsItsFloorInEveryScheme() {
        val failures = mutableListOf<String>()
        MusicPalette.All.forEach { (scheme, palette) ->
            musicPairs(palette).forEach { p ->
                val ratio = contrast(p.fg, p.bg)
                println("music " + report(scheme, p, ratio))
                if (ratio < p.floor) failures += "music " + report(scheme, p, ratio)
            }
        }
        RecorderPalette.All.forEach { (scheme, palette) ->
            recorderPairs(palette).forEach { p ->
                val ratio = contrast(p.fg, p.bg)
                println("recorder " + report(scheme, p, ratio))
                if (ratio < p.floor) failures += "recorder " + report(scheme, p, ratio)
            }
        }
        assertTrue(
            "Cover pairs below their floor — move the fill, never the accent hue:\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /**
     * ***The recorder's red is used for recording and for nothing else***, so it must
     * not be reachable as a general accent: if `record` and `accent` ever converged,
     * the one colour on the screen that means *this is being recorded right now* would
     * have been spent on a seek bar.
     */
    @Test
    fun theRecordersRedIsNotItsAccent() {
        RecorderPalette.All.forEach { (scheme, p) ->
            // By **hue**, not by contrast: the two are deliberately close in
            // luminance so neither shouts over the other, and a luminance test would
            // pass a red drifting toward the accent's blue while failing the pair
            // this rule is actually about.
            val apart = abs(hue(p.record) - hue(p.accent)).let { minOf(it, 360.0 - it) }
            println("$scheme record ${hex(p.record)} vs accent ${hex(p.accent)}: %.0f deg apart".format(apart))
            assertTrue(
                "$scheme record ${hex(p.record)} and accent ${hex(p.accent)} are the same colour " +
                    "to a glance — the red must mean recording and nothing else",
                apart > 60.0,
            )
            assertTrue(
                "$scheme record ${hex(p.record)} is not red — hue %.0f deg".format(hue(p.record)),
                hue(p.record) < 25.0 || hue(p.record) > 335.0,
            )
        }
    }

    // --- 2. no purple, in every scheme --------------------------------------

    /** Hue in degrees, 0..360. Meaningless below [saturationFloor]; guarded at the call site. */
    private fun hue(c: Color): Double {
        val r = c.red.toDouble(); val g = c.green.toDouble(); val b = c.blue.toDouble()
        val hi = maxOf(r, g, b); val lo = minOf(r, g, b)
        val d = hi - lo
        if (d == 0.0) return 0.0
        val h = when (hi) {
            r -> ((g - b) / d) % 6.0
            g -> ((b - r) / d) + 2.0
            else -> ((r - g) / d) + 4.0
        } * 60.0
        return if (h < 0) h + 360.0 else h
    }

    /** HSL saturation. Below this a hue is grey and its angle says nothing. */
    private fun saturation(c: Color): Double {
        val r = c.red.toDouble(); val g = c.green.toDouble(); val b = c.blue.toDouble()
        val hi = maxOf(r, g, b); val lo = minOf(r, g, b)
        val d = hi - lo
        if (d == 0.0) return 0.0
        val l = (hi + lo) / 2.0
        return d / (1.0 - abs(2.0 * l - 1.0))
    }

    private val saturationFloor = 0.15

    private fun tokens(p: VaultPalette) = mapOf(
        "Surface" to p.surface,
        "SurfaceContainerLow" to p.surfaceContainerLow,
        "SurfaceContainer" to p.surfaceContainer,
        "SurfaceContainerHigh" to p.surfaceContainerHigh,
        "ViewerGround" to p.viewerGround,
        "OnViewerGround" to p.onViewerGround,
        "OnViewerGroundVariant" to p.onViewerGroundVariant,
        "Record" to p.record,
        "OnBadge" to p.onBadge,
        "OnSurface" to p.onSurface,
        "OnSurfaceVariant" to p.onSurfaceVariant,
        "Primary" to p.primary,
        "OnPrimary" to p.onPrimary,
        "Secondary" to p.secondary,
        "OnSecondary" to p.onSecondary,
        "Error" to p.error,
        "OnError" to p.onError,
        "Outline" to p.outline,
        "OutlineVariant" to p.outlineVariant,
        "Scrim" to p.scrim,
        // `folder-system`. Enumerated here so §12's purple ban keeps binding every
        // token rather than every token that existed when the ban was written.
        "FolderRed" to p.folderRed,
        "FolderAmber" to p.folderAmber,
        "FolderTeal" to p.folderTeal,
        "FolderBlue" to p.folderBlue,
        "FolderPink" to p.folderPink,
    )

    @Test
    fun noSchemeTokenIsPurpleOrVioletInAnyScheme() {
        val offenders = VaultPalette.All.flatMap { (scheme, palette) ->
            tokens(palette).filter { (_, c) ->
                saturation(c) >= saturationFloor && hue(c) in 260.0..310.0
            }.map { "$scheme.${it.key} ${hex(it.value)} hue %.0f°".format(hue(it.value)) }
        }
        assertTrue(
            "OUTLINE.md rejects purple/violet outright, in every scheme. Offending token(s): " +
                offenders,
            offenders.isEmpty()
        )
    }

    // --- 3. no dynamic colour; exactly two schemes ---------------------------

    private fun themeSources(): List<File> {
        // Gradle runs unit tests with the module directory as the working directory,
        // but do not depend on it: walk up until the package is found.
        var dir: File? = File(".").absoluteFile
        val rel = "src/main/java/com/app/vault/ui/theme"
        while (dir != null) {
            val candidate = File(dir, rel)
            if (candidate.isDirectory) {
                return candidate.listFiles { f -> f.extension == "kt" }!!.toList()
            }
            val nested = File(dir, "app/$rel")
            if (nested.isDirectory) {
                return nested.listFiles { f -> f.extension == "kt" }!!.toList()
            }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate the theme package from ${File(".").absolutePath}")
    }

    @Test
    fun themePackageIsNotEmpty() {
        // Without this the scans below would pass vacuously — the worst possible
        // failure mode for a check whose whole job is to find something absent.
        val files = themeSources().map { it.name }.sorted()
        println("theme sources scanned: $files")
        assertTrue("Expected at least Color, Spacing, Theme, Type, VaultPalette", files.size >= 5)
    }

    /**
     * Comments are stripped before scanning: the theme's own KDoc explains *why*
     * dynamic colour is absent, and a scan that cannot tell an explanation from a
     * call site would make documenting the decision impossible.
     */
    private fun code(f: File): String = f.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")

    @Test
    fun noDynamicColourAnywhereInTheThemePackage() {
        val offenders = themeSources().filter {
            val text = code(it)
            text.contains("dynamicDarkColorScheme") || text.contains("dynamicLightColorScheme")
        }
        assertTrue(
            "Dynamic colour is what produces the generated-app look OUTLINE.md rejects. Found in: " +
                offenders.map { it.name },
            offenders.isEmpty()
        )
    }

    /**
     * **Replaces `noLightSchemeExists`.** P2-23 makes one light scheme correct, so
     * banning the symbol is wrong — but "one" is the requirement, not "at least
     * one". Two light schemes, or a third scheme of any kind, is the palette-picker
     * surface `Do not build` still rejects, and it would arrive looking exactly like
     * this.
     */
    @Test
    fun exactlyTwoSchemesAreConstructed() {
        val source = themeSources().joinToString("\n") { code(it) }
        assertEquals(
            "there must be exactly one darkColorScheme",
            1,
            Regex("""\bdarkColorScheme\(""").findAll(source).count(),
        )
        assertEquals(
            "there must be exactly one lightColorScheme",
            1,
            Regex("""\blightColorScheme\(""").findAll(source).count(),
        )
    }

    /**
     * The palette values must stay readable without a Compose runtime, because every
     * check above runs on the JVM and would otherwise need an emulator to read a
     * colour.
     */
    @Test
    fun thePaletteHoldsNoComposeRuntimeImport() {
        val palette = themeSources().single { it.name == "VaultPalette.kt" }.readText()
        assertTrue(
            "VaultPalette imports the Compose runtime, which would push DesignTokensTest onto a device",
            !palette.contains("androidx.compose.runtime"),
        )
    }

    // --- 4. the pure-black rule and the window grounds ----------------------

    /**
     * The window themes' grounds and the Compose schemes' grounds are the same
     * colours written twice — Compose cannot read an XML colour without a
     * Context, and a theme cannot read a Kotlin constant. The duplication is
     * tolerable only while this asserts it cannot drift.
     *
     * **P2-23 doubled the duplication, so it doubles the assertion.** One ground per
     * scheme, each checked against its own palette.
     */
    @Test
    fun eachWindowBackgroundMatchesItsComposeSurface() {
        var dir: File? = File(".").absoluteFile
        var colors: File? = null
        while (dir != null && colors == null) {
            listOf("src/main/res/values/colors.xml", "app/src/main/res/values/colors.xml")
                .map { File(dir, it) }
                .firstOrNull { it.isFile }
                ?.let { colors = it }
            dir = dir.parentFile
        }
        val text = (colors ?: throw AssertionError("colors.xml not found")).readText()

        fun declared(name: String): String =
            Regex("""<color name="$name">#([0-9A-Fa-f]{6})</color>""")
                .find(text)?.groupValues?.get(1)
                ?: throw AssertionError("$name missing from colors.xml")

        mapOf(
            "vault_ground" to VaultPalette.Dark.surface,
            "vault_ground_light" to VaultPalette.Light.surface,
        ).forEach { (name, surface) ->
            println("window $name #${declared(name)} vs surface ${hex(surface)}")
            assertEquals(
                "the window would flash a different colour than the app draws ($name)",
                hex(surface).uppercase(),
                "#${declared(name)}".uppercase(),
            )
        }
    }

    /**
     * Both window themes exist and the light one is not the dark one by another
     * name — a copy-paste that left the dark ground in place would otherwise pass
     * every other check here.
     */
    @Test
    fun bothWindowThemesAreDeclaredAndDiffer() {
        var dir: File? = File(".").absoluteFile
        var themes: File? = null
        while (dir != null && themes == null) {
            listOf("src/main/res/values/themes.xml", "app/src/main/res/values/themes.xml")
                .map { File(dir, it) }
                .firstOrNull { it.isFile }
                ?.let { themes = it }
            dir = dir.parentFile
        }
        // XML comments stripped: this file explains *why* P2-6 removed those four
        // items, and a scan that cannot tell an explanation from a declaration would
        // make recording the decision impossible — the rule the Kotlin scans above
        // already follow.
        val text = (themes ?: throw AssertionError("themes.xml not found")).readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

        assertTrue("Theme.Vault is missing", text.contains("""name="Theme.Vault""""))
        assertTrue("Theme.Vault.Light is missing", text.contains("""name="Theme.Vault.Light""""))
        assertTrue(
            "the light window theme does not use the light ground",
            text.contains("@color/vault_ground_light"),
        )

        // P2-6's four removals stay removed, in both.
        listOf(
            "statusBarColor", "navigationBarColor",
            "windowLightStatusBar", "windowLightNavigationBar",
        ).forEach {
            assertTrue("$it returned to the window theme; it is a no-op at targetSdk 37", !text.contains(it))
        }
    }

    /**
     * Pure black is the viewer's ground and nothing else's — **in both schemes**.
     * A light scheme that let black in as a surface would be the §4.1 rule broken
     * in the one place nobody would look for it.
     */
    @Test
    fun pureBlackIsTheViewersGroundOnly() {
        val black = Color(0xFF000000)
        VaultPalette.All.forEach { (scheme, p) ->
            assertEquals("$scheme viewer ground is not pure black", black, p.viewerGround)
            listOf(
                "surface" to p.surface,
                "surfaceContainerLow" to p.surfaceContainerLow,
                "surfaceContainer" to p.surfaceContainer,
                "surfaceContainerHigh" to p.surfaceContainerHigh,
            ).forEach { (name, c) ->
                assertTrue("$scheme $name is pure black; only the viewer's ground may be", c != black)
            }
        }
    }
}
