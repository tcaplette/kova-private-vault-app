package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No instrumented test may quietly destroy the install's vault.
 *
 * **Why this exists.** `OnboardingFlowTest` drives the real `MainActivity`, so it
 * uses the real `filesDir` — there is no sandbox subdirectory, because the activity
 * does not know about one. To test a *first* run it deleted `vault.slots` and
 * emptied `blobs/`, and it ran on every `connectedAndroidTest`. It destroyed a
 * vault that was being used for manual checks, repeatedly, and the only symptom was
 * onboarding appearing again after a reinstall that had preserved the data
 * correctly. Nothing caught it because nothing looked.
 *
 * Every other instrumented test in this suite sandboxes into
 * `File(ctx.filesDir, "name-${'$'}{System.nanoTime()}")` and deletes only that. A test
 * that instead reaches for the install's own files must protect the vault that is
 * already there, and **there are two ways to do that** — both of which make a routine
 * run leave an existing vault alone, which is the whole of the property:
 *
 * - **The argument gate.** The test skips unless `destructive=true` is passed to the
 *   runner. `OnboardingFlowTest` needs this one, because testing a *first* run means
 *   deleting the vault and there is no way to do that without deleting it.
 * - **The occupancy guard**, `assumeTrue(..., !lifecycle.vaultExists())`. The test
 *   creates its own vault in `filesDir` when there is none and skips when there is.
 *   This is what every screen suite uses, because a screen suite has no reason to
 *   destroy anything — it only needs *a* vault to look at.
 *
 * **The second form used not to count**, and that was the defect in this scan rather
 * than in the tests it named. It matched on the string `destructive` alone, so eleven
 * files carrying the occupancy guard looked identical to a file carrying nothing. Ten
 * of them were hardcoded into an allowlist described as tests that "still do" destroy
 * the vault — which was untrue of every one of them — and the eleventh,
 * `OriginalRemovalFlowDeviceTest`, was simply never added to a hand-maintained list
 * and failed the build. The allowlist is gone: with both guards recognised there is
 * no debt left for it to hold.
 *
 * This is the enforcement rather than a comment: an instruction to a future reader is
 * not enforcement. [theScanWouldCatchAViolation] is the other half of that — a scan
 * that has quietly stopped matching passes every run.
 */
class DestructiveTestScanTest {

    private companion object {
        /**
         * Reaching for the install's real files rather than a sandbox under them.
         *
         * `filesDir, "name-${'$'}{nanoTime}"` is the sandbox idiom and is not matched:
         * these are the app's own fixed paths, which only a destructive test names.
         */
        val REAL_PATHS = listOf(
            """filesDir, "vault.slots"""",
            """filesDir, "blobs"""",
            """filesDir, "incoming"""",
        )

        /** The argument gate: the test does not run at all unless asked for by name. */
        const val GATE = "destructive"

        /**
         * The occupancy guard: `assumeTrue(..., !lifecycle.vaultExists())`.
         *
         * Matched as two tokens rather than one literal, because the receiver is a
         * local whose name is the test's own business — `lifecycle` today, something
         * else tomorrow — and a scan that breaks on a rename is a scan somebody
         * deletes. Both tokens are required: `vaultExists()` alone is an assertion
         * about a sandbox, and it is `assumeTrue` that turns the answer into a skip.
         */
        val OCCUPANCY_GUARD = listOf("assumeTrue", "vaultExists")
    }

    /**
     * Whether [src] protects a vault that is already on the device, by either route.
     *
     * The two are alternatives and not a hierarchy. A test that must delete the vault
     * needs the gate; a test that only needs *a* vault to look at needs the guard, and
     * requiring the gate of it would mean a screen suite that never runs.
     */
    private fun isGuarded(src: String): Boolean =
        src.contains(GATE) || OCCUPANCY_GUARD.all { src.contains(it) }

    /** Files that name the install's own paths — the only ones the rule applies to. */
    private fun touchesTheInstall(src: String): Boolean = REAL_PATHS.any { src.contains(it) }

