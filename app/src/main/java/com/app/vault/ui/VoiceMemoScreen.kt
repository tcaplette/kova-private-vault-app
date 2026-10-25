package com.app.vault.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.AdtsExtractor
import com.app.vault.BlobStore
import com.app.vault.R
import com.app.vault.VaultDataSource
import com.app.vault.LIVE_CAPACITY
import com.app.vault.LevelRing
import com.app.vault.SAMPLE_MS
import com.app.vault.VoiceMemo
import com.app.vault.WaveformOverview
import com.app.vault.Words
import com.app.vault.WrittenItem
import com.app.vault.ui.theme.Radius
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import com.app.vault.ui.theme.VaultMono
import com.app.vault.words
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.sqrt
import com.app.vault.localWords

/**
 * Recording a voice memo — `voice-memo`, laid out as a recorder by `voice-memo-layout`.
 *
 * **Three zones, in every state** — `DESIGN.md` §7.25. Status at the top, the
 * recording itself in the hero filling the middle, and the controls at the bottom
 * under the thumb. It is the layout Apple Voice Memos, Google Recorder and Samsung
 * Voice Recorder share, and it replaces a column that stacked everything from the
 * top and left the lower half of the screen empty.
 *
 * **Three states, one primary action each.** Ready: the record control. Recording:
 * the same control, now Stop. Recorded: "Save to vault", with "Discard" subordinate
 * beside it — and between Stop and either of those, the memo can be heard.
 *
 * **Not a press-and-hold.** That is a messaging idiom — right for a two-second reply
 * and wrong for something you may talk into for ten minutes, because it makes the
 * user hold a finger down for the duration and offers no way to stop, reconsider, or
 * throw away.
 *
 * **Nothing moves when recording starts.** Ready draws the live waveform at silence —
 * a flat baseline — with `0:00` under it. Recording raises those same bars and counts
 * that same clock. The waveform is never the only sign of the state: the status line,
 * the clock and the control's label say it in words (§6.4).
 *
 * **Listen-back** — `voice-memo-layout`, D2. Stop finishes the sealed write; the memo
 * then plays from its own blobs through [VaultDataSource], and nothing is an item until
 * Save. The recorded waveform is the scrubber.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VoiceMemoScreen(
    /** Whether `RECORD_AUDIO` is granted right now. False draws the explanation instead. */
    permitted: Boolean,
    /** Asks for `RECORD_AUDIO`. The one action on the unpermitted screen (§6.5). */
    onRequestPermission: () -> Unit,
    /**
     * The memo in hand, recording or stopped, or null — [VoiceMemo.current]. **The screen draws
     * it and does not own it**: the recording outlives this screen, through a lock and back, so
     * this is a view of it rather than its holder.
     */
    memo: VoiceMemo.Memo?,
    /** [VoiceMemo.changes], read so a Stop from the notification redraws this screen. */
    changes: Int,
    /** Record: the caller starts the memo, or says in [problem] why it could not. */
    onRecord: () -> Unit,
    /** Stop: the caller finishes the sealed write; the memo, stopped, comes back in [memo]. */
    onStop: () -> Unit,
    /** Keep it: the held memo becomes an item. */
    onSave: () -> Unit,
    /** Throw it away: every blob it wrote and its record are deleted. */
    onDiscard: () -> Unit,
    onBack: () -> Unit,
    /**
     * Reports whether there is something here that leaving would destroy — `MainActivity` owns
     * the back gesture, and the question has to be answerable from there.
     */
    onUnsavedChange: (Boolean) -> Unit,
    /**
     * Whether saving a memo will start the 30-day trial — P1-16. True only where no
     * import has ever landed, so the notice is shown once in the life of an install
     * and before the act rather than after it.
     */
    startsTrial: Boolean,
    /** Where the held memo's blobs are — read by listen-back, under the memo's own key. */
    store: BlobStore,
    modifier: Modifier = Modifier,
    /** Set when the hardware or the vault refused; says so rather than failing silently. */
    problem: String? = null,
    /**
     * A sentence the status zone carries while recording — that Stop will not be in the
     * notification shade, when notifications were refused. Said once, in words.
     */
    notice: String? = null,
) {
    changes // read, so a change made elsewhere — a Stop from the shade — redraws this
    val recording = memo?.recording == true
    val recorded = memo != null && !recording
    val held = memo?.written?.takeIf { recorded }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    // The waveforms are plain buffers on the memo, not state; this is what tells the canvases
    // a reading landed.
    var readings by remember { mutableIntStateOf(0) }

    LaunchedEffect(memo, recording) {
        elapsedMs = memo?.elapsedMs() ?: 0L
        readings = memo?.readings ?: 0
        while (recording && memo != null) {
            elapsedMs = memo.elapsedMs()
            readings = memo.readings
            delay(SAMPLE_MS)
        }
    }

    LaunchedEffect(recording, recorded) { onUnsavedChange(recording || recorded) }
    DisposableEffect(Unit) { onDispose { onUnsavedChange(false) } }

    // Ready draws silence: buffers of its own, empty, until a memo brings its levels.
    val silentRing = remember { LevelRing(LIVE_CAPACITY) }
    val silentOverview = remember { WaveformOverview() }
    val live = memo?.live ?: silentRing
    val overview = memo?.overview ?: silentOverview

    val shown = problem
    val compact = LocalConfiguration.current.screenHeightDp < COMPACT_HEIGHT_DP

    val status: @Composable () -> Unit = {
        StatusLine(
            when {
                !permitted -> ""
                recording -> stringResource(R.string.notification_recording)
                recorded -> stringResource(R.string.memo_recorded)
                else -> ""
            },
            dot = recording,
        )
        // `destination-settings-and-slideshow` D6. Notifications refused: the recording still
        // runs with the screen off, but the shade has no Stop — said here, once, while it records.
        if (recording && notice != null) {
            Text(
                text = notice,
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
    val hero: @Composable (Modifier) -> Unit = { m ->
        Column(
            modifier = m,
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                !permitted -> Text(
                    // §6.5 — an empty state is text plus one action. A dead record
                    // control would be the app showing a broken thing instead of
                    // saying what it needs.
                    text = stringResource(R.string.memo_permission_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                recorded -> Review(
                    held = held,
                    recordedMs = elapsedMs,
                    bars = overview,
                    readings = readings,
                    store = store,
                    key = { memo?.key() },
                )
                else -> {
                    LiveWaveform(live, readings, recording)
                    Spacer(Modifier.height(Spacing.xl))
                    Clock(elapsedMs, recording)
                    if (!recording) {
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = stringResource(R.string.memo_speak),
                            style = MaterialTheme.typography.bodyLarge,
                            color = VaultColors.OnSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
    val notices: @Composable ColumnScope.() -> Unit = {
        if (startsTrial && permitted) {
            Text(
                // P1-16, said in the same place the decoy step says it: before the act.
                text = stringResource(R.string.memo_trial),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.lg),
            )
        }
        shown?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.Error,
                modifier = Modifier.padding(bottom = Spacing.lg),
            )
        }
    }
    val controls: @Composable ColumnScope.() -> Unit = {
        when {
            !permitted -> Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth().height(Spacing.touchTarget),
            ) {
                Text(stringResource(R.string.memo_allow_microphone), style = MaterialTheme.typography.labelLarge)
            }

            recorded -> {
                // Both wait for the sealed write: Save before it lands would close the
                // screen on a memo that does not exist yet, and Discard would have
                // nothing to discard.
                Button(
                    onClick = onSave,
                    enabled = held != null,
                    modifier = Modifier.fillMaxWidth().height(Spacing.touchTarget),
                ) {
                    Text(stringResource(R.string.memo_save), style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(Spacing.related))
                OutlinedButton(
                    onClick = onDiscard,
                    enabled = held != null,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = VaultColors.Error,
                    ),
                    modifier = Modifier.fillMaxWidth().height(Spacing.touchTarget),
                ) {
                    Text(stringResource(R.string.editor_discard), style = MaterialTheme.typography.labelLarge)
                }
            }

            else -> RecordControl(
                recording = recording,
                onClick = { if (recording) onStop() else onRecord() },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }

    VaultScaffold(title = stringResource(R.string.fab_voice_memo), modifier = modifier, onBack = onBack, scrollable = false) {
        if (compact) {
            // Sideways, under the 480dp `landscape-rotation` uses: the hero keeps the
            // leading side and the controls stack on the trailing one, so nothing has
            // to be scrolled to.
            Row(
                modifier = Modifier.fillMaxSize().padding(Spacing.screenInset),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
            ) {
                hero(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    status()
                    Spacer(Modifier.weight(1f))
                    notices()
                    controls()
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(Spacing.screenInset)) {
                status()
                hero(Modifier.weight(1f).fillMaxWidth())
                notices()
                controls()
            }
        }
    }
}

/** The top zone. Always drawn, empty or not, so the hero never shifts when it fills. */
@Composable
private fun StatusLine(text: String, dot: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (dot) {
            Box(
                Modifier
                    .size(Spacing.sm)
                    .clip(CircleShape)
                    .background(VaultColors.Record)
                    .clearAndSetSemantics {},
            )
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = VaultColors.OnSurface,
        )
    }
}

/**
 * The running time, announced as it changes. Tabular figures so a clock counting past
 * 9 does not shift the digits beside it.
 */
@Composable
private fun Clock(elapsedMs: Long, recording: Boolean) {
    val words = localWords()
    Text(
        text = formatElapsed(elapsedMs),
        style = MaterialTheme.typography.displayMedium,
        color = if (recording) VaultColors.OnSurface else VaultColors.OnSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = spokenElapsed(words, elapsedMs, recording, recorded = false)
            },
    )
}

/**
 * The record control — §7.25. A circle at rest and a rounded square while recording:
 * the camera shutter's idiom and the recorder cover's, in the vault's tokens.
 *
 * The label under it is the same fact as the control's description, so it is hidden
 * from accessibility rather than read twice. The shape is never the only thing that
 * says which state this is.
 */
@Composable
private fun RecordControl(recording: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // §8's reduced motion. Compose did not honour the system's animator scale here —
    // `VoiceMemoScreenTest` measured the morph still running at 0 — so it is read and
    // the shape snaps. Read per composition, which is per state change: a setting
    // changed while the screen is open applies at the next press.
    val context = LocalContext.current
    val reduced = Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    ) == 0f
    val fill by animateDpAsState(
        if (recording) Spacing.recordFillActive else Spacing.recordFill,
        if (reduced) snap() else tween(RECORD_MORPH_MS),
        label = "recordFill",
    )
    val corner by animateDpAsState(
        if (recording) Radius.recordFillActive else Spacing.recordFill / 2,
        if (reduced) snap() else tween(RECORD_MORPH_MS),
        label = "recordCorner",
    )
    val description = stringResource(if (recording) R.string.stop_recording else R.string.start_recording)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(Spacing.recordControl)
                .clip(CircleShape)
                .border(Spacing.recordRing, VaultColors.Outline, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = VaultColors.Record),
                    onClick = onClick,
                )
                .semantics {
                    contentDescription = description
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    // Measured by `VoiceMemoScreenTest`'s reduced-motion case: the fill has
                    // no semantics of its own, and its size is the claim.
                    .testTag(RECORD_FILL_TAG)
                    .size(fill)
                    .clip(RoundedCornerShape(corner))
                    .background(VaultColors.Record),
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = stringResource(if (recording) R.string.notification_stop else R.string.start_recording),
            style = MaterialTheme.typography.labelLarge,
            color = VaultColors.OnSurface,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * The input, as bars scrolling in from the trailing edge — D3.
 *
 * Every slot the width holds is drawn: a slot with a reading rises with it in
 * `record`, and a slot without one is the silent baseline in `outline`. That is the
 * whole of the ready state, and why nothing moves when recording starts.
 *
 * Invisible to a screen reader: the clock beside it already announces the same fact in
 * words, and bars reading out every 100ms would be noise, not information.
 */
@Composable
private fun LiveWaveform(ring: LevelRing, readings: Int, recording: Boolean) {
    val record = VaultColors.Record
    val baseline = VaultColors.Outline
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(Spacing.waveformHeight)
            .clearAndSetSemantics {},
    ) {
        readings // read, so a new reading redraws
        val bar = Spacing.waveformBar.toPx()
        val step = bar + Spacing.waveformGap.toPx()
        val slots = ((size.width + Spacing.waveformGap.toPx()) / step).toInt().coerceAtMost(ring.capacity)
        val have = if (recording) minOf(ring.size, slots) else 0
        for (slot in 0 until slots) {
            // Slot 0 is the trailing edge, where the newest reading lands.
            val level = if (slot < have) ring.recent(slot) else -1f
            val x = size.width - bar - slot * step
            drawBar(
                x = if (rtl) size.width - bar - x else x,
                width = bar,
                level = level.coerceAtLeast(0f),
                color = if (level >= 0f) record else baseline,
            )
        }
    }
}

/**
 * The recorded state's hero — D2 and D3. The whole memo's waveform, which is also the
 * seek control, the position and length in the player's readout face, and play/pause.
 *
 * The player is built only once the sealed write has landed ([held] non-null), counts
 * toward the one-player rule, and does not start until play is pressed.
 */
@UnstableApi
@Composable
private fun Review(
    held: WrittenItem?,
    recordedMs: Long,
    bars: WaveformOverview,
    readings: Int,
    store: BlobStore,
    /** The memo key, read when the player is built — the memo's, never the vault's. */
    key: () -> ByteArray?,
) {
    val context = LocalContext.current
    var player by remember(held) { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember(held) { mutableStateOf(false) }
    var positionMs by remember(held) { mutableLongStateOf(0L) }
    var durationMs by remember(held) { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(held) {
        val memoKey = key()
        if (held != null && memoKey != null) {
            player = memoPlayer(context, store, held, memoKey)
            PlayerCensus.live.incrementAndGet()
        }
        onDispose {
            player?.let {
                it.release()
                PlayerCensus.live.decrementAndGet()
            }
            player = null
        }
    }

    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            if (p.playbackState == Player.STATE_ENDED) {
                // The end of a memo returns to its start, paused — the next play is
                // the whole memo again, not a tap that does nothing.
                p.playWhenReady = false
                p.seekTo(0L)
            }
            playing = p.isPlaying
            if (dragging == null) positionMs = p.currentPosition.coerceAtLeast(0L)
            if (p.duration != C.TIME_UNSET) durationMs = p.duration
            delay(SAMPLE_MS)
        }
    }

    // The recorded time stands in until the player has read enough to say.
    val lengthMs = if (durationMs > 0L) durationMs else recordedMs
    val fraction = dragging ?: (positionMs.toFloat() / lengthMs.coerceAtLeast(1L)).coerceIn(0f, 1f)

    fun seek(to: Float) {
        val p = player ?: return
        val ms = (to.coerceIn(0f, 1f) * lengthMs).toLong()
        p.seekTo(ms)
        positionMs = ms
    }

    RecordedWaveform(
        bars = bars,
        readings = readings,
        fraction = fraction,
        enabled = player != null,
        onDrag = { dragging = it },
        onSeek = {
            dragging = null
            seek(it)
        },
    )
    Spacer(Modifier.height(Spacing.lg))
    val words = localWords()
    Text(
        text = "${formatElapsed(if (dragging != null) (fraction * lengthMs).toLong() else positionMs)} / " +
            formatElapsed(lengthMs),
        style = VaultMono.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = spokenElapsed(words, lengthMs, recording = false, recorded = true) },
    )
    Spacer(Modifier.height(Spacing.xl))
    OutlinedIconButton(
        onClick = {
            val p = player ?: return@OutlinedIconButton
            if (p.isPlaying) p.pause() else p.play()
            playing = !playing
        },
        enabled = player != null,
        colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = VaultColors.OnSurface),
        modifier = Modifier.size(Spacing.playControl),
    ) {
        Icon(
            painter = painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow),
            contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
        )
    }
}

/**
 * The whole memo as bars, and the seek control — D3.
 *
 * Played bars in `secondary`, unplayed in `outline`, and a playhead between them so the
 * split is never carried by colour alone. Tap or drag seeks; the drag seeks on release,
 * as the recorder cover's scrubber does, so a finger crossing the memo does not send a
 * seek per frame. TalkBack seeks it with its adjust action.
 */
@Composable
private fun RecordedWaveform(
    bars: WaveformOverview,
    readings: Int,
    fraction: Float,
    enabled: Boolean,
    onDrag: (Float) -> Unit,
    onSeek: (Float) -> Unit,
) {
    val played = VaultColors.Secondary
    val unplayed = VaultColors.Outline
    val head = VaultColors.OnSurface
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    fun along(x: Float, width: Int): Float {
        val f = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        return if (rtl) 1f - f else f
    }
    var last by remember { mutableFloatStateOf(0f) }
    val positionLabel = stringResource(R.string.memo_position)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(Spacing.waveformHeight)
            .pointerInput(enabled, rtl) {
                if (!enabled) return@pointerInput
                detectTapGestures { onSeek(along(it.x, size.width)) }
            }
            .pointerInput(enabled, rtl) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { last = along(it.x, size.width); onDrag(last) },
                    onDragEnd = { onSeek(last) },
                    onDragCancel = { onSeek(last) },
                ) { change, _ ->
                    last = along(change.position.x, size.width)
                    onDrag(last)
                }
            }
            .semantics {
                contentDescription = positionLabel
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                if (enabled) {
                    setProgress { target ->
                        onSeek(target)
                        true
                    }
                }
            },
    ) {
        readings // read, so the buffer is redrawn after a reset
        val bar = Spacing.waveformBar.toPx()
        val step = bar + Spacing.waveformGap.toPx()
        val fit = ((size.width + Spacing.waveformGap.toPx()) / step).toInt().coerceAtLeast(1)
        val levels = resample(bars.bars(), fit)
        if (levels.isEmpty()) return@Canvas
        // Always as many bars as the width holds, at the live strip's pitch: the
        // scrubber's length is the memo's length, and a three-second memo drawn as its
        // thirty readings far apart reads as dots, not a waveform.
        val slot = size.width / levels.size
        levels.forEachIndexed { i, level ->
            val centre = (i + 0.5f) * slot
            val x = if (rtl) size.width - centre else centre
            val at = (i + 0.5f) / levels.size
            drawBar(x = x - bar / 2, width = bar, level = level, color = if (at <= fraction) played else unplayed)
        }
        val headX = (if (rtl) 1f - fraction else fraction) * size.width
        val headW = Spacing.playhead.toPx()
        drawRect(head, topLeft = Offset(headX - headW / 2, 0f), size = Size(headW, size.height))
    }
}

