package com.app.vault.ui

import android.graphics.Bitmap
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.app.vault.MusicLibrary
import com.app.vault.R
import com.app.vault.Trigger
import com.app.vault.ui.theme.MusicColors
import com.app.vault.ui.theme.MusicTheme
import com.app.vault.ui.theme.MusicType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.Text as MaterialText

/**
 * The music player cover — `music-recorder-covers`.
 *
 * **A cover is worth exactly as much as it is convincing**, and the sentence the
 * public advice for spotting a disguised vault is written in — *open it and try to
 * use it as the app it claims to be* — is answered here by a player that plays the
 * music that is actually on the phone. Everything below follows from that:
 *
 * - **The device's own library**, read through `MediaStore` ([MusicLibrary]) and
 *   listed by track, album and artist. A player that opens onto a folder picker, or
 *   onto three bundled sample tracks, fails the sentence in its first five seconds.
 * - **A real transport.** Play, pause, previous, next, seek by dragging, shuffle,
 *   repeat one and repeat all, and a queue that advances by itself when a track ends.
 * - **A mini-player above the bottom edge** whenever something is loaded, which
 *   expands into the now-playing screen. The artwork is one element moved between the
 *   two positions, not two drawings of the same picture — a player where the art jumps
 *   is a player somebody looks at twice.
 * - **Its own palette and type**, never the vault's, following the device's dark-mode
 *   flag. A cover drawn from the host's tokens changes when the host's appearance
 *   changes, which is a tell with a switch attached.
 * - **No app bar, no title, no settings, no about, no branding.** The screen is one
 *   flat ground under transparent system bars.
 *
 * ***The trigger is a pattern drawn on an invisible grid — `pattern-cover-trigger`.***
 * A full-screen, non-consuming handler ([CoverGestures.silentPatternGrid]) observes
 * the whole screen without ever taking a pointer event from whatever is under it, so
 * the library's own scroll and every transport press behave exactly as they would
 * with no trigger configured.
 * The one exception is the dots the owner's hold brings up (`cover-hold-dots`): a 2-second
 * hold in the one area of the screen the owner chose ([CoverGestures.silentHold], `holdArea`)
 * reports `onHold`, and `showDots` then draws the grid and holds the cover still exactly as
 * setup does, until the pattern is judged, Back is pressed or the cover leaves the foreground;
 * a refused pattern on them shows the error state (`dotsError`). A hold anywhere else is not
 * the trigger's, and the control under it does what it always does. A path under its floor produces nothing at all — no
 * haptic, no sound, no visual change — because every one of those confirms that a
 * trigger exists and therefore that a vault does, and the floor is what keeps an
 * ordinary drag from ever reaching it by accident: see `gridNodeAt`'s doc comment for
 * the geometry that guarantees it.
 *
 * ***Nothing here reads the stored trigger or judges an attempt.*** The cover reports
 * what was pressed; `CoverTrigger` decides, in one place, so silence cannot be
 * forgotten at a call site.
 *
 * ***And nothing here requests a permission — there is no callback to do it with.*** A
 * system permission dialog carries the **application** label, so an "Allow access"
 * button in a cover reads "Allow Vault to…" to whoever presses it. The grant is taken
 * in identity setup, inside the vault; if it is later revoked — or auto-revoked, which
 * Android does to an app nobody has opened for months, and a cover is exactly such an
 * app — this draws the no-access state every real player has, and that state names the
 * Settings toggle instead of offering a button.
 */
