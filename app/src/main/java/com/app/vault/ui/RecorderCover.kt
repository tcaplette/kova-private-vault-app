package com.app.vault.ui

import androidx.activity.compose.BackHandler
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.app.vault.R
import com.app.vault.Recorder
import com.app.vault.RecorderStore
import com.app.vault.Trigger
import com.app.vault.ui.theme.RecorderColors
import com.app.vault.ui.theme.RecorderTheme
import com.app.vault.ui.theme.RecorderType
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.material3.Text as MaterialText

/**
 * The voice recorder cover — `music-recorder-covers`.
 *
 * **A cover is worth exactly as much as it is convincing.** *Open it and try to use it
 * as the app it claims to be* is the whole of the public advice for spotting a
 * disguised vault, and a recorder answers it by recording: pressing the button
 * captures real audio, the list holds real files, and playing one back plays what was
 * said into the microphone.
 *
 * - **A real capture.** Record, pause and resume within one recording, a live input
 *   meter, and an elapsed timer that counts recorded time only.
 * - **A real list.** Title, date, duration and size per row, playback with a seek
 *   control, rename, and delete that confirms once. A total under the list, because a
 *   recorder that hides how much of the phone it is using is a recorder nobody trusts.
 * - **A search field that is always there** — including when there is nothing to
 *   search. It searches, and searches only, since `pattern-cover-trigger`: a control
 *   that vanished in the empty state would have been a landmark missing exactly when a
 *   fresh install needs every landmark it can get for the grid drawn over it.
 * - **Its own palette and type**, never the vault's, following the device's dark-mode
 *   flag; red for recording and for nothing else; no app bar, no settings, no about.
 *
 * ***The trigger is a pattern drawn on an invisible grid — `pattern-cover-trigger`.***
 * A full-screen, non-consuming handler ([CoverGestures.silentPatternGrid]) observes
 * the whole screen without ever taking a pointer event from whatever is under it, so
 * the recording list's own scroll and the search field's own typing and selection
 * behave exactly as they would with no trigger configured.
 * The one exception is the dots the owner's hold brings up (`cover-hold-dots`): a 2-second
 * hold in the one area of the screen the owner chose ([CoverGestures.silentHold], `holdArea`)
 * reports `onHold`, and `showDots` then draws the grid and holds the cover still exactly as
 * setup does, until the pattern is judged, Back is pressed or the cover leaves the foreground;
 * a refused pattern on them shows the error state (`dotsError`). A hold anywhere else is not
 * the trigger's, and the control under it does what it always does. The field's contents are
 * never persisted regardless — a draft would be a plaintext record of something typed
 * into what claims to be an ordinary recorder, which is I8's tell by another route.
 *
 * ***Nothing here reads the stored trigger, judges an attempt, or asks for a
 * permission.*** The cover reports what was typed; `CoverTrigger` decides. The
 * microphone grant is taken in identity setup, inside the vault, because a system
 * permission dialog carries the **application** label — the vault's real name.
 *
 * ***And it can no longer ask by accident.*** There is deliberately no callback here
 * to request the grant with. The record control used to raise the system dialog when
 * the microphone was missing, which put "Allow Vault to record audio?" in front of
 * whoever pressed record — the one fact this cover exists to withhold, reached by the
 * first control anybody touches. Without the grant the control is disabled and the
 * empty state says which Settings toggle restores it, which is what every real
 * recorder shows once Android has stopped offering the dialog.
 */
