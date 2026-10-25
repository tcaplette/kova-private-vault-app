package com.app.vault.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.app.vault.FolderView
import com.app.vault.GridPreference
import com.app.vault.OriginalRemoval
import com.app.vault.R
import com.app.vault.localWords
import com.app.vault.ThumbRef
import com.app.vault.TrashPurge
import com.app.vault.NoteMarkdown
import com.app.vault.VaultNote
import com.app.vault.VaultItem
import com.app.vault.VaultKind
import com.app.vault.MediaFilter
import com.app.vault.VaultDestination
import com.app.vault.VaultSort
import com.app.vault.VaultViewMode
import com.app.vault.ui.theme.Radius
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import com.app.vault.ui.theme.VaultMono
import com.app.vault.words
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The vault's main screen — `DESIGN.md` §7.2.
 *
 * Adaptive square cells, 2dp gutter, edge to edge, and **no cards**. A photo
 * grid is content, and chrome between the tiles is chrome between the
 * photographs. This is also the direct answer to the "rounded cards floating on
 * grey" the plan rejects.
 *
 * Every value here comes from the theme. There is no local dimension or colour
 * in this file, which is what §2 of that document requires.
 */
// `search-at-the-top`. `WindowInsets.isImeVisible` is the supported way to ask
// whether the keyboard is up, and it carries this marker.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VaultGrid(
    items: List<VaultItem>,
    thumbnailFor: (ThumbRef) -> android.graphics.Bitmap?,
    onOpen: (VaultItem) -> Unit,
    /**
     * The items currently selected, by `itemId` — P2-11.
     *
     * Empty means there is no selection mode: the bar is the ordinary one and a
     * tap opens. Owned by the activity, because back ends a selection and back is
     * handled there.
     */
    selected: Set<String>,
    /** Long-press on the ordinary grid. Begins a selection holding that one item. */
    onBeginSelection: (VaultItem) -> Unit,
    /** Tap while selecting. Adds or removes the one item. */
    onToggleSelection: (VaultItem) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    /**
     * P2-10, scoped by P2-11. The confirmation is raised for the whole selection;
     * a single-item delete is a selection of one.
     */
    onDeleteSelected: () -> Unit,
    /** P1-13's export path, scoped to the selection — P2-11. */
    onExportSelected: () -> Unit,
    /**
     * P4-2's send flow. Null when the selection cannot be shared, and the control
     * is then **absent** rather than disabled — `DESIGN.md` §7.19 and the viewer
     * menu's rule. That is the case for a selection of two or more, because a
     * container carries one item, and in the trash. Kind is no longer one of the
     * reasons: every kind the vault holds can be sealed.
     */
    onShareSelected: (() -> Unit)? = null,
    onImport: () -> Unit,
    /**
     * P2-12. Draw the trash rather than the vault: same tiles, same mosaic, same
     * selection mode, given the trashed rows instead of the live ones.
     *
     * A second composable for it would be a second copy of the mosaic, which is a
     * second place for the mosaic to drift. What changes is the title, the empty
     * state, the leading action, the absence of import, and the two actions a
     * selection offers.
     */
    trash: Boolean = false,
    /** P2-12. Takes the selection back out of the trash. Trash only. */
    onRestoreSelected: () -> Unit = {},
    /** P2-12. Leaves the trash. The visible twin of back, in the leading slot. */
    onLeaveTrash: () -> Unit = {},
    /**
     * The folders at the level being viewed — `folder-system`.
     *
     * Drawn above the items and never interleaved with them, so the eye finds one
     * region of places and one region of things. Empty at a level that has none,
     * which is every level of a vault that uses no folders.
     */
    folders: List<FolderView> = emptyList(),
    /**
     * The name of the folder being viewed, or null at the home screen.
     *
     * The list is scoped by the caller; this is what the bar says and what the
     * empty state means. Same mechanism as [trash]: one composable, another state.
     */
    folderName: String? = null,
    /** The item a folder's tile shows, or null where it has nothing to show. */
    coverFor: (FolderView) -> VaultItem? = { null },
    /** Walks into a folder. */
    onOpenFolder: (FolderView) -> Unit = {},
    /** Opens a folder's own surface — rename, move, colour, cover, delete. */
    onFolderSurface: (FolderView) -> Unit = {},
    /**
     * Opens a note's own sheet — pin, move, colour, delete.
     *
     * The folder's surface one row down, and reached differently for a reason the
     * grid decides: a folder's is a long-press, and a long-press on an *item* has
     * begun a selection since P2-11. So a note's tile carries a three-dot mark and
     * the gesture is untouched. Withheld in the trash, where rename and delete are
     * already withheld — a trashed note is waiting out its window, not being
     * organised.
     */
    onNoteOptions: (VaultItem) -> Unit = {},
    /** Climbs one level — back's visible twin while inside a folder. */
    onLeaveFolder: () -> Unit = {},
    /** P2-13. Sends the selection to the move surface. */
    onMoveSelected: () -> Unit = {},
    /** Takes the selection out of the folder being viewed, onto the home screen. Null at the home screen. */
    onRemoveFromFolder: (() -> Unit)? = null,
    /**
     * P2-15. The query, empty when nothing is typed — `search-at-the-bottom`, placed by
     * `search-at-the-top`.
     *
     * **Not nullable any more, because search is not a mode.** Null used to mean "the
     * search is closed", and it drove four branches: the title, the leading control, the
     * bar's actions and whether the field was drawn. The field is always drawn now, so
     * there is nothing to open and nothing to close.
     *
     * `""` is open-and-empty and is not the same as null: an open search with no
     * question typed shows everything, and a closed one has no field at all. The
     * list is scoped by the caller, as [folderName] is — this is what the bar
     * says, what the field holds, and what the empty state means.
     */
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    /** P2-15. Opens the search from the grid's bar. */
    /** P2-15. Leaves the search — back's visible twin while one is open. */
    /**
     * P2-16. The ordering the caller sorted [items] by.
     *
     * The grid does not sort — `MainActivity` does, once, so the viewer's paging
     * and this list cannot disagree. This is here so the headings are about the
     * date the list is actually in, and so a name ordering draws flat.
     */
    sort: VaultSort = VaultSort.Default,
    /** Chooses the ordering — see [SortControl]. */
    onSort: (VaultSort) -> Unit = {},
    /**
     * Which destination is being drawn — `vault-destinations`.
     *
     * Owned by the activity for [drawerState]'s reason and no other: back returns to
     * [VaultDestination.Media] before it leaves the vault, so the one `BackHandler` has to
     * be able to read it.
     */
    destination: VaultDestination = VaultDestination.Default,
    onDestination: (VaultDestination) -> Unit = {},
    /** Which of Media's chips is selected. Meaningless off Media, and not drawn there. */
    mediaFilter: MediaFilter = MediaFilter.Default,
    onMediaFilter: (MediaFilter) -> Unit = {},
    /**
     * The shape this destination's list is drawn in — `destination-view-modes`.
     *
     * Owned by the activity beside the ordering, and persisted: it is a device preference
     * rather than a view of an unlocked vault, so unlike [destination] it outlives a lock.
     *
     * Defaulted so the tests and previews that build a grid without one get each
     * destination's own default, which is what the app draws today.
     */
    viewMode: VaultViewMode = destination.defaultViewMode,
    onViewMode: (VaultViewMode) -> Unit = {},
    /**
     * P2-7. Owned by the activity so its `BackHandler` can read and close it —
     * back must shut the drawer before it touches anything underneath.
     */
    drawerState: DrawerState,
    /**
     * Whether the floating action button's four actions are showing —
     * `settings-screen-and-fab`.
     *
     * Owned by the activity for [drawerState]'s reason and no other: back collapses
     * this before it closes the drawer, so the activity's one `BackHandler` has to
     * be able to read it and clear it.
     */
    fabExpanded: Boolean = false,
    onFabExpandedChange: (Boolean) -> Unit = {},
    /** P2-17. Opens the camera screen. Reached only where [cameraAvailable]. */
    onCamera: () -> Unit = {},
    /**
     * Whether the device has a camera — `unavailable-capability-states`. Without one the
     * Camera action is still drawn, in its place, unusable, and its label says why.
     */
    cameraAvailable: Boolean = true,
    /**
     * `voice-memo`. Opens the recording screen. Always present: every device this
     * app runs on has a microphone, and a device that refuses one says so on the
     * screen rather than by the action being missing here.
     */
    onVoiceMemo: () -> Unit = {},
    /** P2-21. Opens an empty note editor. Always present — it needs no hardware. */
    onNewNote: () -> Unit = {},
    /** `folder-system`. Creates a folder at the level being viewed. */
    onNewFolder: () -> Unit = {},
    /**
     * Opens the settings of the destination being drawn — the header's `ic_tune`, beside
     * the drawer. Defaulted for the tests that build a grid with no sheet to open.
     */
    onDestinationSettings: (() -> Unit)? = null,
    /**
     * `per-vault-intruder-capture` D7. The open vault's capture store holds captures not yet seen:
     * a badge on the drawer control, the value the vault drawer's Intruder capture row reads.
     */
    capturesUnseen: Boolean = false,
    /** P2-1. The only way into the multi-vault surface, and it needs an open vault. */
    onHome: () -> Unit = {},
    /**
     * The drawer's contents, supplied from above. The grid hosts the drawer but
     * knows nothing about entitlement, billing or concealment; it passes a slot.
     */
    drawerContent: @Composable () -> Unit,
    /**
     * The open vault's name — `home-screen-chrome`.
     *
     * This screen was titled `"Vault"`, a literal, on an app whose whole premise is that
     * you have several and they are named. It falls at the end of the title chain; see
     * the `when` below.
     */
    vaultName: String,
    notice: String? = null,
    /** P1-10. Device warnings, in the same slot import failures use. */
    warnings: List<String> = emptyList(),
    /** P1-11. What the tamper log recorded since the previous unlock. */
    digest: List<String> = emptyList(),
    /** `remove-originals-on-import`. What [OriginalsCard] offers, or null. */
    originals: OriginalRemoval.Offer? = null,
    /** Android's delete dialog is open. */
    removingOriginals: Boolean = false,
    /** The line that replaces the card once Android has removed them. */
    originalsRemoved: Boolean = false,
    onKeepOriginals: () -> Unit = {},
    onRemoveOriginals: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // P2-8. Restored on launch, so the density the user picked survives the
    // session that picked it.
    var density by remember {
        mutableStateOf(
            GridPreference.columns(context)
                ?.let { c -> GridDensity.entries.firstOrNull { it.columns == c } }
                ?: GridDensity.Medium
        )
    }

    // The outer half of the end-edge flip: Compose's drawer opens from the start
    // edge and takes no side parameter, so the whole host is mirrored to put the
    // sheet on the trailing edge. `VaultDrawerSheet` wraps its own content back to
    // Ltr — both halves are required, and applying only this one mirrors every row
    // inside the sheet.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = drawerContent,
            scrimColor = VaultColors.Scrim.copy(alpha = SCRIM_ALPHA),
            modifier = modifier,
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                val selecting = selected.isNotEmpty()
                // `search-at-the-top`. How tall the bottom block measured, in pixels,
                // so the tiles can reserve exactly it. Measured rather than a constant:
                // a constant for a control's height is a constant a later type-scale change
                // makes wrong, and wrong here means the last row hides behind the block.
                var bottomBlock by remember { mutableIntStateOf(0) }
                // `search-at-the-top`. Whether the search field is drawn at all — withheld
                // while selecting and in the trash, the states that carry no search.
                val fieldShown = !selecting && !trash
                // `landscape-rotation`. A window under 480dp tall — Material's compact height,
                // which is a phone held sideways. The destinations move to the leading rail and
                // the whole header band yields to the scroll, not only the field.
                val compactHeight = LocalConfiguration.current.screenHeightDp < COMPACT_HEIGHT_DP
                // Where the destinations are offered at all, in either form: the condition the
                // bottom block has always been drawn under.
                val destinationsShown = !selecting && !trash
                // How tall the field measured, in pixels, and so how far it can yield.
                // Measured, the same rule `bottomBlock` follows and for the same reason.
                var fieldHeight by remember { mutableIntStateOf(0) }
                // `landscape-rotation`. The whole header band's height — field, notices, cards,
                // chips and sort band — measured, for the compact-height yield below.
                var bandHeight by remember { mutableIntStateOf(0) }
                // Whether the field, or the text inside it, has focus. Reset with `fieldShown`
                // so a field that was removed while focused does not return still "focused".
                var fieldFocused by remember(fieldShown) { mutableStateOf(false) }
                // **The field is held whenever it has focus or the query is non-blank** — a
                // filter the user cannot see is a list narrowed for a reason the screen does
                // not give, and a query matching nothing replaces the list, so there would be
                // no scroll to bring the field back.
                val holdField = query.isNotEmpty() || fieldFocused
                // ***The hold has to be escapable, and it was not.***
                //
                // `search-at-the-top` held the field while it had focus and never said how
                // focus is given up — and nothing in the app gave it up. Once the field was
                // tapped it stayed focused for the life of the destination: the keyboard
                // could be dismissed and `holdField` stayed true behind it, so the field was
                // pinned from then on and the yield this change exists for never came back.
                // With the keyboard up there is barely a strip of tiles left to grab, and
                // the button and the bar cover part of that, so the screen read as stuck.
                //
                // Three ways out, which is what every app with a search over a list offers:
                // the keyboard being dismissed by any means, scrolling the list, and tapping
                // the empty ground. All three below.
                val focusManager = LocalFocusManager.current

                // ***Focus follows the keyboard.***
                //
                // **Back is the one this exists for, and it cannot be handled as a back
                // press.** While the IME is showing, Android consumes the first back to hide
                // the keyboard *before* any `BackHandler` runs — so the app's back never
                // fires, and a branch in `MainActivity.dismissTop` would sit there unreached
                // while the field stayed focused behind a keyboard that was already gone.
                // Watching the keyboard rather than the gesture is what actually catches it,
                // and it catches the others for free: the keyboard's own dismiss control, a
                // swipe down on it, and anything else that takes it away.
                //
                // ***Keyed on `fieldFocused`, and it waits for the keyboard to arrive before
                // it watches for it to leave.*** Focus and the IME are not simultaneous —
                // there are frames after the tap where the field has focus and the keyboard
                // has not come up yet. An effect that only tested "focused and no keyboard"
                // would fire in that gap and drop focus the instant the field was tapped.
                // The first `first` is that gap, waited out explicitly; no flag, no
                // remembered previous value, and the whole thing restarts per focus session.
                //
                // Suspends forever where no soft keyboard ever appears — a hardware keyboard
                // attached — which is correct: there is nothing to dismiss, and the scroll
                // and the tap below still put the field down.
                val imeVisible = WindowInsets.isImeVisible
                // One remembered flag, reset with every focus session by the key, and read
                // in composition rather than through a `snapshotFlow`. The flow version was
                // tried first and did not fire on the path that matters: the keyboard went
                // away on back — `ViewCompat` confirmed it — and the collector never woke.
                var keyboardHasBeenUp by remember(fieldFocused) { mutableStateOf(false) }
                LaunchedEffect(fieldFocused, imeVisible) {
                    if (!fieldFocused) return@LaunchedEffect
                    if (imeVisible) keyboardHasBeenUp = true
                    else if (keyboardHasBeenUp) focusManager.clearFocus()
                }
                // How far the field has been scrolled up and out, in pixels: `-fieldHeight..0`.
                //
                // Keyed so it **restarts at zero** — field showing — when the destination
                // changes (each destination keeps its own scroll, so one left scrolled down
                // would otherwise arrive with the field already gone and nothing to say it
                // exists), when the field stops or starts being drawn, and when it becomes
                // held, which is what keeps it at zero for as long as it is.
                var fieldOffset by remember(destination, fieldShown, holdField, compactHeight) {
                    mutableIntStateOf(0)
                }
                // `onPreScroll` and not `onPostScroll`: the field takes its share *before* the
                // tiles move, or the first drag scrolls both and the two visibly race. Down
                // (negative) hides it, up brings it back from wherever the list is. Keyed the
                // same as `fieldOffset`, so it never holds a state that was replaced.
                val fieldScroll = remember(destination, fieldShown, holdField, compactHeight) {
                    object : NestedScrollConnection {
                        override fun onPreScroll(
                            available: Offset,
                            source: NestedScrollSource,
                        ): Offset {
                            // **A drag of the tiles gives the field up.** The Android
                            // convention for an input above a scrolling list, and the
                            // thing that makes the hold escapable: focus goes, the
                            // keyboard goes with it, `holdField` falls to false and the
                            // field resumes yielding on the very next frame.
                            //
                            // `UserInput` only. A programmatic scroll — `scrollToItem`
                            // when a folder is created, a test's `performScrollToNode` —
                            // is not the user putting the field down.
                            if (fieldFocused && source == NestedScrollSource.UserInput) {
                                focusManager.clearFocus()
                            }
                            if ((!fieldShown && !compactHeight) || holdField) return Offset.Zero
                            // `landscape-rotation`. **Sideways, the whole band yields**, not
                            // only the field: under 480dp of height a band that stays leaves
                            // the tiles a strip too short to scroll. The hold above applies to
                            // the band exactly as it applies to the field. Keyed on
                            // `compactHeight` too, so a turn restarts at "band showing".
                            val reach = if (compactHeight) bandHeight else fieldHeight
                            val before = fieldOffset
                            fieldOffset = (before + available.y.roundToInt())
                                .coerceIn(-reach, 0)
                            return Offset(0f, (fieldOffset - before).toFloat())
                        }
                    }
                }
                // `vault-destinations`. **One scroll position per destination**, so
                // leaving Files to glance at Media and coming back returns the user to
                // where they were rather than to the top.
                //
                // Held here rather than in the activity on purpose: a scroll position is
                // a property of a list that is drawn, and hoisting it to `MainActivity`
                // would mean that file holding a measurement it never reads. The level
                // and the query *are* read there — by back and by the chrome — so those
                // two live there and this one does not.
                val listStates = remember { mutableMapOf<VaultDestination, LazyListState>() }
                // ***Keyed on the destination and deliberately NOT on the view mode.***
                // Adding the mode to this key would give each shape its own state, which
                // reads as "changing the shape of a list returns you to the top of it" —
                // and changing the shape of a list is not leaving it. The position carried
                // across is honestly approximate, because item 40 in a mosaic is not item
                // 40 in a row list, but landing near where the user was beats landing at
                // the top. `destination-view-modes` requires it and a device test holds it.
                val listState = listStates.getOrPut(destination) { LazyListState() }
                VaultScaffold(
                    // P2-11. The count *is* the title while selecting — the
                    // outline's "count in the app bar" — and it takes the mono
                    // face so the title does not reflow on every tap.
                    title = when {
                        selecting -> stringResource(R.string.grid_selected, selected.size)
                        trash -> stringResource(R.string.drawer_trash)
                        folderName != null -> folderName
                        // `home-screen-chrome`. **The vault's own name, not the word.**
                        // At the bottom of the chain deliberately: every branch above has
                        // something truer to say about what is on screen, and the name is
                        // the root title rather than an override of them.
                        //
                        // No key is unwrapped to draw it — the name comes out of the
                        // keyring entry, which is the same argument the vault list makes
                        // about showing names without opening anything.
                        else -> vaultName
                    },
                    titleStyle = if (selecting) VaultMono.titleLarge else null,
                    // The grid scrolls its own tiles and sets its own gutters
                    // ([Spacing.gridGutter], not [Spacing.screenInset]), so the
                    // wrapper does neither. The notice and digest lines below still
                    // carry the screen inset themselves, as they always did.
                    scrollable = false,
                    // Close replaces lock for the duration. Locking mid-selection
                    // would discard the selection to do something the user can do
                    // a tap later, and this slot is where back's visible twin
                    // belongs.
                    leadingAction = {
                        when {
                            selecting -> CloseSelectionAction(onClearSelection)
                            // The trash is a screen reached from the grid, so its
                            // leading slot is back — the same slot, holding what
                            // that screen's back actually does.
                            trash -> BackAction(onLeaveTrash)
                            // Same slot, same meaning: back out to the folder that
                            // holds this one, one level at a time. The lock is that
                            // many back presses away, where the home screen is.
                            folderName != null -> BackAction(onLeaveFolder)
                            // The lock used to sit here. Home replaces it: leaving
                            // for the vault list is the thing reached constantly,
                            // and the list is where locking belongs — it is an act
                            // on the keyring, not on the grid you happen to be in.
                            else -> HomeAction(onHome)
                        }
                    },
                    actions = {
                        when {
                            selecting && trash -> TrashSelectionActions(
                                onSelectAll = onSelectAll,
                                onRestore = onRestoreSelected,
                                onPurge = onDeleteSelected,
                            )
                            selecting -> SelectionActions(
                                onSelectAll = onSelectAll,
                                onMove = onMoveSelected,
                                onRemoveFromFolder = onRemoveFromFolder,
                                onDelete = onDeleteSelected,
                                onExport = onExportSelected,
                                onShare = onShareSelected,
                            )
                            // Nothing in the trash's ordinary bar: import belongs
                            // to the vault, and the settings drawer is where this
                            // screen was reached from.
                            trash -> Unit
                            else -> GridActions(
                                // `file-import`. The `+` belongs to the destination
                                // under it, not to the vault — see [GridActions].
                                destination = destination,
                                onImport = onImport,
                                onNewFolder = onNewFolder,
                                onDestinationSettings = onDestinationSettings,
                                onDrawer = { scope.launch { drawerState.open() } },
                                intruderWaiting = capturesUnseen,
                            )
                        }
                    },
                    // The button is absent in three states, and absent means not
                    // drawn — the idiom every hardware-gated control here uses.
                    //
                    // **Selecting:** its four actions have nothing to do with a
                    // selection, and a 56dp circle over the tiles being picked
                    // would cover the ones in the corner.
                    // **The trash:** one of the four *is* the trash, and a row
                    // that arrives where you already are is a dead end.
                    // **The viewer:** not this screen at all.
                    //
                    // A folder and an open search both keep it: all four
                    // actions still mean exactly what they mean on the whole vault.
                    overlay = {
                        if (!selecting && !trash) {
                            // The scrim, first in the box so the block below draws over
                            // it. Without it a tap outside the expanded set would land on
                            // a tile and open an item the user was dismissing.
                            // `indication = null`: it is a dismiss target, not a control.
                            if (fabExpanded) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            VaultColors.Scrim.copy(alpha = FAB_SCRIM_ALPHA),
                                        )
                                        .clickable(
                                            interactionSource = remember {
                                                MutableInteractionSource()
                                            },
                                            indication = null,
                                        ) { onFabExpandedChange(false) },
                                )
                            }
                            // `search-at-the-top`. **One bottom block, measured** — the
                            // button above the destination bar. The button keeps its corner
                            // and the bar spans the width under it; one column is what
                            // guarantees the button sits above the bar rather than on it,
                            // without a constant for the bar's height that a later style
                            // change would make wrong. The search field is not in it: it is
                            // at the top of the content, where the keyboard cannot reach it.
                            //
                            // ***No `imePadding` here, and that is the fix rather than
                            // the omission.*** This block carried one, and it was the
                            // keyboard being paid for twice: `VaultScaffold` sets
                            // `contentWindowInsets = WindowInsets.safeDrawing`, which is
                            // system bars ∪ cutout ∪ **IME**, and this column lives inside
                            // the padding that produces. So the block already rode above
                            // the keyboard, and `imePadding` lifted it a second
                            // keyboard-height above it.
                            //
                            // What that cost: the block ran out of room, and `Column`
                            // measures in order and gives the last child what is left —
                            // which was **nothing**. `VaultDestinationBar` was laid out at
                            // zero height while still sitting in the semantics tree, so
                            // with the keyboard open the destinations were invisible and
                            // untappable while still being *there* — "present and inert",
                            // which `grid-fab` forbids in as many words. The grid above
                            // lost a keyboard-height of viewport with it.
                            //
                            // Two device tests failed on exactly this and neither said
                            // so: `NonMediaScreenTest.aDestinationKeepsTheQueryItWasLeftWith`
                            // tapped a zero-height bar and nothing happened, and
                            // `SearchDeviceTest.typingNarrowsTheGridAndClearingRestoresIt`
                            // could not see a tile the shrunken viewport had pushed below
                            // the fold. The insets rule `ScreenChromeTest` states — insets
                            // are decided in `VaultScaffold` and nowhere else — is the rule
                            // this broke, one composable down from where it is written.
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .onSizeChanged { size -> bottomBlock = size.height }
                                    // `landscape-rotation`. Upright the bar sits under the button
                                    // and is its floor; sideways the button is alone, so it keeps
                                    // its inset from the bottom edge as it does from the side.
                                    // After `onSizeChanged`, so the clearance includes it.
                                    .padding(bottom = if (compactHeight) Spacing.fabInset else 0.dp),
                                horizontalAlignment = Alignment.End,
                                // Between the button and the bar. They are two
                                // controls, not a bar with something stuck to it.
                                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                            ) {
                                VaultFab(
                                    expanded = fabExpanded,
                                    onExpandedChange = onFabExpandedChange,
                                    onCamera = onCamera,
                                    cameraAvailable = cameraAvailable,
                                    onVoiceMemo = onVoiceMemo,
                                    onNewNote = onNewNote,
                                )
                                // `vault-destinations`. **Last in the block, so it is
                                // lowest on the screen** — the items clear the button and
                                // the bar together, out of the one measured height this
                                // column already reports. No new constant, and no second
                                // thing for a type-scale change to make wrong.
                                //
                                // The block moves as one above the keyboard, on the
                                // scaffold's insets alone — see the note on the column.
                                //
                                // Withheld while selecting and in the trash — a selection
                                // acts on the list beneath it and the trash has no
                                // destination.
                                //
                                // `landscape-rotation`. **Not in a window under 480dp tall**:
                                // there the rail carries the four entries and this block is the
                                // button alone, still measured, so the last row still clears it.
                                if (!compactHeight) {
                                    VaultDestinationBar(
                                        current = destination,
                                        onChoose = onDestination,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    },
                ) {
                    // `search-at-the-top`. **The field yields to the scroll; nothing else does.**
                    // (`landscape-rotation`: sideways, the whole header band yields with it.)
                    // The box clips, so the field can leave under the top bar, and holds the
                    // connection, so the tiles' scroll reaches it before the tiles move.
                    // `landscape-rotation`. **Sideways, the destinations are on the leading edge.**
                    // In a window under 480dp tall the bottom bar is dropped from the block below
                    // and the same four entries stand here instead — see [VaultDestinationRail].
                    // Withheld under exactly the bar's conditions, so while withheld the content
                    // takes the whole width. The box after it takes what the rail leaves: a `Row`
                    // measures its children in order, so `fillMaxSize` there is the remainder.
                    Row(Modifier.fillMaxSize()) {
                        if (compactHeight && destinationsShown) {
                            VaultDestinationRail(
                                current = destination,
                                onChoose = onDestination,
                                modifier = Modifier.fillMaxHeight(),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds()
                                .nestedScroll(fieldScroll)
                                // **The second way out of the hold: tap the empty ground.**
                                // `detectTapGestures` waits for a down nothing else took, so a
                                // tile, a chip, the sort control and the field itself all keep
                                // their taps — this sees only the taps that would otherwise
                                // have landed on nothing at all, which is exactly the gesture
                                // a user makes to put a field down.
                                .pointerInput(Unit) {
                                    detectTapGestures { focusManager.clearFocus() }
                                },
                        ) {
                            // ***Taller than the box by exactly what has been scrolled away, and
                            // placed that far up.*** The bottom edge therefore never moves, so no
                            // gap opens above the bottom block and the last row stays clear of it
                            // in every state. A column a *fixed* field-height taller would be
                            // clipped by that much at the foot while the field is showing.
                            //
                            // Read in the layout phase, not in composition: a scroll changes this
                            // every frame and nothing here needs to recompose for it.
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .layout { measurable, constraints ->
                                        val hidden = -fieldOffset
                                        val height = constraints.maxHeight + hidden
                                        val placeable = measurable.measure(
                                            constraints.copy(minHeight = height, maxHeight = height)
                                        )
                                        layout(placeable.width, constraints.maxHeight) {
                                            placeable.place(0, -hidden)
                                        }
                                    },
                            ) {
                                // `landscape-rotation`. **The header band, as one measured piece.**
                                // Everything above the items is in here, so a sideways phone can
                                // yield all of it to the scroll by its measured height — see
                                // `fieldScroll`. Upright only the field yields, as it always has;
                                // the wrapper changes no layout, it only reports a height.
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onSizeChanged { size -> bandHeight = size.height },
                                ) {
                                    // `search-at-the-top`. **First in the column** — above the notice
                                    // lines, the originals card, the warnings, the digest, Media's chips
                                    // and the sort band. Withheld exactly where it always was: while
                                    // selecting, and in the trash.
                                    if (fieldShown) {
                                        SearchField(
                                            query = query,
                                            onQueryChange = onQueryChange,
                                            modifier = Modifier
                                                .onSizeChanged { size -> fieldHeight = size.height }
                                                .onFocusChanged { fieldFocused = it.hasFocus },
                                        )
                                    }
                                    // Import failures first — they are about something the user just did.
                                    // Device warnings sit below, in the same slot and the same weight: both
                                    // are statements, neither blocks anything (P1-10).
                                    listOfNotNull(notice).forEach { line ->
                                        Text(
                                            text = line,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = VaultColors.Error,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = Spacing.screenInset, vertical = Spacing.sm),
                                        )
                                    }
                                    // `remove-originals-on-import`. Below the failure line and above the
                                    // device warnings: it is about the same batch the failure line is.
                                    OriginalsCard(
                                        offer = originals,
                                        removing = removingOriginals,
                                        onKeep = onKeepOriginals,
                                        onRemove = onRemoveOriginals,
                                    )
                                    // Where the card was. `OnSurfaceVariant`, the digest's colour: it
                                    // reports something done, not something wrong.
                                    if (originalsRemoved) {
                                        Text(
                                            text = stringResource(OriginalRemoval.REMOVED),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = VaultColors.OnSurfaceVariant,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = Spacing.screenInset, vertical = Spacing.sm),
                                        )
                                    }
                                    warnings.forEach { line ->
                                        Text(
                                            text = line,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = VaultColors.Error,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = Spacing.screenInset, vertical = Spacing.sm),
                                        )
                                    }
                                    // The digest last, same slot and same weight. `OnSurfaceVariant` and not
                                    // `Error`, because these are statements of what happened — including the
                                    // tampering line, which carries its meaning in words and would say the
                                    // same thing in any colour (`DESIGN.md` §6.4).
                                    digest.forEach { line ->
                                        Text(
                                            text = line,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = VaultColors.OnSurfaceVariant,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = Spacing.screenInset, vertical = Spacing.sm),
                                        )
                                    }
                                    // Above the first tile row, on the surface it orders. Absent
                                    // with nothing to order, absent while selecting — that bar owns
                                    // the surface — and absent in the trash, which is ordered by
                                    // when things were deleted and not by this.
                                    // `vault-destinations`. Media's chips, in the sort control's band
                                    // and withheld wherever it is withheld — while selecting, because
                                    // that bar owns the surface, and in the trash, which holds every kind
                                    // at once and has no destination to be narrowed within.
                                    //
                                    // Drawn even with nothing to show, unlike the sort control: an empty
                                    // Photos is an answer to the question the chip asked, and a row that
                                    // vanished when it returned nothing would leave no way back to All.
                                    if (destination == VaultDestination.Media && !selecting && !trash) {
                                        MediaFilterChips(current = mediaFilter, onChoose = onMediaFilter)
                                    }
                                    // ***The band is drawn for folders as well as for items.***
                                    //
                                    // It used to require `items.isNotEmpty()`, which was right when the band
                                    // held only the sort control — an ordering over nothing orders nothing.
                                    // The view toggle changed that: a level holding folders and no items is
                                    // an ordinary state, the folders in it are exactly what changes shape,
                                    // and withholding the band there would leave the user standing in front
                                    // of the thing they wanted to reshape with no way to do it.
                                    //
                                    // The sort control keeps its own condition inside — see below.
                                    if ((items.isNotEmpty() || folders.isNotEmpty()) && !selecting && !trash) {
                                        SortControl(
                                            sort = sort,
                                            onChoose = onSort,
                                            // Absent with nothing to order. A folder region is ordered by
                                            // name and says so nowhere, so an ordering control over folders
                                            // alone would claim to change something it does not.
                                            showSort = items.isNotEmpty(),
                                            // `destination-view-modes`. The band's other end. Drawn under
                                            // exactly this condition and no second one of its own — a
                                            // toggle offered while selecting would act on the surface that
                                            // bar owns, and the trash has no mode to be in.
                                            trailing = {
                                                ViewModeToggle(current = viewMode, onChoose = onViewMode)
                                            },
                                        )
                                    }
                                }
                                if (items.isEmpty() && folders.isEmpty()) {
                                    when {
                                        trash -> EmptyTrash()
                                        // P2-15, ahead of the folder for the title's reason: a
                                        // search inside an empty result is about the query,
                                        // not about the folder being empty.
                                        query.isNotEmpty() -> EmptySearch()
                                        folderName != null -> EmptyFolder()
                                        else -> EmptyDestination(
                                            destination = destination,
                                            onImport = onImport,
                                            onNewNote = onNewNote,
                                            onVoiceMemo = onVoiceMemo,
                                            mediaFilter = mediaFilter,
                                            // The block at the foot of the screen covers the lower
                                            // part of this area, so centring in it lands below the
                                            // centre the user sees. Mirrored: the same distance
                                            // above it instead.
                                            bottomBlock = when {
                                                bottomBlock > 0 -> with(LocalDensity.current) {
                                                    bottomBlock.toDp()
                                                }
                                                else -> Spacing.fabClearance
                                            },
                                        )
                                    }
                                } else {
                                    Tiles(
                                        items = items,
                                        // ***The trash is drawn as the mosaic, whatever destination
                                        // the user opened it from.*** It holds every kind at once and
                                        // the bar is not over it, so it has no destination to be on —
                                        // and a trash entered from Files that showed only documents
                                        // would hide the photographs the user came to restore.
                                        destination = if (trash) VaultDestination.Media else destination,
                                        // The trash is the mosaic whatever the destination's mode:
                                        // it is not a destination, has no toggle, and holds every kind
                                        // at once — see the note above.
                                        viewMode = if (trash) VaultViewMode.Tiles else viewMode,
                                        listState = listState,
                                        // `folder-system`. Drawn in the same scroll container,
                                        // ahead of the first date heading: one list, one
                                        // density, one set of measurements.
                                        folders = folders,
                                        coverFor = coverFor,
                                        onOpenFolder = onOpenFolder,
                                        onFolderSurface = onFolderSurface,
                                        onNoteOptions = if (trash) null else onNoteOptions,
                                        // The trash is ordered by when things were deleted, not
                                        // by when they were taken, so a capture-date heading
                                        // over it would be a heading about the wrong date.
                                        //
                                        // P2-16 adds the second case, for the same reason: a
                                        // name ordering has no date to be a heading about.
                                        flat = trash || sort.grouping == VaultSort.Grouping.None,
                                        sort = sort,
                                        thumbnailFor = thumbnailFor,
                                        onOpen = onOpen,
                                        selected = selected,
                                        onBeginSelection = onBeginSelection,
                                        onToggleSelection = onToggleSelection,
                                        // The last row must be scrollable clear of the bottom
                                        // block — the button above the destination bar.
                                        //
                                        // The block's **measured** height, not a constant: a
                                        // constant for a control's height is one a later
                                        // type-scale change makes wrong, and wrong here means the
                                        // last row of photographs sits behind the block. Falls
                                        // back to the button's clearance for the frame before the
                                        // first measurement arrives.
                                        //
                                        // Zero where the block is not drawn — dead space under the
                                        // trash's final row would be padding for controls that are
                                        // not there.
                                        bottomClearance = when {
                                            selecting || trash -> 0.dp
                                            bottomBlock > 0 -> with(LocalDensity.current) {
                                                bottomBlock.toDp()
                                            }
                                            else -> Spacing.fabClearance
                                        },
                                        density = density,
                                        onDensityChange = {
                                            if (it != density) {
                                                density = it
                                                GridPreference.setColumns(context, it.columns)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One folder in the grid — `folder-system`.
 *
 * **A square, a name and a count**, in that order down the cell. The name is drawn
 * *below* the tile on the app's own surface and never on top of the fill: a label over
 * a colour needs a contrast pair per colour per scheme, and a folder's colour carries
 * no meaning worth that (§6.4 — the name is what identifies the folder, always).
 *
 * **The fill is the folder's colour and the image is its cover.** A folder with a
 * cover shows it; one without shows the fill and a folder glyph, so an empty folder is
 * still obviously a folder rather than a coloured hole in the grid.
 *
 * **Tap enters, long-press opens the folder's own surface** — the item tile's gesture
 * pair one cell along, with the same rule: a tap never does both.
 */
@Composable
private fun FolderTile(
    folder: FolderView,
    cover: VaultItem?,
    thumbnailFor: (ThumbRef) -> android.graphics.Bitmap?,
    onOpen: () -> Unit,
    onSurface: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap = cover?.thumb?.let(thumbnailFor)

    Column(
        modifier = modifier.combinedClickable(
            onClick = onOpen,
            onLongClick = onSurface,
            onLongClickLabel = stringResource(R.string.folder_options),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(VaultColors.folder(folder.colour)),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    // The name below says which folder this is; the picture is
                    // this folder's contents, not a second label.
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // A cover is a photograph, and a photograph in a folder's place reads as
                // a photograph. The glyph sits on its own scrim so it holds on any
                // picture, light or dark; the name below still identifies the folder.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Spacing.xs)
                        .size(28.dp)
                        .background(
                            VaultColors.Scrim.copy(alpha = 0.6f),
                            RoundedCornerShape(Spacing.xs),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_folder),
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = null,
                    tint = VaultColors.OnSurfaceVariant,
                )
            }
        }
        Text(
            text = folder.name,
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        Text(
            // Mono for the grid's other figures' reason: a count that reflows the
            // row as it changes is a row that moves while the user reads it.
            text = pluralStringResource(R.plurals.item_count, folder.count, folder.count),
            style = VaultMono.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * The ordering in effect, and the way to change it.
 *
 * ***It was a settings row, then a screen.*** `settings-screen-and-fab` put sort on
 * the settings screen, two navigations from the tiles it orders; P2-16 moved it here
 * but kept a full screen behind it. Sort is not a setting and not a destination: it
 * is a view control over the list in front of you, and its whole result is on screen
 * the moment it changes. Opening a menu in place lets the user watch the tiles
 * reorder; a screen hid that behind a navigation and a back press.
 *
 * **Here and not in the bar.** That row is four controls at its measured 320dp
 * ceiling — a fifth does not fit, which is the same wall that sent settings through
 * the drawer. This slot costs the header nothing.
 *
 * **Leading edge**, because that is where reading starts.
 *
 * **Named, not a bare glyph.** The label is the ordering in effect, so it answers
 * "what order is this in" without being opened — the idiom the settings row it
 * replaces already used, and the reason a lone icon would be worse here.
 *
 * **The active row is marked in words**, `DESIGN.md` §6.4: a tick that is only a
 * tint puts meaning on colour alone.
 *
 * **The two name rows carry a second line.** Alphabetical ordering is not numeric,
 * so `photo 10` precedes `photo 2` — a surprise that reads as a bug, said on the
 * rows it applies to at the moment of the choice. Camera and screenshot names are
 * zero-padded and unaffected; a name typed by hand may not be.
 */
@Composable
private fun SortControl(
    sort: VaultSort,
    onChoose: (VaultSort) -> Unit,
    /**
     * The view toggle, at this row's trailing edge — `destination-view-modes`.
     *
     * **A slot rather than two parameters**, because this control knows nothing about view
     * modes and should not start: it owns a band, and the band has two ends. Empty in the
     * builds and tests that draw a grid with no toggle.
     *
     * It is here and not in the header because `vault-grid` forbids a fifth header control
     * and forbids an overflow menu. This row is already full-width with its button at the
     * start, already inset to the screen margin, and already withheld in exactly the two
     * states a view toggle must be withheld in.
     */
    trailing: @Composable () -> Unit = {},
    /**
     * Whether the ordering control itself is drawn — `destination-view-modes`.
     *
     * False at a level holding folders and no items: the band is still there, because the
     * view toggle applies to the folders, but an ordering over no items would be a control
     * claiming to change something it cannot.
     */
    showSort: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenInset),
    ) {
        Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
        if (!showSort) return@Box
        TextButton(onClick = { open = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_sort),
                // The label beside it says what this does; null so a screen reader
                // reads the control once rather than twice.
                contentDescription = null,
                tint = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(end = Spacing.sm),
            )
            Text(
                text = stringResource(sort.label),
                style = MaterialTheme.typography.labelLarge,
                color = VaultColors.OnSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = VaultColors.Surface,
        ) {
            VaultSort.entries.forEach { entry ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = stringResource(entry.label),
                                style = MaterialTheme.typography.bodyLarge,
                                color = VaultColors.OnSurface,
                            )
                            val note = when {
                                entry == sort -> stringResource(R.string.sort_current)
                                entry.grouping == VaultSort.Grouping.None ->
                                    stringResource(R.string.sort_alphabetical)
                                else -> null
                            }
                            if (note != null) {
                                Text(
                                    text = note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VaultColors.OnSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        open = false
                        onChoose(entry)
                    },
                )
            }
        }
    }
}

/** The drawer's scrim. Dark enough to say "the grid is not the live surface". */
private const val SCRIM_ALPHA = 0.6f

/**
 * Whether this device can offer biometric unlock, why not, and whether it is on —
 * P1-9, `unavailable-capability-states`.
 *
 * Seven states rather than three, because every case Android can report is a
 * different true sentence, and the control is drawn in all of them. Decided by
 * `BiometricWrap.state` from two class results; which biometric it belongs to is
 * [BiometricLayout]'s, decided separately by `BiometricWrap.layout`.
 */
enum class BiometricState {
    On, Off, NotSetUp, NotSecureEnough, NotAvailableNow, NeedsUpdate, NotSupported;

    /** A strong biometric is enrolled: the switch is drawn. */
    val ready: Boolean get() = this == On || this == Off

    /** The user can fix it in Android's enrolment screen: usable, no switch. */
    val fixable: Boolean get() = this == NotSetUp
}

/**
 * The grid's bar actions — P2-7.
 *
 * Two icons: import and settings. **There is no overflow menu here or anywhere
 * else in the app.**
 *
 * P2-6 put four actions behind a `⋮` because five *text* labels do not fit one
 * bar row on a phone. That was true and it was the wrong trade: it bought width
 * by hiding Add and Lock, the two actions a user reaches for most, behind a glyph
 * that names neither. Icons buy the same width without hiding anything — an icon
 * costs about 48dp where "Fingerprint off" costs about 200dp.
 *
 * So the header is five named controls, all permanently visible: the leading slot
 * (see [HomeAction]) and these four.
 *
 * **Four was the ceiling until `settings-separation`, and this is the fifth.** The
 * row is measured at 320dp: five 48dp targets take 240dp and leave **80dp** for the
 * title, which fits "Vault" at `titleLarge` with room to spare. That measurement was
 * what forced settings through the drawer, and it is spent here deliberately — the
 * vault settings sheet acts on the vault you are looking at, so a control that opens
 * it belongs on the bar above that vault and nowhere else. No target shrinks to buy
 * the room; the title's slack pays for it.
 *
 * That is the whole of the budget. Anything else that wants to live up here goes on
 * [VaultFab], in the drawer, or in the sheet.
 *
 * **"Unvault Everything" is deliberately not here.** It was permanent in this bar
 * until P2-7 and now lives at the top of the settings drawer. It is not
 * destructive — the export screen takes the passphrase before anything leaves —
 * but a permanently visible control invites a tap nobody meant, and this is the
 * one that writes plaintext out of the vault. `DESIGN.md` §6.2 was rewritten to
 * require one tap and no burial rather than permanence; putting it back in this
 * bar is a defect, and `ScreenChromeTest` fails the build if anyone does.
 *
 * The grid therefore holds **no primary-coloured action at all**, which is what
 * `vault-grid` has always required of the slot when export is not sitting in it.
 * Import stays secondary.
 *
 * These are the only icon-only controls in the app besides the back affordance,
 * so each carries a content description — without one, an icon-only header is
 * unreachable to a screen reader.
 */
@Composable
private fun GridActions(
    /**
     * Which destination the header sits above — `file-import`, `DESIGN.md` §7.2a.
     *
     * It decides two things about the `+` and nothing else on this bar: whether it is
     * drawn at all, and what it is called. See [VaultDestination.addAction].
     */
    destination: VaultDestination,
    onImport: () -> Unit,
    onNewFolder: () -> Unit,
    /** The destination's own settings, or null where the header carries none. */
    onDestinationSettings: (() -> Unit)?,
    onDrawer: () -> Unit,
    /** `per-vault-intruder-capture` D7. This vault's unseen captures, as a badge on the drawer. */
    intruderWaiting: Boolean = false,
) {
    // **New vault is deliberately not here — `home-screen-chrome`.** It lived in this bar
    // and it never belonged: creating a vault is a thing you do to the *list*, not inside
    // the vault you happen to be looking at, and this bar is for acting on this vault's
    // contents. It sat on the home screen's bar and here at once, which is one control
    // in two places meaning two different things about where you are.
    //
    // The home screen's bar owns it. Adding it back is a defect.

    // `file-import`. [VaultDestination.addAction] withholds this one on Notes and Memos, because those two are filled by the floating action
    // button's write and record actions on this same screen — a `+` there would open a
    // picker whose results could not land on the destination under it. New folder stays
    // on all four: a folder holds any kind.
    //
    // Absent, never disabled: the rule for a control with nothing to act on here. Only a
    // control the device cannot use is drawn disabled, saying why —
    // `unavailable-capability-states`.
    //
    // The mark is `ic_add` on both destinations that carry one, and that is not
    // `grid-fab`'s two-marks ambiguity — that rule is about two controls on **one
    // screen**, and no two forms of this one are ever drawn at once. The **name** is
    // what differs, because the header is icon-only and the description is the only
    // name this control gets. It is declared on [AddAction] rather than switched on
    // here: a conditional inside a `contentDescription` is a name
    // `ScreenChromeTest.everyHeaderControlIsNamed` cannot read, and that scanner is
    // what keeps an icon-only header reachable.
    val add = destination.addAction
    if (add != null) {
        IconButton(
            onClick = onImport,
            modifier = Modifier.size(Spacing.touchTarget),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = stringResource(add.description),
                // Secondary, never primary: `vault-grid` bars import from the
                // primary colour whether or not export is on this screen.
                tint = VaultColors.OnSurface,
            )
        }
    }

    // `new-folder-in-the-header`. **Beside import, not before it.** Import is the more
    // frequent act, and the two read as a pair: put things in, make somewhere to put them.
    // It was two taps behind the floating button — expand the set, then pick — while every
    // other way of putting something into a vault was one tap up here.
    //
    // Its own mark and never the plus: `grid-fab` forbids two controls on one screen
    // bearing the same mark and opening different things.
    IconButton(
        onClick = onNewFolder,
        modifier = Modifier.size(Spacing.touchTarget),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_create_new_folder),
            contentDescription = stringResource(R.string.folder_new),
            tint = VaultColors.OnSurface,
        )
    }
    // **Between new folder and the drawer**: everything to its left acts on what is here,
    // everything from here rightwards opens somewhere. `ic_tune` and not `ic_settings` —
    // the gear names the install, and this opens the settings of the destination under it.
    val settings = onDestinationSettings
    if (settings != null) {
        IconButton(
            onClick = settings,
            modifier = Modifier.size(Spacing.touchTarget),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_tune),
                contentDescription = stringResource(R.string.grid_destination_settings, stringResource(destination.label)),
                tint = VaultColors.OnSurface,
            )
        }
    }
    // **The magnifier left this bar — `search-at-the-bottom`.** It was only ever standing
    // in for a field that was not on screen yet, and the field is at the top of the content
    // now, `search-at-the-top`. An icon that opens a search is a mode; a field is a search.
    // `ic_menu` and not `ic_settings` — `settings-screen-and-fab`. This control
    // opens a drawer holding the trash and one *Settings* row, so a gear on it
    // would name one row out of two. The gear moved inside, onto the row it
    // actually names.
    IconButton(
        onClick = onDrawer,
        modifier = Modifier.size(Spacing.touchTarget),
    ) {
        // `per-vault-intruder-capture` D7. The home bar's badge, for this vault's own captures —
        // a dot and the words, never the dot alone.
        BadgedBox(
            badge = {
                if (intruderWaiting) {
                    val waiting = stringResource(R.string.drawer_capture_waiting)
                    Badge(
                        containerColor = VaultColors.Error,
                        modifier = Modifier.semantics { contentDescription = waiting },
                    )
                }
            },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_menu),
                contentDescription = stringResource(R.string.grid_trash_and_settings),
                tint = VaultColors.OnSurface,
            )
        }
    }
}

