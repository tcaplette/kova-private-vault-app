package com.app.vault.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.app.vault.CoverStore
import com.app.vault.MusicLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The music player cover's playback — `music-recorder-covers`.
 *
 * **media3's `ExoPlayer`, which this app already ships** for playing encrypted media
 * out of the blob store. A second audio stack for the cover would be a dependency in
 * the listing that needed explaining, and there is nothing the platform's
 * `MediaPlayer` would do better here.
 *
 * ***No `MediaSession`, no notification, and no foreground service.*** A media
 * notification carries the **application** label — the vault's real name — onto the
 * lock screen and into the shade the moment anything plays. That is `FORMAT.md` §10's
 * application-label boundary arriving somewhere far more visible than Settings, and
 * triggered by ordinary use rather than by somebody going looking.
 *
 * The cost is real and is taken knowingly: **playback pauses when the cover leaves the
 * foreground**, and headset buttons do nothing. A real player keeps going, so this is
 * a tell — available to somebody who backgrounds the app and notices the music stop.
 * It is the smaller of the two: one is noticed by a person already testing the app,
 * the other is legible to anybody who glances at the lock screen. The player is
 * *paused* rather than released, so returning resumes in place.
 */
@Stable
class MusicPlayerState internal constructor(
    private val context: Context,
    private val player: ExoPlayer,
    /**
     * Whether the player may remember what it played.
     *
     * False while trigger setup is hosting the cover: `cover-trigger` requires that
     * no cover control writes anything during setup, and a rehearsal that left the
     * user's player pointing at a track they never chose is the visible half of that.
     */
    private val persist: Boolean,
) {

    /** The whole library, once read. Empty until [load] finishes, and on no access. */
    var tracks by mutableStateOf<List<MusicLibrary.Track>>(emptyList())
        private set

    /** True until the first query has come back — a player's ordinary opening state. */
    var loading by mutableStateOf(true)
        private set

    /** What is loaded in the transport, playing or paused. */
    var current by mutableStateOf<MusicLibrary.Track?>(null)
        private set

    var playing by mutableStateOf(false)
        private set

    /** Where the current track is, in milliseconds. Polled while playing. */
    var positionMs by mutableLongStateOf(0L)
        private set

    var shuffle by mutableStateOf(false)
        private set

    /** `Player.REPEAT_MODE_OFF`, `_ALL` or `_ONE`, cycled in that order. */
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set

    /** The queue the transport is playing through. */
    private var queue: List<MusicLibrary.Track> = emptyList()

    internal suspend fun load() {
        val read = withContext(Dispatchers.IO) { MusicLibrary.tracks(context) }
        tracks = read
        loading = false
        restore(read)
    }

    /**
     * Put the player back where it was, without starting it.
     *
     * A player that opens silent and empty, having forgotten the album you were
     * halfway through, is one nobody opens twice. The position is state about the
     * music and not about the trigger — the presses are never recorded anywhere,
     * which is the line `cover-front-ends` draws.
     */
    private fun restore(library: List<MusicLibrary.Track>) {
        if (!persist) return
        val id = CoverStore.lastTrackId(context) ?: return
        val track = library.firstOrNull { it.id == id } ?: return
        prepare(track, library, playWhenReady = false)
        val at = CoverStore.lastPositionMs(context)
        player.seekTo(at)
        positionMs = at
    }

    /** Play [track], with [inQueue] as everything that follows it. */
    fun play(track: MusicLibrary.Track, inQueue: List<MusicLibrary.Track>) {
        prepare(track, inQueue, playWhenReady = true)
    }

    private fun prepare(
        track: MusicLibrary.Track,
        inQueue: List<MusicLibrary.Track>,
        playWhenReady: Boolean,
    ) {
        queue = inQueue.ifEmpty { listOf(track) }
        val index = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        player.setMediaItems(
            queue.map { MediaItem.Builder().setUri(it.uri).setMediaId(it.id.toString()).build() },
            index,
            0L,
        )
        player.prepare()
        player.playWhenReady = playWhenReady
        current = queue[index]
        positionMs = 0L
    }

    /** The play/pause control. Starts the first track if nothing is loaded yet. */
    fun toggle() {
        if (current == null) {
            val first = tracks.firstOrNull() ?: return
            play(first, tracks)
            return
        }
        if (player.isPlaying) player.pause() else player.play()
    }

    fun next() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.seekTo(0, 0L)
    }

    /**
     * Back, the way every player does it: to the start of this track first, and to
     * the previous one only if you press it again early on.
     */
    fun previous() {
        if (player.currentPosition > RESTART_WINDOW_MS || !player.hasPreviousMediaItem()) {
            player.seekTo(0L)
        } else {
            player.seekToPreviousMediaItem()
        }
        positionMs = player.currentPosition
    }

    fun seekTo(ms: Long) {
        player.seekTo(ms.coerceIn(0L, durationMs))
        positionMs = player.currentPosition
    }

    fun toggleShuffle() {
        shuffle = !shuffle
        player.shuffleModeEnabled = shuffle
    }

    /**
     * Shuffle all: shuffle on, and the whole of [inQueue] from a random track —
     * `music-player-layout`. Nothing is persisted, so it is as safe in trigger setup
     * as every other control.
     */
    fun shuffleAll(inQueue: List<MusicLibrary.Track>) {
        if (inQueue.isEmpty()) return
        shuffle = true
        player.shuffleModeEnabled = true
        play(inQueue.random(), inQueue)
    }

    /** Off → all → one → off, which is the cycle every player on the device uses. */
    fun cycleRepeat() {
        repeat = when (repeat) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        player.repeatMode = repeat
    }

    /** The playing track's length, or 0 before one is prepared. */
    val durationMs: Long
        get() = current?.durationMs ?: 0L

    internal fun onPlayerChanged() {
        playing = player.isPlaying
        val id = player.currentMediaItem?.mediaId?.toLongOrNull()
        current = queue.firstOrNull { it.id == id } ?: current
        positionMs = player.currentPosition
    }

    internal fun tick() {
        positionMs = player.currentPosition
    }

    /** Pause and remember. Called when the cover stops, not on every position change. */
    internal fun pauseAndRemember() {
        player.pause()
        playing = false
        if (!persist) return
        val track = current ?: return
        CoverStore.setLastPlayed(context, track.id, player.currentPosition)
    }

    private companion object {
        /** Past this, "previous" restarts the track rather than leaving it. */
        const val RESTART_WINDOW_MS = 3_000L
    }
}

