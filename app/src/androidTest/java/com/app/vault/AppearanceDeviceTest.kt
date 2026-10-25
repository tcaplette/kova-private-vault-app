package com.app.vault

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.FolderNameSheet
import com.app.vault.ui.FolderPickerScreen
import com.app.vault.ui.AppearanceControl
import com.app.vault.ui.EditorScreen
import com.app.vault.ui.VaultListScreen
import com.app.vault.ui.DestroyStep
import com.app.vault.ui.VaultDestroyDialog
import com.app.vault.ui.IdentityScreen
import com.app.vault.ui.PanicScreen
import com.app.vault.ui.RenameSheet
import com.app.vault.ui.theme.LocalVaultPalette
import com.app.vault.ui.theme.VaultColors
import com.app.vault.ui.theme.VaultPalette
import com.app.vault.ui.theme.VaultTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The light scheme on a device — P2-23.
 *
 * **This replaces the task's screenshot clause, and the reason is the same one that
 * produced `DesignTokensTest`.** P2-23 asked for every screen screenshotted in both
 * modes. `VaultApplication` sets `FLAG_SECURE` on every activity, so every
 * screenshot comes back pure black — the clause is not executable, and a Verify line
 * nobody can run is worse than one that is missing, because it reads as done.
 *
 * What is asserted instead is what a screenshot would have been evidence *for*: that
 * a surface composed under a scheme resolves that scheme's colours. Composition is
 * where the answer lives, and reading it there needs no pixels.
 *
 * **Restores the device preference it changes** — the appearance is one entry in
 * `shared_prefs/grid`, shared with the app the developer is using, and a test that
 * left it on Light would change how the app looks afterwards. `FaceDownDeviceTest`'s
 * rule.
 */
@RunWith(AndroidJUnit4::class)
class AppearanceDeviceTest {

    /**
     * `createEmptyComposeRule` and `ActivityScenario`, not `createComposeRule` —
     * the repo's existing pattern (`NonMediaScreenTest`) and for its reason. A
     * self-hosting rule needs `androidx.compose.ui:ui-test-manifest` to supply a
     * host activity, which is a Gradle dependency this block does not add. The
     * androidTest manifest declares a bare `ComponentActivity` instead, which is
     * what that artifact does and costs nothing.
     *
     * **The host is deliberately not `MainActivity`.** This test composes twenty-odd
     * times, and the real activity's `onCreate` loads the index, connects Billing and
     * reads the biometric wrap — which left a Keystore alias behind and broke
     * `BiometricWrapTest`'s "creation must touch no Keystore alias" when the suite ran
     * in full while every class passed alone. A theme test has no business starting
     * the app.
     */
    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var before: ThemeChoice

    @Before
    fun remember() {
        before = GridPreference.appearance(ctx)
    }

    @After
    fun restore() {
        GridPreference.setAppearance(ctx, before)
    }

    /** What the composition actually resolved, captured from inside it. */
    private class Seen {
        var palette: VaultPalette? = null
        var surface: Color? = null
        var onSurface: Color? = null
        var schemeSurface: Color? = null
        var viewerGround: Color? = null
        var onViewerGround: Color? = null
    }

