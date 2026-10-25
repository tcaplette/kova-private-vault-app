package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The four destinations partition the five kinds — `vault-destinations`.
 *
 * **This is the test that makes a sixth kind safe to add.** `VaultKind` grew from four to
 * five once already (P2-21 added `NOTE`), and a kind belonging to no destination would be
 * an item stored in a vault and reachable from nowhere in it — invisible, un-exportable,
 * and silent about it. The partition is asserted rather than trusted, so the build fails at
 * the moment the sixth kind is added and not at the moment a user loses one.
 */
class VaultDestinationTest {

    /** Every kind belongs to a destination. No item is unreachable. */
    @Test
    fun everyKindHasADestination() {
        VaultKind.entries.forEach { kind ->
            assertNotNull(
                "no destination holds $kind — an item of this kind would be stored and unreachable",
                VaultDestination.of(kind),
            )
        }
    }

    /** No kind belongs to two. An item appears in exactly one place. */
    @Test
    fun noKindHasTwoDestinations() {
        VaultKind.entries.forEach { kind ->
            val holders = VaultDestination.entries.filter { kind in it.kinds }
            assertEquals("$kind is held by $holders", 1, holders.size)
        }
    }

    /** The union is the whole enum — the other half of "partition". */
    @Test
    fun theDestinationsCoverEveryKind() {
        val covered = VaultDestination.entries.flatMap { it.kinds }.toSet()
        assertEquals(VaultKind.entries.toSet(), covered)
    }

    /**
     * Media holds exactly the kinds that can carry a thumbnail.
     *
     * Not a coincidence and not a restatement: it is *why* those two share a destination.
     * A kind that gains a thumbnail without gaining a place in the mosaic, or the reverse,
     * is a decision someone has to make rather than one that drifts.
     */
    @Test
    fun mediaIsExactlyTheThumbnailableKinds() {
        assertEquals(
            VaultKind.entries.filter { it.canHaveThumbnail }.toSet(),
            VaultDestination.Media.kinds,
        )
    }

    /** The chips narrow Media and never reach past it. */
    @Test
    fun everyChipStaysInsideMedia() {
        MediaFilter.entries.forEach { filter ->
            assertTrue(
                "${TestWords.text(filter.label)} admits a kind Media does not hold",
                VaultDestination.Media.kinds.containsAll(filter.kinds),
            )
        }
    }

    /** All is both kinds — there is always a way to see both without leaving Media. */
    @Test
    fun theAllChipShowsEverythingMediaHolds() {
        assertEquals(VaultDestination.Media.kinds, MediaFilter.All.kinds)
        assertEquals(MediaFilter.All, MediaFilter.Default)
    }

    /** One word each: four labels share a bar measured at 320dp. */
    @Test
    fun everyLabelIsOneWord() {
        VaultDestination.entries.forEach { destination ->
            assertTrue(
                "\"${TestWords.text(destination.label)}\" is not one word",
                TestWords.text(destination.label).let { it.isNotBlank() && !it.contains(' ') },
            )
        }
    }

    /**
     * No two destinations share a mark.
     *
     * `grid-fab`'s rule, applied to the bar: one mark, one meaning, on a screen that also
     * carries the floating button.
     */
    @Test
    fun noTwoDestinationsShareAMark() {
        val marks = VaultDestination.entries.map { it.icon }
        assertEquals(marks.size, marks.toSet().size)
    }

    /** A vault opens on Media. */
    @Test
    fun theDefaultIsMedia() {
        assertEquals(VaultDestination.Media, VaultDestination.Default)
    }

    /**
     * Every destination has answered what its `+` does — `file-import`.
     *
     * **The answer may be "none", and null is that answer.** What this refuses is a
     * destination added without the question being put: `addAction` is a constructor
     * parameter, so a fifth entry cannot compile without one, and this asserts the two
     * that carry a picker are the two that can receive what it returns.
     */
    @Test
    fun everyDestinationStatesWhatItsAddActionDoes() {
        assertEquals(AddAction.Media, VaultDestination.Media.addAction)
        assertEquals(AddAction.Files, VaultDestination.Files.addAction)
        assertNull(
            "Notes carries a header + and the floating button already writes a note",
            VaultDestination.Notes.addAction,
        )
        assertNull(
            "Memos carries a header + and the floating button already records a memo",
            VaultDestination.Memos.addAction,
        )
    }

    /**
     * A destination's picker can only be one whose results can land on it.
     *
     * The rule `vault-destinations` states: a header `+` that opened a picker returning
     * nothing this destination holds would send every batch somewhere else, every time.
     * [AddAction.Files] is the one that may also return other kinds — a filename decides,
     * not the picker — and that is D3's deliberate choice, not an exception to this.
     */
    @Test
    fun aDestinationsPickerCanLandOnIt() {
        assertTrue(
            "Media's picker returns images and video and Media holds neither",
            VaultDestination.Media.kinds.containsAll(setOf(VaultKind.IMAGE, VaultKind.VIDEO)),
        )
        assertTrue(
            "Files' picker returns files and Files does not hold them",
            VaultKind.FILE in VaultDestination.Files.kinds,
        )
    }

