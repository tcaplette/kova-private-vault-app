package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **Only `BiometricWrap` names a biometric** — `biometric-naming`, `biometric-unlock-screen`.
 *
 * The app names the face and the fingerprint from the device's hardware features, through
 * `BiometricWrap.layout`, and every word that does so lives in `BiometricWrap`'s companion
 * beside the state. A literal naming a modality anywhere else is a name that did not go
 * through the detection — a claim about a sensor the app did not check. That is a
 * requirement about *source*, which makes it checkable here rather than on a device — and
 * it has to be, because the layouts it protects are the ones this project cannot test on.
 * The emulator has no face sensor; a scan does not need one.
 *
 * JVM scope: it reads source, so no emulator is involved.
 */
class BiometricNamingScanTest {

    /**
     * Strings that carry the word and name no credential: a log tag, and the *orientation*
     * a phone is in when it is placed face down.
     *
     * `cover-unlock-methods` removed the fingerprint shape, and with it the gesture strings
     * this list once carried by name and its preference key. A method that names a biometric
     * is now built in `BiometricWrap`, like every other such name.
     */
    private val allowed = listOf(
        "FaceDownLock",
        "locked face down",
        "face_down",
        "Face-down lock",
        "This device has no motion sensor, so it can't tell when it's face down.",
    )

    /**
     * The modalities only `BiometricWrap` may name. "Face" followed by "down" is the face-down
     * lock — a motion sensor, not a biometric — so it never matches, rather than each of its
     * literals being listed in [allowed] one at a time.
     */
    private val modality = Regex("fingerprint|face(?![-_ ]?down)|iris", RegexOption.IGNORE_CASE)

