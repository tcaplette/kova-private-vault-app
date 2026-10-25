package com.app.vault.ui

import androidx.activity.compose.LocalActivity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.SurfaceView
import android.view.WindowManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.Image
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import android.provider.Settings
import com.app.vault.Thumbnailer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.foundation.background
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.rememberUpdatedState
import com.app.vault.MemoSettings
import com.app.vault.VideoOrientation
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.app.vault.BlobStore
import com.app.vault.MediaSettings
import com.app.vault.R
import com.app.vault.VaultDataSource
import com.app.vault.VaultItem
import com.app.vault.VaultKind
import com.app.vault.VideoResume
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import com.app.vault.ui.theme.VaultMono
import com.app.vault.words
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.app.vault.localWords

/** ±10 s, per `OUTLINE.md` P1-8. */
const val SKIP_MS = 10_000L

/** How long the controls stay up after the last interaction — `DESIGN.md` §7.3. */
private const val CONTROLS_LINGER_MS = 3_000L

/** How long the frame line holds before it leaves — `video-frame-thumbnail`, D6. */
private const val FRAME_LINE_MS = 3_000L

/**
 * What choosing a frame produced — `video-frame-thumbnail`.
 *
 * A class and not a data class: two choices at the same position are two answers, and
 * equality would fold the second into the first and show nothing for it.
 */
class FrameOutcome(val positionMs: Long, val set: Boolean)

/** Magnification bounds. 1× is "fits the screen"; beyond 4× a thumbnail-derived image is mush. */
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 4f
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * How far below fit a pinch may rubber-band while the fingers are down —
 * `viewer-paging-gestures`.
 *
 * Coercing at [MIN_SCALE] during the gesture makes a shrinking pinch feel like it
 * has hit a wall, which reads as the app having stopped responding rather than as
 * the image having reached its size. It springs back on release.
 */
internal const val PINCH_FLOOR = 0.6f

/** How close to a bound counts as resting on it, in pixels. */
internal const val BOUND_EPSILON = 0.5f

/**
 * The dismiss drag, as fractions of the screen's height.
 *
 * [DISMISS_TRAVEL] is the distance the fade is measured over and [DISMISS_COMMIT]
 * the distance that completes it, so the ground is still readable at the point the
 * gesture becomes a departure rather than reaching nothing first.
 */
internal const val DISMISS_TRAVEL = 0.35f
internal const val DISMISS_COMMIT = 0.18f

/**
 * The speed, in pixels a second, that completes a dismiss on its own.
 *
 * Distance **or** speed, never distance alone: a short fast flick downward is the
 * gesture people actually make, and a distance-only threshold rejects it, which
 * reads as the gesture not existing.
 */
internal const val DISMISS_VELOCITY = 1_200f

/** How far the item shrinks across a full dismiss travel. */
private const val DISMISS_SHRINK = 0.15f

/** How long a gesture that did not complete takes to settle back. */
private const val SETTLE_MS = 200

/**
 * How many players are alive right now — P2-8.
 *
 * Exists so the instrumented Verify can **count** rather than infer. The one-player
 * rule is a correctness constraint, not a tidiness one: `HorizontalPager` keeps
 * neighbouring pages composed, so a player owned per page means up to three alive
 * at once, and hardware video decoders are a small fixed pool. Breaking the rule
 * fails on exactly the vaults holding the most video, and fails as a decode error
 * the user cannot interpret.
 *
 * A structural argument ("only the settled page constructs one") is what would rot
 * silently when someone later hoists the player for a good-looking reason. A
 * number cannot rot.
 */
@Suppress("unused")
internal object PlayerCensus {
    val live = AtomicInteger(0)

    /** `media-settings`. The speed the last player was built with — read by the device test. */
    @Volatile var lastSpeed = 1f

    /** The last player built — read by the device test for position, repeat and skip silence. */
    @Volatile var lastPlayer: java.lang.ref.WeakReference<ExoPlayer>? = null
}

