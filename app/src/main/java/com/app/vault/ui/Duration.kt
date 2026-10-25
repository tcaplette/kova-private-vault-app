package com.app.vault.ui

/**
 * How a video's length is written — P2-8.
 *
 * **Null in, null out, and that is the whole point.** `VaultGrid` used to end with
 * `private fun VaultItem.durationLabel(): String = "--:--"`, a hardcoded string
 * standing in for a value the index never carried. Every video tile in the app
 * showed `--:--` forever, against `vault-grid`'s requirement that a duration
 * render in the monospace face.
 *
 * The replacement does not swap one placeholder for a nicer one. Where the length
 * is unknown there is **no label at all**: digits standing in for a value the app
 * does not have read to a user as a broken app, whereas nothing reads as nothing.
 * The play glyph identifies the item as video without help, so the tile loses no
 * meaning.
 *
 * Pure arithmetic over a `Long?`, so it is tested on the JVM at the boundaries
 * where this kind of function is actually wrong: zero, the minute, the hour.
 */
fun durationLabel(durationMs: Long?): String? {
    if (durationMs == null || durationMs <= 0L) return null

    val totalSeconds = durationMs / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
