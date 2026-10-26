package com.app.vault

import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.vault.ui.BackupStatus
import com.app.vault.ui.BackupStepScreen
import com.app.vault.ui.BiometricLayout
import com.app.vault.ui.BiometricState
import com.app.vault.ui.NO_FRONT_CAMERA
import com.app.vault.ui.CaptureStepScreen
import com.app.vault.ui.DecoyStepScreen
import com.app.vault.ui.FingerprintStepScreen
import com.app.vault.ui.IdentityStepScreen
import com.app.vault.ui.NameStep
import com.app.vault.ui.OnboardingFrame
import com.app.vault.ui.OnboardingWelcomeScreen
import com.app.vault.ui.OnboardingLanguageScreen
import com.app.vault.ui.PassphraseStepScreen
import com.app.vault.ui.PanicStepScreen
import com.app.vault.ThemeChoice
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every first-run step can be completed — `first-run-onboarding`.
 *
 * **This class exists because the flow shipped unable to reach its own end.** The
 * first build gave a step a primary action only while something was unset, and the
 * capture step never had one at all. A user who turned a mode on was left with
 * "Skip" as the only forward control — which reads as *discard what I just did* —
 * and several steps had no way onward whatever the user chose. Nothing caught it,
 * because nothing asserted it.
 *
 * `FLAG_SECURE` makes this app unscreenshottable, so a human cannot eyeball these
 * screens and a reviewer cannot either. That is not a reason to check less; it is
 * the reason every structural claim about them has to be a test. The claims here
 * are the ones whose absence was the bug:
 *
 *  - every step offers a forward action, in every state it can be in;
 *  - that action is the *only* one — the Skip beside it called the same function,
 *    and its label promised an outcome it did not deliver;
 *  - the label says which outcome it is: "Not now" while the step's work is undone,
 *    "Continue" once it is done, "Finish setup" on the last step;
 *  - the counter starts at 1 and the frame is present from the first step.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingStepScreenTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun screen(content: @Composable () -> Unit, assertions: () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { VaultTheme { content() } } }
            compose.waitForIdle()
            assertions()
        }
    }

    /** Counts nodes without throwing when there are none. */
    private fun count(text: String): Int =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size


    private fun assertAdvances(
        label: String = "Continue",
        content: @Composable (() -> Unit) -> Unit,
    ) {
        var advanced = 0
        screen({ content { advanced++ } }) {
            assertTrue(
                "no forward action on this step — the flow cannot be completed",
                count(label) > 0,
            )
            compose.onNodeWithText(label).performClick()
            compose.waitForIdle()
        }
        assertEquals("the forward action must advance exactly once", 1, advanced)
    }

    // --- every step, in every state it can be in ------------------------------

    /** The biometric step, every input named — the activity's own call, with fakes. */
    private fun step(
        layout: BiometricLayout,
        state: BiometricState,
        fingerprint: Boolean = true,
        face: Boolean = false,
        onToggle: () -> Unit = {},
        onSetUp: () -> Unit = {},
        onContinue: () -> Unit = {},
    ): @Composable () -> Unit = {
        FingerprintStepScreen(
            layout = layout, state = state, hasFingerprint = fingerprint, hasFace = face,
            onToggle = onToggle, onSetUp = onSetUp, onContinue = onContinue,
        )
    }

    @Test fun fingerprintAdvancesBeforeEnrolment() =
        assertAdvances("Not now") { go -> step(BiometricLayout.Fingerprint, BiometricState.Off, onContinue = go)() }

    @Test fun fingerprintAdvancesAfterEnrolment() =
        assertAdvances { go -> step(BiometricLayout.Fingerprint, BiometricState.On, onContinue = go)() }

    @Test fun fingerprintAdvancesWhereTheDeviceCannotDoIt() =
        assertAdvances { go -> step(BiometricLayout.None, BiometricState.NotSupported, onContinue = go)() }

    // --- the biometric step in every layout and state — `biometric-unlock-screen` --

    private val allRows = listOf(
        BiometricWrap.FINGERPRINT_ROW, BiometricWrap.FACE_ROW, BiometricWrap.BOTH_ROW,
    ).map(ctx::getString)
    private val allFacts = BiometricLayout.entries.flatMap { l -> BiometricWrap.facts(l).map { it.first } }.toSet()

    /** Nodes whose text equals [text] exactly — a row's name, not a sentence containing it. */
    private fun exactly(text: String): Int =
        compose.onAllNodes(hasText(text, substring = false)).fetchSemanticsNodes().size

    /**
     * The step's contract, for one layout and state: the fixed title and lead, the rows the
     * layout names with their lines and no others, the layout's facts and no others, no
     * secondary action, and the forward label.
     */
    private fun assertStep(
        layout: BiometricLayout,
        state: BiometricState,
        rows: List<Pair<String, String>>,
        forward: String,
        fingerprint: Boolean = true,
        face: Boolean = false,
    ) {
        screen(step(layout, state, fingerprint, face)) {
            assertEquals("$layout/$state: the title is fixed", 1, exactly(ctx.getString(BiometricWrap.NAME)))
            assertTrue("$layout/$state: the lead", count(ctx.getString(BiometricWrap.LEAD)) > 0)
            rows.forEach { (name, line) ->
                compose.onNode(hasText(name, substring = false) and hasText(line, substring = false)).assertExists()
            }
            (allRows - rows.map { it.first }.toSet()).forEach {
                assertEquals("$layout/$state: no \"$it\" row", 0, exactly(it))
            }
            val facts = BiometricWrap.facts(layout).map { it.first }
            facts.forEach { assertTrue("$layout/$state: fact \"$it\"", count(ctx.getString(it)) > 0) }
            (allFacts - facts.toSet()).forEach { assertEquals("$layout/$state: no fact \"$it\"", 0, count(ctx.getString(it))) }
            assertEquals("$layout/$state: no secondary action", 0, count(ctx.getString(BiometricWrap.ENABLE_LABEL)))
            val other = if (forward == "Continue") "Not now" else "Continue"
            assertTrue("$layout/$state: the forward action reads \"$forward\"", count(forward) > 0)
            assertEquals("$layout/$state: the forward action must not read \"$other\"", 0, count(other))
        }
    }

    private fun live(name: String, state: BiometricState) = name to ctx.getString(BiometricWrap.liveLine(state)!!)
    private fun greyed(face: Boolean, present: Boolean) =
        ctx.getString(if (face) BiometricWrap.FACE_ROW else BiometricWrap.FINGERPRINT_ROW) to
            ctx.getString(BiometricWrap.unavailableLine(face, present))

    @Test fun theStepFingerprintOff() = assertStep(
        BiometricLayout.Fingerprint, BiometricState.Off,
        listOf(live(ctx.getString(BiometricWrap.FINGERPRINT_ROW), BiometricState.Off), greyed(face = true, present = true)),
        "Not now", face = true,
    )

    @Test fun theStepFingerprintOn() = assertStep(
        BiometricLayout.Fingerprint, BiometricState.On,
        listOf(live(ctx.getString(BiometricWrap.FINGERPRINT_ROW), BiometricState.On), greyed(face = true, present = false)),
        "Continue",
    )

    @Test fun theStepFingerprintNotSetUp() = assertStep(
        BiometricLayout.Fingerprint, BiometricState.NotSetUp,
        listOf(live(ctx.getString(BiometricWrap.FINGERPRINT_ROW), BiometricState.NotSetUp), greyed(face = true, present = false)),
        "Not now",
    )

    @Test fun theStepFingerprintNotAvailableNow() = assertStep(
        BiometricLayout.Fingerprint, BiometricState.NotAvailableNow,
        listOf(live(ctx.getString(BiometricWrap.FINGERPRINT_ROW), BiometricState.NotAvailableNow), greyed(face = true, present = false)),
        "Continue",
    )

    @Test fun theStepFingerprintNeedsUpdate() = assertStep(
        BiometricLayout.Fingerprint, BiometricState.NeedsUpdate,
        listOf(live(ctx.getString(BiometricWrap.FINGERPRINT_ROW), BiometricState.NeedsUpdate), greyed(face = true, present = false)),
        "Continue",
    )

    @Test fun theStepBoth() = assertStep(
        BiometricLayout.Both, BiometricState.Off,
        listOf(live(ctx.getString(BiometricWrap.BOTH_ROW), BiometricState.Off)),
        "Not now", face = true,
    )

    @Test fun theStepFace() = assertStep(
        BiometricLayout.Face, BiometricState.Off,
        listOf(greyed(face = false, present = false), live(ctx.getString(BiometricWrap.FACE_ROW), BiometricState.Off)),
        "Not now", fingerprint = false, face = true,
    )

    @Test fun theStepNone() = assertStep(
        BiometricLayout.None, BiometricState.NotSecureEnough,
        listOf(greyed(face = false, present = false), greyed(face = true, present = true)),
        "Continue", fingerprint = false, face = true,
    )

    /** The live row is the switch: one node with the switch role, carrying the state. */
    @Test
    fun theLiveRowIsOneSwitch() {
        var toggled = 0
        screen(step(BiometricLayout.Fingerprint, BiometricState.Off, onToggle = { toggled++ })) {
            compose.onNode(isToggleable() and hasText(ctx.getString(BiometricWrap.FINGERPRINT_ROW))).assertIsOff().performClick()
            compose.waitForIdle()
        }
        assertEquals("a tap on the row toggles once", 1, toggled)
        screen(step(BiometricLayout.Fingerprint, BiometricState.On)) {
            compose.onNode(isToggleable() and hasText(ctx.getString(BiometricWrap.FINGERPRINT_ROW))).assertIsOn()
        }
    }

    /** NotSetUp takes the enrolment route, not the enable prompt, and draws no switch. */
    @Test
    fun setUpOpensEnrolmentAndNotThePrompt() {
        var setUp = 0
        var toggled = 0
        screen(step(BiometricLayout.Fingerprint, BiometricState.NotSetUp, onToggle = { toggled++ }, onSetUp = { setUp++ })) {
            assertEquals("nothing to switch yet", 0, compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size)
            compose.onNodeWithText(ctx.getString(BiometricWrap.liveLine(BiometricState.NotSetUp)!!)).performClick()
            compose.waitForIdle()
        }
        assertEquals("the row must open enrolment once", 1, setUp)
        assertEquals("the row must not raise the enable prompt", 0, toggled)
    }

    /** A greyed row is one disabled node, and a tap on it does nothing. */
    @Test
    fun aGreyedRowIsDisabled() {
        var toggled = 0
        var setUp = 0
        screen(step(BiometricLayout.None, BiometricState.NotSupported, face = true, onToggle = { toggled++ }, onSetUp = { setUp++ })) {
            listOf(greyed(face = false, present = true), greyed(face = true, present = true)).forEach { (name, line) ->
                compose.onNode(hasText(name) and hasText(line)).assertIsNotEnabled().performClick()
            }
            compose.waitForIdle()
        }
        assertEquals(0, toggled)
        assertEquals(0, setUp)
    }

    /** Photo and Video are drawn with no front camera, greyed out and saying why; Audio keeps its switch. */
    @Test
    fun theCaptureStepDrawsTheModesItCannotUse() {
        screen({
            CaptureStepScreen(
                enabled = emptySet(), hasFrontCamera = false,
                onEnable = {}, onDisable = {}, problem = null, onContinue = {},
            )
        }) {
            assertEquals("Photo and Video each say why", 2, count(ctx.getString(NO_FRONT_CAMERA)))
            listOf("Photo", "Video").forEach {
                compose.onNode(hasText(it) and hasText(ctx.getString(NO_FRONT_CAMERA))).assertIsNotEnabled()
            }
            compose.onNodeWithText("Audio").assertExists()
            compose.onNodeWithText("A short clip from the microphone").assertExists()
            assertEquals(
                "only Audio's switch can be moved — Photo and Video draw theirs greyed out",
                1, compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size,
            )
        }
    }

    @Test fun captureAdvancesWithNoModesOn() = assertAdvances("Not now") { go ->
        CaptureStepScreen(
            enabled = emptySet(), hasFrontCamera = true,
            onEnable = {}, onDisable = {}, problem = null, onContinue = go,
        )
    }

    /** The exact state the flow got stuck in: a mode turned on, and no way forward. */
    @Test fun captureAdvancesAfterAModeIsTurnedOn() = assertAdvances { go ->
        CaptureStepScreen(
            enabled = setOf(Capture.Mode.PHOTO), hasFrontCamera = true,
            onEnable = {}, onDisable = {}, problem = null, onContinue = go,
        )
    }

    @Test fun captureAdvancesWithNoFrontCamera() = assertAdvances("Not now") { go ->
        CaptureStepScreen(
            enabled = emptySet(), hasFrontCamera = false,
            onEnable = {}, onDisable = {}, problem = null, onContinue = go,
        )
    }

    @Test fun decoyAdvancesBeforeOneIsBuilt() =
        assertAdvances("Not now") { go ->
            DecoyStepScreen(configured = false, onSetUp = {}, onContinue = go)
        }

    @Test fun decoyAdvancesAfterOneIsBuilt() =
        assertAdvances { go -> DecoyStepScreen(configured = true, onSetUp = {}, onContinue = go) }

    @Test fun backupAdvancesWithNoDestination() = assertAdvances("Not now") { go ->
        BackupStepScreen(
            status = BackupStatus.Off, folderName = null, storeBytes = 1_000L, problem = null,
            destinations = { emptyList<CloudMirror.Destination>() }, onChooseDestination = {}, onContinue = go,
        )
    }

    @Test fun backupAdvancesOnceAFolderIsChosen() = assertAdvances { go ->
        BackupStepScreen(
            status = BackupStatus.UpToDate(null), folderName = "Backups", storeBytes = 1_000L,
            problem = null, destinations = { emptyList<CloudMirror.Destination>() }, onChooseDestination = {}, onContinue = go,
        )
    }

    @Test fun panicAdvancesBeforeEnrolment() = assertAdvances("Not now") { go ->
        PanicStepScreen(enabled = false, onSetUp = {}, backupConfigured = false, onContinue = go)
    }

    @Test fun panicAdvancesAfterEnrolment() = assertAdvances { go ->
        PanicStepScreen(enabled = true, onSetUp = {}, backupConfigured = true, onContinue = go)
    }

    @Test fun identityAdvancesAndSaysItIsTheLast() =
        assertAdvances("Finish setup") { go ->
            IdentityStepScreen(onChoose = {}, onContinue = go)
        }

    // --- forward is the only way forward --------------------------------------

    /**
     * A step inside the frame offers one control that moves the flow on.
     *
     * The frame used to add a "Skip" beside the step's own action, wired to the
     * same call. Two controls, one behaviour, and a label claiming otherwise.
     */
    @Test
    fun theStepOffersOneForwardControlInsideTheFrame() {
        var advanced = 0
        screen({
            OnboardingFrame(
                step = Onboarding.Step.Capture,
                onBack = null,
            ) {
                CaptureStepScreen(
                    enabled = setOf(Capture.Mode.PHOTO), hasFrontCamera = true,
                    onEnable = {}, onDisable = {}, problem = null,
                    onContinue = { advanced++ },
                )
            }
        }) {
            assertEquals("there is no second way forward", 0, count("Skip"))
            compose.onNodeWithText("Continue").performClick()
            compose.waitForIdle()
        }
        assertEquals("Continue advanced the step", 1, advanced)
    }

    /**
     * The label is the disclosure, so it has to change with the state.
     *
     * Asserted as a pair on one screen rather than as two separate advances: what
     * matters is that the two states do not read the same, because a user who
     * walked past a step and one who completed it must not press the same word.
     */
    @Test
    fun theLabelDistinguishesPassingFromCompleting() {
        screen({ PanicStepScreen(enabled = false, onSetUp = {}, backupConfigured = false, onContinue = {}) }) {
            assertTrue("an un-enrolled step must say it is being passed", count("Not now") > 0)
            assertEquals("and must not claim the step was completed", 0, count("Continue"))
        }
        screen({ PanicStepScreen(enabled = true, onSetUp = {}, backupConfigured = false, onContinue = {}) }) {
            assertTrue("an enrolled step continues", count("Continue") > 0)
            assertEquals(0, count("Not now"))
        }
    }

    // --- the counter covers the whole flow ------------------------------------

    @Test
    fun theCounterStartsAtOne() {
        // The first build excluded the passphrase and the kit, so the rail first
        // appeared reading "Step 3 of 8", already a third full, on a flow the user
        // thought had just started.
        screen({
            OnboardingFrame(
                step = Onboarding.Step.Name,
                onBack = null,
            ) { }
        }) {
            assertTrue("the flow must count from its first screen", count("Step 1 of 9") > 0)
        }
    }

    // --- Name and look, `first-vault-naming` ----------------------------------

    /**
     * Step 1 is New vault's Name and look, inside the frame: the badge, the field, both
     * choosers, and a Continue that refuses an empty name under the field.
     */
    @Test
    fun nameAndLookDrawsTheBadgeTheFieldAndBothChoosersAndRefusesAnEmptyName() {
        var continued = 0
        screen({
            var name by remember { mutableStateOf("") }
            var appearance by remember {
                mutableStateOf(VaultAppearance.defaultFor(0))
            }
            var error by remember { mutableStateOf<String?>(null) }
            OnboardingFrame(step = Onboarding.Step.Name, onBack = null) {
                NameStep(
                    name = name,
                    onName = { name = it; error = null },
                    appearance = appearance,
                    onAppearance = { appearance = it },
                    accepts = VaultKeyring::fits,
                    error = error,
                    onContinue = {
                        error = if (name.isBlank()) ctx.getString(R.string.vault_name_empty) else null
                        if (error == null) continued++
                    },
                )
            }
        }) {
            assertTrue(count("Step 1 of 9") > 0)
            assertTrue("the title", count("Name your vault") > 0)
            assertTrue("the lead", count("You'll see this name and badge on your vault list.") > 0)
            compose.onNode(hasSetTextAction()).assertExists()
            assertTrue("the colour chooser", count("Colour") > 0)
            compose.onNode(hasContentDescription("Teal")).assertExists()
            assertTrue("the icon chooser", count("Icon") > 0)
            compose.onNode(hasContentDescription("Home")).assertExists()

            compose.onNodeWithText("Continue").performClick()
            compose.waitForIdle()
            assertTrue("an empty name is refused, under the field", count("Give the vault a name.") > 0)
            assertEquals("an empty name must not leave the step", 0, continued)

            compose.onNode(hasSetTextAction()).performTextInput("Family")
            compose.onNodeWithText("Continue").performClick()
            compose.waitForIdle()
            assertEquals(0, count("Give the vault a name."))
            assertEquals(1, continued)
        }
    }

    @Test
    fun everyStepIsInsideTheCounter() {
        Onboarding.Step.entries.forEach { step ->
            screen({ OnboardingFrame(step = step, onBack = null) { } }) {
                assertTrue(
                    "$step is not framed, so the flow's position jumps at it",
                    count("Step ${step.position} of 9") > 0,
                )
            }
        }
    }

    // --- the passphrase field's reveal and match feedback ---------------------

    /**
     * The reveal toggles, per field, and says which state it is in.
     *
     * **What this can and cannot assert.** Revealing is a `VisualTransformation`,
     * and Compose exposes no semantics for the transformed string — the node keeps
     * the real characters, which the IME requires. material3 also declares both
     * fields passwords from `keyboardType` regardless of reveal, so that property
     * is not a probe for this either. What *is* observable, and what a user and a
     * screen reader both perceive, is the toggle's own state: this asserts that,
     * and that the two fields hold it independently.
     */
    @Test
    fun eachFieldRevealsIndependently() {
        screen({
            PassphraseStepScreen(onCreate = {}, busy = false, progress = null)
        }) {
            fun showing() = compose.onAllNodesWithContentDescription("Show passphrase")
                .fetchSemanticsNodes().size
            fun hiding() = compose.onAllNodesWithContentDescription("Hide passphrase")
                .fetchSemanticsNodes().size

            assertEquals("both fields start hidden", 2, showing())
            assertEquals(0, hiding())

            compose.onAllNodesWithContentDescription("Show passphrase").onFirst().performClick()
            compose.waitForIdle()
            assertEquals(
                "one toggle reveals one field; the other must stay hidden",
                1, showing(),
            )
            assertEquals(1, hiding())

            compose.onAllNodesWithContentDescription("Show passphrase").onFirst().performClick()
            compose.waitForIdle()
            assertEquals("both revealed", 0, showing())
            assertEquals(2, hiding())

            compose.onAllNodesWithContentDescription("Hide passphrase").onFirst().performClick()
            compose.waitForIdle()
            assertEquals("hiding puts one back", 1, showing())
        }
    }

    @Test
    fun theMatchLineAppearsOnlyWhenTheEntriesAgree() {
        screen({
            PassphraseStepScreen(onCreate = {}, busy = false, progress = null)
        }) {
            assertEquals("nothing typed is not a match", 0, count("Both entries match"))

            compose.onNodeWithText("Passphrase").performTextInput("a-long-enough-passphrase")
            compose.onNodeWithText("Enter it again").performTextInput("a-long-enough-passphras")
            compose.waitForIdle()
            assertEquals(
                "a near miss is not a match",
                0, count("Both entries match"),
            )

            compose.onNodeWithText("Enter it again").performTextInput("e")
            compose.waitForIdle()
            assertTrue(
                "the user must be told the two boxes agree, not left to guess",
                count("Both entries match") > 0,
            )
        }
    }

    @Test
    fun createIsRefusedUntilTheEntriesMatch() {
        var created = 0
        screen({
            PassphraseStepScreen(onCreate = { created++ }, busy = false, progress = null)
        }) {
            compose.onNodeWithText("Passphrase").performTextInput("a-long-enough-passphrase")
            compose.onNodeWithText("Enter it again").performTextInput("something-else-entirely")
            compose.waitForIdle()
            compose.onNodeWithText("Create vault").performClick()
            compose.waitForIdle()
        }
        assertEquals("a mismatch must never reach vault creation", 0, created)
    }

    // --- the strength floor, `passphrase-strength-meter` ------------------------

    /**
     * The requirement is on the field before anything is typed. A user who learns the rule
     * from a refusal has already been refused once for no reason.
     */
    @Test
    fun theFloorIsStatedBeforeAnythingIsTyped() {
        screen({
            PassphraseStepScreen(onCreate = {}, busy = false, progress = null)
        }) {
            assertTrue(
                "the empty field must state what is required",
                count(ctx.getString(R.string.passphrase_requirement, PassphraseStrength.MIN_CODE_POINTS)) > 0,
            )
            assertEquals("and must not refuse anything yet", 0, count(ctx.getString(R.string.passphrase_too_weak)))
        }
    }

    /**
     * `passwordpassword` is sixteen characters, so a length-only floor would have taken it.
     * This is the case the estimate exists for, asserted through the screen rather than only
     * in `PassphraseStrengthTest`.
     */
    @Test
    fun aLongButGuessablePassphraseNeverReachesCreation() {
        var created = 0
        screen({
            PassphraseStepScreen(onCreate = { created++ }, busy = false, progress = null)
        }) {
            compose.onNodeWithText("Passphrase").performTextInput("passwordpassword")
            compose.onNodeWithText("Enter it again").performTextInput("passwordpassword")
            compose.waitForIdle()
            // The entries agree, so a mismatch is not what is refusing this.
            assertTrue("the two entries do match", count("Both entries match") > 0)
            compose.onNodeWithText("Create vault").performClick()
            compose.waitForIdle()
        }
        assertEquals("a guessable passphrase must never reach vault creation", 0, created)
    }

    /** The meter counts up to the floor while the passphrase is too short to measure. */
    @Test
    fun theMeterCountsTowardsTheFloor() {
        screen({
            PassphraseStepScreen(onCreate = {}, busy = false, progress = null)
        }) {
            compose.onNodeWithText("Passphrase").performTextInput("three quiet")
            compose.waitForIdle()
            assertTrue(
                "a short passphrase is told how far it has to go",
                count("11 of 15 characters") > 0,
            )
        }
    }

    /** And a passphrase that clears it is allowed through. */
    @Test
    fun aPassphraseThatClearsTheFloorReachesCreation() {
        var created = 0
        screen({
            PassphraseStepScreen(onCreate = { created++ }, busy = false, progress = null)
        }) {
            compose.onNodeWithText("Passphrase").performTextInput("three quiet lemons argue")
            compose.onNodeWithText("Enter it again").performTextInput("three quiet lemons argue")
            compose.waitUntil(WAIT_MS) { count("Strong enough") > 0 }
            compose.onNodeWithText("Create vault").performClick()
            compose.waitForIdle()
        }
        assertEquals("a passphrase clearing the floor must be accepted", 1, created)
    }

    /**
     * The meter in the **light** scheme — `DESIGN.md` §4.1 binds both schemes, and a
     * component that only works in the one the developer happens to run is the defect that
     * rule exists for.
     *
     * That it is not the *primary* in either scheme is carried by
     * `PassphraseStrengthMeterScanTest`, which fails on `VaultColors.Primary` in the meter's
     * source: the token is scheme-independent, so proving it is absent proves it for both.
     * What a screenshot cannot prove and this can is that the meter still renders, still
     * reaches its levels, and still refuses, with the light palette in place.
     */
    @Test
    fun theMeterWorksInTheLightScheme() {
        var created = 0
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    VaultTheme(choice = ThemeChoice.Light) {
                        PassphraseStepScreen(
                            onCreate = { created++ }, busy = false, progress = null,
                        )
                    }
                }
            }
            compose.waitForIdle()

            assertTrue("the requirement is stated in light too", count(ctx.getString(R.string.passphrase_requirement, PassphraseStrength.MIN_CODE_POINTS)) > 0)

            compose.onNodeWithText("Passphrase").performTextInput("passwordpassword")
            compose.waitUntil(WAIT_MS) { count("Very weak") > 0 }

            compose.onNodeWithText("Enter it again").performTextInput("passwordpassword")
            compose.waitForIdle()
            compose.onNodeWithText("Create vault").performClick()
            compose.waitForIdle()
        }
        assertEquals("the floor holds in the light scheme", 0, created)
    }

    // --- welcome-language-choice ----------------------------------------------------------

    /**
     * The welcome's language control names the language on screen in its own script, is
     * spoken as the drawer row is, and opens the sheet — not Get started, not restore.
     */
    @Test
    fun theWelcomeOffersTheLanguage() {
        var opened = 0
        var begun = 0
        val shown = AppLanguage.current(ctx) ?: AppLanguage.systemResolved(ctx)
        val spoken = ctx.getString(R.string.drawer_language, shown.ownName)
        screen({ OnboardingWelcomeScreen(onBegin = { begun++ }, onRestore = {}, onLanguage = { opened++ }) }) {
            compose.onNode(hasText(shown.ownName), useUnmergedTree = true).assertExists()
            val control = compose.onNode(hasContentDescription(spoken) and hasClickAction())
            control.assertExists()
            control.performClick()
            compose.waitForIdle()
        }
        assertEquals("the control opens the language sheet", 1, opened)
        assertEquals("the control does not start the flow", 0, begun)
    }

    // --- onboarding-language-screen -------------------------------------------------------

    /**
     * First run's language screen lists System default and the nine, selects System default
     * where nothing is chosen, hands a tapped row to `onChoose`, and Continue to `onContinue`.
     */
    @Test
    fun theLanguageScreenListsChoosesAndContinues() {
        val before = AppLanguage.current(ctx)
        AppLanguage.set(ctx, null)
        try {
            val chosen = mutableListOf<String?>()
            var continued = 0
            screen({ OnboardingLanguageScreen(onChoose = { chosen += it?.tag }, onContinue = { continued++ }) }) {
                val systemDefault = ctx.getString(R.string.language_system_default)
                compose.onNode(hasText(systemDefault) and hasClickAction()).assertIsSelected()
                AppLanguage.supported.forEach {
                    compose.onAllNodes(hasText(it.ownName) and hasClickAction())
                        .filter(!hasText(systemDefault)).onFirst().assertExists()
                }
                compose.onAllNodes(hasText("Deutsch") and hasClickAction())
                    .filter(!hasText(systemDefault)).onFirst().performScrollTo().performClick()
                compose.onNodeWithText(ctx.getString(R.string.action_continue)).performClick()
                compose.waitForIdle()
            }
            assertEquals("the tapped row is handed over", listOf<String?>("de"), chosen)
            assertEquals("Continue leaves the screen", 1, continued)
        } finally {
            AppLanguage.set(ctx, before)
        }
    }
}

/** The estimate runs off the main thread, so a level is waited for rather than assumed. */
private const val WAIT_MS = 5_000L