    private fun sourceDir(): File {
        var dir: File? = File(".").absoluteFile
        val rel = "src/main/java/com/app/vault"
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isDirectory }
                ?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the source package from ${File(".").absolutePath}")
    }

    private fun sources(): List<File> =
        sourceDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /**
     * Every double-quoted literal in [code], with comments removed first.
     *
     * A state machine rather than a regex pair, for the reason `ScreenChromeTest`
     * tests its own stripper: the app really does pass a wildcard mime type as a
     * string literal, and the comment opener inside it must not open a comment —
     * the third case in [theScanCatchesTheLiteralThisChangeRemoved] is exactly that
     * string. Raw strings are skipped whole —
     * the app has none carrying copy, and a scanner that half-understood them would
     * be worse than one that says so.
     */
    private fun literalsIn(code: String): List<String> = scan(code).first

    /** [code] with comments and literals removed — what the program actually calls. */
    private fun codeIn(code: String): String = scan(code).second

    /** The literals, and the code left once comments and literals are taken out. */
    private fun scan(code: String): Pair<List<String>, String> {
        val found = mutableListOf<String>()
        val literal = StringBuilder()
        val bare = StringBuilder()
        var i = 0
        var mode = "code"
        while (i < code.length) {
            val rest = code.length - i
            val c = code[i]
            when (mode) {
                "code" -> when {
                    rest >= 3 && code.startsWith("\"\"\"", i) -> { mode = "raw"; i += 3 }
                    c == '"' -> { mode = "string"; literal.setLength(0); i++ }
                    rest >= 2 && code.startsWith("//", i) -> { mode = "line"; i += 2 }
                    rest >= 2 && code.startsWith("/*", i) -> { mode = "block"; i += 2 }
                    else -> { bare.append(c); i++ }
                }
                "string" -> when {
                    c == '\\' -> { literal.append(c).append(code.getOrNull(i + 1) ?: ' '); i += 2 }
                    c == '"' -> { found += literal.toString(); mode = "code"; i++ }
                    c == '\n' -> { mode = "code"; i++ }
                    else -> { literal.append(c); i++ }
                }
                "raw" -> if (rest >= 3 && code.startsWith("\"\"\"", i)) { mode = "code"; i += 3 } else i++
                "line" -> { if (c == '\n') mode = "code"; i++ }
                "block" -> if (rest >= 2 && code.startsWith("*/", i)) { mode = "code"; i += 2 } else i++
            }
        }
        return found to bare.toString()
    }

    private fun offendersIn(code: String): List<String> =
        literalsIn(code).filter { modality.containsMatchIn(it) && it !in allowed }

    /** The one file whose words may name a biometric, because it holds the detection. */
    private val namer = "BiometricWrap.kt"

    @Test
    fun onlyBiometricWrapNamesAModality() {
        val offenders = sources().filter { it.name != namer }.flatMap { f ->
            offendersIn(f.readSource()).map { "${f.name} -> \"$it\"" }
        }
        assertEquals(
            "`biometric-naming`: a face or fingerprint is named only by `BiometricWrap`, from the " +
                "device's hardware features through `BiometricWrap.layout`. A literal elsewhere names " +
                "a sensor nothing checked — on a face-only device it tells the user to touch a sensor " +
                "that will never be used. Put the words in `BiometricWrap`'s companion. Found: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    /** Without this the test above passes just as happily once `BiometricWrap` stops naming anything. */
    @Test
    fun biometricWrapStillNamesTheRows() {
        val words = literalsIn(sources().single { it.name == namer }.readSource())
        listOf("Fingerprint unlock", "Face unlock", "Face & fingerprint unlock").forEach {
            assertTrue("$namer no longer holds \"$it\"", it in words)
        }
    }

    /**
     * `cover-hold-dots` D6. The words `cover-fingerprint-dots` added for the cover's own
     * fingerprint — the link step, the lost key, the pattern covers' fingerprint method and route,
     * the cover-fingerprint log line — are gone from every source file, and the log's shown-dots
     * line names no fingerprint.
     */
    @Test
    fun theCoverFingerprintWordsAreGone() {
        val literals = sources().flatMap { literalsIn(it.readSource()) }
        listOf(
            "Link your fingerprint", "Link fingerprint again", "Pattern with fingerprint",
            "Not recognised. Use a finger you unlock your phone with.", "wrong fingerprint",
            "after a matching fingerprint", "touch the fingerprint sensor", "cover.bio",
        ).forEach { gone ->
            // The unlock screen's own line names its biometric — "Unlock screen: wrong
            // fingerprint" — since `app-languages` spelled each one out instead of "wrong $noun".
            assertTrue(
                "\"$gone\" is still in the source",
                literals.none { it.contains(gone, ignoreCase = true) && !it.startsWith("Unlock screen:") },
            )
        }
        assertEquals(
            "Music player: wrong pattern on the shown dots",
            BiometricWrap.captureWhere(
                TestWords, CaptureRecord.STAGE_COVER_PATTERN, CaptureRecord.FLAG_DOTS_SHOWN, CaptureRecord.COVER_MUSIC,
                com.app.vault.ui.BiometricLayout.Face,
            ),
        )
        // The screens that draw the log and the unlock screen spell no modality.
        listOf("IdentityScreen.kt", "VaultGate.kt", "CaptureIngest.kt", "PatternGridOverlay.kt").forEach { name ->
            val found = literalsIn(sources().single { it.name == name }.readSource())
            assertTrue("$name names a modality: $found", found.none { modality.containsMatchIn(it) && it !in allowed })
        }
    }

    /**
     * Without this the scan above passes just as happily once it stops matching
     * anything at all — `ExportPathScanTest` guards its own scan the same way.
     */
    @Test
    fun theScanCatchesTheLiteralThisChangeRemoved() {
        assertTrue(
            "the scan no longer detects the button label this change replaced",
            offendersIn("""Text("Use fingerprint", style = x)""").isNotEmpty(),
        )
        assertTrue(
            "the scan must catch a face literal as readily as a fingerprint one",
            offendersIn("""val t = "Unlock with your face"""").isNotEmpty(),
        )
        assertTrue(
            "a `/*` inside a string must not blind the scanner to what follows",
            offendersIn("""launch(arrayOf("*/*")); val t = "Use fingerprint"""").isNotEmpty(),
        )
    }

    // --- `unavailable-capability-states` ---------------------------------------

    /**
     * No composable reads the biometric state or the hardware. `canAuthenticate` and
     * `getStrings` are binder calls, and one per recomposition is what once got the process
     * killed; the activity reads them in `refreshBiometric` and hands the result down. The
     * hardware features are read once, by the activity, for the same reason.
     */
    @Test
    fun noComposableReadsTheBiometricState() {
        val ui = sources().filter { it.parentFile.name == "ui" }
        val offenders = ui.flatMap { f ->
            val code = codeIn(f.readSource())
            listOf("canAuthenticate", "getStrings", "hasSystemFeature", "BiometricWrap.state(",
                "BiometricWrap.allStrong(", "BiometricWrap.hasFingerprint(", "BiometricWrap.hasFace(",
                "BiometricWrap.buttonLabel(", "BiometricWrap.isAvailable(")
                .filter { code.contains(it) }.map { "${f.name} -> $it" }
        }
        assertEquals("a composable source reads the biometric state: $offenders", emptyList<String>(), offenders)

        val activity = codeIn(sources().single { it.name == "MainActivity.kt" }.readSource())
        val refresh = activity.substringAfter("private fun refreshBiometric()").substringBefore("private fun ")
        listOf("BiometricWrap.state(", "BiometricWrap.allStrong(", "BiometricWrap.buttonLabel(").forEach { read ->
            assertEquals(
                "$read is called outside refreshBiometric — the only place, run on onStart and on the " +
                    "way back from enrolment, never in a composition",
                Regex(Regex.escape(read)).findAll(refresh).count(),
                Regex(Regex.escape(read)).findAll(activity).count(),
            )
        }
        assertTrue("MainActivity must not read availability any other way", !activity.contains("BiometricWrap.isAvailable("))
        // Read once each, and not on every onStart: a sensor does not appear at runtime.
        listOf("BiometricWrap.hasFingerprint(", "BiometricWrap.hasFace(").forEach { read ->
            assertEquals("$read is read once", 1, Regex(Regex.escape(read)).findAll(activity).count())
            assertTrue("$read is inside refreshBiometric", !refresh.contains(read))
        }
    }

    /**
     * The hardware features name *which* rows, never *whether*: offering is decided by the
     * strong class alone (`biometric-unlock`), so the features are read in the detection and
     * nowhere else. Iris is not a row, so nothing reads it.
     */
    @Test
    fun onlyTheDetectionReadsTheModalityFeatures() {
        val offenders = sources().filter { it.name != namer }.flatMap { f ->
            val code = codeIn(f.readSource())
            listOf("FEATURE_FACE", "FEATURE_FINGERPRINT", "FEATURE_IRIS").filter { code.contains(it) }
                .map { "${f.name} -> $it" }
        }
        assertEquals(
            "`biometric-unlock`: a per-modality feature is read only by `BiometricWrap`'s detection. " +
                "Found: $offenders",
            emptyList<String>(), offenders,
        )
        assertTrue(!codeIn(sources().single { it.name == namer }.readSource()).contains("FEATURE_IRIS"))
    }

    /**
     * The layout is a pure function the JVM suite can drive: its signature holds the state,
     * two hardware flags and a nullable flag, and no Android type.
     */
    @Test
    fun theLayoutSignatureHoldsNoAndroidType() {
        val code = codeIn(sources().single { it.name == namer }.readSource())
        val params = code.substringAfter("fun layout(").substringBefore(")")
        val types = params.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { it.substringAfter(":").trim() }
        assertEquals(listOf("BiometricState", "Boolean", "Boolean", "Boolean?"), types)
    }

    /**
     * The surfaces that name no row name no modality: the drawer row, the cover method
     * choice, the screen's title and lead, the enable prompt and the button's fallback. They
     * speak of the feature, which is the same on every device.
     */
    @Test
    fun theFeatureWideSentencesNameNoModality() {
        val sentences = com.app.vault.ui.BiometricState.entries.flatMap { state ->
            listOf(BiometricWrap.drawerLine(state))
        }.map(TestWords::text) + listOf(BiometricWrap.NAME, BiometricWrap.LEAD, BiometricWrap.ENABLE_LABEL, BiometricWrap.BUTTON_FALLBACK).map(TestWords::text) +
            // The calculator's three methods name no biometric either — `calculator-dots-methods`.
            listOf(
                BiometricWrap.PLAIN_METHOD, BiometricWrap.PLAIN_METHOD_LINE,
                BiometricWrap.SEQUENCE_PATTERN_METHOD, BiometricWrap.SEQUENCE_PATTERN_METHOD_LINE,
                BiometricWrap.HOLD_PATTERN_METHOD, BiometricWrap.HOLD_PATTERN_METHOD_LINE,
            ).map(TestWords::text)
        val offenders = sentences.filter { modality.containsMatchIn(it) }
        assertEquals("a feature-wide sentence names a modality: $offenders", emptyList<String>(), offenders)
    }

    /** The allow-list earns its place only while every entry is still in the source. */
    @Test
    fun everyAllowedStringIsStillInTheSource() {
        val present = sources().flatMap { literalsIn(it.readSource()) }.toSet()
        val stale = allowed.filter { it !in present }
        assertEquals(
            "an allow-listed string is no longer in the app, so the allow-list is now " +
                "wider than the code it was written for: $stale",
            emptyList<String>(),
            stale,
        )
    }
}
