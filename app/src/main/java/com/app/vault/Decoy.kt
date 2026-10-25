package com.app.vault

import androidx.annotation.StringRes
import java.io.File
import kotlin.random.Random

/**
 * The arithmetic behind a plausible decoy — P2-2.
 *
 * A decoy is an ordinary vault (I8). What makes one *believable* is not
 * cryptography, which is already done, but the dates: a vault whose fifty items
 * all arrived in one ten-minute burst and whose log records a single unlock reads
 * as what it is. This file produces the timestamps that make it read otherwise,
 * and nothing else — no content, no screens, no storage.
 *
 * **Deliberately pure.** Every function takes its clock and its randomness as
 * arguments and returns numbers, so the two properties P2-2's Verify names —
 * nothing inside the setup window, a spread of at least 8 weeks — are asserted on
 * the JVM, over thousands of generated sets, without an emulator.
 *
 * **Not key material.** [Random] here rather than `SecureRandom`: these values are
 * a story told to someone who has already compelled the passphrase, not a secret.
 * A seedable generator is what lets the tests be deterministic, which matters more.
 */
object Decoy {

    /** The band the task names. Below the floor is not a library; above the ceiling is not a decoy. */
    const val MIN_ITEMS = 30
    const val MAX_ITEMS = 80

    /** The Verify's gate, in the unit it is stated in. */
    const val MIN_SPREAD_WEEKS = 8

    /** How many times a vault this old would plausibly have been opened. A judgement, not a measurement. */
    const val MIN_UNLOCKS = 8
    const val MAX_UNLOCKS = 20

    const val DAY = 86_400_000L
    const val WEEK = 7 * DAY

    /** ~8 months. The far edge of an assigned capture date and of a vault's claimed age. */
    const val OLDEST_DAYS = 240L

    /** Assigned capture dates stop here: a decoy whose newest item is from today is not months old. */
    const val NEWEST_DAYS = 14L

    /**
     * Why an item was refused, in the words the screen shows. Only one reason
     * exists, because only one thing disqualifies an item.
     */
    val CAPTURED_DURING_SETUP = R.string.decoy_captured_during_setup

    /**
     * The interval the flow ran in, widened by a day at each end.
     *
     * A day rather than the calendar-day flooring this could do: the widened form
     * is a superset of it in every timezone, and there is no timezone here to get
     * wrong. A photo taken this morning is inside the window whether the flow
     * started this afternoon or last night.
     */
    class Window(val from: Long, val to: Long) {
        operator fun contains(t: Long) = t in from..to
    }

    fun setupWindow(startedAt: Long, now: Long) = Window(startedAt - DAY, now + DAY)

    /**
     * When this install first wrote anything — the mtime of its oldest blob.
     *
     * §6's install chaff is written at first run and never rewritten, so the oldest
     * file in the store dates the install. It is readable from a directory listing
     * **with no passphrase at all**, which is what makes it a ceiling on any age a
     * vault can claim: a vault older than the app that holds it is disproved by
     * `ls`. Returns [now] for a store with nothing in it.
     */
    fun installedAt(blobsDir: File, now: Long): Long =
        blobsDir.listFiles().orEmpty().filter { it.isFile }.minOfOrNull { it.lastModified() } ?: now

    /**
     * The vault's claimed birthday: uniform in `[max(install, 8 months ago), just before setup]`.
     *
     * Floored at the install for the reason above, and stopping before the setup
     * window so the log's first entry is outside it like everything else the flow
     * writes. On an install too young for any of that the bounds collapse and the
     * answer is [now] — the vault really was made today, and a directory listing
     * would say so.
     */
    fun origin(installedAt: Long, window: Window, now: Long, rng: Random): Long {
        val floor = maxOf(installedAt, now - OLDEST_DAYS * DAY)
        // The window's own edge, not `now - 1 day`: the window is widened by a day
        // at each end, so "a day ago" is inside it. Everything the flow *writes as
        // history* has to land before the window starts.
        val ceiling = window.from - 1
        // No room means no history: an install made this morning genuinely produces
        // a vault made this morning, and the truthful timestamp is now. Claiming
        // otherwise is disproved by the directory listing.
        return if (floor >= ceiling) now else floor + rng.nextLong(ceiling - floor)
    }