@Composable
fun RecorderCover(
    hasPermission: Boolean,
    onTrigger: (Trigger) -> Unit,
    modifier: Modifier = Modifier,
    /** False while trigger setup hosts the cover: a rehearsal writes no file. */
    persist: Boolean = true,
    /**
     * How many distinct nodes the pattern currently drawn holds, reported as each new
     * one is reached.
     *
     * ***Null on a live cover, and that is what keeps the cover silent.*** A launcher
     * entry that visibly reacted to a gesture would tell whoever is holding the phone
     * that a gesture exists. Only trigger setup passes a reporter, so a live cover has
     * nothing to switch off and nobody has to remember to set a flag.
     */
    onProgress: ((Int) -> Unit)? = null,
    /**
     * `cover-hold-dots` D4. The live cover's dots, shown after the owner's hold: drawn and held
     * still exactly as setup draws and holds them, with their line and no count reported. False
     * is the live cover as it always was.
     */
    showDots: Boolean = false,
    /** D4. A refused pattern on the shown dots: the error state, and no further path drawn. */
    dotsError: Boolean = false,
    /** D2. The stored hold area, `0..11` — null while setup hosts the cover, and the hold is inert. */
    holdArea: Int? = null,
    /** D2. A 2-second hold in [holdArea]: the activity brings up the dots. */
    onHold: () -> Unit = {},
) {
    RecorderTheme {
        // Live only — `persist` is false exactly when setup hosts the cover. The top of the
        // window is then the vault's bar, and the cover's icons over it went dark-on-dark.
        if (persist) CoverSystemBars()
        Box(
            modifier
                .fillMaxSize()
                .background(RecorderColors.Ground),
        ) {
            RecorderScreen(
                hasPermission = hasPermission,
                onTrigger = onTrigger,
                persist = persist,
                onProgress = onProgress,
                showDots = showDots,
                dotsError = dotsError,
                holdArea = holdArea,
                onHold = onHold,
            )
        }
    }
}

