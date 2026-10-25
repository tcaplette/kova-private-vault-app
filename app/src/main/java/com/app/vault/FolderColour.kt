package com.app.vault

import androidx.annotation.StringRes

/**
 * The colours a folder can be — `folder-system`.
 *
 * **A fixed set and not a picker.** An arbitrary colour is a colour that can be
 * illegible against either scheme, and the app draws in two (P2-23). These six are
 * defined once in the theme against both palettes and are the only ones a folder can
 * hold.
 *
 * **No purple and no primary indigo.** Purple is `DESIGN.md` §12's mechanical ban, which
 * binds every token in either scheme. The primary's indigo is barred for the opposite
 * reason: it holds the primary-action role in this app and nothing else, and a folder
 * that is simply the indigo one would be the first thing wearing that colour without
 * meaning it.
 *
 * **[None] is the default and it is not "no colour"** — it is the surface the grid
 * already draws, so a folder nobody has coloured looks like a folder rather than like
 * a gap. It is first because it is what every folder starts as.
 *
 * **The ordinal is serialised**, so entries are appended and never reordered: moving
 * one would repaint every folder in every vault written before the move. An ordinal
 * this build does not know reads as [None] — `MetadataDb.parse`'s rule for a
 * preference written by a later build, one field along.
 *
 * Meaning never rides on the colour alone (§6.4). A folder tile always draws its name.
 */
enum class FolderColour(
    /** What a screen reader calls the colour — `app-languages`. Stored by [name], never by this. */
    @StringRes val label: Int,
) {
    None(R.string.colour_none),
    Red(R.string.colour_red),
    Amber(R.string.colour_amber),
    Teal(R.string.colour_teal),
    Blue(R.string.colour_blue),
    Pink(R.string.colour_pink);

    companion object {
        /** The stored ordinal's colour, or [None] when it is one this build has no entry for. */
        fun of(ordinal: Int): FolderColour = entries.getOrNull(ordinal) ?: None
    }
}