    /** A capture date for an item that has none: uniform in `[8 months ago, 2 weeks ago]`. */
    fun assignedCreatedAt(now: Long, rng: Random): Long {
        val from = now - OLDEST_DAYS * DAY
        val to = now - NEWEST_DAYS * DAY
        return from + rng.nextLong(to - from)
    }

    /**
     * When the item joined the vault: uniform in `[max(capture, origin), just before setup]`.
     *
     * Never before the photograph was taken, never before the vault existed, never
     * inside the setup window. `imported_at` is the one date with no counterpart
     * inside the file — nothing but the index records it — so a value here
     * contradicts nothing, which is exactly why fifty identical ones would be the
     * tell instead.
     */
    fun assignedImportedAt(createdAt: Long, origin: Long, window: Window, rng: Random): Long {
        val from = maxOf(createdAt, origin)
        val to = window.from - 1
        return if (from >= to) from else from + rng.nextLong(to - from)
    }

    /** What the flow decided about one source, and why. The screen renders these; it does not make them. */
    sealed class Decision {
        /** The source has a real capture date. It wins — see [plan]. */
        class Keep(val createdAt: Long) : Decision()

        /** The source has none, so one is assigned. */
        class Assign(val createdAt: Long) : Decision()

        class Decline(@StringRes val reason: Int) : Decision()

        /** The date this decision produces, or null when the item is not imported. */
        val date: Long?
            get() = when (this) {
                is Keep -> createdAt
                is Assign -> createdAt
                is Decline -> null
            }
    }

    /**
     * One decision per source, from its capture date alone.
     *
     * **A real capture date is never replaced.** Assigning over it would put June in
     * the index for a file whose own EXIF says 2019, while every ordinary import
     * records `DATE_TAKEN` unchanged and leaves the two agreeing — so the
     * substitution would be the tell, not the cover. Items captured inside the setup
     * window are declined instead of backdated, for the same reason: their own
     * metadata would contradict the index.
     */
    fun plan(sourceDates: List<Long?>, window: Window, now: Long, rng: Random): List<Decision> =
        sourceDates.map { date ->
            when {
                date == null -> Decision.Assign(assignedCreatedAt(now, rng))
                date in window || date > window.to -> Decision.Decline(CAPTURED_DURING_SETUP)
                else -> Decision.Keep(date)
            }
        }

    /** Whole weeks from the earliest date to the latest. Zero for fewer than two dates. */
    fun spreadWeeks(dates: List<Long>): Long =
        if (dates.size < 2) 0 else (dates.max() - dates.min()) / WEEK

    /** The Verify's first property: nothing the flow wrote lands in the window it ran in. */
    fun outsideWindow(dates: List<Long>, window: Window): Boolean = dates.none { it in window }

    /** The Verify's second: the set covers at least [MIN_SPREAD_WEEKS]. */
    fun spreadIsEnough(dates: List<Long>): Boolean = spreadWeeks(dates) >= MIN_SPREAD_WEEKS

    /**
     * The unlocks a vault of this age would have accumulated.
     *
     * The first sits just after [origin] and the last just before the setup window,
     * so the history spans the whole period rather than a random subset of it —
     * eight uniform samples usually cover most of a range, and "usually" is not a
     * property a test can hold. Strictly ascending, because a log that goes
     * backwards is a log no honest vault could have written.
     *
     * Empty when the vault is younger than the gap between its origin and the setup
     * window. That is the truthful answer for an install made yesterday, and the
     * screen states it rather than inventing months the file list would disprove.
     */
    fun unlockHistory(origin: Long, window: Window, rng: Random): List<Long> {
        val from = origin + 1
        val to = window.from - 1
        if (to - from < MIN_UNLOCKS) return emptyList()

        val count = MIN_UNLOCKS + rng.nextInt(MAX_UNLOCKS - MIN_UNLOCKS + 1)
        val middle = List(count - 2) { from + rng.nextLong(to - from) }
        val all = (listOf(from, to) + middle).sorted()

        // Collisions are possible and a repeated millisecond is not fatal, but
        // "ascends" is asserted as strict, so make it strict rather than argue.
        val strict = ArrayList<Long>(all.size)
        all.forEach { t -> strict += if (strict.isEmpty()) t else maxOf(t, strict.last() + 1) }
        return strict.filter { it <= to }
    }
}
