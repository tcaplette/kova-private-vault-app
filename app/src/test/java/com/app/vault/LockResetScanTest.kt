package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every screen goes with the lock — `screen-off-lock-all-screens`.
 *
 * The defect this guards against was a flag nobody added to the reset: the screens drawn
 * above the lock branch survived a screen-off because the only reset was the Lock button's,
 * and its list had not grown with the app. So the check is the list. Every Compose state
 * field in `MainActivity` is either assigned by `resetForLock` (or by a function it calls),
 * or named below with the reason it is not the session's.
 *
 * JVM scope: it reads source, so no emulator is involved.
 */
class LockResetScanTest {

    /** Not session state, each for a stated reason. A new field goes in the reset instead. */
    private val notTheSession = mapOf(
        // Settings and preferences — the user's choices, not what they were looking at.
        "sort" to "setting", "mediaSettings" to "setting", "faceDownOn" to "setting", "faceDownLockAfter" to "setting",
        "identity" to "setting", "appearance" to "setting", "stripExifOnImport" to "setting",
        "photoDetailsPreset" to "setting", "photoDetailsCustom" to "setting",
        "noteSettings" to "setting", "memoSettings" to "setting",
        "bioEnabled" to "setting", "captureModes" to "setting", "exportTree" to "setting",
        // `recovery-practice` D7. One switch for the device, read at start and on each toggle.
        "extraSecurityOn" to "setting",
        // Facts about the device, the install or the purchase.
        "faceDownAvailable" to "device", "screenTimeoutMs" to "device", "bioState" to "device", "bioAllStrong" to "device",
        "bioButtonLabel" to "device", "cameraPermitted" to "device",
        "voiceMemoPermitted" to "device", "coverGrant" to "device", "hasVault" to "install",
        // `per-vault-intruder-capture` D8, D9. Re-read on every resume.
        "capturePermsHeld" to "device", "autoRevokeExempt" to "device",
        "welcomeSeen" to "install", "languageSeen" to "install", "owned" to "purchase", "purchasePending" to "purchase",
        "price" to "purchase", "billingRev" to "purchase", "decoyBuiltThisRun" to "process",
        // The cover is raised, not cleared.
        "coverShowing" to "raised by onStart",
        // `recents-shows-identity` D1, D4. Set in onUserLeaveHint and cleared by returnFromLeave
        // in onStart and onResume: the activity's arrival and departure, not the session's.
        "leaving" to "cleared by returnFromLeave",
        // The cover's own unlock state, owned by the activity's start and stop like the
        // cover itself: read in onStart, cleared in onStop (hideCoverDots) and on an
        // accepted trigger. None of it is the session's.
        "coverHoldArea" to "read in onStart", "coverCalculatorMethod" to "read in onStart",
        "coverDots" to "cleared by hideCoverDots", "coverDotsError" to "cleared by hideCoverDots",
        // The lock screen's own messages — they are what the lock screen says.
        "unlockFailed" to "lock screen", "notice" to "lock screen", "newerVersion" to "lock screen",
        "indexUnreadable" to "lock screen", "indexRelinked" to "lock screen",
        "bioNotice" to "lock screen", "warnings" to "lock screen",
        // Work that keeps running across the lock and reports its own end.
        "busy" to "running work", "importing" to "running work", "setupProgress" to "running work",
        "archiveProgress" to "running work", "exportProgress" to "running work",
        "panicBusy" to "running work", "panicEnabled" to "re-read on every open",
        // Reset by their own screen's open function; unreachable while that screen is closed.
        "frameOutcome" to "viewer's answer",
        "panicSettled" to "openPanicCredential", "captureProblem" to "capture sheet open",
        "purchaseProblem" to "buy, beginShare, beginShareOpen", "pairingStep" to "openPairing",
        "shareSealedTo" to "beginShare", "shareContacts" to "beginShare",
        "mirrorDestination" to "openBackup",
        "setupProblem" to "onboarding step",
        // `first-vault-naming`. The first vault's name and look, held before any keyring exists
        // and cleared when the vault is created, so there is never a session for a lock to end.
        "firstName" to "first run, before any keyring", "firstAppearance" to "first run, before any keyring",
        "firstNameError" to "first run, before any keyring",
        // A recomposition key: a count of views spent this process, naming no kit and no list.
        "kitViewsSpent" to "recomposition key",
        // `destination-settings-and-slideshow` D5. A memo records through the lock, so what
        // describes it is the recording's and not the session's: a mirror of `VoiceMemo.changes`,
        // and the refused-notifications sentence, reset when the memo screen is opened or closed.
        "memoChanges" to "recomposition key", "memoNotice" to "openVoiceMemo, closeVoiceMemo",
        // `app-languages` D3. The install's language, not the session's: a lock does not change it.
        "languageEpoch" to "recomposition key",
        // `third-party-notices`. What the build is made of, read once at start; no vault's.
        "notices" to "the install's, not the session's",
    )

    private fun source(): String {
        var dir: File? = File(".").canonicalFile
        val rel = "src/main/java/com/app/vault/MainActivity.kt"
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isFile }
                ?.let { return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate MainActivity.kt from ${File(".").absolutePath}")
    }

    private fun body(src: String, name: String): String? {
        val start = Regex("""private fun $name\(\)\s*\{""").find(src) ?: return null
        var depth = 1
        var i = start.range.last + 1
        while (depth > 0) {
            when (src[i]) { '{' -> depth++; '}' -> depth-- }
            i++
        }
        return src.substring(start.range.last + 1, i - 1).replace(Regex("""//.*"""), "")
    }

    @Test
    fun everyScreenFlagIsReset() {
        val src = source()
        val fields = Regex("""private va[rl] (\w+) by mutable\w*StateOf""").findAll(src).map { it.groupValues[1] } +
            Regex("""private val (\w+)State = mutableStateOf""").findAll(src).map { it.groupValues[1] }
        val funs = Regex("""private fun (\w+)\(\)""").findAll(src).map { it.groupValues[1] }.toSet()

        val covered = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        val todo = ArrayDeque(listOf("resetForLock"))
        assertTrue("resetForLock is missing", body(src, "resetForLock") != null)
        while (todo.isNotEmpty()) {
            val f = todo.removeFirst()
            if (!seen.add(f)) continue
            val b = body(src, f) ?: continue
            covered += Regex("""\b(\w+)\s*=(?!=)""").findAll(b).map { it.groupValues[1] }
            covered += Regex("""\b(\w+)\.clear\(\)""").findAll(b).map { it.groupValues[1] }
            todo += Regex("""\b(\w+)\(\)""").findAll(b).map { it.groupValues[1] }.filter { it in funs }
        }

        val missing = fields.filter { it !in covered && it !in notTheSession }.toList()
        assertEquals("state a lock leaves behind — reset it in resetForLock, or state why not", emptyList<String>(), missing)
    }

    @Test
    fun theCoverIsTheFirstBranch() {
        val src = source()
        val kit = src.indexOf("kit != null && onboardingStep == null ->")
        assertTrue("the kit branch moved; update this test", kit > 0)
        val whenAt = src.lastIndexOf("when {", kit)
        val firstBranch = src.substring(whenAt + "when {".length, kit)
            .lines().map { it.trim() }.first { it.isNotEmpty() && !it.startsWith("//") }
        assertTrue("the cover must be drawn ahead of every screen, found: $firstBranch", firstBranch.startsWith("coverShowing ->"))
    }
}
