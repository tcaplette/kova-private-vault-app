package com.app.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.StringRes

/**
 * How photos and videos look and play in the viewer — `media-settings`.
 *
 * **They follow the kind, not the screen the viewer was opened from.** Media is the destination
 * that holds exactly photos and videos, so "Media's settings" and "how photos and videos are
 * shown" are the same set. The trash and a received share open photos in the same viewer, and a
 * photo that looked different there would be one photo with two behaviours.
 *
 * **The defaults are what the viewer did before these existed**: full-resolution decodes, small
 * photos enlarged to fit, the screen on its usual timer, the phone's brightness, normal speed. No
 * vault changes appearance because of an update.
 */
data class MediaSettings(
    /** The screen does not time out while a photo or video is settled. The vault stays open with it. */
    val keepScreenOn: Boolean = false,
    /** The viewer's window asks for full brightness while a photo or video is settled. */
    val fullBrightness: Boolean = false,
    /** A photo smaller than the screen is enlarged to fit it; off, it rests at its own size. */
    val stretchSmall: Boolean = true,
    /** Photos decode at full resolution; off, at the screen's. */
    val fullQuality: Boolean = true,
    /** Videos play at this speed. Recordings follow Memos' own — [MemoSettings.speed]. */
    val speed: PlaybackSpeed = PlaybackSpeed.Default,
    /** A video starts where it was last stopped; off, from the start, and no stopping point is written. */
    val resume: Boolean = true,
    /** A video that ends starts again. A slideshow ignores this. */
    val repeat: Boolean = false,
    /** How far a double tap on a playing video skips. */
    val skip: SkipLength = SkipLength.Default,
    /** Follow the phone's rotation, or hold a settled video in landscape. */
    val orientation: VideoOrientation = VideoOrientation.Default,
)

/** Double-tap to skip — YouTube's, VLC's and MX Player's four. Stored by name. */
enum class SkipLength(val seconds: Int, @StringRes val detail: Int) {
    Five(5, R.string.skip_short),
    Ten(10, R.string.skip_usual),
    Fifteen(15, R.string.skip_longer),
    Thirty(30, R.string.skip_scene);

    val ms: Long get() = seconds * 1_000L

    companion object {
        val Default = Ten
        fun of(name: String?): SkipLength = entries.firstOrNull { it.name == name } ?: Default
    }
}

/** Video orientation — VLC's and MX Player's two. Stored by name. */
enum class VideoOrientation(@StringRes val label: Int, @StringRes val detail: Int) {
    Auto(R.string.orientation_auto, R.string.orientation_auto_detail),
    Landscape(R.string.orientation_landscape, R.string.orientation_landscape_detail);

    companion object {
        val Default = Auto
        fun of(name: String?): VideoOrientation = entries.firstOrNull { it.name == name } ?: Default
    }
}

/**
 * The six speeds — YouTube's list without its two extremes. 0.25× is too slow to follow, and
 * 1.75× is too close to 2× to need a row of its own.
 *
 * Stored by **name**, never ordinal, so adding a speed later cannot re-point a saved choice.
 */
enum class PlaybackSpeed(val factor: Float, @StringRes val label: Int, @StringRes val detail: Int) {
    Slow50(0.5f, R.string.speed_0_5, R.string.speed_0_5_detail),
    Slow75(0.75f, R.string.speed_0_75, R.string.speed_0_75_detail),
    Normal(1f, R.string.speed_normal, R.string.speed_normal_detail),
    Fast125(1.25f, R.string.speed_1_25, R.string.speed_1_25_detail),
    Fast150(1.5f, R.string.speed_1_5, R.string.speed_1_5_detail),
    Fast200(2f, R.string.speed_2, R.string.speed_2_detail);

    companion object {
        val Default = Normal

        /** The stored speed, or Normal for a name this build does not know. */
        fun of(name: String?): PlaybackSpeed = entries.firstOrNull { it.name == name } ?: Default
    }
}

/**
 * The largest power-of-two `inSampleSize` that keeps a photo's long edge **at or above** the
 * screen's — what *Highest display quality* off decodes at. Never below screen resolution, so the
 * photo on screen looks the same unmagnified; what goes is detail past the screen, which only a
 * zoom would show.
 */
fun screenSample(width: Int, height: Int, screenLongEdge: Int): Int {
    if (screenLongEdge <= 0) return 1
    val longest = maxOf(width, height)
    var sample = 1
    while (longest / (sample * 2) >= screenLongEdge) sample *= 2
    return sample
}

/**
 * A photo's bytes decoded for the viewer: at full resolution, or at [screenSample]'s reduction.
 * Both in full colour depth — `RGB_565` bands gradients, and no gallery ships it for photos.
 */
fun decodeForViewer(bytes: ByteArray, fullQuality: Boolean, screenLongEdge: Int): Bitmap? {
    val options = BitmapFactory.Options()
    if (!fullQuality) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        options.inSampleSize = screenSample(bounds.outWidth, bounds.outHeight, screenLongEdge)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}