/**
 * Full-screen viewing — `DESIGN.md` §6.6, §7.3, and the `media-viewer` spec.
 *
 * The ground is pure black and the media carries no card, elevation, radius or
 * border. This is the one place in the app where `#000000` is a surface: a
 * photograph should sit on nothing.
 *
 * **The one screen that does not use [VaultScaffold], and that is deliberate — do
 * not "fix" it.** P2-6 put every other screen in that wrapper for its title, back
 * affordance and safe-area insets. Applying it here would contradict the two rules
 * above: a top bar is chrome, and padding the item to the safe area frames it.
 *
 * Back comes from `MainActivity`'s single `BackHandler`, which clears the open item
 * and returns to the grid — **and from [onBack], a visible control in the revealed
 * layer.** A gesture that leaves no mark on screen is a gesture only the people who
 * already know it can use; the way out of a photograph should be visible when
 * anything else is.
 *
 * `viewer-paging-gestures` adds a third way — a downward drag — and does **not**
 * replace either of the first two, for the sentence above. It reaches [onBack] like
 * the visible control does, so there is one way out with three ways to ask for it.
 * `viewer-swipe-navigation` keeps that drag on the sideways pages only: on a video, down
 * is the previous video, and the visible control and system back are the ways out.
 *
 * **On the player's controls.** Until P2-8 this file said "DESIGN.md turns the
 * controller off". It does not, and never did — there was no section about the
 * player at all, so an absent rule was being cited to explain an absent feature.
 * §7.3 now exists and these controls are built to it. The *other* half of that
 * comment was sound and still holds: `media3-ui`'s `PlayerView` with its controller
 * disabled contributes a surface the platform already provides, in exchange for a
 * dependency and a P0-7-style I1 clearance run. It stays refused.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VaultViewer(
    items: List<VaultItem>,
    startIndex: Int,
    store: BlobStore,
    vmk: ByteArray,
    fullImageFor: (VaultItem) -> Bitmap?,
    /**
     * P2-10. The item on screen, never a list — the viewer shows one thing.
     *
     * **Null in the trash (P2-12)**, where the item is already deleted: what is
     * offered there is restore and destroy, both on the trash's own bar, and the
     * revealed layer is not the place to grow a second pair of controls (§7.3).
     */
    onDelete: ((VaultItem) -> Unit)?,
    /**
     * P2-14. The item on screen, never a list — a rename applies to one thing, and
     * this is where the app shows one thing.
     *
     * **Null in the trash**, for [onDelete]'s reason: that item is waiting to be
     * restored or destroyed, not being organised.
     */
    onRename: ((VaultItem) -> Unit)?,
    /**
     * P2-18. Exports the one item on screen — the action on a file item's identity
     * page, and the only thing that page offers.
     *
     * **P2-11's scoped export reached from a new place**, not a second export path:
     * it opens the export screen with this item as the scope, where `ExportAuth`
     * takes the passphrase exactly as it does for a selection or a whole vault.
     * Null in the trash, for [onDelete]'s reason.
     */
    onExport: ((VaultItem) -> Unit)?,
    /**
     * Leaves the viewer for the list the item came from — the grid, a folder, or a
     * search result. The same act as the system back gesture, given a control.
     */
    onBack: () -> Unit,
    /**
     * `viewer-chrome-and-editor`. Turns the item on screen a quarter turn clockwise.
     *
     * **Null in the trash**, for [onDelete]'s reason. A stored turn is an edit to a
     * row that is waiting to be destroyed or restored.
     */
    onRotate: ((VaultItem) -> Unit)?,
    /**
     * `viewer-chrome-and-editor`. Opens the image editor on the item on screen.
     *
     * **Null in the trash and null for anything that is not an image** — the editor
     * has nothing to do with a video, an audio file, a note or a file item, and a
     * control offered on all four would be a control that fails on three.
     */
    onEdit: ((VaultItem) -> Unit)?,
    /**
     * P4-2's send flow, reached from the item the viewer is showing.
     *
     * The same act as the selection bar's share control, given the surface that
     * already shows one item — a container carries one item, and this is the one.
     * **Null in the trash**, absent rather than disabled, which is [onSetCover]'s
     * idiom and §7.19's. Every kind the viewer can open can be shared, the file
     * identity page included.
     */
    onShare: ((VaultItem) -> Unit)?,
    /**
     * `viewer-chrome-and-editor`. Makes the item the cover of the folder it is in.
     *
     * **Null in the trash, and null at the vault root** — an item in no folder has no
     * cover to be. Absent rather than disabled, `onDelete`'s idiom: a greyed row
     * explains a state the user can already see.
     */
    onSetCover: ((VaultItem) -> Unit)?,
    /**
     * `viewer-chrome-and-editor`. Moves the one item on screen into a folder, through
     * the picker a selection already uses. Null in the trash.
     */
    onMoveToFolder: ((VaultItem) -> Unit)?,
    /**
     * `video-frame-thumbnail`. Makes the frame on screen the item's thumbnail, given the
     * position the player paused at.
     *
     * **Null in the trash and on a received share not yet kept** — a trashed item is
     * waiting, not being organised, and an unkept share has no row in the index to
     * repoint. The row it drives exists only on a settled video besides.
     */
    onUseFrame: ((VaultItem, Long) -> Unit)? = null,
    /**
     * `video-frame-thumbnail`. What the last [onUseFrame] produced, shown as D6's line
     * above the transport — and only as the answer to a choice this viewer made, so an
     * outcome left over from an earlier visit is never shown on arrival.
     */
    frameOutcome: FrameOutcome? = null,
    /**
     * `media-settings`. How photos and videos look and play. Defaulted to what the viewer did
     * before the settings existed, for the tests and previews that build a viewer without them.
     */
    media: MediaSettings = MediaSettings(),
    /** `memos-settings`. How audio plays: its own speed, and whether silence is skipped. */
    memos: MemoSettings = MemoSettings(),
    /**
     * `slideshow`. Whether the ⋮ menu offers a slideshow — on Media only, never in the trash or
     * on a received share. Offered while a photo or video is the settled page.
     */
    slideshow: Boolean = false,
    /**
     * `viewer-filmstrip`. Whether a photo's revealed layer carries the strip of the photos it
     * pages between — on Media only, never in the trash or on a received share. Drawn only on
     * the sideways pages, and only when there is more than one of them (D6).
     */
    filmstrip: Boolean = false,
    /**
     * `viewer-swipe-navigation`, D4. A video's thumbnail, for a page under the finger and for
     * the settled page until its first frame is drawn — the grid's own `thumbnailFor`, so it
     * is usually already decoded. Null shows the black ground.
     */
    thumbnailFor: (VaultItem) -> Bitmap? = { null },
    /**
     * `viewer-swipe-navigation`, D8. Where a video starts, or null for the beginning. **Null
     * on a received share not yet kept**: it has no row, so nothing is resumed for it.
     */
    resumeAt: ((VaultItem) -> Long?)? = null,
    /**
     * `viewer-swipe-navigation`, D7. The settled video's stopping point, once a second while
     * it plays and once as its player is released — already through [VideoResume.keep], so
     * null means "start over". Null on a received share; never called for audio.
     */
    onPosition: ((VaultItem, Long?) -> Unit)? = null,
    /**
     * `intruder-captures-at-home` D12. Moves the capture on screen into a vault on the keyring.
     * Null everywhere but the Intruder capture screen's viewer, and absent when null.
     */
    onMoveToVault: ((VaultItem) -> Unit)? = null,
    /**
     * `intruder-captures-at-home` D12. Exports the item on screen as one of the viewer's own
     * actions, on every kind — where [onExport] is offered on a file's page only. Null everywhere
     * but the Intruder capture screen's viewer.
     */
    onExportAction: ((VaultItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    // `viewer-swipe-navigation`, D1. The item the viewer was opened on picks the sequence —
    // the list's videos, paged vertically, or everything else, paged sideways — and a swipe
    // never crosses from one into the other.
    // `slideshow`. A slideshow plays photos and videos together and can end on either, so the
    // item that picks the sequence is held: a slideshow ending on a video, over a viewer opened on
    // a photo, hands the viewer over to the videos with that one settled.
    var anchor by remember { mutableStateOf(items[startIndex.coerceIn(0, items.lastIndex)]) }
    val opened = items.firstOrNull { it.itemId == anchor.itemId } ?: items[startIndex.coerceIn(0, items.lastIndex)]
    val vertical = opened.kind == VaultKind.VIDEO
    val pages = remember(items, vertical) { viewerSequence(items, opened) }

    val pagerState = rememberPagerState(
        initialPage = pages.indexOfFirst { it.itemId == opened.itemId }.coerceAtLeast(0),
        pageCount = { pages.size },
    )
    // `slideshow`. Where in [items] a slideshow started, while one is showing; null otherwise.
    var showing by remember { mutableStateOf<Int?>(null) }

    // `viewer-paging-gestures`. How far a dismiss drag has travelled, 0 to 1.
    // Reported by the page that owns the drag, applied here because what fades is
    // the ground and the controls, neither of which belongs to a page.
    var dismissProgress by remember { mutableFloatStateOf(0f) }

    // --- the revealed layer (P2-10) -----------------------------------------
    //
    // One reveal state for the whole viewer, and it governs the player's controls
    // as well as the item actions. It was `VideoSurface`'s private state until
    // P2-10, which needed the same behaviour on a still image; two copies of a
    // hide-after-linger rule is two places for it to drift.
    var revealed by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // §7.3 hides the controls after undisturbed *playback*. A paused video is
    // disturbed, so the layer stays up; a still image is never paused, and the
    // flag is cleared when the settled page is not a playing video.
    var paused by remember { mutableStateOf(false) }
    val touched = {
        revealed = true
        lastInteraction = System.currentTimeMillis()
    }

    // `viewer-filmstrip`, D4. From the start of a drag on the strip until its scroll stops. The
    // strip leads the pager meanwhile, no full-size decode starts, and the layer stays up — it
    // cannot disappear from under the finger. False wherever no strip is drawn.
    val scrubbing = remember { mutableStateOf(false) }

    LaunchedEffect(lastInteraction, paused, scrubbing.value) {
        if (paused || scrubbing.value) return@LaunchedEffect
        delay(CONTROLS_LINGER_MS)
        if (System.currentTimeMillis() - lastInteraction >= CONTROLS_LINGER_MS) revealed = false
    }

    // `video-frame-thumbnail`. The settled video's "pause, and say where" — registered
    // by the page that holds the player, so it is present exactly while there is a frame
    // on screen to take, which is the only time the menu row may exist.
    val frameHandler = remember { mutableStateOf<(() -> Long)?>(null) }
    // Between the tap and the answer. The row is withheld meanwhile, so a second tap
    // cannot race the first commit.
    var framePending by remember { mutableStateOf(false) }
    var frameLine by remember { mutableStateOf<String?>(null) }
    val words = localWords()
    LaunchedEffect(frameOutcome) {
        val outcome = frameOutcome ?: return@LaunchedEffect
        // Not an answer to anything this viewer asked — an earlier visit's.
        if (!framePending) return@LaunchedEffect
        framePending = false
        frameLine = if (outcome.set) {
            // The position in the player's own format, so the user can match it
            // against the readout they paused on.
            words.get(R.string.viewer_thumbnail_set, positionLabel(outcome.positionMs, 0L))
        } else {
            words.get(R.string.viewer_frame_unusable)
        }
        delay(FRAME_LINE_MS)
        frameLine = null
    }

    // `media-settings`. Keep screen on and full brightness, while a photo or video is the
    // settled item and the setting is on. Keyed to the settled item's kind, so swiping to a
    // note in the trash releases both.
    //
    // **The window's own flag and brightness, never the system's**: nothing here outlives the
    // viewer, and no permission is asked for. And **not an auto-lock suppression** —
    // `runtime-hardening` permits exactly two. A held display keeps the activity from stopping;
    // releasing it lets the screen time out and the vault lock through the ordinary path.
    // `AutoLock` is not called, extended or read.
    val window = LocalActivity.current?.window
    val settledKind = pages[pagerState.settledPage.coerceIn(0, pages.lastIndex)].kind
    val visual = settledKind == VaultKind.IMAGE || settledKind == VaultKind.VIDEO
    // A slideshow holds the screen itself, only while it plays — `slideshow`.
    val holdScreen = visual && media.keepScreenOn && showing == null
    val fullBrightness = visual && media.fullBrightness
    DisposableEffect(window, holdScreen, fullBrightness) {
        window?.apply {
            if (holdScreen) {
                addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            attributes = attributes.apply {
                screenBrightness = if (fullBrightness) {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                } else {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
        onDispose {
            window?.apply {
                clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                attributes = attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    // `landscape-rotation`. **A sideways video fills the screen: the system bars go.** The
    // convention in Photos, YouTube and the platform's own players — turning the phone to watch a
    // landscape video is asking for the whole screen. Hidden with the platform's transient
    // behaviour, so a swipe in from an edge brings them back briefly and no control of ours is
    // needed to reach the system.
    //
    // **A video only, and only sideways.** A photograph, a file page and an audio item keep the
    // bars in both orientations — only a video has a picture whose shape is the reason to turn the
    // phone — and upright is exactly what it was. Keyed to the settled item, so paging from a video
    // to anything else shows the bars again, and restored in `onDispose`, so leaving the viewer by
    // any path — back, lock, the drag — never leaves them hidden behind it.
    //
    // **Not an auto-lock suppression**, for the keep-screen-on effect's reason above: nothing here
    // touches `AutoLock`, and hidden bars keep no activity from stopping.
    //
    // Nothing else changes for it. The rows already carry `safeDrawingPadding()`, which falls to
    // the cutout alone once the bars are hidden, so the controls move to the edge by themselves;
    // and the surface is already sized by `aspectRatio` inside a centred full-size box, so a 16:9
    // video fills the height sideways with no further rule.
    val immersive = settledKind == VaultKind.VIDEO &&
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    DisposableEffect(window, immersive) {
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (immersive) {
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // `destination-settings-and-slideshow` D8. Video orientation: Landscape holds the screen
    // sideways — either way up, by the sensor — while a video is the settled page, and lets go
    // when it is not or the viewer closes. `landscape-rotation` made the turn non-destructive,
    // so playback carries on through it.
    val activity = LocalActivity.current
    val holdLandscape = settledKind == VaultKind.VIDEO && media.orientation == VideoOrientation.Landscape
    DisposableEffect(activity, holdLandscape) {
        activity?.requestedOrientation = if (holdLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // `viewer-paging-gestures`. The ground fades as a dismiss drag travels,
            // so the gesture says what it is about to do before it commits.
            .background(VaultColors.ViewerGround.copy(alpha = 1f - dismissProgress)),
    ) {
        // `viewer-swipe-navigation`, D1. One page body for both axes, so a page is the same
        // page whichever way it moves.
        val pageContent: @Composable (Int) -> Unit = { page ->
            val item = pages[page]
            // `settledPage` and not `currentPage`: during a drag both the outgoing
            // and incoming pages are current-ish, and starting playback on a page
            // the user is still scrolling past is how two players end up alive.
            val settled = pagerState.settledPage == page

            // P2-18. Three page types, chosen by kind rather than by asking "is
            // this video" and treating everything else as a photograph — which is
            // how a PDF ended up in the image decoder being reported as broken.
            val kind = item.kind
            if (kind.isPlayable) {
                PlayerPage(
                    item = item,
                    kind = kind,
                    store = store,
                    vmk = vmk,
                    // The one-player rule, across the slideshow too: while it is up, it holds
                    // the only player.
                    active = settled && showing == null,
                    controlsVisible = revealed,
                    onInteraction = touched,
                    onPlayingChanged = { playing -> if (settled) paused = !playing },
                    // `media-settings` for video, `memos-settings` for audio — each kind
                    // follows its own destination's speed.
                    speed = if (kind == VaultKind.VIDEO) media.speed.factor else memos.speed.factor,
                    skipSilence = kind == VaultKind.AUDIO && memos.skipSilence,
                    repeat = kind == VaultKind.VIDEO && media.repeat,
                    skipMs = if (kind == VaultKind.VIDEO) media.skip.ms else SKIP_MS,
                    // `video-frame-thumbnail`. Videos only — audio has no frame.
                    frameHandler = frameHandler.takeIf { kind == VaultKind.VIDEO },
                    frameLine = frameLine.takeIf { settled },
                    // `viewer-swipe-navigation`. Videos only: audio has no picture to
                    // show under the finger, and D8 resumes videos and nothing else.
                    thumbnail = { thumbnailFor(item) }.takeIf { kind == VaultKind.VIDEO },
                    // Resume videos off: start at the beginning and keep nothing — D8.
                    resumeAt = resumeAt.takeIf { kind == VaultKind.VIDEO && media.resume },
                    onPosition = onPosition.takeIf { kind == VaultKind.VIDEO && media.resume },
                )
            } else if (kind == VaultKind.FILE) {
                FilePage(
                    item = item,
                    onExport = onExport,
                    onReveal = {
                        if (settled) {
                            paused = false
                            touched()
                        }
                    },
                )
            } else {
                ImagePage(
                    item = item,
                    fullImageFor = fullImageFor,
                    stretch = media.stretchSmall,
                    onDismissProgress = { at -> if (settled) dismissProgress = at },
                    onDismiss = onBack,
                    onReveal = {
                        if (settled) {
                            // An image is never paused, and the flag would
                            // otherwise be left set by the video it paged away
                            // from — pinning the layer up for good.
                            paused = false
                            touched()
                        }
                    },
                    thumbnail = { thumbnailFor(item) },
                    decode = !scrubbing.value,
                )
            }
        }

        // D2 and D3. Down is "previous" on a video, so the dismiss drag lives only on the
        // sideways pages, inside `ImagePage`; a drag across a pager's axis is not its gesture,
        // so a sideways drag leaves a video playing. Both ends stop and neither wraps. Each
        // pager gives accessibility services forward and back on its own axis — page up and
        // down here, left and right there — through Foundation's `pagerSemantics`.
        if (vertical) {
            VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { pageContent(it) }
        } else {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { pageContent(it) }
        }

        // `viewer-chrome-and-editor`. **Two rows, and neither is a bar.** They sit
        // directly on the media, appear and hide with the rest of the revealed layer,
        // and carry their own safe-area padding — the item is never inset to make
        // room for one, which is the half of §6.6 that did not change. What the
        // section used to forbid was `VaultScaffold`, a title, and chrome that
        // outlives the reveal timer; all three are still refused.
        //
        // The settled page and not the swiped one: a control acts on what the user is
        // looking at, and mid-swipe there are two pages composed.
        val settled = { pages[pagerState.settledPage.coerceIn(0, pages.lastIndex)] }

        // `player-controls-and-menu`. **The one property the layout turns on.** An
        // item that plays needs the bottom edge for its scrubber, so its actions move
        // into the menu; an item that does not has that edge free and keeps them on
        // it. One question, asked once, so no third layout can appear by accident.
        val playing = pages[pagerState.settledPage.coerceIn(0, pages.lastIndex)].kind.isPlayable

        if (revealed) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .safeDrawingPadding(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The leading corner, where every other screen in this app puts back.
                // The position is borrowed from `VaultScaffold`, not the bar.
                ViewerAction(R.drawable.ic_arrow_back, stringResource(R.string.action_back), onBack)

                Spacer(modifier = Modifier.weight(1f))

                // Withheld on a playable item at the call site as well, and here
                // too: a rotation the player cannot apply is a control that changes
                // what is stored and shows the user nothing.
                if (onRotate != null && !playing) {
                    ViewerAction(R.drawable.ic_rotate_right, stringResource(R.string.viewer_rotate)) { onRotate(settled()) }
                }
                // The acts on the item join the menu only where the bottom row is
                // gone. On a still image the menu is what it was — the occasional
                // acts, and nothing that was already within reach.
                val menuRename = onRename.takeIf { playing }
                val menuShare = onShare.takeIf { playing }
                val menuDelete = onDelete.takeIf { playing }
                val menuMoveToVault = onMoveToVault.takeIf { playing }
                val menuExport = onExportAction.takeIf { playing }
                // `video-frame-thumbnail`. Pauses on the frame and takes its position in
                // the same act, so the words are literally true afterwards: the frame the
                // user sees is the one that became the thumbnail.
                val handler = frameHandler.value
                val menuFrame = if (onUseFrame != null && handler != null && !framePending) {
                    {
                        val target = settled()
                        framePending = true
                        onUseFrame(target, handler())
                    }
                } else {
                    null
                }
                // `slideshow`. From the item on screen, over the list the viewer was opened from.
                val settledKind = settled().kind
                val menuSlideshow = if (slideshow && (settledKind == VaultKind.IMAGE || settledKind == VaultKind.VIDEO)) {
                    { showing = items.indexOfFirst { it.itemId == settled().itemId }.coerceAtLeast(0) }
                } else {
                    null
                }
                if (menuSlideshow != null || onSetCover != null || onMoveToFolder != null || menuFrame != null ||
                    menuRename != null || menuShare != null || menuDelete != null ||
                    menuMoveToVault != null || menuExport != null
                ) {
                    ViewerMenu(
                        onSlideshow = menuSlideshow,
                        onSetCover = onSetCover?.let { { it(settled()) } },
                        onMoveToFolder = onMoveToFolder?.let { { it(settled()) } },
                        onUseFrame = menuFrame,
                        onMoveToVault = menuMoveToVault?.let { { it(settled()) } },
                        onExport = menuExport?.let { { it(settled()) } },
                        onRename = menuRename?.let { { it(settled()) } },
                        onShare = menuShare?.let { { it(settled()) } },
                        onDelete = menuDelete?.let { { it(settled()) } },
                    )
                }
            }

            // Not drawn at all over a playable item: the transport owns that edge.
            // Until `player-controls-and-menu` this row was aligned to the same edge
            // as the player's controls and composed after them, so it sat on top and
            // rename took the taps meant for play/pause.
            //
            // `viewer-filmstrip`, D1. The strip sits directly above this row, in one column that
            // carries the safe-area padding for both. The strip runs the column's full width;
            // the row keeps its own inset.
            val strip = filmstrip && !vertical && pages.size > 1 && showing == null
            val actions = !playing && (
                onRename != null || onEdit != null || onShare != null || onDelete != null ||
                    onMoveToVault != null || onExportAction != null
                )
            if (strip || actions) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .safeDrawingPadding(),
                ) {
                    if (strip) {
                        Filmstrip(
                            pages = pages,
                            pagerState = pagerState,
                            thumbnailFor = thumbnailFor,
                            scrubbing = scrubbing,
                            onInteraction = {
                                paused = false
                                touched()
                            },
                        )
                    }
                    if (strip && actions) Spacer(modifier = Modifier.height(Spacing.sm))
                    if (actions) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.screenInset),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // P2-14 put rename beside delete because a rename is one item and
                            // this is the surface that shows one item; that holds one row
                            // along. **Delete keeps the far end**, which is P2-10's rule
                            // carried from a corner to a row: the destructive control does not
                            // move under a finger that already knows where it is.
                            if (onRename != null) {
                                ViewerAction(R.drawable.ic_edit, stringResource(R.string.rename)) { onRename(settled()) }
                            }
                            if (onEdit != null) {
                                ViewerAction(R.drawable.ic_tune, stringResource(R.string.viewer_edit_image)) { onEdit(settled()) }
                            }
                            // P4-2. Before delete for the same reason the selection bar puts
                            // it before unvault: it takes the item out of the vault, and the
                            // destructive control keeps the far end regardless.
                            if (onShare != null) {
                                ViewerAction(R.drawable.ic_share, stringResource(R.string.share_securely)) { onShare(settled()) }
                            }
                            // `intruder-captures-at-home`. A capture's two ways out, before delete,
                            // which keeps the far end.
                            if (onMoveToVault != null) {
                                ViewerAction(R.drawable.ic_drive_file_move, stringResource(R.string.intruder_move_to_vault)) {
                                    onMoveToVault(settled())
                                }
                            }
                            if (onExportAction != null) {
                                ViewerAction(R.drawable.ic_output, stringResource(R.string.intruder_export)) { onExportAction(settled()) }
                            }
                            if (onDelete != null) {
                                ViewerAction(R.drawable.ic_delete, stringResource(R.string.delete)) { onDelete(settled()) }
                            }
                        }
                    }
                }
            }
        }

        // `slideshow`. Over everything, the viewer's chrome included; it ends on the item it was
        // showing, and the viewer is left settled there.
        val from = showing
        if (from != null) {
            val shows = remember(items) { items.filter { it.kind == VaultKind.IMAGE || it.kind == VaultKind.VIDEO } }
            SlideshowScreen(
                items = shows,
                start = shows.indexOfFirst { it.itemId == items[from].itemId }.coerceAtLeast(0),
                store = store,
                vmk = vmk,
                fullImageFor = fullImageFor,
                thumbnailFor = thumbnailFor,
                media = media,
                onClose = { last ->
                    showing = null
                    if ((last.kind == VaultKind.VIDEO) != vertical) anchor = last
                    val sequence = viewerSequence(items, last)
                    val page = sequence.indexOfFirst { it.itemId == last.itemId }.coerceAtLeast(0)
                    pagerState.requestScrollToPage(page)
                    touched()
                },
            )
        }
    }
}

/**
 * The items a viewer opened on [opened] moves between — `viewer-swipe-navigation`, D1.
 *
 * The list's videos when it was opened on a video, everything else when it was not, in the
 * list's own order. `MainActivity` lands a delete with it too, so the viewer and the landing
 * can never disagree about which item comes next.
 */
internal fun viewerSequence(items: List<VaultItem>, opened: VaultItem): List<VaultItem> {
    val videos = opened.kind == VaultKind.VIDEO
    return items.filter { (it.kind == VaultKind.VIDEO) == videos }
}

/**
 * One control on the viewer's revealed layer — `viewer-chrome-and-editor`.
 *
 * Every one of them is the same size, the same tint and named to accessibility
 * services, because they are peers: nothing on these rows is more important than the
 * item, and one control drawn larger than the others would say otherwise.
 *
 * Internal so [ViewerMenu] can wear the same control as its trigger — the menu lives
 * in its own file because it is a Material surface rather than part of the black
 * ground, and a second definition of this would be a second size to keep in step.
 */
@Composable
internal fun ViewerAction(icon: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(Spacing.touchTarget)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = VaultColors.OnViewerGround,
        )
    }
}

// --- the filmstrip ---------------------------------------------------------

/** `viewer-filmstrip`, D2. The current cell's width at its own shape, clamped. */
private val STRIP_MIN_WIDTH = 24.dp
private val STRIP_MAX_WIDTH = 96.dp

/**
 * How far one cell is grown towards its own shape — `viewer-filmstrip`, D3.
 *
 * [full] is the cell's width at its own shape, in pixels; [t] runs 0 (a square) to 1 (that shape,
 * with its space on each side). Held by index for the strip as a whole, not by the cell, so a cell
 * that scrolls out mid-animation still finishes pushing its neighbours back.
 */
private class Growth(val full: Float, val t: Animatable<Float, AnimationVector1D>)

/**
 * The strip of photos along the bottom of the Media viewer's revealed layer — `viewer-filmstrip`.
 *
 * **One row of thumbnails, on nothing.** No bar, scrim, border or radius: §6.6 refuses a bar on
 * the viewer, and opaque pictures read on any photo without one. The photo on screen is marked
 * the way Apple Photos marks it — its own shape, space on each side, and the centre — never by
 * colour (§6.4).
 *
 * **One source of truth for "current": [pagerState].** The strip leads while it is being dragged
 * or is still settling ([scrubbing]), and swaps the photo as each thumbnail crosses the centre;
 * the pager leads the rest of the time, and the strip glides to centre what it shows. Each
 * direction runs only in its own mode, so the two cannot feed each other.
 *
 * **Every cell is a 48dp slot in the row's layout, the current one included.** Its own shape, and
 * the space that moves its neighbours aside, are drawn over that slot — placed wider, translated —
 * and never measured. Measured, they moved the cells under the centre, which changed which cell
 * was current, which changed them back: a finger resting on the strip flipped the photo between
 * two neighbours for as long as it stayed down. A tall photo is narrower than a square when
 * current, so its neighbours closed in on the centre the moment it became current.
 */
@Composable
private fun Filmstrip(
    pages: List<VaultItem>,
    pagerState: PagerState,
    thumbnailFor: (VaultItem) -> Bitmap?,
    scrubbing: MutableState<Boolean>,
    /** A drag or a tap on the strip is an interaction with the layer — D4's hide timer. */
    onInteraction: () -> Unit,
) {
    val context = LocalContext.current
    // Compose does not honour the animator scale on its own — `SlideshowScreen`'s check.
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val density = LocalDensity.current
    val slot = with(density) { Spacing.touchTarget.toPx() }
    val side = with(density) { Spacing.sm.toPx() }
    val scope = rememberCoroutineScope()
    val current = pagerState.currentPage
    val strip = rememberLazyListState(initialFirstVisibleItemIndex = current)

    // The current cell's width at its own shape — D2. Rotation-aware, so a turned photo's cell
    // turns with it.
    fun widthOf(item: VaultItem): Dp {
        val bitmap = thumbnailFor(item) ?: return Spacing.touchTarget
        val quarter = item.rotation % 180 != 0
        val w = if (quarter) bitmap.height else bitmap.width
        val h = if (quarter) bitmap.width else bitmap.height
        if (w <= 0 || h <= 0) return Spacing.touchTarget
        return (Spacing.touchTarget * (w.toFloat() / h)).coerceIn(STRIP_MIN_WIDTH, STRIP_MAX_WIDTH)
    }

    // D3. The incoming cell grows to its shape and the outgoing one returns to a square, 250ms
    // `EaseOut`, §8's Enter; at zero animator scale, and on arrival, they take their shapes at once.
    val growth = remember { mutableStateMapOf<Int, Growth>() }
    val target = with(density) { widthOf(pages[current]).toPx() }
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(current, target) {
        val spec = tween<Float>(250, easing = EaseOut)
        val instant = still || !arrived
        arrived = true
        growth.filterKeys { it != current }.forEach { (i, g) ->
            launch {
                if (instant) g.t.snapTo(0f) else g.t.animateTo(0f, spec)
                growth.remove(i)
            }
        }
        val from = growth[current]?.t?.value ?: 0f
        val g = Growth(target, Animatable(from)).also { growth[current] = it }
        if (instant) g.t.snapTo(1f) else g.t.animateTo(1f, spec)
    }
    // How far a grown cell moves each of its neighbours aside: half its extra width, plus its space.
    fun push(g: Growth) = ((g.full - slot) / 2 + side) * g.t.value
    fun shiftOf(index: Int): Float = growth.entries.sumOf { (i, g) ->
        when {
            i < index -> push(g).toDouble()
            i > index -> -push(g).toDouble()
            else -> 0.0
        }
    }.toFloat()

    // The slot whose span holds the centre — the gutter between two falls to the nearer.
    fun centred(): Int? {
        val info = strip.layoutInfo
        val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
        val cells = info.visibleItemsInfo
        return (cells.firstOrNull { mid >= it.offset && mid < it.offset + it.size }
            ?: cells.minByOrNull { abs(it.offset + it.size / 2 - mid) })?.index
    }

    // Scrubbing: on with the drag, off once the scroll — drag, fling and snap — has stopped.
    val dragged by strip.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) {
        if (dragged) {
            scrubbing.value = true
            onInteraction()
        }
    }
    LaunchedEffect(strip) {
        snapshotFlow { strip.isScrollInProgress }.collect { moving ->
            if (!moving && scrubbing.value) {
                scrubbing.value = false
                onInteraction()
            }
        }
    }
    // A strip leaving mid-scrub must not leave the decode held and the layer pinned.
    DisposableEffect(Unit) { onDispose { scrubbing.value = false } }

    // The strip leads: the photo follows the thumbnail under the centre, instantly, as Apple's does.
    LaunchedEffect(strip, pagerState) {
        snapshotFlow { if (scrubbing.value) centred() else null }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { if (it != pagerState.currentPage) pagerState.scrollToPage(it) }
    }
    // The pager leads: the strip centres whatever the photo now is. The first placement is
    // instant, so the strip does not glide in from the side on every reveal.
    LaunchedEffect(strip, pagerState) {
        var placed = false
        snapshotFlow { if (scrubbing.value) null else pagerState.currentPage }
            .filterNotNull()
            .collectLatest { page ->
                if (still || !placed) strip.scrollToItem(page) else strip.animateScrollToItem(page)
                placed = true
            }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().testTag("filmstrip")) {
        // D2. Lets the first and last slots reach the centre.
        val edge = ((maxWidth - Spacing.touchTarget) / 2).coerceAtLeast(0.dp)
        LazyRow(
            state = strip,
            flingBehavior = rememberSnapFlingBehavior(strip, SnapPosition.Center),
            contentPadding = PaddingValues(horizontal = edge),
            horizontalArrangement = Arrangement.spacedBy(Spacing.gridGutter),
            modifier = Modifier.fillMaxWidth().height(Spacing.touchTarget),
        ) {
            itemsIndexed(pages, key = { _, item -> item.itemId }) { index, item ->
                val isCurrent = index == current
                Box(
                    modifier = Modifier
                        // Moved aside by the grown cells around it, at draw — never measured.
                        .graphicsLayer { translationX = shiftOf(index) }
                        // The slot is 48dp whatever is drawn in it; a grown cell is placed wider,
                        // centred on its slot.
                        .layout { measurable, _ ->
                            val g = growth[index]
                            val w = if (g == null) slot else slot + (g.full - slot) * g.t.value
                            val placeable = measurable.measure(Constraints.fixed(w.roundToInt(), slot.roundToInt()))
                            layout(slot.roundToInt(), slot.roundToInt()) {
                                placeable.place(((slot - placeable.width) / 2).roundToInt(), 0)
                            }
                        }
                        .clickable {
                            onInteraction()
                            // Instant, as Apple's tap is: paging there would compose and decode
                            // every photo between. The pager-leads flow then centres the cell.
                            scope.launch { pagerState.scrollToPage(index) }
                        }
                        .semantics {
                            role = Role.Button
                            contentDescription = item.filename
                            selected = isCurrent
                        }
                        .clipToBounds(),
                    contentAlignment = Alignment.Center,
                ) {
                    val bitmap = thumbnailFor(item)
                    if (bitmap != null) {
                        // The stored turn at draw, as the grid tile applies it. Laid out at the
                        // turned shape first, so a quarter turn lands back on the cell's own.
                        val quarter = item.rotation % 180 != 0
                        val drawn = with(density) {
                            val w = growth[index]?.let { slot + (it.full - slot) * it.t.value } ?: slot
                            w.toDp()
                        }
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .requiredSize(
                                    width = if (quarter) Spacing.touchTarget else drawn,
                                    height = if (quarter) drawn else Spacing.touchTarget,
                                )
                                .graphicsLayer { rotationZ = item.rotation.toFloat() },
                        )
                    } else {
                        // The viewer's palette, not the theme's: the ground is black in both
                        // themes, and a light-scheme surface would be a bright block on it.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(VaultColors.OnViewerGroundVariant.copy(alpha = 0.24f)),
                        )
                    }
                }
            }
        }
    }
}

