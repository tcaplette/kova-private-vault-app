package com.app.vault

import android.view.WindowInsets
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P2-6's Verify, on a device — the half `ScreenChromeTest` cannot reach.
 *
 * The JVM test reads the source and proves the *code* is shaped correctly. It
 * cannot prove a window behaves correctly, and every defect P2-6 fixed was a
 * window behaviour: content under the status bar, the keyboard over the passphrase
 * field, back leaving the activity from the viewer. `FLAG_SECURE` returns black to
 * every screenshot, so these are assertions over node bounds and app state rather
 * than pixels.
 *
 * **This test creates a real vault in the app's own `filesDir`,** because the
 * screens under test only exist behind an unlock. It is removed in [tearDown],
 * including when an assertion fails. Anything already there is left alone and the
 * test skips instead — a developer's real vault is not this test's to overwrite.
 */
@RunWith(AndroidJUnit4::class)
class ScreenChromeDeviceTest {

    /**
     * Empty, not `createAndroidComposeRule`: the activity has to start *after* the
     * vault exists, and an activity rule launches before `@Before` runs.
     */
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobs get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")

    private var created = false

    private companion object {
        val PASS = "correct horse battery staple".toByteArray(Charsets.UTF_8)
        const val PASS_TEXT = "correct horse battery staple"
    }

    @Before
    fun setUp() {
        // ***This suite needs the app to open on the unlock screen, and it never said so.***
        // The launcher identity is an install-wide preference that outlives the vault by
        // design, and `LauncherIdentityDeviceTest` and the cover suites move it. A suite
        // that runs after one of those launches `MainActivity` and gets a **cover front
        // end** — a music player, not a passphrase field — and every wait in it times out
        // on "the lock screen". Sixty tests failed that way in one run, across nine
        // suites, none of which had anything to do with launcher identity.
        //
        // Stating the precondition is the fix: this is a device suite, and the shipped
        // state is the one it is entitled to start from.
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        val lifecycle = VaultLifecycle(slots, BlobStore(blobs, incoming))
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        lifecycle.create(PASS.copyOf())
        lockKeyringAndVault()
        created = true
    }

    @After
    fun tearDown() {
        lockKeyringAndVault()
        if (!created) return
        // The three artifacts a vault is: the slot array, the pooled blob store
        // (which also holds the metadata index), and the staging directory the
        // store defaults to beside it. Nothing else in `filesDir` is ours.
        slots.delete()
        blobs.deleteRecursively()
        incoming.deleteRecursively()
    }

    /** The status bar height in pixels, as the platform reports it right now. */
    private fun statusBarTop(scenario: ActivityScenario<MainActivity>): Int {
        var top = 0
        scenario.onActivity { activity ->
            top = activity.window.decorView.rootWindowInsets
                ?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
        }
        return top
    }