@Composable
private fun RecorderScreen(
    hasPermission: Boolean,
    onTrigger: (Trigger) -> Unit,
    persist: Boolean,
    onProgress: ((Int) -> Unit)?,
    showDots: Boolean,
    dotsError: Boolean,
    holdArea: Int?,
    onHold: () -> Unit,
) {
    val context = LocalContext.current
    val recorder = remember { Recorder() }

    var recordings by remember { mutableStateOf(RecorderStore.recordings(context)) }
    var query by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var renaming by remember { mutableStateOf<RecorderStore.Recording?>(null) }
    var deleting by remember { mutableStateOf<RecorderStore.Recording?>(null) }
    var currentFile by remember { mutableStateOf<java.io.File?>(null) }
    // The one row showing its controls, by name — `voice-recorder-layout`. Held here and
    // not in the list, so it survives the list giving way to a recording and returns with it.
    var expanded by remember { mutableStateOf<String?>(null) }

    // The waveform's readings: a fixed ring, written at `readings % RING` by the meter loop.
    // `readings` is the state the canvas reads, so each new reading redraws it.
    val levels = remember { FloatArray(RING) }
    var readings by remember { mutableIntStateOf(0) }

    val playback = rememberRecorderPlayback()

    // §8's reduced motion. Compose does not honour the system's animator scale on its
    // own — `voice-memo-layout` measured it — so it is read and the moves snap.
    val reduced = Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    ) == 0f

    fun refresh() {
        recordings = RecorderStore.recordings(context)
    }

    fun startRecording() {
        // Unreachable while `RecordControl` is disabled, and kept as the guard rather
        // than the request that used to be here: nothing in this cover may raise a
        // system dialog carrying the application label.
        if (!hasPermission) return
        val file = RecorderStore.newFile(context)
        if (!recorder.start(context, file)) return
        // The list and its controls are about to give way to the recording view, and audio
        // never plays with its controls hidden — nor into the microphone.
        playback.pause()
        currentFile = file
        recording = true
        paused = false
        elapsedMs = 0L
        readings = 0
    }

    fun stopRecording() {
        val file = currentFile ?: return
        if (persist) {
            val duration = recorder.stop(file)
            if (duration > 0L) {
                RecorderStore.add(context, file, duration)
                refresh()
            }
        } else {
            // Trigger setup is hosting this cover. `cover-trigger` requires that no
            // cover control writes anything here, so the capture is thrown away
            // rather than filed: the control still works, and nothing survives it.
            recorder.discard(file)
        }
        currentFile = null
        recording = false
        paused = false
        elapsedMs = 0L
    }

    // The timer and the waveform, while capture runs. Sixteen readings a second is smooth
    // to the eye on a scrolling waveform and is not a per-frame wake-up.
    LaunchedEffect(recording, paused) {
        while (recording && !paused) {
            elapsedMs = recorder.elapsedMs()
            levels[readings % RING] = recorder.level()
            readings++
            delay(METER_MS)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // A capture running when the screen goes away is discarded, not filed: it
            // ends without the user having ended it, and half a recording nobody asked
            // for is worse than none.
            currentFile?.let { recorder.discard(it) }
        }
    }

    BackHandler(renaming != null || deleting != null) {
        renaming = null
        deleting = null
    }

    val shown = recordings.filter { it.title.contains(query.trim(), ignoreCase = true) }

    // The trigger's drawn path, live — `pattern-cover-trigger`. Read only by
    // `PatternGridOverlay` below, and only while the grid is shown: during setup, or on a
    // live cover whose dots the owner's hold brought up (`cover-hold-dots`). Otherwise
    // nothing reports a path, this never changes, and the overlay is never composed.
    var pathDrawn by remember { mutableStateOf(emptyList<Int>()) }
    val gridShown = onProgress != null || showDots
    // Each showing of the dots starts from nothing, not from the last try's shape.
    LaunchedEffect(showDots) { if (showDots) pathDrawn = emptyList() }
    // D2. Read at each touch by the hold, which is inactive while the dots are up.
    val dotsUp by rememberUpdatedState(showDots)
    // D4's line sits below the status bar this cover draws under.
    val statusTop = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }

    Box(
        Modifier
            .fillMaxSize()
            // The trigger. Spans the whole screen and never consumes below the
            // floor, so the list's scroll and the search field's own typing and
            // selection behave exactly as they would with no trigger configured.
            // See `CoverGestures.silentPatternGrid`.
            .silentPatternGrid(
                // A reporter is what makes the grid consume, so the shown grid holds the
                // cover still exactly as setup does; only setup is told the count.
                onProgress = if (gridShown) {
                    // The refused shape stays on screen through the error state.
                    { path: List<Int> -> if (!dotsError) pathDrawn = path; onProgress?.invoke(path.size) }
                } else {
                    null
                },
                onDrawn = { dots -> onTrigger(Trigger.Pattern(dots)) },
            )
            // `cover-hold-dots` D2. Beside the grid, over the whole screen: a hold in the owner's
            // area brings up the dots; anywhere else it takes nothing from the control beneath.
            .silentHold(holdArea, active = { !dotsUp }, onHold = onHold),
    ) {
    // The list's area: the list, or the recording while one runs — `voice-recorder-layout`.
    // The search field stays above it in every state, because the trigger is performed there.
    val main: @Composable ColumnScope.() -> Unit = {
        Spacer(Modifier.statusBarsPadding())
        SearchField(
            query = query,
            onQuery = { query = it },
        )

        Box(Modifier.weight(1f)) {
            Crossfade(
                recording,
                animationSpec = if (reduced) snap() else tween(CROSSFADE_MS),
                label = "recorder-area",
            ) { capturing ->
                when {
                    capturing -> RecordingView(paused, elapsedMs, levels, readings)
                    recordings.isEmpty() -> EmptyRecorder(hasPermission)
                    shown.isEmpty() -> NoMatches(query)
                    else -> RecordingList(
                        recordings = shown,
                        playback = playback,
                        expanded = expanded,
                        reduced = reduced,
                        onToggle = { target ->
                            if (expanded == target.name) {
                                // Collapsing pauses: audio never plays with its controls hidden.
                                expanded = null
                                if (playback.current?.name == target.name) playback.pause()
                            } else {
                                // Expanding loads it paused, so the seek bar and times are
                                // right before play is pressed — and stops whatever was playing.
                                expanded = target.name
                                playback.load(RecorderStore.file(context, target), target)
                            }
                        },
                        onPlay = { playback.play(RecorderStore.file(context, it), it) },
                        onRename = { renaming = it },
                        onDelete = { deleting = it },
                    )
                }
            }
        }

        if (!recording && recordings.isNotEmpty()) {
            MaterialText(
                text = pluralStringResource(R.plurals.recorder_count, recordings.size, recordings.size, sizeLabel(RecorderStore.totalBytes(context))),
                style = RecorderType.note,
                color = RecorderColors.TextSecondary,
                modifier = Modifier.padding(horizontal = Gutter, vertical = 4.dp),
            )
        }
    }
    val onRecord = { if (recording) stopRecording() else startRecording() }
    val onPause = {
        if (paused) {
            recorder.resume()
            paused = false
        } else {
            recorder.pause()
            paused = true
        }
    }

    if (LocalConfiguration.current.screenHeightDp < COMPACT_HEIGHT_DP) {
        // Sideways: the list takes the leading side's full height and the transport is a
        // column on the trailing side — `voice-recorder-layout`, the 480dp threshold
        // `landscape-rotation`, `voice-memo-layout` and `music-player-layout` use.
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Bottom),
                    ),
                content = main,
            )
            SideTransport(
                recording = recording,
                paused = paused,
                enabled = hasPermission,
                reduced = reduced,
                onRecord = onRecord,
                onPause = onPause,
            )
        }
    } else {
        // The insets are applied **per section**, not to the whole column: the transport
        // has to paint to the bottom edge with the gesture bar over it, which is what
        // every recorder on the device does. A padded column would leave a strip of ground
        // under the one surface a person looks at most.
        Column(Modifier.fillMaxSize()) {
            main()
            Transport(
                recording = recording,
                paused = paused,
                enabled = hasPermission,
                reduced = reduced,
                onRecord = onRecord,
                onPause = onPause,
            )
        }
    }

    // Setup, and a live cover's dots after the hold — the same grid either way.
    CoverPatternGrid(gridShown, pathDrawn, dotsError, showDots, statusTop)
    }

    renaming?.let { target ->
        RenameDialog(
            recording = target,
            onRename = { title ->
                RecorderStore.rename(context, target, title)
                refresh()
                renaming = null
            },
            onCancel = { renaming = null },
        )
    }

    deleting?.let { target ->
        ConfirmDelete(
            recording = target,
            onConfirm = {
                playback.stopIf(target)
                if (expanded == target.name) expanded = null
                RecorderStore.delete(context, target)
                refresh()
                deleting = null
            },
            onCancel = { deleting = null },
        )
    }
}