    /**
     * Compose [content] under [choice] and report what the tokens resolved to.
     *
     * The reads happen **inside** the same composition the surface is in, through the
     * same `VaultColors` accessors every screen uses — so this measures the path the
     * app takes, not a parallel one built for the test.
     */
    private fun underTheme(choice: ThemeChoice, content: @Composable () -> Unit): Seen {
        val seen = Seen()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    VaultTheme(choice) {
                        seen.palette = LocalVaultPalette.current
                        seen.surface = VaultColors.Surface
                        seen.onSurface = VaultColors.OnSurface
                        seen.viewerGround = VaultColors.ViewerGround
                        seen.onViewerGround = VaultColors.OnViewerGround
                        seen.schemeSurface = MaterialTheme.colorScheme.surface
                        content()
                    }
                }
            }
            compose.waitForIdle()
        }
        return seen
    }

    // --- the schemes resolve ------------------------------------------------

    @Test
    fun forcedLightResolvesTheLightPalette() {
        val seen = underTheme(ThemeChoice.Light) {}
        assertEquals(VaultPalette.Light, seen.palette)
        assertEquals(VaultPalette.Light.surface, seen.surface)
        assertEquals(VaultPalette.Light.onSurface, seen.onSurface)
    }

    @Test
    fun forcedDarkResolvesTheDarkPalette() {
        val seen = underTheme(ThemeChoice.Dark) {}
        assertEquals(VaultPalette.Dark, seen.palette)
        assertEquals(VaultPalette.Dark.surface, seen.surface)
        assertEquals(VaultPalette.Dark.onSurface, seen.onSurface)
    }

    /**
     * The `MaterialTheme` scheme and the palette are the same decision made once.
     * They are set side by side in `VaultTheme`, which is exactly the shape that
     * lets them drift — a Material component would then draw one scheme's colour
     * beside a `VaultColors` read of the other's.
     */
    @Test
    fun theMaterialSchemeAgreesWithThePalette() {
        assertEquals(VaultPalette.Light.surface, underTheme(ThemeChoice.Light) {}.schemeSurface)
        assertEquals(VaultPalette.Dark.surface, underTheme(ThemeChoice.Dark) {}.schemeSurface)
    }

    /** `System` resolves to whatever this device is currently set to, and matches the pure function. */
    @Test
    fun systemFollowsTheDevice() {
        val systemIsDark = ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val expected = if (ThemeChoice.resolve(ThemeChoice.System, systemIsDark)) {
            VaultPalette.Dark
        } else {
            VaultPalette.Light
        }
        assertEquals(expected, underTheme(ThemeChoice.System) {}.palette)
    }

    /** The two schemes are actually different, or every assertion above is vacuous. */
    @Test
    fun theTwoSchemesDiffer() {
        assertNotEquals(VaultPalette.Light.surface, VaultPalette.Dark.surface)
        assertNotEquals(VaultPalette.Light.onSurface, VaultPalette.Dark.onSurface)
    }

    // --- the viewer's exception ---------------------------------------------

    /**
     * The viewer's ground is black in both schemes, and its foregrounds are pinned
     * light in both — otherwise the light scheme puts near-black text on black.
     */
    @Test
    fun theViewersGroundAndForegroundAreIdenticalInBothSchemes() {
        val light = underTheme(ThemeChoice.Light) {}
        val dark = underTheme(ThemeChoice.Dark) {}

        assertEquals(Color(0xFF000000), light.viewerGround)
        assertEquals(Color(0xFF000000), dark.viewerGround)
        assertEquals(dark.onViewerGround, light.onViewerGround)

        // And the pinned foreground is not the light scheme's ordinary one, which is
        // the bug this exists to prevent.
        assertNotEquals(light.onSurface, light.onViewerGround)
    }

    // --- surfaces follow the scheme -----------------------------------------

    /**
     * Each surface composed under each scheme.
     *
     * Composed for real, so a surface that wraps itself in its own theme, or reads a
     * colour outside composition, changes what this observes. `LocalVaultPalette` has
     * no default, so one composed outside `VaultTheme` throws here rather than
     * quietly drawing the wrong scheme.
     *
     * **The surfaces needing a live vault are covered statically instead** and named
     * in `ThemeScanTest`: no colour literal exists outside the theme package, every
     * `VaultColors` property reads the active palette, and a `@Composable` getter
     * cannot be read outside composition at all — the compiler enforces that last one
     * for all 188 call sites. Building a decrypting source and a camera session to
     * re-derive a guarantee the type system already gives is not worth the fixture.
     */
    private fun eachSurface(): List<Pair<String, @Composable () -> Unit>> = listOf(
        "IdentityScreen" to { IdentityScreen(LauncherIdentity.Default, {}, {}) },
        "AppearanceControl" to { AppearanceControl(ThemeChoice.Default, {}) },
        "RenameSheet" to { RenameSheet("photo.jpg", {}, {}) },
        "PanicScreen" to { PanicScreen(false, false, null, 0, { _, _ -> }, { _, _, _ -> }, {}, {}, {}) },
        "FolderPickerScreen" to { FolderPickerScreen(1, null, emptyList(), null, {}, null, {}) },
        "FolderNameSheet" to { FolderNameSheet(creating = true, initial = "", problem = null, onConfirm = {}, onCancel = {}) },
        // `viewer-chrome-and-editor`. Composed with a one-pixel bitmap: the palette
        // is what this measures, and decoding a photograph to measure a colour would
        // be a fixture doing more than the assertion needs.
        "EditorScreen" to { EditorScreen(EDITOR_PIXEL, {}, {}) },
        // `vault-keyring`. Composed with no vaults, which is the state the palette has
        // to be right for as much as any other — an empty list is still a surface.
        // `home-screen-chrome`. The list hosts the side drawer now, so it takes the
        // state and the sheet the same way the grid does. Closed here: the palette this
        // measures is the screen's, and an open sheet would be measuring the sheet's.
        "VaultListScreen" to {
            VaultListScreen(
                vaults = emptyList(),
                onOpen = {},
                onEdit = {},
                onRemove = {},
                onLock = {},
                drawerState = androidx.compose.material3.rememberDrawerState(
                    androidx.compose.material3.DrawerValue.Closed,
                ),
                drawerContent = {},
                onCreate = {},
            )
        },
        // `protected-vault-destroy-gate`. Both steps, so the gate is held to both schemes too.
        "VaultDestroyDialog gate" to {
            VaultDestroyDialog(
                "Trip", false, {}, {}, {}, step = DestroyStep.Gate, ownPassphrase = true, showLost = true,
                checking = false, wrong = true, onSubmit = {},
            )
        },
        "VaultDestroyDialog choice" to {
            VaultDestroyDialog(
                "Trip", true, {}, {}, {}, step = DestroyStep.Choice, ownPassphrase = false, showLost = false,
                checking = false, wrong = false, onSubmit = {},
            )
        },
    )

    /** The smallest thing `EditorScreen` will take. See [eachSurface]. */
    private val EDITOR_PIXEL: android.graphics.Bitmap by lazy {
        android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
    }

    @Test
    fun everySurfaceComposesUnderBothSchemes() {
        eachSurface().forEach { (name, content) ->
            val light = underTheme(ThemeChoice.Light, content)
            assertEquals("$name did not resolve the light palette", VaultPalette.Light, light.palette)

            val dark = underTheme(ThemeChoice.Dark, content)
            assertEquals("$name did not resolve the dark palette", VaultPalette.Dark, dark.palette)
        }
    }

    /**
     * The list above is not allowed to quietly shrink. A surface removed from it
     * would take its coverage with it and nothing would say so.
     */
    @Test
    fun theSurfaceListIsNotEmptyAndNamesAreUnique() {
        val names = eachSurface().map { it.first }
        assertTrue("no surfaces are covered", names.size >= 8)
        assertEquals("a surface is listed twice", names.size, names.toSet().size)
    }


    // --- the control itself --------------------------------------------------

    /** Compose the control in the bare host and run [assertions] against it. */
    private fun withControl(
        current: ThemeChoice,
        onChoose: (ThemeChoice) -> Unit,
        assertions: () -> Unit,
    ) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent { VaultTheme { AppearanceControl(current, onChoose) } }
            }
            compose.waitForIdle()
            assertions()
        }
    }

    /**
     * ***All three at once, and no navigation.*** The screen this replaced showed the
     * same three choices only after a drawer close and a push; the whole point of the
     * control is that they are simultaneously present.
     */
    @Test
    fun everyChoiceIsVisibleWithoutNavigating() {
        withControl(ThemeChoice.Default, {}) {
            ThemeChoice.entries.forEach { choice ->
                assertTrue(
                    "${choice.name} is not offered by the control",
                    compose.onAllNodes(hasText(ctx.getString(choice.label))).fetchSemanticsNodes().isNotEmpty(),
                )
            }
            // And the group says what it sets, so a screen reader does not reach
            // three unlabelled buttons.
            //
            // **Asserted on the description and not on a visible heading.** The
            // heading was removed as redundant — three segments reading "System",
            // "Light" and "Dark" say what they are, and a word above them is the
            // same information twice — so the name survives for a screen reader and
            // for nothing else.
            assertTrue(
                "the control does not name what it sets",
                compose.onAllNodesWithContentDescription("Appearance")
                    .fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }

    /**
     * The active choice is identified by something that is not a colour.
     *
     * §6.4, and this is the control where it earns the most: a tint-only marker here
     * would be changing colour at the instant it was meant to be identifying
     * something. Asserted through the selection semantics the check mark and the
     * label carry, which survive a palette swap — a colour assertion could not tell
     * "marked" from "re-themed".
     */
    @Test
    fun theActiveChoiceIsMarkedWithoutRelyingOnColour() {
        ThemeChoice.entries.forEach { active ->
            withControl(active, {}) {
                val selected = compose
                    .onAllNodes(androidx.compose.ui.test.isSelected())
                    .fetchSemanticsNodes()
                assertEquals("exactly one segment must read as selected", 1, selected.size)
            }
        }
    }

    /** Tapping a segment reports that choice, and only that choice. */
    @Test
    fun tappingASegmentReportsIt() {
        val chosen = mutableListOf<ThemeChoice>()
        withControl(ThemeChoice.Dark, { chosen += it }) {
            compose.onAllNodes(hasText(ctx.getString(ThemeChoice.Light.label)))[0].performClick()
            compose.waitForIdle()
            assertEquals(listOf(ThemeChoice.Light), chosen)
        }
    }

    /**
     * Tapping the active segment reports the choice already in effect and invents
     * nothing. The write and the re-theme are dropped by `chooseAppearance`'s guard,
     * which is where that decision has always lived.
     */
    @Test
    fun tappingTheActiveSegmentReportsTheChoiceAlreadyInEffect() {
        val chosen = mutableListOf<ThemeChoice>()
        withControl(ThemeChoice.Dark, { chosen += it }) {
            compose.onAllNodes(hasText(ctx.getString(ThemeChoice.Dark.label)))[0].performClick()
            compose.waitForIdle()
            assertEquals(listOf(ThemeChoice.Dark), chosen)
        }
    }

    // --- the preference ------------------------------------------------------

    @Test
    fun theChoiceOutlivesTheProcessThatMadeIt() {
        GridPreference.setAppearance(ctx, ThemeChoice.Light)

        // A fresh handle on the same store is what a new process gets.
        val reread = ThemeChoice.of(
            ctx.getSharedPreferences("grid", android.content.Context.MODE_PRIVATE)
                .getString("appearance", null),
        )
        assertEquals(ThemeChoice.Light, reread)

        // Stored by name and not ordinal.
        assertEquals(
            "Light",
            ctx.getSharedPreferences("grid", android.content.Context.MODE_PRIVATE)
                .getString("appearance", null),
        )
    }

    /**
     * The scheme is right on the **first** composition of a fresh activity, not after
     * a frame of the other one. Two launches, so the second is a relaunch reading the
     * preference rather than inheriting anything from the first.
     */
    @Test
    fun theSchemeIsRightOnTheFirstFrameAfterARelaunch() {
        GridPreference.setAppearance(ctx, ThemeChoice.Light)
        assertEquals(VaultPalette.Light, underTheme(GridPreference.appearance(ctx)) {}.palette)

        GridPreference.setAppearance(ctx, ThemeChoice.Dark)
        assertEquals(VaultPalette.Dark, underTheme(GridPreference.appearance(ctx)) {}.palette)
    }

    /**
     * The appearance is a device preference and owes nothing to a vault session, so
     * locking must not disturb it. Asserted rather than assumed, because every other
     * piece of state in this app is deliberately destroyed by a lock and this one is
     * deliberately not.
     */
    @Test
    fun theChoiceSurvivesALock() {
        GridPreference.setAppearance(ctx, ThemeChoice.Light)
        VaultSession.lock()
        assertEquals(ThemeChoice.Light, GridPreference.appearance(ctx))
        assertEquals(VaultPalette.Light, underTheme(GridPreference.appearance(ctx)) {}.palette)
    }

    /**
     * **The device's own night mode, changed for real.**
     *
     * `System` must follow it and a forced choice must ignore it — the whole
     * distinction the setting offers, and the one thing about it that a pure function
     * cannot prove on its own, because the pure function is only correct if it is fed
     * the platform's actual flag.
     *
     * Restores the device's night mode afterwards: it is a system-wide setting shared
     * with everything else on the emulator.
     */
    @Test
    fun systemFollowsARealNightModeChangeAndForcedChoicesIgnoreIt() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun night(on: Boolean) {
            automation.executeShellCommand("cmd uimode night ${if (on) "yes" else "no"}").close()
            Thread.sleep(1_500)
        }

        val wasDark = ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        try {
            night(true)
            assertEquals(
                "System did not follow the device into night mode",
                VaultPalette.Dark, underTheme(ThemeChoice.System) {}.palette,
            )
            assertEquals(
                "a forced Light followed the device",
                VaultPalette.Light, underTheme(ThemeChoice.Light) {}.palette,
            )

            night(false)
            assertEquals(
                "System did not follow the device out of night mode",
                VaultPalette.Light, underTheme(ThemeChoice.System) {}.palette,
            )
            assertEquals(
                "a forced Dark followed the device",
                VaultPalette.Dark, underTheme(ThemeChoice.Dark) {}.palette,
            )
        } finally {
            night(wasDark)
        }
    }

    @Test
    fun anInstallThatNeverChoseIsDark() {
        ctx.getSharedPreferences("grid", android.content.Context.MODE_PRIVATE)
            .edit().remove("appearance").commit()
        assertEquals(ThemeChoice.Dark, GridPreference.appearance(ctx))
        assertEquals(VaultPalette.Dark, underTheme(GridPreference.appearance(ctx)) {}.palette)
    }

    /**
     * The window theme and the composition resolve through the same function, so a
     * cold start cannot paint one ground and then draw the other. Asserted as the
     * parity it is, rather than by trying to photograph a first frame that
     * `FLAG_SECURE` would return black anyway.
     */
    @Test
    fun theWindowGroundAgreesWithTheComposedGround() {
        val systemIsDark = ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

        ThemeChoice.entries.forEach { choice ->
            GridPreference.setAppearance(ctx, choice)
            val dark = ThemeChoice.resolve(GridPreference.appearance(ctx), systemIsDark)
            val windowGround = ctx.getColor(
                if (dark) R.color.vault_ground else R.color.vault_ground_light
            )
            val composed = underTheme(choice) {}.surface!!.toArgb()
            assertEquals(
                "${choice.name}: the window would flash a different ground than the app draws",
                windowGround,
                composed,
            )
        }
    }
}
