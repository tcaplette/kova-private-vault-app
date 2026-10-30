package com.app.vault

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.NewPassphrase
import com.app.vault.ui.NewPassphraseStep
import com.app.vault.ui.NewVaultStep
import com.app.vault.ui.OpensStep
import com.app.vault.ui.StepFrame
import com.app.vault.ui.VaultFlowBody
import com.app.vault.ui.theme.VaultTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * New vault and Add a passphrase as stepped flows — `new-vault-setup-flow`.
 *
 * Two kinds of case. The **hosted** ones compose the steps in a throwaway activity, holding their
 * state outside them as `MainActivity` does, and touch no storage. The **real-activity** ones walk
 * the flows on `MainActivity` from the vault list, so they make a keyring at `filesDir/vault.slots`
 * and remove it after — gated, as every suite that names the install's own files is:
 *
 *   -Pandroid.testInstrumentationRunnerArguments.destructive=true
 *
 * and skipped where a vault already exists. Emulator only.
 *
 * Test-only state: the keyrings named above, removed after each case; the CAMERA or RECORD_AUDIO
 * grant a case makes before launch, which the orchestrator's `clearPackageData` drops before the
 * next case. No grant is revoked here: revoking kills the instrumented process.
 */
@RunWith(AndroidJUnit4::class)
class VaultSetupFlowDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")
    private var created = false
    private var sandbox: File? = null

    private companion object {
        const val TAG = "VaultSetupFlow"
        const val GOOD = "correct horse battery staple"
        const val WRONG = "a different passphrase entirely"
        const val OWN = "lantern quarry obsidian meadow"
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    @After fun tearDown() {
        lockKeyringAndVault()
        MetadataDb.awaitQuiescent()
        sandbox?.deleteRecursively()
        if (!created) return
        Capture.resetForTest(ctx)
        CapturePermissions.resetForTest(ctx)
        slots.delete()
        blobsDir.deleteRecursively()
        incoming.deleteRecursively()
    }

    // --- harness ---------------------------------------------------------------------------------

    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 45_000, condition = condition)
        } catch (e: Throwable) {
            runCatching {
                compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { i ->
                    compose.onAllNodes(isRoot())[i].printToLog(TAG)
                }
            }
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun text(label: String) =
        compose.onAllNodes(hasText(label, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun button(label: String) = compose.onNode(hasText(label) and hasClickAction() and isEnabled())

    /** A keyring with onboarding's "My vault", locked, at the install's own paths — the gate first. */
    private fun realKeyring(): VaultLifecycle {
        assumeTrue(
            "gated: pass -Pandroid.testInstrumentationRunnerArguments.destructive=true to run it.",
            InstrumentationRegistry.getArguments().getString("destructive") == "true",
        )
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        Capture.resetForTest(ctx)
        CapturePermissions.resetForTest(ctx)
        val lifecycle = VaultLifecycle(slots, BlobStore(blobsDir, incoming))
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        assertTrue(lifecycle.create(pass(GOOD)).opened)
        created = true
        return lifecycle
    }

    /** From the lock screen to the vault list. */
    private fun unlock() {
        await("the lock screen") { text("Welcome back") }
        val field = compose.onNode(hasText("Passphrase") and hasSetTextAction())
        field.performClick()
        field.performTextInput(GOOD)
        await("the passphrase to register") {
            compose.onAllNodes(hasText("Unlock") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Unlock") and hasClickAction()).performClick()
        await("the vault list") { compose.onAllNodes(hasContentDescription("New vault")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun startNewVault() {
        compose.onNode(hasContentDescription("New vault") and hasClickAction()).performClick()
        await("Name and look") { text("Name your vault") }
    }

    private fun type(label: String, value: String) {
        val field = compose.onNode(hasText(label) and hasSetTextAction())
        field.performClick()
        field.performTextReplacement(value)
    }

    /** Name and look, then How it opens with its own passphrase, then its passphrase twice. */
    private fun walkToCapture(name: String) {
        startNewVault()
        type("Name", name)
        button("Continue").performClick()
        await("How it opens") { text("How should $name open?") }
        compose.onNode(hasText("With its own passphrase") and hasClickAction()).performClick()
        button("Continue").performClick()
        await("its passphrase") { text("Choose a passphrase for $name") }
        type("New passphrase", OWN)
        type("New passphrase again", OWN)
        button("Continue").performClick()
        await("the capture step") { text("See who tries to open $name") }
    }

    /** The kit's typed acknowledgement, its Continue, then the kit check — `recovery-practice` D5. */
    private fun putTheKitAway() {
        val words = KitCheckWalk.wordsOnScreen(compose)
        compose.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextInput("I understand")
        button("Continue").performScrollTo().performClick()
        KitCheckWalk.pass(compose, words)
    }

    private fun enrolmentFor(name: String): Set<Capture.Mode>? {
        val entry = VaultKeyringSession.vaults().single { it.name == name }
        return CaptureStore.enrolment(keyringCaptureStore(BlobStore(blobsDir, incoming)), entry.indexBlobId)?.modes
    }

    /**
     * Watches for Android's permission dialog and answers it as refused without raising it,
     * recording which permissions were asked for. A blocking monitor sees the request and the
     * system never draws the dialog.
     */
    private inner class PermissionWatch : Instrumentation.ActivityMonitor() {
        val asked = mutableListOf<List<String>>()

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != "android.content.pm.action.REQUEST_PERMISSIONS") return null
            val names = intent.getStringArrayExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES").orEmpty()
            synchronized(asked) { asked += names.toList() }
            val refused = Intent()
                .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES", names)
                .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS", IntArray(names.size) { PackageManager.PERMISSION_DENIED })
            return Instrumentation.ActivityResult(Activity.RESULT_OK, refused)
        }
    }

    private fun <T> watchingPermissions(block: (PermissionWatch) -> T): T {
        val watch = PermissionWatch()
        instr.addMonitor(watch)
        return try {
            block(watch)
        } finally {
            instr.removeMonitor(watch)
        }
    }

    private fun grant(permission: String) = instr.uiAutomation.grantRuntimePermission(ctx.packageName, permission)

    private fun host(content: @Composable () -> Unit, test: () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { VaultTheme(ThemeChoice.Dark) { content() } } }
            compose.waitForIdle()
            test()
        }
    }

    // --- hosted ------------------------------------------------------------------------------------

    /** How it opens and its passphrase, in the frame, their state held outside as the activity holds it. */
    private var step by mutableStateOf(NewVaultStep.Opens)
    private var own by mutableStateOf(false)

    @Composable
    private fun HostedFlow(draft: NewPassphrase) {
        val steps = NewVaultStep.shown(own)
        StepFrame(
            position = steps.indexOf(step) + 1,
            total = steps.size,
            onBack = { step = steps[steps.indexOf(step) - 1] },
            onCancel = {},
            cancelDescription = "Cancel",
        ) {
            VaultFlowBody(step, reducedMotion = false) { shown ->
                when (shown) {
                    NewVaultStep.Opens -> OpensStep("Receipts", own, { own = it }, busy = false, problem = null) {
                        step = NewVaultStep.Passphrase
                    }
                    else -> NewPassphraseStep("Receipts", draft) {}
                }
            }
        }
    }

    @Test fun theOwnPassphraseRowTurnsTheCountToFiveInTheSameFrame() {
        host({ HostedFlow(NewPassphrase("Receipts")) }) {
            assertTrue(text("Step 2 of 2"))
            assertTrue("the list row reads Create vault", text("Create vault"))
            compose.onNode(hasText("With its own passphrase") and hasClickAction()).performClick()
            assertTrue(text("Step 2 of 5"))
            assertFalse(text("Step 2 of 2"))
            assertTrue("and the action reads Continue", compose.onAllNodes(hasText("Continue") and hasClickAction()).fetchSemanticsNodes().isNotEmpty())
            assertTrue("the title names the vault", text("How should Receipts open?"))
        }
    }

    @Test fun backKeepsEveryEntryIncludingTheMatchLine() {
        own = true
        val draft = NewPassphrase("Receipts")
        host({ HostedFlow(draft) }) {
            button("Continue").performClick()
            await("its passphrase") { text("Choose a passphrase for Receipts") }
            type("New passphrase", OWN)
            type("New passphrase again", OWN)
            assertTrue(text("Both entries match"))
            button("Back").performClick()
            await("How it opens") { text("How should Receipts open?") }
            assertTrue("the choice is kept", text("Step 2 of 5"))
            button("Continue").performClick()
            await("its passphrase again") { text("Choose a passphrase for Receipts") }
            assertTrue(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2)
            assertEquals(OWN, draft.new)
            assertEquals(OWN, draft.again)
            assertTrue("the match line still says they match", text("Both entries match"))
        }
    }

    // --- the Add commit's cost, in a sandbox ------------------------------------------------------

    @Test fun theAddCommitIsTimed() {
        lockKeyringAndVault()
        val root = File(ctx.filesDir, "setup-flow-${System.nanoTime()}").apply { mkdirs() }
        sandbox = root
        val store = BlobStore(File(root, "blobs"), File(root, "incoming"))
        val lifecycle = VaultLifecycle(File(root, "vault.slots"), store)
        assertTrue(lifecycle.create(pass(GOOD)).opened)
        val started = SystemClock.elapsedRealtime()
        val added = lifecycle.addVaultPassphrase(0, pass(GOOD), pass(OWN), setOf(Capture.Mode.AUDIO))
        val took = SystemClock.elapsedRealtime() - started
        added.kitKey?.fill(0)
        Log.i(TAG, "add passphrase commit (two derivations and the enrolment): $took ms")
        assertEquals(VaultLifecycle.PassphraseAct.DONE, added.act)
        assertTrue("the enrolment landed", added.enrolled)
        val modes = CaptureStore.enrolment(keyringCaptureStore(store), VaultKeyringSession.vaults().single().indexBlobId)?.modes
        assertEquals(setOf(Capture.Mode.AUDIO), modes)
    }

    // --- the real activity, gated ------------------------------------------------------------------

    @Test fun aListOpenedVaultIsTwoStepsWithNoCaptureStep() {
        realKeyring()
        lockKeyringAndVault()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            startNewVault()
            assertTrue(text("Step 1 of 2"))
            type("Name", "Receipts")
            button("Continue").performClick()
            await("How it opens") { text("How should Receipts open?") }
            assertTrue(text("Step 2 of 2"))
            button("Create vault").performClick()
            await("the list") { compose.onAllNodes(hasText("Receipts") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            assertFalse("no capture step was shown", text("See who tries to open"))
            assertFalse("no kit", text("Recovery kit for"))
            val entry = VaultKeyringSession.vaults().single { it.name == "Receipts" }
            assertFalse("it opens on tap", entry.hasOwnPassphrase)
            assertNull("no enrolment", enrolmentFor("Receipts"))
        }
    }

    @Test fun aProtectedVaultWithPhotoOnIsEnrolledForPhotoAloneAndNothingExistsBeforeTheCommit() {
        realKeyring()
        lockKeyringAndVault()
        grant(android.Manifest.permission.CAMERA)
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            watchingPermissions { watch ->
                walkToCapture("Receipts")
                assertTrue(text("Step 4 of 5"))
                assertEquals("nothing is in the keyring before the commit", 1, VaultKeyringSession.vaults().size)

                compose.onAllNodes(isToggleable())[0].performClick() // photo, already granted
                compose.waitForIdle()
                assertTrue("a held permission raises no dialog", watch.asked.isEmpty())
                button("Create vault").performClick()

                await("the kit") { text("Recovery kit for Receipts") }
                assertTrue(text("Step 5 of 5"))
                assertFalse("no Back on the kit", compose.onAllNodes(hasText("Back") and hasClickAction()).fetchSemanticsNodes().isNotEmpty())
                assertFalse("no Cancel on the kit", compose.onAllNodes(hasText("Cancel") and hasClickAction()).fetchSemanticsNodes().isNotEmpty())
                instr.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
                compose.waitForIdle()
                assertTrue("back does nothing on the kit", text("Recovery kit for Receipts"))

                assertEquals(setOf(Capture.Mode.PHOTO), enrolmentFor("Receipts"))
                Capture.Mode.entries.forEach { assertFalse("the home modes are unchanged", Capture.enabled(ctx, it)) }

                putTheKitAway()
                await("the list, the vault marked locked") {
                    compose.onAllNodes(hasContentDescription("Receipts, Locked — has its own passphrase")).fetchSemanticsNodes().isNotEmpty()
                }
            }
        }
    }

    @Test fun turningAudioOnRequestsRecordAudioAlone() {
        realKeyring()
        lockKeyringAndVault()
        // CAMERA held and RECORD_AUDIO not: each case starts with no grant (clearPackageData).
        grant(android.Manifest.permission.CAMERA)
        assumeTrue(ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            watchingPermissions { watch ->
                walkToCapture("Receipts")
                compose.onAllNodes(isToggleable())[1].performClick() // audio
                await("the request") { synchronized(watch.asked) { watch.asked.isNotEmpty() } }
                assertEquals(listOf(listOf(android.Manifest.permission.RECORD_AUDIO)), watch.asked)
                await("the refusal said") { text("That mode needs the permission it just asked for. It is off.") }
                assertTrue("a refused mode stays off", compose.onAllNodes(isOn()).fetchSemanticsNodes().isEmpty())
            }
        }
    }

    @Test fun everyModeOffEnrolsNothingAndRequestsNothing() {
        realKeyring()
        lockKeyringAndVault()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            watchingPermissions { watch ->
                walkToCapture("Receipts")
                button("Create vault").performClick()
                await("the kit") { text("Recovery kit for Receipts") }
                assertTrue("no permission was requested", watch.asked.isEmpty())
                assertNull("no enrolment", enrolmentFor("Receipts"))
                assertTrue(VaultKeyringSession.vaults().single { it.name == "Receipts" }.hasOwnPassphrase)
            }
        }
    }

    @Test fun aFullListIsRefusedOnStepOne() {
        realKeyring()
        val store = BlobStore(blobsDir, incoming)
        while (!VaultKeyringSession.isFull()) VaultKeyringSession.createVault(store, "Filler ${VaultKeyringSession.vaults().size}")
        lockKeyringAndVault()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            startNewVault()
            type("Name", "Receipts")
            button("Continue").performClick()
            await("the refusal") { text("This list is full. Remove a vault to make room.") }
            assertTrue("the flow stays on step 1", text("Name your vault"))
            assertTrue(text("Step 1 of 2"))
        }
    }

    @Test fun cancelClosesAtOnceUntilSomethingIsTypedThenAsks() {
        realKeyring()
        lockKeyringAndVault()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            startNewVault()
            button("Cancel").performClick()
            await("the list") { !text("Name your vault") }
            assertFalse("no dialog for an untouched flow", text("Discard this vault?"))

            walkToCapture("Receipts")
            button("Back").performClick()
            await("its passphrase") { text("Choose a passphrase for Receipts") }
            button("Cancel").performClick()
            await("the dialog") { text("Discard this vault?") }
            assertTrue(text("Nothing has been created yet."))
            button("Keep editing").performClick()
            await("the step, kept") { !text("Discard this vault?") }
            assertTrue(text("Both entries match"))

            button("Cancel").performClick()
            await("the dialog") { text("Discard this vault?") }
            button("Discard").performClick()
            await("the list") { !text("Choose a passphrase for") }
            assertEquals("Discard creates nothing", listOf(VaultKeyringSession.MIGRATED_NAME), VaultKeyringSession.vaults().map { it.name })
            assertTrue(keyringCaptureStore(BlobStore(blobsDir, incoming)).vaultCaptures.isEmpty())
        }
    }

    @Test fun addAPassphraseWithAudioOnEnrolsAudioAlone() {
        realKeyring()
        lockKeyringAndVault()
        grant(android.Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            compose.openEditVault(VaultKeyringSession.MIGRATED_NAME)
            compose.onNode(hasText("Add a passphrase") and hasClickAction()).performScrollTo().performClick()
            await("the Add flow") { text("Step 1 of 3") }

            type("Vault list passphrase", WRONG)
            type("New passphrase", OWN)
            type("New passphrase again", OWN)
            button("Continue").performClick()
            await("the wrong list passphrase said") { text("That passphrase doesn't open this list.") }
            assertTrue("the flow stays on step 1", text("Step 1 of 3"))

            type("Vault list passphrase", GOOD)
            button("Continue").performClick()
            await("the capture step") { text("See who tries to open ${VaultKeyringSession.MIGRATED_NAME}") }
            assertTrue(text("Step 2 of 3"))
            assertFalse("nothing is re-sealed before the capture step's action", VaultKeyringSession.vaults().single().hasOwnPassphrase)

            watchingPermissions { watch ->
                compose.onAllNodes(isToggleable())[1].performClick() // audio, already granted
                compose.waitForIdle()
                assertTrue(watch.asked.isEmpty())
            }
            button("Add passphrase").performClick()
            await("the kit") { text("Recovery kit for ${VaultKeyringSession.MIGRATED_NAME}") }
            assertTrue(text("Step 3 of 3"))
            assertEquals(setOf(Capture.Mode.AUDIO), enrolmentFor(VaultKeyringSession.MIGRATED_NAME))
            assertTrue(VaultKeyringSession.vaults().single().hasOwnPassphrase)

            putTheKitAway()
            await("Edit vault, with its status") { text("Passphrase added.") }
        }
    }
}
