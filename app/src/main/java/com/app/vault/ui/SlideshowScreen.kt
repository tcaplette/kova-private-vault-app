package com.app.vault.ui

import android.app.Activity
import android.graphics.Bitmap
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import com.app.vault.BlobStore
import com.app.vault.GridPreference
import com.app.vault.MediaSettings
import com.app.vault.R
import com.app.vault.Slideshow
import com.app.vault.SlideshowSettings
import com.app.vault.SlideshowSpeed
import com.app.vault.VaultItem
import com.app.vault.VaultKind
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * A slideshow — `slideshow`, design D9. Started from the viewer's ⋮ on the photo or video on
 * screen, over the list the viewer was opened from, and drawn by the viewer over itself.
 *
 * **What Google Photos, Apple Photos and Samsung Gallery share**: full-bleed on black with the
 * system bars gone; a photo stays for the chosen time and fades to the next over 500ms; a video
 * plays to its end; a swipe moves on by hand; a tap brings Close, Pause and Options and they leave
 * again on their own. Back or Close ends it on the item showing.
 *
 * **One player at a time.** A video's page holds a player only while it is the slide on screen —
 * the outgoing slide of a fade shows its thumbnail — and the viewer under this one lets go of its
 * own player while this is up.
 *
 * **The screen stays on while it plays, and only then.** Paused, behind its Options, or with the
 * activity stopped, the window lets go of the screen, so it times out and the vault locks through
 * the ordinary path. `AutoLock` is not called, extended or read — `runtime-hardening` permits two
 * suppressions, and this is not a third.
 */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SlideshowScreen(
    /** The list the viewer was opened from — the grid's, as filtered, foldered and searched. */
    items: List<VaultItem>,
    /** Where in [items] the slideshow starts: the item on screen when it was chosen. */
    start: Int,
    store: BlobStore,
    vmk: ByteArray,
    fullImageFor: (VaultItem) -> Bitmap?,
    thumbnailFor: (VaultItem) -> Bitmap?,
    /** Media's stretch, for photos, and its speed, for videos. Repeat video is not read. */
    media: MediaSettings,
    /** The slideshow ended — Back, Close, or the end of the list — on this item. */
    onClose: (VaultItem) -> Unit,
) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(GridPreference.slideshowSettings(context)) }
    fun change(next: SlideshowSettings) {
        settings = next
        GridPreference.setSlideshowSettings(context, next)
    }

    // The pass in play, where in it the slideshow is, and a count of every move — so a swipe
    // to the same item, or a second pass starting on it, is still a new slide with a new timer.
    var queue by remember { mutableStateOf(Slideshow.order(items.size, start, settings.shuffle)) }
    var at by remember { mutableIntStateOf(0) }
    var moves by remember { mutableIntStateOf(0) }
    val current = items[queue[at]]

    var paused by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var stopped by remember { mutableStateOf(false) }
    val playing = !paused && !options && !stopped

    fun forward() {
        when {
            at < queue.lastIndex -> at++
            settings.repeat -> {
                queue = Slideshow.nextPass(items.size, settings.shuffle, last = queue[at])
                at = 0
            }
            // Repeat off: the slideshow ends after the last item, on it.
            else -> return onClose(current)
        }
        moves++
    }

    fun back() {
        if (at > 0) at--
        moves++
    }

    // A photo waits the interval; a video waits for its own end. Keyed on the move, so a swipe
    // restarts the interval and a resume after a pause starts a full one.
    LaunchedEffect(moves, playing, settings.speed) {
        if (!playing) return@LaunchedEffect
        val wait = Slideshow.interval(current.kind, settings.speed) ?: return@LaunchedEffect
        delay(wait)
        forward()
    }

    // --- the revealed layer: the viewer's linger, and its rule that a pause holds it up ---
    var revealed by remember { mutableStateOf(false) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(revealed, touchedAt, paused) {
        if (!revealed || paused) return@LaunchedEffect
        delay(SLIDESHOW_LINGER_MS)
        revealed = false
    }
    fun touch() {
        revealed = true
        touchedAt = System.nanoTime()
    }

    // --- the screen, the bars, and the activity ---
    val window = (context as? Activity)?.window
    DisposableEffect(window, playing) {
        if (playing) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(window) {
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    // The activity's own lifecycle, for `MusicPlayback`'s reason: no dependency for one observer.
    val lifecycle = (context as? androidx.activity.ComponentActivity)?.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> stopped = true
                Lifecycle.Event.ON_START -> stopped = false
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    BackHandler { onClose(current) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    // §8 has no fade this long: 500ms is the slideshow convention, a picture handing over to the
    // next rather than a control appearing. Compose does not honour the animator scale on its
    // own, so at zero the slides cut.
    val cut = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VaultColors.ViewerGround)
            .pointerInput(Unit) {
                // A tap shows the controls, or hides them if they are up. It never ends the show.
                detectTapGestures(onTap = { if (revealed) revealed = false else touch() })
            }
            .pointerInput(Unit) {
                var travel = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travel = 0f },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        travel += delta
                    },
                    onDragEnd = {
                        if (abs(travel) > SWIPE_PX) {
                            // Toward the start of the line is "next", whichever way the line reads.
                            val next = if (rtl) travel > 0 else travel < 0
                            if (next) forward() else back()
                        }
                    },
                )
            },
    ) {
        AnimatedContent(
            targetState = moves,
            transitionSpec = {
                if (cut) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    fadeIn(tween(FADE_MS, easing = LinearEasing)) togetherWith
                        fadeOut(tween(FADE_MS, easing = LinearEasing))
                }
            },
            label = "slide",
        ) { move ->
            // The slide this content was composed for, captured once: the outgoing slide of a
            // fade keeps its own item while the incoming one takes the new.
            val item = remember(move) { current }
            val onScreen = move == moves
            // One node per slide, named by its item — a photo's image names it already; a
            // video has no picture of its own to carry the name.
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag(if (onScreen) SLIDE_TAG else "$SLIDE_TAG-leaving")
                    .semantics(mergeDescendants = true) {
                        if (item.kind == VaultKind.VIDEO) contentDescription = item.filename
                    },
            ) {
            if (item.kind == VaultKind.VIDEO) {
                PlayerPage(
                    item = item,
                    kind = VaultKind.VIDEO,
                    store = store,
                    vmk = vmk,
                    active = onScreen,
                    controlsVisible = false,
                    onInteraction = {},
                    onPlayingChanged = {},
                    speed = media.speed.factor,
                    frameHandler = null,
                    frameLine = null,
                    thumbnail = { thumbnailFor(item) },
                    resumeAt = null,
                    onPosition = null,
                    // A slideshow that never moves on is not a slideshow — D9.
                    repeat = false,
                    onEnded = { if (onScreen) forward() },
                    playing = playing,
                    chromeless = true,
                )
            } else {
                SlidePhoto(item, fullImageFor, media.stretchSmall)
            }
            }
        }

        // The scrim and the three controls, entering and leaving with §8's timings.
        AnimatedVisibility(
            visible = revealed,
            enter = fadeIn(tween(REVEAL_ENTER_MS, easing = EaseOut)),
            exit = fadeOut(tween(REVEAL_EXIT_MS, easing = EaseIn)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    // 60% black: the viewer's light ink stays above 4.5:1 over a white photo.
                    .background(VaultColors.Scrim.copy(alpha = SCRIM_ALPHA))
                    .safeDrawingPadding()
                    .padding(Spacing.sm),
            ) {
                IconButton(
                    onClick = { onClose(current) },
                    modifier = Modifier.align(Alignment.TopStart).size(Spacing.touchTarget),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.slideshow_end),
                        tint = VaultColors.OnViewerGround,
                    )
                }
                IconButton(
                    onClick = {
                        paused = !paused
                        touch()
                    },
                    modifier = Modifier.align(Alignment.Center).size(PLAY_SIZE),
                ) {
                    Icon(
                        painter = painterResource(if (paused) R.drawable.ic_play_arrow else R.drawable.ic_pause),
                        contentDescription = stringResource(if (paused) R.string.slideshow_play else R.string.slideshow_pause),
                        tint = VaultColors.OnViewerGround,
                        modifier = Modifier.size(PLAY_GLYPH),
                    )
                }
                IconButton(
                    onClick = { options = true },
                    modifier = Modifier.align(Alignment.BottomEnd).size(Spacing.touchTarget),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_tune),
                        contentDescription = stringResource(R.string.slideshow_options),
                        tint = VaultColors.OnViewerGround,
                    )
                }
            }
        }
    }

    if (options) {
        SlideshowOptions(
            settings = settings,
            onChange = { next ->
                // Shuffle turned on or off applies at once: the rest of the pass is redrawn
                // from the item on screen, which stays where it is.
                if (next.shuffle != settings.shuffle) {
                    queue = Slideshow.order(items.size, queue[at], next.shuffle)
                    at = 0
                }
                change(next)
            },
            onDismiss = {
                options = false
                touch()
            },
        )
    }
}