@Composable
fun MusicCover(
    hasAccess: Boolean,
    onTrigger: (Trigger) -> Unit,
    modifier: Modifier = Modifier,
    /** False while trigger setup hosts the cover: a rehearsal commits nothing. */
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
    /**
     * `recents-shows-identity` D3. Drawn as the recent-apps card's picture: the library and
     * the stored last track, paused, read for display. No player is built, nothing plays,
     * nothing is written, and there is no trigger, no hold and no system-bar change — this
     * copy sits beneath the live screen for the whole session.
     */
    still: Boolean = false,
) {
    MusicTheme {
        // Live only — `persist` is false exactly when setup hosts the cover. The top of the
        // window is then the vault's bar, and the cover's icons over it went dark-on-dark.
        if (persist && !still) CoverSystemBars()
        Box(
            modifier
                .fillMaxSize()
                .background(MusicColors.Ground),
        ) {
            MusicScreen(
                hasAccess = hasAccess,
                onTrigger = if (still) ({}) else onTrigger,
                persist = persist && !still,
                onProgress = onProgress.takeIf { !still },
                showDots = showDots && !still,
                dotsError = dotsError && !still,
                holdArea = holdArea.takeIf { !still },
                onHold = if (still) ({}) else onHold,
                still = still,
            )
        }
    }
}

/** Where the library list is. One level deep, as every player's browse is. */
private sealed interface Browse {
    data class Album(val id: Long) : Browse
    data class Artist(val name: String) : Browse
}

private enum class Tab(@StringRes val label: Int) {
    Tracks(R.string.music_tracks), Albums(R.string.music_albums), Artists(R.string.music_artists),
}

/**
 * ***The transport and the artwork are drawn in every state this screen has***, and
 * that is a requirement rather than a layout choice.
 *
 * They carry the trigger. A player that hid them when the library was empty — or when
 * the audio grant had been revoked, which Android can do on its own to an app nobody
 * has opened for months — would be a cover whose owner could no longer reach their own
 * vault. So the library area changes with the state and the player bar does not.
 */
