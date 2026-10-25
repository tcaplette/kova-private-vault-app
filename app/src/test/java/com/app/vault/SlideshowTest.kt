package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/** `slideshow` — the order from the start item, shuffle's coverage, the reshuffled pass, and the end. */
class SlideshowTest {

    @Test
    fun inOrderStartsAtTheItemOnScreenAndRunsToTheEnd() {
        assertEquals(listOf(11, 12, 13, 14), Slideshow.order(15, 11, shuffle = false))
    }

    @Test
    fun repeatOffEndsAfterTheLastItem() {
        // The first pass is the whole of a slideshow without Repeat: nothing comes after it.
        val pass = Slideshow.order(50, 0, shuffle = false)
        assertEquals(49, pass.last())
        assertEquals(50, pass.size)
    }

    @Test
    fun repeatInOrderContinuesFromTheFirstItem() {
        assertEquals((0 until 5).toList(), Slideshow.nextPass(5, shuffle = false, last = 4))
    }

    @Test
    fun shuffleBeginsWithTheStartAndShowsAllFiftyOnce() {
        repeat(200) { seed ->
            val pass = Slideshow.order(50, 17, shuffle = true, rng = Random(seed))
            assertEquals(17, pass.first())
            assertEquals("every item once, seed $seed", (0 until 50).toSet(), pass.toSet())
            assertEquals(50, pass.size)
        }
    }

    @Test
    fun aReshuffledPassNeverBeginsWithTheItemJustShown() {
        repeat(500) { seed ->
            val pass = Slideshow.nextPass(50, shuffle = true, last = 3, rng = Random(seed))
            assertNotEquals("seed $seed", 3, pass.first())
            assertEquals((0 until 50).toSet(), pass.toSet())
        }
        assertEquals(listOf(0), Slideshow.nextPass(1, shuffle = true, last = 0))
    }

    @Test
    fun aPhotoWaitsTheIntervalAndAVideoWaitsItsEnd() {
        assertEquals(5_000L, Slideshow.interval(VaultKind.IMAGE, SlideshowSpeed.Default))
        assertEquals(3_000L, Slideshow.interval(VaultKind.IMAGE, SlideshowSpeed.Three))
        assertNull(Slideshow.interval(VaultKind.VIDEO, SlideshowSpeed.Ten))
    }

    @Test
    fun speedsAreStoredByNameWithFiveTheDefault() {
        assertEquals(listOf(3, 5, 10), SlideshowSpeed.entries.map { it.seconds })
        assertEquals(SlideshowSpeed.Five, SlideshowSpeed.of("Twenty"))
        assertEquals(SlideshowSpeed.Ten, SlideshowSpeed.of("Ten"))
        val d = SlideshowSettings()
        assertEquals(false, d.repeat)
        assertEquals(false, d.shuffle)
    }
}
