package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No system prompt may be raised in front of a cover — `cover-biometric-suppression`,
 * as a build failure.
 *
 * **Why this is a scan and not a device test.** The claim is a negative one: the
 * biometric prompt does *not* appear. To watch it not appear, a test would first have to
 * be able to make it appear, and `BiometricWrapTest` already records why nothing
 * unattended can — the production key requires a live strong biometric for every
 * operation, which is the entire point of it. So the guard is asserted where it is
 * decidable, in the source, and the prompt itself is watched by hand in the change's
 * device run. Stated plainly because the alternative is worse: a test seam inside
 * `MainActivity` that let a test raise the real prompt would be a bypass shipped in the
 * APK to make a test easier.
 *
 * The same shape as `DecoyScanTest`, negative assertion included: a scan that has stopped
 * matching its target passes every run, so [theScanActuallyMatches] proves it still bites.
 *
 * JVM scope: it reads source, so no emulator is involved.
 */
class CoverPromptScanTest {

    private companion object {
        const val ACTIVITY = "MainActivity.kt"
        const val PROMPT = "autoPromptBiometric"
        const val TRIGGER = "coverTrigger"
        const val FLAG = "coverShowing"
        const val ONE_SHOT = "bioPrompted"
    }

    private fun activitySource(): String {
        var dir: File? = File(".").absoluteFile
        val rel = "src/main/java/com/app/vault/$ACTIVITY"
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isFile }
                ?.let { return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $ACTIVITY from ${File(".").absolutePath}")
    }

    private fun stripped(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")

    /**
     * The body of `fun <name>(`, braces balanced, comments removed.
     *
     * Modifier-agnostic, because one of the four is an `override` and the rest are
     * `private`. Comments go first and deliberately: a guard that exists only in a KDoc
     * is the failure this whole test is here to catch.
     */
    private fun bodyOf(source: String, name: String): String {
        val code = stripped(source)
        val at = Regex("""\bfun\s+$name\s*\(""").find(code)?.range?.first ?: -1
        assertTrue("$ACTIVITY no longer declares `fun $name(`", at >= 0)
        val open = code.indexOf('{', at)
        assertTrue("`$name` has no body", open > at)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
        }
        throw AssertionError("`$name`'s body is unbalanced")
    }

    @Test
    fun theAutomaticPromptIsGuardedByTheCover() {
        val body = bodyOf(activitySource(), PROMPT)
        assertTrue(
            "cover-front-ends: nothing the app raises while a cover is showing may name " +
                "the vault. `$PROMPT` must not fire while `$FLAG` is true — a system sheet " +
                "reading \"Unlock vault\" over the calculator is the fact the cover exists " +
                "to withhold.",
            body.contains(FLAG)
        )
    }

    @Test
    fun theGuardComesBeforeTheOneShotIsBurned() {
        val body = bodyOf(activitySource(), PROMPT)
        val guard = body.indexOf(FLAG)
        val burn = body.indexOf("$ONE_SHOT = true")
        assertTrue("`$ONE_SHOT = true` is no longer in `$PROMPT`", burn >= 0)
        assertTrue(
            "the position of the `$FLAG` guard is load-bearing: suppressing the prompt " +
                "*after* `$ONE_SHOT = true` burns the one-shot, so the prompt is withheld " +
                "over the cover and then never offered on the unlock screen the trigger " +
                "opens — biometric unlock reachable only by tapping the unlock screen's " +
                "biometric control.",
            guard in 0 until burn
        )
    }

    @Test
    fun anAcceptedTriggerReleasesTheDeferredPrompt() {
        val body = bodyOf(activitySource(), TRIGGER)
        assertTrue(
            "biometric-unlock: a prompt withheld because a cover was showing must still " +
                "be available once the cover is dismissed. `$TRIGGER` must call `$PROMPT`.",
            body.contains("$PROMPT()")
        )
        assertTrue(
            "`$TRIGGER` must still dismiss the cover before releasing the prompt",
            body.indexOf("$FLAG = false") in 0 until body.indexOf("$PROMPT()")
        )
    }