@Composable
private fun MusicScreen(
    hasAccess: Boolean,
    onTrigger: (Trigger) -> Unit,
    persist: Boolean,
    onProgress: ((Int) -> Unit)?,
    showDots: Boolean,
    dotsError: Boolean,
    holdArea: Int?,
    onHold: () -> Unit,
    still: Boolean,
) {
    val player = rememberMusicPlayer(persist, still)
    var tab by remember { mutableStateOf(Tab.Tracks) }
    var browse by remember { mutableStateOf<Browse?>(null) }
    var expanded by remember { mutableStateOf(false) }

    // Back goes up inside the player, as it does inside a player: the now-playing
    // screen collapses, an album returns to the list. Only at the top does it fall
    // through to `MainActivity`, which leaves the app — `cover-front-ends`.
    BackHandler(expanded) { expanded = false }
    BackHandler(!expanded && browse != null) { browse = null }

    val artwork = rememberArtworkCache()

    // Where the now-playing layout put the artwork, in root coordinates, measured —
    // `music-player-layout`. Null until the pane has been laid out once; kept after,
    // so later expansions have their target from the first frame — until the window
    // changes size, when a slot measured in the other orientation would be wrong.
    val window = LocalConfiguration.current
    var artBounds by remember(window.screenWidthDp, window.screenHeightDp) { mutableStateOf<Rect?>(null) }
    // And the mini player's artwork space, measured the same way, so the collapsed
    // artwork is centred by the bar's own layout — at any font scale — and not by a
    // sum that has to agree with it.
    var miniBounds by remember(window.screenWidthDp, window.screenHeightDp) { mutableStateOf<Rect?>(null) }

    // §8's reduced motion. Compose does not honour the system's animator scale on its
    // own — `voice-memo-layout` measured it — so it is read and the moves snap. Read
    // per composition, which is per expansion.
    val context = LocalContext.current
    val reduced = Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    ) == 0f

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
            // floor, so every control beneath — the library list's scroll, every
            // transport press — behaves exactly as it would with no trigger
            // configured. See `CoverGestures.silentPatternGrid`.
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
        Crossfade(
            expanded,
            animationSpec = if (reduced) snap() else tween(CROSSFADE_MS),
            label = "music-pane",
        ) { showing ->
            if (showing) {
                NowPlayingPane(
                    player = player,
                    onCollapse = { expanded = false },
                    onArtBounds = { artBounds = it },
                )
            } else {
                LibraryPane(
                    player = player,
                    hasAccess = hasAccess,
                    tab = tab,
                    onTab = { tab = it; browse = null },
                    browse = browse,
                    onBrowse = { browse = it },
                    artwork = artwork,
                )
            }
        }

        if (!expanded) {
            MiniPlayer(
                player = player,
                onExpand = { expanded = true },
                onArtBounds = { miniBounds = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // The artwork is drawn **once**, above both panes, and moved between the
        // mini-player's corner and the now-playing screen. Two drawings of the same
        // picture would jump on the swap, which is the kind of small wrongness that
        // makes a person look at an app twice.
        SharedArtwork(
            track = player.current,
            expanded = expanded,
            artBounds = artBounds,
            miniBounds = miniBounds,
            reduced = reduced,
            artwork = artwork,
            onTap = { if (!expanded) expanded = true },
        )

        // Setup, and a live cover's dots after the hold — the same grid either way.
        CoverPatternGrid(gridShown, pathDrawn, dotsError, showDots, statusTop)
    }
}

// --- the library ---------------------------------------------------------------

@Composable
private fun LibraryPane(
    player: MusicPlayerState,
    hasAccess: Boolean,
    tab: Tab,
    onTab: (Tab) -> Unit,
    browse: Browse?,
    onBrowse: (Browse?) -> Unit,
    artwork: ArtworkCache,
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        when {
            browse != null -> BrowseHeader(player, browse, onBack = { onBrowse(null) })
            else -> Tabs(tab, onTab)
        }

        when {
            !hasAccess -> NoAccess()
            player.loading -> Loading()
            player.tracks.isEmpty() -> EmptyLibrary()
            browse is Browse.Album -> TrackList(
                tracks = MusicLibrary.albums(player.tracks).first { it.id == browse.id }.tracks,
                player = player,
                artwork = artwork,
            )
            browse is Browse.Artist -> TrackList(
                tracks = MusicLibrary.artists(player.tracks).first { it.name == browse.name }.tracks,
                player = player,
                artwork = artwork,
            )
            // Shuffle all heads the whole library's list and no other: an opened
            // album or artist is not "all" — `music-player-layout`.
            tab == Tab.Tracks -> TrackList(player.tracks, player, artwork) {
                ShuffleAll { player.shuffleAll(player.tracks) }
            }
            tab == Tab.Albums -> AlbumList(MusicLibrary.albums(player.tracks), artwork) {
                onBrowse(Browse.Album(it.id))
            }
            else -> ArtistList(MusicLibrary.artists(player.tracks), artwork) {
                onBrowse(Browse.Artist(it.name))
            }
        }
    }
}

