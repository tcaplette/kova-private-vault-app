package com.app.vault

import android.app.Activity
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.RecoveryKitScreen
import com.app.vault.ui.theme.VaultTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's language on a device — `app-languages` 5.3.
 *
 * **The real activity, because the claim is the real activity's.** That a choice closes the
 * sheet and re-draws the app without recreating `MainActivity` is a statement about its
 * `configChanges` and its handling of them; a bare host would prove nothing. The vault list — where the Language row is —
 * shows only where a vault exists, so the keyring is [LiveCover]'s: made in the app's storage only
 * on a device with none, which under the orchestrator's `clearPackageData` is every run, and
 * deleted after.
 *
 * **The Calculator identity is worn throughout**, so every case also shows that a change of
 * language does not raise the cover: the activity is not recreated, and its `onCreate` — the
 * only path that raises it for a live session — does not run.
 *
 * **Restores everything it changes**: the app's language (back to what it was, System default
 * on a clean device), the identity, the calculator's trigger, the session and the vault it made.
 */
@RunWith(AndroidJUnit4::class)
class LanguageDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** `sin` is on the calculator's keypad and on no other screen in the app. */
    private val calcTrigger = Trigger.Keys(listOf("7", "3", "sin"))

    private var languageBefore: AppLanguage.Choice? = null
    private lateinit var identityBefore: LauncherIdentity
    private var triggerBefore: Trigger? = null
    private var patternBefore: Trigger.Pattern? = null
    private var holdBefore: Int? = null
    private var keyring: LiveCover.Keyring? = null

    @Before
    fun setUp() {
        DeviceLock.awake()
        languageBefore = AppLanguage.current(ctx)
        identityBefore = GridPreference.identity(ctx)
        triggerBefore = CoverTrigger.triggerFor(ctx, Cover.Calculator)
        patternBefore = CoverTrigger.patternFor(ctx, Cover.Calculator)
        holdBefore = CoverTrigger.holdFor(ctx, Cover.Calculator)
        AppLanguage.set(ctx, null)
        VaultKeyringSession.lock()
        VaultSession.lock()
        CoverTrigger.set(ctx, Cover.Calculator, calcTrigger)
        CoverTrigger.clearAttempts(ctx, Cover.Calculator)
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Calculator)
    }

    @After
    fun restore() {
        AppLanguage.set(ctx, languageBefore)
        VaultKeyringSession.lock()
        VaultSession.lock()
        keyring?.close()
        CoverTrigger.clear(ctx, Cover.Calculator)
        triggerBefore?.let { CoverTrigger.set(ctx, Cover.Calculator, it, dots = patternBefore) }
            ?: patternBefore?.let { CoverTrigger.set(ctx, Cover.Calculator, it, area = holdBefore) }
        CoverTrigger.clearAttempts(ctx, Cover.Calculator)
        LauncherIdentity.switchTo(ctx, identityBefore)
        LauncherIdentity.reconcile(ctx)
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun showing(text: String): Boolean =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun coverIsUp(): Boolean = showing("sin")

    /** Every text and description on screen, for a failure message. */
    private fun onScreen(): List<String> =
        compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes()
            .flatMap { root -> generateSequence(listOf(root)) { l -> l.flatMap { it.children }.ifEmpty { null } }.flatten() }
            .mapNotNull { n ->
                n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString()
                    ?: n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { null }?.joinToString()
            }

    /** Through the cover, out of the vault to the list, then the home drawer's Language row. */
    private fun openTheLanguageSheet() {
        compose.waitForIdle()
        assertTrue("a launch shows the cover", coverIsUp())
        calcTrigger.keys.forEach { compose.onAllNodes(hasText(it))[0].performClick() }
        compose.onAllNodes(hasText("="))[0].performTouchInput { longClick(durationMillis = 2_500) }
        compose.waitForIdle()
        assertFalse("the trigger did not dismiss the cover", coverIsUp())

        // The keyring's vault opens with it; the home drawer is the list's, one step out.
        val allVaults = hasContentDescription(ctx.getString(R.string.all_vaults)) and hasClickAction()
        compose.waitUntil(15_000) { compose.onAllNodes(allVaults).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(allVaults)[0].performClick()
        compose.waitForIdle()
        val menu = hasContentDescription(ctx.getString(R.string.menu)) and hasClickAction()
        runCatching { compose.waitUntil(15_000) { compose.onAllNodes(menu).fetchSemanticsNodes().isNotEmpty() } }
            .onFailure { throw AssertionError("no Menu after the trigger; on screen: ${onScreen()}") }
        compose.onNode(menu).performClick()
        compose.waitForIdle()
        val row = ctx.getString(R.string.drawer_language, ctx.getString(R.string.language_system_default))
        compose.onNode(hasText(row) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    /** A row of the sheet, by its own name — never the System default row whose line repeats it. */
    private fun row(ownName: String, systemDefault: String) =
        compose.onNode(hasText(ownName) and hasClickAction() and !hasText(systemDefault))

    private fun top(text: String) = compose.onNode(hasText(text)).fetchSemanticsNode().boundsInRoot.top

    /** [id] as German resources write it, whatever the app's language is now. */
    private fun german(id: Int, vararg args: Any) =
        AppLanguage.localized(ctx, LocaleList.forLanguageTags("de")).getString(id, *args)

    /** The sheet has gone: its explanation line is on screen in neither language. */
    private fun sheetClosed() =
        !showing(ctx.getString(R.string.language_explainer)) && !showing(german(R.string.language_explainer))

    // --- 5.3 ----------------------------------------------------------------------------------

    /**
     * `language-picker-redesign` 6.1. Suggested — System default, then the device's languages —
     * above All languages, the rest in the platform picker's order; English, the emulator's
     * language, under Suggested and not under All languages; System default selected.
     */
    @Test
    fun theSheetListsTheNineInOrderWithSystemDefaultSelected() {
        keyring = LiveCover.openKeyring()
        val suggested = AppLanguage.suggested(ctx)
        assertTrue("the emulator is not in English: ${AppLanguage.systemLocales(ctx)}", suggested.any { it.tag == "en-US" })
        ActivityScenario.launch(MainActivity::class.java).use {
            openTheLanguageSheet()
            val systemDefault = ctx.getString(R.string.language_system_default)
            compose.waitUntil(10_000) { showing(systemDefault) }

            val tops = listOf(top(ctx.getString(R.string.language_suggested))) +
                compose.onNode(hasText(systemDefault) and hasClickAction()).fetchSemanticsNode().boundsInRoot.top +
                suggested.map { row(it.ownName, systemDefault).fetchSemanticsNode().boundsInRoot.top } +
                top(ctx.getString(R.string.language_all)) +
                AppLanguage.supported.filterNot { it in suggested }
                    .map { row(it.ownName, systemDefault).fetchSemanticsNode().boundsInRoot.top }
            assertEquals("the sheet's rows are out of order: $tops", tops.sorted(), tops)
            assertEquals("a language is listed twice", 1, compose.onAllNodes(hasText("English") and hasClickAction() and !hasText(systemDefault)).fetchSemanticsNodes().size)

            compose.onNode(hasText(systemDefault) and hasClickAction()).assertIsSelected()
            AppLanguage.supported.forEach { row(it.ownName, systemDefault).assertIsNotSelected() }
        }
    }

    /**
     * `language-picker-redesign` 6.2. Choosing German closes the sheet and re-draws the app in
     * German, in the same activity instance, with the keyring still open and the cover still down;
     * the snackbar's Undo is in English, the language before the tap, and restores System default
     * as the platform's empty list, so a later change of the device's language is followed.
     */
    @Test
    fun choosingGermanClosesTheSheetAndUndoRestoresIt() {
        keyring = LiveCover.openKeyring()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openTheLanguageSheet()
            val before = arrayOfNulls<Activity>(1).also { box -> scenario.onActivity { box[0] = it } }[0]

            row("Deutsch", ctx.getString(R.string.language_system_default)).performClick()
            val undo = hasText(ctx.getString(R.string.undo)) and hasClickAction()
            runCatching { compose.waitUntil(10_000) { sheetClosed() && compose.onAllNodes(undo).fetchSemanticsNodes().isNotEmpty() } }
                .onFailure { throw AssertionError("no closed sheet and English Undo; on screen: ${onScreen()}") }

            val after = arrayOfNulls<Activity>(1).also { box -> scenario.onActivity { box[0] = it } }[0]
            assertSame("a change of language recreated the activity", before, after)
            assertTrue("the snackbar does not name Deutsch in German", showing(german(R.string.language_changed, "Deutsch")))
            assertEquals("de", AppLanguage.current(ctx)?.tag)
            assertTrue("the change closed the keyring", VaultKeyringSession.isOpen)
            assertFalse("the change raised the cover", coverIsUp())

            compose.onNode(undo).performClick()
            compose.waitUntil(10_000) { AppLanguage.current(ctx) == null && !showing(german(R.string.language_changed, "Deutsch")) }
            compose.waitForIdle()
            assertEquals(null, AppLanguage.current(ctx))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                assertEquals(
                    "System default must be the empty list, never the resolved tag",
                    LocaleList.getEmptyLocaleList(),
                    ctx.getSystemService(LocaleManager::class.java).applicationLocales,
                )
            } else {
                assertEquals(null, GridPreference.language(ctx))
            }
            val anySnackbar = ctx.getString(R.string.language_changed, "").trim()
            assertFalse(
                "Undo posted a second snackbar; on screen: ${onScreen()}",
                compose.onAllNodes(hasText(anySnackbar, substring = true)).fetchSemanticsNodes().isNotEmpty(),
            )
            assertTrue(VaultKeyringSession.isOpen)
            assertFalse(coverIsUp())
        }
    }

    /**
     * The calculator's separators follow a change made in place, without a restart: after
     * German is chosen, the worn cover shows 1234567.5 as German writes it — `app-languages` D8.
     */
    @Test
    fun theCalculatorWritesGermanNumbersAfterAnInPlaceChange() {
        keyring = LiveCover.openKeyring()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openTheLanguageSheet()
            row("Deutsch", ctx.getString(R.string.language_system_default)).performClick()
            // The sheet closes, and the list behind it reads in German.
            val germanMenu = hasContentDescription(german(R.string.menu)) and hasClickAction()
            compose.waitUntil(10_000) { sheetClosed() && compose.onAllNodes(germanMenu).fetchSemanticsNodes().isNotEmpty() }

            // Locked and back: the activity is the same one, and the cover is what it raises.
            VaultKeyringSession.lock()
            VaultSession.lock()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { coverIsUp() }

            assertTrue(
                "the keypad's decimal key is not German's comma; on screen: ${onScreen()}",
                compose.onAllNodes(hasText(",") and hasClickAction()).fetchSemanticsNodes().isNotEmpty(),
            )
            listOf("1", "2", "3", "4", "5", "6", "7", ",", "5").forEach {
                compose.onAllNodes(hasText(it) and hasClickAction())[0].performClick()
            }
            compose.waitForIdle()
            assertTrue(
                "1234567.5 is not shown as 1.234.567,5",
                compose.onAllNodes(hasText("1.234.567,5", substring = true)).fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }

    /**
     * The kit's typed acknowledgement is the phrase in the app's language: in Japanese,
     * 理解しました enables Continue and the English phrase does not.
     */
    @Test
    fun theJapaneseAcknowledgementEnablesContinue() {
        AppLanguage.set(ctx, AppLanguage.supported.single { it.tag == "ja" })
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                AppLanguage.apply(activity)
                assertEquals("ja", activity.getString(R.string.language_resource_tag))
                activity.setContent {
                    VaultTheme(ThemeChoice.Dark) {
                        RecoveryKitScreen(words = List(24) { "abandon" }, onPrint = {}, onContinue = {})
                    }
                }
            }
            compose.waitForIdle()
            val proceed = compose.onNode(hasText("続行") and hasClickAction())
            proceed.assertIsNotEnabled()

            val field = compose.onNode(hasSetTextAction())
            field.performTextInput("I understand")
            proceed.assertIsNotEnabled()

            field.performTextClearance()
            field.performTextInput("理解しました")
            proceed.assertIsEnabled()
        }
    }
}
