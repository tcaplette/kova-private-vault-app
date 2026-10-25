package com.app.vault

import android.content.Context

/**
 * What the covers keep between launches — `music-recorder-covers`.
 *
 * Two of the three keep anything at all: the music player remembers what it was
 * playing and where, and the voice recorder keeps its recordings (in
 * [RecorderStore], which owns files as well as preferences). The calculator keeps
 * **nothing** and opens at zero, which is a cold start the platform's own calculator
 * does too.
 *
 * ***Ordinary plaintext, in `shared_prefs/`, and never encrypted.*** A cover holding
 * an encrypted blob would be the one file on the device that makes no sense for the
 * app it claims to be — I8's tell arriving through a different door. Nothing here
 * touches `FORMAT.md` §2's enumerated directory, reads the vault, or needs it open;
 * a cover that wrote into the vault would be a vault surface, which is the opposite
 * of the point.
 *
 * ***What the player must never keep is the presses.*** Its trigger is a sequence of
 * transport controls, so a record of which controls were pressed is the trigger
 * written down — the same rule that keeps the calculator from holding a tape. The
 * position of a track is not that: it is where the music was, and every player on
 * either store remembers it.
 */
object CoverStore {

    private const val PREFS = "cover_state"
    private const val KEY_TRACK = "music_track"
    private const val KEY_POSITION = "music_position"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- the music player ---------------------------------------------------

    /** The track id the player was last on, or **null** if it has played nothing. */
    fun lastTrackId(context: Context): Long? =
        prefs(context).getLong(KEY_TRACK, -1L).takeIf { it >= 0L }

    /** Where in that track it was, in milliseconds. */
    fun lastPositionMs(context: Context): Long = prefs(context).getLong(KEY_POSITION, 0L)

    /**
     * Remember the track and position.
     *
     * Written when playback pauses and when the cover stops, not on every tick: a
     * position committed sixty times a second would be a write loop for a value only
     * ever read once, on the next open.
     */
    fun setLastPlayed(context: Context, trackId: Long, positionMs: Long) {
        prefs(context).edit()
            .putLong(KEY_TRACK, trackId)
            .putLong(KEY_POSITION, positionMs.coerceAtLeast(0L))
            .apply()
    }

    /** Forget it. Used by trigger setup, which may play but must commit nothing. */
    fun clearLastPlayed(context: Context) {
        prefs(context).edit().remove(KEY_TRACK).remove(KEY_POSITION).apply()
    }
}
