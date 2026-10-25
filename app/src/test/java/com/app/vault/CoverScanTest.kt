package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What a cover must never become, checked against the source rather than argued about.
 *
 * A cover is the one surface in this app that is *supposed* to look like something
 * else, which makes it the one place where a well-meant addition does real damage: a
 * haptic buzz on a failed trigger, a history tape, a draft of the compose field, an
 * encrypted blob in a "flashlight". Every one of those is a small, reasonable-looking
 * change, and every one of them undoes the feature. So they are build failures.
 *
 * JVM scope: it reads source, so no emulator is involved.
 */
class CoverScanTest {

    private fun repoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "app/src/main/AndroidManifest.xml").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("repo root not found")
    }

    private fun source(name: String): File {
        val root = File(repoRoot(), "app/src/main/java/com/app/vault")
        return root.walkTopDown().first { it.name == name }
    }

    /**
     * Strings out before comments, for the reason `ThemeScanTest` records: the SAF
     * any-type wildcard elsewhere in this app opens a block comment as far as a naive
     * stripper is concerned. Every check here scans for something's *absence*, so
     * being handed a truncated file would make them pass by seeing nothing.
     */
    private fun code(f: File): String = f.readSource()
        .replace(Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL), "\"\"")
        .replace(Regex("\"(\\\\.|[^\"\\\\])*\""), "\"\"")
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\n]*"), "")

    private val coverFiles = listOf(
        "CalculatorCover.kt", "MusicCover.kt", "RecorderCover.kt",
        "CoverGestures.kt", "IdentitySetupScreen.kt", "IdentityCommitSheet.kt",
        // `pattern-cover-trigger`. Setup-only, the same standing IdentitySetupScreen.kt
        // already has, and held to the same scans for the same reason.
        "PatternGridOverlay.kt",
    )

    private fun covers(): List<File> = coverFiles.map { source(it) }

    @Test
    fun theCoverFilesExist() {
        // Without this the scans below pass vacuously — the failure mode a check for
        // something absent can always have.
        covers().forEach { assertTrue("${it.name} is missing", it.isFile) }
        assertEquals(coverFiles.size, covers().size)

        // And the stripper leaves real code behind. Every check below scans for
        // something's *absence*, so a `code()` that returned nothing would make all
        // of them pass while measuring nothing — the worst failure mode this kind of
        // test can have, and the reason `themePackageIsNotEmpty` exists next door.
        covers().forEach { file ->
            val text = code(file)
            assertTrue("${file.name} stripped to nothing", text.length > 400)
            assertTrue("${file.name} lost its declarations", text.contains("fun "))
        }
    }

    // --- silence -------------------------------------------------------------

    /**
     * ***`combinedClickable` fires long-press haptic feedback by default.*** A buzz
     * on a control that visibly does nothing says *something is here* to whoever is
     * holding the phone, which is the single thing a cover exists to avoid saying.
     */
    @Test
    fun noCoverUsesCombinedClickableOrAnyFeedback() {
        val banned = listOf(
            "combinedClickable", "performHapticFeedback", "HapticFeedback",
            "Toast", "makeText", "playSoundEffect",
        )
        covers().forEach { file ->
            val text = code(file)
            banned.forEach {
                assertTrue("${file.name} uses $it — a cover must be silent", !text.contains(it))
            }
        }
    }

    /** And nothing in a cover logs. A trigger in logcat is the whole answer. */
    @Test
    fun noCoverLogs() {
        (covers() + listOf(source("Cover.kt"), source("CoverTrigger.kt"), source("CoverStore.kt")))
            .forEach { file ->
                val text = code(file)
                assertTrue("${file.name} imports Log", !text.contains("android.util.Log"))
                assertTrue(
                    "${file.name} logs",
                    !Regex("Log[.][dviwe]\\(").containsMatchIn(text),
                )
            }
    }

    // --- what a cover may not keep -------------------------------------------

    /**
     * A cover writes plaintext preferences and nothing else. `filesDir` is
     * `FORMAT.md` §2's enumerated directory — a cover writing there would be a vault
     * surface, and it would need the vault open to do it.
     */
    @Test
    fun noCoverWritesToTheVaultsDirectory() {
        (covers() + listOf(source("CoverStore.kt"))).forEach { file ->
            val text = code(file)
            listOf("filesDir", "BlobStore", "MetadataDb", "VaultSession", "VaultNote").forEach {
                assertTrue("${file.name} reaches $it — a cover is not a vault surface", !text.contains(it))
            }
        }
    }

    /**
     * ***The recorder is the one cover that owns files, and this is where that is
     * bounded*** — `music-recorder-covers`.
     *
     * It writes plaintext `.m4a` into a directory of its own. What it must never do is
     * reach the vault's: a blob, the slot array or the index would make a cover a
     * vault surface, and an encrypted recording would be the one file on the device
     * that makes no sense for the app it claims to be.
     *
     * `MediaStore` and external storage are barred as well — I3 draws a hard line at
     * `MediaStore` for plaintext, and I9 keeps everything of this app's in `filesDir`.
     */
    @Test
    fun theRecorderWritesOnlyItsOwnDirectory() {
        val text = code(source("RecorderStore.kt"))
        assertTrue(
            "the recorder does not use a directory of its own",
            text.contains("File(context.filesDir, DIR)"),
        )
        listOf(
            "BlobStore", "MetadataDb", "VaultSession", "VaultNote", "vault.slots", "blobs",
            "MediaStore", "getExternalFilesDir", "Environment", "cacheDir",
        ).forEach {
            assertTrue(
                "RecorderStore reaches $it — its recordings live in its own directory",
                !text.contains(it),
            )
        }
    }

    /**
     * ***A cover never raises a system permission dialog.***
     *
     * The dialog reads the **application** label — the vault's real name — which no
     * alias label reaches. A player that asked for the music library the first time it
     * opened would introduce the app by name to whoever was holding the phone, so the
     * grant is taken in identity setup and the cover only reports that it is missing.
     */
    @Test
    fun noCoverRequestsAPermission() {
        covers().filter { it.name.endsWith("Cover.kt") }.forEach { file ->
            val text = code(file)
            listOf(
                "requestPermissions(", "ActivityResultContracts", "checkSelfPermission",
                "registerForActivityResult",
            ).forEach {
                assertTrue(
                    "${file.name} uses $it — a cover must not ask; setup asks",
                    !text.contains(it),
                )
            }
        }
    }

    /**
     * ***No cover stores ciphertext.*** An encrypted blob inside a "flashlight" is
     * the one file on the device that makes no sense for the app it claims to be —
     * I8's tell arriving through a different door.
     */
    @Test
    fun noCoverEncryptsAnything() {
        (covers() + listOf(source("CoverStore.kt"), source("CoverTrigger.kt"))).forEach { file ->
            val text = code(file)
            listOf("Cipher", "SecretKey", "Keystore", "KeyStore", "AEAD", "GCM", "seal(").forEach {
                assertTrue("${file.name} encrypts — covers store plaintext by design", !text.contains(it))
            }
        }
    }

    /**
     * The calculator keeps no history tape and persists nothing. A tape records what
     * was typed, and what was typed is the trigger.
     */
    @Test
    fun theCalculatorPersistsNothing() {
        val text = code(source("CalculatorCover.kt"))
        listOf("SharedPreferences", "getSharedPreferences", "CoverStore", "rememberSaveable")
            .forEach {
                assertTrue("CalculatorCover uses $it — it must persist nothing", !text.contains(it))
            }
    }

    /**
     * The recorder's search field is never drafted. The trigger **is** the word typed
     * into that field, so a draft writes the trigger to disk in plaintext — the one
     * leak the whole arrangement exists to avoid.
     */
    @Test
    fun theRecordersSearchFieldIsNeverDrafted() {
        val text = code(source("RecorderCover.kt"))
        listOf("rememberSaveable", "getSharedPreferences").forEach {
            assertTrue("RecorderCover uses $it — the field must not survive", !text.contains(it))
        }
    }

    /**
     * And the player keeps no record of which controls were pressed. Its trigger is a
     * sequence of presses, so a press log *is* the trigger written down — the same
     * rule that keeps the calculator from holding a tape.
     */
    @Test
    fun thePlayerKeepsNoPressHistory() {
        val text = code(source("MusicCover.kt"))
        listOf("rememberSaveable", "getSharedPreferences").forEach {
            assertTrue("MusicCover uses $it — presses must not survive", !text.contains(it))
        }
        val store = code(source("CoverStore.kt"))
        listOf("Step", "steps", "presses").forEach {
            assertTrue("CoverStore holds $it — a press log is the trigger", !store.contains(it))
        }
    }

    // --- the trigger is never the credential ---------------------------------

    /**
     * ***The load-bearing assertion of the whole task.*** A cover reveals the unlock
     * screen; the passphrase still opens the vault. If a trigger ever derived key
     * material, an offline attack path would open against ten to fifteen bits — the
     * pattern-unlock failure `Do not build` refused, arriving through a door P2-24
     * opened.
     */
    @Test
    fun nothingInTheTriggerPathTouchesKeyMaterial() {
        val banned = listOf(
            "Argon2", "deriveKey", "SecretKey", "Cipher", "unwrap", "Slot",
            "vmk", "Vmk", "VMK", "passphrase", "Passphrase",
        )
        (covers() + listOf(source("Cover.kt"), source("CoverTrigger.kt"))).forEach { file ->
            val text = code(file)
            banned.forEach {
                assertTrue(
                    "${file.name} names $it — a trigger must never be the credential",
                    !text.contains(it),
                )
            }
        }
    }

    /**
     * One place judges an attempt, so silence cannot be forgotten at a call site: a
     * cover that compared a trigger itself would also have to remember not to react
     * to the answer.
     */
    @Test
    fun onlyCoverTriggerJudgesAnAttempt() {
        covers().forEach { file ->
            val text = code(file)
            assertTrue(
                "${file.name} matches a trigger itself — it must call CoverTrigger.attempt",
                !text.contains(".matches("),
            )
        }
    }

    /** And the covers do not read the stored trigger at all. */
    @Test
    fun noCoverReadsTheStoredTrigger() {
        covers().filter { it.name.endsWith("Cover.kt") }.forEach { file ->
            val text = code(file)
            assertTrue(
                "${file.name} reads the stored trigger — it should only report input",
                !text.contains("triggerFor") && !text.contains("CoverTrigger"),
            )
        }
    }

    // --- the covers' permission story ----------------------------------------

    /**
     * ***The manifest declares the two audio permissions and nothing else new***, and
     * the gate that used to fail on every `READ_MEDIA_*` now permits exactly the audio
     * one — `music-recorder-covers`.
     *
     * The permission list is the one claim about this app anybody can check without
     * trusting us, so what it is allowed to contain is asserted here rather than left
     * to a build task nobody reads the output of.
     */
    @Test
    fun theManifestDeclaresTheAudioPermissionsAndNoOther() {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml").readSource()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertTrue(
            "READ_MEDIA_AUDIO is not declared — the music player cannot read the library",
            manifest.contains("android.permission.READ_MEDIA_AUDIO"),
        )
        assertTrue(
            "READ_EXTERNAL_STORAGE must be capped at 32, where READ_MEDIA_AUDIO begins",
            manifest.contains("android:maxSdkVersion=\"32\""),
        )
        listOf("READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_VISUAL_USER_SELECTED")
            .forEach {
                assertTrue("the manifest declares $it", !manifest.contains(it))
            }
        val internet = Regex("uses-permission[^>]*INTERNET[^>]*>").find(manifest)?.value
        assertTrue(
            "INTERNET is declared without being stripped — invariant I1",
            internet == null || internet.contains("tools:node=\"remove\""),
        )
    }

    /**
     * The gate itself: `READ_MEDIA_AUDIO` by **exact name**, so permitting audio does
     * not quietly permit images, video or user-selected visual media.
     */
    @Test
    fun theGatePermitsAudioByNameAndNotByPrefix() {
        val gradle = File(repoRoot(), "app/build.gradle.kts").readSource()
        assertTrue(
            "the gate no longer forbids READ_MEDIA_*",
            gradle.contains("startsWith(\"android.permission.READ_MEDIA_\")"),
        )
        assertTrue(
            "the gate does not carve out READ_MEDIA_AUDIO by name",
            gradle.contains("it != \"android.permission.READ_MEDIA_AUDIO\""),
        )
    }

    // --- how a trigger is set up ---------------------------------------------

    /**
     * ***A trigger is performed, never typed.***
     *
     * The screen this replaced took a space-separated key list and two enum pickers,
     * so a user could commit to a gesture the cover cannot produce and discover it on
     * a phone whose only launcher entry had already become that cover. There is no
     * recovery from that by design. The setup screen therefore holds no field and no
     * picker: the only way to make a trigger is to make it on the cover.
     */
    @Test
    fun setupAcceptsNoTypedTrigger() {
        setupFlowFiles.forEach { name ->
            val text = code(source(name))
            listOf("OutlinedTextField", "TextField", "BasicTextField", "onValueChange").forEach {
                assertTrue("$name uses $it — a trigger must be performed", !text.contains(it))
            }
        }
    }

    /**
     * Setup captures a gesture; it does not test one. Calling `attempt` here would
     * charge the owner's own limiter for rehearsing and could lock them out of the
     * screen they are standing on.
     */
    @Test
    fun setupNeverConsultsTheLimiter() {
        setupFlowFiles.forEach { name ->
            val text = code(source(name))
            listOf("attempt(", "Outcome", "attempts(", "clearAttempts").forEach {
                assertTrue("$name uses $it — setup captures, it does not judge", !text.contains(it))
            }
        }
    }

    /**
     * The step and both performances live in composition, so leaving the screen
     * discards them. A store of its own here would be state that survives an
     * abandoned setup — the one thing the flow promises it does not do.
     */
    @Test
    fun setupKeepsNoStoreOfItsOwn() {
        setupFlowFiles.forEach { name ->
            val text = code(source(name))
            listOf("getSharedPreferences", "SharedPreferences", "rememberSaveable", "CoverStore")
                .forEach {
                    assertTrue("$name uses $it — setup must persist nothing", !text.contains(it))
                }
        }
    }

    /**
     * The flow is two files now: the sheet an identity is committed from, and the
     * screen the gesture is recorded on. Every scan that held for one holds for both —
     * the sheet is where the permission dialog is raised, which is exactly the place a
     * field or a store would be most tempting to add.
     */
    private val setupFlowFiles = listOf("IdentitySetupScreen.kt", "IdentityCommitSheet.kt")

    /**
     * `launcher-identity` requires the pinned-shortcut cost stated **once**. It moved
     * from the chooser's rows to the preview step, and from there — when the preview
     * step went — to the commit sheet, which is the surface it is now paid from. The
     * failure this catches is it ending up in two of them.
     */
    @Test
    fun thePinnedShortcutCostIsStatedInExactlyOneFile() {
        val root = java.io.File(repoRoot(), "app/src/main/java/com/app/vault")
        val saying = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readSource().contains("pinned shortcut") }
            .map { it.name }
            .toList()
        assertEquals("the pinned-shortcut cost must be stated once, in one place", 1, saying.size)
        assertEquals("IdentityCommitSheet.kt", saying.single())
    }

    /**
     * ***No screen in this app asks the user to allow access.***
     *
     * The step this deletes read "Allow access" over a button whose only effect was to
     * raise the system dialog that asks the same question — two consents for one
     * decision, and the platform's answer to "should I explain myself"
     * (`shouldShowRequestPermissionRationale`) was never consulted, because before a
     * first request it returns false and so the step had to be unconditional.
     *
     * `CoverScreenTest` already fails if either cover grows such a button. This fails
     * if any file does, which is the version that survives somebody adding a new
     * screen rather than a new control.
     */
    @Test
    fun nothingInTheAppOffersAnAllowAccessButton() {
        // Line-based, and comments kept out by their leading marker rather than by
        // `code`: the five files that explain why this button is gone must go on saying
        // so, and a stripper that removed the strings instead would pass by seeing
        // nothing. The SAF wildcard that defeats a naive block-comment stripper — the
        // reason `code` takes strings out first — cannot reach a line test.
        val offering = java.io.File(repoRoot(), "app/src/main/java/com/app/vault")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.readLines().any { line ->
                    val trimmed = line.trim()
                    "Allow access" in line &&
                        !trimmed.startsWith("*") &&
                        !trimmed.startsWith("//") &&
                        !trimmed.startsWith("/*")
                }
            }
            .map { it.name }
            .toList()
        assertTrue("$offering still offers to ask for a grant", offering.isEmpty())
    }

    /**
     * ***A live cover reports nothing, and the default is what enforces it.***
     *
     * `onProgress` exists so trigger setup can acknowledge a press as it lands. A live
     * launcher entry that did the same would tell whoever is holding the phone that a
     * gesture exists. `MainActivity` draws every live cover, and it must pass no
     * reporter to any of them — the null default then means there is nothing to switch
     * off and no flag anybody has to remember to set.
     */
    @Test
    fun noLiveCoverIsGivenAProgressReporter() {
        val text = code(source("MainActivity.kt"))
        assertTrue(
            "MainActivity passes onProgress — a live cover must acknowledge nothing",
            !text.contains("onProgress"),
        )
        listOf("CalculatorCover.kt", "MusicCover.kt", "RecorderCover.kt").forEach {
            assertTrue(
                "$it does not default its reporter to null",
                code(source(it)).contains("onProgress: ((Int) -> Unit)? = null"),
            )
        }
        // `calculator-dots-methods` D3. The calculator's pattern steps report through their own
        // parameter, held to the same rule: setup passes it, a live cover never does.
        assertTrue("MainActivity passes dotsProgress", !text.contains("dotsProgress"))
        assertTrue(
            "CalculatorCover does not default its pattern reporter to null",
            code(source("CalculatorCover.kt")).contains("dotsProgress: ((Int) -> Unit)? = null"),
        )
    }

    /**
     * `calculator-dots-methods`. The calculator now draws dots and takes a hold, and stays silent
     * doing it: the hold is the raw `silentHold`, and the one haptic its dots carry — `REJECT` on
     * a wrong pattern — is `MainActivity`'s, never this file's.
     */
    @Test
    fun theCalculatorsDotsAndHoldAreSilent() {
        val text = code(source("CalculatorCover.kt"))
        listOf("combinedClickable", "performHapticFeedback", "HapticFeedback").forEach {
            assertTrue("CalculatorCover uses $it", !text.contains(it))
        }
        assertTrue("CalculatorCover lost its hold", text.contains(".silentHold("))
        assertTrue("CalculatorCover lost its grid", text.contains(".silentPatternGrid("))
    }

    // --- the grid's floor -----------------------------------------------------

    /** The text between the `(` at [open] and its balanced `)`. */
    private fun parenthesised(text: String, open: Int): String {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(open + 1, i)
            }
        }
        throw AssertionError("unbalanced parentheses")
    }

    /** The named arguments at the top level of an argument list, in order. */
    private fun argumentNames(args: String): List<String> =
        Regex("""(?m)^\s*(\w+)\s*[:=]""").findAll(args).map { it.groupValues[1] }.toList()

    /**
     * ***The grid's floor cannot be lowered quietly.*** The grid reports at
     * [Trigger.MIN_DOTS] and nothing else, it takes no floor of its own, and both pattern
     * covers pass it exactly what they passed before — the revealed dots of
     * `cover-unlock-methods` included, which reuse `onProgress` rather than adding a
     * parameter. A four-node floor would put a curved scroll on the counted side of the
     * limiter.
     */
    @Test
    fun theGridsFloorIsStillTheMainPatterns() {
        val gestures = code(source("CoverGestures.kt"))
        val at = gestures.indexOf("fun Modifier.silentPatternGrid(")
        assertTrue("CoverGestures no longer declares silentPatternGrid", at >= 0)
        val params = parenthesised(gestures, gestures.indexOf('(', at))
        assertEquals(
            "silentPatternGrid grew a parameter — a floor passed in is a floor that can be lowered",
            listOf("onProgress", "onDrawn"),
            argumentNames(params),
        )
        assertTrue(
            "silentPatternGrid no longer reports at Trigger.MIN_DOTS",
            gestures.contains("visited.size >= Trigger.MIN_DOTS"),
        )

        listOf("MusicCover.kt", "RecorderCover.kt").forEach { name ->
            val text = code(source(name))
            val call = text.indexOf(".silentPatternGrid(")
            assertTrue("$name no longer uses silentPatternGrid", call >= 0)
            assertEquals(
                "$name passes silentPatternGrid something new",
                listOf("onProgress", "onDrawn"),
                argumentNames(parenthesised(text, text.indexOf('(', call))),
            )
        }
    }

    /**
     * The live covers gained nothing a bystander could notice from `cover-unlock-methods`'
     * revealed dots: no long-press, no haptic, no toast — the grid and nothing else.
     */
    @Test
    fun theCoversGainedNothingButTheGrid() {
        listOf("MusicCover.kt", "RecorderCover.kt").forEach { name ->
            val text = code(source(name))
            listOf("combinedClickable", "performHapticFeedback", "HapticFeedback", "Toast", "makeText").forEach {
                assertTrue("$name uses $it — a live cover stays silent", !text.contains(it))
            }
        }
    }

    // --- where the cover sits in the app -------------------------------------

    /**
     * ***The cover is the first branch, ahead of `!unlocked`.***
     *
     * If a live session skipped the cover, the identity would evaporate for exactly
     * as long as the vault is open — which is when it matters. Asserted by position
     * in the source because that is what decides it: `MainActivity`'s top-level
     * `when` picks the first branch that holds.
     */
    @Test
    fun theCoverBranchPrecedesEveryOtherScreen() {
        val text = code(source("MainActivity.kt"))
        val cover = text.indexOf("coverShowing ->")
        assertTrue("MainActivity has no cover branch", cover >= 0)

        listOf("recovering ->", "!hasVault ->", "!unlocked ->").forEach { branch ->
            val at = text.indexOf(branch)
            assertTrue("MainActivity lost its `$branch` branch", at >= 0)
            assertTrue(
                "the cover branch must precede `$branch`, or the identity evaporates",
                cover < at,
            )
        }
    }

    /**
     * `dismissTop`'s list must run in the `when`'s order — that list's own KDoc makes
     * this a rule, because dismissing a flag the `when` would not have reached closes
     * a screen the user cannot see.
     */
    @Test
    fun theBackOrderMatchesTheScreenOrder() {
        val text = code(source("MainActivity.kt"))
        val dismiss = text.indexOf("private fun dismissTop()")
        assertTrue("dismissTop is gone", dismiss >= 0)
        val body = text.substring(dismiss)

        val cover = body.indexOf("coverShowing ->")
        val setup = body.indexOf("settingUpIdentity != null ->")
        // The appearance chooser used to be the anchor here. It is a drawer control
        // now and has no back entry at all, so the identity screen — the next-oldest
        // screen in the `when` — is what trigger setup must still precede.
        val older = body.indexOf("identityScreen ->")
        assertTrue("back does not handle the cover", cover >= 0)
        assertTrue("back does not handle trigger setup", setup >= 0)
        assertTrue("back does not handle the identity screen", older >= 0)
        assertTrue("the cover must come first in the back order", cover < setup)
        assertTrue("trigger setup must precede the older screens", setup < older)
    }

    /**
     * **Back on a cover leaves the app.** It must dismiss nothing — an app that
     * navigates somewhere else when you back out of its "calculator" has said the
     * calculator has somewhere else to be.
     */
    @Test
    fun backOnACoverDismissesNothing() {
        val text = code(source("MainActivity.kt"))
        val at = text.indexOf("coverShowing ->", text.indexOf("private fun dismissTop()"))
        val branch = text.substring(at, at + 60)
        assertTrue(
            "back on a cover changes state; it must fall through to the system default",
            branch.contains("Unit"),
        )
    }

    /**
     * The cover is shown whenever the active identity has one — read from the
     * identity rather than from a second flag that could disagree with it.
     */
    @Test
    fun theCoverIsShownWheneverTheIdentityHasOne() {
        val text = code(source("MainActivity.kt"))
        assertTrue(
            "coverShowing is not derived from the identity's cover",
            text.contains("coverShowing = identity.cover != null"),
        )
    }

    /**
     * ***The accept path clears the cover and does nothing else.*** It does not
     * unlock, does not mark a session, does not touch `hasVault`. What follows is the
     * ordinary `when`, which means the unlock screen and the passphrase.
     */
    @Test
    fun theAcceptPathOnlyDismissesTheCover() {
        val text = code(source("MainActivity.kt"))
        val at = text.indexOf("private fun coverTrigger(")
        assertTrue("MainActivity has no trigger handler", at >= 0)
        val body = text.substring(at, text.indexOf("private fun coverGranted"))

        assertTrue("the trigger handler must clear the cover", body.contains("coverShowing = false"))
        listOf("unlocked = true", "hasVault = true", "VaultSession", "attemptUnlock").forEach {
            assertTrue(
                "the trigger handler reaches $it — a trigger is not authentication",
                !body.contains(it),
            )
        }
    }

    /**
     * `cover-hold-dots` D6. No cover reads the fingerprint sensor — on under-display and
     * power-button sensors Android answers such a read with its own prompt over the cover — and
     * there is one biometric wrap, the vault's: the accept path opens no keyring, from shown dots
     * or hidden ones.
     */
    @Test
    fun noCoverReadsTheSensorAndThereIsOneWrap() {
        val main = File(repoRoot(), "app/src/main/java/com/app/vault").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        main.forEach { file ->
            val text = code(file)
            assertTrue(
                "${file.name} names FingerprintManager — nothing reads the sensor outside the prompt",
                !text.contains("FingerprintManager"),
            )
        }
        val activity = code(source("MainActivity.kt"))
        assertEquals("one biometric wrap, the vault's", 1, Regex("BiometricWrap\\(").findAll(activity).count())
        val at = activity.indexOf("private fun coverTrigger(")
        val body = activity.substring(at, activity.indexOf("private fun coverGranted"))
        listOf("resolveFromWrap", "keyringOpen = true", "OpenVault").forEach {
            assertTrue("the trigger path reaches $it — the hold and the dots open nothing", !body.contains(it))
        }
    }

    /**
     * **A cover must not crash, and this is the line that stopped one.**
     *
     * `CoverScreenTest`'s mash found `1.2.3` reaching `toDouble` and throwing — the
     * loudest possible tell, a "calculator" dying under a curious finger. The input
     * guard is the fix; this asserts the parse stayed defensive as well, because the
     * two protect against different future edits.
     */
    @Test
    fun theCalculatorNeverParsesUnsafely() {
        val text = code(source("CalculatorCover.kt"))
        assertTrue(
            "CalculatorCover uses toDouble — a throw here is the loudest possible tell",
            !Regex("\\.toDouble\\(\\)").containsMatchIn(text),
        )
        // The guard for a second decimal point, named by the thing it guards rather
        // than by the variable it used to be written against. `calculator-cover-aesthetic`
        // renamed the entry buffer and made the separator the locale's, so the literal
        // `pending.contains(".")` this used to look for no longer exists — the guard
        // does, and it is what the mashing test found `1.2.3` reaching without.
        assertTrue(
            "CalculatorCover must guard a second decimal point",
            text.contains("contains(Calculator.DECIMAL)"),
        )
    }

    /**
     * ***No key label contains a Unicode superscript.***
     *
     * `x²`, `xʸ`, `sin⁻¹`, `eˣ` and `10ˣ` are drawn with a baseline shift instead.
     * `ˣ` and `ʸ` are modifier letters and `⁻¹` is a superscript block a font need
     * not carry at all — Inter's coverage is not the guarantee, the shipping device's
     * fallback chain is, and there isn't one. A tofu box on a calculator key is a
     * louder tell than every functional gap `calculator-cover-aesthetic` closed.
     *
     * Raw source: `code()` strips string literals, and the labels *are* string
     * literals.
     */
    @Test
    fun noCalculatorKeyLabelUsesAUnicodeSuperscript() {
        val labels = stringLiterals(source("CalculatorCover.kt").readSource())
        assertTrue("found no labels to scan", labels.size > 20)
        listOf("\u00B2", "\u00B3", "\u00B9", "\u02E3", "\u02B8", "\u207B", "\u2070", "\u2071")
            .forEach { glyph ->
                val offenders = labels.filter { it.contains(glyph) }
                assertTrue(
                    "CalculatorCover uses the Unicode superscript U+%04X in %s — use BaselineShift"
                        .format(glyph.single().code, offenders),
                    offenders.isEmpty(),
                )
            }
        // And the mechanism that replaces them is actually present, so this cannot
        // pass by the labels having been dropped instead.
        assertTrue(
            "CalculatorCover draws no superscript at all",
            source("CalculatorCover.kt").readSource().contains("BaselineShift.Superscript"),
        )
    }

    /**
     * Every double-quoted literal in a file, comments excluded.
     *
     * The inverse of [code], and it has to be: the labels *are* string literals, so
     * the usual stripper blanks exactly what this needs to look at — while the KDoc
     * next to the rule names the very glyphs the rule forbids. A scanner that cannot
     * tell an explanation from a label would make documenting the decision impossible,
     * which is `DesignTokensTest`'s argument about dynamic colour, one door over.
     */
    private fun stringLiterals(text: String): List<String> {
        val found = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c == '/' && next == '/' -> { while (i < text.length && text[i] != '\n') i++ }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                }
                c == '"' -> {
                    val start = ++i
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\') i++
                        i++
                    }
                    found += text.substring(start, minOf(i, text.length))
                    i++
                }
                else -> i++
            }
        }
        return found
    }

    /**
     * ***The recorder cover shares nothing with the vault's own voice memo*** —
     * `music-recorder-covers`.
     *
     * They record the same thing by opposite rules. `VoiceMemo` streams into the blob
     * store and may never touch a plaintext file; this writes a plaintext file and may
     * never touch the blob store. A shared recorder would have to hold both rules at
     * once, and the one it broke would be silent.
     */
    @Test
    fun theRecorderCoverSharesNothingWithTheVaultsMemo() {
        (listOf(source("RecorderCover.kt"), source("RecorderStore.kt"))).forEach { file ->
            val text = code(file)
            listOf("VoiceMemo", "VoiceMemoScreen", "CaptureIngest", "IntruderCapture", "BlobStore")
                .forEach {
                    assertTrue(
                        "${file.name} reaches $it — the cover records into its own directory",
                        !text.contains(it),
                    )
                }
        }
    }

    /**
     * ***And the music player reads the library, never the vault's media path.***
     *
     * The vault plays encrypted blobs through a media3 `DataSource` of its own. The
     * cover plays `content://` URIs from `MediaStore`. Two mechanisms for two kinds of
     * bytes, and the cover must not learn about the other one.
     */
    @Test
    fun theMusicCoverReachesNoVaultMedia() {
        (listOf(source("MusicCover.kt"), source("MusicPlayback.kt"), source("MusicLibrary.kt")))
            .forEach { file ->
                val text = code(file)
                listOf(
                    "BlobStore", "VaultDataSource", "MetadataDb", "VaultSession",
                    "ThumbnailStore", "ThumbnailCache",
                )
                    .forEach {
                        assertTrue(
                            "${file.name} reaches $it — the cover plays the device's own audio",
                            !text.contains(it),
                        )
                    }
            }
    }

    // --- what the set is, and what is gone -----------------------------------

    /**
     * ***The identity set is exactly four*** — `music-recorder-covers`.
     *
     * Named here rather than inferred, because the failure this catches is an addition:
     * a fifth cover arriving without the argument that put the first three in, which is
     * the thing `cover-front-ends` requires be amended before it happens.
     */
    @Test
    fun theIdentitySetIsExactlyTheFour() {
        assertEquals(
            listOf("Vault", "Calculator", "MusicPlayer", "VoiceRecorder"),
            LauncherIdentity.entries.map { it.name },
        )
        assertEquals(
            listOf("Calculator", "MusicPlayer", "VoiceRecorder"),
            Cover.entries.map { it.name },
        )
        assertEquals(
            "an identity has a cover the covers do not know, or the reverse",
            Cover.entries.toSet(),
            LauncherIdentity.entries.mapNotNull { it.cover }.toSet(),
        )
    }

    /**
     * ***Nothing in the shipping app still names a flashlight or a notes cover.***
     *
     * A removal that leaves a string, a drawable or a `when` branch behind is a
     * removal that half happened, and the half left behind is the one that turns up in
     * a review as "why does the vault mention a flashlight". The vault's own notes
     * feature is a different thing entirely and is not searched for here — only the
     * cover's own names are.
     */
    @Test
    fun nothingStillNamesTheRemovedCovers() {
        val root = File(repoRoot(), "app/src/main")
        val offenders = root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { file ->
                val text = file.readSource()
                listOf(
                    "FlashlightCover", "NotesCover", "identity_flashlight", "identity_notes",
                    "ic_launcher_flashlight", "ic_launcher_notes", "alias.Flashlight",
                    "alias.Notes", "Cover.Flashlight", "Cover.Notes", "Trigger.Blinks",
                    "Trigger.Word", "setTorchMode",
                ).any { text.contains(it) }
            }
            .map { it.name }
            .toList()
        assertEquals("the removed covers are still named in: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun noUsesFeatureIsDeclared() {
        val manifest = File(repoRoot(), "app/src/main/AndroidManifest.xml").readSource()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertTrue(
            "a uses-feature element was added — required would let Play filter the app off devices",
            !manifest.contains("uses-feature"),
        )
    }
}