@Composable
private fun Tabs(tab: Tab, onTab: (Tab) -> Unit) {
    // Scrolls rather than squeezes: a longer translation would otherwise wrap a chip's
    // word one syllable per line.
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Gutter, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Tab.entries.forEach { entry ->
            val selected = entry == tab
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (selected) MusicColors.Accent else MusicColors.Surface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = MusicColors.Accent),
                        onClick = { onTab(entry) },
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                MaterialText(
                    text = stringResource(entry.label),
                    style = MusicType.tab,
                    // Selection is carried by fill *and* by weight of colour, never
                    // by tint alone — DESIGN.md §6.4.
                    color = if (selected) MusicColors.OnAccent else MusicColors.TextSecondary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun BrowseHeader(player: MusicPlayerState, browse: Browse, onBack: () -> Unit) {
    val title = when (browse) {
        is Browse.Album -> MusicLibrary.albums(player.tracks).first { it.id == browse.id }.name
        is Browse.Artist -> browse.name
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconControl(R.drawable.ic_arrow_back, stringResource(R.string.action_back), MusicColors.TextPrimary, onBack)
        Spacer(Modifier.width(4.dp))
        MaterialText(
            text = title,
            style = MusicType.rowTitle,
            color = MusicColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TrackList(
    tracks: List<MusicLibrary.Track>,
    player: MusicPlayerState,
    artwork: ArtworkCache,
    /** Drawn as the list's first item, scrolling with it. */
    header: (@Composable () -> Unit)? = null,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = ListBottom)) {
        if (header != null) item(key = "header") { header() }
        items(tracks, key = { it.id }) { track ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { player.play(track, tracks) }
                    .padding(horizontal = Gutter, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumbnail(track, artwork)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    MaterialText(
                        text = track.title,
                        style = MusicType.rowTitle,
                        color = if (player.current?.id == track.id) {
                            MusicColors.Accent
                        } else {
                            MusicColors.TextPrimary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MaterialText(
                        text = track.artist,
                        style = MusicType.rowSecondary,
                        color = MusicColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                MaterialText(
                    text = formatTime(track.durationMs),
                    style = MusicType.time,
                    color = MusicColors.TextSecondary,
                )
            }
        }
    }
}

/**
 * Shuffle all — `music-player-layout`. A pill in the unselected tabs' `Surface`, not
 * the accent: the accent carries the play state and the seek progress and nothing
 * else, and this reads as part of the library's control row above it.
 *
 * 40dp tall; Compose's hit testing extends a target under 48dp to 48dp, and the 4dp
 * above and below leave it the room to.
 */
@Composable
private fun ShuffleAll(onClick: () -> Unit) {
    Row(
        Modifier
            .padding(start = Gutter, end = Gutter, top = 4.dp, bottom = 8.dp)
            .height(40.dp)
            .clip(CircleShape)
            .background(MusicColors.Surface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = MusicColors.TextPrimary),
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_shuffle),
            // The words beside it say it; describing both reads the pill twice.
            contentDescription = null,
            tint = MusicColors.TextPrimary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        MaterialText(text = stringResource(R.string.music_shuffle_all), style = MusicType.tab, color = MusicColors.TextPrimary)
    }
}

/** Albums as every phone player draws them: two columns of artwork, name under, artist under that. */
@Composable
private fun AlbumList(
    albums: List<MusicLibrary.Album>,
    artwork: ArtworkCache,
    onOpen: (MusicLibrary.Album) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = ListBottom),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(albums, key = { it.id }) { album ->
            Column(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpen(album) },
            ) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    Thumbnail(
                        track = album.tracks.first(),
                        artwork = artwork,
                        size = maxWidth,
                        shape = RoundedCornerShape(8.dp),
                        glyph = maxWidth / 4,
                    )
                }
                Spacer(Modifier.height(8.dp))
                MaterialText(
                    text = album.name,
                    style = MusicType.rowTitle,
                    color = MusicColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MaterialText(
                    text = album.artist,
                    style = MusicType.rowSecondary,
                    color = MusicColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Artists, each led by a circle of their first track's album art — the library holds
 * no artist images, and people are circles where albums are squares.
 */
@Composable
private fun ArtistList(
    artists: List<MusicLibrary.Artist>,
    artwork: ArtworkCache,
    onOpen: (MusicLibrary.Artist) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = ListBottom)) {
        items(artists, key = { it.name }) { artist ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(artist) }
                    .padding(horizontal = Gutter, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumbnail(artist.tracks.first(), artwork, shape = CircleShape)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    MaterialText(
                        text = artist.name,
                        style = MusicType.rowTitle,
                        color = MusicColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MaterialText(
                        text = pluralStringResource(R.plurals.music_track_count, artist.tracks.size, artist.tracks.size),
                        style = MusicType.rowSecondary,
                        color = MusicColors.TextSecondary,
                    )
                }
            }
        }
    }
}

// --- now playing ---------------------------------------------------------------

/**
 * The now-playing screen in the three zones every phone player uses —
 * `music-player-layout`: the collapse chevron at the top, the artwork filling the
 * middle, and the text, seek row and transport at the bottom, where the thumb is.
 *
 * Sideways, under 480dp tall, the same zones sit side by side: the artwork on the
 * leading half, everything else on the trailing half, so the transport is never below
 * the window's edge.
 *
 * The artwork itself is not drawn here. This pane lays out an empty square where it
 * belongs and reports that square through [onArtBounds]; [SharedArtwork] moves the one
 * picture there.
 */
@Composable
private fun NowPlayingPane(
    player: MusicPlayerState,
    onCollapse: () -> Unit,
    onArtBounds: (Rect) -> Unit,
) {
    if (LocalConfiguration.current.screenHeightDp < COMPACT_HEIGHT_DP) {
        Row(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = Gutter),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ArtArea(
                margin = 0.dp,
                onArtBounds = onArtBounds,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 16.dp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                CollapseRow(onCollapse)
                Spacer(Modifier.weight(1f))
                NowPlayingControls(player)
                Spacer(Modifier.height(16.dp))
            }
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = Gutter),
        ) {
            CollapseRow(onCollapse)
            ArtArea(
                margin = 32.dp,
                onArtBounds = onArtBounds,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            NowPlayingControls(player)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The top zone: the chevron that does exactly what back does. */
@Composable
private fun CollapseRow(onCollapse: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pulled out by the target's own inset, so the glyph — not its 48dp touch
        // area — lines up with the gutter the title and seek bar start on.
        Box(Modifier.offset(x = (-12).dp)) {
            IconControl(R.drawable.ic_expand_more, stringResource(R.string.music_close_player), MusicColors.TextPrimary, onCollapse)
        }
    }
}

/**
 * The artwork's place: square, centred, as large as the space allows less [margin],
 * and reported in root coordinates. It draws nothing.
 */
@Composable
private fun ArtArea(margin: Dp, onArtBounds: (Rect) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight - margin).coerceAtLeast(0.dp)
        Box(
            Modifier
                .size(side)
                .onGloballyPositioned { onArtBounds(it.boundsInRoot()) },
        )
    }
}

/** The bottom zone: title and "artist · album" on the leading edge, then seek, then transport. */
@Composable
private fun NowPlayingControls(player: MusicPlayerState) {
    val track = player.current
    MaterialText(
        text = track?.title ?: stringResource(R.string.music_nothing_playing),
        style = MusicType.nowPlayingTitle,
        color = MusicColors.TextPrimary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(4.dp))
    MaterialText(
        text = listOfNotNull(track?.artist, track?.album).joinToString(" · "),
        style = MusicType.nowPlayingArtist,
        color = MusicColors.TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(20.dp))
    SeekRow(player)
    Spacer(Modifier.height(12.dp))
    TransportRow(player)
}

/**
 * The seek control: drag it and the music follows.
 *
 * Hand-drawn rather than a Material `Slider`, for the reason every colour in this file
 * is passed explicitly — a Material control here would take the host's theme, and the
 * cover would change appearance when the vault's appearance changed.
 */
@Composable
private fun SeekRow(player: MusicPlayerState) {
    val duration = player.durationMs.coerceAtLeast(1L)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: (player.positionMs.toFloat() / duration).coerceIn(0f, 1f)

    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            dragging?.let { player.seekTo((it * duration).toLong()) }
                            dragging = null
                        },
                        onDragCancel = { dragging = null },
                    ) { change, _ ->
                        dragging = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        player.seekTo(((offset.x / size.width).coerceIn(0f, 1f) * duration).toLong())
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val width = maxWidth
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MusicColors.Outline),
            )
            Box(
                Modifier
                    .width(width * fraction)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MusicColors.Accent),
            )
            Box(
                Modifier
                    .offset(x = (width * fraction) - 6.dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(MusicColors.Accent),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MaterialText(
                text = formatTime((fraction * duration).toLong()),
                style = MusicType.time,
                color = MusicColors.TextSecondary,
            )
            MaterialText(
                text = formatTime(player.durationMs),
                style = MusicType.time,
                color = MusicColors.TextSecondary,
            )
        }
    }
}