// --- images ---------------------------------------------------------------

/** What an item's full-size decode produced. An empty frame is not one of these. */
private sealed interface Decoded {
    data object Loading : Decoded
    data class Ready(val bitmap: Bitmap) : Decoded
    data object Failed : Decoded
}

@Composable
private fun ImagePage(
    item: VaultItem,
    fullImageFor: (VaultItem) -> Bitmap?,
    /**
     * `media-settings`. On, a photo smaller than the screen is enlarged to fit it, as it always
     * was; off, it rests at its own size, one image pixel to one screen pixel.
     */
    stretch: Boolean,
    /**
     * `viewer-paging-gestures`. How far a dismiss drag has travelled, 0 to 1.
     *
     * The item moves here, because the item belongs to this page. The ground and
     * the control rows fade in [VaultViewer], because they do not.
     */
    onDismissProgress: (Float) -> Unit,
    /** The drag completed. The same way out the visible back control takes. */
    onDismiss: () -> Unit,
    /** P2-10. A single tap brings the item's actions back. Never a magnification. */
    onReveal: () -> Unit,
    /**
     * `viewer-filmstrip`, D5. The grid's thumbnail, drawn while the full-size photo is being
     * decoded. Null leaves the loading notice.
     */
    thumbnail: () -> Bitmap?,
    /**
     * `viewer-filmstrip`, D5. False while the filmstrip is being dragged: a page the scrub passes
     * over shows its thumbnail and starts no decode, because `fullImageFor` cannot be cancelled
     * once it has begun.
     */
    decode: Boolean,
) {
    // Off the main thread. This used to be `remember { fullImageFor(item) }`,
    // which decrypts and decodes a full-size photograph during composition — and
    // gave no way to distinguish "still working" from "failed", because both are
    // a null bitmap and both drew nothing.
    /**
     * The decoded photograph, and **what stays on screen while it is being replaced.**
     *
     * `decodeFull` composes the EXIF angle with the item's stored turn and bakes the
     * result into the bitmap, so a turn changes what this should hold — but `itemId`
     * does not change when a row is rotated. Keyed on identity alone the decode never
     * re-ran, and the photograph on screen stayed as it was until the viewer was left
     * and re-entered. That was the reported bug.
     *
     * **The state resets on the item and the decode re-runs on the turn**, and that
     * split is the whole point of two keys rather than one:
     *
     * - Arriving at a *different* photograph blanks to `Loading`, because holding the
     *   previous one would show the wrong picture under the new one's name.
     * - Turning *this* photograph keeps the frame already on screen until the turned
     *   one is ready. It is the same picture; replacing it with `ViewerNotice("Opening…")`
     *   for the length of a full-size decrypt and decode would make every quarter turn
     *   a blink to a text notice.
     *
     * A decode that fails still reports the failure rather than leaving a stale frame
     * up: a picture that silently refuses to turn is worse than one that says it could
     * not be opened.
     *
     * The bitmap is not recycled here, as it never was — `decodeFull` recycles only its
     * own intermediate. Recycling the outgoing frame on the spot would race the frame
     * still drawing it, which trades a blink for a crash.
     *
     * The tile does this differently — `rotationZ` at draw, no re-decode — because a
     * grid cell is square and a quarter turn still fits it. A viewer draws to `Fit`
     * against the whole screen, where a turned portrait would not.
     *
     * **While it is `Loading` the item's own thumbnail is drawn** — `viewer-filmstrip`, D5 —
     * fitted to the same box the full image takes, so the sharp picture replaces it in place.
     * "Opening…" is left only for an item with no thumbnail.
     */
    var decoded by remember(item.itemId) { mutableStateOf<Decoded>(Decoded.Loading) }
    // The turn [decoded] was made at, so a scrub that starts and ends on this photo does not
    // decode it a second time when `decode` comes back on.
    var decodedTurn by remember(item.itemId) { mutableStateOf<Int?>(null) }

    LaunchedEffect(item.itemId, item.rotation, decode) {
        if (!decode || decodedTurn == item.rotation) return@LaunchedEffect
        val next = withContext(Dispatchers.IO) {
            runCatching { fullImageFor(item) }.getOrNull()
        }
        decoded = next?.let { Decoded.Ready(it) } ?: Decoded.Failed
        decodedTurn = item.rotation
    }

    var scale by remember(item.itemId) { mutableFloatStateOf(1f) }
    var offset by remember(item.itemId) { mutableStateOf(Offset.Zero) }

    // `viewer-paging-gestures`. How far a dismiss drag has carried the item, and
    // that distance as the 0..1 the fades are drawn from.
    var dismiss by remember(item.itemId) { mutableStateOf(Offset.Zero) }
    var travelled by remember(item.itemId) { mutableFloatStateOf(0f) }

    // Settling runs outside the gesture loop: animating inside it would hold the
    // loop open and swallow the next gesture, so a second flick after a spring-back
    // would do nothing.
    val settling = rememberCoroutineScope()

    // `media-settings`. The box pan bounds are measured from. For a fitted photo that is the
    // screen, exactly as it always was. A small photo resting at its own size bounds its pan by
    // its own size — measured from the screen, a magnified one could be dragged off it entirely.
    fun panBox(width: Float, height: Float): Pair<Float, Float> {
        val bitmap = (decoded as? Decoded.Ready)?.bitmap ?: return width to height
        return if (!stretch && bitmap.width <= width && bitmap.height <= height) {
            bitmap.width.toFloat() to bitmap.height.toFloat()
        } else {
            width to height
        }
    }

    // Per page, and reset on arrival. Carrying magnification to the next
    // photograph means landing on an arbitrary crop of an image never seen whole.
    LaunchedEffect(item.itemId) {
        scale = 1f
        offset = Offset.Zero
        dismiss = Offset.Zero
        travelled = 0f
        onDismissProgress(0f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // The reveal tap for the states that draw no image. A failure is
            // exactly the item a user reaches for the delete action on, so it
            // cannot be the one state where the action is unreachable. The Ready
            // branch repeats it below because the image consumes its own taps.
            .pointerInput(item.itemId) {
                detectTapGestures(onTap = { onReveal() })
            },
    ) {
        when (val state = decoded) {
            is Decoded.Loading -> {
                // Turned into a new bitmap, never the cached one: the cache still hands that
                // to the grid, so it is neither changed nor recycled here.
                val thumb = thumbnail()
                val turned = remember(item.itemId, item.rotation, thumb) {
                    thumb?.let { Thumbnailer.oriented(it, item.rotation) }
                }
                if (turned != null) {
                    Image(
                        bitmap = turned.asImageBitmap(),
                        contentDescription = item.filename,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    ViewerNotice(stringResource(R.string.viewer_opening))
                }
            }

            is Decoded.Failed -> ViewerNotice(
                // Says what happened and nothing else. No path, no blob ID, no
                // exception — a viewer is not a debugger, and those name internals
                // to whoever is holding the phone.
                stringResource(R.string.viewer_could_not_open)
            )

            is Decoded.Ready -> Image(
                bitmap = state.bitmap.asImageBitmap(),
                contentDescription = item.filename,
                // `media-settings`. `Inside` fits a large photo exactly as `Fit` does and
                // leaves a small one at its own size.
                contentScale = if (stretch) ContentScale.Fit else ContentScale.Inside,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val shrink = 1f - DISMISS_SHRINK * travelled
                        scaleX = scale * shrink
                        scaleY = scale * shrink
                        translationX = offset.x + dismiss.x
                        translationY = offset.y + dismiss.y
                    }
                    .pointerInput(item.itemId) {
                        detectTapGestures(
                            onTap = { onReveal() },
                            onDoubleTap = { at ->
                                if (scale > MIN_SCALE) {
                                    scale = MIN_SCALE
                                    offset = Offset.Zero
                                } else {
                                    // The tapped point stays under the finger.
                                    // Scaling happens about the centre, so a point
                                    // `p` lands at `centre + (p - centre)·s + offset`;
                                    // holding `p` still is one rearrangement of that.
                                    // Double-tapping a face magnified the middle of
                                    // the frame until this was written.
                                    val next = DOUBLE_TAP_SCALE
                                    val centre = Offset(size.width / 2f, size.height / 2f)
                                    val (boxW, boxH) = panBox(
                                        size.width.toFloat(),
                                        size.height.toFloat(),
                                    )
                                    offset = clampPan(
                                        (centre - at) * (next - 1f),
                                        next,
                                        boxW,
                                        boxH,
                                    )
                                    scale = next
                                }
                            },
                        )
                    }
                    /**
                     * `viewer-paging-gestures`. **The whole point of this loop is
                     * deciding what *not* to consume.**
                     *
                     * `detectTransformGestures` — what was here — consumes every
                     * change past touch slop, including a one-finger drag it reports
                     * as `zoom = 1f`. So a horizontal swipe on a photograph never
                     * reached the pager, and the branch below discarded the pan as
                     * well: the gesture did nothing at all, which is the reported
                     * bug. There is no detector in Foundation that exposes the
                     * consumption decision, which is why this is written out.
                     *
                     * An unconsumed change propagates to the pager's own drag
                     * detector in the same pass. That is the entire mechanism.
                     */
                    .pointerInput(item.itemId) {
                        val slop = viewConfiguration.touchSlop
                        val width = size.width.toFloat()
                        val height = size.height.toFloat()

                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            // The pan's box; the dismiss still measures the screen.
                            val (panW, panH) = panBox(width, height)
                            var intent: DragIntent? = null
                            // Latched, not re-decided per event: a finger resting at
                            // a bound would otherwise alternate between panning and
                            // paging on every jitter.
                            var handedOff = false
                            var pinched = false
                            var moved = Offset.Zero
                            val speed = VelocityTracker()

                            while (true) {
                                val event = awaitPointerEvent()
                                val down = event.changes.filter { it.pressed }
                                if (down.isEmpty()) break

                                if (down.size > 1) {
                                    // A second finger outranks whatever one had begun.
                                    pinched = true
                                    val next = (scale * event.calculateZoom())
                                        .coerceIn(PINCH_FLOOR, MAX_SCALE)
                                    val pan = event.calculatePan()
                                    scale = next
                                    offset = if (next <= MIN_SCALE) {
                                        Offset.Zero
                                    } else {
                                        clampPan(offset + pan, next, panW, panH)
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    continue
                                }

                                val change = down.first()
                                val delta = change.positionChange()
                                moved += delta
                                speed.addPosition(change.uptimeMillis, change.position)

                                if (pinched) {
                                    // The tail of a pinch, one finger still down.
                                    // Not a page turn and not a dismissal.
                                    if (change.positionChanged()) change.consume()
                                    continue
                                }

                                if (intent == null) {
                                    // Below slop nothing is consumed, so the pager is
                                    // tracking this drag too and can take it whole.
                                    if (moved.getDistance() < slop) continue
                                    intent = dragIntent(moved, magnified = scale > MIN_SCALE)
                                    if (intent == DragIntent.PAGE) handedOff = true
                                }

                                if (handedOff) continue

                                when (intent) {
                                    DragIntent.PAN -> {
                                        if (handsOffToPager(offset, delta.x, scale, panW)) {
                                            handedOff = true
                                        } else {
                                            offset = clampPan(offset + delta, scale, panW, panH)
                                            if (change.positionChanged()) change.consume()
                                        }
                                    }

                                    DragIntent.DISMISS -> {
                                        dismiss += delta
                                        travelled = dismissTravel(dismiss.y, height)
                                        onDismissProgress(travelled)
                                        if (change.positionChanged()) change.consume()
                                    }

                                    else -> Unit
                                }
                            }

                            val fell = speed.calculateVelocity().y
                            if (intent == DragIntent.DISMISS && !handedOff) {
                                if (dismissCommitted(dismiss.y, fell, height)) {
                                    onDismiss()
                                } else {
                                    val from = dismiss
                                    settling.launch {
                                        animate(1f, 0f, animationSpec = tween(SETTLE_MS)) { t, _ ->
                                            dismiss = from * t
                                            travelled = dismissTravel(dismiss.y, height)
                                            onDismissProgress(travelled)
                                        }
                                    }
                                }
                            } else if (scale < MIN_SCALE) {
                                val fromScale = scale
                                val fromOffset = offset
                                settling.launch {
                                    animate(1f, 0f, animationSpec = tween(SETTLE_MS)) { t, _ ->
                                        scale = MIN_SCALE + (fromScale - MIN_SCALE) * t
                                        offset = fromOffset * t
                                    }
                                }
                            }
                        }
                    },
            )
        }
    }
}

/**
 * What a drag on a photograph is for — `viewer-paging-gestures`.
 *
 * Three claimants share one pointer stream: the pager, the pan of a magnified
 * image, and the drag that leaves the viewer. Deciding between them per event
 * makes a diagonal flick oscillate, so the decision is taken **once**, from the
 * first movement past touch slop, and held for the rest of the drag.
 */
internal enum class DragIntent { PAGE, PAN, DISMISS }

/**
 * Which claimant a drag belongs to, from the movement that crossed touch slop.
 *
 * Magnification wins outright: while an image is magnified a vertical drag pans it,
 * which `media-viewer` already required and which the dismiss must not take away.
 */
internal fun dragIntent(travelled: Offset, magnified: Boolean): DragIntent = when {
    magnified -> DragIntent.PAN
    abs(travelled.y) > abs(travelled.x) -> DragIntent.DISMISS
    else -> DragIntent.PAGE
}

/**
 * Whether a pan has run out of image and the rest of the drag belongs to the pager.
 *
 * True only when the image is **already resting on** the bound and the drag is
 * pushing further into it. A pan that is merely travelling towards a bound keeps
 * panning, and a vertical drag at a horizontal bound is not pushing into it at all,
 * so it keeps panning too.
 *
 * An unmagnified image has no pan to give, so every drag on it is the pager's.
 */
internal fun handsOffToPager(offset: Offset, deltaX: Float, scale: Float, width: Float): Boolean {
    if (scale <= MIN_SCALE) return true
    val maxX = width * (scale - 1f) / 2f
    if (maxX <= 0f) return true
    // Positive x shows the image's left edge, so pushing further right from there
    // is asking for picture that is not in the file.
    val atLeftEdge = offset.x >= maxX - BOUND_EPSILON
    val atRightEdge = offset.x <= -maxX + BOUND_EPSILON
    return (atLeftEdge && deltaX > 0f) || (atRightEdge && deltaX < 0f)
}

/**
 * How far a dismiss drag has travelled, as the 0..1 the fades are drawn from.
 *
 * Measured over [DISMISS_TRAVEL] rather than over the distance that completes the
 * gesture, so the ground is still legible at the moment the drag becomes a
 * departure. Upward travel is not a dismissal and reads as zero.
 */
internal fun dismissTravel(travelY: Float, height: Float): Float {
    if (height <= 0f) return 0f
    return (travelY / (height * DISMISS_TRAVEL)).coerceIn(0f, 1f)
}

/**
 * Whether a released dismiss drag completes.
 *
 * Distance **or** speed. A short fast flick is the gesture people make, and a
 * distance-only threshold rejects it — which reads as the gesture not existing
 * rather than as the flick having been too short.
 */
internal fun dismissCommitted(travelY: Float, speedY: Float, height: Float): Boolean =
    travelY > 0f && (travelY >= height * DISMISS_COMMIT || speedY >= DISMISS_VELOCITY)

/**
 * Keeps a magnified image from being dragged off screen.
 *
 * At scale *s* the image overhangs the viewport by *(s − 1)/2* in each direction,
 * and that overhang is exactly how far it may travel. Beyond it the user is
 * dragging emptiness, having lost the photograph they were looking at with no
 * indication of which way to drag back.
 */
internal fun clampPan(desired: Offset, scale: Float, width: Float, height: Float): Offset {
    if (scale <= MIN_SCALE) return Offset.Zero
    val maxX = width * (scale - 1f) / 2f
    val maxY = height * (scale - 1f) / 2f
    return Offset(
        desired.x.coerceIn(-maxX, maxX),
        desired.y.coerceIn(-maxY, maxY),
    )
}

/**
 * An item the app does not render — P2-18.
 *
 * A glyph, the name, the size, and one action. **Not "this item could not be
 * opened"**: it opened perfectly, there is simply nothing to draw, and telling
 * someone their stored file is broken when it is intact is the one thing a vault
 * must never get wrong.
 *
 * **Nothing here is decrypted.** Name, size and kind all come from the index row,
 * so a 4 GB archive costs exactly what a text file costs to present.
 *
 * **There is no way to open it elsewhere, and there will not be one.** No
 * `ACTION_VIEW`, no `FileProvider`, no share sheet, no in-app document renderer.
 * I3 forbids the plaintext copy another app would need; I9 makes the authorised
 * export the only way out. Every feature anyone will want to add here next is
 * refused by an invariant rather than by taste, which is the only refusal that
 * holds.
 */
@Composable
private fun FilePage(
    item: VaultItem,
    onExport: ((VaultItem) -> Unit)?,
    /** P2-10's reveal, so the item's delete and rename are reachable here too. */
    onReveal: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(item.itemId) { detectTapGestures(onTap = { onReveal() }) }
            .padding(Spacing.screenInset),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_description),
            contentDescription = stringResource(R.string.kind_file),
            tint = VaultColors.OnViewerGroundVariant,
            modifier = Modifier.size(Spacing.xxxl),
        )
        Text(
            text = item.filename,
            style = MaterialTheme.typography.bodyLarge,
            color = VaultColors.OnViewerGround,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.lg),
        )
        Text(
            text = sizeLabel(item.plaintextLength),
            style = VaultMono.bodyMedium,
            color = VaultColors.OnViewerGroundVariant,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        if (onExport != null) {
            OutlinedButton(
                onClick = { onExport(item) },
                modifier = Modifier.padding(top = Spacing.xl),
            ) {
                Text(stringResource(R.string.viewer_export_item), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * One sentence on the viewer's ground — a loading or failure state, and since
 * `viewer-chrome-and-editor` an item the editor cannot open.
 */
@Composable
fun ViewerNotice(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnViewerGroundVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(Spacing.screenInset),
        )
    }
}

// --- the player: video and audio -------------------------------------------

/**
 * The player page — video since P1-8, audio since P2-18.
 *
 * **One page for both kinds, and that is the one-player rule holding.**
 * `HorizontalPager` keeps neighbours composed, so a separate audio page owning its
 * own `ExoPlayer` would let a song and a video be alive at once — exactly the
 * failure [PlayerCensus] was built to catch. Sharing the page means the count
 * cannot rise, and P2-8's instrumented assertion covers audio without being told
 * about it.
 *
 * Audio differs in one place: no `SurfaceView`, and the space it would have taken
 * carries the item's name and length instead. That text is not chrome over content
 * — it *is* the content, since there is no picture to identify what is playing.
 */
@UnstableApi
@Composable
internal fun PlayerPage(
    item: VaultItem,
    kind: VaultKind,
    store: BlobStore,
    vmk: ByteArray,
    active: Boolean,
    controlsVisible: Boolean,
    onInteraction: () -> Unit,
    onPlayingChanged: (Boolean) -> Unit,
    /** `media-settings`. The speed this item plays at; pitch is kept, as ExoPlayer keeps it. */
    speed: Float,
    /**
     * `video-frame-thumbnail`. Where this page registers "pause, and say where" while it
     * holds the player. Null for audio.
     */
    frameHandler: MutableState<(() -> Long)?>?,
    /** `video-frame-thumbnail`. D6's line, or null when there is nothing to say. */
    frameLine: String?,
    /** `viewer-swipe-navigation`, D4. This video's thumbnail. Null for audio. */
    thumbnail: (() -> Bitmap?)?,
    /** D8. Where this video starts. Null for audio and on a received share. */
    resumeAt: ((VaultItem) -> Long?)?,
    /** D7. Where it stopped, through [VideoResume.keep]. Null for audio and on a received share. */
    onPosition: ((VaultItem, Long?) -> Unit)?,
    /** `memos-settings`. The player skips silent passages. */
    skipSilence: Boolean = false,
    /** `media-settings`, Repeat video. A slideshow passes false whatever the setting. */
    repeat: Boolean = false,
    /** How far a double tap skips. */
    skipMs: Long = SKIP_MS,
    /** The slideshow's: the item played to its end. Null in the viewer. */
    onEnded: (() -> Unit)? = null,
    /** The slideshow's pause: false holds the player where it is. */
    playing: Boolean = true,
    /** The slideshow draws its own controls and takes its own taps; the page draws neither. */
    chromeless: Boolean = false,
) {
    val context = LocalContext.current
    var player by remember(item.itemId) { mutableStateOf<ExoPlayer?>(null) }
    val ended by rememberUpdatedState(onEnded)

    // D4. Off the main thread: a cache hit returns at once, a miss decrypts one record.
    val poster by produceState<Bitmap?>(null, item.itemId, item.thumb) {
        val load = thumbnail ?: return@produceState
        value = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() }
    }

    // **The one-player rule.** A player exists only while this page is the settled
    // one, and is released the moment it is not. A pager keeps neighbours composed
    // on either axis, so constructing a player per page — the obvious arrangement —
    // leaves up to three alive and exhausts the device's decoders.
    DisposableEffect(active, item.itemId) {
        if (active) {
            val from = resumeAt?.invoke(item)
            player = ExoPlayer.Builder(context).build().apply {
                val factory = VaultDataSource.Factory(store, item.blobIds, vmk, item.plaintextLength)
                setMediaSource(
                    ProgressiveMediaSource.Factory(factory).createMediaSource(
                        MediaItem.fromUri(VaultDataSource.uriFor(item.blobIds.first()))
                    )
                )
                // D8. Before `prepare`, so the first frame decoded is the stopping point's
                // rather than 0:00 followed by a jump.
                if (from != null) seekTo(from)
                repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                skipSilenceEnabled = skipSilence
                prepare()
                playWhenReady = playing
                setPlaybackSpeed(speed)
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) ended?.invoke()
                    }
                })
            }
            PlayerCensus.lastSpeed = speed
            PlayerCensus.lastPlayer = player?.let { java.lang.ref.WeakReference(it) }
            PlayerCensus.live.incrementAndGet()
            // D8. Arriving at a video that resumes raises the revealed layer, so the readout
            // shows where playback began; it lingers out as it always does.
            if (from != null) onInteraction()
        }
        onDispose {
            player?.let {
                // D7. Where it stopped, reported as the player goes — swiped away, the
                // viewer closed, or the vault locked under it.
                onPosition?.invoke(item, stoppedAt(it, item))
                it.release()
                PlayerCensus.live.decrementAndGet()
            }
            player = null
        }
    }

    // D7. Refreshed once a second while it plays, so a lock from the background — which
    // cannot read a player — has a value no more than a second old.
    LaunchedEffect(player) {
        val held = player ?: return@LaunchedEffect
        val report = onPosition ?: return@LaunchedEffect
        while (true) {
            delay(1_000)
            if (held.isPlaying) report(item, stoppedAt(held, item))
        }
    }

    // `video-frame-thumbnail`. Registered while this page holds a player — which is only
    // while it is the settled page — and cleared the moment it lets the player go. The
    // clear checks it is still this page's handler, so a page leaving never unregisters
    // the one that arrived.
    DisposableEffect(player, frameHandler) {
        val slot = frameHandler
        val held = player
        val handler: (() -> Long)? = if (slot != null && held != null) {
            {
                held.playWhenReady = false
                held.currentPosition.coerceAtLeast(0L)
            }
        } else {
            null
        }
        if (slot != null && handler != null) slot.value = handler
        onDispose {
            if (slot != null && slot.value === handler) slot.value = null
        }
    }

    // The slideshow's Pause and Play, applied to the player it holds.
    LaunchedEffect(player, playing) { player?.playWhenReady = playing }

    val active_ = player
    if (active_ == null) {
        // D4. Composed but not settled — a neighbour under the finger — or still being
        // built: the video's thumbnail on the black ground, so a swipe shows the next video
        // rather than a black page. Nothing is decoded and no player is made for it.
        Poster(poster)
        return
    }

    PlayerSurface(
        item, kind, active_, controlsVisible && !chromeless, onInteraction, onPlayingChanged, frameLine, poster,
        skipMs = skipMs,
        taps = !chromeless,
    )
}

