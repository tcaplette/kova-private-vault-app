package com.app.vault

/**
 * A video's stopping point — `viewer-swipe-navigation`, the `video-resume` spec.
 *
 * Two halves: what is kept ([keep]) and how it reaches the vault ([record]). The viewer
 * decides neither; it reports where the player was and this answers.
 */
object VideoResume {

    /**
     * Five seconds at either end — D6. A stop before it has nothing to resume; a stop within
     * it of the end means the video was finished, so it starts over, as YouTube and Netflix
     * do. A clip shorter than ten seconds therefore never resumes.
     */
    const val EDGE_MS = 5_000L

    /**
     * The stopping point to keep for a player released at [stoppedMs], or null to start over.
     *
     * [durationMs] null or not positive is an unknown length — ExoPlayer reports
     * `C.TIME_UNSET` until it has read the container — and then a stop is kept from five
     * seconds on.
     */
    fun keep(stoppedMs: Long, durationMs: Long?): Long? {
        if (stoppedMs < EDGE_MS) return null
        if (durationMs != null && durationMs > 0 && stoppedMs >= durationMs - EDGE_MS) return null
        return stoppedMs
    }

    /**
     * Writes [positions] — item ID to stopping point, null to clear — in one index commit, and
     * returns the index it committed, or null when nothing needed writing.
     *
     * **Nothing is written when every value already matches its row.** The index is one blob
     * rewritten whole (§7), so a viewing session that changed nothing must not leave an index
     * write behind for an observer with repeated snapshots — `FORMAT.md` §10(47). Deciding
     * that needs the index, so it is read first; the commit reads it again under its own lock.
     *
     * Items gone since the positions were taken — deleted, purged — are skipped: a position is
     * a fact about a row, and a row that is not there has nothing to carry it.
     */
    fun record(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        positions: Map<String, Long?>,
    ): MetadataDb? {
        if (positions.isEmpty()) return null
        if (MetadataDb.load(store, indexId, vmk).items.none { changes(it, positions) }) return null
        return MetadataDb.commit(store, indexId, vmk) { db ->
            for (i in db.items.indices) {
                val item = db.items[i]
                if (changes(item, positions)) db.items[i] = item.withResume(positions[item.itemId])
            }
            db
        }
    }

    private fun changes(item: VaultItem, positions: Map<String, Long?>): Boolean {
        val id = item.itemId
        return positions.containsKey(id) && positions[id] != item.resumeMs
    }
}