/**
 * The five controls the trigger is drawn from.
 *
 * Every one of them does its own job first and reports its step second, so the
 * sequence is invisible as an act: pressing next plays the next track, whether or not
 * it was the third press of a trigger.
 */
@Composable
private fun TransportRow(
    player: MusicPlayerState,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconControl(
            icon = R.drawable.ic_shuffle,
            description = stringResource(if (player.shuffle) R.string.music_shuffle_on else R.string.music_shuffle_off),
            tint = if (player.shuffle) MusicColors.Accent else MusicColors.TextSecondary,
        ) { player.toggleShuffle() }

        IconControl(R.drawable.ic_skip_previous, stringResource(R.string.music_previous), MusicColors.TextPrimary) {
            player.previous()
        }

        PlayControl(player)

        IconControl(R.drawable.ic_skip_next, stringResource(R.string.music_next), MusicColors.TextPrimary) {
            player.next()
        }

        IconControl(
            icon = if (player.repeat == Player.REPEAT_MODE_ONE) {
                R.drawable.ic_repeat_one
            } else {
                R.drawable.ic_repeat
            },
            description = when (player.repeat) {
                Player.REPEAT_MODE_ONE -> stringResource(R.string.music_repeat_one)
                Player.REPEAT_MODE_ALL -> stringResource(R.string.music_repeat_all)
                else -> stringResource(R.string.music_repeat_off)
            },
            tint = if (player.repeat == Player.REPEAT_MODE_OFF) {
                MusicColors.TextSecondary
            } else {
                MusicColors.Accent
            },
        ) { player.cycleRepeat() }
    }
}

