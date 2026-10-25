package com.app.vault

import androidx.annotation.StringRes

/**
 * What shape a destination's list is — `destination-view-modes`.
 *
 * ***`vault-destinations` welded two things together and this pulls them apart.*** That
 * change gave each destination one presentation: Media the mosaic, the other three rows. It
 * was right that a document is not a photograph, and wrong that the shape of a list is a
 * property of the kind rather than a preference. A vault of five hundred photographs wants a
 * mosaic; a vault of thirty documents wants rows carrying sizes and dates. One answer per
 * destination cannot serve both people.
 *
 * **Where it showed was the folder.** `FolderTile` drew a square colour block with the name
 * beneath, in every destination, because the mosaic was the only thing that had ever drawn a
 * folder — so in Notes a folder read as a coloured card sitting among notes rather than as a
 * way into somewhere. Dropbox, Drive and Files all answer that identically, and the answer is
 * not a different tile: a folder in a **list** is a row, and a folder in a **grid** is a tile
 * with a cover. The app had no list, so it had no folder row.
 *
 * **Two, and never three.** The toggle is a two-state control and this is what it switches
 * between. A third mode would need a different control and is not what was asked for.
 *
 * **A device preference, not a view of a vault.** Held in `GridPreference` beside the
 * ordering and the density, and — unlike the destination, the media filter and each
 * destination's saved place — it is deliberately *not* cleared on lock. Those are views of an
 * unlocked vault and die with the session; this is how the person likes their lists, which
 * outlives it.
 */
enum class VaultViewMode {

    /** Rows. Folders as glyph-and-name, items in the form their kind is read in. */
    List,

    /** The mosaic. Folders as covered squares, items as tiles at their own aspect ratio. */
    Tiles;

    /**
     * The mode this one switches to — the whole of what the toggle needs to know.
     *
     * Two members, so this is the other one. Written as a `when` rather than as index
     * arithmetic on `entries` because a third member would then silently rotate instead of
     * failing to compile.
     */
    val other: VaultViewMode get() = when (this) {
        List -> Tiles
        Tiles -> List
    }

    /**
     * What the toggle announces to accessibility services — **the action, not the state**.
     *
     * `screen-chrome` requires every interactive control be named, and an icon-only control
     * in a band is unreachable without one. Naming it by the state ("List view") would make a
     * screen reader read a button as a label; naming it by what pressing it does is what a
     * button is.
     */
    @get:StringRes
    val actionLabel: Int get() = when (this) {
        List -> R.string.view_as_list
        Tiles -> R.string.view_as_grid
    }

    /**
     * The toggle's mark for reaching *this* mode.
     *
     * `ic_format_list_bulleted` and `ic_apps` were both already in the drawable set and
     * neither means anything else in the chrome — no new asset. The toggle draws
     * `other.icon`, because it shows where pressing it goes rather than where you are: a
     * two-state control showing its current state reads as a label, and one showing its
     * destination reads as an action. Drive, Dropbox and Files all make the same call.
     */
    val icon: Int get() = when (this) {
        List -> R.drawable.ic_format_list_bulleted
        Tiles -> R.drawable.ic_apps
    }
}
