package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folder tree's shape — `folder-system`.
 *
 * The pure half of `VaultFolders`: what is at a level, what is in a subtree, which
 * item a folder shows, and how the level queries behave when something dangles. The
 * write half needs a `BlobStore` and is covered on device.
 */
class FolderTreeTest {

    private var next = 1

    private fun folder(
        name: String,
        parent: VaultFolder? = null,
        destination: VaultDestination? = null,
    ) = VaultFolder(
        folderId = ByteArray(16).also { it[0] = (next++).toByte() },
        parentId = parent?.folderId,
        name = name,
        destination = destination,
    )

    private fun item(
        name: String,
        folder: VaultFolder? = null,
        at: Long = 1L,
        trashed: Boolean = false,
    ) = VaultItem(
        blobIds = listOf(ByteArray(16).also { it[0] = (next++).toByte() }),
        plaintextLength = 1L,
        thumb = null,
        filename = name,
        album = null,
        createdAt = at,
        importedAt = at,
        tags = emptyList(),
        deletedAt = if (trashed) 9L else null,
        folderId = folder?.folderId,
    )

    /** A level is its own folders, ordered by name, with their own live counts. */
    @Test
    fun childrenAreTheLevelsOwnFoldersOrderedByName() {
        val trips = folder("Trips")
        val admin = folder("Admin")
        val inner = folder("2024", trips)
        val items = listOf(item("a.jpg", trips), item("b.jpg", inner))

        val top = VaultFolders.children(listOf(trips, admin, inner), items, null)
        assertEquals(listOf("Admin", "Trips"), top.map { it.name })
        // Its own item only: the sub-folder's item is in the sub-folder.
        assertEquals(listOf(0, 1), top.map { it.count })
    }

    /** A folder with nothing in it is still listed. That is the whole change. */
    @Test
    fun anEmptyFolderIsListedWithZero() {
        val empty = folder("Empty")
        val listed = VaultFolders.children(listOf(empty), emptyList(), null)
        assertEquals(1, listed.size)
        assertEquals(0, listed.single().count)
    }

    /** A trashed item is not counted, and does not un-list the folder. */
    @Test
    fun trashedItemsAreNotCounted() {
        val f = folder("Trip")
        val listed = VaultFolders.children(
            listOf(f),
            listOf(item("live.jpg", f), item("gone.jpg", f, trashed = true)),
            null,
        )
        assertEquals(1, listed.single().count)
    }

    /** An item pointing at a folder that is gone is at the top level, never hidden. */
    @Test
    fun aDanglingReferenceReadsAsTheTopLevel() {
        val gone = folder("Gone")
        val orphan = item("orphan.jpg", gone)
        val here = VaultFolders.items(emptyList(), listOf(orphan), null)
        assertEquals(listOf("orphan.jpg"), here.map { it.filename })
    }

    @Test
    fun aSubtreeIsTheFolderAndEverythingUnderIt() {
        val trips = folder("Trips")
        val y2024 = folder("2024", trips)
        val june = folder("June", y2024)
        val other = folder("Admin")

        val within = VaultFolders.subtreeIds(listOf(trips, y2024, june, other), trips.folderId)
        assertEquals(3, within.size)
        assertTrue(within.contains(june.id))
        assertFalse(within.contains(other.id))
    }

    /** No cover chosen: the newest live item anywhere within, sub-folders included. */
    @Test
    fun theCoverFallsBackToTheNewestItemInTheSubtree() {
        val trips = folder("Trips")
        val inner = folder("2024", trips)
        val old = item("old.jpg", trips, at = 1L)
        val newest = item("new.jpg", inner, at = 9L)

        val cover = VaultFolders.coverFor(listOf(trips, inner), listOf(old, newest), trips)
        assertEquals("new.jpg", cover?.filename)
    }