// --- the search field ----------------------------------------------------------

/**
 * Always drawn, including over an empty list — see the file's KDoc.
 *
 * `BasicTextField` and not a Material one, for the reason every colour in this file is
 * passed explicitly: a Material field would take the host's theme, and the cover would
 * change when the vault's appearance changed.
 *
 * **Nothing typed here is ever persisted.** No `rememberSaveable`, no preference, no
 * draft.
 */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gutter, vertical = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(RecorderColors.Surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = RecorderColors.TextSecondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                MaterialText(
                    text = stringResource(R.string.recorder_search),
                    style = RecorderType.search,
                    color = RecorderColors.TextSecondary,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = RecorderType.search.copy(color = RecorderColors.TextPrimary),
                cursorBrush = SolidColor(RecorderColors.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// --- the list ------------------------------------------------------------------

/**
 * Rows are calm until tapped, as in Apple Voice Memos — `voice-recorder-layout`.
 *
 * A collapsed row is its title and "date · duration · size", and the whole card is the
 * target. Tapped, it expands in place to the seek bar and its times, then back 15 seconds,
 * play/pause, forward 15 seconds and delete. Its title renames it, and its header collapses
 * it. At most one row is expanded.
 */
@Composable
private fun RecordingList(
    recordings: List<RecorderStore.Recording>,
    playback: RecorderPlayback,
    expanded: String?,
    reduced: Boolean,
    onToggle: (RecorderStore.Recording) -> Unit,
    onPlay: (RecorderStore.Recording) -> Unit,
    onRename: (RecorderStore.Recording) -> Unit,
    onDelete: (RecorderStore.Recording) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
        items(recordings, key = { it.name }) { recording ->
            val open = expanded == recording.name
            val loaded = playback.current?.name == recording.name
            val renameLabel = stringResource(R.string.rename)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Gutter, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(RecorderColors.Surface)
                    .then(if (reduced) Modifier else Modifier.animateContentSize(tween(EXPAND_MS))),
            ) {
                // The header: the whole card while collapsed, and what collapses it after.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = RecorderColors.TextPrimary),
                            role = Role.Button,
                            onClickLabel = stringResource(if (open) R.string.recorder_hide_controls else R.string.recorder_show_controls),
                            onClick = { onToggle(recording) },
                        )
                        .padding(horizontal = 12.dp),
                ) {
                    // The title sits at the foot of a 48dp box in both states, so expanding
                    // does not move it and the rename target is full size once it is one.
                    Row {
                        Box(
                            Modifier
                                .weight(1f, fill = false)
                                .widthIn(min = 48.dp)
                                .heightIn(min = 48.dp)
                                .then(
                                    if (open) {
                                        Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = ripple(color = RecorderColors.TextPrimary),
                                                role = Role.Button,
                                                onClickLabel = renameLabel,
                                                onClick = { onRename(recording) },
                                            )
                                    } else {
                                        Modifier
                                    },
                                )
                                .padding(bottom = 6.dp),
                            contentAlignment = Alignment.BottomStart,
                        ) {
                            MaterialText(
                                text = recording.title,
                                style = RecorderType.rowTitle,
                                color = RecorderColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    MaterialText(
                        text = "${dateLabel(recording.createdAtMs)} · " +
                            "${formatTime(recording.durationMs)} · " +
                            sizeLabel(recording.sizeBytes),
                        style = RecorderType.rowMeta,
                        color = RecorderColors.TextSecondary,
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }

                if (open) {
                    Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                        PlaybackSeek(playback)
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                Modifier.weight(1f),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SkipControl(forward = false) {
                                    playback.seekTo(skipTarget(playback.positionMs, -SKIP_STEP_MS, playback.durationMs))
                                }
                                RowControl(
                                    icon = if (loaded && playback.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                                    description = stringResource(if (loaded && playback.playing) R.string.pause else R.string.play),
                                    tint = RecorderColors.Accent,
                                    // The primary control of the row, larger than the skips beside it.
                                    iconSize = 32.dp,
                                ) { if (loaded) playback.toggle() else onPlay(recording) }
                                SkipControl(forward = true) {
                                    playback.seekTo(skipTarget(playback.positionMs, SKIP_STEP_MS, playback.durationMs))
                                }
                            }
                            RowControl(R.drawable.ic_delete, stringResource(R.string.delete), RecorderColors.TextSecondary) {
                                onDelete(recording)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Skip back or forward 15 seconds — `voice-recorder-layout`. `ic_replay` with "15" set
 * inside it; forward mirrors the arrow and leaves the numerals upright, which is how
 * Material draws `replay_10` and `forward_10` and Apple draws `gobackward.15`.
 */
@Composable
private fun SkipControl(forward: Boolean, onClick: () -> Unit) {
    val label = pluralStringResource(if (forward) R.plurals.viewer_skip_forward else R.plurals.viewer_skip_back, 15, 15)
    // The numerals are part of the glyph, so they scale with it and not with the font
    // scale: at 200% the "15" would be wider than the ring it sits in.
    val numerals = with(LocalDensity.current) {
        val size = RecorderType.skip.fontSize.value.dp.toSp()
        RecorderType.skip.copy(fontSize = size, lineHeight = size)
    }
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = RecorderColors.TextPrimary),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_replay),
            contentDescription = null,
            tint = RecorderColors.TextPrimary,
            modifier = Modifier
                .size(28.dp)
                .graphicsLayer { if (forward) scaleX = -1f },
        )
        MaterialText(
            text = "15",
            style = numerals,
            color = RecorderColors.TextPrimary,
            // The ring's centre sits 1/24 of the glyph below the glyph's centre.
            modifier = Modifier
                .offset(y = 1.dp)
                .clearAndSetSemantics {},
        )
    }
}

@Composable
private fun PlaybackSeek(playback: RecorderPlayback) {
    val duration = playback.durationMs.coerceAtLeast(1L)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f)

    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                // 48dp: the Android minimum for a control, which the seek bar is.
                .height(48.dp)
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            dragging?.let { playback.seekTo((it * duration).toLong()) }
                            dragging = null
                        },
                        onDragCancel = { dragging = null },
                    ) { change, _ ->
                        dragging = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val width = maxWidth
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(RecorderColors.Outline),
            )
            Box(
                Modifier
                    .width(width * fraction)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(RecorderColors.Accent),
            )
            Box(
                Modifier
                    .offset(x = (width * fraction) - 5.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(RecorderColors.Accent),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MaterialText(
                text = formatTime((fraction * duration).toLong()),
                style = RecorderType.rowMeta,
                color = RecorderColors.TextSecondary,
            )
            MaterialText(
                text = formatTime(playback.durationMs),
                style = RecorderType.rowMeta,
                color = RecorderColors.TextSecondary,
            )
        }
    }
}

// --- the recording view and the transport ---------------------------------------

/**
 * What the list's area shows while a recording runs — `voice-recorder-layout`: the status,
 * a live waveform scrolling in from the trailing edge, and the timer, centred.
 *
 * **Red only while live.** Paused, the waveform freezes in `TextSecondary`: a paused
 * waveform is not a live meter, and red means recording right now and nothing else.
 */
@Composable
private fun RecordingView(paused: Boolean, elapsedMs: Long, levels: FloatArray, readings: Int) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Gutter),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!paused) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(RecorderColors.Record),
                )
                Spacer(Modifier.width(8.dp))
            }
            MaterialText(
                text = stringResource(if (paused) R.string.recorder_paused else R.string.notification_recording),
                style = RecorderType.rowTitle,
                color = RecorderColors.TextPrimary,
            )
        }
        Spacer(Modifier.height(24.dp))
        Waveform(
            levels = levels,
            readings = readings,
            live = !paused,
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
        )
        Spacer(Modifier.height(24.dp))
        val seconds = (elapsedMs / 1000).toInt()
        val spoken = stringResource(
            if (paused) R.string.recorder_paused_length else R.string.memo_recording_length,
            pluralStringResource(R.plurals.seconds, seconds, seconds),
        )
        MaterialText(
            text = formatTimer(elapsedMs),
            style = RecorderType.timer,
            color = RecorderColors.TextPrimary,
            // The state in words, once a second — the waveform says nothing to TalkBack.
            modifier = Modifier.clearAndSetSemantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = spoken
            },
        )
    }
}