/**
 * A video's thumbnail on the viewer's ground — `viewer-swipe-navigation`, D4.
 *
 * Fitted, with nothing around it: §6.6 binds this exactly as it binds a photograph — no card,
 * elevation, radius or border. With no thumbnail it is the black ground alone, which is what
 * every page showed before. It is a picture, not a player, so the one-player rule never sees it.
 */
@Composable
private fun Poster(bitmap: Bitmap?, alpha: Float = 1f) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .background(VaultColors.ViewerGround),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                // The page is the item; its name is not read twice.
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Where [player] is, as the stopping point D6 keeps — `viewer-swipe-navigation`. The
 * container's duration first, the row's where the player has not read one yet.
 */
@UnstableApi
private fun stoppedAt(player: ExoPlayer, item: VaultItem): Long? = VideoResume.keep(
    player.currentPosition.coerceAtLeast(0L),
    player.duration.takeIf { it > 0 } ?: item.durationMs,
)

@UnstableApi
@Composable
private fun PlayerSurface(
    item: VaultItem,
    kind: VaultKind,
    player: ExoPlayer,
    // P2-10. Reveal state lives in `VaultViewer` now, because a still image needs
    // the same layer and the same linger. This page reads it and reports the two
    // things only it knows: that the user touched the video, and whether playback
    // is running.
    controlsVisible: Boolean,
    onInteraction: () -> Unit,
    onPlayingChanged: (Boolean) -> Unit,
    /** `video-frame-thumbnail`. D6's line, drawn directly above the transport. */
    frameLine: String?,
    /** `viewer-swipe-navigation`, D4. Over the surface until the first frame is drawn. */
    poster: Bitmap?,
    /** `media-settings`, Double-tap to skip. */
    skipMs: Long = SKIP_MS,
    /** False under a slideshow, which takes the taps itself. */
    taps: Boolean = true,
) {
    var playing by remember { mutableStateOf(true) }
    // The readout a double tap leaves — "+15 s" on the side tapped — and when it was left, so
    // a second tap restarts its time on screen.
    var skipped by remember { mutableStateOf<SkipReadout?>(null) }
    LaunchedEffect(skipped) {
        if (skipped == null) return@LaunchedEffect
        delay(SKIP_READOUT_MS)
        skipped = null
    }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var firstFrame by remember(player) { mutableStateOf(false) }
    var videoSize by remember(player) { mutableStateOf(player.videoSize) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                onPlayingChanged(isPlaying)
            }

            override fun onRenderedFirstFrame() {
                firstFrame = true
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                videoSize = size
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            // Leaving for another page must not leave the layer pinned up by a
            // video that is no longer on screen.
            onPlayingChanged(true)
        }
    }

    // Position ticks while the controls are up. When they are hidden there is
    // nothing to update, so nothing wakes.
    LaunchedEffect(player, controlsVisible) {
        while (controlsVisible) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(0L)
            delay(250)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (!taps) Modifier else Modifier.pointerInput(player, skipMs) {
                    detectTapGestures(
                        // A tap recalls the controls. It is explicitly **not** a skip
                        // — the user reaching for a hidden scrubber must not seek by
                        // touching the screen.
                        onTap = { onInteraction() },
                        // A single tap is not a skip. Double tap on the left half
                        // goes back, right half forward, clamped at both ends.
                        onDoubleTap = { offset ->
                            val forward = offset.x > size.width / 2
                            skip(player, if (forward) skipMs else -skipMs)
                            skipped = SkipReadout(forward, skipMs, System.nanoTime())
                            onInteraction()
                        },
                    )
                },
            ),
    ) {
        if (kind == VaultKind.VIDEO) {
            // A bare SurfaceView, not media3-ui's PlayerView — see this file's KDoc.
            // The surface takes the video's shape, not the screen's — a SurfaceView scales
            // each frame to fill itself, so a full-screen one stretched every video whose
            // shape differed from the display. The decoder applies the file's rotation, and
            // media3 reports the size already turned, so width over height is the frame on screen.
            val ratio = videoSize
                .takeIf { it.width > 0 && it.height > 0 }
                ?.let { it.width * it.pixelWidthHeightRatio / it.height }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { ctx -> SurfaceView(ctx).also { player.setVideoSurfaceView(it) } },
                    modifier = if (ratio != null) Modifier.aspectRatio(ratio) else Modifier.fillMaxSize(),
                )
            }
            // `viewer-swipe-navigation`, D4. The thumbnail holds over the surface until the
            // player reports its first frame, so a start is never a black flash, then leaves
            // with §8's exit motion — the same picture handing over, not a cut.
            val cover by animateFloatAsState(
                targetValue = if (firstFrame) 0f else 1f,
                animationSpec = tween(REVEAL_EXIT_MS, easing = EaseIn),
                label = "poster",
            )
            if (cover > 0f) Poster(poster, cover)
        } else {
            // P2-18. No surface is created and none is attached — an audio item has
            // no picture, and a black rectangle with a scrubber under it does not
            // say which of three songs is playing. §6.6 is not in tension with this:
            // there is no photograph here for the words to sit on.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.screenInset),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_audiotrack),
                    contentDescription = stringResource(R.string.kind_audio),
                    tint = VaultColors.OnViewerGroundVariant,
                    modifier = Modifier.size(Spacing.xxxl),
                )
                Text(
                    text = item.filename,
                    style = MaterialTheme.typography.bodyLarge,
                    color = VaultColors.OnViewerGround,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Spacing.lg),
                )
                durationLabel(item.durationMs)?.let { label ->
                    Text(
                        text = label,
                        style = VaultMono.bodyMedium,
                        color = VaultColors.OnViewerGroundVariant,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
            }
        }

        // `destination-settings-and-slideshow` D8. What a double tap did, on the half that was
        // tapped: "+15 s" or "−15 s", in the viewer's ink straight on the media (§6.6), gone after
        // a moment. A polite live region, so a screen reader hears the skip it asked for.
        val readout = skipped
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.xl),
            contentAlignment = if (readout?.forward == false) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            AnimatedVisibility(
                visible = readout != null,
                enter = fadeIn(tween(REVEAL_ENTER_MS, easing = EaseOut)),
                exit = fadeOut(tween(REVEAL_EXIT_MS, easing = EaseIn)),
            ) {
                val shown = remember { mutableStateOf("") }
                if (readout != null) {
                    shown.value = (if (readout.forward) "+" else "−") + "${readout.ms / 1_000} s"
                }
                val seconds = ((readout?.ms ?: skipMs) / 1_000).toInt()
                val spoken = pluralStringResource(
                    if (readout?.forward != false) R.plurals.viewer_skip_forward else R.plurals.viewer_skip_back,
                    seconds, seconds,
                )
                Text(
                    text = shown.value,
                    style = VaultMono.titleLarge,
                    color = VaultColors.OnViewerGround,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = spoken
                    },
                )
            }
        }

        // `video-frame-thumbnail`. One column so the line sits directly on top of the
        // transport rather than at a guessed height above it. The inset moves up to the
        // column and is consumed there, so the controls' own `safeDrawingPadding` adds
        // nothing and they stay exactly where they were.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .safeDrawingPadding(),
        ) {
            if (kind == VaultKind.VIDEO) FrameLine(frameLine)
            if (controlsVisible) {
                PlayerControls(
                    playing = playing,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onPlayPause = {
                        player.playWhenReady = !player.playWhenReady
                        onInteraction()
                    },
                    onSeek = { fraction ->
                        if (durationMs > 0) player.seekTo((durationMs * fraction).toLong())
                        onInteraction()
                    },
                )
            }
        }
    }
}

