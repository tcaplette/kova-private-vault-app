package com.app.vault

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.AddPassphraseStep
import com.app.vault.ui.DiscardFlowDialog
import com.app.vault.ui.NewPassphrase
import com.app.vault.ui.StepFrame
import com.app.vault.ui.ChangeVaultPassphraseScreen
import com.app.vault.ui.EditVaultScreen
import com.app.vault.ui.PassphraseAct
import com.app.vault.ui.PassphraseActState
import com.app.vault.ui.RemoveVaultPassphraseScreen
import com.app.vault.ui.theme.VaultTheme
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A vault's own passphrase added, changed and removed from Edit vault — `vault-passphrase-manage`.
 *
 * The acts run against a keyring in a directory of the test's own, through the same
 * [VaultLifecycle] functions the activity calls. The screens are hosted composables, except the
 * one case that walks Add on the real activity from the list to the kit and back — that one is
 * gated, as every suite that makes a keyring at `filesDir/vault.slots` is:
 *
 *   -Pandroid.testInstrumentationRunnerArguments.destructive=true
 *
 * Test-only state: keyrings under a temporary directory, removed after each case; for the gated
 * case, a keyring at `filesDir/vault.slots` with "My vault" given the passphrase [OWN], removed after.
 */
@RunWith(AndroidJUnit4::class)
class VaultPassphraseManageDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle
    private lateinit var mnemonic: Mnemonic

    private companion object {
        const val TAG = "VaultPassphraseManage"
        const val GOOD = "correct horse battery staple"
        const val WRONG = "a different passphrase entirely"
        const val PANIC = "a panic phrase far from it"
        const val OWN = "lantern quarry obsidian meadow"
        const val NEW = "harbour violet cinder almanac"
        const val AGAIN = "pebble saffron gondola thicket"
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    @Before fun setUp() {
        lockKeyringAndVault()
        root = File(ctx.filesDir, "passphrase-manage-${System.nanoTime()}").apply { mkdirs() }
        store = BlobStore(File(root, "blobs"), File(root, "incoming"))
        vault = VaultLifecycle(File(root, "vault.slots"), store)
        mnemonic = Mnemonic.fromResources(ctx)
    }

    @After fun tearDown() {
        lockKeyringAndVault()
        MetadataDb.awaitQuiescent()
        root.deleteRecursively()
    }

    private fun entry(position: Int) = VaultKeyringSession.vaults().single { it.position == position }

    private fun keyringFile() = store.fileOf(VaultKeyringSession.keyringBlobId()!!).readBytes()

    private fun kek(position: Int, passphrase: String) =
        VaultKeyringSession.entryPassphraseKek(pass(passphrase), position, entry(position).kdfSet)!!

    /** Opens the protected vault at [position] with [passphrase]; false for a wrong one. Leaves it after. */
    private fun opensWith(position: Int, passphrase: String): Boolean {
        val k = kek(position, passphrase)
        return try {
            VaultKeyringSession.openVaultWithPassphrase(position, k) != null
        } finally {
            k.fill(0)
            VaultKeyringSession.leaveVault()
        }
    }

    /** The VMK at [position], under its own [passphrase]. The caller zeroes it. */
    private fun vmkWith(position: Int, passphrase: String): ByteArray {
        val k = kek(position, passphrase)
        return try {
            VaultKeyringSession.vaultKeyForKit(position, k)!!
        } finally {
            k.fill(0)
        }
    }

    /** "My vault", the vault onboarding makes, with two items in it. */
    private fun onboardingVaultWithItems(): Pair<List<WrittenItem>, List<ByteArray>> {
        assertTrue(vault.create(pass(GOOD)).opened)
        assertTrue(vault.openVaultAt(0))
        val payloads = listOf(ByteArray(3_000) { 7 }, ByteArray(5_000) { (it % 251).toByte() })
        val written = putItems(store, payloads)
        VaultKeyringSession.leaveVault()
        assertFalse("onboarding's vault has no passphrase of its own", entry(0).hasOwnPassphrase)
        return written to payloads
    }

    private fun protectedVault(name: String = "Papers"): Int =
        VaultKeyringSession.createVault(store, name) { p, set -> VaultKeyringSession.entryPassphraseKek(pass(OWN), p, set)!! }!!

    // --- Add -------------------------------------------------------------------------------------

    @Test fun addOnTheOnboardingVaultLocksItShowsItsKitAndKeepsItsItems() {
        val (written, payloads) = onboardingVaultWithItems()

        val started = SystemClock.elapsedRealtime()
        val added = vault.addVaultPassphrase(0, pass(GOOD), pass(OWN))
        Log.i(TAG, "Add (prove + seal) took ${SystemClock.elapsedRealtime() - started} ms")
        assertEquals(VaultLifecycle.PassphraseAct.DONE, added.act)
        val kitKey = added.kitKey!!
        try {
            assertEquals("a kit to show", 24, mnemonic.encode(kitKey).size)
            val vmk = vmkWith(0, OWN)
            assertArrayEquals("the kit carries the vault's own key", vmk, kitKey)
            vmk.fill(0)
        } finally {
            kitKey.fill(0)
        }

        val e = entry(0)
        assertTrue("the list marks it locked", e.hasOwnPassphrase)
        assertEquals("a first passphrase starts with three views", 0, e.kitViewsUsed)
        assertEquals(VaultKeys.RELEASED_KDF_PARAMS.lastIndex, e.kdfSet)
        assertNull("a tap no longer opens it", VaultKeyringSession.openVault(0))
        val ring = VaultKeyringSession.withKey { VaultKeyring.load(store, VaultKeyringSession.keyringBlobId()!!, it) }!!
        assertNull("the keyring entry key fails", VaultKeyringSession.withKey { VaultKeyring.unsealVaultKey(ring, it, 0) })

        val k = kek(0, OWN)
        try {
            assertNotNull("the new passphrase opens it", VaultKeyringSession.openVaultWithPassphrase(0, k))
            assertTrue("its items are unchanged", openVaultHolds(store, written, payloads))
        } finally {
            k.fill(0)
            VaultKeyringSession.leaveVault()
        }
    }

    @Test fun aWrongListPassphraseAndThePanicPhraseAreRefusedAndWriteNothing() {
        onboardingVaultWithItems()
        assertEquals(VaultLifecycle.PanicResult.DONE, vault.setPanic(pass(GOOD), pass(PANIC)))
        val before = keyringFile()

        listOf(WRONG, PANIC).forEach {
            val added = vault.addVaultPassphrase(0, pass(it), pass(OWN))
            assertEquals("$it is not the list's passphrase", VaultLifecycle.PassphraseAct.WRONG_PASSPHRASE, added.act)
            assertNull(added.kitKey)
        }
        assertArrayEquals("nothing was written", before, keyringFile())
        assertNotNull("it still opens on tap", VaultKeyringSession.openVault(0))
    }

    @Test fun anAlreadyProtectedVaultIsNotAddedTo() {
        assertTrue(vault.create(pass(GOOD)).opened)
        val p = protectedVault()
        val before = keyringFile()
        assertEquals(VaultLifecycle.PassphraseAct.NOT_SAVED, vault.addVaultPassphrase(p, pass(GOOD), pass(NEW)).act)
        assertArrayEquals(before, keyringFile())
        assertTrue(opensWith(p, OWN))
    }

    // --- Change ----------------------------------------------------------------------------------

    @Test fun changeRefusesTheOldPassphraseAndTheOldKitStillRecovers() {
        assertTrue(vault.create(pass(GOOD)).opened)
        val p = protectedVault()
        assertNotNull(vault.viewVaultKit(mnemonic, p, pass(OWN)).words)
        val before = vmkWith(p, OWN)
        val words = mnemonic.encode(before).joinToString(" ")

        val started = SystemClock.elapsedRealtime()
        assertEquals(VaultLifecycle.PassphraseAct.DONE, vault.changeVaultPassphrase(p, pass(OWN), pass(NEW)))
        Log.i(TAG, "Change (prove + seal) took ${SystemClock.elapsedRealtime() - started} ms")

        assertFalse("the old passphrase no longer opens it", opensWith(p, OWN))
        assertTrue("the new one does", opensWith(p, NEW))
        assertEquals("the kit count did not move", 1, entry(p).kitViewsUsed)
        assertEquals(VaultKeys.RELEASED_KDF_PARAMS.lastIndex, entry(p).kdfSet)

        val after = vmkWith(p, NEW)
        assertArrayEquals("the key did not change", before, after)
        val recovered = mnemonic.decode(words)!!
        assertArrayEquals("the 24 words from before still decode to it", after, recovered)
        assertEquals("and recover it, onto its own entry", p, vault.recoverVault("Papers again", recovered))
        before.fill(0); after.fill(0); recovered.fill(0)
    }

    @Test fun aWrongCurrentPassphraseOnChangeAndOnRemoveWritesNothing() {
        assertTrue(vault.create(pass(GOOD)).opened)
        val p = protectedVault()
        val before = keyringFile()

        assertEquals(VaultLifecycle.PassphraseAct.WRONG_PASSPHRASE, vault.changeVaultPassphrase(p, pass(WRONG), pass(NEW)))
        assertEquals(VaultLifecycle.PassphraseAct.WRONG_PASSPHRASE, vault.removeVaultPassphrase(p, pass(WRONG)))
        assertEquals("the list's passphrase is not the vault's", VaultLifecycle.PassphraseAct.WRONG_PASSPHRASE, vault.removeVaultPassphrase(p, pass(GOOD)))

        assertArrayEquals("nothing was written", before, keyringFile())
        assertTrue("still locked", entry(p).hasOwnPassphrase)
        assertTrue("the old passphrase still opens it", opensWith(p, OWN))
    }

    // --- Remove ----------------------------------------------------------------------------------

    @Test fun removeLeavesAVaultThatOpensOnTap() {
        assertTrue(vault.create(pass(GOOD)).opened)
        val p = protectedVault()
        val before = vmkWith(p, OWN)

        assertEquals(VaultLifecycle.PassphraseAct.DONE, vault.removeVaultPassphrase(p, pass(OWN)))
        assertFalse("the list no longer marks it", entry(p).hasOwnPassphrase)
        val opened = VaultKeyringSession.openVault(p)
        assertNotNull("a tap opens it", opened)
        assertArrayEquals("the same key", before, opened!!.vmk)
        VaultKeyringSession.leaveVault()
        before.fill(0)
    }

    @Test fun theKitCountIsCarriedThroughRemoveAndReAdd() {
        assertTrue(vault.create(pass(GOOD)).opened)
        val p = protectedVault()
        repeat(2) { assertNotNull(vault.viewVaultKit(mnemonic, p, pass(OWN)).words) }
        assertEquals(2, entry(p).kitViewsUsed)

        assertEquals(VaultLifecycle.PassphraseAct.DONE, vault.removeVaultPassphrase(p, pass(OWN)))
        assertEquals("kept while unprotected", 2, entry(p).kitViewsUsed)

        val added = vault.addVaultPassphrase(p, pass(GOOD), pass(AGAIN))
        assertEquals(VaultLifecycle.PassphraseAct.DONE, added.act)
        added.kitKey!!.fill(0)
        assertEquals("not reset", 2, entry(p).kitViewsUsed)
        assertEquals("its drawer row reads 1 view left", 1, VaultKeyring.KIT_VIEWS - entry(p).kitViewsUsed)
    }

    // --- recovery while listed — `protected-vault-destroy-gate` D6 --------------------------------

    @Test fun aProtectedVaultsWordsWhileListedTakeBackItsEntry() {
        assertTrue(vault.create(pass(GOOD)).opened)
        VaultKeyringSession.leaveVault()
        val p = protectedVault()
        val look = entry(p).appearance
        val k = kek(p, OWN)
        assertNotNull(VaultKeyringSession.openVaultWithPassphrase(p, k))
        val payloads = listOf(ByteArray(2_000) { 3 })
        val written = putItems(store, payloads)
        VaultKeyringSession.leaveVault()
        val id = enrolVaultCapture(store, p, k, setOf(Capture.Mode.PHOTO))
        k.fill(0)
        val takenAt = System.currentTimeMillis() - 5_000
        queueVaultCapture(store, id, Capture.Mode.PHOTO, capturePhotoBytes(), takenAt)

        val words = vmkWith(p, OWN)
        val started = SystemClock.elapsedRealtime()
        val position = vault.recoverVault("Letters", words)
        Log.i(TAG, "Recover while listed took ${SystemClock.elapsedRealtime() - started} ms")
        words.fill(0)

        assertEquals("the same position", p, position)
        assertEquals("one entry for it, not two", 1, VaultKeyringSession.vaults().count { it.indexBlobId.contentEquals(id) })
        val e = entry(p)
        assertEquals("renamed to what was typed", "Letters", e.name)
        assertEquals("its appearance kept", look, e.appearance)
        assertFalse("it no longer asks for its own passphrase", e.hasOwnPassphrase)
        assertNotNull("it opens on tap", VaultKeyringSession.openVault(p))
        assertTrue("its items are intact", openVaultHolds(store, written, payloads))
        VaultKeyringSession.leaveVault()
        assertTrue("nothing is queued for it", vaultQueue(store, id).isEmpty())
        assertNull("its enrolment is forgotten", CaptureStore.enrolment(keyringCaptureStore(store), id))
        assertTrue("its capture is on the home timeline", keyringCaptureStore(store).items.isNotEmpty())
    }

    @Test fun anUnprotectedVaultsWordsWhileListedOnlyRenameIt() {
        onboardingVaultWithItems()
        val vmk = VaultKeyringSession.openVault(0)!!.vmk.copyOf()
        VaultKeyringSession.leaveVault()
        val sealed = entry(0).sealedKey.copyOf()

        assertEquals(0, vault.recoverVault("Renamed", vmk))
        vmk.fill(0)

        assertEquals("one entry", 1, VaultKeyringSession.vaults().size)
        assertEquals("Renamed", entry(0).name)
        assertArrayEquals("its key is unchanged", sealed, entry(0).sealedKey)
    }

    // --- the screens -------------------------------------------------------------------------------

    private fun hosted(name: String, own: Boolean) = VaultKeyring.Entry(
        0, name, ByteArray(72), ByteArray(16), hasOwnPassphrase = own, appearance = VaultAppearance.LETTER.pack(),
    )

    private val idle = PassphraseActState(busy = false, wrong = false, problem = null)

    private var addList by mutableStateOf("")
    private var addWrong by mutableStateOf(false)
    private var addBusy by mutableStateOf(false)

    /** Add a passphrase's first step, its typed values held outside it as `MainActivity` holds them. */
    @androidx.compose.runtime.Composable
    private fun AddStep(vault: VaultKeyring.Entry, draft: NewPassphrase, onProve: () -> Unit) = AddPassphraseStep(
        vault = vault,
        list = addList,
        onList = { addList = it; addWrong = false },
        wrong = addWrong,
        draft = draft,
        busy = addBusy,
        onProve = onProve,
    )

    @Test fun weakNameBasedAndMismatchedPassphrasesAreRefusedBeforeAnyDerivation() {
        // Random enough to clear the floor on its own, and nothing once it is the vault's name.
        val name = "kx7Qm2vLp9Rt4Wz"
        assertTrue(PassphraseStrength.clears(name, PassphraseStrength.APP_WORDS))
        var calls = 0
        val draft = NewPassphrase(name)
        compose.setContent {
            VaultTheme(ThemeChoice.Dark) { AddStep(hosted(name, own = false), draft) { calls++ } }
        }
        val list = compose.onNode(hasText("Vault list passphrase") and hasSetTextAction())
        val new = compose.onNode(hasText("New passphrase") and hasSetTextAction())
        val again = compose.onNode(hasText("New passphrase again") and hasSetTextAction())
        val proceed = compose.onNode(hasText("Continue") and hasClickAction())

        proceed.performClick()
        compose.onNodeWithText("Enter the passphrase that opens your vault list.").assertIsDisplayed()
        list.performTextInput(GOOD)

        new.performTextInput("too short")
        again.performTextInput("too short")
        proceed.performClick()
        compose.onNodeWithText("That passphrase is too easy to guess. Add another word or two.").assertIsDisplayed()

        new.performTextReplacement(name)
        again.performTextReplacement(name)
        proceed.performClick()
        compose.onNodeWithText("That passphrase is too easy to guess. Add another word or two.").assertIsDisplayed()

        new.performTextReplacement(OWN)
        again.performTextReplacement(NEW)
        proceed.performClick()
        compose.onNodeWithText("The two entries do not match.").assertIsDisplayed()

        assertEquals("no derivation was asked for", 0, calls)

        again.performTextReplacement(OWN)
        compose.onNodeWithText("Both entries match").assertIsDisplayed()
        proceed.performClick()
        assertEquals(1, calls)
    }

    @Test fun aWrongListPassphraseOnAddIsSaidUnderItsField() {
        val draft = NewPassphrase("Papers").apply { new = OWN; again = OWN }
        compose.setContent {
            VaultTheme(ThemeChoice.Dark) { AddStep(hosted("Papers", own = false), draft) { addBusy = true } }
        }
        compose.onNode(hasText("Vault list passphrase") and hasSetTextAction()).performTextInput(WRONG)
        compose.onNode(hasText("Continue") and hasClickAction()).performClick()
        compose.onNode(hasText("Checking…") and hasClickAction()).assertIsDisplayed()
        addBusy = false
        addWrong = true
        compose.onNode(hasText("Vault list passphrase") and hasText("That passphrase doesn't open this list.")).assertIsDisplayed()
    }

    @Test fun aWrongProvingPassphraseIsSaidUnderItsField() {
        var state by mutableStateOf(idle)
        compose.setContent {
            VaultTheme(ThemeChoice.Dark) {
                RemoveVaultPassphraseScreen(hosted("Papers", own = true), state, onRemove = {}, onBack = {})
            }
        }
        listOf(
            "Your vault list's passphrase opens it",
            "Your list's recovery kit covers it",
            "Its intruder captures move home",
        ).forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNode(hasText("Current passphrase") and hasSetTextAction()).performTextInput(WRONG)
        state = PassphraseActState(busy = true, wrong = false, problem = null)
        compose.onNode(hasText("Removing…") and hasClickAction()).assertIsDisplayed()
        state = PassphraseActState(busy = false, wrong = true, problem = null)
        compose.onNode(hasText("Current passphrase") and hasText("That passphrase doesn't open this vault.")).assertIsDisplayed()
    }

    private var screen by mutableStateOf<PassphraseAct?>(null)
    private var draftName by mutableStateOf("Papers")
    private var draftLook by mutableStateOf(VaultAppearance.LETTER)
    private var status by mutableStateOf<String?>(null)
    private var saved = false

    private var discarding by mutableStateOf(false)

    /**
     * Edit vault, a passphrase screen pushed over it, or the Add flow's first step in its frame — as
     * `MainActivity` holds them. Cancel asks once something is typed, and Discard drops the flow.
     */
    private fun editWithScreens(vault: VaultKeyring.Entry) = compose.setContent {
        VaultTheme(ThemeChoice.Dark) {
            when (screen) {
                PassphraseAct.Change -> ChangeVaultPassphraseScreen(vault, idle, onChange = { _, _ -> }, onBack = { screen = null })
                PassphraseAct.Add -> {
                    val draft = androidx.compose.runtime.remember { NewPassphrase(vault.name) }
                    StepFrame(
                        position = 1,
                        total = 3,
                        onBack = null,
                        onCancel = {
                            if (addList.isEmpty() && draft.new.isEmpty() && draft.again.isEmpty()) screen = null else discarding = true
                        },
                        cancelDescription = "Cancel",
                    ) { AddStep(vault, draft) {} }
                    if (discarding) {
                        DiscardFlowDialog(
                            title = "Discard this passphrase?",
                            body = "${vault.name} still opens with your vault list's passphrase.",
                            onKeep = { discarding = false },
                            onDiscard = {
                                discarding = false
                                addList = ""
                                screen = null
                            },
                        )
                    }
                }
                PassphraseAct.Remove -> RemoveVaultPassphraseScreen(vault, idle, onRemove = {}, onBack = { screen = null })
                null -> EditVaultScreen(
                    vault = vault,
                    name = draftName,
                    appearance = draftLook,
                    onDraft = { n, a -> draftName = n; draftLook = a },
                    onSave = { _, _ -> saved = true },
                    onBack = {},
                    onPassphrase = { screen = it },
                    busy = false,
                    problem = null,
                    status = status,
                )
            }
        }
    }

    @Test fun anUnsavedNameSurvivesTheRoundTrip() {
        editWithScreens(hosted("Papers", own = true))
        compose.onNode(hasSetTextAction()).performTextReplacement("Letters")
        compose.onNode(hasText("Change passphrase") and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText("Current passphrase") and hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNode(hasSetTextAction() and hasText("Letters")).assertIsDisplayed()
        assertFalse("still unsaved", saved)
    }

    @Test fun aDraftSurvivesADiscardedAdd() {
        editWithScreens(hosted("Papers", own = false))
        compose.onNode(hasSetTextAction()).performTextReplacement("Letters")
        compose.onNode(hasText("Add a passphrase") and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText("New passphrase") and hasSetTextAction()).performTextInput(OWN)
        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        compose.onNodeWithText("Discard this passphrase?").assertIsDisplayed()
        compose.onNode(hasText("Discard") and hasClickAction()).performClick()
        compose.onNode(hasSetTextAction() and hasText("Letters")).assertIsDisplayed()
        compose.onNodeWithText("Opens with your vault list's passphrase.").performScrollTo().assertIsDisplayed()
        assertFalse("still unsaved", saved)
    }

    @Test fun theSectionOffersTheActsForItsStateAndAnnouncesTheResult() {
        editWithScreens(hosted("Papers", own = false))
        compose.onNodeWithText("Opens with your vault list's passphrase.").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Add a passphrase") and hasClickAction()).performScrollTo().assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasText("Change passphrase")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasText("Remove passphrase")).fetchSemanticsNodes().isEmpty())

        status = "Passphrase removed."
        val line = compose.onNode(hasText("Passphrase removed.") and SemanticsMatcher("polite") {
            it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite
        })
        line.performScrollTo().assertIsDisplayed()
    }

    @Test fun aProtectedVaultOffersChangeThenRemove() {
        editWithScreens(hosted("Papers", own = true))
        compose.onNodeWithText("Opens only with its own passphrase.").performScrollTo().assertIsDisplayed()
        // Unclipped: the rows sit below the fold until scrolled to.
        val change = compose.onNode(hasText("Change passphrase") and hasClickAction()).fetchSemanticsNode()
        val remove = compose.onNode(hasText("Remove passphrase") and hasClickAction()).fetchSemanticsNode()
        assertTrue("Change, then Remove", change.positionInRoot.y < remove.positionInRoot.y)
        val target = with(compose.density) { 48.dp.toPx() }
        assertTrue("each row is a 48dp target", change.size.height >= target && remove.size.height >= target)
        assertTrue(compose.onAllNodes(hasText("Add a passphrase")).fetchSemanticsNodes().isEmpty())
        listOf("Passphrase added.", "Passphrase changed.").forEach {
            status = it
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun theKitAtAReAddSaysHowManyViewsAreReallyLeft() {
        var left by mutableStateOf(1)
        compose.setContent {
            VaultTheme(ThemeChoice.Dark) {
                com.app.vault.ui.RecoveryKitScreen(
                    words = List(24) { "word" }, onPrint = {}, onContinue = {}, vaultName = "Papers", createdViewsLeft = left,
                )
            }
        }
        compose.onNodeWithText("you can view them 1 more time from the menu", substring = true).assertIsDisplayed()
        left = 0
        compose.onNodeWithText("they can't be shown again", substring = true).assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasText("more time", substring = true)).fetchSemanticsNodes().isEmpty())
        left = VaultKeyring.KIT_VIEWS
        compose.onNodeWithText("you can view them 3 more times from the menu", substring = true).assertIsDisplayed()
    }

    // --- the real activity, gated ----------------------------------------------------------------

    @Test fun addFromEditVaultShowsTheKitThenTheStatusAndTheListMarksItLocked() {
        assumeTrue(
            "gated: pass -Pandroid.testInstrumentationRunnerArguments.destructive=true to run it.",
            InstrumentationRegistry.getArguments().getString("destructive") == "true",
        )
        val slots = File(ctx.filesDir, "vault.slots")
        val blobsDir = File(ctx.filesDir, "blobs")
        val incoming = File(ctx.filesDir, "incoming")
        val real = VaultLifecycle(slots, BlobStore(blobsDir, incoming))
        assumeTrue("a vault already exists here; not overwriting it", !real.vaultExists())
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        assertTrue(real.create(pass(GOOD)).opened)
        lockKeyringAndVault()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(45_000) { compose.onAllNodes(hasText("Welcome back")).fetchSemanticsNodes().isNotEmpty() }
                val field = compose.onNode(hasText("Passphrase") and hasSetTextAction())
                field.performClick()
                field.performTextInput(GOOD)
                compose.onNode(hasText("Unlock") and hasClickAction()).performClick()
                compose.openEditVault(VaultKeyringSession.MIGRATED_NAME)

                compose.onNode(hasText("Add a passphrase") and hasClickAction()).performScrollTo().performClick()
                compose.onNode(hasText("Vault list passphrase") and hasSetTextAction()).performTextInput(GOOD)
                compose.onNode(hasText("New passphrase") and hasSetTextAction()).performTextInput(OWN)
                compose.onNode(hasText("New passphrase again") and hasSetTextAction()).performTextInput(OWN)
                compose.onNode(hasText("Continue") and hasClickAction()).performClick()
                compose.waitUntil(15_000) {
                    compose.onAllNodes(hasText("See who tries to open", substring = true)).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(hasText("Add passphrase") and hasClickAction()).performClick()

                compose.waitUntil(30_000) { compose.onAllNodes(hasText("I understand", substring = true)).fetchSemanticsNodes().isNotEmpty() }
                assertTrue("the vault's own kit copy", compose.onAllNodes(hasText("the passphrase you just set", substring = true)).fetchSemanticsNodes().isNotEmpty())
                val ack = compose.onAllNodes(hasSetTextAction())[0]
                ack.performScrollTo().performTextInput("I understand")
                compose.onNode(hasText("Continue") and hasClickAction()).performScrollTo().performClick()

                compose.waitUntil(15_000) { compose.onAllNodes(hasText("Passphrase added.")).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Opens only with its own passphrase.").performScrollTo().assertIsDisplayed()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.waitUntil(15_000) {
                    compose.onAllNodes(hasContentDescription("${VaultKeyringSession.MIGRATED_NAME}, Locked — has its own passphrase"))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals("the creation showing spent no view", 0, VaultKeyringSession.vaults().single().kitViewsUsed)
            }
        } finally {
            lockKeyringAndVault()
            MetadataDb.awaitQuiescent()
            slots.delete()
            blobsDir.deleteRecursively()
            incoming.deleteRecursively()
        }
    }
}