/**
 * The grid's floating action button, and the four actions it expands to —
 * `settings-screen-and-fab`.
 *
 * **Camera, New Note, New Folder — and no fourth.** New Vault used to be here and
 * is now a bar action: it makes a vault beside this one rather than anything in
 * it, and grouping it with three content actions made it read as the opposite.
 * All three below are things you
 * *do to the vault* rather than things you configure about it. The set is closed: a
 * fifth action here is the drawer's failure starting again one surface along, and
 * `ScreenChromeTest` fails the build for one.
 *
 * **New folder is here and not under the header's `+`.** That mark is import; one
 * glyph opening two unrelated things is the ambiguity §6.4 exists to prevent, and the
 * header is at its measured four-control ceiling besides. It creates **at the level
 * being viewed**, so a folder made inside a folder is a sub-folder without a second
 * control.
 *
 * **Trash is not one of them.** It is a destination, not an action — nothing happens
 * to the vault when you tap it — so it is a row in [VaultDrawerSheet] with the
 * folders on the home screen, which is where the app now keeps the places you can go.
 *
 * **The mark is `ic_bolt` and deliberately not a plus.** [GridActions] keeps `ic_add`
 * for import two positions away, and two marks that are both a plus opening different
 * sets is the ambiguity §6.4 exists to prevent. It is not three dots either —
 * `ic_more_vert` was retired with the overflow menu and a test asserts it stays gone.
 * The set is not add-only: *New Vault* is a lifecycle action rather than something
 * added to the vault in front of you, so a mark saying "add" would be wrong about it.
 *
 * **The button is the grid's one primary-coloured control** (§6.1). `vault-grid` used
 * to reserve that slot for export and assert it stayed empty once export left; the
 * slot is now claimed, because a floating action button that is not primary-coloured
 * is not one, and tinting it to satisfy an assertion would be the test writing the
 * design.
 *
 * **Every expanded row carries words** (§6.4). The button itself is icon-only and
 * carries a content description — `DESIGN.md` §9 enumerates it as the twelfth and
 * last such control, for the viewer's reason one corner along: a label inside a 56dp
 * circle over the tiles is chrome on the photographs.
 *
 * **Not a dialog and not a bottom sheet.** §6.7 keeps the delete confirmation as this
 * app's only dialog. The scrim below is a transient layer, not a `Dialog`, and it
 * takes no focus and blocks no window.
 */