    /** Two pickers, two names. The header is icon-only, so the name is all there is. */
    @Test
    fun noTwoAddActionsShareADescription() {
        val descriptions = AddAction.entries.map { TestWords.text(it.description) }
        assertEquals(descriptions.size, descriptions.toSet().size)
        AddAction.entries.forEach {
            assertTrue("${it.name} has no description", TestWords.text(it.description).isNotBlank())
        }
    }

    // --- where a batch landed, `file-import` D4 ------------------------------

    private fun item(name: String) = VaultItem(
        blobIds = listOf(ByteArray(16) { name.first().code.toByte() }),
        plaintextLength = 1L,
        thumb = null,
        filename = name,
        album = null,
        createdAt = null,
        importedAt = 1L,
        tags = emptyList(),
    )

    /** A photograph picked on Files is an image, and the line names where it went. */
    @Test
    fun aBatchThatLandedElsewhereNamesWhereItWent() {
        assertEquals(
            "Added to Media.",
            landedElsewhere(TestWords, listOf(item("beach.jpg")), VaultDestination.Files),
        )
    }

    /**
     * Silence when any item landed in front of the user.
     *
     * The grid filling in under the thumb is the feedback. This is the assertion that
     * stops the line becoming a toast on every import.
     */
    @Test
    fun aBatchThatLandedHereSaysNothing() {
        assertNull(landedElsewhere(TestWords, listOf(item("statement.pdf")), VaultDestination.Files))
        assertNull(landedElsewhere(TestWords, listOf(item("beach.jpg")), VaultDestination.Media))
    }

    /** A mixed batch is silent: part of it is on screen, so the screen has said it. */
    @Test
    fun aMixedBatchSaysNothing() {
        assertNull(
            landedElsewhere(TestWords, 
                listOf(item("statement.pdf"), item("beach.jpg")),
                VaultDestination.Files,
            ),
        )
    }

    /** Two destinations, joined, in the bar's order and not the pick's. */
    @Test
    fun aBatchSplitAcrossTwoNamesBoth() {
        assertEquals(
            "Added to Media and Memos.",
            landedElsewhere(TestWords, 
                // Picked in the other order, and named in the bar's.
                listOf(item("song.mp3"), item("beach.jpg")),
                VaultDestination.Files,
            ),
        )
    }

    /**
     * ***Notes cannot be reached by an import at all, and that is not this line's doing.***
     *
     * A note is a note because it carries a note blob — `VaultItem.kind` checks that before
     * asking the filename — so a picked `shopping.md` is an unrecognised extension, which
     * is [VaultKind.FILE]. It lands on Files and the user is told nothing, because it
     * landed in front of them.
     *
     * Asserted because the design for this change said the opposite in its first draft.
     */
    @Test
    fun aMarkdownFileIsAFileAndNotANote() {
        assertEquals(VaultKind.FILE, item("shopping.md").kind)
        assertNull(landedElsewhere(TestWords, listOf(item("shopping.md")), VaultDestination.Files))
        assertEquals(
            "Added to Files.",
            landedElsewhere(TestWords, listOf(item("shopping.md")), VaultDestination.Media),
        )
    }

    /** An empty batch is not an event. */
    @Test
    fun anEmptyBatchSaysNothing() {
        assertNull(landedElsewhere(TestWords, emptyList(), VaultDestination.Files))
    }

    /**
     * ***No destination is reachable from the drawer.***
     *
     * The whole argument for the bar is that these are primary navigation and a drawer is
     * where primary navigation goes to be forgotten. `VaultDrawerSheet` records that it
     * already failed once by becoming the surface things landed on because it had room;
     * this is the guard that stops a later task putting a destination back in it, which
     * is exactly how that happened the first time.
     *
     * Reads the source rather than the rendered tree for `ScreenChromeTest`'s reason:
     * `FLAG_SECURE` returns black to a screenshot, so the drawer cannot be checked by
     * looking at it.
     */
    @Test
    fun noDestinationIsReachableFromTheDrawer() {
        var dir: File? = File(".").absoluteFile
        var drawer: File? = null
        while (dir != null && drawer == null) {
            listOf(
                File(dir, "src/main/java/com/app/vault/ui/VaultDrawer.kt"),
                File(dir, "app/src/main/java/com/app/vault/ui/VaultDrawer.kt"),
            ).firstOrNull { it.isFile }?.let { drawer = it }
            dir = dir.parentFile
        }
        val text = requireNotNull(drawer) { "could not locate VaultDrawer.kt" }.readText()

        assertFalse(
            "the drawer names VaultDestination; the bar owns the destinations",
            text.contains("VaultDestination"),
        )
        VaultDestination.entries.forEach { destination ->
            // The label's resource, so this finds a drawer *row* saying "Files" and not the
            // word appearing inside a sentence of prose about why it is not here.
            val resource = "R.string.destination_" + destination.name.lowercase()
            assertFalse(
                "the drawer offers a row leading to ${TestWords.text(destination.label)}",
                text.contains(resource),
            )
        }
    }
}
