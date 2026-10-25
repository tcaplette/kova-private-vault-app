package com.app.vault.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The meter's design rules, checked over its source rather than argued in a comment —
 * `passphrase-strength-meter` D5, task 4.3.
 *
 * Three claims that a later edit could quietly break, and each is the kind of thing a
 * screenshot review passes over: a literal colour, a hardcoded dimension, and the primary
 * used as a status. `DESIGN.md` §4.1 gives the primary token exactly one role — the primary
 * action — and
 * §6.4 forbids meaning carried by colour at all, which is the whole reason the meter's
 * level is also a word.
 *
 * JVM scope, on `ScreenChromeTest`'s source-scan shape: these are properties of the source,
 * so no emulator is involved. Each scan also asserts **negatively** against a fixture, so a
 * regex that stops matching fails here rather than passing forever.
 */
class PassphraseStrengthMeterScanTest {

    private fun meterSource(): String {
        var dir: File? = File(".").absoluteFile
        val rel = "src/main/java/com/app/vault/ui/PassphraseStrengthMeter.kt"
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isFile }
                ?.let { return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the meter's source from ${File(".").absolutePath}")
    }

    /** Comments and imports removed: both legitimately mention colours and sizes. */
    private fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")
        .lines()
        .filterNot { it.trimStart().startsWith("import ") }
        .joinToString("\n")

    private fun literalColours(text: String): List<String> =
        Regex("""Color\s*\(|0xFF[0-9A-Fa-f]{6}""").findAll(code(text)).map { it.value }.toList()

    private fun literalDimensions(text: String): List<String> =
        Regex("""\b\d+\s*\.\s*dp\b""").findAll(code(text)).map { it.value }.toList()

    @Test
    fun theMeterDeclaresNoColourOfItsOwn() {
        val offenders = literalColours(meterSource())
        assertTrue(
            "every colour must come from VaultColors — DESIGN.md section 3. Found: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun theColourScanActuallyMatches() {
        assertTrue(literalColours("val c = Color(0xFF5FD3A0)").isNotEmpty())
        assertTrue(
            "a mention inside a comment must not count",
            literalColours("// Color(0xFF5FD3A0) would be wrong here").isEmpty(),
        )
    }

    @Test
    fun theMeterDeclaresNoDimensionOfItsOwn() {
        val offenders = literalDimensions(meterSource())
        assertTrue(
            "every size must come from Spacing or Radius — DESIGN.md section 7.1. Found: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun theDimensionScanActuallyMatches() {
        assertTrue(literalDimensions("Modifier.height(4.dp)").isNotEmpty())
        assertTrue(
            "a mention inside a comment must not count",
            literalDimensions("// 4.dp tall, from Spacing.xs").isEmpty(),
        )
    }

    /**
     * The meter must not reach for the primary token. `Primary` is the colour
     * `DESIGN.md` §4.1 reserves for the primary action and forbids as a status, and a
     * strength meter is the most tempting place in the app to break that rule.
     */
    @Test
    fun theMeterIsNeverPrimary() {
        val source = code(meterSource())
        for (forbidden in listOf("VaultColors.Primary", "VaultColors.Record")) {
            assertTrue(
                "$forbidden carries meaning DESIGN.md section 4.1 forbids a status from using",
                !source.contains(forbidden),
            )
        }
        assertTrue(
            "the level must be carried by a word, not only by the segments",
            source.contains("verdict.word"),
        )
    }

    /**
     * Announced politely or not at all. Assertive would interrupt a screen-reader user on
     * every keystroke, which is precisely what a live meter would do.
     */
    @Test
    fun theMeterAnnouncesPolitely() {
        val source = code(meterSource())
        assertTrue(
            "the word and sentence belong in a polite live region",
            source.contains("LiveRegionMode.Polite"),
        )
        assertTrue(
            "assertive announcements must not be used on something that changes per keystroke",
            !source.contains("LiveRegionMode.Assertive"),
        )
    }

    /** zxcvbn's crack-time figures assume 10^4 guesses/sec; this app is nearer 2. */
    @Test
    fun theMeterNeverReadsACrackTime() {
        val source = code(meterSource())
        for (forbidden in listOf("crackTime", "CrackTime", "crack_time")) {
            assertTrue("no crack-time figure may reach the screen", !source.contains(forbidden))
        }
    }
}