/**
 * 3dp bars at a 5dp pitch, newest at the trailing edge. A slot with no reading yet is the
 * 3dp `Outline` baseline.
 *
 * Heights are the reading as it comes: [Recorder.level] already returns the square root
 * of the amplitude, which is what lifts speech off the floor of a linear scale.
 */
@Composable
private fun Waveform(levels: FloatArray, readings: Int, live: Boolean, modifier: Modifier) {
    val bar = if (live) RecorderColors.Record else RecorderColors.TextSecondary
    val silent = RecorderColors.Outline
    Canvas(modifier.clearAndSetSemantics {}) {
        val width = 3.dp.toPx()
        val pitch = 5.dp.toPx()
        val slots = (size.width / pitch).toInt().coerceAtMost(RING)
        val rtl = layoutDirection == LayoutDirection.Rtl
        for (slot in 0 until slots) {
            // Slot 0 is the newest reading, at the trailing edge.
            val reading = if (slot < readings) levels[(readings - 1 - slot) % RING] else null
            val height = ((reading ?: 0f).coerceIn(0f, 1f) * size.height).coerceAtLeast(width)
            val fromEnd = size.width - slot * pitch - (pitch + width) / 2
            drawRoundRect(
                color = if (reading == null) silent else bar,
                topLeft = Offset(if (rtl) size.width - fromEnd - width else fromEnd, (size.height - height) / 2),
                size = Size(width, height),
                cornerRadius = CornerRadius(width / 2),
            )
        }
    }
}

