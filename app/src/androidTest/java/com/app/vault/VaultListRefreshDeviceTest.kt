package com.app.vault

import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.vault.ui.EditVaultScreen
import com.app.vault.ui.HomeTarget
import com.app.vault.ui.VaultHomeTransition
import com.app.vault.ui.VaultListScreen
import com.app.vault.ui.firstGrapheme
import com.app.vault.ui.theme.VaultPalette
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `vault-list-refresh`, task 5.2 — the refreshed list, Edit vault, the choosers and the row →
 * vault transition, as composed. Hosted composables only: no keyring, no vault, nothing
 * written to the app's storage.
 */
@RunWith(AndroidJUnit4::class)
class VaultListRefreshDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val compose = createComposeRule()

    private val plain = "Opens with this list's passphrase"
    private val locked = "Locked — has its own passphrase"

    private fun entry(position: Int, name: String, own: Boolean = false, look: VaultAppearance = VaultAppearance.LETTER) =
        VaultKeyring.Entry(
            position, name, ByteArray(72), ByteArray(16), hasOwnPassphrase = own, appearance = look.pack(),
        )

    private val four = listOf(
        entry(0, "Photos", look = VaultAppearance(FolderColour.Blue, 0)),
        entry(1, "Papers", own = true, look = VaultAppearance(FolderColour.Red, 12)),
        entry(2, "Sounds"),
        entry(3, "Trips", look = VaultAppearance(FolderColour.Teal, 15)),
    )

    private fun list(vaults: List<VaultKeyring.Entry>) = compose.setContent {
        VaultTheme(ThemeChoice.Dark) {
            VaultListScreen(
                vaults = vaults,
                onOpen = {},
                onEdit = {},
                onRemove = {},
                onLock = {},
                drawerState = rememberDrawerState(DrawerValue.Closed),
                drawerContent = {},
                onCreate = {},
            )
        }
    }

    // --- the list ------------------------------------------------------------------

    @Test
    fun rowsShareOneHeightAndEachHasTwoLines() {
        list(four)
        val heights = four.map { e ->
            val line = if (e.hasOwnPassphrase) locked else plain
            val node = compose.onNode(hasContentDescription("${e.name}, $line"))
            node.assert(hasText(e.name)).assert(hasText(line))
            node.fetchSemanticsNode().boundsInRoot.height
        }
        assertEquals("every row is the same height: $heights", 1, heights.distinct().size)
    }

    @Test
    fun aProtectedRowAnnouncesItsNameAndLocked() {
        list(four)
        compose.onNode(hasContentDescription("Papers, $locked")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Photos, $plain")).assertIsDisplayed()
    }

    @Test
    fun theBadgeLetterIsAWholeGrapheme() {
        assertEquals("👨‍👩‍👧", firstGrapheme("👨‍👩‍👧 Family"))
        assertEquals("🇫🇷", firstGrapheme("🇫🇷 Paris"))
        // "école" with the accent as its own code point: two code points, one character.
        assertEquals("É", firstGrapheme("école"))
        assertEquals("P", firstGrapheme("photos"))
        assertEquals("", firstGrapheme("  "))
    }

    @Test
    fun theLargeTitleCollapsesOnScrollAndTheBarKeepsItsControls() {
        list((0 until VaultKeyring.CAPACITY).map { entry(it, "Vault $it") })
        compose.waitForIdle()

        fun titleBottom() = compose.onAllNodesWithText("Vaults", useUnmergedTree = true)
            .fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }

        val before = titleBottom()
        compose.onNode(hasContentDescription("Vault 2, $plain"))
            .performTouchInput { swipeUp(startY = centerY, endY = centerY - 900f, durationMillis = 600) }
        compose.waitForIdle()
        val after = titleBottom()

        val collapse = with(compose.density) { 40.dp.toPx() }
        assertTrue("the large title collapsed: $before → $after", before - after > collapse)
        listOf("Lock", "New vault", "Menu").forEach {
            compose.onNodeWithContentDescription(it).assertIsDisplayed()
        }
    }

    // --- Edit vault and the choosers ------------------------------------------------

    private var saved: Pair<String, VaultAppearance>? = null
    private var backed = false

    private fun edit(vault: VaultKeyring.Entry) = compose.setContent {
        // The draft is the caller's since `vault-passphrase-manage`, as `MainActivity` holds it.
        var name by androidx.compose.runtime.remember { mutableStateOf(vault.name) }
        var look by androidx.compose.runtime.remember { mutableStateOf(VaultAppearance.of(vault.appearance)) }
        VaultTheme(ThemeChoice.Dark) {
            EditVaultScreen(
                vault = vault,
                name = name,
                appearance = look,
                onDraft = { n, a -> name = n; look = a },
                onSave = { n, a -> saved = n to a },
                onBack = { backed = true },
                onPassphrase = {},
                busy = false,
                problem = null,
                status = null,
            )
        }
    }

    private fun toTop() {
        repeat(3) {
            compose.onNodeWithText("Icon").performTouchInput {
                swipeDown(startY = centerY, endY = centerY + 900f, durationMillis = 400)
            }
        }
        compose.waitForIdle()
    }

    /**
     * The preview, found on the screen's centre column as the run of [fill] pixels — the
     * centre falls in the gaps of the swatch row and the icon grid, so the only thing in
     * the vault's colour there is the badge.
     */
    private fun preview(fill: androidx.compose.ui.graphics.Color): android.graphics.Bitmap? {
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        val cx = shot.width / 2
        val rows = (0 until shot.height / 2).filter { shot.getPixel(cx, it) == fill.toArgb() }
        if (rows.isEmpty()) return null
        val r = (rows.last() - rows.first()) / 2
        return android.graphics.Bitmap.createBitmap(shot, cx - r, rows.first(), 2 * r, 2 * r)
    }

    private fun sameImage(a: android.graphics.Bitmap, b: android.graphics.Bitmap) =
        a.width == b.width && a.height == b.height &&
            (0 until a.width).all { x -> (0 until a.height).all { y -> a.getPixel(x, y) == b.getPixel(x, y) } }

    @Test
    fun thePreviewFollowsTheNameTheColourAndTheIcon() {
        val dark = VaultPalette.Dark
        edit(entry(1, "Papers", look = VaultAppearance(FolderColour.Red, 0)))
        compose.waitForIdle()

        val red = preview(dark.folderRed)
        assertTrue("the preview is drawn in the vault's colour", red != null && red.width > 0)
        assertEquals("no blue anywhere on the centre column yet", null, preview(dark.folderBlue))

        compose.onNode(hasSetTextAction()).performTextReplacement("Letters")
        compose.waitForIdle()
        val renamed = preview(dark.folderRed)!!
        assertFalse("the letter follows the name", sameImage(red!!, renamed))

        compose.onNodeWithContentDescription("Blue").performScrollTo().performClick()
        toTop()
        val blue = preview(dark.folderBlue)
        assertTrue("the preview follows the colour", blue != null)
        assertEquals("and the red is gone", null, preview(dark.folderRed))

        compose.onNodeWithContentDescription("Home").performScrollTo().performClick()
        toTop()
        assertFalse("the preview follows the icon", sameImage(blue!!, preview(dark.folderBlue)!!))
    }

    @Test
    fun backLeavesTheVaultUntouchedAndSaveCarriesBothHalves() {
        edit(entry(2, "Papers", own = true))
        compose.onNode(hasSetTextAction()).performTextReplacement("Letters")
        compose.onNodeWithContentDescription("Pink").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(backed)
        assertEquals("back saved nothing", null, saved)

        compose.onNodeWithContentDescription("Pets").performScrollTo().performClick()
        compose.onNode(hasText("Save")).performScrollTo().performClick()
        assertEquals("Letters" to VaultAppearance(FolderColour.Pink, 6), saved)
    }

    @Test
    fun everySwatchAndIconCellAnnouncesItsNameAndSelection() {
        edit(entry(0, "Photos", look = VaultAppearance(FolderColour.Teal, 3)))
        compose.waitForIdle()

        fun selected(label: String): Boolean? =
            compose.onNode(hasContentDescription(label)).fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.Selected)

        val swatches = listOf("No colour", "Red", "Amber", "Teal", "Blue", "Pink")
        val cells = listOf("Letter") + VaultAppearance.ICONS.map { ctx.getString(it.first) }
        assertEquals(25, cells.size)

        swatches.forEach { assertEquals("$it reports its selection", it == "Teal", selected(it)) }
        cells.forEach { assertEquals("$it reports its selection", it == "Person", selected(it)) }

        compose.onNodeWithContentDescription("Letter").performScrollTo().performClick()
        assertEquals(true, selected("Letter"))
        assertEquals(false, selected("Person"))
    }

    // --- the transition ----------------------------------------------------------------

    private var target by mutableStateOf(HomeTarget.List)

    private fun transition(reduced: Boolean) = compose.setContent {
        VaultHomeTransition(
            target = target,
            openPosition = 3,
            reducedMotion = reduced,
            list = { rowBounds ->
                Column {
                    Box(Modifier.fillMaxWidth().height(120.dp))
                    Box(rowBounds(3).fillMaxWidth().height(72.dp).testTag("row"))
                }
            },
            // The shared bounds outermost, as the app applies them.
            grid = { bounds -> Box(bounds.fillMaxSize().testTag("grid")) },
        )
    }

    @Test
    fun theVaultGrowsOutOfItsRowWithin250ms() {
        transition(reduced = false)
        compose.waitForIdle()
        val row = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot

        compose.mainClock.autoAdvance = false
        target = HomeTarget.Grid
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(100)
        val mid = compose.onNodeWithTag("grid").fetchSemanticsNode().boundsInRoot

        // 250ms from the start, plus the frame the transition started on.
        compose.mainClock.advanceTimeBy(150 + 16)
        val end = compose.onNodeWithTag("grid").fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(500)
        val settled = compose.onNodeWithTag("grid").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = true

        assertTrue("mid-transition the grid is taller than the row: $mid vs $row", mid.height > row.height)
        assertTrue("and shorter than the screen: $mid vs $settled", mid.height < settled.height)
        assertEquals("full within 250ms: $end vs $settled", settled.height, end.height, 1f)
        assertTrue("and it is the screen, not the row", settled.height > 3 * row.height)

        // And back into the row.
        target = HomeTarget.List
        compose.waitForIdle()
        assertEquals(row, compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot)
        assertEquals(0, compose.onAllNodes(hasTestTag("grid")).fetchSemanticsNodes().size)
    }

    @Test
    fun reducedMotionSwitchesInstantly() {
        transition(reduced = true)
        compose.waitForIdle()
        val row = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot

        compose.mainClock.autoAdvance = false
        target = HomeTarget.Grid
        compose.mainClock.advanceTimeByFrame()
        val grid = compose.onNodeWithTag("grid").fetchSemanticsNode().boundsInRoot
        assertEquals("the list is gone on the same frame", 0, compose.onAllNodes(hasTestTag("row")).fetchSemanticsNodes().size)
        assertTrue("the grid is the screen at once, not the row: $grid", grid.height > 3 * row.height)
        compose.mainClock.autoAdvance = true
    }

    private var unlocked by mutableStateOf(false)
    private var other by mutableStateOf(false)

    /**
     * `MainActivity`'s shape: the list and the grid are two branches of one `when` with another
     * screen between them, joined by one remembered `movableContentOf`. The transition has to
     * survive the move from one branch to the other.
     */
    @Test
    fun theMovableContentCarriesTheTransitionAcrossTwoBranches() {
        compose.setContent {
            val home = androidx.compose.runtime.remember {
                androidx.compose.runtime.movableContentOf { t: HomeTarget ->
                    VaultHomeTransition(
                        target = t,
                        openPosition = 3,
                        reducedMotion = false,
                        list = { rowBounds ->
                            Column {
                                Box(Modifier.fillMaxWidth().height(120.dp))
                                Box(rowBounds(3).fillMaxWidth().height(72.dp).testTag("row"))
                            }
                        },
                        grid = { bounds -> Box(bounds.fillMaxSize().testTag("grid")) },
                    )
                }
            }
            when {
                !unlocked -> home(HomeTarget.List)
                other -> Box(Modifier.fillMaxSize().testTag("other"))
                else -> home(HomeTarget.Grid)
            }
        }
        compose.waitForIdle()
        val row = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot

        compose.mainClock.autoAdvance = false
        unlocked = true
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(100)
        val mid = compose.onNodeWithTag("grid").fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(700)
        val settled = compose.onNodeWithTag("grid").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = true

        assertTrue("the move animated: mid $mid, row $row, settled $settled",
            mid.height > row.height && mid.height < settled.height)
        assertTrue(settled.height > 3 * row.height)
    }

    private fun hasTestTag(tag: String) = androidx.compose.ui.test.hasTestTag(tag)
}