@Composable
private fun VaultFab(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCamera: () -> Unit,
    cameraAvailable: Boolean,
    onVoiceMemo: () -> Unit,
    onNewNote: () -> Unit,
) {
    // A quarter turn right as the set opens, and back as it closes — `fab-quarter-turn`.
    // A spring rather than a tween so a tap mid-turn reverses from where the square
    // currently is instead of queueing behind the first turn.
    val turn by animateFloatAsState(targetValue = if (expanded) 90f else 0f, label = "fabTurn")

    // **The scrim is the caller's now — `search-at-the-bottom`.** It has to fill the whole
    // screen, and this column no longer does: it sits inside the bottom block, above the
    // destination bar, so a `fillMaxSize` in here would fill the block rather than the window.
    Column(
        modifier = Modifier.padding(horizontal = Spacing.fabInset),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(Spacing.related),
    ) {
        if (expanded) {
            // Camera is always first in the set. With no camera it stays in its place,
            // unusable, its reason in the label — `unavailable-capability-states`. The
            // set stays at three, so `grid-fab`'s closed-set guard is untouched.
            FabAction(stringResource(R.string.fab_camera), R.drawable.ic_add_a_photo, reason = stringResource(NO_CAMERA).takeUnless { cameraAvailable }) {
                onExpandedChange(false)
                onCamera()
            }
            // Between the camera and the note, which is the order `voice-memo` fixes:
            // the three are what this vault can be given, roughly by how much of the
            // room they capture.
            FabAction(stringResource(R.string.fab_voice_memo), R.drawable.ic_mic) {
                onExpandedChange(false)
                onVoiceMemo()
            }
            FabAction(stringResource(R.string.fab_new_note), R.drawable.ic_note_add) {
                onExpandedChange(false)
                onNewNote()
            }
            // **New folder is deliberately not here — `new-folder-in-the-header`.** It
            // is one tap from the grid's header now, beside import, rather than two taps
            // behind this button. Putting it back is a defect.
        }

        // **The mark is a sibling of the square, not its content.** A rotation applies to
        // a composable's whole subtree, so a mark held in the button's content slot
        // turns with its parent — and the only way to keep it upright from in there is
        // to subtract the same angle again on the child. One rotation, reaching the one
        // thing that is meant to move.
        val fabLabel = stringResource(if (expanded) R.string.fab_close_actions else R.string.fab_actions)
        Box(contentAlignment = Alignment.Center) {
            FloatingActionButton(
                onClick = { onExpandedChange(!expanded) },
                containerColor = VaultColors.Primary,
                contentColor = VaultColors.OnPrimary,
                modifier = Modifier
                    .rotate(turn)
                    .size(Spacing.xxl + Spacing.xl)
                    // The name lives on the button because the button is what is
                    // pressed. With the mark outside the content slot, a description on
                    // the mark would name a node nobody can tap.
                    .semantics {
                        contentDescription = fabLabel
                    },
                content = {},
            )
            Icon(
                painter = painterResource(R.drawable.ic_bolt),
                // The button carries the name; this would be the same fact twice.
                contentDescription = null,
                // `contentColor` reaches the content slot, and this is not in it.
                tint = VaultColors.OnPrimary,
            )
        }
    }
}