/**
 * The record control, centred, and pause/resume beside it while recording — controls only.
 *
 * One large circle at the bottom centre, in the one colour this cover uses for
 * recording and nothing else, morphing between a filled circle at rest and a rounded
 * square while capture runs — the shape change every recorder on either store uses,
 * and one a person reads without a label.
 */
@Composable
private fun Transport(
    recording: Boolean,
    paused: Boolean,
    enabled: Boolean,
    reduced: Boolean,
    onRecord: () -> Unit,
    onPause: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // Background first, insets after: the surface runs to the edge and the
            // controls sit clear of the gesture bar.
            .background(RecorderColors.Surface)
            .navigationBarsPadding()
            .padding(start = Gutter, end = Gutter, top = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(56.dp))
        Spacer(Modifier.weight(1f))
        RecordControl(recording = recording, enabled = enabled, reduced = reduced, onClick = onRecord)
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
            if (recording) PauseControl(paused, onPause)
        }
    }
}

/**
 * The transport sideways — `voice-recorder-layout`: a 160dp column on the trailing side,
 * on its own surface to the window's edges, the record control centred and pause under it.
 * The space pause takes is held at rest too, so the record control never moves.
 */
@Composable
private fun SideTransport(
    recording: Boolean,
    paused: Boolean,
    enabled: Boolean,
    reduced: Boolean,
    onRecord: () -> Unit,
    onPause: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .background(RecorderColors.Surface)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical))
            .width(160.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp + 16.dp))
        RecordControl(recording = recording, enabled = enabled, reduced = reduced, onClick = onRecord)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            if (recording) PauseControl(paused, onPause)
        }
    }
}

