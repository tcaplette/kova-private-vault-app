package com.app.vault

import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.vault.ui.VaultGrid
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bar, the chips and the four presentations — `vault-destinations`.
 *
 * ***Rendered directly rather than through the activity, and that is deliberate.***
 * `DestructiveTestScanTest` forbids a new instrumented test reaching for the install's
 * real `vault.slots` and `blobs/`, because a routine `connectedAndroidTest` run would then
 * destroy whatever vault is on the device — and its debt list may only shrink. Everything
 * this file asserts is a property of the grid given a destination, so a vault, an unlock
 * and an import would be three preconditions for a drawing check. `FabDeviceTest` made the
 * same call for the same reason.
 *
 * The two questions that genuinely need the running app — that back returns to Media, and
 * that a destination gives back the place it was left in — are asserted in
 * `NonMediaScreenTest`, which already creates a vault and is already on that list.
 *
 * `VaultDestinationTest` holds the partition on the JVM; this holds what is on screen.
 */
@RunWith(AndroidJUnit4::class)
class DestinationDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val compose = createComposeRule()

    private companion object {
        const val PHOTO = "beach.jpg"
        const val CLIP = "sunset.mp4"
        const val DOC = "statement.pdf"
        const val MEMO = "reminder.m4a"
    }

    private fun item(name: String, duration: Long? = null) = VaultItem(
        blobIds = listOf(ByteArray(16) { name.first().code.toByte() }),
        plaintextLength = 4_096L,
        thumb = null,
        filename = name,
        album = null,
        createdAt = null,
        importedAt = 1L,
        tags = emptyList(),
        durationMs = duration,
    )

    /**
     * Renders the grid at a destination.
     *
     * [items] is what the caller has already narrowed, exactly as `MainActivity` hands it
     * over: the grid presents a destination, it does not decide one.
     */
    private fun grid(
        destination: VaultDestination = VaultDestination.Media,
        items: List<VaultItem> = listOf(item(PHOTO)),
        selecting: Boolean = false,
        trash: Boolean = false,
        /** Which of the grid's add actions ran, by name, in order. */
        taps: MutableList<String> = mutableListOf(),
        /**
         * Whether the header carries a settings control at a destination — `MainActivity`
         * passes null on Files, which `ScreenChromeTest` holds in its source.
         */
        hasSettings: (VaultDestination) -> Boolean = { false },
    ) {
        compose.setContent {
            VaultTheme {
                var at by remember { mutableStateOf(destination) }
                var filter by remember { mutableStateOf(MediaFilter.Default) }
                // `destination-view-modes`. One mode per destination, seeded from each
                // one's default, so this harness behaves as the app does on a device that
                // has never been told otherwise.
                val modes = remember {
                    mutableStateMapOf<VaultDestination, VaultViewMode>().apply {
                        VaultDestination.entries.forEach { put(it, it.defaultViewMode) }
                    }
                }
                val shown = remember(items, at, filter) {
                    items.filter {
                        it.kind in if (at == VaultDestination.Media) filter.kinds else at.kinds
                    }
                }
                VaultGrid(
                    items = if (trash) items else shown,
                    thumbnailFor = { null },
                    onOpen = {},
                    selected = if (selecting) setOf(items.first().itemId) else emptySet(),
                    onBeginSelection = {},
                    onToggleSelection = {},
                    onSelectAll = {},
                    onClearSelection = {},
                    onDeleteSelected = {},
                    onExportSelected = {},
                    onImport = { taps += "import" },
                    onNewNote = { taps += "note" },
                    onVoiceMemo = { taps += "memo" },
                    trash = trash,
                    destination = at,
                    onDestination = { at = it },
                    mediaFilter = filter,
                    onMediaFilter = { filter = it },
                    viewMode = modes[at] ?: at.defaultViewMode,
                    onViewMode = { modes[at] = it },
                    drawerState = DrawerState(DrawerValue.Closed),
                    drawerContent = {},
                    vaultName = "Vault",
                    onDestinationSettings = if (hasSettings(at)) ({ taps += "settings" }) else null,
                )
            }
        }
    }

    // --- the header's + , `file-import` ---------------------------------------

    /** Every destination in turn, through the bar — `setContent` runs once per test. */
    private fun atEachDestination(block: (VaultDestination) -> Unit) {
        grid(items = listOf(item(PHOTO), item(DOC), item(MEMO)))
        VaultDestination.entries.forEach { destination ->
            compose.onNodeWithText(ctx.getString(destination.label)).performClick()
            compose.waitForIdle()
            block(destination)
        }
    }

    /**
     * The `+` is on Media and Files and on neither of the other two — `file-import`.
     *
     * **Absence is asserted by looking for the name**, which is what the control is: the
     * header is icon-only, so a `+` that is drawn is a node carrying one of the two
     * descriptions, and a `+` that is withheld is neither of them present.
     */
    @Test
    fun theAddControlIsOnMediaAndFilesAndNowhereElse() {
        atEachDestination { destination ->
            val expected = destination.addAction?.description
            AddAction.entries.forEach { action ->
                val drawn = compose.onAllNodesWithContentDescription(ctx.getString(action.description))
                    .fetchSemanticsNodes().isNotEmpty()
                assertEquals(
                    "${destination.label} and the header's \"${action.description}\"",
                    action.description == expected,
                    drawn,
                )
            }
        }
    }

    /**
     * Media, Notes and Memos carry their settings control; Files carries none, and no gap
     * stands where it was — `destination-settings-and-slideshow` D1. New folder and the
     * drawer control meet edge to edge on Files.
     */
    @Test
    fun theSettingsControlIsOnEveryDestinationButFiles() {
        grid(
            items = listOf(item(PHOTO), item(DOC), item(MEMO)),
            hasSettings = { it != VaultDestination.Files },
        )
        VaultDestination.entries.forEach { destination ->
            compose.onNodeWithText(ctx.getString(destination.label)).performClick()
            compose.waitForIdle()
            val name = ctx.getString(destination.label)
            val drawn = compose.onAllNodesWithContentDescription(ctx.getString(R.string.grid_destination_settings, name))
                .fetchSemanticsNodes().isNotEmpty()
            assertEquals("$name settings control", destination != VaultDestination.Files, drawn)
            if (destination == VaultDestination.Files) {
                val folder = compose.onNodeWithContentDescription("New folder")
                    .fetchSemanticsNode().boundsInRoot
                val drawer = compose.onNodeWithContentDescription("Trash and settings")
                    .fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "a gap stands where Files' settings were: ${folder.right} to ${drawer.left}",
                    drawer.left - folder.right < 1f,
                )
            }
        }
    }

    /**
     * Withheld, not disabled — and new folder does not go with it.
     *
     * The two controls were gated together before `file-import`: one `if` held both, so
     * making the `+` conditional would have taken new folder off Notes and Memos as a side
     * effect. A folder holds any kind, so it belongs on all four.
     */
    @Test
    fun newFolderStaysOnEveryDestination() {
        atEachDestination {
            compose.onNodeWithContentDescription("New folder").assertIsDisplayed()
            compose.onNodeWithContentDescription("Trash and settings").assertIsDisplayed()
        }
    }

    /** The `+` meets the touch target wherever it is drawn. */
    @Test
    fun theAddControlMeetsTheTouchTarget() {
        atEachDestination { destination ->
            destination.addAction?.let {
                compose.onNodeWithContentDescription(ctx.getString(it.description))
                    .assertHeightIsAtLeast(Spacing.touchTarget)
            }
        }
    }

    // --- the bar --------------------------------------------------------------

    /** Four entries, each carrying its own word. */
    @Test
    fun theBarNamesEveryDestination() {
        grid()
        VaultDestination.entries.forEach { destination ->
            compose.onNodeWithText(ctx.getString(destination.label)).assertIsDisplayed()
        }
    }

    /**
     * Every entry meets the minimum touch target.
     *
     * The bar's own 80dp already clears it; this is the assertion that says so, because
     * `VaultListScreen`'s lock action sat on a component's 40dp default until a device
     * test caught it.
     */
    @Test
    fun everyEntryMeetsTheTouchTarget() {
        grid()
        VaultDestination.entries.forEach { destination ->
            compose.onNodeWithText(ctx.getString(destination.label))
                .assertHeightIsAtLeast(Spacing.touchTarget)
        }
    }

    /** Tapping an entry moves to it. */
    @Test
    fun tappingAnEntryChangesDestination() {
        grid(items = listOf(item(PHOTO), item(DOC)))
        compose.onNodeWithText(PHOTO).assertIsDisplayed()

        compose.onNodeWithText(ctx.getString(VaultDestination.Files.label)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(DOC).assertIsDisplayed()
        assertTrue(
            "the photograph is still drawn in Files",
            compose.onAllNodesWithText(PHOTO).fetchSemanticsNodes().isEmpty(),
        )
    }

    /**
     * The bar is absent exactly where the search field is absent.
     *
     * A selection acts on the list beneath it, and the trash holds every kind at once —
     * neither has a destination to be on. Absent means **not drawn**, as `grid-fab` means
     * it: a control that is present and dead only advertises a dead end.
     */
    @Test
    fun theBarIsAbsentWhileSelecting() {
        grid(selecting = true)
        assertFalse(
            "the bar is drawn over a selection",
            compose.onAllNodesWithText(ctx.getString(VaultDestination.Files.label))
                .fetchSemanticsNodes().isNotEmpty(),
        )
        assertFalse(
            "the search field is drawn over a selection",
            compose.onAllNodesWithText("Search").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun theBarIsAbsentInTheTrash() {
        grid(trash = true)
        assertFalse(
            "the bar is drawn over the trash",
            compose.onAllNodesWithText(ctx.getString(VaultDestination.Files.label))
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /**
     * ***The trash still shows every kind.*** It is not a destination, so a trash entered
     * from Files that listed only documents would hide the photographs the user came to
     * restore.
     */
    @Test
    fun theTrashHoldsEveryKind() {
        grid(
            destination = VaultDestination.Files,
            items = listOf(item(PHOTO), item(DOC), item(MEMO)),
            trash = true,
        )
        listOf(PHOTO, DOC, MEMO).forEach { name ->
            compose.onNodeWithText(name).assertIsDisplayed()
        }
    }

    // --- the chips ------------------------------------------------------------

    /** All is the selection a vault opens on, and it shows both kinds. */
    @Test
    fun theChipsStartOnAllAndNarrowMedia() {
        grid(items = listOf(item(PHOTO), item(CLIP)))
        compose.onNodeWithText(PHOTO).assertIsDisplayed()
        compose.onNodeWithText(CLIP).assertIsDisplayed()

        compose.onNodeWithText(ctx.getString(MediaFilter.Photos.label)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(PHOTO).assertIsDisplayed()
        assertTrue(
            "Photos still shows video",
            compose.onAllNodesWithText(CLIP).fetchSemanticsNodes().isEmpty(),
        )

        compose.onNodeWithText(ctx.getString(MediaFilter.Videos.label)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(CLIP).assertIsDisplayed()
        assertTrue(
            "Videos still shows photographs",
            compose.onAllNodesWithText(PHOTO).fetchSemanticsNodes().isEmpty(),
        )

        compose.onNodeWithText(ctx.getString(MediaFilter.All.label)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(PHOTO).assertIsDisplayed()
        compose.onNodeWithText(CLIP).assertIsDisplayed()
    }

    /** The chips belong to Media and are not drawn over the other three. */
    @Test
    fun theChipsAreOnlyOnMedia() {
        grid(destination = VaultDestination.Files, items = listOf(item(DOC)))
        assertFalse(
            "Media's chips are drawn on Files",
            compose.onAllNodesWithText(ctx.getString(MediaFilter.Photos.label))
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    // --- what each destination draws -------------------------------------------

    /** A recording of known length says it; one of unknown length says nothing. */
    @Test
    fun aMemoShowsALengthOnlyWhenOneIsKnown() {
        grid(
            destination = VaultDestination.Memos,
            items = listOf(item(MEMO, duration = 65_000L), item("silent.m4a")),
        )
        compose.onNodeWithText("1:05").assertIsDisplayed()
        assertEquals(
            "a placeholder duration was drawn for a recording that has none",
            0,
            compose.onAllNodesWithText("--:--").fetchSemanticsNodes().size,
        )
    }

    /** A document row carries its size. */
    @Test
    fun aFileRowShowsItsSize() {
        grid(destination = VaultDestination.Files, items = listOf(item(DOC)))
        compose.onNodeWithText(DOC).assertIsDisplayed()
        // 4 096 bytes under SI names — `sizeLabel`'s rule, unchanged by this change.
        compose.onNodeWithText("4.1 kB", substring = true).assertIsDisplayed()
    }

    // --- view modes ---------------------------------------------------------------

    /** The toggle is in the band, meets the touch target, and is named by its action. */
    @Test
    fun theToggleIsInTheBandNamedByItsAction() {
        grid()
        // Media defaults to tiles, so the toggle offers list.
        compose.onNodeWithContentDescription(ctx.getString(VaultViewMode.List.actionLabel))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(Spacing.touchTarget)
        assertTrue(
            "the toggle names the mode already in use rather than the one it switches to",
            compose.onAllNodesWithContentDescription(ctx.getString(VaultViewMode.Tiles.actionLabel))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    /** Never in the header — `vault-grid` allows it no fifth control. */
    @Test
    fun theToggleIsNotAHeaderControl() {
        grid()
        val toggle = compose.onNodeWithContentDescription(ctx.getString(VaultViewMode.List.actionLabel))
            .fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithContentDescription("Add photos or videos")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the view toggle sits on the header row, which may not grow to a fifth control",
            toggle.top > header.bottom,
        )
    }

    /** Files in Tiles draws documents as tiles; switching back draws rows. */
    @Test
    fun filesCanBeSwitchedToTilesAndBack() {
        grid(destination = VaultDestination.Files, items = listOf(item(DOC)))
        compose.onNodeWithText("4.1 kB", substring = true).assertIsDisplayed()

        compose.onNodeWithContentDescription(ctx.getString(VaultViewMode.Tiles.actionLabel)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(DOC).assertIsDisplayed()
        assertTrue(
            "the row's size label survived into tiles view",
            compose.onAllNodesWithText("4.1 kB", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )

        compose.onNodeWithContentDescription(ctx.getString(VaultViewMode.List.actionLabel)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("4.1 kB", substring = true).assertIsDisplayed()
    }

    /** Media in List draws rows, and a video still carries its length. */
    @Test
    fun mediaCanBeSwitchedToList() {
        grid(items = listOf(item(PHOTO), item(CLIP, duration = 94_000L)))
        compose.onNodeWithContentDescription(ctx.getString(VaultViewMode.List.actionLabel)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(PHOTO).assertIsDisplayed()
        compose.onNodeWithText(CLIP).assertIsDisplayed()
        compose.onNodeWithText("1:34").assertIsDisplayed()
    }

    /**
     * ***A folder is a row in List and a tile in Tiles, and tap enters from both.***
     *
     * This is the change's origin: a square colour block standing over a list of notes.
     */
    @Test
    fun aFolderIsARowInListAndATileInTiles() {
        var entered = 0
        compose.setContent {
            VaultTheme {
                var mode by remember { mutableStateOf(VaultViewMode.List) }
                VaultGrid(
                    items = emptyList(),
                    thumbnailFor = { null },
                    onOpen = {},
                    selected = emptySet(),
                    onBeginSelection = {},
                    onToggleSelection = {},
                    onSelectAll = {},
                    onClearSelection = {},
                    onDeleteSelected = {},
                    onExportSelected = {},
                    onImport = {},
                    folders = listOf(
                        FolderView(VaultFolder(ByteArray(16) { 3 }, null, "Trips"), 7),
                    ),
                    onOpenFolder = { entered++ },
                    destination = VaultDestination.Notes,
                    viewMode = mode,
                    onViewMode = { mode = it },
                    drawerState = DrawerState(DrawerValue.Closed),
                    drawerContent = {},
                    vaultName = "Vault",
                )
            }
        }

        // List: glyph, name, bare count — and the row enters the folder.
        compose.onNodeWithText("Trips").assertIsDisplayed()
        compose.onNodeWithText("7").assertIsDisplayed()
        compose.onNodeWithText("Trips").performClick()
        compose.waitForIdle()
        assertEquals("the folder row did not enter the folder", 1, entered)

        // Tiles: the tile spells its count out, which is how it has always read.
        compose.onNodeWithContentDescription(ctx.getString(VaultViewMode.Tiles.actionLabel)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Trips").assertIsDisplayed()
        compose.onNodeWithText("7 items").assertIsDisplayed()
        compose.onNodeWithText("Trips").performClick()
        compose.waitForIdle()
        assertEquals("the folder tile did not enter the folder", 2, entered)
    }

    /**
     * ***A level of folders alone still offers the toggle.***
     *
     * The band used to require items, which was right when it held only the sort control and
     * wrong the moment it held a view toggle: those folders are exactly what changes shape.
     * Found by `aFolderIsARowInListAndATileInTiles` failing.
     */
    @Test
    fun aLevelOfFoldersAloneStillOffersTheToggle() {
        compose.setContent {
            VaultTheme {
                var mode by remember { mutableStateOf(VaultViewMode.List) }
                VaultGrid(
                    items = emptyList(),
                    thumbnailFor = { null },
                    onOpen = {},
                    selected = emptySet(),
                    onBeginSelection = {},
                    onToggleSelection = {},
                    onSelectAll = {},
                    onClearSelection = {},
                    onDeleteSelected = {},
                    onExportSelected = {},
                    onImport = {},
                    folders = listOf(
                        FolderView(VaultFolder(ByteArray(16) { 4 }, null, "Admin"), 2),
                    ),
                    destination = VaultDestination.Notes,
                    viewMode = mode,
                    onViewMode = { mode = it },
                    drawerState = DrawerState(DrawerValue.Closed),
                    drawerContent = {},
                    vaultName = "Vault",
                )
            }
        }
        compose.onNodeWithContentDescription(ctx.getString(VaultViewMode.Tiles.actionLabel)).assertIsDisplayed()
        assertTrue(
            "an ordering control is offered over no items",
            compose.onAllNodesWithText(ctx.getString(VaultSort.Default.label)).fetchSemanticsNodes().isEmpty(),
        )
    }

    /** The mode is per destination: switching one does not switch another. */
    @Test
    fun theModeIsPerDestination() {
        grid(items = listOf(item(PHOTO), item(DOC)))

        compose.onNodeWithContentDescription(ctx.getString(VaultViewMode.List.actionLabel)).performClick()
        compose.waitForIdle()

        // Files was never touched, so it is still in its default — list — and offers tiles.
        compose.onNodeWithText(ctx.getString(VaultDestination.Files.label)).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(ctx.getString(VaultViewMode.Tiles.actionLabel)).assertIsDisplayed()

        // Media kept the list it was switched to, so it offers tiles as well.
        compose.onNodeWithText(ctx.getString(VaultDestination.Media.label)).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(ctx.getString(VaultViewMode.Tiles.actionLabel)).assertIsDisplayed()
    }

    /** The toggle is withheld while selecting — the sort control's own condition. */
    @Test
    fun theToggleIsAbsentWhileSelecting() {
        grid(selecting = true)
        assertTrue(
            "the toggle is drawn over a selection",
            compose.onAllNodesWithContentDescription(ctx.getString(VaultViewMode.List.actionLabel))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    /** And in the trash, which is not a destination and has no mode. */
    @Test
    fun theToggleIsAbsentInTheTrash() {
        grid(trash = true)
        VaultViewMode.entries.forEach { mode ->
            assertTrue(
                "the toggle is drawn over the trash, which has no mode",
                compose.onAllNodesWithContentDescription(ctx.getString(mode.actionLabel))
                    .fetchSemanticsNodes().isEmpty(),
            )
        }
    }

    // --- empty destinations -----------------------------------------------------

    /**
     * An empty destination says what belongs there.
     *
     * A vault of photographs alone is an ordinary vault and three of its four
     * destinations are empty; none of them may read as an error.
     */
    @Test
    fun everyEmptyDestinationNamesWhatBelongsInIt() {
        // One `setContent` and then the bar, because a test rule accepts content once —
        // and tapping through the bar is how a user reaches these anyway.
        grid(items = emptyList())

        listOf(
            VaultDestination.Media to "This vault is empty.",
            VaultDestination.Files to "No files yet.",
            VaultDestination.Notes to "No notes yet.",
            VaultDestination.Memos to "No recordings yet.",
        ).forEach { (destination, heading) ->
            if (destination != VaultDestination.Media) {
                compose.onNodeWithText(ctx.getString(destination.label)).performClick()
                compose.waitForIdle()
            }
            compose.onNodeWithText(heading).assertIsDisplayed()
        }
    }

    /**
     * Every empty destination offers the one act that fills it, and the button runs that
     * destination's own way in — the header's `+` on Media and Files, the floating button's
     * write and record actions on Notes and Memos.
     */
    @Test
    fun everyEmptyDestinationOffersItsOwnAction() {
        val taps = mutableListOf<String>()
        grid(items = emptyList(), taps = taps)

        listOf(
            Triple(VaultDestination.Media, "Add media", "import"),
            Triple(VaultDestination.Files, "Add files", "import"),
            Triple(VaultDestination.Notes, "Create note", "note"),
            Triple(VaultDestination.Memos, "Create memo", "memo"),
        ).forEach { (destination, label, action) ->
            if (destination != VaultDestination.Media) {
                compose.onNodeWithText(ctx.getString(destination.label)).performClick()
                compose.waitForIdle()
            }
            taps.clear()
            compose.onNodeWithText(label).assertIsDisplayed().performClick()
            compose.waitForIdle()
            assertEquals("${destination.label}'s \"$label\"", listOf(action), taps)
        }
    }
}