/** The scrim behind the expanded actions. Lighter than the drawer's — it covers a corner's worth of decision, not a whole surface. */
private const val FAB_SCRIM_ALPHA = 0.4f

/**
 * One expanded action: a label and its glyph, on a surface of its own.
 *
 * **A labelled pill and not a small circular button.** A speed dial of four unlabelled
 * circles is four glyphs that name nothing, which is the overflow menu's failure in a
 * different shape — §6.4 keeps meaning off shape and colour alone, and P2-7 deleted
 * the overflow for exactly this. The whole pill is the target, and it clears §7.1's
 * 48dp without inflating the 24dp glyph.
 */
@Composable
private fun FabAction(
    label: String,
    icon: Int,
    /**
     * Why the device cannot do this, or null where it can — `unavailable-capability-states`.
     *
     * A pill has no supporting line, so the reason joins the label with the drawer's own
     * separator ("App identity  ·  Calculator"). The pill keeps its 48dp height, drops
     * to `OnSurfaceVariant`, and takes no click: `enabled = false` gives it disabled
     * semantics, so TalkBack reads "Camera · Not on this device, disabled". **A pill is
     * disabled only with a reason** — present, labelled and saying why is the opposite
     * of present and inert.
     */
    reason: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = reason == null,
        shape = RoundedCornerShape(Radius.control),
        color = VaultColors.SurfaceContainerHigh,
        contentColor = if (reason == null) VaultColors.OnSurface else VaultColors.OnSurfaceVariant,
    ) {
        Row(
            modifier = Modifier
                // A floor, not a height: a reason that wraps at a large font scale
                // grows the pill instead of being clipped.
                .heightIn(min = Spacing.touchTarget)
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(
                painter = painterResource(icon),
                // The label carries the meaning; the glyph repeats it. Null so a
                // screen reader reads the action once rather than twice.
                contentDescription = null,
                tint = VaultColors.OnSurfaceVariant,
            )
            Text(
                text = reason?.let { "$label  ·  $it" } ?: label,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** The Camera pill's reason on a device with no camera — `grid-fab`. */
private val NO_CAMERA = R.string.fab_no_camera

/**
 * The selection bar's actions — P2-11.
 *
 * Three icon-only controls, each with a content description, under exactly the
 * measurement P2-7 recorded for the grid's header: text labels for these plus a
 * count do not fit one bar row on a phone. `DESIGN.md` §9 enumerates them, and
 * `screen-chrome` fails the build for an icon-only control the direction does not
 * list.
 *
 * **None takes the primary colour** (§6.1), and delete is deliberately **not**
 * tinted `Error` here: the destructive weight belongs on the confirmation's named
 * action (§6.7), not on a control the user's thumb passes while selecting.
 */
@Composable
private fun SelectionActions(
    onSelectAll: () -> Unit,
    onMove: () -> Unit,
    onRemoveFromFolder: (() -> Unit)?,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    /** P4-2. Absent rather than disabled where the selection cannot be shared. */
    onShare: (() -> Unit)?,
) {
    IconButton(onClick = onSelectAll, modifier = Modifier.size(Spacing.touchTarget)) {
        Icon(
            painter = painterResource(R.drawable.ic_select_all),
            contentDescription = stringResource(R.string.select_all),
            tint = VaultColors.OnSurface,
        )
    }
    // P2-13. Beside select-all rather than beside the destructive pair: it is an
    // organising action, and the two on the right are the ones that take things
    // out of the vault.
    IconButton(onClick = onMove, modifier = Modifier.size(Spacing.touchTarget)) {
        Icon(
            painter = painterResource(R.drawable.ic_drive_file_move),
            contentDescription = stringResource(R.string.move_to_folder),
            tint = VaultColors.OnSurface,
        )
    }
    if (onRemoveFromFolder != null) {
        IconButton(onClick = onRemoveFromFolder, modifier = Modifier.size(Spacing.touchTarget)) {
            Icon(
                painter = painterResource(R.drawable.ic_folder_remove),
                contentDescription = stringResource(R.string.remove_from_folder),
                tint = VaultColors.OnSurface,
            )
        }
    }
    // P4-2. Beside unvault, because both take content out of the vault — one as
    // plaintext files, one as a sealed container. Two acts, two glyphs (§6.4);
    // `ic_output`'s own file comment says it is deliberately not a share glyph.
    if (onShare != null) {
        IconButton(onClick = onShare, modifier = Modifier.size(Spacing.touchTarget)) {
            Icon(
                painter = painterResource(R.drawable.ic_share),
                contentDescription = stringResource(R.string.share_securely),
                tint = VaultColors.OnSurface,
            )
        }
    }
    IconButton(onClick = onExport, modifier = Modifier.size(Spacing.touchTarget)) {
        Icon(
            painter = painterResource(R.drawable.ic_output),
            contentDescription = stringResource(R.string.unvault_selected),
            tint = VaultColors.OnSurface,
        )
    }
    IconButton(onClick = onDelete, modifier = Modifier.size(Spacing.touchTarget)) {
        Icon(
            painter = painterResource(R.drawable.ic_delete),
            contentDescription = stringResource(R.string.delete_selected),
            tint = VaultColors.OnSurface,
        )
    }
}

/**
 * The trash's selection actions — P2-12.
 *
 * Restore and destroy, in place of P2-11's unvault and delete. Icon-only under the
 * same bar-row measurement P2-7 recorded and `DESIGN.md` §9 enumerates, each with a
 * content description.
 *
 * Destroy is **not** tinted `Error` here either. It is irreversible, and §6.7 puts
 * that weight on the confirmation's named action rather than on a control a thumb
 * passes while selecting.
 */
@Composable
private fun TrashSelectionActions(
    onSelectAll: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    IconButton(onClick = onSelectAll, modifier = Modifier.size(Spacing.touchTarget)) {
        Icon(
            painter = painterResource(R.drawable.ic_select_all),
            contentDescription = stringResource(R.string.select_all),
            tint = VaultColors.OnSurface,
        )
    }
    IconButton(onClick = onRestore, modifier = Modifier.size(Spacing.touchTarget)) {
        Icon(
            painter = painterResource(R.drawable.ic_restore_from_trash),
            contentDescription = stringResource(R.string.restore_selected),
            tint = VaultColors.OnSurface,
        )
    }
    IconButton(onClick = onPurge, modifier = Modifier.size(Spacing.touchTarget)) {
        Icon(
            painter = painterResource(R.drawable.ic_delete_forever),
            contentDescription = stringResource(R.string.delete_selected_forever),
            tint = VaultColors.OnSurface,
        )
    }
}

/**
 * Leaves the trash — P2-12.
 *
 * `VaultScaffold` draws its own back affordance for the screens it owns; the grid
 * supplies its leading slot itself, so the trash's back lives here.
 */
@Composable
private fun BackAction(onBack: () -> Unit) {
    IconButton(onClick = onBack, modifier = Modifier.size(Spacing.touchTarget)) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = stringResource(R.string.action_back),
            tint = VaultColors.OnSurface,
        )
    }
}

/**
 * Leaves the selection — P2-11.
 *
 * Sits where the lock sits, and takes its place for the duration. Back does the
 * same thing; this is its visible twin, which is what the leading slot is for.
 */
@Composable
internal fun CloseSelectionAction(onClose: () -> Unit) {
    IconButton(onClick = onClose, modifier = Modifier.size(Spacing.touchTarget)) {
        Icon(
            painter = painterResource(R.drawable.ic_close),
            contentDescription = stringResource(R.string.leave_selection),
            tint = VaultColors.OnSurface,
        )
    }
}

/**
 * Lock, in the header's leading slot — P2-7.
 *
 * That slot is conventionally back-or-menu. The grid is the bottom of the stack
 * and has no back, so it is free, and it is the corner a thumb reaches fastest —
 * which is the entire point of putting lock there. P2-6 had it two taps deep
 * inside an overflow menu, which for an action whose value is being instant is
 * the same as not having it.
 *
 * **No confirmation, deliberately.** Locking discards the session key; nothing is
 * lost and the way back in is the passphrase the user already has. A lock that
 * asks "are you sure" is not a quick lock, and the situation this serves is
 * someone walking into the room.
 */
@Composable
private fun HomeAction(onHome: () -> Unit) {
    IconButton(
        onClick = onHome,
        modifier = Modifier.size(Spacing.touchTarget),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_home),
            contentDescription = stringResource(R.string.all_vaults),
            tint = VaultColors.OnSurface,
        )
    }
}