/** A photo in the slideshow: decoded off the main thread at Media's quality, fitted by its stretch. */
@Composable
private fun SlidePhoto(item: VaultItem, fullImageFor: (VaultItem) -> Bitmap?, stretch: Boolean) {
    val bitmap by produceState<Bitmap?>(null, item.itemId) {
        value = withContext(Dispatchers.IO) { runCatching { fullImageFor(item) }.getOrNull() }
    }
    Box(Modifier.fillMaxSize()) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = item.filename,
                // `media-settings`: `Inside` leaves a small photo at its own size.
                contentScale = if (stretch) ContentScale.Fit else ContentScale.Inside,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The slideshow's Options — set from inside it, the way Apple and Samsung do it, with the rows the
 * destination settings sheets are built from. The slideshow waits while this is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlideshowOptions(
    settings: SlideshowSettings,
    onChange: (SlideshowSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = VaultColors.Surface,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.slideshow_options),
                style = MaterialTheme.typography.titleLarge,
                color = VaultColors.OnSurface,
                modifier = Modifier
                    .padding(start = Spacing.screenInset, end = Spacing.screenInset)
                    .semantics { heading() },
            )
            Text(
                text = stringResource(R.string.slideshow_options_lead),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(
                    start = Spacing.screenInset,
                    end = Spacing.screenInset,
                    top = Spacing.xs,
                    bottom = Spacing.sm,
                ),
            )
            RevealChoice(
                name = stringResource(R.string.speed),
                icon = R.drawable.ic_timer,
                current = settings.speed,
                choices = SlideshowSpeed.entries,
                label = { pluralStringResource(R.plurals.seconds, it.seconds, it.seconds) },
                detail = { stringResource(it.detail) },
                one = stringResource(R.string.reveal_speed),
                many = stringResource(R.string.reveal_speeds),
                onChoose = { onChange(settings.copy(speed = it)) },
                footnote = stringResource(R.string.slideshow_speed_footnote),
            )
            SwitchRow(
                name = stringResource(R.string.slideshow_repeat),
                supporting = if (settings.repeat) {
                    stringResource(R.string.slideshow_repeat_on)
                } else {
                    stringResource(R.string.slideshow_repeat_off)
                },
                icon = R.drawable.ic_repeat,
                checked = settings.repeat,
                onChange = { onChange(settings.copy(repeat = it)) },
            )
            SwitchRow(
                name = stringResource(R.string.slideshow_shuffle),
                supporting = if (settings.shuffle) {
                    stringResource(R.string.slideshow_shuffle_on)
                } else {
                    stringResource(R.string.slideshow_shuffle_off)
                },
                icon = R.drawable.ic_shuffle,
                checked = settings.shuffle,
                onChange = { onChange(settings.copy(shuffle = it)) },
            )
            Spacer(Modifier.height(Spacing.section))
        }
    }
}

/** The slide on screen, for the device test that follows the show. */
internal const val SLIDE_TAG = "slide"

/** The cross-fade between slides. */
private const val FADE_MS = 500

/** The viewer's reveal timeout, for the slideshow's controls. */
private const val SLIDESHOW_LINGER_MS = 3_000L

/** How far a finger travels before a drag is a swipe to the next or previous slide. */
private const val SWIPE_PX = 120f

/** The scrim under the controls — the least that keeps the viewer's ink at 4.5:1 over white. */
private const val SCRIM_ALPHA = 0.6f

private val PLAY_SIZE = 64.dp
private val PLAY_GLYPH = 40.dp