/**
 * The line that says what choosing a frame did — `video-frame-thumbnail`, D6.
 *
 * ***On the video, and nothing around it.*** `bodyMedium` in the viewer's pinned
 * foreground, the same ink as the position readout beside it, with no card, elevation,
 * radius or border: §6.6 binds the player's chrome and this is chrome. It enters and
 * leaves with §8's motion and is gone three seconds after it arrives.
 *
 * **Always composed on a video, and empty when there is nothing to say.** A polite live
 * region announces a change to its text, which is the one event this can rely on; a node
 * that appears from nothing is not reliably announced. The words are held through the
 * fade so they leave rather than vanish, then emptied so a screen reader moving through
 * the page finds nothing left behind. Focus is never moved to it.
 */
@Composable
private fun FrameLine(text: String?) {
    var shown by remember { mutableStateOf("") }
    if (text != null) shown = text
    val alpha by animateFloatAsState(
        targetValue = if (text != null) 1f else 0f,
        animationSpec = if (text != null) {
            tween(REVEAL_ENTER_MS, easing = EaseOut)
        } else {
            tween(REVEAL_EXIT_MS, easing = EaseIn)
        },
        finishedListener = { if (it == 0f) shown = "" },
        label = "frame line",
    )
    Text(
        text = shown,
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnViewerGround,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenInset)
            .graphicsLayer { this.alpha = alpha }
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * The player's controls — `DESIGN.md` §7.3.
 *
 * §6.6 binds the player exactly as it binds a still image: no card, no elevation,
 * no corner radius, no border. The controls sit directly on the media and take
 * themselves away, so what is left is the video on pure black.
 *
 * The position readout is [VaultMono] with tabular figures, so the row does not
 * shift as the digits change — §5.1's rule, and the same reason the trial
 * countdown uses it.
 */
@Composable
private fun PlayerControls(
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // `player-controls-and-menu`. **Its own inset, and this is half of why
            // play/pause felt dead.** Without it the row's lower part sits under the
            // system's gesture area, which takes those touches whatever this app
            // draws. The item behind is still not inset — §6.6's rule is that the
            // *media* is never inset to make room for a row, not that a row may lie
            // under the navigation bar.
            .safeDrawingPadding()
            .padding(horizontal = Spacing.sm, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        IconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(Spacing.touchTarget),
        ) {
            Icon(
                painter = painterResource(
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow
                ),
                contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                tint = VaultColors.OnViewerGround,
            )
        }

        Text(
            text = positionLabel(positionMs, durationMs),
            style = VaultMono.bodyMedium,
            color = VaultColors.OnViewerGround,
        )

        val seek = stringResource(R.string.seek)
        Slider(
            value = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
            onValueChange = onSeek,
            colors = SliderDefaults.colors(
                thumbColor = VaultColors.OnViewerGround,
                activeTrackColor = VaultColors.OnViewerGround,
                inactiveTrackColor = VaultColors.OnViewerGroundVariant,
            ),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = seek },
        )
    }
}