/**
 * The mosaic — P2-8.
 *
 * Items keep their own proportions, packed into justified rows by [MosaicRows] and
 * grouped under sticky date headings by [DateSections]. Before P2-8 this was a
 * `LazyVerticalGrid` of `aspectRatio(1f)` + `ContentScale.Crop`, which threw away
 * a shape the thumbnail already carried — `Thumbnailer.scale()` preserves aspect
 * ratio by scaling the longest edge.
 *
 * `DESIGN.md` §7.2's other rules are untouched: [Spacing.gridGutter] between
 * tiles, edge to edge, and no card, elevation, radius, border or inset anywhere.
 * Only the square went.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tiles(
    items: List<VaultItem>,
    /**
     * Which destination's body to draw — `vault-destinations`.
     *
     * [VaultDestination.Media] is the mosaic this composable has always been. The other
     * three are rows in the same `LazyColumn`, under the same folder region and the same
     * date headings: what changes is the shape of a row, never the list around it.
     */
    destination: VaultDestination,
    /**
     * Rows or tiles — `destination-view-modes`.
     *
     * ***This replaced `destination == Media` as the thing the body branches on.*** That
     * test was right while Media was the only mosaic and wrong the moment the shape became a
     * preference: it welded "which kind is here" to "what shape is the list", which are the
     * two questions this change pulls apart. The destination now decides only *which* row
     * composable a list body uses.
     */
    viewMode: VaultViewMode,
    /**
     * The scroll position, hoisted — `vault-destinations`.
     *
     * One state per destination, owned by the caller, so switching away and back returns
     * the user to where they were rather than to the top.
     */
    listState: LazyListState,
    /** `folder-system`. The level's folders, drawn ahead of the first heading. */
    folders: List<FolderView> = emptyList(),
    coverFor: (FolderView) -> VaultItem? = { null },
    onOpenFolder: (FolderView) -> Unit = {},
    onFolderSurface: (FolderView) -> Unit = {},
    /** A note's sheet, or null where the grid withholds it — see [VaultGrid]. */
    onNoteOptions: ((VaultItem) -> Unit)? = null,
    /** P2-12. One unheaded run instead of date sections — the trash's shape. */
    flat: Boolean = false,
    thumbnailFor: (ThumbRef) -> android.graphics.Bitmap?,
    onOpen: (VaultItem) -> Unit,
    selected: Set<String>,
    onBeginSelection: (VaultItem) -> Unit,
    onToggleSelection: (VaultItem) -> Unit,
    density: GridDensity,
    onDensityChange: (GridDensity) -> Unit,
    /** P2-16. Which date the headings are about. Ignored when [flat]. */
    sort: VaultSort = VaultSort.Default,
    /**
     * Bottom content padding, so the last row can be scrolled clear of the floating
     * action button. Zero where the button is not drawn.
     */
    bottomClearance: Dp = 0.dp,
) {
    // The configuration is a key so a change of language re-labels the headings in place.
    val words = localWords()
    val sections = remember(items, flat, sort, LocalConfiguration.current) {
        if (flat) listOf(DateSections.Section("", false, items))
        else DateSections.group(words, items, sort)
    }
    // `landscape-rotation`. **The shorter side, not the current width.** Sized from the width,
    // a turn made every row about 305dp tall at Medium — taller than the space the tiles are
    // given sideways. From the shorter side a tile is the size it was upright, and a turn
    // shows more of them across instead.
    val smallestWidth = LocalConfiguration.current.smallestScreenWidthDp.toFloat()
    val rowHeight = GridDensity.rowTarget(smallestWidth, density.columns).dp

    /**
     * `folder-system`. Keeps a newly created folder on screen.
     *
     * **A keyed `LazyColumn` anchors its scroll to the item that was first**, so a
     * folder row prepended to the list lands *above* the viewport: the folder is
     * created, composed, and invisible until the user scrolls up. The screen appears
     * to have ignored the whole flow, which is how this was found — a test made a
     * folder and then could not see it.
     *
     * Only on an increase, and only from at or near the top: a user who has scrolled
     * down to look at something is not dragged back by a count changing under them,
     * and a folder being deleted moves nothing.
     */
    var lastFolderCount by remember { mutableStateOf(folders.size) }
    LaunchedEffect(folders.size) {
        if (folders.size > lastFolderCount && listState.firstVisibleItemIndex <= 1) {
            listState.scrollToItem(0)
        }
        lastFolderCount = folders.size
    }

    // `landscape-rotation`. Packed against the width the list is **given**, not the screen's:
    // sideways the rail takes the leading edge, and rows packed to the whole screen would run
    // off the trailing one by the rail's width.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availableWidth = maxWidth
        val folderColumns = GridDensity.folderColumns(availableWidth.value, smallestWidth, density.columns)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // Pinch changes how many items fit a row. Bounded to `GridDensity`'s
                // few steps, so a stray pinch is undone by one more.
                //
                // **Wherever tiles are drawn**, and nowhere else. Density is a property of a
                // mosaic, not of one destination: this used to read `destination == Media`,
                // which was right while Media was the only mosaic. Files in tiles pinches now,
                // and Media in list does not — there is nothing in a row list to make denser.
                .then(
                    if (viewMode == VaultViewMode.Tiles) {
                        Modifier.pointerInput(density) {
                            detectTransformGestures { _, _, zoom, _ ->
                                if (zoom > PINCH_IN) onDensityChange(density.denser())
                                if (zoom < PINCH_OUT) onDensityChange(density.sparser())
                            }
                        }
                    } else {
                        Modifier
                    }
                ),
            // The mosaic's gutter is what sits between photographs. Rows carry their own
            // padding and butt against each other, as a list does everywhere else.
            verticalArrangement = if (viewMode == VaultViewMode.Tiles) {
                Arrangement.spacedBy(Spacing.gridGutter)
            } else {
                Arrangement.Top
            },
            // Content padding rather than a trailing spacer item: a spacer would be a
            // row in the list, and a row in the list is a row the sticky headings and
            // the key-based recomposition both have to know is not an item.
            contentPadding = PaddingValues(bottom = bottomClearance),
        ) {
            // `folder-system`. The places, then the things. Ahead of every heading and
            // never interleaved with the items: a date heading is about the items under
            // it, and a folder has no capture date to be under one.
            //
            // A fixed row of `density.columns`, not the mosaic below: the mosaic packs by
            // the aspect ratio a thumbnail happens to have, and a folder's tile is a
            // square whatever is inside it.
            if (folders.isNotEmpty()) {
                // `destination-view-modes`. **A folder follows the items' shape.** Squares
                // standing over a list of rows was the complaint this change began from: in a
                // list a folder is a row, and in a grid it is a tile with a cover. Dropbox,
                // Drive and Files all draw it the same way round.
                if (viewMode == VaultViewMode.List) {
                    items(folders, key = { folder -> "f:" + folder.id }) { folder ->
                        FolderListRow(
                            folder = folder,
                            onOpen = { onOpenFolder(folder) },
                            onSurface = { onFolderSurface(folder) },
                        )
                    }
                } else {
                    items(
                        folders.chunked(folderColumns),
                        key = { row -> "f:" + row.first().id },
                    ) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gridGutter)) {
                            row.forEach { folder ->
                                FolderTile(
                                    folder = folder,
                                    cover = coverFor(folder),
                                    thumbnailFor = thumbnailFor,
                                    onOpen = { onOpenFolder(folder) },
                                    onSurface = { onFolderSurface(folder) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // The last row keeps the tile size of a full one; without this a
                            // single folder would stretch across the screen and read as a
                            // banner rather than as one of a set.
                            repeat(folderColumns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            sections.forEach { section ->
                if (!flat) {
                    stickyHeader(key = "h:${section.label}:${section.items.first().itemId}") {
                        SectionHeading(section.label)
                    }
                }

                if (viewMode == VaultViewMode.Tiles) {
                    val rows = MosaicRows.pack(
                        ratios = section.items.map { aspectRatioOf(it, thumbnailFor) },
                        availableWidth = availableWidth.value,
                        targetHeight = rowHeight.value,
                        gutter = Spacing.gridGutter.value,
                    )

                    items(
                        rows,
                        key = { r -> "r:" + section.items[r.items.first().index].itemId },
                    ) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gridGutter)) {
                            row.items.forEach { placed ->
                                val item = section.items[placed.index]
                                MosaicTile(
                                    item = item,
                                    thumbnailFor = thumbnailFor,
                                    onOpen = onOpen,
                                    selected = item.itemId in selected,
                                    selecting = selected.isNotEmpty(),
                                    onBeginSelection = onBeginSelection,
                                    onToggleSelection = onToggleSelection,
                                    onOptions = onNoteOptions,
                                    modifier = Modifier
                                        .width(placed.width.dp)
                                        .height(placed.height.dp),
                                )
                            }
                        }
                    }
                } else {
                    // `vault-destinations`. One row per item, in the form that kind is read
                    // in — see `DestinationRows`. The packing above exists to fit pictures of
                    // different shapes together, and none of these has a picture.
                    items(section.items, key = { item -> "r:" + item.itemId }) { item ->
                        when (destination) {
                            // `destination-view-modes`. Media has a row now: it is reachable
                            // whenever the user asks for List here, so this branch is no longer
                            // the unreachable one it was.
                            VaultDestination.Media -> MediaRow(
                                item = item,
                                thumbnailFor = thumbnailFor,
                                onOpen = onOpen,
                                selected = item.itemId in selected,
                                selecting = selected.isNotEmpty(),
                                onBeginSelection = onBeginSelection,
                                onToggleSelection = onToggleSelection,
                            )
                            VaultDestination.Files -> FileRow(
                                item = item,
                                onOpen = onOpen,
                                selected = item.itemId in selected,
                                selecting = selected.isNotEmpty(),
                                onBeginSelection = onBeginSelection,
                                onToggleSelection = onToggleSelection,
                            )
                            VaultDestination.Notes -> NoteCard(
                                item = item,
                                onOpen = onOpen,
                                selected = item.itemId in selected,
                                selecting = selected.isNotEmpty(),
                                onBeginSelection = onBeginSelection,
                                onToggleSelection = onToggleSelection,
                                onOptions = onNoteOptions,
                            )
                            VaultDestination.Memos -> MemoRow(
                                item = item,
                                onOpen = onOpen,
                                selected = item.itemId in selected,
                                selecting = selected.isNotEmpty(),
                                onBeginSelection = onBeginSelection,
                                onToggleSelection = onToggleSelection,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** `landscape-rotation`. Material's compact-height boundary: below this the window is a sideways phone. */
private const val COMPACT_HEIGHT_DP = 480

/** Zoom thresholds for the grid pinch. Deliberately far apart so a scroll is never read as a pinch. */
private const val PINCH_IN = 1.15f
private const val PINCH_OUT = 0.87f

/**
 * How many items fit across the grid — P2-8's pinch density.
 *
 * A **bounded** set rather than a free scale: the layout stays justified at every
 * step, and a stray pinch is recoverable with one more rather than leaving the
 * user somewhere they cannot get back from.
 */
enum class GridDensity(val columns: Int) {
    Large(2), Medium(3), Small(5);

    fun denser(): GridDensity = when (this) {
        Large -> Large
        Medium -> Large
        Small -> Medium
    }

    fun sparser(): GridDensity = when (this) {
        Large -> Medium
        Medium -> Small
        Small -> Small
    }

    companion object {
        /**
         * `landscape-rotation`. A mosaic row's target height: the device's **shorter** side over
         * the column count. Upright on a phone that is the width, so nothing changes there;
         * sideways it keeps the tile the size it was rather than growing with the width.
         */
        fun rowTarget(smallestWidthDp: Float, columns: Int): Float = smallestWidthDp / columns

        /**
         * `landscape-rotation`. How many folder tiles fit across [availableWidthDp] at the
         * upright tile width — never fewer than the density's own column count, so upright
         * is exactly what it was.
         */
        fun folderColumns(availableWidthDp: Float, smallestWidthDp: Float, columns: Int): Int =
            maxOf(columns, (availableWidthDp / rowTarget(smallestWidthDp, columns)).toInt())
    }
}

/**
 * The item's true shape, from its decoded thumbnail.
 *
 * Null — no thumbnail yet, or one that failed to decode — reads as square.
 * [MosaicRows] treats a non-positive ratio the same way, so a broken item takes a
 * sane place in its row instead of collapsing it.
 */
private fun aspectRatioOf(
    item: VaultItem,
    thumbnailFor: (ThumbRef) -> android.graphics.Bitmap?,
): Float {
    val bitmap = item.thumb?.let(thumbnailFor) ?: return 1f
    if (bitmap.height <= 0) return 1f
    return bitmap.width.toFloat() / bitmap.height.toFloat()
}

@Composable
private fun SectionHeading(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = VaultColors.OnSurface,
        modifier = Modifier
            .fillMaxWidth()
            .background(VaultColors.Surface)
            .padding(horizontal = Spacing.screenInset, vertical = Spacing.sm)
            .semantics { heading() },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MosaicTile(
    item: VaultItem,
    thumbnailFor: (ThumbRef) -> android.graphics.Bitmap?,
    onOpen: (VaultItem) -> Unit,
    selected: Boolean,
    selecting: Boolean,
    onBeginSelection: (VaultItem) -> Unit,
    onToggleSelection: (VaultItem) -> Unit,
    /** A note's sheet, or null where the grid withholds it — see [VaultGrid]. */
    onOptions: ((VaultItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val bitmap = item.thumb?.let(thumbnailFor)
    // `notes-as-documents`. A note has no thumbnail and never will, so under the
    // generic tile it was a glyph you had to open to identify. Every notes app on
    // either store shows you the first lines instead, and that is what makes a wall
    // of notes usable. The excerpt comes from the index — drawing it reads no blob.
    val note = item.kind == VaultKind.NOTE

    Box(
        modifier = modifier
            .background(
                if (note) VaultColors.folder(item.colour) else VaultColors.SurfaceContainer
            )
            // P2-10's gesture, extended by P2-11. Long-press begins a selection
            // holding this tile; a tap opens the item, or toggles it while a
            // selection is running. **A tap never does both** — one that opened
            // sometimes and selected other times would be unpredictable in
            // exactly the moment the user is moving fast.
            //
            // The tile at rest is unchanged either way: no badge, no overlay,
            // nothing added between the photographs (§7.2). Selected is a state,
            // drawn below, not the resting appearance.
            .combinedClickable(
                onClick = { if (selecting) onToggleSelection(item) else onOpen(item) },
                onLongClick = { if (!selecting) onBeginSelection(item) },
                onLongClickLabel = stringResource(R.string.select),
            )
            .semantics { if (selected) this.selected = true },
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = item.filename,
                contentScale = ContentScale.Crop,
                // `viewer-chrome-and-editor`. **The turn is applied at draw, not
                // baked into the thumbnail.** Regenerating a 384px WebP and writing
                // it back into a thumbnail pack would spend a blob write to change a
                // number the index already holds; `rotationZ` costs no allocation, no
                // new cache key, and no trip to the store. The EXIF angle is already
                // in these pixels — `Thumbnailer.fromImage` applied it when the
                // thumbnail was built — so this is the stored rotation alone.
                //
                // The cell is square and the scale is `Crop`, so a quarter turn fills
                // it exactly as the unturned image did.
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = item.rotation.toFloat() },
            )
        }

        if (note) {
            Column(Modifier.fillMaxSize().padding(Spacing.sm)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // The title stops where the three-dot mark starts. Without this
                    // an ellipsis runs under the glyph, and the last thing a long
                    // title shows is the mark sitting on top of a letter.
                    modifier = Modifier.padding(end = OptionsGutter),
                ) {
                    if (item.pinned) {
                        Icon(
                            painter = painterResource(R.drawable.ic_push_pin),
                            contentDescription = stringResource(R.string.pinned),
                            tint = VaultColors.OnSurfaceVariant,
                            modifier = Modifier.padding(end = Spacing.xs).size(PinSize),
                        )
                    }
                    Text(
                        text = VaultNote.titleOf(item),
                        style = MaterialTheme.typography.titleMedium,
                        color = VaultColors.OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = item.preview.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurfaceVariant,
                    maxLines = PreviewLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.xs).weight(1f),
                )
            }

            // **The note's own actions, on the note.** `ScreenChromeTest` grants this
            // mark the exemption it grants the viewer's menu, on the same terms: what
            // is behind it is N acts on *one object* — pin, move, colour, delete — and
            // never the screen's own actions in hiding. The grid's five stay named in
            // its bar, which is what P2-7 deleted the overflow to protect.
            //
            // **It is drawn because it has to be.** A folder's surface is a long-press
            // and a note cannot borrow that: a long-press on an item begins a
            // selection, and taking that away from notes would make one kind of tile
            // answer the same gesture differently.
            //
            // Gone while a selection is running, for `combinedClickable`'s reason one
            // layer up — a tap in that mode adds and removes items, and a control that
            // opened a sheet instead would be the one place the rule does not hold.
            if (onOptions != null && !selecting) {
                IconButton(
                    onClick = { onOptions(item) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(Spacing.touchTarget),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        // Names its subject as well as itself, as the vault list's
                        // row glyphs do: "Options" alone reads identically on every
                        // note on the screen.
                        contentDescription = stringResource(R.string.vault_row_options, VaultNote.titleOf(item)),
                        tint = VaultColors.OnSurface,
                        modifier = Modifier.size(OptionsGlyph),
                    )
                }
            }
        }

        // P2-18. One glyph per kind, and no glyph for an image: a photograph
        // identifies itself, and §7.2 keeps the space between photographs empty.
        // Two kinds sharing a mark would make the tile unreadable with colour
        // disregarded, which is exactly what §6.4 is about.
        val kind = item.kind
        val glyph = when (kind) {
            // A note draws its own words above; a glyph over them would be chrome
            // between the thing and the person reading it.
            VaultKind.VIDEO -> R.drawable.ic_play_circle to R.string.kind_video
            // ***`vault-destinations` did not delete these two, and the reason is the
            // trash.*** Audio and file items no longer reach this tile from a
            // destination — Memos and Files draw them as rows now — but **the trash is
            // not a destination**: it holds every kind at once, the bar is not drawn over
            // it, and it is still the mosaic. Removing these branches would have left a
            // trashed recording as an unglyphed square, which is the "dead cell" P2-18
            // existed to end.
            //
            // The marks are shared with those destinations on purpose: `ic_audiotrack` is
            // Memos', `ic_description` is Files'. One mark, one meaning, in both places.
            VaultKind.AUDIO -> R.drawable.ic_audiotrack to R.string.kind_audio
            VaultKind.FILE -> R.drawable.ic_description to R.string.kind_file
            // P2-21. Its own mark, not FILE's: a note and an imported text file are
            // different items, and §6.4 forbids two kinds under one glyph.
            VaultKind.NOTE -> null
            VaultKind.IMAGE -> null
        }

        if (glyph != null) {
            // Identifiable without relying on colour: a glyph plus a duration
            // label, per DESIGN.md §6.4. The label is mono with tabular figures
            // so it does not reflow.
            Icon(
                painter = painterResource(glyph.first),
                contentDescription = stringResource(glyph.second),
                tint = VaultColors.OnSurface,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(Spacing.sm),
            )
            // P2-8, extended to audio by P2-18. Only when the length is actually
            // known — see `durationLabel`. This was a hardcoded "--:--" until then,
            // so every video in the app advertised a value the index never carried.
            durationLabel(item.durationMs)?.let { label ->
                Text(
                    text = label,
                    style = VaultMono.bodyMedium,
                    color = VaultColors.OnSurface,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(Spacing.xs),
                )
            }
        }

        // P2-18. A tile with no picture says what it is. Until this change it was
        // an empty rectangle — indistinguishable from a thumbnail that failed to
        // load, and the "dead cell" the outline named. The name sits under the
        // glyph rather than over a photograph: an image tile is never in this
        // branch, so §7.2's "nothing between the photographs" is untouched.
        if (bitmap == null) {
            Text(
                text = item.filename,
                style = MaterialTheme.typography.bodySmall,
                color = VaultColors.OnSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
            )
        }

        // P2-11. **A glyph and a scrim, not a tint.** §6.4 forbids meaning carried
        // by colour alone, and a tinted overlay is exactly that: the check is what
        // says "selected" and the scrim is only emphasis. The tile's geometry is
        // untouched — no inset, no resize, no radius — because a tile that changes
        // size on selection reflows the mosaic under the finger that is tapping it.
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(VaultColors.Scrim.copy(alpha = SELECTED_SCRIM_ALPHA)),
            )
            Icon(
                painter = painterResource(R.drawable.ic_check_circle),
                contentDescription = null,
                tint = VaultColors.OnSurface,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(Spacing.xs),
            )
        }
    }
}

/** The selected tile's scrim. Emphasis only — [R.drawable.ic_check_circle] carries the meaning. */
private const val SELECTED_SCRIM_ALPHA = 0.45f

/**
 * A destination with nothing in it — `vault-destinations`.
 *
 * ***An empty destination is an ordinary state, not a failure.*** A vault of photographs
 * alone is a perfectly normal vault, and three of its four destinations are empty. Each
 * says what belongs there, so the answer to "why is this blank" is on the screen rather
 * than something the user has to work out.
 *
 * [Media]'s copy and its action are the ones `EmptyVault` has always had, unchanged: the
 * word for a vault with nothing in it at all is still about photographs, because Media is
 * where a vault opens and where importing lands.
 *
 * **Every destination offers its one action** — `DESIGN.md` §6.5, text plus one action. It
 * is the destination's own way in, not a second one: Media and Files call the header's `+`
 * (`DESIGN.md` §7.2a decides which picker), Notes the floating button's write action, and
 * Memos its record action. An empty screen names the act that fills it where the eye already
 * is, rather than leaving the user to find an icon in the header or open the floating set.
 */
/**
 * How far above the visible centre an empty destination's words sit — `EmptyDestination`.
 *
 * A little, which is what was asked for. The true mirror of where they used to sit is
 * the whole height of the block at the foot of the screen, and moving them that far
 * puts them at the top edge and clips the last line, and every destination carries a button.
 */
private val EMPTY_RAISE = 48.dp

@Composable
private fun EmptyDestination(
    destination: VaultDestination,
    onImport: () -> Unit,
    onNewNote: () -> Unit,
    onVoiceMemo: () -> Unit,
    /** Media's chip — the button names what the chip shows. */
    mediaFilter: MediaFilter,
    /**
     * The height of the button and the bar at the foot of the screen.
     *
     * **This text is not centred in the area it is drawn in, and that is deliberate.**
     * The column fills the whole grid area — the block at the foot is an `overlay`, so
     * it covers the lower part rather than shortening it — and `Arrangement.Center`
     * therefore puts the words half that block's height *below* the centre the user
     * can actually see. That is the low position this corrects.
     *
     * **This height as bottom padding, plus [EMPTY_RAISE].** The first term centres the
     * words in the area that is actually visible; the second lifts them a little above
     * it. Not twice the block, which is the true mirror of the old offset and is far
     * too much: the block is the button and the bar together, so mirroring moves the
     * words most of a screen and squeezes the column until the last line is clipped.
     * Measured from the block rather than fixed, because its height is the button's
     * and the bar's and both move with the type scale.
     */
    bottomBlock: Dp,
    modifier: Modifier = Modifier,
) {
    val (heading, line) = when (destination) {
        VaultDestination.Media -> R.string.empty_media to R.string.empty_media_line
        VaultDestination.Files -> R.string.empty_files to R.string.empty_files_line
        VaultDestination.Notes -> R.string.empty_notes to R.string.empty_notes_line
        VaultDestination.Memos -> R.string.empty_memos to R.string.empty_memos_line
    }

    // Text plus one action. No illustration — DESIGN.md §6.5.
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.screenInset)
            // See [bottomBlock]: the block centres the words in what is visible, the
            // raise lifts them a little above it.
            .padding(bottom = bottomBlock + EMPTY_RAISE),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(heading),
            style = MaterialTheme.typography.titleMedium,
            color = VaultColors.OnSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(line),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xl),
        )
        val (label, action) = when (destination) {
            VaultDestination.Media ->
                (if (mediaFilter == MediaFilter.Photos) R.string.empty_add_photos else R.string.empty_add_media) to onImport
            VaultDestination.Files -> R.string.add_files to onImport
            VaultDestination.Notes -> R.string.empty_create_note to onNewNote
            VaultDestination.Memos -> R.string.empty_create_memo to onVoiceMemo
        }
        // Secondary, not primary: the primary slot is reserved for P1-13's
        // export action and stays empty until it exists (DESIGN.md §6.2).
        OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * The query field — P2-15, made persistent by `search-at-the-bottom` and put at the top of
 * the content by `search-at-the-top`.
 *
 * **At the top of the screen's content and always there.** It used to sit under the bar and
 * only while a search was open, which charged two taps for the app's most-repeated action —
 * one to open, one to leave. It then sat at the foot of the screen, between the button and
 * the destination bar, which is no app's convention for a search that filters the list
 * beneath it. It is drawn above every other element of the content, yields to a downward
 * scroll and is held while it has focus or the query is non-blank — see [VaultGrid].
 *
 * **It does not ride above the keyboard, and does not need to.** At the top the keyboard
 * cannot reach it.
 *
 * Material 3's `SearchBar` was the obvious alternative and brings an expanding surface, its
 * own scrim and a suggestions region this app has nothing to put in — three behaviours to
 * suppress where a text field has none. That is as true at the top as it was at the bottom.
 *
 * **The glyph is inside the field and is not a control.** It labels what the field does.
 * A tappable icon inside a text field is a second target inside a target, and the field is
 * already the affordance.
 *
 * **No auto-focus.** It used to request focus on composition, which was right for a field
 * the user had just asked for and is wrong for one that is simply present: a screen that
 * raises the keyboard because it was opened has decided what the user came to do.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.search)) },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                // Null: the field beside it is already named "Search" by its label, and a
                // screen reader announcing the glyph as well would say it twice.
                contentDescription = null,
                tint = VaultColors.OnSurfaceVariant,
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenInset, vertical = Spacing.sm),
    )
}