@Composable
private fun PauseControl(paused: Boolean, onPause: () -> Unit) {
    RowControl(
        icon = if (paused) R.drawable.ic_play_arrow else R.drawable.ic_pause,
        description = stringResource(if (paused) R.string.recorder_resume else R.string.pause),
        tint = RecorderColors.TextPrimary,
        onClick = onPause,
    )
}

@Composable
private fun RecordControl(recording: Boolean, enabled: Boolean, reduced: Boolean, onClick: () -> Unit) {
    val corner by animateDpAsState(
        if (recording) 10.dp else 34.dp,
        if (reduced) snap() else tween(MORPH_MS),
        label = "record-corner",
    )
    val inner by animateDpAsState(
        if (recording) 30.dp else 56.dp,
        if (reduced) snap() else tween(MORPH_MS),
        label = "record-size",
    )
    val fill by animateColorAsState(
        if (enabled) RecorderColors.Record else RecorderColors.Outline,
        if (reduced) snap() else spring(),
        label = "record-fill",
    )
    Box(
        Modifier
            .size(72.dp)
            .clip(CircleShape)
            .border(2.dp, RecorderColors.Outline, CircleShape)
            // `enabled` used to tint the fill and nothing else, so a control that read
            // as unavailable still took the tap — and the tap raised the system
            // permission dialog. Disabled here means disabled: no click, no ripple.
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = fill),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(inner)
                .clip(RoundedCornerShape(corner))
                .background(fill),
        )
    }
}

// --- rename, delete, and the empty states ---------------------------------------

@Composable
private fun RenameDialog(
    recording: RecorderStore.Recording,
    onRename: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember(recording.name) { mutableStateOf(recording.title) }
    CoverDialog(onCancel = onCancel) {
        MaterialText(text = stringResource(R.string.rename), style = RecorderType.rowTitle, color = RecorderColors.TextPrimary)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(RecorderColors.Ground)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = RecorderType.search.copy(color = RecorderColors.TextPrimary),
                cursorBrush = SolidColor(RecorderColors.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(16.dp))
        DialogActions(
            confirm = stringResource(R.string.action_save),
            confirmColour = RecorderColors.Accent,
            onConfirm = { onRename(text) },
            onCancel = onCancel,
        )
    }
}

@Composable
private fun ConfirmDelete(
    recording: RecorderStore.Recording,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    CoverDialog(onCancel = onCancel) {
        MaterialText(
            text = stringResource(R.string.recorder_delete_title, recording.title),
            style = RecorderType.rowTitle,
            color = RecorderColors.TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        MaterialText(
            text = stringResource(R.string.recorder_delete_body),
            style = RecorderType.note,
            color = RecorderColors.TextSecondary,
        )
        Spacer(Modifier.height(16.dp))
        DialogActions(
            confirm = stringResource(R.string.delete),
            confirmColour = RecorderColors.Record,
            onConfirm = onConfirm,
            onCancel = onCancel,
        )
    }
}

@Composable
private fun CoverDialog(onCancel: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            // A scrim strong enough to isolate the dialog, and a tap outside cancels —
            // what a dialog does everywhere else on the device.
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCancel,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 32.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(RecorderColors.Surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(20.dp),
        ) { content() }
    }
}

@Composable
private fun DialogActions(
    confirm: String,
    confirmColour: Color,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextControl(stringResource(R.string.action_cancel), RecorderColors.TextSecondary, onCancel)
        Spacer(Modifier.width(8.dp))
        TextControl(confirm, confirmColour, onConfirm)
    }
}

@Composable
private fun TextControl(label: String, colour: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = colour),
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        MaterialText(text = label, style = RecorderType.rowTitle, color = colour)
    }
}

/**
 * The empty state, and — when the microphone is gone — the recovery path.
 *
 * **The path is the words, not a button.** A control here would have to raise the
 * system permission dialog, which reads the application label. So the note names the
 * Settings toggle exactly as Android's own permissions screen labels it, which is
 * both the honest instruction and what a real recorder shows once the platform has
 * stopped presenting the dialog. It states a cause and a fix, which is what an empty
 * state owes the person reading it; what it must not do is name the app.
 */
