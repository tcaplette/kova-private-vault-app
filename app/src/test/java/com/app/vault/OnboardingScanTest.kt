package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * First run's order, and the three keys it is allowed to own —
 * `first-run-onboarding`.
 *
 * The order test is not ceremony. `Step.ordinal` **is** the sequence, so an enum
 * reordered by a refactor silently reorders first run — and five of the nine
 * positions are forced by behaviour rather than taste. A panic step that drifted
 * above the decoy step would enrol a credential that destroys nothing, with no
 * compiler error and no failing behaviour test anywhere else in the suite.
 *
 * The key scan is the other half: what onboarding must *not* remember is as much a
 * requirement as what it must.
 *
 * JVM scope: an enum and a source scan, so no emulator is involved.
 */
class OnboardingScanTest {

    @Test
    fun theNineStepsAreInTheOrderFirstRunRequires() {
        assertEquals(
            "first-run-onboarding fixes this order and five of the positions are forced. " +
                "If you are here because a refactor reordered the enum, the refactor is wrong.",
            listOf(
                "Name", "Passphrase", "Kit", "Fingerprint", "Capture",
                "Decoy", "Backup", "Panic", "Identity",
            ),
            Onboarding.Step.entries.map { it.name },
        )
    }

    @Test
    fun panicFollowsTheDecoyAndTheBackup() {
        // The correctness constraint, asserted as an ordering rather than trusted
        // to the list above: panic on a single-vault install destroys nothing, and
        // its enrolment screen names a backup only if one can already exist.
        assertTrue(
            "panic must follow the decoy, or it enrols a credential that does nothing",
            Onboarding.Step.Decoy < Onboarding.Step.Panic,
        )
        assertTrue(
            "panic must follow backup, or its disclosure cannot name the backup",
            Onboarding.Step.Backup < Onboarding.Step.Panic,
        )
        assertTrue(
            "backup must follow the decoy, or the size it quotes is about to be wrong",
            Onboarding.Step.Decoy < Onboarding.Step.Backup,
        )
    }

    @Test
    fun identityIsLast() {
        assertEquals(
            "a cover has no escape hatch, so nothing may follow it",
            Onboarding.Step.Identity, Onboarding.Step.entries.last(),
        )
        assertNull(Onboarding.Step.Identity.next)
    }

    /**
     * The first two steps cannot be passed, asserted where it is actually enforced.
     *
     * This used to read `Step.skippable`, an enum property restating an invariant it
     * did not enforce. The enforcement is, and always was, the two screens' own
     * gates: no vault exists until a valid confirmed passphrase is entered, and the
     * kit's 24 words are shown exactly once behind a typed acknowledgement. A source
     * scan rather than a Compose test because this is the JVM half of the suite —
     * `OnboardingStepScreenTest` exercises the same two gates on a device.
     */
    @Test
    fun theFirstTwoStepsGateTheirForwardAction() {
        val code = source("ui/OnboardingSteps.kt").readSource()

        val passphrase = code.substringAfter("fun PassphraseStepScreen")
            .substringBefore("fun RecoveryKitStepScreen")
        assertTrue(
            "the passphrase step must gate its forward action — there is no app without it",
            passphrase.contains("continueEnabled = ready"),
        )

        val kit = code.substringAfter("fun RecoveryKitStepScreen")
            .substringBefore("fun FingerprintStepScreen")
        assertTrue(
            "the kit step must gate its forward action on the typed acknowledgement — the " +
                "words are shown exactly once",
            kit.contains("continueEnabled = acknowledged"),
        )
    }

    /**
     * Every other step's forward action says whether it is passing the step.
     *
     * The label is the whole of what distinguishes *I did this* from *I am passing
     * this* now that there is one control instead of two. A step that dropped its
     * branch would silently claim the user completed something they walked past.
     */
    @Test
    fun everyStepAfterTheKitLabelsItsForwardAction() {
        val code = source("ui/OnboardingSteps.kt").readSource()
        listOf(
            "FingerprintStepScreen", "CaptureStepScreen", "DecoyStepScreen",
            "BackupStepScreen", "PanicStepScreen",
        ).forEach { screen ->
            val body = code.substringAfter("fun $screen").substringBefore("\n@Composable")
            assertTrue(
                "$screen must say \"Not now\" while its work is undone",
                body.contains("\"Continue\" else \"Not now\""),
            )
        }
        assertTrue(
            "the last step ends the flow under one label in both states",
            code.substringAfter("fun IdentityStepScreen")
                .contains("continueLabel = \"Finish setup\""),
        )
    }

    /**
     * The biometric step's rows are its work — `biometric-unlock-screen` D7. A secondary
     * button or a status line beside them would say a second time what the live row's
     * switch and line already say, and a second way to turn it on is a second thing to
     * keep in step with the first.
     */
    @Test
    fun theBiometricStepHasNoSecondaryAction() {
        val step = source("ui/OnboardingSteps.kt").readSource()
            .substringAfter("fun FingerprintStepScreen").substringBefore("\n@Composable")
        listOf("secondaryLabel", "onSecondary", "StatusLine(").forEach {
            assertFalse("the biometric step must not draw $it", step.contains(it))
        }
        assertTrue(
            "the step draws the screen's rows and facts, so the two cannot drift",
            step.contains("BiometricRows(") && step.contains("BiometricFacts("),
        )
        assertTrue("the step's title is fixed", step.contains("title = stringResource(BiometricWrap.NAME)"))
    }