/**
 * One bar, centred vertically. A silent bar is [Spacing.waveformBar] tall — the
 * baseline — and a loud one fills the height.
 *
 * `sqrt`, because `getMaxAmplitude` is linear and speech sits low in it: drawn
 * linearly, a normal voice is a line a few pixels tall and the waveform looks dead.
 */
private fun DrawScope.drawBar(x: Float, width: Float, level: Float, color: Color) {
    val min = width
    val h = min + sqrt(level.coerceIn(0f, 1f)) * (size.height - min)
    drawRoundRect(
        color = color,
        topLeft = Offset(x, (size.height - h) / 2),
        size = Size(width, h),
        cornerRadius = CornerRadius(width / 2, width / 2),
    )
}

/**
 * [levels] as exactly [fit] bars. More readings than bars keep each span's loudest, so
 * a shout is never averaged away; fewer are stretched, each reading drawn across the
 * bars its share of the memo covers.
 */
internal fun resample(levels: FloatArray, fit: Int): FloatArray {
    if (levels.isEmpty() || fit <= 0) return FloatArray(0)
    if (levels.size <= fit) return FloatArray(fit) { j -> levels[j * levels.size / fit] }
    return FloatArray(fit) { j ->
        var peak = 0f
        for (i in j * levels.size / fit until (j + 1) * levels.size / fit) peak = max(peak, levels[i])
        peak
    }
}

