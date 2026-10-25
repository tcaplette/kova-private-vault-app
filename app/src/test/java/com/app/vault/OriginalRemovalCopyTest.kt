package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/** The prompt's sentence, against the exact strings `original-removal` specifies. */
class OriginalRemovalCopyTest {

    @Test
    fun photosOnly() {
        assertEquals(
            "4 photos are now in this vault. Android will ask you to confirm.",
            OriginalRemoval.body(TestWords, photos = 4, videos = 0, unavailable = 0),
        )
    }

    @Test
    fun oneItem() {
        assertEquals(
            "1 video is now in this vault. Android will ask you to confirm.",
            OriginalRemoval.body(TestWords, photos = 0, videos = 1, unavailable = 0),
        )
    }

    @Test
    fun mixedWithSomeUnavailable() {
        assertEquals(
            "3 photos and 1 video are now in this vault. Android will ask you to confirm. " +
                "2 others can't be removed from here and will stay in your gallery.",
            OriginalRemoval.body(TestWords, photos = 3, videos = 1, unavailable = 2),
        )
    }

    @Test
    fun oneUnavailable() {
        assertEquals(
            "1 photo is now in this vault. Android will ask you to confirm. " +
                "1 other can't be removed from here and will stay in your gallery.",
            OriginalRemoval.body(TestWords, photos = 1, videos = 0, unavailable = 1),
        )
    }
}
