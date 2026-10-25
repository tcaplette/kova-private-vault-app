package com.app.vault

import com.app.vault.ui.theme.Spacing
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-6's enforcement, in `DesignTokensTest`'s shape.
 *
 * `FLAG_SECURE` returns pure black to every screenshot (measured in P0-1), so the
 * chrome cannot be verified by looking at it. These read the source and the tokens
 * instead — the same reason the palette is checked by recomputation rather than by
 * eye.
 *
 * What this exists to stop is specific. Before P2-6 the app had a dead
 * `onBackPressed` override, four window attributes that stopped doing anything at
 * `targetSdk` 35, and thirteen of fourteen screens with no inset handling. Every
 * one of those was invisible to review for the same reason: nothing failed. Each
 * assertion below is one of those failures, converted into a build error.
 */
class ScreenChromeTest {

    private fun sources(rel: String): List<File> {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isDirectory }
                ?.let { d -> return d.walkTopDown().filter { it.isFile }.toList() }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $rel from ${File(".").absolutePath}")
    }

    private fun kotlinSources(): List<File> =
        sources("src/main/java/com/app/vault").filter { it.extension == "kt" }

    private fun resFile(name: String): File =
        sources("src/main/res").single { it.name == name }

    /**
     * Remove comments, optionally blanking string literals too.
     *
     * **Why this is a scanner and not the usual two-regex chain.** The chain is
     * wrong on this codebase, and was caught being wrong while P2-6 was written.
     * `MainActivity` opens documents with the wildcard MIME type — a string whose
     * payload contains a slash-star. A block-comment regex cannot tell that from
     * real syntax, so it treats the MIME type as the start of a comment, runs to
     * the next genuine comment terminator, and deletes about forty lines of live
     * code on the way — including the `enableEdgeToEdge()` call that one of the
     * tests below exists to find.
     *
     * That is a guard failing *open*: it reported compliance because it could no
     * longer see the code it was judging. A checker that silently stops checking
     * is worse than no checker, so this one tracks string state rather than
     * guessing at it.
     */
    private fun strip(text: String, keepStrings: Boolean): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("\"\"\"", i) -> {
                    val close = text.indexOf("\"\"\"", i + 3)
                    val end = if (close < 0) text.length else close + 3
                    if (keepStrings) out.append(text, i, end)
                    i = end
                }
                text[i] == '"' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != '"') {
                        if (text[j] == '\\') j++
                        j++
                    }
                    val end = minOf(j + 1, text.length)
                    if (keepStrings) out.append(text, i, end)
                    i = end
                }
                // A char literal can hold a quote — '"' — and would otherwise open
                // a string that never closes.
                text[i] == '\'' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != '\'') {
                        if (text[j] == '\\') j++
                        j++
                    }
                    val end = minOf(j + 1, text.length)
                    if (keepStrings) out.append(text, i, end)
                    i = end
                }
                text.startsWith("/*", i) -> {
                    val close = text.indexOf("*/", i + 2)
                    i = if (close < 0) text.length else close + 2
                }
                text.startsWith("//", i) -> {
                    val nl = text.indexOf('\n', i)
                    out.append('\n')
                    i = if (nl < 0) text.length else nl + 1
                }
                else -> {
                    out.append(text[i])
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Code with comments and string payloads gone — for "is this call present". */
    private fun code(text: String): String = strip(text, keepStrings = false)

    /** Code with the strings intact — for "is this label still rendered". */
    private fun labels(text: String): String = strip(text, keepStrings = true)

    /** The scanner is the thing every other assertion trusts, so it is tested first. */
    @Test
    fun theStripperSurvivesAMimeTypeThatLooksLikeAComment() {
        val src = """
            fun a() { launch(arrayOf("*/*")) }
            fun b() { enableEdgeToEdge() }
        """.trimIndent()
        assertTrue(
            "a /* inside a string must not open a comment",
            code(src).contains("enableEdgeToEdge()"),
        )
        assertTrue(
            "a real block comment must still be removed",
            !code("/* enableEdgeToEdge() */ fun a() {}").contains("enableEdgeToEdge()"),
        )
        assertTrue(
            "a line comment must still be removed",
            !code("// enableEdgeToEdge()\nfun a() {}").contains("enableEdgeToEdge()"),
        )
        assertTrue(
            "labels() keeps string payloads",
            labels("""val x = "Unvault Everything"""").contains("Unvault Everything"),
        )
        assertTrue(
            "labels() still drops comments",
            !labels("""// "Unvault Everything"""").contains("Unvault Everything"),
        )
    }

    // --- 6.1 back, insets, and the bar --------------------------------------

    /**
     * Predictive back is mandatory at `targetSdk` 36 and the opt-out attribute is
     * gone, so an `onBackPressed` override is never called. The app shipped one
     * anyway, which is why back in the viewer left the activity instead of
     * returning to the grid.
     */
    /**
     * Files has no settings, so `MainActivity` hands its header no settings control —
     * `destination-settings-and-slideshow` D1. `DestinationDeviceTest` holds what the grid
     * draws given that null; this holds that the activity gives it.
     */
    @Test
    fun filesIsGivenNoSettingsControl() {
        val activity = code(kotlinSources().single { it.name == "MainActivity.kt" }.readSource())
        assertTrue(
            "MainActivity must pass onDestinationSettings = null on Files",
            Regex("""onDestinationSettings\s*=\s*if\s*\(destination\s*==\s*VaultDestination\.Files\)\s*\{\s*null""")
                .containsMatchIn(activity),
        )
    }

    @Test
    fun noOnBackPressedOverride() {
        val offenders = kotlinSources()
            .filter { code(it.readSource()).contains("fun onBackPressed") }
            .map { it.name }
        assertEquals(
            "onBackPressed is dead at this targetSdk; use BackHandler. Found in: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The bar colours stopped being honoured when Android 15 enforced
     * edge-to-edge. Leaving them in the theme is worse than useless: it reads like
     * bar colour is configured somewhere.
     */
    @Test
    fun themeDeclaresNoDeadWindowAttributes() {
        val theme = resFile("themes.xml").readSource()
        listOf(
            "android:statusBarColor",
            "android:navigationBarColor",
            "android:windowLightStatusBar",
            "android:windowLightNavigationBar",
        ).forEach { attr ->
            assertTrue(
                "$attr is a no-op at targetSdk 37 and must not be in themes.xml",
                !theme.contains(attr),
            )
        }
    }

    /**
     * The wrapper marks the insets it applies as consumed. Without it, a cover hosted
     * in identity setup — which insets itself because it runs full-window everywhere
     * else — pays the status bar and the navigation bar a second time, and the
     * calculator's keypad loses both bands' height.
     */
    @Test
    fun theWrapperConsumesTheInsetsItApplies() {
        val scaffold = kotlinSources().single { it.name == "VaultScaffold.kt" }
        assertTrue(
            "VaultScaffold must consumeWindowInsets(innerPadding) on its content",
            code(scaffold.readSource()).contains("consumeWindowInsets(innerPadding)"),
        )
    }

    /**
     * One place decides insets. `VaultGrid` used to carry the app's only
     * `statusBarsPadding()`; the wrapper now offsets content for the bar it draws,
     * and a leftover call would double the inset on the one screen that was
     * previously correct.
     */
    @Test
    fun insetPaddingLivesOnlyInTheWrapper() {
        // `VaultViewer` is the one screen with no wrapper, deliberately —
        // `DESIGN.md` §6.6 forbids framing the media, so it cannot take the
        // scaffold's bar or its padding. P2-10 gave it a control, and a control
        // under the status bar cannot be tapped. The exemption is for the
        // *control*; the item is never inset.
        //
        // `safeDrawingPadding` joined the list at P2-10. It was absent while the
        // other three were checked, which made the guard avoidable by using the
        // synonym — a guard with a synonym is not a guard.
        // `EditorScreen` joined the exemption at `viewer-chrome-and-editor`, for
        // `VaultViewer`'s reason and no other: it puts the same photograph on the
        // same black ground, so it cannot take the wrapper either — and its own
        // controls are under the same status bar. The item is never inset there
        // either; the padding is on the rows.
        // `CalculatorCover` joined the exemption at `calculator-cover-aesthetic`, and
        // for a reason neither of the other two shares: a cover is not a vault screen.
        // It cannot take `VaultScaffold` — the wrapper is the vault's chrome, and the
        // whole point of a cover is that none of the vault's chrome is on it. Nothing
        // else insets it either; `MainActivity` hosts the covers bare. So the calculator
        // is the only thing that can keep its own keypad off the gesture bar, and it
        // does it with the same modifier the wrapper uses rather than with a synonym.
        // `MusicCover` and `RecorderCover` are exempt on exactly that ground —
        // `music-recorder-covers`: a transport row and a record button are the two
        // controls most likely to sit under the gesture bar, and neither cover may
        // wear the vault's chrome to avoid it.
        // `CameraScreen` joined at `camera-conventions`, on `VaultViewer`'s ground
        // exactly: §6.6 forbids framing the media and a viewfinder is the media
        // before it exists, so the frame fills the screen and takes no wrapper. The
        // exemption is for the *controls* — a shutter under the gesture bar cannot be
        // pressed — and the frame itself is never inset, which is the viewer's rule
        // one screen along.
        //
        // `SlideshowScreen` joined at `destination-settings-and-slideshow`, on the viewer's
        // ground exactly: the viewer draws it over itself, full-bleed with the bars hidden, and
        // only its controls are inset.
        //
        // `MainActivity`'s language snackbar host (`language-picker-redesign` D2) is exempt,
        // and only that one block: it draws over whichever screen is showing — the viewer and
        // the covers included, which take no wrapper — so no `VaultScaffold` is there to inset
        // it. The block is cut out by its shape below; any other inset modifier in the file
        // still fails.
        val snackbarHost = Regex(
            """\.navigationBarsPadding\(\)\s*\.padding\(Spacing\.lg\),\s*contentAlignment = Alignment\.BottomCenter,\s*\)\s*\{\s*SnackbarHost\(snackbars\)"""
        )
        val covers = setOf("CalculatorCover.kt", "MusicCover.kt", "RecorderCover.kt")
        val offenders = kotlinSources()
            .filter {
                it.name != "VaultScaffold.kt" && it.name != "VaultViewer.kt" &&
                    it.name != "EditorScreen.kt" && it.name != "CameraScreen.kt" &&
                    it.name != "SlideshowScreen.kt" &&
                    it.name !in covers
            }
            .filter {
                val src = code(it.readSource())
                    .let { src ->
                        if (it.name != "MainActivity.kt") src
                        else src.replace(snackbarHost, "")
                            .replace("import androidx.compose.foundation.layout.navigationBarsPadding", "")
                    }
                src.contains("statusBarsPadding") ||
                    src.contains("navigationBarsPadding") ||
                    src.contains("systemBarsPadding") ||
                    src.contains("safeDrawingPadding")
            }
            .map { it.name }
        assertEquals(
            "Inset padding belongs to VaultScaffold; found in: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * ***`imePadding` is the synonym that got through, and this is the guard for it.***
     *
     * [insetPaddingLivesOnlyInTheWrapper] names four inset modifiers and its own comment
     * says a guard with a synonym is not a guard. `imePadding` was the fifth and was not
     * on the list — and `VaultGrid`'s bottom block carried one. `VaultScaffold` sets
     * `contentWindowInsets = WindowInsets.safeDrawing`, which **includes the IME**, so the
     * block was paid the keyboard twice: it lifted a second keyboard-height off the
     * bottom, `Column` gave its last child what was left, and `VaultDestinationBar` was
     * laid out at **zero height** while still sitting in the semantics tree — invisible,
     * untappable, and "present and inert", which `grid-fab` forbids in as many words.
     *
     * Two device tests failed on it and neither said so:
     * `NonMediaScreenTest.aDestinationKeepsTheQueryItWasLeftWith` tapped the zero-height
     * bar and nothing happened, and
     * `SearchDeviceTest.typingNarrowsTheGridAndClearingRestoresIt` could not see a tile the
     * shrunken viewport had pushed below the fold.
     *
     * **A `ModalBottomSheet` is exempt, and only it.** A sheet is not drawn inside the
     * scaffold's content, so nothing has paid its keyboard inset and it must pay its own.
     *
     * ponytail: exemption is by file, so a file holding both a sheet and scaffold content
     * could still hide one. Narrow it to the enclosing composable if that ever bites —
     * today all three exempt files are sheets and nothing else.
     */
    @Test
    fun theKeyboardInsetAlsoLivesOnlyInTheWrapper() {
        val offenders = kotlinSources()
            .filter { it.name != "VaultScaffold.kt" }
            .map { it to code(it.readSource()) }
            .filter { (_, src) -> src.contains("imePadding") }
            .filterNot { (_, src) -> src.contains("ModalBottomSheet") }
            .map { it.first.name }
        assertEquals(
            "imePadding belongs to VaultScaffold's safeDrawing, or to a ModalBottomSheet " +
                "which the scaffold does not inset; found in: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * `decoy-flow-overhaul`. A frame that is not `VaultScaffold` still pays its insets.
     *
     * `StepFrame` and `OnboardingFrame` both build their chrome from a plain `Column`
     * inside a `Scaffold`, which is the one shape [insetPaddingLivesOnlyInTheWrapper]
     * cannot speak for: they take no wrapper, so nothing else insets them, and a
     * `Column` handles none of its own. `OnboardingFrame`'s own KDoc records what that
     * costs when it is missed — the step counter under the status bar and the actions
     * under the gesture bar.
     *
     * One `contentWindowInsets = WindowInsets.safeDrawing` plus one `padding(inner)`
     * is the arrangement, and it is asserted rather than reviewed.
     */
    @Test
    fun everyFrameInsetsItselfOnce() {
        listOf("StepFrame.kt" to "StepFrame", "OnboardingScaffold.kt" to "OnboardingFrame")
            .forEach { (file, frame) ->
                val src = code(kotlinSources().single { it.name == file }.readSource())
                assertTrue(
                    "$frame takes no wrapper, so it must ask for safeDrawing itself",
                    src.contains("contentWindowInsets = WindowInsets.safeDrawing"),
                )
                assertTrue(
                    "$frame must apply the insets it asked for, once, to everything",
                    src.contains("padding(inner)"),
                )
            }
    }

    /** The wrapper handles the IME too, which is what keeps the keyboard off the passphrase field. */
    @Test
    fun theWrapperUsesSafeDrawing() {
        val src = kotlinSources().single { it.name == "VaultScaffold.kt" }.readSource()
        assertTrue(
            "VaultScaffold must use WindowInsets.safeDrawing — systemBars alone leaves the " +
                "keyboard covering the passphrase field",
            code(src).contains("WindowInsets.safeDrawing"),
        )
    }

    /** No screen declares its own bar; that is how thirteen of them disagreed before. */
    @Test
    fun onlyTheWrapperDeclaresATopAppBar() {
        // `vault-list-refresh`, D6. `LargeTopAppBar(` contains `TopAppBar(`, so it is caught
        // here too: the large title lives in the wrapper and is asked for, never declared.
        val offenders = kotlinSources()
            .filter { it.name != "VaultScaffold.kt" }
            .filter { code(it.readSource()).contains("TopAppBar(") }
            .map { it.name }
        assertEquals(
            "Screens take their bar from VaultScaffold; found one declared in: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * `enableEdgeToEdge` is declared once, at the activity, **and with explicit bar
     * styles**.
     *
     * *Amended by P2-23.* This used to require the bare `enableEdgeToEdge()`, which
     * was right while the app had one scheme. Called bare it derives the status- and
     * navigation-bar **icon** colours from the platform's night-mode flag — which is
     * not the app's setting the moment a user forces Light or Dark, and light icons
     * on a light bar are invisible controls. The requirement is now the stricter
     * one: exactly one call, carrying both styles.
     */
    /**
     * A lock destroys a recording in progress — `voice-memo`.
     *
     * A source scan because the claim is a property of the *shape* of a 4,000-line
     * activity: the lock path (`lockVault` and the `resetForLock` every lock runs) reaches
     * `discardRecording`, and nothing reaches `endRecording` or `commitRecording` — which
     * commit — from a lock. A device test of this needs a real
     * vault, a real unlock and a real microphone to assert one call ordering.
     *
     * What it is guarding: a partial memo the user never chose to keep becoming an
     * item, and a microphone left open over a locked vault.
     */
    @Test
    fun aLockDiscardsARecordingRatherThanCommittingIt() {
        val src = code(kotlinSources().single { it.name == "MainActivity.kt" }.readSource())
        val lockVault = src.substringAfter("private fun lockVault()", "")
            .substringBefore("private fun ")
        assertTrue("lockVault must exist", lockVault.isNotEmpty())
        // `screen-off-lock-all-screens` moved the screen state into one reset that every
        // lock runs — the button through `lockVault`, screen-off through `onStart`. The
        // claim is about that path, so both bodies are what is scanned.
        assertTrue("lockVault must run the shared reset", lockVault.contains("resetForLock()"))
        val lock = lockVault + src.substringAfter("private fun resetForLock()", "")
            .substringBefore("private fun ")

        assertTrue(
            "a lock must discard a recording in flight — a partial memo is not an item",
            lock.contains("discardRecording()"),
        )
        assertTrue(
            "a lock must not commit one: endRecording writes the item, and the user " +
                "never chose to keep this",
            !lock.contains("endRecording()"),
        )
        assertTrue(
            "nor commit a held one: a memo stopped and not saved is not an item, and " +
                "commitRecording is what makes it one — `voice-memo-layout`",
            !lock.contains("commitRecording("),
        )
        assertTrue(
            "and must drop the screen, so its own disposal releases the microphone",
            lock.contains("voiceMemoScreen = false"),
        )
    }

    /**
     * Back on the memo screen asks before it throws a recording away.
     *
     * Both backs, which is the half that is easy to lose: the bar's control and the
     * system gesture are different code paths, and a confirmation on only one of them
     * is a recording silently destroyed by the more common gesture.
     */
    @Test
    fun bothBacksAskBeforeDiscardingARecording() {
        val src = code(kotlinSources().single { it.name == "MainActivity.kt" }.readSource())
        assertTrue(
            "the gesture must route through the same question the bar's back asks",
            src.contains("voiceMemoScreen -> requestLeaveVoiceMemo()"),
        )
        assertTrue(
            "and the screen's own back control must too",
            src.contains("onBack = ::requestLeaveVoiceMemo"),
        )
        assertTrue(
            "the confirmation must sit above the screen in dismissTop, or back stacks " +
                "a second one behind the first",
            src.indexOf("confirmingMemoDiscard -> confirmingMemoDiscard = false") <
                src.indexOf("voiceMemoScreen -> requestLeaveVoiceMemo()"),
        )
    }

    @Test
    fun edgeToEdgeIsDeclaredAtTheActivityWithExplicitBarStyles() {
        val src = code(kotlinSources().single { it.name == "MainActivity.kt" }.readSource())
        assertEquals(
            "enableEdgeToEdge must be called exactly once, at the activity",
            1,
            Regex("""enableEdgeToEdge\(""").findAll(src).count(),
        )
        assertTrue(
            "MainActivity must not call enableEdgeToEdge() bare — the bars would follow the " +
                "system's night mode rather than the app's appearance setting",
            !src.contains("enableEdgeToEdge()"),
        )
        assertTrue(
            "enableEdgeToEdge must be given a statusBarStyle",
            src.contains("statusBarStyle ="),
        )
        assertTrue(
            "enableEdgeToEdge must be given a navigationBarStyle",
            src.contains("navigationBarStyle ="),
        )
    }

    // --- 6.2 content descriptions -------------------------------------------

    /**
     * Every `Icon` either describes itself or is explicitly decorative. `null` is
     * allowed and is the right answer beside a text label — it stops a screen
     * reader saying the row twice — but it has to be *written*, because the
     * failure being caught is the omission, not the choice.
     */
    @Test
    fun everyIconDeclaresItsContentDescription() {
        val iconCall = Regex("""(?<![A-Za-z])Icon\((?:[^()]|\([^()]*\))*\)""", RegexOption.DOT_MATCHES_ALL)
        val offenders = mutableListOf<String>()
        kotlinSources().forEach { file ->
            iconCall.findAll(code(file.readSource())).forEach { m ->
                if (!m.value.contains("contentDescription")) {
                    offenders += "${file.name}: ${m.value.take(60).replace('\n', ' ')}"
                }
            }
        }
        assertEquals(
            "Every Icon needs contentDescription (null is fine beside a label, but state it)",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The negative fixture the task asks for: the check above must actually fail
     * on an undescribed icon, not pass because the regex missed it.
     */
    @Test
    fun theContentDescriptionCheckFailsOnAnUndescribedIcon() {
        val iconCall = Regex("""(?<![A-Za-z])Icon\((?:[^()]|\([^()]*\))*\)""", RegexOption.DOT_MATCHES_ALL)
        val bad = """Icon(painter = painterResource(R.drawable.ic_lock), tint = Color.Red)"""
        val good = """Icon(painter = painterResource(R.drawable.ic_lock), contentDescription = null)"""
        assertTrue(
            "the regex must match a bare Icon call",
            iconCall.find(bad)?.value?.contains("contentDescription") == false,
        )
        assertTrue(
            "the regex must accept a described Icon call",
            iconCall.find(good)?.value?.contains("contentDescription") == true,
        )
    }

    /** The one icon-only control in the app carries a real description, not null. */
    @Test
    fun theBackAffordanceIsDescribed() {
        val src = kotlinSources().single { it.name == "VaultScaffold.kt" }.readSource()
        assertTrue(
            "the back affordance is the app's only icon-only control and must name itself",
            labels(src).contains("""contentDescription = "Back""""),
        )
    }

    // --- 6.3 touch targets ---------------------------------------------------

    /**
     * The grid's bar row still fits at the narrowest width a phone offers — P2-15,
     * and the measurement `settings-separation` spends.
     *
     * P2-7 put three controls in that row on the argument that five text labels do
     * not fit one row; P2-15 made it four, and `settings-separation` made it **five**
     * with the settings control, which opens the settings of the destination under it.
     * **320dp is the narrowest width this checks**, the smallest compact phone Android
     * ships.
     *
     * **The number, stated rather than implied: 80dp.** Five 48dp targets take 240dp
     * and leave 80dp for the title. A vault's name at `titleLarge` is roughly 11dp a
     * character; a title long enough not to fit is ellipsised by `VaultScaffold`, which
     * is what that overflow rule is there for.
     *
     * **No target shrank to buy the room, and none may.** The floor below is the
     * thing `settings-separation` said it would stop for; the title's slack is what
     * paid instead, and this asserts both halves.
     */
    @Test
    fun theGridBarRowFitsAtTheNarrowestWidth() {
        val narrowest = 320f
        // leading home + add + new folder + destination settings + drawer
        val controls = 5 * Spacing.touchTarget.value
        val forTitle = narrowest - controls
        assertEquals(
            "the five-control header leaves this much for the title at ${narrowest}dp",
            80f, forTitle, 0.001f,
        )
        assertTrue(
            "five 48dp controls leave $forTitle dp for the title at ${narrowest}dp, which is " +
                "not enough for a one-word title",
            forTitle >= 64f,
        )
        assertEquals(
            "the room was found in the title, not by shrinking a control below §7.1's floor",
            48f, Spacing.touchTarget.value, 0.001f,
        )
        // The selection bar is the denser case and has carried it since P2-11:
        // close + four actions + a count.
        val selectionControls = 5 * Spacing.touchTarget.value
        assertTrue(
            "the selection bar's five controls must still leave room for a count",
            narrowest - selectionControls >= 48f,
        )
    }

    /**
     * `DESIGN.md` §7.1: "The 48dp minimum is met by padding around the glyph,
     * never by inflating the glyph. A 24dp icon stays 24dp and gets 12dp of
     * padding." That is arithmetic, so it is checked as arithmetic.
     */
    @Test
    fun theGlyphPlusItsPaddingMeetsTheTouchTarget() {
        val glyph = 24f
        val padding = Spacing.md.value
        assertEquals(
            "a 24dp glyph with 12dp of padding on each side is the 48dp target",
            Spacing.touchTarget.value,
            glyph + padding * 2,
            0.001f,
        )
        assertTrue(
            "the touch target must not be reached by inflating the glyph past 24dp",
            glyph < Spacing.touchTarget.value,
        )

        // The arithmetic above passed while the real control measured 40dp: it
        // checks the tokens agree with each other, not that anything uses them.
        // material3 1.4.0's IconButton defaults to 40dp, under §7.1's floor, and
        // only the instrumented Verify could see that. This asserts the wrapper
        // actually sizes the control — the JVM half of a defect the device half
        // found.
        val src = code(kotlinSources().single { it.name == "VaultScaffold.kt" }.readSource())
        assertTrue(
            "VaultScaffold must size the back affordance to Spacing.touchTarget; the " +
                "IconButton default is below the floor",
            src.contains("Modifier.size(Spacing.touchTarget)"),
        )
    }

    /** The bar height is a recorded token, not a literal at the call site. */
    @Test
    fun theBarHeightIsATokenAndOnTheScale() {
        assertEquals(64f, Spacing.barHeight.value, 0.001f)
        assertEquals(
            "every spacing value is a multiple of the 4dp base unit",
            0f,
            Spacing.barHeight.value % 4f,
            0.001f,
        )
        val src = kotlinSources().single { it.name == "VaultScaffold.kt" }.readSource()
        assertTrue(
            "VaultScaffold must read the bar height from Spacing, not write 64.dp",
            code(src).contains("Spacing.barHeight") && !code(src).contains("64.dp"),
        )
    }

    // --- §6.2, as rewritten by P2-7 ------------------------------------------

    /**
     * Export is at the drawer's top level and nowhere else.
     *
     * **This assertion has said three different things**, and the previous two are
     * worth keeping in view. It first required "Unvault Everything" to be rendered in
     * `VaultGrid`, because §6.2 then read "not behind overflow — a claim in an
     * overflow menu is not a claim". P2-7 rewrote that rule rather than ignoring it:
     * the old wording was about *credibility*, not tap safety, and a permanently
     * visible export invites a tap nobody meant on the one control that writes
     * plaintext out of a vault. `settings-screen` then put it on a screen.
     *
     * `settings-separation` deletes that screen, so it is in the drawer, and
     * `vault-group-in-drawer` puts it in the open vault's group, whose it is — at the top
     * level rather than inside the row helper. The credibility half is unchanged and is what this still enforces: one
     * tap from the grid, never nested deeper, always a text label, never styled as
     * destructive.
     */
    @Test
    fun exportIsInTheDrawerAndNowhereElse() {
        val grid = labels(kotlinSources().single { it.name == "VaultGrid.kt" }.readSource())
        val drawer = labels(kotlinSources().single { it.name == "VaultDrawer.kt" }.readSource())

        assertTrue(
            "export must not be rendered in the grid's header — a permanent export is the " +
                "accidental tap P2-7 removed and no change since has put back",
            !grid.contains("\"Unvault Everything\""),
        )
        assertTrue(
            "\"Unvault Everything\" must be rendered in the drawer, as a text label",
            drawer.contains("\"Unvault Everything\""),
        )
        assertTrue(
            "export must sit at the drawer's top level, not inside a nested row helper",
            drawer.substringBefore("private fun DrawerRow").contains("\"Unvault Everything\""),
        )
    }

    /**
     * The drawer's one primary-coloured action is export, and the purchase is not it.
     *
     * This is **I4 expressed as a test**. The entitlement never gates reads or
     * exports; a surface that emphasised *Buy* above a muted *Unvault
     * Everything* would tell the user they buy to get their files out. §6.1 allows one
     * primary per surface, and since `settings-separation` the two are not even on the
     * same surface. Since `vault-group-in-drawer` the export is a row in the open vault's
     * group rather than a filled button, and it keeps the primary colour on that row — so
     * the rule is now that the drawer's one primary-coloured element is the export, and
     * no surface carries a filled button in it. Since `vault-drawer-trial-and-destroy-pinned`
     * the Purchase row heads the vault's drawer too — a row, never a button, so it outranks
     * nothing — and the drawer holds exactly one filled button: Destroy Vault, pinned below
     * the scroll in `Danger`, which is not the primary colour and is not an action anyone is
     * steered toward.
     */
    @Test
    fun theDrawersPrimaryActionIsExportAndNotThePurchase() {
        val drawer = labels(kotlinSources().single { it.name == "VaultDrawer.kt" }.readSource())
        val account =
            labels(kotlinSources().single { it.name == "PurchaseScreen.kt" }.readSource())

        val beforeBuy = account.substringBefore("onClick = onBuy", "").takeLast(80)

        assertTrue(
            "export must be a DrawerRow in the primary colour — it holds the drawer's one " +
                "primary colour, on a row and not a filled button",
            Regex(
                """DrawerRow\(\s*label = "Unvault Everything",\s*icon = R\.drawable\.ic_output,""" +
                    """\s*onClick = onExport,\s*primary = true,""",
            ).containsMatchIn(drawer),
        )
        // The vault's drawer is the scroll's shared head — the Purchase row and its rule, drawn
        // on both branches — and then the vault's own branch.
        val scroll = drawer.substringAfter("verticalScroll(")
        val vaultBody = scroll.substringBefore("if (vaultName != null) {") +
            scroll.substringAfter("if (vaultName != null) {").substringBefore("} else {")
        assertTrue(
            "the vault's drawer holds the export and the Purchase row",
            vaultBody.contains("\"Unvault Everything\"") && vaultBody.contains("\"Purchase"),
        )
        assertEquals(
            "the export is the only row in the primary colour",
            1,
            Regex("primary = true").findAll(drawer).count(),
        )
        assertTrue(
            "the purchase must be an OutlinedButton; I4 means export is never subordinate " +
                "to a purchase",
            beforeBuy.contains("OutlinedButton("),
        )
        // The lookbehind already excludes OutlinedButton, so these count filled
        // buttons only.
        assertEquals(
            "the drawer carries one filled button, Destroy Vault — its primary colour is on the " +
                "export's row",
            1,
            Regex("""(?<![A-Za-z])Button\(""").findAll(drawer).count(),
        )
        val pill = drawer.substring(Regex("""(?<![A-Za-z])Button\(""").find(drawer)!!.range.last)
            .substringBefore("\"Destroy Vault\"")
        assertTrue(
            "the drawer's one filled button is the Destroy Vault pill in VaultColors.Danger",
            pill.contains("onClick = onDestroyVault") &&
                pill.contains("containerColor = VaultColors.Danger") &&
                pill.contains("contentColor = VaultColors.OnDanger"),
        )
        assertEquals(
            "the Purchase screen carries none — I4 again, one surface along",
            0,
            Regex("""(?<![A-Za-z])Button\(""").findAll(account).count(),
        )
    }

    // --- P2-7's header and drawer --------------------------------------------

    /**
     * No overflow menu survives anywhere. That was the whole point of P2-7.
     *
     * **What P2-7 actually decided was about the grid's header**, where lock, add,
     * search and settings were hidden behind a glyph naming none of them. It was
     * written down as an app-wide prohibition and enforced here over every file, and
     * that is the part `viewer-chrome-and-editor` narrows — not the decision.
     *
     * ***The vault list is the third exemption and is bought the same way.*** Its rows
     * carried a rename glyph and a remove glyph; they are one three-dot trigger now. The
     * surface exists to **open** a vault, and opening stays on the face of the row where a
     * tap already does it — so what sits behind the glyph is the requirement's "occasional
     * acts on the object", not the act the surface exists to perform. The assertion below
     * is what keeps that true: the row must stay clickable to open, and the trigger must
     * keep naming the vault it acts on.
     *
     * **Three exemptions, and each is guarded by what it must keep doing.** The grid's
     * sort menu is one value in six *states*, and its trigger has to keep printing
     * `sort.label`, so it cannot quietly become a bare glyph. The viewer's menu holds
     * the two *occasional* acts on an item while back, rotate, rename, delete and the
     * editor stay on the face of its rows — so the viewer has to keep naming those
     * five.
     *
     * The grid's header is exempted from nothing, which is the whole of P2-7 and is
     * asserted below.
     */
    @Test
    fun noOverflowMenuAnywhere() {
        val permitted = setOf("VaultGrid.kt", "ViewerMenu.kt", "VaultListScreen.kt")
        val offenders = kotlinSources()
            .filter { code(it.readSource()).contains("DropdownMenu") }
            .map { it.name }
            .filter { it !in permitted }
        assertEquals(
            "P2-7 deleted the overflow; actions are named and visible. Found in: $offenders",
            emptyList<String>(),
            offenders,
        )

        val grid = code(kotlinSources().single { it.name == "VaultGrid.kt" }.readSource())
        assertTrue(
            "the grid's exempt menu is the sort control, and its trigger must name the " +
                "ordering in effect rather than hide behind a glyph",
            grid.contains("text = stringResource(sort.label)"),
        )

        // The viewer's exemption is bought by what stays outside the menu. If one of
        // these five moves into it, the menu has become the overflow P2-7 deleted.
        // `labels`, not `code`: these are the rendered content descriptions, and the
        // scanner that hides string literals is the wrong one for reading them.
        val viewer = labels(kotlinSources().single { it.name == "VaultViewer.kt" }.readSource())
        listOf("\"Back\"", "\"Rotate\"", "\"Rename\"", "\"Delete\"", "\"Edit image\"").forEach { named ->
            assertTrue(
                "the viewer's menu is exempt only while the acts a user reaches for stay on " +
                    "the face of its rows; $named is no longer one of them",
                viewer.contains(named),
            )
        }

        // The vault list's exemption is bought by the act the surface exists to perform
        // staying outside the menu. A row whose tap no longer opens the vault, or a
        // trigger that stops naming which vault it is about, has turned this into the
        // overflow P2-7 deleted.
        val list = kotlinSources().single { it.name == "VaultListScreen.kt" }
        assertTrue(
            "the vault list's menu is exempt only while opening a vault stays on the face " +
                "of the row; the row must remain clickable to open",
            code(list.readSource()).contains("clickable { onOpen(entry) }"),
        )
        assertTrue(
            "the vault list's menu trigger must name the vault it acts on; a bare " +
                "\"Options\" reads the same on every row",
            labels(list.readSource()).contains("contentDescription = stringResource(\"Options for %1\$s\", entry.name)"),
        )

        val menu = labels(kotlinSources().single { it.name == "ViewerMenu.kt" }.readSource())
        assertTrue(
            "the viewer's menu holds the occasional acts and only those",
            menu.contains("Set as folder cover") && menu.contains("Move to folder"),
        )
    }

    /**
     * The retired glyph does not return to the grid's **header**.
     *
     * ***This assertion has been narrowed twice, and both narrowings are here on
     * purpose.*** P2-7 removed the glyph along with the overflow it opened, and the
     * file going with it was recorded as the rule. `viewer-chrome-and-editor` brought
     * the glyph back for the viewer's menu — which is not an overflow of reached
     * actions — so what was asserted then was the thing P2-7 decided: **the grid's
     * header names its five actions and hides none of them behind a mark.**
     *
     * `note-options-sheet` is the second narrowing. A note's tile carries the mark
     * now, and it opens that note's own sheet: pin, move, colour, delete. That is the
     * viewer's exemption one surface over — N acts on **one object**, with nothing of
     * the screen's own in it — and it is bought the same way, by what has to stay
     * outside it. The mark is drawn rather than gestural because a long-press on an
     * item begins a selection and has since P2-11, so a note cannot borrow the
     * folder tile's long-press without answering one gesture two ways.
     *
     * **What is guarded is that the glyph stays on the tile.** One occurrence in the
     * file, inside `MosaicTile`. A second one is how this comes back as a header
     * overflow, which is the thing P2-7 deleted and the thing still deleted.
     *
     * Anyone reading this as a regression to fix should read `noOverflowMenuAnywhere`
     * above, where the exemptions and what buys them are written out.
     */
    @Test
    fun theRetiredOverflowGlyphDoesNotReturnToTheGrid() {
        val grid = code(kotlinSources().single { it.name == "VaultGrid.kt" }.readSource())

        assertEquals(
            "the three-dot mark is the note tile's and there is one of it; a second in " +
                "this file is the header overflow P2-7 deleted",
            1,
            Regex("ic_more_vert").findAll(grid).count(),
        )

        val tile = grid
            .substringAfter("private fun MosaicTile(")
            .substringBefore("private fun EmptyDestination")
        assertTrue(
            "the grid's header names its five actions; the one three-dot mark in this " +
                "file belongs to the note tile and must be inside MosaicTile",
            tile.contains("ic_more_vert"),
        )

        // `vault-destinations`. **The note's mark has a second home and only one
        // meaning.** Notes are a destination now, and a note there is a card rather than
        // a tile — but the surface the mark opens is the same one, reached the same way,
        // for the same reason: a long-press has begun a selection since P2-11, so a
        // note's own sheet needs a target of its own. The mosaic still draws note tiles
        // in the trash, which is why both exist.
        val rows = code(kotlinSources().single { it.name == "DestinationRows.kt" }.readSource())
        assertEquals(
            "the three-dot mark on a row is the note card's and there is one of it",
            1,
            Regex("ic_more_vert").findAll(rows).count(),
        )
        assertTrue(
            "the row's mark names the item it is about, not just itself",
            labels(kotlinSources().single { it.name == "DestinationRows.kt" }.readSource())
                .contains("Options for "),
        )

        // `labels`, not `code`: this is the rendered content description, and the
        // scanner that hides string literals is the wrong one for reading it. A mark
        // that says only "Options" reads identically on every note on the screen.
        val labelled = labels(kotlinSources().single { it.name == "VaultGrid.kt" }.readSource())
        assertTrue(
            "the note tile's mark names the note it is about, not just itself",
            labelled.contains("Options for "),
        )

        val permitted = setOf(
            "ViewerMenu.kt", "VaultGrid.kt", "DestinationRows.kt", "VaultListScreen.kt",
        )
        val elsewhere = kotlinSources()
            .filter { code(it.readSource()).contains("ic_more_vert") }
            .map { it.name }
            .filter { it !in permitted }
        assertEquals(
            "the three-dot glyph belongs to the viewer's menu, the note tile, the note " +
                "card and the vault row, and nowhere else",
            emptyList<String>(),
            elsewhere,
        )
    }

    /**
     * The editor names no file API at all — `viewer-chrome-and-editor`, I3.
     *
     * `EditorTest` drives the pipeline on a device and walks private storage after
     * every step, which proves no artifact appeared *while it ran*. This is the other
     * half: a file API the editor never calls cannot write a draft on a path that
     * test did not walk — a cancel, a lock, a low-memory trim, a configuration change.
     *
     * The editor is handed a decoded bitmap and hands back a stack of values; the
     * bytes belong to `MainActivity`, where the key is. If this fails, someone gave
     * the editor a path.
     */
    @Test
    fun theEditorTouchesNoFileApi() {
        val forbidden = listOf(
            "java.io.File", "FileOutputStream", "FileInputStream", "cacheDir",
            "filesDir", "externalCacheDir", "createTempFile", "openFileOutput",
            "MediaStore", "FileProvider",
        )
        listOf("EditorScreen.kt", "ImageEdits.kt").forEach { name ->
            val src = code(kotlinSources().single { it.name == name }.readSource())
            forbidden.forEach { api ->
                assertTrue(
                    "$name names $api; the editor is handed pixels and returns values, and " +
                        "a file API here is how a plaintext draft reaches disk (I3)",
                    !src.contains(api),
                )
            }
        }
    }

    /**
     * There is no settings screen, and every row is where the rule put it.
     *
     * **This assertion has now been inverted three times, and that is the point of
     * keeping its history in view.** P2-7 deleted `SettingsScreen` and asserted it stayed
     * deleted; `settings-screen` brought it back and asserted its five groups were in
     * order; `settings-separation` deleted it again and split what was left between the
     * drawer and a vault settings sheet; and that sheet went too, its trash, backup and
     * Destroy Vault moving into the drawer under the vault's name. What each of them was
     * reaching for is the same property, and it is the one asserted here: **every
     * setting is somewhere a rule put it.**
     *
     * The rule is in the spec: a setting belongs to the open vault if changing it changes
     * only that vault, and to the install otherwise. Since `vault-only-drawer` each kind has
     * its own drawer: inside a vault the sheet draws that vault's rows under its name and
     * nothing else, and on the home screen it draws the install's. A vault row among the
     * install's is how a user with three vaults read one vault's trash as the app's.
     */
    @Test
    fun thereIsNoSettingsScreenAndEveryRowIsWhereTheRulePutIt() {
        listOf("SettingsScreen.kt", "VaultSettingsSheet.kt").forEach { gone ->
            assertEquals(
                "$gone must not exist: the drawer holds the install's settings and, under " +
                    "the vault's name, the open vault's",
                emptyList<String>(),
                kotlinSources().map { it.name }.filter { it == gone },
            )
        }

        val drawer = kotlinSources().single { it.name == "VaultDrawer.kt" }.readSource()
        // The sheet draws one of two bodies: the vault's, which its name gates, and the
        // home screen's in the `else`. The home body runs to the chooser's own gate.
        val vaultGroup = labels(drawer)
            .substringAfter("if (vaultName != null) {")
            .substringBefore("} else {")
        val homeBody = labels(drawer)
            .substringAfter(vaultGroup)
            .substringBefore("if (vaultName == null) {")
        // `vault-drawer-trial-and-destroy-pinned`. The scroll's head, above the branch, is
        // drawn in both drawers; the pinned slot below the scroll holds Destroy Vault in the
        // vault's drawer only, in the `else` of the chooser's gate.
        val sharedHead = labels(drawer)
            .substringAfter("verticalScroll(")
            .substringBefore("if (vaultName != null) {")
        val scroll = labels(drawer)
            .substringAfter("verticalScroll(")
            .substringBefore("if (vaultName == null) {")
        val vaultPinned = labels(drawer)
            .substringAfter("if (vaultName == null) {")
            .substringAfter("} else {")
            .substringBefore("Spacing.section")
        assertTrue(
            "the open vault's drawer must be headed by the vault's name",
            vaultGroup.contains("GroupHeading(vaultName)"),
        )
        // `vault-only-drawer`. Every row for the open vault, in this order. Destroy Vault is
        // not one of them: it is pinned below the scroll. `per-vault-intruder-capture`: a vault
        // with its own passphrase holds Intruder capture between Backup and Open a share….
        val protectedRows = listOf(
            "Trash", "Backup", "Intruder capture", "Open a share…", "Unvault Everything",
        )
        assertEquals(
            "a protected vault's rows read Trash, Backup, Intruder capture, Open a share…, Unvault Everything",
            protectedRows,
            protectedRows.sortedBy { vaultGroup.indexOf("\"$it\"") },
        )
        protectedRows.forEach { assertTrue("\"$it\" is in the vault's group", vaultGroup.contains("\"$it\"")) }
        // Omitted otherwise: drawn only inside the gate its handler sets, which is null for a
        // vault without its own passphrase.
        assertTrue(
            "the vault's Intruder capture row is drawn only when the open vault has its own passphrase",
            vaultGroup.substringAfter("onVaultIntruderCapture?.let {").substringBefore("}").contains("\"Intruder capture\""),
        )
        val vaultRows = protectedRows - "Intruder capture"
        vaultRows.forEach { row ->
            assertTrue(
                "\"$row\" belongs to the open vault, so it must sit in the group its name heads",
                vaultGroup.contains("\"$row\""),
            )
            assertEquals(
                "\"$row\" must be drawn once, in the vault's group and nowhere else",
                1,
                Regex(Regex.escape("\"$row\"")).findAll(labels(drawer)).count(),
            )
        }
        assertEquals(
            "the vault's rows read Trash, Backup, Open a share…, Unvault Everything",
            vaultRows,
            vaultRows.sortedBy { vaultGroup.indexOf("\"$it\"") },
        )
        assertEquals(
            "\"Destroy Vault\" must be drawn once",
            1,
            Regex(Regex.escape("\"Destroy Vault\"")).findAll(labels(drawer)).count(),
        )
        assertTrue(
            "Destroy Vault is pinned below the scroll, in the vault's drawer only, and not a row",
            vaultPinned.contains("\"Destroy Vault\"") && !scroll.contains("\"Destroy Vault\""),
        )

        // `biometric-unlock-screen` D6. The biometric row's label is the app's own and fixed,
        // held in `BiometricWrap` with every other biometric word, so it is pinned there and
        // found here by its reference, its glyph and its place in the group.
        assertEquals("Biometric unlock", TestWords.text(BiometricWrap.NAME))
        val biometricRow = homeBody.indexOf("label = stringResource(BiometricWrap.NAME)")
        assertTrue(
            "the home drawer must hold the Biometric unlock row, labelled by BiometricWrap.NAME",
            biometricRow >= 0,
        )
        assertTrue(
            "the biometric row opens its screen and carries no switch",
            homeBody.substring(biometricRow).substringBefore("DrawerRow(").let {
                it.contains("onClick = onBiometric") && !it.contains("checked =")
            },
        )
        assertEquals(
            "`ic_fingerprint` is the biometric row's glyph and no other row's, so it " +
                "identifies that row on its own",
            1,
            Regex(Regex.escape("R.drawable.ic_fingerprint")).findAll(labels(drawer)).count(),
        )
        assertTrue(
            "the biometric row sits in The install between App identity and Face-down lock",
            homeBody.indexOf("\"App identity") in 0 until biometricRow &&
                biometricRow < homeBody.indexOf("\"Face-down lock\""),
        )

        // The Purchase row heads both drawers: once, in the scroll's shared head.
        assertTrue(
            "the Purchase row is drawn in both drawers, above the branch",
            sharedHead.contains("\"Purchase") && !vaultGroup.contains("\"Purchase") &&
                !homeBody.contains("\"Purchase"),
        )

        // The ones that change the install, and recovery — the home drawer's, and absent
        // from the vault's.
        listOf(
            "App identity", "Face-down lock",
            "Set up a decoy", "Panic credential", "Photo details", "Contacts",
            "Recover a vault with its kit", "Open-source licences",
        ).forEach { row ->
            assertTrue(
                "the home drawer must hold \"$row\" — it is the app's",
                homeBody.contains("\"$row"),
            )
            assertTrue(
                "\"$row\" is the app's, so it must not be in the drawer opened inside a vault",
                !vaultGroup.contains("\"$row"),
            )
        }
        // `intruder-captures-at-home`. An app setting, directly after the panic credential.
        assertTrue(
            "Intruder capture sits in The install directly after Panic credential, before Photo details",
            homeBody.indexOf("\"Panic credential\"") in 0 until homeBody.indexOf("\"Intruder capture\"") &&
                homeBody.indexOf("\"Intruder capture\"") < homeBody.indexOf("\"Photo details"),
        )
        assertTrue(
            "the vault's drawer carries no rule and no colour scheme chooser",
            !vaultGroup.contains("Rule()") && !vaultGroup.contains("AppearanceControl("),
        )
    }

    /**
     * The player's controls own the bottom edge — `player-controls-and-menu`.
     *
     * Two rows were aligned to that edge and the action row was composed after the
     * pager, so it sat on top of the transport: play/pause took no touches because
     * rename was drawn over it. `ViewerChromeDeviceTest` holds that the action row is
     * gone on a player. This holds the other half, which is not visible to a semantics
     * query — that the transport carries its own safe-area padding. Without it the
     * lower part of the row lies under the system's gesture area, which takes those
     * touches whatever this app draws, and the control feels dead for a second reason
     * that removing the row above it would not fix.
     */
    @Test
    fun thePlayersControlsCarryTheirOwnSafeAreaPadding() {
        val viewer = kotlinSources().single { it.name == "VaultViewer.kt" }.readSource()
        val controls = viewer.substringAfter("private fun PlayerControls(")
            .substringBefore("\n/**")

        assertTrue(
            "PlayerControls must carry safeDrawingPadding() — without it its lower " +
                "part sits under the gesture bar and the system takes those touches",
            controls.contains("safeDrawingPadding()"),
        )
    }

    /**
     * The bottom action row is withheld over an item that plays.
     *
     * Asserted on the source as well as on the screen because the device test can only
     * see the arrangement for the one kind it opened; this sees the condition itself,
     * so a later edit that draws the row unconditionally fails here even if nobody
     * writes a device test for audio.
     */
    @Test
    fun theViewersActionRowIsWithheldOverAPlayer() {
        val viewer = code(kotlinSources().single { it.name == "VaultViewer.kt" }.readSource())

        assertTrue(
            "the viewer must decide the layout from whether the settled item plays",
            viewer.contains("kind.isPlayable"),
        )
        assertTrue(
            "the bottom action row must be guarded on the item not playing — two rows " +
                "on one edge is the defect this change exists to remove",
            Regex("""(if \(|val actions = )!playing &&""").containsMatchIn(viewer),
        )
    }

    /**
     * The drawer holds the entitlement, the open vault, the install, recovery and Open-source
     * licences, separated — the open vault alone inside a vault, the other four on the home screen.
     *
     * The failure this guards against is the one that actually happened: P2-7 built a
     * six-row sheet and nine later tasks put a setting in it because it was the only
     * surface with room. Nothing failed when they did.
     *
     * **What fails now is different, because settings are meant to be here.** A
     * vault's row outside the group its name heads is the defect — that is
     * [thereIsNoSettingsScreenAndEveryRowIsWhereTheRulePutIt] above. What this one holds is
     * the shape: no unbounded list, the three rules that separate the home drawer's four
     * groups, the scroll, and the scheme chooser pinned below it on the home screen only.
     *
     * **A row may carry a switch.** It could not until `conventional-image-editor`'s
     * test run caught the contradiction: the ban listed `Switch(` while the drawer drew
     * one for "Face-down lock" and argued for it in a comment. A boolean is a boolean —
     * what this test is defending is that a setting needing a *range* does not get its
     * mechanism inlined here.
     *
     * It does **not** hold the order — that is on screen, not in the file, and
     * [ScreenChromeDeviceTest.theDrawerReadsInItsOwnOrderOnEachScreen] measures it.
     */
    @Test
    fun theDrawerIsFourGroupsAndNotAList() {
        val drawer = kotlinSources().single { it.name == "VaultDrawer.kt" }.readSource()
        val src = code(drawer)

        // Controls that carry a *range* — a setting whose mechanism needs the surface
        // to itself. The scheme chooser is the exception and is pinned below the
        // scroll.
        //
        // **`Switch(` was on this list and should not have been.** A boolean shown as
        // a trailing switch on a list row is the Material list-item pattern, and it is
        // what a drawer row for one boolean is supposed to be; sending "Face-down
        // lock" through a screen of its own to hold a single toggle is worse on every
        // count, and `VaultDrawer` says so where it draws that row. The failure this
        // test exists for — vault settings accumulating in a shared sheet — is held by
        // [thereIsNoSettingsScreenAndEveryRowIsWhereTheRulePutIt], which checks *which*
        // settings live here rather than which widget draws them. Banning the switch
        // reached past that and failed on a row the design asked for.
        listOf("Slider(", "Checkbox(")
            .forEach { control ->
                assertTrue(
                    "$control puts a ranged setting's mechanism in the drawer; a row here " +
                        "names its state in words and opens the thing that changes it, or " +
                        "carries a switch when the state is one boolean",
                    !src.contains(control),
                )
            }
        assertTrue(
            "the drawer must scroll — it is seven rows on a device that may show five",
            src.contains("verticalScroll("),
        )
        val sheet = src.substringBefore("private fun Rule")
        val vaultBody = sheet.substringAfter("if (vaultName != null) {").substringBefore("} else {")
        // `debug-crash-log-screen` put the debug build's crash log row last, under its
        // own rule. That rule is the one allowed outside the four groups, and only
        // directly above that row.
        val crashLogRule = Regex("""if \(onCrashLog != null\) \{\s*Rule\(\)""")
        assertTrue(
            "the debug crash log row sits under its own rule",
            crashLogRule.containsMatchIn(sheet),
        )
        assertEquals(
            "three rules for the home drawer's four groups: under the entitlement, above " +
                "recovery and above Open-source licences. A fourth call site is a group nobody " +
                "decided on",
            3,
            Regex("""\bRule\(\)""").findAll(crashLogRule.replace(sheet, "")).count(),
        )
        assertTrue(
            "the vault's drawer is one group and draws no rule",
            !vaultBody.contains("Rule()"),
        )
        assertTrue(
            "the scheme chooser stays pinned below the scroll, where a tap re-themes the " +
                "screen behind the open sheet, and only when no vault is open",
            Regex("""if \(vaultName == null\) \{\s*AppearanceControl\(""")
                .containsMatchIn(sheet.substringAfter("verticalScroll(")),
        )
        // `folder-system`. Folders are on the home screen; a list of them here is
        // the same navigation twice, and it is how this sheet filled up before.
        assertTrue(
            "the drawer must not list the vault's folders",
            !src.contains("onOpenFolder") && !src.contains("folders:"),
        )
    }

    /**
     * The floating action button holds exactly the four actions it was given.
     *
     * A fifth here is the drawer's failure starting again one surface along, which
     * is the whole reason this change exists. Trash left the set deliberately: it is
     * a destination, and destinations are places, not actions. **New folder** joined
     * it for the mirror-image reason — making one is an act, and the header's `+` is
     * import.
     *
     * **New vault left the set afterwards.** Every action here puts something *in*
     * the vault on screen; creating a vault does not, and grouping it with three
     * that do made it read as making a vault inside a vault. It lives on the home
     * screen's bar now — `home-screen-chrome` — and
     * [creatingAVaultIsNotAnActionInsideAVault] keeps it off this screen entirely.
     *
     * **`VaultFab` stopped being a `BoxScope` extension** in `search-at-the-bottom`:
     * it sits inside the bottom block above the destination bar now, and its dismiss
     * scrim moved to the caller, which is the only thing that still fills the window.
     */
    @Test
    fun theFabHoldsExactlyThreeContentActions() {
        val grid = labels(kotlinSources().single { it.name == "VaultGrid.kt" }.readSource())
        val body = grid.substringAfter("private fun VaultFab(", "")
            .substringBefore("private fun FabAction(")
        assertTrue("VaultFab must exist in VaultGrid.kt", body.isNotEmpty())

        // `new-folder-in-the-header`. It left the set for the header, where it is one tap
        // from the grid rather than two. Asserted rather than left to review, because the
        // set is where things land when nobody decides where they belong.
        assertFalse(
            "creating a folder must not be in the floating set — it is a header control " +
                "now, and one act with two homes is one too many",
            body.contains("FabAction(\"New folder\""),
        )

        listOf("Camera", "Voice memo", "New note").forEach { label ->
            assertTrue(
                "the floating action set must offer \"$label\"",
                body.contains("FabAction(\"$label\""),
            )
        }
        // `voice-memo` reopened this deliberately and moved it from two to three. The
        // ceiling is not gone — a fourth is still a defect, and this still fails the
        // build for one.
        assertEquals(
            "the floating action set is closed at three; a fourth is a defect",
            3,
            Regex("""FabAction\(""").findAll(body).count(),
        )
        assertTrue(
            "Trash is a destination and belongs in the drawer, not on the button",
            !body.contains("FabAction(\"Trash\""),
        )
        assertTrue(
            "New vault makes a vault beside this one rather than anything in it. On the " +
                "button it read as a vault inside a vault, and it belongs on the home " +
                "screen's bar — see creatingAVaultIsNotAnActionInsideAVault.",
            !body.contains("FabAction(\"New vault\""),
        )
    }

    /**
     * `unavailable-capability-states`. **The guard against "present and inert", narrowed to
     * its target.**
     *
     * `grid-fab` forbids a control that is present, says nothing and does nothing. The
     * Camera pill on a device with no camera is present and does nothing — and says why,
     * in its label, which is the opposite of inert. So what is asserted is the shape that
     * keeps it the opposite: a pill is disabled **only** when it carries a reason, the
     * reason is in its label, and every pill keeps its label and the 48dp floor. A pill
     * disabled any other way, or with no words, still fails.
     */
    @Test
    fun aDisabledPillSaysWhyAndIsNeverInert() {
        val grid = labels(kotlinSources().single { it.name == "VaultGrid.kt" }.readSource())
        val pill = grid.substringAfter("private fun FabAction(").substringBefore("private const val NO_CAMERA")
        assertTrue("FabAction must exist in VaultGrid.kt", pill.isNotEmpty())
        assertTrue(
            "a pill is disabled only when it carries a reason",
            pill.contains("enabled = reason == null"),
        )
        assertFalse("a pill must not be disabled any other way", pill.contains("enabled = false"))
        assertTrue(
            "a disabled pill's label must carry its reason, in the drawer's separator idiom",
            pill.contains("\"\$label  ·  \$it\""),
        )
        assertTrue(
            "every pill keeps the 48dp floor, and may grow to hold a wrapped reason",
            pill.contains("heightIn(min = Spacing.touchTarget)"),
        )
        assertTrue("every pill draws its label", pill.contains("Text("))

        val fab = grid.substringAfter("private fun VaultFab(").substringBefore("private fun FabAction(")
        assertTrue(
            "the Camera pill is disabled exactly where the device has no camera",
            fab.contains("reason = stringResource(NO_CAMERA).takeUnless { cameraAvailable }"),
        )
        assertTrue(
            "the reason the Camera pill gives is the one `grid-fab` names",
            grid.contains("private val NO_CAMERA = \"Not on this device\""),
        )
    }

    /**
     * Both halves of the end-edge flip are present.
     *
     * The host is mirrored to `Rtl` so Compose's start-side drawer lands on the
     * trailing edge; the sheet's content is mirrored back to `Ltr` so its rows are
     * not reversed. Applying only the first half puts the label on the right and
     * the glyph on the left in every row — and `FLAG_SECURE` means no screenshot
     * would ever show anyone that, which is exactly why it is asserted here.
     */
    @Test
    fun bothLayoutDirectionWrapsArePresent() {
        val grid = code(kotlinSources().single { it.name == "VaultGrid.kt" }.readSource())
        val drawer = code(kotlinSources().single { it.name == "VaultDrawer.kt" }.readSource())
        assertTrue(
            "VaultGrid must mirror the drawer host to Rtl to reach the trailing edge",
            grid.contains("LayoutDirection.Rtl"),
        )
        assertTrue(
            "VaultGrid must put its own content back to Ltr",
            grid.contains("LayoutDirection.Ltr"),
        )
        assertTrue(
            "VaultDrawer must put the sheet's content back to Ltr, or every row mirrors",
            drawer.contains("LayoutDirection.Ltr"),
        )
    }

    /**
     * Destroying a vault is a dialog, and it is the app's second and last one.
     *
     * §6.7 says an action that destroys user content asks once **in a modal dialog**. The
     * vault removal was a full screen — the deviation, not the dialog — and `delete-vault`
     * corrects it. What is asserted is that the screen went and did not come back.
     *
     * **Not a count of the app's dialogs.** §6.7 still calls the item delete "the only
     * one"; three other surfaces have quietly become dialogs since it was written, so a
     * count here would assert something already false. What is checked is this dialog.
     *
     * **The row says `Destroy Vault`, never `Delete`.** Delete is this app's word for an
     * item, which goes to the trash and returns for thirty days — and the trash is a row
     * in the same drawer. Two acts under one word, that close together, is a tap nobody
     * meant on the one that cannot be undone.
     */
    @Test
    fun destroyingAVaultIsADialogAndIsNotCalledDelete() {
        assertEquals(
            "VaultRemoveScreen.kt must not exist; the confirmation is a dialog now",
            emptyList<String>(),
            kotlinSources().map { it.name }.filter { it == "VaultRemoveScreen.kt" },
        )

        val dialog = kotlinSources().single { it.name == "VaultDestroyDialog.kt" }.readSource()
        assertTrue(
            "the confirmation must be an AlertDialog, not a screen",
            code(dialog).contains("AlertDialog("),
        )
        assertTrue(
            "a dialog is its own window and never inherits FLAG_SECURE — it must state it",
            code(dialog).contains("SecureFlagPolicy.SecureOn"),
        )
        assertTrue(
            "the confirmation must not push a screen: no scaffold, no title bar, no back",
            !code(dialog).contains("VaultScaffold("),
        )
        // `protected-vault-destroy-gate` D3. The gate and the choice are one modal that crossfades,
        // and its passphrase field offers nothing to the clipboard.
        assertTrue("the steps crossfade inside one dialog", code(dialog).contains("AnimatedContent("))
        assertTrue("the gate's field offers no clipboard action", code(dialog).contains("NoClipboard {"))
        assertEquals(
            "neither outcome may be primary-coloured — §6.1, and the app leans on neither",
            0,
            Regex("""(?<![A-Za-z])Button\(""").findAll(code(dialog)).count(),
        )

        val drawer = labels(kotlinSources().single { it.name == "VaultDrawer.kt" }.readSource())
        assertTrue(
            "the drawer's row must read \"Destroy Vault\"",
            drawer.contains("\"Destroy Vault\""),
        )
        assertTrue(
            "\"Delete\" is the item's word and the trash is in this same drawer; the vault's " +
                "act must not share it",
            !drawer.contains("\"Delete\""),
        )
    }

    /**
     * The vault list's row carries **one** named glyph, and never a word.
     *
     * **This is a measured failure converted into a build error**, which is what this
     * file is for. As two `TextButton`s, Rename and Remove took 155dp of a 320dp row —
     * enough that the row's own centre fell inside "Rename", so a thumb aimed at the
     * middle of a row to *open* a vault renamed it. Nothing failed when it did: the
     * device suite runs at the emulator's width, where the centre clears the buttons,
     * and `FLAG_SECURE` means no screenshot would ever have shown anyone the narrow
     * case. It surfaced only when `settings-separation` measured at 320dp.
     *
     * Two 48dp glyphs took 96dp and moved the centre back onto the row body. **One
     * three-dot trigger takes 48dp**, which carries that argument further rather than
     * reversing it — and it is why the words are permitted *inside the menu* while still
     * being forbidden on the row. A menu overlays; its width cannot move a row's centre.
     *
     * So what this forbids is unchanged in substance and narrowed in letter: no text
     * control on the row, and the trigger names its subject. A bare "Options" read
     * identically on every row, which for a screen reader on a list of vaults is no name.
     */
    @Test
    fun theVaultListsRowControlIsOneNamedGlyph() {
        val list = kotlinSources().single { it.name == "VaultListScreen.kt" }.readSource()
        val labels = labels(list)

        listOf("Edit vault", "Remove").forEach { word ->
            assertTrue(
                "\"$word\" as a text button on the vault row takes ~78dp, and two of them put " +
                    "the row's centre inside the first one at 320dp — a tap meant to open a " +
                    "vault lands on it. Inside the menu it is fine; on the row it is not",
                !labels.contains("TextButton") || !labels.contains("Text(\"$word\""),
            )
        }

        assertTrue(
            "the row's trailing control must name the vault it acts on",
            labels.contains("contentDescription = stringResource(\"Options for %1\$s\""),
        )
        assertTrue(
            "the trailing control must carry the 48dp target explicitly — the row is the " +
                "narrowest place in the app and IconButton's own default is 40dp",
            code(list).contains("Modifier.size(Spacing.touchTarget)"),
        )

        // One trailing target on the **row**, not two. Two were the fix for three; one is
        // the fix for two, and a third glyph creeping back onto the row re-opens the 320dp
        // defect. Scoped to `trailingContent`, because this screen's header carries its own
        // 48dp controls and those are not on the row.
        val trailing = code(list)
            .substringAfter("trailingContent = {")
            .substringBefore("colors = ListItemDefaults.colors(")
        assertEquals(
            "the vault row carries exactly one trailing control; more re-creates the " +
                "measurement that put the row's centre inside a button",
            1,
            Regex("IconButton\\(").findAll(trailing).count(),
        )
    }

    /**
     * The header is icon-only, so every one of its controls must name itself.
     *
     * The list is `DESIGN.md` §9's enumeration, and it grows with it: P2-12 added
     * the trash's two, which are icon-only under the same bar-row measurement.
     * `settings-screen-and-fab` replaced "Settings" with "Albums and settings" — the
     * control now opens a drawer holding the vault, the albums and one Settings row,
     * and a name claiming it opens settings would be wrong about most of it — and
     * added the floating action button, §9's twelfth and last icon-only control.
     *
     * `settings-separation` added the thirteenth, the settings control, which opens the
     * settings of the destination under it and takes its name from that destination.
     */
    @Test
    fun everyHeaderControlIsNamed() {
        val grid = labels(kotlinSources().single { it.name == "VaultGrid.kt" }.readSource())
        listOf(
            // "Lock vault" left the header: locking is an act on the keyring and
            // belongs on the screen that lists vaults, which "All vaults" reaches.
            // "New vault" left this bar — `home-screen-chrome`. See
            // [creatingAVaultIsNotAnActionInsideAVault] below, which is what keeps it out.
            // `file-import`. "Add photos or videos" left this list because it left
            // this file: the `+`'s name now comes from `VaultDestination.addAction`,
            // and [everyAddActionIsNamedWhereItIsDeclared] below reads it there. The
            // control is still named — the name moved, and so did the assertion.
            "All vaults", "New folder",
            "Trash and settings",
            "Actions", "Close actions",
            "Restore selected", "Delete selected forever", "Back",
        ).forEach { name ->
            assertTrue(
                "the header control \"$name\" must declare a contentDescription; an icon-only " +
                    "header with an unnamed control is unreachable to a screen reader",
                describes(grid, name),
            )
        }
    }

    /**
     * The Media settings sheet — `media-settings`. A change applies as it is made, so there is
     * no confirm button; the sheet opens fully expanded and scrolls; its four booleans are the
     * shared one-node switch row; and each of its five glyphs names one row and nothing else in
     * the app, which is the ambiguity §6.4 exists to prevent.
     */
    @Test
    fun theMediaSettingsSheetAppliesAsItGoesAndItsGlyphsAreItsOwn() {
        val sheet = code(kotlinSources().single { it.name == "DestinationSettingsSheet.kt" }.readSource())
        assertTrue(
            "no confirm button — a change applies as it is made",
            !Regex("""(?<![A-Za-z])Button\(""").containsMatchIn(sheet) &&
                !sheet.contains("TextButton(") && !sheet.contains("OutlinedButton("),
        )
        assertTrue("the sheet opens fully expanded", sheet.contains("skipPartiallyExpanded = true"))
        assertTrue("the sheet scrolls rather than truncating", sheet.contains("verticalScroll("))
        // `destination-settings-and-slideshow`: Media six, Notes two, Memos three — the shared
        // one-node row — and five reveals, the shared reveal row.
        assertEquals("eleven switch rows, the shared one-node row", 11, Regex("""SwitchRow\(""").findAll(sheet).count())
        assertEquals("five reveal rows, the shared reveal row", 5, Regex("""RevealChoice\(""").findAll(sheet).count())

        val all = kotlinSources().joinToString("\n") { code(it.readSource()) }
        listOf(
            "ic_timer_off", "ic_brightness_high", "ic_fit_screen", "ic_high_quality", "ic_speed",
            "ic_resume", "ic_touch_double", "ic_screen_rotation",
            "ic_low_priority", "ic_keyboard_lock",
            "ic_high_res", "ic_surround_sound", "ic_headset_mic", "ic_avg_pace", "ic_graphic_eq",
            "ic_timer",
        ).forEach { glyph ->
            assertEquals(
                "R.drawable.$glyph must name one row and nothing else",
                1,
                Regex("""R\.drawable\.$glyph\b""").findAll(all).count(),
            )
        }
    }

    /**
     * The `+`'s name is declared on the destination, and every destination declares one —
     * `file-import`, `DESIGN.md` §7.2a.
     *
     * ***`everyHeaderControlIsNamed` cannot see this one and that is deliberate.*** The
     * `+` opens two different pickers, so its `contentDescription` is
     * `add.description` — an expression, which is exactly the case that scanner's short
     * window is written to allow and cannot check. The names moved to
     * `VaultDestination.AddAction`; the assertion moves with them, so the control is no
     * less asserted than when the literal sat in the header.
     *
     * **A `when` in the header would have been the wrong fix.** It would have kept the
     * literals where the scanner could see them and put a second copy of the mapping two
     * files from the first — and `VaultGrid.kt` already warns, on the settings control,
     * that a conditional inside a description is a name no scanner can read.
     */
    @Test
    fun everyAddActionIsNamedWhereItIsDeclared() {
        val destinations = labels(
            kotlinSources().single { it.name == "VaultDestination.kt" }.readSource()
        )
        listOf("Add photos or videos", "Add files").forEach { name ->
            assertTrue(
                "the header's + must be named for each picker it opens; \"$name\" is not " +
                    "declared in VaultDestination.kt, and an icon-only header with an " +
                    "unnamed control is unreachable to a screen reader",
                destinations.contains(""""$name""""),
            )
        }

        val grid = code(kotlinSources().single { it.name == "VaultGrid.kt" }.readSource())
        assertTrue(
            "the header's + must take its name from the destination rather than a literal " +
                "or a conditional in the header",
            grid.contains("contentDescription = stringResource(add.description)"),
        )
    }

    /**
     * The `+` is absent on Notes and Memos, and absence is not a disabled control —
     * `file-import`.
     *
     * **Read from the source, and from the destination's own declaration.** A rendered
     * check is impossible for the reason `ScreenChromeTest` exists at all — `FLAG_SECURE`
     * returns black to a screenshot — and the device test covers the four headers by
     * screenshot separately. What is asserted here is the fact the header derives its
     * behaviour from: two destinations carry a picker, two carry none, and the control is
     * gated on `null` rather than on an `enabled` flag.
     */
    @Test
    fun theHeadersAddControlIsWithheldRatherThanDisabled() {
        val grid = code(kotlinSources().single { it.name == "VaultGrid.kt" }.readSource())
        val actions = grid.substringAfter("private fun GridActions(", "")
            .substringBefore("private fun VaultFab(")
        assertTrue(
            "GridActions must gate the + on the destination's addAction",
            actions.contains("destination.addAction"),
        )
        assertTrue(
            "the + must be withheld by absence, not drawn and disabled",
            actions.contains("add != null"),
        )
        assertFalse(
            "no control in the vault's header is drawn disabled; absent is the rule",
            actions.contains("enabled = false"),
        )
        assertTrue(
            "new folder must stay on every destination — a folder holds any kind",
            actions.contains("R.drawable.ic_create_new_folder") && !actions.contains("captures"),
        )
    }

    /**
     * Creating a vault belongs to the screen that lists vaults — `home-screen-chrome`.
     *
     * **This control was in two bars at once.** The home screen's bar carries it, which is
     * where it belongs: making a vault is a thing you do to the *list*, not inside the one
     * you happen to be looking at. The grid's bar is for acting on this vault's contents,
     * and a control that appears in both says two different things about where you are.
     *
     * Asserted on the source rather than left to review, because it was put back once
     * already — `per-vault-passphrases` re-pointed it instead of removing it.
     */
    @Test
    fun creatingAVaultIsNotAnActionInsideAVault() {
        val grid = labels(kotlinSources().single { it.name == "VaultGrid.kt" }.readSource())
        assertFalse(
            "New vault must not be a control in the vault's own header — it belongs to " +
                "the screen that lists vaults, and one control in two bars means two " +
                "different things about where the user is",
            describes(grid, "New vault"),
        )

        val list = labels(kotlinSources().single { it.name == "VaultListScreen.kt" }.readSource())
        listOf("Lock", "New vault", "Menu").forEach { name ->
            assertTrue(
                "the home screen's header control \"$name\" must declare a " +
                    "contentDescription; an icon-only header with an unnamed control is " +
                    "unreachable to a screen reader",
                describes(list, name),
            )
        }
    }

    /**
     * Whether [name] is used as a `contentDescription` in [src].
     *
     * Not a plain `contains("contentDescription = \"$name\"")`, because a description
     * may legitimately be an expression rather than a literal: the floating action
     * button says "Actions" or "Close actions" depending on what the next tap does,
     * exactly as the camera's shutter changes with what it will do. Matching the
     * whole assignment would fail those and pass a description that reads worse.
     *
     * The window is deliberately short. It matches the name inside the *same*
     * assignment and not the next parameter along, so a bare string literal
     * elsewhere in the file cannot satisfy the check.
     */
    private fun describes(src: String, name: String): Boolean =
        Regex("""contentDescription = [^\n]{0,120}""")
            .findAll(src)
            .any { it.value.contains(""""$name"""") } ||
            // `app-languages`: a description read in composition and handed to a semantics
            // block through a val, because a resource cannot be read inside the block.
            Regex("""val (\w+) = [^\n]{0,160}""").findAll(src)
                .filter { it.value.contains(""""$name"""") }
                .any { src.contains("contentDescription = ${it.groupValues[1]}") }

    /**
     * The grid's one primary-coloured control is the floating action button.
     *
     * **This replaced `theGridHoldsNoPrimaryColouredAction`**, which asserted
     * `VaultGrid.kt` contained no `VaultColors.Primary` at all. That held while the
     * slot `vault-grid` reserves was empty. It is now claimed: a floating action
     * button that is not primary-coloured is not one, and tinting it to satisfy an
     * assertion would be the test writing the design.
     *
     * The guard is not weaker for being inverted — "empty" and "exactly one, and it
     * is this one" both fail the moment something reaches for emphasis. Import, the
     * drawer control, the lock and every selection action stay secondary — as does the
     * search field at the top of the content, which is a field and not a control.
     */
    @Test
    fun theGridsOnlyPrimaryControlIsTheFab() {
        val grid = code(kotlinSources().single { it.name == "VaultGrid.kt" }.readSource())

        assertEquals(
            "exactly one control on the grid may use VaultColors.Primary",
            1,
            Regex("""VaultColors\.Primary""").findAll(grid).count(),
        )
        val fab = grid.substringAfter("private fun VaultFab(", "")
            .substringBefore("private fun FabAction(")
        assertTrue(
            "the one primary-coloured control must be the floating action button",
            fab.contains("VaultColors.Primary"),
        )
    }

    /**
     * `search-at-the-top`. The search field is not drawn inside the bottom block.
     *
     * The block is the floating button above the destination bar, measured. A field back in
     * it makes it three deep again and charges the field's height to every session that never
     * searches, which is the clutter this change removed. The block is the `Column` that
     * carries `onSizeChanged { … bottomBlock = … }`, so that is the region scanned.
     *
     * The field is drawn once, and from the content column: the call must exist, and it must
     * follow the block — the content is declared after the `overlay` that holds the block.
     */
    @Test
    fun theSearchFieldIsNotDrawnInTheBottomBlock() {
        val grid = code(kotlinSources().single { it.name == "VaultGrid.kt" }.readSource())
        val block = grid.substringAfter("bottomBlock = size.height", "")
            .substringBefore("private fun ")

        val calls = Regex("""\bSearchField\(""")
        val blockEnd = block.indexOf("VaultDestinationBar(")
        assertTrue("could not find the bottom block's bar", blockEnd > 0)
        assertEquals(
            "the search field must not be drawn in the bottom block, between the button and the bar",
            0,
            calls.findAll(block.substring(0, blockEnd)).count(),
        )
        assertEquals(
            "the search field is drawn exactly once, from the content column",
            1,
            calls.findAll(grid.substringBefore("private fun SearchField(")).count(),
        )
    }

    /**
     * `search-at-the-top`. The grid's header carries no search field and no search control.
     *
     * The bar already holds a leading control, the vault's name and its own actions, and
     * `screen-chrome` requires those actions stay visible and named rather than behind an
     * overflow — which a further control would force. Search is reached from the field at the
     * top of the content, and from nowhere else.
     *
     * The header is the `VaultScaffold` call's `leadingAction` and `actions` slots plus the
     * bar's own composables, so those are what is scanned: none may mention the field, a
     * search glyph, or the word.
     */
    @Test
    fun theGridsHeaderCarriesNoSearchField() {
        val grid = kotlinSources().single { it.name == "VaultGrid.kt" }.readSource()
        val src = code(grid)
        val header = src.substringAfter("leadingAction = {", "")
            .substringBefore("overlay = {")
        assertTrue("could not locate the header slots", header.isNotBlank())
        assertFalse("the header must not draw the search field", header.contains("SearchField"))
        assertFalse("the header must not carry a search glyph", header.contains("ic_search"))

        val bar = src.substringAfter("private fun GridActions(", "")
            .substringBefore("private fun VaultFab(")
        assertFalse("GridActions must not draw the search field", bar.contains("SearchField"))
        assertFalse("GridActions must not carry a search glyph", bar.contains("ic_search"))

        val labelled = labels(grid)
        val actions = labelled.substringAfter("private fun GridActions(", "")
            .substringBefore("private fun VaultFab(")
        assertFalse(
            "no header action may be labelled as a search",
            Regex("""(?i)"[^"]*search[^"]*"""").containsMatchIn(actions),
        )
    }

    /**
     * The send flow keeps the promises `DESIGN.md` §7.19 makes for it — P4-2.
     *
     * Three of these are sentences on the screen and one is an absence. They are
     * asserted here rather than trusted because every one of them is a thing a
     * later edit would remove without noticing: copy reads as filler until you
     * know it is the security model, and a dialog reads as diligence until you
     * know §6.7 spends the app's only one on destruction.
     */
    @Test
    fun theSendFlowSaysWhatItMustAndAsksNothingItShouldNot() {
        val share = kotlinSources().single { it.name == "ShareScreen.kt" }
        val copy = labels(share.readSource())

        assertTrue(
            "the code and the file travelling separately is the whole security model, " +
                "and the screen must say so",
            copy.contains("Send the code a different way than the file."),
        )
        assertTrue(
            "the code is not recoverable, so the screen must say it is shown once",
            copy.contains("shown once and is not saved"),
        )
        assertTrue(
            "client-side policy must be described honestly rather than implied to be " +
                "revocable",
            copy.contains("cannot take it back"),
        )
        // The ledger that spends a single-view share is per-install, so a forwarded
        // code-mode container opens again on the next phone. A sender choosing
        // "Open once" will assume the opposite unless the screen says otherwise —
        // and since `remote-pairing` the code warning says it, once.
        assertTrue(
            "a code-mode limit restarts on every phone, and the sender must be told",
            copy.contains("“Open once” means once per phone. Passed on, it opens again.") &&
                !copy.contains("Pass the file and code on and it opens again on the next phone."),
        )
        assertTrue(
            "contact mode's binding is the reason to choose it, and must be stated where " +
                "the limit is chosen",
            copy.contains("phone can open the file"),
        )

        val code = code(share.readSource())
        assertTrue(
            "§6.7 spends the app's one dialog on destruction; sharing destroys nothing",
            !code.contains("AlertDialog") && !code.contains("DeleteConfirm"),
        )
        assertTrue(
            "the code renders in the monospace face — §5.1, and the recipient " +
                "transcribes it over a phone call",
            code.contains("VaultMono."),
        )

        // Absent rather than disabled, which is the viewer menu's rule and §7.19's.
        val grid = code(kotlinSources().single { it.name == "VaultGrid.kt" }.readSource())
        assertTrue(
            "the share control is withheld by absence, not by a disabled flag",
            grid.contains("if (onShare != null)"),
        )
    }

    /**
     * The receive screen keeps §7.20's promises, and the word entry it borrows did
     * not lose the recovery kit's — P4-3.
     *
     * The second half is the one worth having. `MnemonicWordEntry` gained a count
     * parameter so a four-word code could reuse it, and the failure that change
     * invites is silent: twenty-four fields quietly becoming four, on the screen
     * where a wrong field count costs somebody their vault.
     */
    @Test
    fun theReceiveScreenSaysWhatItMustAndTheKitStillAsksForTwentyFour() {
        val receive = kotlinSources().single { it.name == "ShareOpenScreen.kt" }
        val copy = labels(receive.readSource())

        assertTrue(
            "a policy refusal must say the code was right, or the user retypes a correct code",
            copy.contains("The code was correct."),
        )
        assertTrue(
            "client-side enforcement must be described on the receiving side too",
            copy.contains("kept by the app on this phone"),
        )
        assertTrue(
            "every cryptographic outcome is one message — FORMAT.md §12",
            copy.contains("This share could not be opened."),
        )

        val body = code(receive.readSource())
        assertTrue(
            "the code entry reuses the recovery kit's component rather than a second word field",
            body.contains("MnemonicWordEntry(") && body.contains("count = ShareCode.WORD_COUNT"),
        )

        // The recovery kit must still ask for all twenty-four. It passes no count,
        // so the default is what protects it — and a default is exactly the kind of
        // thing a later edit changes without noticing.
        val kit = code(kotlinSources().single { it.name == "RecoveryKit.kt" }.readSource())
        val entry = kit.substringAfter("MnemonicWordEntry(", "").substringBefore(")")
        assertTrue(
            "the recovery kit must not pass a word count; twenty-four is the default",
            !entry.contains("count ="),
        )
        val component = code(kotlinSources().single { it.name == "MnemonicEntry.kt" }.readSource())
        assertTrue(
            "the default word count must remain the mnemonic's own",
            component.contains("count: Int = Mnemonic.WORD_COUNT"),
        )
    }

    /**
     * The send flow's two shapes, and the receive flow's two doors — P4-5.
     *
     * The failure this exists to stop is silent in both directions: a code share
     * that lost the sentences carrying its security model, and a contact share
     * that kept them while having no code to protect.
     */
    @Test
    fun bothShareShapesSayOnlyWhatIsTrueOfThem() {
        val share = kotlinSources().single { it.name == "ShareScreen.kt" }
        val copy = labels(share.readSource())
        val body = code(share.readSource())

        assertTrue(
            "the recipient is chosen above the policy — §7.19",
            copy.contains("Who can open this") && copy.contains("Anyone with the code"),
        )
        assertTrue(
            "the block is drawn whether or not anybody is paired — `remote-pairing` D6",
            !body.contains("if (contacts.isNotEmpty())"),
        )
        assertTrue(
            "a contact share says who can open it",
            // The sentence wraps in source, so this takes the half carrying the claim.
            copy.contains("sealed to their phone"),
        )
        assertTrue(
            "and it is a second state rather than a nullable code",
            body.contains("ContactSendState("),
        )

        val open = kotlinSources().single { it.name == "ShareOpenScreen.kt" }
        val openBody = code(open.readSource())
        assertTrue(
            "the probe decides which question is asked — `share-format-v2`",
            openBody.contains("sealedHere -> ContactState("),
        )
        val codeState = openBody.substringAfter("private fun CodeState(")
            .substringBefore("private fun ColumnScope.OpenedState(")
        assertTrue(
            "the unreadable identity is a line under the code entry, not the single failure",
            codeState.contains("if (identityUnreadable)") && codeState.contains("onClick = onContacts"),
        )
        assertTrue(
            "and not a state of its own, because the flow cannot know the file wanted it",
            !openBody.contains("IdentityUnreadableState("),
        )
        assertTrue(
            "a contact container still reports the single failure when it will not open",
            labels(open.readSource()).contains("This share could not be opened."),
        )
    }

    /**
     * Pairing keeps §7.21's promises — P4-4.
     *
     * Three of these are the kind of thing that works perfectly while being the
     * defect: a scan that stops on a refusal, a code drawn in the app's palette,
     * and a screen that asks its question with no way back out of it.
     */
    @Test
    fun thePairingScreenSaysWhatItMustAndTheCodeIsReadableByACamera() {
        val pairing = kotlinSources().single { it.name == "PairingScreen.kt" }
        val copy = labels(pairing.readSource())
        val body = code(pairing.readSource())

        assertTrue(
            "pairing is mutual, and the screen that just succeeded is where that is read",
            copy.contains("Now let them scan your code."),
        )
        assertTrue(
            "the show state must say both sides scan, before either has",
            // The sentence wraps in source, so the assertion takes the half that
            // carries the claim rather than a line break's accident.
            copy.contains("You each need to scan the "),
        )
        assertTrue(
            "the name is the scanner's own and never leaves the phone — §7.21",
            copy.contains("stored on this phone and never "),
        )
        assertTrue(
            "a permission state is text plus one action, in §7.10's words",
            copy.contains("Allow the camera"),
        )

        // The code is read by a camera, not by a person: it is drawn dark on light
        // whatever the app's scheme is. A token here would follow the palette into
        // dark mode and make an unscannable code.
        assertTrue(
            "the code must be dark modules on a light field, not palette colours",
            body.contains("Color.White") && body.contains("Color.Black"),
        )
        assertTrue(
            "the code is drawn from the module grid, never rendered to a bitmap",
            body.contains("PairingQr.matrix(") && !body.contains("Bitmap"),
        )
        assertTrue(
            "three forward-only states, each leavable by back",
            body.contains("PairingStep.Show") && body.contains("PairingStep.Scan") &&
                body.contains("PairingStep.Name") && body.contains("onBack = onBack"),
        )
        assertTrue(
            "the screen keeps the shared chrome and adds no bar of its own",
            body.contains("VaultScaffold(") && !body.contains("TopAppBar"),
        )

        // **The camera screen is untouched by scan mode, and this is the proof.**
        // `VaultCameraSession.configure` takes the scan path only when a frame
        // callback is set, and the only thing that sets one is `scan()`. If the
        // camera screen ever calls it, P2-17's still and recording paths start
        // sharing a session shape with pairing — which is exactly the drift a
        // shared class invites and nothing else would fail on.
        val camera = code(kotlinSources().single { it.name == "CameraScreen.kt" }.readSource())
        assertTrue(
            "the camera screen must not enter scan mode",
            !camera.contains("session.scan(") && !camera.contains("stopScan("),
        )
        val session = code(kotlinSources().single { it.name == "VaultCamera.kt" }.readSource())
        assertTrue(
            "scan mode is entered only through a frame callback",
            session.contains("val frames = onFrame") && session.contains("if (frames == null)"),
        )

        // A refusal is drawn under a viewfinder that is still running. If the scan
        // were stopped, the user would have to restart it to try the code that was
        // going to work.
        val activity = code(kotlinSources().single { it.name == "MainActivity.kt" }.readSource())
        val decoded = activity.substringAfter("private fun pairingDecoded(")
            .substringBefore("private fun savePairedContact(")
        listOf("stopScan", "pairingScreen = false", "PairingStep.Show").forEach {
            assertTrue(
                "a refusal must not end the scan — found $it in pairingDecoded",
                !decoded.contains(it),
            )
        }
    }

    /**
     * The contact list shows names and never keys — P4-4, §7.21.
     *
     * The empty state and the unreadable state are both easy to leave as a blank
     * screen, and the second one is easy to "fix" by regenerating silently, which
     * would delete every contact for a user who never asked.
     */
    @Test
    fun theContactListShowsNamesAndNeverKeys() {
        val contacts = kotlinSources().single { it.name == "ContactsScreen.kt" }
        val copy = labels(contacts.readSource())
        val body = code(contacts.readSource())

        assertTrue(
            "§6.5 — an empty state is one sentence and one action",
            copy.contains("No contacts yet.") && copy.contains("Add a contact"),
        )
        assertTrue(
            "removal must not claim to unsend what was already sent",
            copy.contains("Anything you have already sent stays sent."),
        )
        assertTrue(
            "the empty state names both ways to pair, and removal names neither — `contact-pairing`",
            copy.contains("together, or by sending them an invite") &&
                copy.contains("You can pair with them again.") && !copy.contains("in person"),
        )
        assertTrue(
            "§6.6a — nothing here is corrupt, so nothing here says corrupt",
            !copy.contains("corrupt"),
        )
        assertTrue(
            "the unreadable state names its cause and offers exactly one action",
            copy.contains("cannot read its contacts") && copy.contains("Start a new identity"),
        )
        // **The one action here destroys every contact, so it does not wear the
        // affirmative colour and it asks §6.7's question first.** A filled primary
        // is what a user taps to go forward safely; §6.2 spends it on the trust
        // action, and a discard-everything button in that colour is the opposite
        // claim in the same paint.
        assertTrue(
            "starting a new identity must not be the screen's filled primary",
            body.contains("OutlinedButton(onClick = onAskNewIdentity"),
        )
        assertTrue(
            "and it must confirm once before anything is deleted — §6.7",
            body.contains("NewIdentityConfirmation(") &&
                copy.contains("Start a new identity?"),
        )
        assertTrue(
            "a key, a fingerprint or a hex string on this screen is furniture nobody can check",
            !body.contains("publicKey") && !body.contains("Base64"),
        )
        assertTrue(
            // The labels view, not the code view: `code()` blanks string literals,
            // and the point here is the description's *content* — what a screen
            // reader announces on a row of otherwise identical controls.
            "the row's control names which contact it removes",
            copy.contains("Remove ") && copy.contains("contact.name"),
        )
        // **§9: a word, not a glyph.** That section enumerates every icon-only
        // control in the app and a list row is not one of them — this row has the
        // width for a word, and the trash mark it would otherwise borrow means
        // *moved to the trash*, which is not what removing a contact does.
        // The Waiting and add-a-contact rows carry a chevron because they go
        // somewhere; a *contact* row goes nowhere and removes, so the rule is held
        // on the contact list itself.
        val list = body.substringAfter("private fun ContactList(").substringBefore("private fun UnreadableState(")
        assertTrue("the contact list was found", list.contains("contacts.forEach"))
        assertTrue(
            "a contact row must carry a text control, not an icon-only one",
            !list.contains("IconButton") && !list.contains("painterResource") && !body.contains("IconButton"),
        )
    }

    // --- exif-strip-choice: one selection, three questions -----------------------

    /**
     * ***Three egress points, three defaults, one definition.*** The import switch keeps by
     * default, the export control and the share block strip by default, and what a strip removes
     * is the Photo details sheet's one answer — named on the export control and the share block
     * in words from one function, so the copy and the strip cannot disagree.
     */
    @Test
    fun theMetadataControlsStateTheirOwnDefaultsAndTheirCeiling() {
        val export = kotlinSources().single { it.name == "ExportScreen.kt" }.readSource()
        assertTrue(
            "the export control must start on, which is the decision this change made",
            code(export).contains("var stripExif by remember { mutableStateOf(true) }"),
        )
        assertTrue(
            "the export control names what it removes, from the one summary",
            code(export).contains("ExifPolicy.exportSummary(localWords(), removed)"),
        )
        assertTrue("the control is named for details", labels(export).contains("Remove photo details"))
        assertTrue(
            "the off state must be stated too, not left as an unlabelled switch",
            labels(export).contains("Exported photos keep everything they carry"),
        )
        assertTrue(
            "and what it does not reach - a control that implies it cleans a whole library " +
                "when it cleans half of one is worse than no control",
            labels(kotlinSources().single { it.name == "ExifPolicy.kt" }.readSource())
                .contains("Other formats are written unchanged."),
        )

        val drawer = kotlinSources().single { it.name == "VaultDrawer.kt" }.readSource()
        val drawerCopy = labels(drawer)
        assertTrue("the row names the preset in effect", drawerCopy.contains("Photo details  ·  "))
        assertTrue(
            "and nothing else - the sheet explains, the row shows the value",
            !drawerCopy.contains("Imported photos are stored exactly as you gave them.") &&
                !drawerCopy.contains("Also removed from JPEG photos as you import them.") &&
                !code(drawer).contains("stripOnImport"),
        )
        assertTrue(
            "with the glyph that names what the setting is about",
            code(drawer).contains("R.drawable.ic_location_off"),
        )
        assertTrue("the row opens the sheet rather than carrying the switch", !code(drawer).contains("onToggleStripExif"))
        // The home drawer's install group is an enumerated set in the spec, "in this order
        // and no other". The row sits between intruder capture — after the panic credential since
        // `intruder-captures-at-home` — and contacts.
        val detailsAt = drawer.indexOf("\"Photo details")
        val intruderAt = drawer.indexOf("\"Intruder capture\"")
        val contactsAt = drawer.indexOf("\"Contacts\"")
        assertTrue("the photo details row must exist", detailsAt > 0)
        assertTrue(
            "it belongs between intruder capture and contacts",
            detailsAt in (intruderAt + 1) until contactsAt,
        )

        val sheet = kotlinSources().single { it.name == "PhotoDetailsSheet.kt" }.readSource()
        val sheetCode = code(sheet)
        assertTrue("the categories are chosen on a sheet", sheetCode.contains("ModalBottomSheet("))
        assertTrue("with no confirm button - a change applies as it is made", !sheetCode.contains("Button("))
        listOf(
            "Privacy (recommended)", "Custom", "Location", "Camera info", "Date and time",
            "Software and edit history", "Remove from photos you import", "JPEG photos only",
        ).forEach { assertTrue("the sheet must say \"$it\"", labels(sheet).contains(it)) }
        assertTrue(
            "the import row says a removal cannot be undone",
            labels(sheet).contains("Can't be undone for a photo once it's imported."),
        )
        assertTrue(
            "the fine print is one footer, not a paragraph among the rows",
            !labels(sheet).contains("Always kept:") && sheetCode.contains("Footer("),
        )
        // The row is shared since `media-settings` lifted it into SettingRows.kt.
        val rows = code(kotlinSources().single { it.name == "SettingRows.kt" }.readSource())
        assertTrue(
            "each category row is one switch to a screen reader",
            sheetCode.contains("SwitchRow(") && rows.contains("Role.Switch") &&
                rows.contains("onCheckedChange = null"),
        )

        val share = kotlinSources().single { it.name == "ShareScreen.kt" }.readSource()
        val shareCopy = labels(share)
        assertTrue(
            "the share asks its own question rather than inheriting the import setting",
            code(share).contains("val stripMetadata: Boolean = true") &&
                !share.contains("stripExifOnImport"),
        )
        assertTrue(
            "the removing row names what goes, from the one summary",
            code(share).contains("ExifPolicy.shareSummary(localWords(), removed)"),
        )
        assertTrue("the block is headed", shareCopy.contains("What the photo carries"))
        assertTrue("removing is offered", shareCopy.contains("Remove the details"))
        assertTrue("and so is not removing", shareCopy.contains("Send it as it is"))
        // Absent, not disabled, for an item with nothing to strip - this screen's own idiom.
        assertTrue(
            "the block must be gated on the item, not greyed out",
            code(share).contains("if (strippable) {"),
        )
    }

    // --- `vault-list-refresh` ---------------------------------------------------

    /**
     * The row's menu is **Edit vault**, a divider, **Remove** — in that order, and nothing else.
     * Edit vault replaced the rename sheet; its words must be inside the menu, not on the row.
     */
    @Test
    fun theVaultRowsMenuIsEditVaultThenRemove() {
        val list = labels(kotlinSources().single { it.name == "VaultListScreen.kt" }.readSource())
        val menu = list.substringAfter("DropdownMenu(").substringBefore("colors = ListItemDefaults.colors(")
        val edit = menu.indexOf("text = \"Edit vault\"")
        val divider = menu.indexOf("HorizontalDivider(")
        val remove = menu.indexOf("text = \"Remove\"")
        assertTrue("Edit vault, then a divider, then Remove", edit in 0 until divider && divider < remove)
        assertEquals("exactly two items", 2, Regex("DropdownMenuItem\\(").findAll(menu).count())
        assertFalse("the rename item is gone", menu.contains("\"Rename\""))
    }

    /** Only the home screen takes the large title — `ui-design-direction`. */
    @Test
    fun onlyTheVaultListAsksForTheLargeTitle() {
        val askers = kotlinSources()
            .filter { it.name != "VaultScaffold.kt" }
            .filter { code(it.readSource()).contains("largeTitle = true") }
            .map { it.name }
        assertEquals(listOf("VaultListScreen.kt"), askers)
    }

    /**
     * The grouped list's radii, gap and badge sizes are tokens: no `dp` literal at the call
     * site, so each is a value `DESIGN.md` §7.1 records.
     */
    @Test
    fun theVaultListHoldsNoLiteralDp() {
        val list = code(kotlinSources().single { it.name == "VaultListScreen.kt" }.readSource())
        val literals = Regex("\\b\\d+(\\.\\d+)?\\.dp\\b").findAll(list).map { it.value }.toList()
        assertEquals("read these from Spacing or Radius: $literals", emptyList<String>(), literals)
        listOf("Radius.groupOuter", "Radius.groupInner", "Spacing.groupGap", "Spacing.badge").forEach {
            assertTrue("$it must be what the list reads", list.contains(it))
        }
    }

    /**
     * A padlock on every row tells no row apart — the row leads with the vault's badge, and
     * the lock is a mark on a protected vault's badge only.
     */
    @Test
    fun noPadlockLeadsEveryVaultRow() {
        val list = code(kotlinSources().single { it.name == "VaultListScreen.kt" }.readSource())
        val leading = list.substringAfter("leadingContent = {").substringBefore("trailingContent = {")
        assertFalse("the row's leading content draws ic_lock", leading.contains("ic_lock"))
        assertTrue("the row leads with the vault's badge", leading.contains("VaultBadge("))
        val badge = list.substringAfter("fun VaultBadge(").substringBefore("private const val ICON_SHARE")
        assertTrue(
            "the lock mark is drawn only when the vault is locked",
            badge.substringAfter("if (locked) {").contains("ic_lock") &&
                !badge.substringBefore("if (locked) {").contains("ic_lock"),
        )
    }
}