/**
 * A player bound to the composition that shows it.
 *
 * Built here and released with the screen, so the cover holds no audio resource
 * while it is not on screen — and so there is nothing left running to be noticed
 * once the vault's own unlock screen is up.
 */
@Composable
fun rememberMusicPlayer(persist: Boolean = true): MusicPlayerState {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    val state = remember(player, persist) { MusicPlayerState(context, player, persist) }

    DisposableEffect(player, state) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = state.onPlayerChanged()
        }
        player.addListener(listener)
        onDispose {
            state.pauseAndRemember()
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(state) { state.load() }

    // The seek row has to move with the music, and ExoPlayer reports position by
    // asking rather than by callback. A quarter second is smooth to a reading eye and
    // is four wake-ups a second rather than sixty.
    LaunchedEffect(state.playing) {
        while (state.playing) {
            state.tick()
            delay(POSITION_POLL_MS)
        }
    }

    // Leaving the foreground pauses and commits, which is this player's whole
    // background story — see the class KDoc.
    //
    // Taken from the hosting activity rather than from `LocalLifecycleOwner`, which is
    // deprecated in favour of a composition local in `lifecycle-runtime-compose` — a
    // Gradle dependency this change does not add for one observer.
    val lifecycle = remember(context) { context.hostActivity()?.lifecycle }
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) state.pauseAndRemember()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    return state
}

private const val POSITION_POLL_MS = 250L

/** The activity this composition is running in, through however many wrappers. */
private fun Context.hostActivity(): ComponentActivity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is ComponentActivity) return context
        context = context.baseContext
    }
    return null
}
