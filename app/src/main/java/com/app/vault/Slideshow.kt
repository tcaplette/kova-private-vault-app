package com.app.vault

import androidx.annotation.StringRes
import kotlin.random.Random

/**
 * How long a photo stays — `slideshow`. Google Photos' fixed 5 seconds, with Apple's and Samsung's
 * quicker and slower either side. Stored by **name**, for [PlaybackSpeed]'s reason.
 */
enum class SlideshowSpeed(val seconds: Int, @StringRes val detail: Int) {
    Three(3, R.string.slideshow_speed_quick),
    Five(5, R.string.slideshow_speed_usual),
    Ten(10, R.string.slideshow_speed_slow);

    val ms: Long get() = seconds * 1_000L

    companion object {
        val Default = Five
        fun of(name: String?): SlideshowSpeed = entries.firstOrNull { it.name == name } ?: Default
    }
}

/** The slideshow's Options — set from inside it, kept for the device. */
data class SlideshowSettings(
    val speed: SlideshowSpeed = SlideshowSpeed.Default,
    val repeat: Boolean = false,
    val shuffle: Boolean = false,
)

/**
 * The order a slideshow plays in and when it moves on — `slideshow`, design D9. Pure, so the
 * order is checked on the JVM.
 *
 * Positions are indices into the list the viewer is paging. A **pass** is one run through it.
 */
object Slideshow {

    /**
     * The first pass, starting at [start]. In order: [start] to the end of the list — Repeat then
     * goes round to the first. Shuffled: [start], then every other item once in a random order.
     */
    fun order(size: Int, start: Int, shuffle: Boolean, rng: Random = Random.Default): List<Int> {
        if (size <= 0) return emptyList()
        val first = start.coerceIn(0, size - 1)
        if (!shuffle) return (first until size).toList()
        return listOf(first) + (0 until size).filter { it != first }.shuffled(rng)
    }

    /**
     * The pass after one that ended on [last], with Repeat on. In order: the whole list from its
     * first item. Shuffled: reshuffled, and never beginning with [last] — the same photo twice in a
     * row reads as the slideshow sticking.
     */
    fun nextPass(size: Int, shuffle: Boolean, last: Int, rng: Random = Random.Default): List<Int> {
        if (size <= 0) return emptyList()
        if (!shuffle) return (0 until size).toList()
        val pass = (0 until size).shuffled(rng).toMutableList()
        if (size > 1 && pass[0] == last) {
            val swap = 1 + rng.nextInt(size - 1)
            pass[0] = pass[swap].also { pass[swap] = pass[0] }
        }
        return pass
    }

    /**
     * How long [kind] stays before the slideshow moves on, or null when it waits for the item's
     * own end — a video plays through.
     */
    fun interval(kind: VaultKind, speed: SlideshowSpeed): Long? =
        if (kind == VaultKind.VIDEO) null else speed.ms
}