    private fun instrumentedSources(): List<File> {
        var dir: File? = File(".").absoluteFile
        val rel = "src/androidTest/java/com/app/vault"
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isDirectory }
                ?.let { d -> return d.walkTopDown().filter { it.extension == "kt" }.toList() }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate androidTest from ${File(".").absolutePath}")
    }

    private fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")

    @Test
    fun aTestThatTouchesTheInstallsOwnFilesMustBeOptIn() {
        val files = instrumentedSources()
        assertTrue("scanned no instrumented sources", files.size > 10)

        val offenders = files
            .filter { f -> code(f.readText()).let { touchesTheInstall(it) && !isGuarded(it) } }
            .map { it.name }

        assertEquals(
            "These tests reach for the install's real vault files and protect nothing, so a " +
                "routine connectedAndroidTest run would destroy the vault on the device. Take " +
                "one of three routes: sandbox into File(ctx.filesDir, " +
                "\"name-${'$'}{System.nanoTime()}\") and delete only that; add assumeTrue(..., " +
                "!lifecycle.vaultExists()) so the test skips when a vault is already here; or, " +
                "if it genuinely must delete the vault, gate the class behind the \"$GATE\" " +
                "instrumentation argument. Found: $offenders",
            emptyList<String>(), offenders,
        )
    }

    @Test
    fun theKnownDestructiveTestIsStillGated() {
        // Named, so deleting the gate fails here rather than silently widening what
        // a routine run is allowed to destroy.
        val flow = instrumentedSources().singleOrNull { it.name == "OnboardingFlowTest.kt" }
        assertTrue("OnboardingFlowTest.kt not found", flow != null)
        val src = code(flow!!.readText())
        assertTrue(
            "OnboardingFlowTest wipes the install's vault and must stay behind the " +
                "\"$GATE\" argument",
            src.contains("assumeTrue") && src.contains(GATE),
        )
    }

    /**
     * Both guards are really in use, so neither branch of [isGuarded] is dead.
     *
     * The allowlist this replaces was a hand-maintained set that drifted out of date
     * and failed the build with a name nobody had added to it. What keeps its successor
     * honest is not another list but this: if the occupancy guard stopped being how
     * screen suites protect the vault, or the argument gate stopped being how a
     * genuinely destructive test protects it, that branch would be carrying no weight
     * and should be deleted rather than left to wave everything through.
     */
    @Test
    fun bothGuardsAreInUseAndNeitherBranchIsDead() {
        val touching = instrumentedSources()
            .map { it.name to code(it.readText()) }
            .filter { (_, src) -> touchesTheInstall(src) }

        assertTrue(
            "no instrumented test names the install's own paths any more — if that is " +
                "true then this scan is dead code and should be deleted, not left to " +
                "pass vacuously",
            touching.size > 5,
        )
        assertTrue(
            "nothing uses the \"$GATE\" argument gate; delete that branch of isGuarded",
            touching.any { (_, src) -> src.contains(GATE) },
        )
        assertTrue(
            "nothing uses the occupancy guard; delete that branch of isGuarded",
            touching.any { (_, src) -> OCCUPANCY_GUARD.all { token -> src.contains(token) } },
        )
    }

    @Test
    fun theScanWouldCatchAViolation() {
        // Negative control. A scan that has stopped matching passes every run, so both
        // branches are exercised against a sample that must fail and one that must pass.
        val bare = code("""val slots = File(ctx.filesDir, "vault.slots"); slots.delete()""")
        assertTrue(
            "the scan no longer detects what it exists to detect",
            touchesTheInstall(bare) && !isGuarded(bare),
        )

        // Half the occupancy guard is not the guard. `ScreenChromeDeviceTest` really
        // does carry an unrelated `assumeTrue` about the status bar, so matching on
        // that token alone would wave a destructive test through on a coincidence.
        val halfGuarded = code(
            """
            assumeTrue("this device reports no status bar", barTop > 0)
            val slots = File(ctx.filesDir, "vault.slots"); slots.delete()
            """,
        )
        assertTrue("assumeTrue alone must not count as a guard", !isGuarded(halfGuarded))

        val occupancy = code(
            """
            assumeTrue("a vault already exists here", !lifecycle.vaultExists())
            val slots = File(ctx.filesDir, "vault.slots")
            """,
        )
        assertTrue("the occupancy guard must count", isGuarded(occupancy))
        assertTrue(
            "the argument gate must count",
            isGuarded(code("""getArguments().getString("destructive") == "true"""")),
        )

        // A sandbox is never examined, which is what keeps the rule narrow enough that
        // the hundred tests doing the right thing never have to think about it.
        val sandbox = code("""root = File(ctx.filesDir, "occupancy-${'$'}{System.nanoTime()}")""")
        assertTrue("the sandbox idiom must stay out of scope", !touchesTheInstall(sandbox))
    }
}