    @Test
    fun theDeferredPromptFiresOnlyWhereATriggerIsAccepted() {
        val body = bodyOf(activitySource(), TRIGGER)
        assertEquals(
            "cover-trigger: a rejected and a rate-limited attempt are the same event to a " +
                "bystander. `$PROMPT` may be called once, inside the accepted branch, and " +
                "nowhere else in `$TRIGGER`.",
            1, Regex("""\b$PROMPT\(\)""").findAll(body).count()
        )
        assertTrue(
            "`$TRIGGER` must still judge the attempt through `CoverTrigger.attempt`, whose " +
                "rate limiter runs before the match",
            body.contains("CoverTrigger.attempt") && body.contains("Outcome.Accepted")
        )
    }

    @Test
    fun theCoverIsRaisedAgainOnEveryReturnToTheForeground() {
        val body = bodyOf(activitySource(), "onStart")
        assertTrue(
            "cover-front-ends: the cover is shown on every return to the foreground while " +
                "no session is live. `onStart` must raise `$FLAG`.",
            body.contains("$FLAG = true")
        )
        assertEquals(
            "raise only, never lower — a plain assignment in `onStart` would dismiss a " +
                "cover legitimately showing over a live session, dropping the user onto " +
                "the grid with no trigger.",
            0, Regex("""\b$FLAG = false""").findAll(body).count()
        )
        assertTrue(
            "the raise must come before the prompt, or the cover goes up over a prompt " +
                "that has already fired — which is the whole defect",
            body.indexOf("$FLAG = true") in 0 until body.indexOf("$PROMPT()")
        )
        assertTrue(
            "a cover's grant must be re-read on return to the foreground: granting a " +
                "permission in Settings does not restart the process",
            body.contains("coverGrant = coverGranted(")
        )
    }

    @Test
    fun anUnlockThatLandsUnderACoverDismissesIt() {
        val body = bodyOf(activitySource(), "unlockWithBiometric")
        assertTrue(
            "cover-front-ends: an unlock that completes while a cover is showing dismisses " +
                "the cover. The prompt is asynchronous, so a user can raise it, background " +
                "the app, and return to an `onStart` that re-raises the cover while the " +
                "callback is still in flight — without this they land on a cover with the " +
                "vault open behind it and no way forward but the trigger again.",
            body.contains("$FLAG = false")
        )
    }

    // --- nothing over a cover — `cover-unlock-methods` ---------------------------

    /** The calls that put a biometric sheet on screen. */
    private val promptCalls = listOf("unlockWithBiometric(", "authenticate(", "BiometricPrompt(")

    private fun promptsIn(body: String): List<String> = promptCalls.filter { body.contains(it) }

    @Test
    fun noBiometricPromptIsReachableFromTheCoverTrigger() {
        val body = bodyOf(activitySource(), TRIGGER)
        assertEquals(
            "cover-front-ends: a live cover raises no system surface. `$TRIGGER` may reach a prompt " +
                "only through `$PROMPT`, after the cover is down — never directly.",
            emptyList<String>(), promptsIn(body),
        )
        assertTrue(
            "the sheet raised over a cover is gone, and so is the parameter that titled it",
            !stripped(activitySource()).contains("fromCover"),
        )
    }

    @Test
    fun theScanActuallyMatches() {
        val good = """
            class X {
                private fun autoPromptBiometric() {
                    if (coverShowing || bioPrompted) return
                    bioPrompted = true
                }
            }
        """.trimIndent()
        assertTrue(bodyOf(good, PROMPT).contains(FLAG))

        // The guard in a comment is exactly the rot this test exists to catch.
        val commented = """
            class X {
                private fun autoPromptBiometric() {
                    // never while coverShowing
                    bioPrompted = true
                }
            }
        """.trimIndent()
        assertTrue(!bodyOf(commented, PROMPT).contains(FLAG))

        // Braces are balanced rather than counted to the first `}`.
        val nested = """
            class X {
                private fun coverTrigger(i: Int) {
                    if (i > 0) { coverShowing = false }
                    autoPromptBiometric()
                }
            }
        """.trimIndent()
        val body = bodyOf(nested, TRIGGER)
        assertEquals(1, Regex("""\b$PROMPT\(\)""").findAll(body).count())
        assertTrue(body.indexOf("$FLAG = false") in 0 until body.indexOf("$PROMPT()"))

        // A trigger that raises the sheet itself is caught, wherever in its body it does.
        val raising = """
            class X {
                private fun coverTrigger(i: Int) {
                    if (i > 0) { unlockWithBiometric() }
                }
            }
        """.trimIndent()
        assertEquals(listOf("unlockWithBiometric("), promptsIn(bodyOf(raising, TRIGGER)))
    }
}