/**
 * `position / duration`, both in the same shape so the row's width is stable.
 *
 * An unknown duration shows the position alone rather than inventing a total —
 * the same rule the grid's tile follows.
 */
internal fun positionLabel(positionMs: Long, durationMs: Long): String {
    val position = durationLabel(positionMs.coerceAtLeast(0L)) ?: "0:00"
    val total = durationLabel(durationMs)
    return if (total != null) "$position / $total" else position
}

/** One double tap's readout: which way, how far, and when — so the same skip twice restarts it. */
private data class SkipReadout(val forward: Boolean, val ms: Long, val at: Long)

/** How long a skip's readout stays — long enough to read "+15 s", short enough not to linger. */
private const val SKIP_READOUT_MS = 900L

/** Clamped seek. Exposed so the skip arithmetic is testable without a UI. */
@UnstableApi
fun skip(player: ExoPlayer, deltaMs: Long) {
    player.seekTo(clampSeek(player.currentPosition, deltaMs, player.duration))
}

/**
 * Where a skip lands. Separate from the player so the clamping is checkable on
 * its own — the ends are where a skip goes wrong, and neither wrapping nor
 * erroring is acceptable there.
 */
fun clampSeek(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
    val target = positionMs + deltaMs
    val upper = if (durationMs > 0) durationMs else Long.MAX_VALUE
    return target.coerceIn(0L, upper)
}