    private fun back(scenario: ActivityScenario<MainActivity>) {
        // The predictive-back dispatcher itself — the thing `onBackPressed` was
        // silently no longer connected to. Driving it directly is the point:
        // pressing a button would prove nothing about the gesture.
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** True when the named text is somewhere in the tree. */
    private fun showing(text: String): Boolean =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /**
     * True when the named text is actually **on screen**.
     *
     * Not the same as [showing], and the difference is the whole reason this
     * exists: `ModalNavigationDrawer` keeps its sheet composed while it is closed,
     * parked outside the window. Every label in the drawer — including "Unvault
     * Everything" — is therefore present in the semantics tree at all times. An
     * existence check would report the export action as visible on the grid, which
     * is precisely the thing P2-7 removed, and the test asserting its absence would
     * fail against correct code.
     */
    private fun displayed(text: String): Boolean = try {
        compose.onNodeWithText(text).assertIsDisplayed()
        true
    } catch (e: AssertionError) {
        false
    }

    /**
     * [displayed], for a label that carries its state after a separator.
     *
     * "Purchase  ·  Trial, 30 days left" is one node, and the rule that a row names the value
     * in effect is exactly what makes an exact-match lookup wrong for it.
     */
    private fun displayedContaining(text: String): Boolean = try {
        compose.onNode(hasText(text, substring = true)).assertIsDisplayed()
        true
    } catch (e: AssertionError) {
        false
    }

    /**
     * The grid is up when its header's leading control is present.
     *
     * It used to be "Unvault Everything is on screen". P2-7 moved export into the
     * drawer, so that string stopped being a grid marker — and became the marker for
     * the opposite thing.
     *
     * **It was "Lock vault" until now, and that had stopped existing.** Locking is an
     * act on the keyring, so the leading slot became Home — "All vaults" — when the
     * vault list arrived, and this marker was never moved with it.
     */
    private fun onGrid(): Boolean =
        compose.onAllNodesWithContentDescription("All vaults")
            .fetchSemanticsNodes().isNotEmpty()

    /**
     * Unlock, and wait for the grid to actually arrive.
     *
     * `attemptUnlock` derives the key on its own thread — `waitForIdle` knows
     * nothing about it and returns while the lock screen is still up. Without this
     * wait the next assertion races the KDF, and the thread outlives the test and
     * reads `vault.slots` after teardown has deleted it.
     */
    private fun unlock() {
        // Wait for the lock screen to actually be up before typing. The activity
        // launches, reads the slot file and runs its device checks before the
        // field exists, and typing into a tree that has not settled is how this
        // helper went flaky across tests.
        await("the lock screen") { showing("Welcome back") }
        compose.waitForIdle()

        // Focus first, then type. `performTextInput` on an unfocused field lands
        // nowhere on a cold start, and the failure is silent: the field stays
        // empty, `Unlock` stays disabled because it gates on `passphrase
        // .isNotEmpty()`, the click is swallowed, and the test times out looking
        // like unlock is broken.
        val field = compose.onNode(hasText("Passphrase"))
        field.performClick()
        compose.waitForIdle()
        field.performTextInput(PASS_TEXT)

        // The button's own enablement is the signal that the text arrived, so wait
        // on that rather than on a duration.
        await("the passphrase to register") {
            compose.onAllNodes(hasText("Unlock") and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Unlock").performClick()
        // `vault-keyring`. The passphrase opens a keyring and the app lands on
        // the list; the grid is behind the vault this taps.
        compose.openFirstVaultFromTheList()

        await("the grid") { onGrid() }
        compose.waitForIdle()
    }

    /**
     * `waitUntil` with the semantics tree logged on timeout.
     *
     * A timeout on its own says only that something did not appear; the tree says
     * which screen the app was actually on. That distinction is what turned the
     * first failure of this suite from "unlock is broken" into a named screen
     * that was in the way.
     */
    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 45_000, condition = condition)
        } catch (e: Throwable) {
            // **Every root, and never `onRoot()`.** A sheet, a dialog or a dropdown
            // is a second root, so `onRoot()` throws "expected 1, found 2" — and it
            // throws from inside the failure handler, which replaces the message
            // that says which screen the app was on with one that says nothing.
            // A diagnostic that fails when a diagnostic is needed is worse than none.
            runCatching {
                compose.onAllNodes(isRoot()).fetchSemanticsNodes().forEachIndexed { i, _ ->
                    compose.onAllNodes(isRoot())[i].printToLog("ChromeVerify")
                }
            }
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    /**
     * Open the drawer from the header — P2-7.
     *
     * There is no overflow any more. The header carries three named controls and
     * this is one of them, so the drawer is one tap from the grid rather than two
     * and a guess.
     *
     * **What proves it opened has moved three times.** It was the billing line while
     * this sheet held the settings; `settings-screen-and-fab` moved those to a screen
     * and `folder-system` took the folder list out, leaving one *Settings* row; and
     * `settings-separation` deletes that screen and puts the app settings back here as
     * rows, and it was the panic credential. `vault-only-drawer` takes the app settings
     * out of the drawer opened inside a vault, so what is waited for is a vault row —
     * Unvault Everything, which the spec requires on screen, unscrolled, whenever this
     * drawer opens.
     */
    private fun openDrawer() {
        compose.onNodeWithContentDescription("Trash and settings").performClick()
        await("the drawer") { displayed("Unvault Everything") }
        compose.waitForIdle()
    }

    /** The vault list's own drawer control is on screen — the home screen. */
    private fun onHome(): Boolean =
        compose.onAllNodesWithContentDescription("Menu").fetchSemanticsNodes().isNotEmpty()

    /**
     * Leave the grid for the vault list and open its drawer, where the app's settings are
     * since `vault-only-drawer`. Called from the grid with its drawer closed: the sheet's
     * scrim is over the header, so a tap on Home while it is open lands on the scrim.
     *
     * "New vault" is a glyph with a description, not a label, so the list is recognised by
     * its drawer control's description.
     */
    private fun openHomeDrawer() {
        compose.onNodeWithContentDescription("All vaults").performClick()
        await("the home screen") { onHome() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Menu").performClick()
        await("the home drawer") { displayed("Recover a vault with its kit") }
        compose.waitForIdle()
    }

    /**
     * The app's rows and the colour scheme chooser, none of which the drawer opened inside a
     * vault may draw — `vault-only-drawer`. By substring, because the identity row names its
     * state after a separator. Not the Purchase row: it heads both drawers since
     * `vault-drawer-trial-and-destroy-pinned`.
     */
    private fun assertNoAppRowIsDrawn() {
        listOf(
            "App identity", ctx.getString(BiometricWrap.NAME), "Face-down lock",
            "Set up a decoy", "Decoy", "Panic credential", "Intruder capture", "Photo details", "Contacts",
            "Recover a vault with its kit", "Open-source licences",
        ).forEach { row ->
            assertTrue(
                "inside a vault the drawer must not draw \"$row\" — it is the app's",
                compose.onAllNodes(hasText(row, substring = true)).fetchSemanticsNodes().isEmpty(),
            )
        }
        assertTrue(
            "inside a vault the drawer must not draw the colour scheme chooser",
            compose.onAllNodesWithContentDescription("Appearance").fetchSemanticsNodes().isEmpty(),
        )
    }

    /**
     * The lock screen's own content clears the status bar, and the keyboard does
     * not take the passphrase field with it.
     */
    @Test
    fun theLockScreenClearsTheBarsAndTheKeyboard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()

            val barTop = statusBarTop(scenario)
            assumeTrue("this device reports no status bar", barTop > 0)

            val title = compose.onNodeWithText("Welcome back")
            title.assertIsDisplayed()
            val titleTop = title.fetchSemanticsNode().boundsInWindow.top
            assertTrue(
                "the heading sits under the status bar: ${titleTop}px vs a ${barTop}px bar",
                titleTop >= barTop,
            )

            // Raising the IME is what used to bury this field. `safeDrawing`
            // covers the keyboard as well as the bars, which is the whole reason
            // the wrapper uses it rather than `systemBars`.
            val field = compose.onNode(hasText("Passphrase"))
            field.performTextInput(PASS_TEXT)
            compose.waitForIdle()
            compose.onNodeWithText("Unlock").assertIsDisplayed()
        }
    }

    /** Back closes the drawer and stays on the grid — it does not leave the app. */
    @Test
    fun backClosesTheDrawerAndStaysOnTheGrid() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            unlock()

            // §6.2 as rewritten by P2-7: export is *not* on the grid. It is in the
            // drawer since `settings-separation`, which is where this test finds it.
            assertTrue(
                "export must not be visible on the grid; a permanent export is the " +
                    "accidental tap P2-7 removed",
                !displayed("Unvault Everything"),
            )

            openDrawer()
            // ...and it is one tap away, in the vault's drawer — on screen without scrolling
            // (`vault-only-drawer`).
            compose.onNodeWithText("Unvault Everything").assertIsDisplayed()

            back(scenario)

            await("the drawer to close") { !displayed("Unvault Everything") }
            assertTrue("the grid is still there", onGrid())
            var finishing = false
            scenario.onActivity { finishing = it.isFinishing }
            assertTrue("back from the drawer must not finish the activity", !finishing)
        }
    }

    /**
     * The lock control in the header locks the vault.
     *
     * P2-6 had this two taps deep in an overflow menu. For an action whose whole
     * value is being instant, that was the same as not having it.
     */
    @Test
    fun theHeaderLeadingControlLeavesForTheListWhereLockingLives() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            unlock()

            // **This asserted a lock in the grid's header, and there is not one.**
            // Locking is an act on the keyring, not on the vault you happen to be in,
            // so the leading slot became Home and the lock moved to the screen that
            // lists vaults. The test kept tapping a control that had stopped existing.
            compose.onNodeWithContentDescription("All vaults")
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performClick()

            // The list, where the lock is.
            await("the vault list") { displayed("Vaults") }
            assertTrue("the vault's key is gone on leaving it", !VaultSession.isUnlocked)

            compose.onNodeWithContentDescription("Lock")
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performClick()

            await("the lock screen") { showing("Welcome back") }
            assertTrue("the session key is gone", !VaultSession.isUnlocked)
        }
    }

    /**
     * A drawer row closes the drawer and opens the screen it names — an app setting, so
     * from the home drawer since `vault-only-drawer`.
     */
    @Test
    fun aDrawerRowClosesTheDrawerAndOpensItsScreen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            unlock()
            openHomeDrawer()

            // `settings-separation`. The panic credential is the row picked because it
            // is on every device in every state — no hardware gates it and it reads
            // the same whether or not one is enrolled.
            compose.onNodeWithText("Panic credential").performClick()
            await("the panic screen") { showing("Panic credential") && !onHome() }
            assertTrue("the drawer closed behind it", !displayedContaining("Purchase"))

            // And that screen still has the P2-6 back affordance, at its 48dp size.
            compose.onNodeWithContentDescription("Back")
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)

            back(scenario)
            await("the home screen") { onHome() }
        }
    }

    /**
     * The header's five controls fit, do not overlap, and stay on screen.
     *
     * P2-6's lesson was that a component default I had assumed — a 40dp
     * `IconButton` under §7.1's floor — was only ever going to be caught by
     * measuring a rendered control. This measures the arrangement rather than
     * calculating it.
     *
     * **Five since `settings-separation`.** `ScreenChromeTest` does the arithmetic at
     * 320dp and reports the number — 240dp of controls, 80dp of title — and this is
     * the half that catches a control whose rendered size disagrees with the token it
     * was given. Every one of them still asserts its own 48dp, because the change
     * that spent the header's slack said it would stop rather than shrink one.
     */
    @Test
    fun theHeaderControlsFitWithoutOverlapping() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            unlock()

            var displayWidth = 0f
            scenario.onActivity { displayWidth = it.window.decorView.width.toFloat() }

            // Four actions plus the leading control, which is Home rather than the lock
            // it used to be. In bar order, which is what makes the overlap check below
            // mean anything.
            val bounds = listOf(
                "All vaults", "Add photos or videos", "New folder", "Media settings",
                "Trash and settings",
            ).map { name ->
                name to compose.onNodeWithContentDescription(name)
                    .assertIsDisplayed()
                    .assertWidthIsAtLeast(48.dp)
                    .assertHeightIsAtLeast(48.dp)
                    .fetchSemanticsNode().boundsInWindow
            }

            bounds.forEach { (name, b) ->
                assertTrue(
                    "$name runs off the ${displayWidth}px display: ${b.left}..${b.right}",
                    b.left >= 0f && b.right <= displayWidth,
                )
            }
            bounds.zipWithNext().forEach { (a, c) ->
                assertTrue(
                    "${a.first} and ${c.first} overlap: ${a.second.right} vs ${c.second.left}",
                    a.second.right <= c.second.left + 1f,
                )
            }
        }
    }

    /**
     * Each drawer reads in its own order — `drawer-row-order`, `vault-only-drawer`.
     *
     * **The order is not in the file, so no source test can reach it.** `VaultDrawer.kt`
     * lists its rows in a `Column`, and a reorder that puts recovery back on top compiles,
     * keeps every row, keeps every rule, and passes both membership tests and
     * `theDrawerIsFourGroupsAndNotAList` without a word of complaint. That is exactly the
     * regression this change corrected, and it went unnoticed because nothing measured
     * where a row sits.
     *
     * So this measures it, on both screens, from node bounds. The rows chosen exist on
     * every device: no fingerprint reader, no accelerometer and no camera is assumed —
     * those three are gated on hardware and absent-means-not-drawn is the rule they follow.
     *
     * The first half holds the vault's drawer to the Purchase row over its five rows in order,
     * Destroy Vault pinned at the sheet's bottom, and nothing else of the app's. The second half is the one that would have caught the original defect: the
     * home drawer's first row used to be recovery, the rarest destination in the app.
     */
    @Test
    fun theDrawerReadsInItsOwnOrderOnEachScreen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            unlock()

            // Inside a vault the scroll holds the Purchase row, then the vault's rows — Trash,
            // Backup, Open a share…, Unvault Everything — and Destroy Vault
            // is pinned below it. `vault-only-drawer`, `vault-drawer-trial-and-destroy-pinned`.
            openDrawer()
            // Not scrolled: the export is on screen the moment the drawer opens.
            compose.onNodeWithText("Unvault Everything").assertIsDisplayed()
            val purchase = drawerRowTop("Purchase")
            val trash = drawerRowTop("Trash")
            val backup = drawerRowTop("Backup")
            val share = drawerRowTop("Open a share")
            val export = drawerRowTop("Unvault Everything")
            val destroy = drawerRowTop("Destroy Vault")

            assertOrder("in a vault", "Purchase", purchase, "Trash", trash)
            assertOrder("in a vault", "Trash", trash, "Backup", backup)
            assertOrder("in a vault", "Backup", backup, "Open a share", share)
            assertOrder("in a vault", "Open a share", share, "Unvault Everything", export)
            assertOrder("in a vault", "Unvault Everything", export, "Destroy Vault", destroy)
            assertAtTheSheetsBottom(scenario)
            assertNoAppRowIsDrawn()

            // Home: entitlement, install, recovery, Open-source licences. The vault's rows are
            // absent — each needs an open vault. `third-party-notices` put the licences last.
            //
            // **Close the drawer before leaving** — see [openHomeDrawer].
            back(scenario)
            await("the grid") { onGrid() }
            openHomeDrawer()

            val homePurchase = drawerRowTop("Purchase")
            val homeIdentity = drawerRowTop("App identity")
            val homePanic = drawerRowTop("Panic credential")
            val homeIntruder = drawerRowTop("Intruder capture")
            val recover = drawerRowTop("Recover a vault with its kit")
            val licences = drawerRowTop("Open-source licences")

            assertOrder("at home", "Purchase", homePurchase, "App identity", homeIdentity)
            assertOrder("at home", "App identity", homeIdentity, "Panic credential", homePanic)
            // `intruder-captures-at-home`. An app setting, directly after the panic credential.
            assertOrder("at home", "Panic credential", homePanic, "Intruder capture", homeIntruder)
            assertOrder(
                "at home", "Intruder capture", homeIntruder,
                "Recover a vault with its kit", recover,
            )
            assertOrder(
                "at home", "Recover a vault with its kit", recover,
                "Open-source licences", licences,
            )
            // No vault is open, so none of the vault's rows is drawn.
            listOf(
                "Trash", "Backup", "Open a share…", "Unvault Everything",
                "Destroy Vault",
            ).forEach { row ->
                assertTrue(
                    "with no vault open \"$row\" must not be drawn",
                    compose.onAllNodes(hasText(row)).fetchSemanticsNodes().isEmpty(),
                )
            }
        }
    }

    /**
     * The top edge of a drawer row, by the text it carries.
     *
     * Substring, because the entitlement and identity rows both name their state after
     * a separator — "Purchase  ·  Trial, 30 days left" is one node, and an exact
     * match would find nothing.
     *
     * **Unclipped.** The drawer scrolls, and the home drawer's last rows can sit below the
     * fold on a short screen; a clipped bound would put every
     * row past the fold at the same edge and the order would read as a tie.
     */
    private fun drawerRowTop(text: String): Float =
        compose.onNode(hasText(text, substring = true))
            .fetchSemanticsNode().positionInRoot.y

    /**
     * Destroy Vault's bottom edge is the sheet's last thing: under it is only the sheet's
     * bottom breathing room (`Spacing.section`, 24dp) and, at most, the navigation bar the
     * sheet insets itself from. 4dp of slack for rounding.
     */
    private fun assertAtTheSheetsBottom(scenario: ActivityScenario<MainActivity>) {
        var windowHeight = 0
        var navBar = 0
        scenario.onActivity { activity ->
            windowHeight = activity.window.decorView.height
            navBar = activity.window.decorView.rootWindowInsets
                ?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
        }
        val density = ctx.resources.displayMetrics.density
        val bottom = compose.onNodeWithText("Destroy Vault").fetchSemanticsNode().boundsInWindow.bottom
        val gap = windowHeight - bottom
        assertTrue(
            "Destroy Vault must sit at the bottom of the sheet: ${gap}px under it, against at " +
                "most ${navBar}px of navigation bar and 24dp",
            gap >= 0f && gap <= navBar + 28 * density,
        )
    }

    /** Strictly above, and the failure says which pair and by how much. */
    private fun assertOrder(
        where: String,
        upperLabel: String,
        upper: Float,
        lowerLabel: String,
        lower: Float,
    ) {
        assertTrue(
            "$where the drawer must read \"$upperLabel\" above \"$lowerLabel\", but " +
                "\"$upperLabel\" is at $upper and \"$lowerLabel\" is at $lower",
            upper < lower,
        )
    }

    /**
     * The drawer's rows are not mirrored.
     *
     * The sheet is reached by flipping the host to `Rtl`, so its *content* has to
     * be flipped back to `Ltr`. Apply only the first half and every row reverses —
     * label right, glyph left — and `FLAG_SECURE` guarantees no screenshot would
     * ever show anyone that. So it is measured: a section heading must start at
     * the same edge as the full-width control beneath it, not at the far one.
     */
    @Test
    fun theDrawerContentIsNotMirrored() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            unlock()
            // The home drawer, because it is the one that draws the chooser this measures
            // against — the vault's drawer does not since `vault-only-drawer`. One sheet
            // serves both, so the wrap measured here is the wrap both use.
            openHomeDrawer()

            // The panic credential: it is present on every device and in every state.
            val row = compose.onNodeWithText("Panic credential")
                .fetchSemanticsNode().boundsInWindow
            // A full-width control inside the sheet, so its left edge is the
            // sheet's own content edge. The scheme chooser is that control since
            // `appearance-in-drawer` pinned it to the foot of the sheet.
            val chooser = compose.onNodeWithContentDescription("Appearance")
                .fetchSemanticsNode().boundsInWindow

            assertTrue(
                "the drawer's content is mirrored: the row's label starts at ${row.left} but " +
                    "the sheet's content edge is ${chooser.left}",
                row.left - chooser.left < chooser.width / 2f,
            )
        }
    }

    /**
     * ***The bottom of the stack moved — `vault-keyring`.***
     *
     * It was the grid, because unlocking landed there and there was nothing behind
     * it. A passphrase now opens a **keyring**, the app lands on the list of vaults,
     * and the grid is one level in — so back at the grid returns to the list, which is
     * where the user came from, and back at the *list* is what leaves the app.
     *
     * P2-6's rule is unchanged and is what the second half asserts: at the bottom of
     * the stack the handler is disabled rather than swallowing the gesture.
     */
    @Test
    fun backAtTheGridReturnsToTheListAndBackAgainLeavesTheActivity() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            unlock()

            back(scenario)

            // One level out: the list, still unlocked, with no vault open.
            await("the vault list") {
                compose.onAllNodes(hasText("Vaults")).fetchSemanticsNodes().isNotEmpty()
            }
            var finishingEarly = false
            scenario.onActivity { finishingEarly = it.isFinishing || it.isDestroyed }
            assertFalse(
                "back at the grid must return to the list, not leave the app",
                finishingEarly,
            )

            back(scenario)

            // `finish()` is not instant from the test thread's point of view, and
            // asserting immediately is how this read as a P2-6 regression when it
            // was a race.
            val deadline = System.currentTimeMillis() + 5_000
            var finishing = false
            while (System.currentTimeMillis() < deadline && !finishing) {
                scenario.onActivity { finishing = it.isFinishing || it.isDestroyed }
                if (!finishing) Thread.sleep(100)
            }
            assertTrue(
                "the list is the bottom of the stack: the handler is disabled there and " +
                    "back leaves the activity",
                finishing,
            )
        }
    }

    // --- `vault-only-drawer`: the drawer inside a vault is the vault's ----------

    /**
     * The drawer opened inside a vault holds, under the vault's name, its trash, backup,
     * opening a share and the export, and Destroy Vault pinned below them.
     *
     * The rows are scrolled to rather than looked for: "not on screen" is not "not in the
     * drawer" on a short screen. Destroy Vault is not in the scroll, so it is on screen with
     * no scroll at all.
     */
    @Test
    fun theVaultsDrawerHoldsItsRows() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            unlock()
            openDrawer()

            // The export is on screen without scrolling.
            compose.onNodeWithText("Unvault Everything").assertIsDisplayed()
            listOf(
                "Trash", "Backup", "Open a share", "Unvault Everything",
            ).forEach { row ->
                compose.onNode(hasText(row, substring = true))
                    .performScrollTo()
                    .assertIsDisplayed()
            }
            compose.onNodeWithText("Destroy Vault").assertIsDisplayed()
        }
    }

    /**
     * `intruder-captures-at-home`. The Intruder capture screen is reached from the home drawer and
     * back returns to the vault list it was opened over — `screen-chrome`.
     */
    @Test
    fun backFromTheIntruderCaptureScreenReturnsToTheVaultList() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            unlock()
            openHomeDrawer()
            compose.onNodeWithText("Intruder capture").performClick()
            await("the Intruder capture screen") {
                compose.onAllNodesWithContentDescription("Capture settings").fetchSemanticsNodes().isNotEmpty()
            }
            back(scenario)
            await("the vault list") { onHome() }
            assertTrue(
                "back must leave the Intruder capture screen",
                compose.onAllNodesWithContentDescription("Capture settings").fetchSemanticsNodes().isEmpty(),
            )
        }
    }

    /**
     * The vault's drawer opens with the home drawer's Purchase row — the same words — and it
     * opens the Purchase screen over the vault — `vault-drawer-trial-and-destroy-pinned`.
     */
    @Test
    fun theVaultsDrawerShowsTheTrialAndOpensThePurchase() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            unlock()
            openDrawer()

            val row = compose.onNode(hasText("Purchase", substring = true))
            row.assertIsDisplayed()
            val words = row.fetchSemanticsNode().config[SemanticsProperties.Text]
                .joinToString("") { it.text }
            val state = words.removePrefix(ctx.getString(R.string.drawer_purchase, ""))
            val states = listOf(
                R.string.purchase_state_owned, R.string.purchase_state_pending,
                R.string.purchase_state_not_started, R.string.purchase_state_trial,
                R.string.purchase_state_ended,
            ).map(ctx::getString) + (1..30).map {
                ctx.resources.getQuantityString(R.plurals.purchase_state_trial_left, it, it)
            }
            assertTrue("the vault's Purchase row names the trial's state, but reads \"$words\"", state in states)
            assertTrue(
                "the Purchase row is the vault drawer's first row",
                drawerRowTop("Purchase") < drawerRowTop("Trash"),
            )

            row.performClick()
            await("the Purchase screen") { !onGrid() && showing(ctx.getString(R.string.purchase_title)) }

            back(scenario)
            await("the grid") { onGrid() }
            openHomeDrawer()
            val home = compose.onNode(hasText("Purchase", substring = true)).fetchSemanticsNode()
                .config[SemanticsProperties.Text].joinToString("") { it.text }
            assertEquals("both drawers word the Purchase row the same", words, home)
        }
    }

    /**
     * Destroy Vault is pinned below the scroll: scrolling the vault's rows does not move it,
     * and it is not inside the scrolling container at all — so the first holds on a screen
     * too tall for the rows to scroll, too.
     */
    @Test
    fun scrollingTheVaultsRowsLeavesDestroyVaultWhereItIs() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            unlock()
            openDrawer()

            val rows = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Trash")))
            assertTrue(
                "Destroy Vault must not be inside the scrolling rows",
                compose.onAllNodes(hasScrollAction() and hasAnyDescendant(hasText("Destroy Vault")))
                    .fetchSemanticsNodes().isEmpty(),
            )
            val before = compose.onNodeWithText("Destroy Vault").fetchSemanticsNode().boundsInWindow
            rows.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10_000f) }
            compose.waitForIdle()
            val after = compose.onNodeWithText("Destroy Vault").fetchSemanticsNode().boundsInWindow
            assertEquals("scrolling the vault's rows moved Destroy Vault", before, after)
            compose.onNodeWithText("Destroy Vault").assertIsDisplayed()
        }
    }

    /**
     * The drawer opened inside a vault holds none of the app's rows, no recovery and no
     * colour scheme chooser — those are the home drawer's.
     */
    @Test
    fun theVaultsDrawerHoldsNoneOfTheAppsRows() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            unlock()
            openDrawer()

            assertNoAppRowIsDrawn()
        }
    }

    /** The vault's backup row closes the drawer and opens the screen it names. */
    @Test
    fun theBackupRowClosesTheDrawerAndOpensItsScreen() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            unlock()
            openDrawer()

            compose.onNodeWithText("Backup").performClick()

            // The screen's one primary action, in whichever of its three states the
            // install is in. The predicate used to name "Choose a folder" — which is
            // the *export* screen's string and never appears here — and to omit Off,
            // which is the state a fresh install is actually in, so it waited 45s for
            // text the screen could not draw while the screen itself was up.
            await("the backup screen") {
                showing("Turn on backup") || showing("Choose where to back up") ||
                    showing("Back up now")
            }
            assertFalse("the drawer closed behind it", displayed("Destroy Vault"))

            back(scenario)
            await("the grid") { onGrid() }
        }
    }

    /**
     * There is no settings screen left to reach — `settings-separation`.
     *
     * A drawer row named *Settings* is what routed to it, and its absence is the
     * shortest true statement of this change on a running device: the app settings are
     * here, not one tap further in.
     */
    @Test
    fun noSettingsRowRoutesToAScreenThatHoldsEverything() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            unlock()
            openDrawer()

            assertTrue(
                "the drawer must not hold a row that opens a screen holding the app settings",
                compose.onAllNodes(hasText("Settings")).fetchSemanticsNodes().isEmpty(),
            )
        }
    }

    /**
     * The destroy confirmation is a dialog with two peers, and dismissing it does
     * nothing — `delete-vault` D1.
     *
     * **Dismissal is not a third outcome.** The vault, its contents and its backup are
     * exactly as they were, and the user is left on the surface that raised it — which is
     * the property a dialog has and a screen does not.
     */
    @Test
    fun destroyingAVaultAsksInADialogAndDismissingDoesNothing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            unlock()
            openDrawer()

            compose.onNodeWithText("Destroy Vault").performClick()

            // `protected-vault-destroy-gate`: the dialog proves the passphrase before it offers
            // the choices — this vault has none of its own, so it is the list's.
            await("the destroy gate") { showing("Vault list passphrase") }
            val gate = compose.onNode(hasText("Vault list passphrase"))
            gate.performClick()
            compose.waitForIdle()
            gate.performTextInput(PASS_TEXT)
            compose.onNodeWithText("Continue").performClick()

            await("the destroy confirmation") { showing("Give my files back, then destroy") }
            listOf("Give my files back, then destroy", "Destroy everything in it", "Keep vault")
                .forEach { label ->
                    compose.onNodeWithText(label).assertIsDisplayed()
                }
            assertTrue(
                "the confirmation names the vault it would destroy",
                compose.onAllNodes(hasText("Destroy", substring = true))
                    .fetchSemanticsNodes().isNotEmpty(),
            )

            // Back out of it: nothing happens, and the grid is still underneath.
            back(scenario)

            await("the confirmation to close") { !displayed("Destroy everything in it") }
            assertTrue("the vault is untouched and the grid is still there", onGrid())
            var finishing = false
            scenario.onActivity { finishing = it.isFinishing }
            assertFalse("dismissing a dialog must not finish the activity", finishing)
        }
    }

    /**
     * `per-vault-intruder-capture`. In a vault with its own passphrase the drawer reads Backup,
     * Intruder capture, Open a share…; in a vault without one, Intruder capture is absent.
     */
    @Test
    fun intruderCaptureSitsBetweenBackupAndOpenAShareOnlyInAProtectedVault() {
        val store = BlobStore(blobs, incoming)
        assertTrue(VaultLifecycle(slots, store).unlock(PASS.copyOf()).opened)
        VaultKeyringSession.createVault(store, "Private") { position, set ->
            VaultKeyringSession.entryPassphraseKek("a different staple entirely".toByteArray(Charsets.UTF_8), position, set)!!
        }
        lockKeyringAndVault()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            unlock()
            openDrawer()
            assertTrue(
                "a vault without its own passphrase has no Intruder capture row",
                compose.onAllNodes(hasText("Intruder capture")).fetchSemanticsNodes().isEmpty(),
            )
            assertOrder("in an unprotected vault", "Backup", drawerRowTop("Backup"), "Open a share", drawerRowTop("Open a share"))

            back(scenario)
            await("the grid") { onGrid() }
            compose.onNodeWithContentDescription("All vaults").performClick()
            await("the home screen") { onHome() }
            compose.openProtectedVaultFromTheList("Private", "a different staple entirely")
            await("the grid") { onGrid() }
            openDrawer()
            val backup = drawerRowTop("Backup")
            val intruder = drawerRowTop("Intruder capture")
            val share = drawerRowTop("Open a share")
            assertOrder("in a protected vault", "Backup", backup, "Intruder capture", intruder)
            assertOrder("in a protected vault", "Intruder capture", intruder, "Open a share", share)
        }
    }
}
