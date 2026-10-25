package com.app.vault.ui

import androidx.compose.runtime.Composable
import com.app.vault.R
import com.app.vault.Words
import com.app.vault.words
import java.util.Locale
import com.app.vault.localWords

/**
 * A size in words — P2-18.
 *
 * **1000-based units under SI names.** Binary sizes printed under decimal names is
 * the usual quiet lie — 1 "MB" that is actually 1,048,576 bytes — and the number
 * here exists to be compared against what the user's file manager and their
 * computer say about the same file. Those say 1000.
 *
 * One decimal place above `kB`, none below, because a document's size to the tenth
 * of a kilobyte is precision nobody reads. `Locale.getDefault()` for the separator:
 * this is a number being read aloud, not a filename being sorted — the opposite of
 * P2-16's rule, and for the opposite reason.
 */
fun sizeLabel(words: Words, bytes: Long): String {
    val n = bytes.coerceAtLeast(0L)
    if (n < 1000) return words.get(R.string.size_bytes, n)

    // `app-languages`: the unit symbols are each language's own — "Mo" in French.
    val units = listOf(R.string.size_kb, R.string.size_mb, R.string.size_gb, R.string.size_tb, R.string.size_pb)
    var value = n.toDouble() / 1000.0
    var unit = 0
    while (value >= 1000.0 && unit < units.lastIndex) {
        value /= 1000.0
        unit++
    }
    return words.get(units[unit], String.format(Locale.getDefault(), "%.1f", value))
}

@Composable
fun sizeLabel(bytes: Long): String = sizeLabel(localWords(), bytes)