/** The one filled control on the screen, and the only place the accent is a fill. */
@Composable
private fun PlayControl(
    player: MusicPlayerState,
) {
    // The play glyph settles rather than snapping between its two states.
    val scale by animateFloatAsState(if (player.playing) 1f else 0.94f, label = "play-scale")
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MusicColors.Accent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = MusicColors.OnAccent),
                onClick = { player.toggle() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(
                if (player.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow,
            ),
            contentDescription = stringResource(if (player.playing) R.string.pause else R.string.play),
            tint = MusicColors.OnAccent,
            modifier = Modifier
                .size(30.dp)
                .graphicsLayer(scaleX = scale, scaleY = scale),
        )
    }
}

// --- the mini player -----------------------------------------------------------

@Composable
private fun MiniPlayer(
    player: MusicPlayerState,
    onExpand: () -> Unit,
    /** Where the artwork's space is, in root coordinates, for [SharedArtwork]. */
    onArtBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = player.current
    val lineTrack = MusicColors.Outline
    val lineFill = MusicColors.Accent
    Row(
        modifier
            .fillMaxWidth()
            .safeDrawingPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MusicColors.Surface)
            // The progress line — `music-player-layout`: 2dp along the bottom edge,
            // inside the rounded shape, in the accent because it is the seek progress.
            // No semantics: the play/pause description carries the state, and the
            // now-playing seek row carries the position for TalkBack.
            .drawWithContent {
                drawContent()
                if (track != null) {
                    val line = 2.dp.toPx()
                    val done = (player.positionMs.toFloat() / player.durationMs.coerceAtLeast(1L))
                        .coerceIn(0f, 1f)
                    val top = Offset(0f, size.height - line)
                    drawRect(lineTrack, top, Size(size.width, line))
                    drawRect(lineFill, top, Size(size.width * done, line))
                }
            }
            .clickable(onClick = onExpand)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The artwork's own space. It is drawn by SharedArtwork, above this row, which
        // is told where this is.
        Spacer(
            Modifier
                .size(MiniArt)
                .onGloballyPositioned { onArtBounds(it.boundsInRoot()) },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            MaterialText(
                text = track?.title ?: stringResource(R.string.music_nothing_playing),
                style = MusicType.rowTitle,
                color = if (track == null) MusicColors.TextSecondary else MusicColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MaterialText(
                text = track?.artist ?: stringResource(R.string.music_tap_to_open),
                style = MusicType.rowSecondary,
                color = MusicColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconControl(
            icon = if (player.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow,
            description = stringResource(if (player.playing) R.string.pause else R.string.play),
            tint = MusicColors.Accent,
        ) { player.toggle() }
    }
}

// --- artwork -------------------------------------------------------------------

private typealias ArtworkCache = androidx.compose.runtime.snapshots.SnapshotStateMap<Long, Bitmap?>

/**
 * Album art held for the session and no longer.
 *
 * In memory only: a cover that wrote a thumbnail cache would be putting the user's
 * music on disk from inside a vault, which is a file that has to be explained.
 */
@Composable
private fun rememberArtworkCache(): ArtworkCache = remember { mutableStateMapOf() }

@Composable
private fun Thumbnail(
    track: MusicLibrary.Track,
    artwork: ArtworkCache,
    size: Dp = MiniArt,
    shape: Shape = RoundedCornerShape(6.dp),
    /** The no-artwork glyph's size. */
    glyph: Dp = size / 2,
) {
    val bitmap = rememberArtwork(track, artwork)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(MusicColors.Ground)
            // A tile the same colour as the row it sits on is not a tile. The hairline
            // is what makes an empty artwork slot read as artwork rather than as a
            // stray glyph — in light, where surface and ground are both near-white.
            .border(1.dp, MusicColors.Outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_audiotrack),
                contentDescription = null,
                tint = MusicColors.TextSecondary,
                modifier = Modifier.size(glyph),
            )
        }
    }
}

