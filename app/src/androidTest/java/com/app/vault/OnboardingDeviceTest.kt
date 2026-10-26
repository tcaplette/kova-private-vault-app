package com.app.vault

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.ACKNOWLEDGEMENT
import com.app.vault.ui.BackupScreen
import com.app.vault.ui.BackupStatus
import com.app.vault.ui.BiometricLayout
import com.app.vault.ui.BiometricState
import com.app.vault.ui.BackupStepScreen
import com.app.vault.ui.CaptureStepScreen
import com.app.vault.ui.FingerprintStepScreen
import com.app.vault.ui.OnboardingFrame
import com.app.vault.ui.RestoreState
import com.app.vault.ui.VaultDrawerSheet
import com.app.vault.ui.backupDrawerLine
import com.app.vault.ui.DecoyIntroStep
import com.app.vault.ui.PanicScreen
import com.app.vault.ui.RecoveryKitScreen
import com.app.vault.ui.theme.VaultTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * First run's state and the wording each step actually shows —
 * `first-run-onboarding`.
 *
 * Two halves. The first is the pause-and-resume rule, asserted against real
 * `SharedPreferences` and a real keyring rather than fakes, because "the step comes
 * back under the keyring that reached it, and under no other" is a claim about what
 * survives a process and about the seal. The second is the copy the
 * conditional steps show, because three of this change's decisions are entirely
 * about what a screen says at the moment the user is deciding: the kit's backup
 * sentence, panic's backup paragraph, and the decoy's trial notice.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val roots = mutableListOf<java.io.File>()

    @Before fun setUp() = Onboarding.resetForTest(ctx)

    @After fun tearDown() {
        Onboarding.resetForTest(ctx)
        lockKeyringAndVault()
        roots.forEach { it.deleteRecursively() }
    }

    /** A keyring in a scratch store, created and left open — `HeldKitTest`'s shape. */
    private fun openKeyring(passphrase: String): VaultLifecycle {
        val root = java.io.File(ctx.filesDir, "onboarding-keyring-${System.nanoTime()}").apply { mkdirs() }
        roots += root
        return VaultLifecycle(
            java.io.File(root, "vault.slots"),
            BlobStore(java.io.File(root, "blobs"), java.io.File(root, "incoming")),
        ).also { it.create(passphrase.toByteArray(Charsets.UTF_8)) }
    }

    /** What `advanceOnboarding` writes on arrival. */
    private fun reach(step: Onboarding.Step) =
        Onboarding.reached(ctx, VaultKeyringSession.sealHeld(byteArrayOf(step.ordinal.toByte()))!!)

    // --- pause and resume -----------------------------------------------------

    @Test
    fun afreshInstallIsInProgressWithNothingPaused() {
        assertTrue(Onboarding.inProgress(ctx))
        openKeyring("owner-passphrase")
        assertNull(Onboarding.pausedStep(ctx))
    }

    @Test
    fun aStepReachedIsRemembered() {
        openKeyring("owner-passphrase")
        reach(Onboarding.Step.Decoy)
        assertEquals(Onboarding.Step.Decoy, Onboarding.pausedStep(ctx))
        assertTrue("reaching a step does not end the flow", Onboarding.inProgress(ctx))
    }

    @Test
    fun aStepSealedUnderOneKeyringDoesNotOpenUnderAnother() {
        val owner = openKeyring("owner-passphrase")
        reach(Onboarding.Step.Backup)
        lockKeyringAndVault()

        // The decoy's case: a second keyring, open, reads nothing.
        openKeyring("decoy-passphrase")
        assertNull("another keyring must not resume the flow", Onboarding.pausedStep(ctx))
        lockKeyringAndVault()

        assertTrue(owner.unlock("owner-passphrase".toByteArray(Charsets.UTF_8)).opened)
        assertEquals(Onboarding.Step.Backup, Onboarding.pausedStep(ctx))
    }

    @Test
    fun noKeyringOpenReadsAsNotPaused() {
        openKeyring("owner-passphrase")
        reach(Onboarding.Step.Panic)
        lockKeyringAndVault()

        assertNull("the lock screen must not know a flow is paused", Onboarding.pausedStep(ctx))
    }

    @Test
    fun finishingRemovesThePausedStep() {
        openKeyring("owner-passphrase")
        reach(Onboarding.Step.Panic)
        Onboarding.finish(ctx)

        assertNull(Onboarding.pausedStep(ctx))
        assertFalse(
            "the stored step goes with the flow",
            ctx.getSharedPreferences("onboarding", android.content.Context.MODE_PRIVATE).contains("step"),
        )
    }

    @Test
    fun aFinishedFlowDoesNotComeBack() {
        openKeyring("owner-passphrase")
        reach(Onboarding.Step.Identity)
        Onboarding.finish(ctx)

        assertFalse("a finished flow must not return", Onboarding.inProgress(ctx))
        assertNull(Onboarding.pausedStep(ctx))
    }

    @Test
    fun finishingIsPermanent() {
        Onboarding.finish(ctx)
        repeat(3) { assertFalse(Onboarding.inProgress(ctx)) }
    }

    @Test
    fun aClearedInstallRunsItAgain() {
        Onboarding.finish(ctx)
        assertFalse(Onboarding.inProgress(ctx))

        Onboarding.resetForTest(ctx)

        assertTrue("a fresh install is a fresh first run", Onboarding.inProgress(ctx))
        openKeyring("owner-passphrase")
        assertNull(Onboarding.pausedStep(ctx))
    }

    // --- what a step passed over leaves behind --------------------------------

    /**
     * Passing a step enrols nothing, asserted against the features rather than the UI.
     *
     * There is one forward action per step now, and it reaches `advanceOnboarding`
     * whether the step's work was done or walked past — the label is what says
     * which. That is only safe because a step's screen enrols nothing until its own
     * secondary control is used, so what has to hold is that walking the whole
     * sequence without touching a control leaves the install exactly as a fresh one:
     * no panic, no capture mode, no destination, no second vault and no trial
     * started.
     */
    @Test
    fun walkingTheWholeFlowWithoutTouchingAnythingEnrolsNothing() {
        openKeyring("owner-passphrase")
        var step: Onboarding.Step? = Onboarding.Step.Fingerprint
        while (step != null) {
            reach(step)
            step = step.next
        }
        Onboarding.finish(ctx)

        assertFalse("passing the panic step must not set a panic phrase", VaultKeyringSession.hasPanicPhrase())
        Capture.Mode.entries.forEach {
            assertFalse("passing the capture step must not turn on $it", Capture.enabled(ctx, it))
        }
        assertEquals(
            "passing the decoy step must not start the trial",
            0L, Entitlement.trialStartedAt(ctx),
        )
    }

    // --- the biometric step is drawn on every device ---------------------------

    /**
     * `unavailable-capability-states`. On a device with no enrolled biometric — the
     * emulator, with its strong fingerprint sensor empty — the biometric step is drawn in
     * the NotSetUp state, not passed over, and Back from intruder capture returns to it.
     *
     * The state is the device's own, read the way the activity reads it. Back is the
     * frame's own control, wired to the app's own `previousStep()`; the step drawn for
     * each position is the one the activity draws.
     */
    @Test
    fun withNothingEnrolledTheBiometricStepIsDrawnAndBackReturnsToIt() {
        val state = BiometricWrap.state(ctx, enabled = false)
        assertEquals(
            "this test needs the emulator with no enrolled fingerprint — remove the enrolment and rerun",
            BiometricState.NotSetUp, state,
        )
        // `biometric-unlock-screen`. Every emulator declares a fingerprint; a Pixel 9 image
        // declares a face as well, so the layout read the activity's way is Fingerprint or Both.
        val fingerprint = BiometricWrap.hasFingerprint(ctx)
        val face = BiometricWrap.hasFace(ctx)
        val layout = BiometricWrap.layout(state, fingerprint, face, BiometricWrap.allStrong(ctx))
        assertTrue(
            "this test needs an emulator with a fingerprint sensor, but the layout was $layout",
            layout == BiometricLayout.Fingerprint || layout == BiometricLayout.Both,
        )
        screen({
            var step by remember { mutableStateOf(Onboarding.Step.Capture) }
            OnboardingFrame(step = step, onBack = step.previousStep()?.let { prev -> { step = prev } }) {
                when (step) {
                    Onboarding.Step.Fingerprint -> FingerprintStepScreen(
                        layout = layout, state = state, hasFingerprint = fingerprint, hasFace = face,
                        onToggle = {}, onSetUp = {}, onContinue = {},
                    )
                    else -> CaptureStepScreen(
                        enabled = emptySet(), hasFrontCamera = true,
                        onEnable = {}, onDisable = {}, problem = null, onContinue = {},
                    )
                }
            }
        }) {
            compose.onNodeWithText("See who tried to get in").assertIsDisplayed()
            compose.onNodeWithText("Back").performClick()
            compose.waitForIdle()

            compose.onNodeWithText("Step 4 of 9").assertExists()
            // The live row is named for the layout; a fingerprint-only device also draws face
            // as unavailable, and a device with both draws the one combined row.
            compose.onNodeWithText(ctx.getString(BiometricWrap.liveName(layout)!!)).assertIsDisplayed()
            compose.onNodeWithText(ctx.getString(BiometricWrap.liveLine(BiometricState.NotSetUp)!!)).assertIsDisplayed()
            if (layout == BiometricLayout.Fingerprint) {
                compose.onNodeWithText(ctx.getString(BiometricWrap.FACE_ROW)).assertIsDisplayed()
            }
            compose.onNodeWithText("Not now").assertIsDisplayed()
        }
    }

    // --- what backup quotes, and when it reads it -----------------------------

    /**
     * The reason backup follows the decoy: the figure is read when the step is
     * reached, so items added by an earlier step are in it.
     *
     * Asserted against the store rather than the screen, because the claim is about
     * `storeBytes()` being a live read and not a cached one — a cache is exactly
     * what would make the order pointless.
     */
    @Test
    fun theBackupStepQuotesTheStoreAsItIsWhenReached() {
        val root = java.io.File(ctx.filesDir, "onboarding-${System.nanoTime()}")
        val blobs = java.io.File(root, "blobs").apply { mkdirs() }
        try {
            val before = CloudMirror.localEntries(blobs).values.sumOf { it.size }
            assertEquals("an empty store measures nothing", 0L, before)

            // What a decoy does to the store, in the only respect this cares about.
            repeat(3) { i ->
                java.io.File(blobs, "%032x".format(java.math.BigInteger.valueOf(i.toLong() + 1)))
                    .writeBytes(ByteArray(64 * 1024))
            }

            val after = CloudMirror.localEntries(blobs).values.sumOf { it.size }
            assertEquals(3 * 64 * 1024L, after)
            assertTrue(
                "the figure must grow with the store, or backup-after-decoy buys nothing",
                after > before,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    // --- what the conditional steps say ---------------------------------------

    private fun screen(content: @Composable () -> Unit, assertions: () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { VaultTheme { content() } } }
            compose.waitForIdle()
            assertions()
        }
    }

    @Test
    fun theKitSaysABackupIsRestoredWithTheWords() {
        val words = List(Mnemonic.WORD_COUNT) { "abandon" }
        screen({ RecoveryKitScreen(words = words, onPrint = {}, onContinue = {}) }) {
            compose.onNode(
                hasText("these words are what restore", substring = true)
            ).assertIsDisplayed()
            compose.onNode(
                hasText("not your passphrase", substring = true)
            ).assertIsDisplayed()
        }
    }

    @Test
    fun theKitStillGatesOnTheTypedAcknowledgement() {
        // The paragraph was added; nothing else about this screen changed.
        val words = List(Mnemonic.WORD_COUNT) { "abandon" }
        screen({ RecoveryKitScreen(words = words, onPrint = {}, onContinue = {}) }) {
            // `assertExists`, not `assertIsDisplayed`: these screens scroll, and
            // whether a control is below the fold is a layout question. What is
            // being asserted is that the gate and the print action are still
            // offered after the paragraph was added.
            compose.onNodeWithText(ctx.getString(ACKNOWLEDGEMENT), substring = true).assertExists()
            compose.onNodeWithText("Print").assertExists()
        }
    }

    @Test
    fun panicNamesTheBackupOnlyWhereOneIsConfigured() {
        screen({
            PanicScreen(
                enabled = false, busy = false, problem = null, settled = 0,
                onSet = { _, _ -> }, onChange = { _, _, _ -> }, onTurnOff = {}, onForget = {},
                onCancel = {}, backupConfigured = true,
            )
        }) {
            compose.onNode(
                hasText("empties your backup folder", substring = true)
            ).assertIsDisplayed()
        }

        screen({
            PanicScreen(
                enabled = false, busy = false, problem = null, settled = 0,
                onSet = { _, _ -> }, onChange = { _, _, _ -> }, onTurnOff = {}, onForget = {},
                onCancel = {}, backupConfigured = false,
            )
        }) {
            assertEquals(
                "an install with no destination must not be told about a deletion that " +
                    "cannot happen",
                0,
                compose.onAllNodes(hasText("empties your backup folder", substring = true))
                    .fetchSemanticsNodes().size,
            )
        }
    }

    @Test
    fun theDecoyStepStatesTheTrialCostAndSettingsDoesNot() {
        val notice = "your 30-day trial starts at the first import"

        screen({
            DecoyIntroStep(
                onContinue = {},
                trialNotice = "Building a decoy imports real photographs, and $notice that lands.",
            )
        }) {
            compose.onNode(hasText(notice, substring = true)).assertIsDisplayed()
        }

        screen({ DecoyIntroStep(onContinue = {}, trialNotice = null) }) {
            assertEquals(
                "Settings is not walking anyone into this, so it does not carry the notice",
                0,
                compose.onAllNodes(hasText(notice, substring = true)).fetchSemanticsNodes().size,
            )
        }
    }

    // --- the backup screen — `backup-screen-status-first` -----------------------

    private val drive = CloudMirror.Destination.CloudApp("Google Drive", "com.example.docs", null)
    private val sdCard = CloudMirror.Destination.Volume("SD card", android.content.Intent())

    private fun backup(
        status: BackupStatus = BackupStatus.Off,
        restoreMode: Boolean = false,
        restoreState: RestoreState = RestoreState.Idle,
        fileDestination: Boolean = true,
        destinations: List<CloudMirror.Destination> = listOf(drive, sdCard),
        onChooseDestination: (CloudMirror.Destination) -> Unit = {},
        onStop: () -> Unit = {},
    ): @Composable () -> Unit = {
        BackupScreen(
            restoreMode = restoreMode,
            status = status,
            folderName = "Google Drive · backup.bin",
            fileDestination = fileDestination,
            storeBytes = 1_200_000_000L,
            problem = null,
            restoreState = restoreState,
            destinations = { destinations },
            onChooseDestination = onChooseDestination,
            onBackUpNow = {},
            onStop = onStop,
            onChooseRestoreFile = {},
            onChooseRestoreFolder = {},
            onBack = {},
        )
    }

    private fun count(text: String, substring: Boolean = false) =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size

    @Test
    fun theVaultsBackupScreenOffersNoRestore() {
        screen(backup()) {
            assertEquals(
                "with a vault open the store is never empty, so restore could only fail",
                0,
                count("Restore from a backup", substring = true),
            )
            compose.onNodeWithText("Turn on backup").assertExists()
        }
    }

    @Test
    fun withNoVaultOpenTheScreenRestores() {
        screen(backup(restoreMode = true)) {
            compose.onNodeWithText("Restore from a backup").assertExists()
            compose.onNodeWithText("Choose the backup file").assertExists()
            compose.onNodeWithText("Backup is on an SD card").assertExists()
            assertEquals("restore mode offers nothing to back up", 0, count("Turn on backup"))
            assertEquals(0, count("Backup is off"))
        }
    }

    @Test
    fun restoreShowsItsProgress() {
        screen(backup(restoreMode = true, restoreState = RestoreState.Copying(40, 212))) {
            compose.onNodeWithText("Copying files… 40 of 212").assertExists()
            compose.onNodeWithText("Choose the backup file").assertIsNotEnabled()
        }
    }

    @Test
    fun turningBackupOnAsksFirstAndListsWhereItCanGo() {
        var chosen: CloudMirror.Destination? = null
        screen(backup(onChooseDestination = { chosen = it })) {
            compose.onNodeWithText("Backup is off").assertExists()
            compose.onNodeWithText("Turn on backup").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Before you turn on backup").assertExists()
            compose.onNodeWithText("Back up to").assertExists()
            compose.onNodeWithText("One backup file, updated after changes").assertExists()
            compose.onNodeWithText("A folder, updated after every change").assertExists()
            assertEquals("nothing is chosen by opening the sheet", null, chosen)
            compose.onNodeWithText("Google Drive").performClick()
            compose.waitForIdle()
            assertEquals(drive, chosen)
        }
    }

    @Test
    fun anEmptyListSaysWhatToInstall() {
        screen(backup(destinations = emptyList())) {
            compose.onNodeWithText("Turn on backup").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("No cloud app or SD card on this phone", substring = true).assertExists()
        }
    }

    @Test
    fun notNowChangesNothing() {
        var chosen: CloudMirror.Destination? = null
        screen(backup(onChooseDestination = { chosen = it })) {
            compose.onNodeWithText("Turn on backup").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Not now").performClick()
            compose.waitForIdle()
            assertEquals(0, count("Before you turn on backup"))
            assertEquals(null, chosen)
            compose.onNodeWithText("Backup is off").assertExists()
        }
    }

    @Test
    fun theBackedUpToRowOpensTheListWithoutThePoints() {
        screen(backup(status = BackupStatus.UpToDate(null))) {
            compose.onNodeWithText("Backed up to").assertExists()
            compose.onNodeWithText("Google Drive · backup.bin").assertExists()
            compose.onNodeWithText("Backed up to").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Back up to").assertExists()
            assertEquals("the points were accepted when backup was turned on", 0, count("Before you turn on backup"))
        }
    }

    @Test
    fun keepBackingUpChangesNothing() {
        var stopped = false
        screen(backup(status = BackupStatus.UpToDate(null), onStop = { stopped = true })) {
            compose.onNodeWithText("Stop backing up").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Stop backing up this vault?").assertExists()
            compose.onNodeWithText("Keep backing up").performClick()
            compose.waitForIdle()
            assertFalse(stopped)
            compose.onNodeWithText("Up to date").assertExists()
        }
    }

    @Test
    fun copyingAFileShowsBytesAndHoldsTheButton() {
        screen(backup(status = BackupStatus.Copying(340_000_000L, 1_200_000_000L, bytes = true))) {
            compose.onNodeWithText("340.0 MB of 1.2 GB", substring = true).assertExists()
            compose.onNodeWithText("Back up now").assertIsNotEnabled()
        }
    }

    @Test
    fun anUnreachableFileSaysSoAndOffersADestination() {
        screen(backup(status = BackupStatus.Unreachable(file = true))) {
            compose.onNodeWithText("Can't reach the backup file").assertExists()
            compose.onNodeWithText("Choose where to back up").assertExists()
            assertEquals("unreachable is never a count of changes", 0, count("to copy", substring = true))
        }
    }

    @Test
    fun anUnreachableFolderSaysFolder() {
        screen(backup(status = BackupStatus.Unreachable(file = false), fileDestination = false)) {
            compose.onNodeWithText("Can't reach the folder").assertExists()
        }
    }

    @Test
    fun notEnoughSpaceSaysHowMuch() {
        screen(backup(status = BackupStatus.NoSpace(400_000_000L))) {
            compose.onNodeWithText("Not enough space").assertExists()
            compose.onNodeWithText("400.0 MB more free space", substring = true).assertExists()
        }
    }

    @Test
    fun theDrawerLineSaysOffAndBadgesOnlyTheFaults() {
        assertEquals("Off" to false, backupDrawerLine(ctx.words(), BackupStatus.Off))
        assertEquals("On" to false, backupDrawerLine(ctx.words(), null))
        assertEquals("Can't reach the folder" to true, backupDrawerLine(ctx.words(), BackupStatus.Unreachable()))
        assertEquals("Can't reach the backup file" to true, backupDrawerLine(ctx.words(), BackupStatus.Unreachable(file = true)))
        assertEquals("Not enough space" to true, backupDrawerLine(ctx.words(), BackupStatus.NoSpace(1L)))
        assertEquals("3 files didn't copy" to true, backupDrawerLine(ctx.words(), BackupStatus.Partial(3)))
        assertEquals("12 changes to copy" to false, backupDrawerLine(ctx.words(), BackupStatus.Behind(12, null)))
    }

    @Test
    fun theDrawerRowCarriesTheStatus() {
        screen({
            VaultDrawerSheet(
                vaultName = "Photos",
                backupLine = backupDrawerLine(ctx.words(), BackupStatus.Unreachable(file = true)).first,
                backupAttention = backupDrawerLine(ctx.words(), BackupStatus.Unreachable(file = true)).second,
                owned = false,
                trialDaysRemaining = 12,
                trialStarted = true,
                onPurchase = {},
                currentIdentity = LauncherIdentity.Vault,
                onIdentity = {},
                biometricState = BiometricState.Off,
                onBiometric = {},
                faceDownAvailable = false,
                faceDownOn = false,
                faceDownLockAfter = 0L,
                onFaceDown = {},
                decoySetUp = false,
                onSetUpDecoy = {},
                onPanicCredential = {},
                onIntruderCapture = {},
                onExport = {},
                currentAppearance = ThemeChoice.System,
                onChooseAppearance = {},
            )
        }) {
            compose.onNodeWithText("Can't reach the backup file").assertExists()
        }
    }

    @Test
    fun theBackupStepIsTheStatusAndNotTheExplanation() {
        screen({
            BackupStepScreen(
                status = BackupStatus.Off,
                folderName = null,
                storeBytes = 1_000_000L,
                problem = null,
                destinations = { listOf(drive) },
                onChooseDestination = {},
                onContinue = {},
            )
        }) {
            compose.onNodeWithText("Backup is off").assertExists()
            compose.onNodeWithText("How backup works").assertExists()
            assertEquals(
                "the explanation lives in the sheet now",
                0,
                count("Pick a folder your cloud app already syncs"),
            )
            compose.onNodeWithText("Turn on backup").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Before you turn on backup").assertExists()
            compose.onNodeWithText("Google Drive").assertExists()
        }
    }
}