/**
 * The listen-back player — D2. Built exactly as the viewer's is, over the held memo's
 * sealed blobs, with no cache: a cache of decrypted bytes is a plaintext artifact
 * whichever component writes it.
 *
 * **ADTS constant-bitrate seeking is switched on.** ADTS carries no seek table, and
 * media3's `AdtsExtractor` is unseekable without one of its constant-bitrate flags.
 * [VaultDataSource] reports the length, so the plain flag suffices; the `_ALWAYS` form
 * only adds the unknown-length case. The seek lands approximately — it is estimated
 * from the average bitrate — and `VoiceMemoTest` measures how far off.
 */
@UnstableApi
internal fun memoPlayer(context: Context, store: BlobStore, held: WrittenItem, vmk: ByteArray): ExoPlayer =
    ExoPlayer.Builder(context).build().apply {
        val factory = VaultDataSource.Factory(store, held.blobIds, vmk, held.plaintextLength)
        val extractors = DefaultExtractorsFactory()
            .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
        setMediaSource(
            ProgressiveMediaSource.Factory(factory, extractors).createMediaSource(
                MediaItem.fromUri(VaultDataSource.uriFor(held.blobIds.first()))
            )
        )
        playWhenReady = false
        prepare()
    }

/** `m:ss`, or `h:mm:ss` once there is an hour to show. */
private fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** The same fact, said the way it is read aloud rather than the way it is printed. */
private fun spokenElapsed(words: Words, ms: Long, recording: Boolean, recorded: Boolean): String {
    val total = ms / 1000
    val minutes = (total / 60).toInt()
    val seconds = (total % 60).toInt()
    val secondsText = words.plural(R.plurals.seconds, seconds, seconds)
    val length = if (minutes > 0) {
        words.get(R.string.memo_minutes_seconds, words.plural(R.plurals.minutes, minutes, minutes), secondsText)
    } else {
        secondsText
    }
    return when {
        recording -> words.get(R.string.memo_recording_length, length)
        recorded -> words.get(R.string.memo_recorded_length, length)
        else -> words.get(R.string.memo_nothing_yet)
    }
}

/**
 * Leaving with a recording that has not been saved — `voice-memo`.
 *
 * Its own composable rather than `NoteScreen`'s, which is private to that file. The
 * argument `NoteScreen` records still holds: a shared dialog wrapper gets written
 * when a *third* one needs it, and whoever adds that one makes the case then. Until
 * then the corner comes from the token, and `FLAG_SECURE` is stated here because a
 * dialog is its own window and the per-activity callback never sees it.
 */
@Composable
fun DiscardMemoConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Radius.container),
        containerColor = VaultColors.SurfaceContainerHigh,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.memo_discard_title)) },
        text = {
            Text(
                // Says what is lost and does not overstate: the memo is not in the
                // vault, and nothing already stored is touched by this.
                text = stringResource(R.string.memo_discard_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.editor_discard), color = VaultColors.Error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.memo_keep_recording)) }
        },
    )
}

/** The record control's circle ⇄ square morph — `DESIGN.md` §8. */
private const val RECORD_MORPH_MS = 200

/** The record control's fill, for the test that measures it. */
internal const val RECORD_FILL_TAG = "recordFill"

/** `landscape-rotation`'s height-compact threshold. */
private const val COMPACT_HEIGHT_DP = 480