/**
 * Nothing matched — P2-15.
 *
 * Text and no action, [EmptyFolder]'s shape. The query is not repeated here: it is
 * in the field a few dp above, and restating it would be the screen telling the
 * user what they just typed.
 */
@Composable
private fun EmptySearch(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.screenInset),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.search_no_match),
            style = MaterialTheme.typography.titleMedium,
            color = VaultColors.OnSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.search_no_match_line),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
}

/**
 * An opened folder with nothing in it — `folder-system`.
 *
 * **A folder is allowed to be empty now**, which is what this change bought, so this
 * states a fact rather than warning about a mistake. `EmptyVault`'s shape: a line, a
 * second line, and the one action that changes it.
 */
@Composable
private fun EmptyFolder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.screenInset),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.folder_empty),
            style = MaterialTheme.typography.titleMedium,
            color = VaultColors.OnSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.folder_empty_line),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
}

/**
 * The trash with nothing in it — §6.5, minus the action.
 *
 * Text and no button, deliberately: §6.5 asks for one action that changes the
 * state, and there is nothing here that does. "Add photos" belongs to the vault,
 * and an empty trash is a good outcome rather than a gap to fill.
 */
@Composable
private fun EmptyTrash(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.screenInset),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.trash_empty),
            style = MaterialTheme.typography.titleMedium,
            color = VaultColors.OnSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.trash_empty_line, TrashPurge.WINDOW_DAYS),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
}

/** The note tile's excerpt depth — about what fits under a title at tile width. */
private const val PreviewLines = 3

/** The pin marker, sized to sit on a title's line without displacing it. */
private val PinSize = 14.dp

/**
 * The note tile's three-dot mark, and the room the title leaves for it.
 *
 * The glyph is 18dp inside `Spacing.touchTarget`'s 48dp, which is the token's own
 * rule: the target is met by padding around the mark and never by inflating it. The
 * gutter is that target less the tile's `Spacing.sm` inset, because the mark hangs off
 * the tile's corner while the title is already indented.
 */
private val OptionsGlyph = 18.dp
private val OptionsGutter = 40.dp