@Composable
private fun EmptyRecorder(hasPermission: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Gutter),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_mic),
            contentDescription = null,
            tint = RecorderColors.TextSecondary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        MaterialText(
            text = stringResource(if (hasPermission) R.string.recorder_none else R.string.recorder_mic_off),
            style = RecorderType.rowTitle,
            color = RecorderColors.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        MaterialText(
            text = if (hasPermission) {
                stringResource(R.string.recorder_none_line)
            } else {
                stringResource(R.string.recorder_mic_off_line)
            },
            style = RecorderType.note,
            color = RecorderColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NoMatches(query: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MaterialText(
            text = stringResource(R.string.recorder_no_match, query.trim()),
            style = RecorderType.note,
            color = RecorderColors.TextSecondary,
        )
    }
}

@Composable
private fun RowControl(
    icon: Int,
    description: String,
    tint: Color,
    iconSize: Dp = 22.dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = tint),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

// --- playback ------------------------------------------------------------------

/**
 * Playing a recording back, on the ExoPlayer this app already ships.
 *
 * No media session and no notification, for [MusicPlayerState]'s reason: a media
 * notification carries the application label onto the lock screen.
 */
class RecorderPlayback internal constructor(private val player: ExoPlayer) {

    var current by mutableStateOf<RecorderStore.Recording?>(null)
        private set

    var playing by mutableStateOf(false)
        private set

    var positionMs by mutableLongStateOf(0L)
        private set

    val durationMs: Long get() = current?.durationMs ?: 0L

    fun play(file: java.io.File, recording: RecorderStore.Recording) {
        player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        player.prepare()
        player.play()
        current = recording
        playing = true
        positionMs = 0L
    }

    /**
     * Load without playing — `voice-recorder-layout`. An expanded row loads its recording so
     * the seek bar and times are right before play is pressed.
     */
    fun load(file: java.io.File, recording: RecorderStore.Recording) {
        // Cleared first: a player left wanting to play would start the new item on prepare.
        player.playWhenReady = false
        player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        player.prepare()
        current = recording
        playing = false
        positionMs = 0L
    }

    fun toggle() {
        if (player.isPlaying) player.pause() else player.play()
        playing = player.isPlaying
    }

    fun pause() {
        player.pause()
        playing = false
    }

    fun seekTo(ms: Long) {
        player.seekTo(ms.coerceAtLeast(0L))
        positionMs = player.currentPosition
    }

    /** Stop if this is what is loaded — the delete path, so a file is not played after it is gone. */
    fun stopIf(recording: RecorderStore.Recording) {
        if (current?.name != recording.name) return
        player.stop()
        current = null
        playing = false
    }

    internal fun tick() {
        if (player.playbackState == Player.STATE_ENDED) {
            // The end returns to the start, paused — the next play is the whole recording
            // again, not a tap that does nothing, as the vault's memo screen does.
            player.playWhenReady = false
            player.seekTo(0L)
        }
        playing = player.isPlaying
        positionMs = player.currentPosition
    }
}

@Composable
private fun rememberRecorderPlayback(): RecorderPlayback {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    val playback = remember(player) { RecorderPlayback(player) }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    LaunchedEffect(playback) {
        while (true) {
            playback.tick()
            delay(POLL_MS)
        }
    }
    return playback
}

/** Where a 15-second skip lands: never before the start, never past the end. */
internal fun skipTarget(positionMs: Long, deltaMs: Long, durationMs: Long): Long =
    (positionMs + deltaMs).coerceIn(0L, durationMs.coerceAtLeast(0L))

// --- formatting and metrics ------------------------------------------------------

/** `0:00.0` while recording — the tenth is what makes the timer look alive. */
private fun formatTimer(ms: Long): String {
    val tenths = (ms / 100) % 10
    return "${formatTime(ms)}.$tenths"
}

// `app-languages` D8. Built per use and in the language's own order, so a change of language
// takes the dates with it.
private fun dateLabel(ms: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private val Gutter = 16.dp
private const val METER_MS = 60L
private const val POLL_MS = 250L
private const val MORPH_MS = 200
private const val CROSSFADE_MS = 200
private const val EXPAND_MS = 200
private const val SKIP_STEP_MS = 15_000L
private const val RING = 512
private const val COMPACT_HEIGHT_DP = 480