@Composable
private fun rememberArtwork(track: MusicLibrary.Track, cache: ArtworkCache): Bitmap? {
    val context = LocalContext.current
    LaunchedEffect(track.albumId) {
        if (!cache.containsKey(track.albumId)) {
            cache[track.albumId] = withContext(Dispatchers.IO) {
                MusicLibrary.artwork(context, track, ARTWORK_PX)
            }
        }
    }
    return cache[track.albumId]
}

/**
 * The artwork, drawn once and moved.
 *
 * It sits above both panes and animates between the mini-player's corner and the
 * now-playing screen, so the picture is carried across the expansion rather than
 * swapped.
 *
 * **No longer the trigger's terminator** — `pattern-cover-trigger`. The pattern is
 * drawn across the whole screen by a grid in [MusicCover]'s root, not a hold on any
 * one control, so this carries no gesture of its own any more.
 */
@Composable
private fun SharedArtwork(
    track: MusicLibrary.Track?,
    expanded: Boolean,
    /** The now-playing layout's artwork slot in root coordinates; null until first laid out. */
    artBounds: Rect?,
    /** The mini player's artwork space in root coordinates; null until first laid out. */
    miniBounds: Rect?,
    /** The system animation scale is off: every move lands without animating. */
    reduced: Boolean,
    artwork: ArtworkCache,
    onTap: () -> Unit,
) {
    // This box's own place in the root, so the slot — measured in the root — can be
    // turned into an offset inside it. The cover is not always at the window's origin:
    // trigger setup hosts it inside its own screen.
    var origin by remember { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        // The expanded target is wherever the layout put the slot. Until it has been
        // measured — one frame, on the first expansion — the artwork holds the mini
        // player's corner rather than flash at a guessed position.
        val slot = if (expanded) origin?.let { at -> artBounds?.translate(-at.x, -at.y) } else null
        // Collapsed, it sits in the mini player's measured space. The sum is only the
        // first frame's, before the bar has been laid out: the bar's 8dp margin and 8dp
        // padding below a 48dp art space, 16dp in from the start.
        val mini = origin?.let { at -> miniBounds?.translate(-at.x, -at.y) }
        val spec = if (reduced) snap<Dp>() else tween(EXPAND_MS)
        val size by animateDpAsState(
            slot?.let { with(density) { it.width.toDp() } } ?: MiniArt,
            spec,
            label = "art-size",
        )
        val x by animateDpAsState(
            (slot ?: mini)?.let { with(density) { it.left.toDp() } } ?: 16.dp,
            spec,
            label = "art-x",
        )
        val y by animateDpAsState(
            (slot ?: mini)?.let { with(density) { it.top.toDp() } } ?: (maxHeight - MiniArt - 16.dp),
            spec,
            label = "art-y",
        )
        val corner by animateDpAsState(if (slot != null) 16.dp else 6.dp, spec, label = "art-corner")

        val bitmap = track?.let { rememberArtwork(it, artwork) }
        Box(
            Modifier
                .offset(x = x, y = y)
                .size(size)
                .clip(RoundedCornerShape(corner))
                .background(MusicColors.Ground)
                .border(1.dp, MusicColors.Outline, RoundedCornerShape(corner))
                // Tap opens the now-playing screen. No hold gesture of its own any
                // more — `pattern-cover-trigger` moved the trigger to the grid.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTap,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_audiotrack),
                    contentDescription = null,
                    tint = MusicColors.TextSecondary,
                    modifier = Modifier.size(size / 3),
                )
            }
        }
    }
}