    /** A chosen cover wins while it is live, and the fallback takes over when it is not. */
    @Test
    fun aTrashedCoverFallsBackAndComesBack() {
        val f = folder("Trip")
        val chosen = item("chosen.jpg", f, at = 1L)
        val other = item("other.jpg", f, at = 5L)
        val withCover = f.withCover(chosen.blobIds.first())

        assertEquals(
            "chosen.jpg",
            VaultFolders.coverFor(listOf(withCover), listOf(chosen, other), withCover)?.filename,
        )

        val trashed = chosen.withDeletedAt(9L)
        assertEquals(
            "other.jpg",
            VaultFolders.coverFor(listOf(withCover), listOf(trashed, other), withCover)?.filename,
        )

        // Restored: the stored choice was never cleared, so it is the cover again.
        assertEquals(
            "chosen.jpg",
            VaultFolders.coverFor(listOf(withCover), listOf(chosen, other), withCover)?.filename,
        )
    }

    /** A folder holding nothing at any depth shows no image at all. */
    @Test
    fun anEmptyFolderHasNoCover() {
        val f = folder("Empty")
        assertNull(VaultFolders.coverFor(listOf(f), emptyList(), f))
    }

    /**
     * A count is what the destination the user is standing in would show —
     * `vault-destinations`.
     *
     * `MainActivity` narrows the item list by the destination's kinds *before* handing it
     * to [VaultFolders.children], so the counting code is unchanged and this asserts the
     * property that change buys.
     */
    @Test
    fun aCountIsPerDestination() {
        val trip = folder("Trip")
        val items = List(40) { item("photo$it.jpg", trip) } +
            List(3) { item("doc$it.pdf", trip) }

        fun countOn(destination: VaultDestination) = VaultFolders
            .children(
                listOf(trip),
                items.filter { it.kind in destination.kinds },
                null,
            )
            .single()
            .count

        assertEquals(40, countOn(VaultDestination.Media))
        assertEquals(3, countOn(VaultDestination.Files))
        assertEquals(0, countOn(VaultDestination.Memos))
        assertEquals(0, countOn(VaultDestination.Notes))
    }

    /**
     * ***The folder is listed in every destination, including where its count is none.***
     *
     * `album-organisation` requires a recorded folder stay listed, and a folder hidden
     * wherever its count is zero would be invisible in three destinations out of four —
     * a folder the user made and cannot find.
     */
    @Test
    fun aFolderIsListedInEveryDestinationEvenAtZero() {
        val trip = folder("Trip")
        val items = List(5) { item("photo$it.jpg", trip) }

        VaultDestination.entries.forEach { destination ->
            val listed = VaultFolders.children(
                listOf(trip),
                items.filter { it.kind in destination.kinds },
                null,
            )
            assertEquals(
                "${destination.label} does not list the folder",
                listOf("Trip"),
                listed.map { it.name },
            )
        }
    }

    /** Media's chips narrow the same way the destination does. */
    @Test
    fun aCountFollowsMediasChip() {
        val trip = folder("Trip")
        val items = List(4) { item("photo$it.jpg", trip) } +
            List(2) { item("clip$it.mp4", trip) }

        fun countUnder(filter: MediaFilter) = VaultFolders
            .children(listOf(trip), items.filter { it.kind in filter.kinds }, null)
            .single()
            .count

        assertEquals(6, countUnder(MediaFilter.All))
        assertEquals(4, countUnder(MediaFilter.Photos))
        assertEquals(2, countUnder(MediaFilter.Videos))
    }

    /**
     * A move offers only folders made where the items live — decided by their kinds, and
     * nothing at all to a set spanning two destinations. A folder named "Intruder captures"
     * is an ordinary folder — `intruder-captures-at-home` D11 — and is offered like any other.
     */
    @Test
    fun aMoveIsOfferedOnlyTheItemsOwnFolders() {
        val trips = folder("Trips", destination = VaultDestination.Media)
        val calls = folder("Calls", destination = VaultDestination.Memos)
        val captures = folder("Intruder captures", destination = VaultDestination.Media)
        val folders = listOf(trips, calls, captures)
        val photos = listOf(item("a.jpg"), item("b.mp4"))
        val clip = item("c.m4a")

        assertEquals(listOf(trips, captures), VaultFolders.accepting(folders, photos))
        assertEquals(listOf(calls), VaultFolders.accepting(folders, listOf(clip)))
        assertTrue(VaultFolders.accepting(folders, photos + clip).isEmpty())
    }
}