    @Test
    fun positionsAreOneBasedAndContiguous() {
        assertEquals(1, Onboarding.Step.Name.position)
        assertEquals(2, Onboarding.Step.Passphrase.position)
        assertEquals(9, Onboarding.Step.Identity.position)
        assertEquals(
            Onboarding.Step.entries.map { it.ordinal + 1 },
            Onboarding.Step.entries.map { it.position },
        )
    }

    @Test
    fun eachStepPointsAtTheNext() {
        Onboarding.Step.entries.zipWithNext { a, b ->
            assertEquals("$a must lead to $b", b, a.next)
        }
    }

    /**
     * Every step is drawn on every device — `first-run-onboarding`,
     * `unavailable-capability-states`.
     *
     * The biometric step used to be passed over in place where the hardware could not do
     * it, and Back skipped it in reverse. A user on such a device met no language at all.
     * Now the step is drawn and says why, so there is nothing to skip in either direction.
     */
    @Test
    fun everyStepIsDrawnOnEveryDevice() {
        val activity = source("MainActivity.kt").readSource()
        assertFalse(
            "no step may be passed over for hardware — it is drawn and says why",
            activity.contains("onboardingStepApplies"),
        )
        val advance = activity.substringAfter("private fun advanceOnboarding(")
            .substringBefore("private fun completeOnboardingStep(")
        assertFalse(
            "advanceOnboarding must draw the step it is given, never move past it on its own",
            advance.contains("advanceOnboarding(step.next)"),
        )
        Onboarding.Step.entries.filter { it > Onboarding.Step.Capture }.forEach {
            assertEquals("Back from $it returns to the step before it", Onboarding.Step.entries[it.ordinal - 1], it.previousStep())
        }
        assertEquals(
            "Back from intruder capture returns to the biometric step, on every device",
            Onboarding.Step.Fingerprint, Onboarding.Step.Capture.previousStep(),
        )
        assertNull("never back past the kit", Onboarding.Step.Fingerprint.previousStep())
        assertNull("the vault exists by the kit, so there is nothing to go back to", Onboarding.Step.Kit.previousStep())
        assertEquals(
            "first-vault-naming: before the vault exists, the passphrase goes back to Name and look",
            Onboarding.Step.Name, Onboarding.Step.Passphrase.previousStep(),
        )
        assertNull("Back on Name and look is the welcome, which is not a step", Onboarding.Step.Name.previousStep())
    }

    // --- nothing ends the flow early ----------------------------------------

    /**
     * The back gesture moves one step. It never ends first run.
     *
     * It used to: `dismissTop`'s onboarding branch ended the flow, and later offered
     * to. Every user walks the nine steps, so back reaches `previousStep` and
     * nothing else; where there is none, back leaves the app and the flow pauses.
     *
     * A source scan because the branch is one arm of a `when` in a long activity,
     * and what has to hold is a property of the *shape*.
     */
    @Test
    fun theBackGestureMovesOneStepAndNeverEndsTheFlowOnItsOwn() {
        val branch = source("MainActivity.kt").readSource()
            .substringAfter("private fun dismissTop()")
            .substringBefore("backupScreen -> closeBackup()")

        assertTrue(
            "back inside the flow must move one step — the same call the frame's " +
                "Back button makes",
            branch.contains("previousStep()"),
        )
        assertFalse(
            "back must not offer to leave first run: there is no leaving",
            branch.contains("leavingOnboarding"),
        )
        assertFalse(
            "back must not end first run",
            branch.contains("finish("),
        )
    }

    /**
     * The negative control for the test above: `Onboarding.finish` has exactly three
     * callers — the end of the flow in `advanceOnboarding`, `completeSetup` before the
     * launcher entry switches, and the panic wipe's `destroyWrap`.
     */
    @Test
    fun theFlowEndsOnlyAtItsEndOrInAPanic() {
        val callers = Regex("""Onboarding\.finish\(""")
            .findAll(source("MainActivity.kt").readSource()).count()
        assertEquals(
            "first-run-onboarding: every user walks the nine steps, so the callers " +
                "that end the flow are counted. A new one is a way out.",
            3, callers,
        )
    }

    // --- what onboarding may not remember -------------------------------------

    private companion object {
        /**
         * The only keys this object may own. Anything else is onboarding recording
         * a decision the user made about their own safety — "declined panic",
         * "skipped the decoy" — which is the tell those features exist to avoid and
         * a decoy flag wearing a different name (I8).
         */
        val PERMITTED_KEYS = setOf("step", "done")
    }

    private fun source(name: String): File {
        var dir: File? = File(".").absoluteFile
        val rel = "src/main/java/com/app/vault"
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isDirectory }
                ?.let { return File(it, name) }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the source package from ${File(".").absolutePath}")
    }

    @Test
    fun onboardingOwnsOnlyTheThreeKeysItIsAllowedTo() {
        val code = source("Onboarding.kt").readSource()
        val keys = Regex("""KEY_[A-Z_]+\s*=\s*"([^"]+)"""").findAll(code)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(
            "first-run-onboarding: onboarding records the step and whether it finished, and " +
                "nothing about what any step decided. Found: $keys",
            PERMITTED_KEYS, keys,
        )
    }

    @Test
    fun theKeyScanWouldCatchAViolation() {
        // Negative control. A scan that has stopped matching passes every run.
        val found = Regex("""KEY_[A-Z_]+\s*=\s*"([^"]+)"""")
            .findAll("""private const val KEY_DECLINED_PANIC = "declinedPanic"""")
            .map { it.groupValues[1] }.toSet()
        assertEquals(setOf("declinedPanic"), found)
    }
}