// --- states --------------------------------------------------------------------

@Composable
private fun Loading() {
    // A player's opening moment, and deliberately not a spinner: the library is
    // milliseconds on most phones, and a spinner that flashes is more visible than a
    // line of text that does not move.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MaterialText(text = stringResource(R.string.music_loading), style = MusicType.rowSecondary, color = MusicColors.TextSecondary)
    }
}

/** An ordinary player's empty library. Every control stays where it was. */
@Composable
private fun EmptyLibrary() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Gutter),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_audiotrack),
            contentDescription = null,
            tint = MusicColors.TextSecondary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        MaterialText(
            text = stringResource(R.string.music_none),
            style = MusicType.rowTitle,
            color = MusicColors.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        MaterialText(
            text = stringResource(R.string.music_none_line),
            style = MusicType.rowSecondary,
            color = MusicColors.TextSecondary,
        )
    }
}

/**
 * The state a real player shows when it cannot read the library — and the recovery
 * path, which is the words rather than a button.
 *
 * **There used to be an "Allow access" button here.** It went back through
 * `MainActivity` to the system request, and that dialog carries the **application**
 * label: pressing it read "Allow Vault to…" to whoever was holding the phone. No
 * control in a cover may reach that. So the note names the Settings toggle exactly as
 * Android's own permissions screen labels it, which is the honest instruction and is
 * what a real player shows once the platform has stopped offering the dialog. The
 * grant is still taken in identity setup, inside the vault.
 */
@Composable
private fun NoAccess() {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = Gutter),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_audiotrack),
            contentDescription = null,
            tint = MusicColors.TextSecondary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        MaterialText(
            text = stringResource(R.string.music_access_off),
            style = MusicType.rowTitle,
            color = MusicColors.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        MaterialText(
            text = stringResource(R.string.music_access_off_line),
            style = MusicType.rowSecondary,
            color = MusicColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** A 48dp target around a 24dp glyph, which is the floor every control here meets. */
@Composable
private fun IconControl(
    icon: Int,
    description: String,
    tint: Color,
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
            modifier = Modifier.size(24.dp),
        )
    }
}

// --- metrics and motion --------------------------------------------------------

private val Gutter = 16.dp
private val MiniArt = 48.dp

/** Clear of the mini-player, so the last row is not hidden behind it. */
private val ListBottom = 88.dp

private const val ARTWORK_PX = 512

/** Under this, the now-playing screen goes side by side — the threshold `landscape-rotation` uses. */
private const val COMPACT_HEIGHT_DP = 480

private const val EXPAND_MS = 320
private const val CROSSFADE_MS = 220
