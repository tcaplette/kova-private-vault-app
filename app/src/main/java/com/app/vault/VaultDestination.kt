package com.app.vault

import androidx.annotation.StringRes

/**
 * What the grid header's `+` does on a destination — `file-import`, `DESIGN.md` §7.2a.
 *
 * ***One control whose scope is the destination under it.*** The mark is `ic_add` on both
 * cases that have one, and that is not `grid-fab`'s two-marks ambiguity: that rule is about
 * two controls on **one screen**, and no two of these are ever drawn at once. What must
 * differ is [description] — the header is icon-only, so the description is the control's
 * only name, and one wording covering both pickers would name neither.
 *
 * `null` on a destination is the whole of "no `+` here", and it means **absent, not
 * disabled** — the rule every gated control in this app follows.
 */
enum class AddAction(
    /** What the control is called to a screen reader. `screen-chrome` requires it. */
    @StringRes val description: Int,
) {

    /** The photo and video picker — `PickVisualMedia`. Media's, and unchanged. */
    Media(R.string.add_photos_or_videos),

    /**
     * The document picker — `OpenMultipleDocuments` over every type.
     *
     * **Not narrowed to exclude what other destinations hold.** Filtering images out would
     * mean a photograph the user wants kept as a document cannot be added, and would make a
     * picker's contents depend on a taxonomy nobody has shown them. A `.jpg` picked here
     * becomes an image by `item-kinds`' filename rule and lands on [VaultDestination.Media];
     * the grid says so rather than the picker preventing it.
     */
    Files(R.string.add_files),
}

/**
 * Where the user is standing inside an open vault — `vault-destinations`.
 *
 * ***An open vault was one screen until now.*** `VaultGrid` drew folders, then every kind
 * an item can be, under date headings, with selection, the trash, search and sort layered
 * over the same tiles. A photograph, a document, a note and a recording are read with
 * different verbs, and one mosaic served the first and apologised for the other three: a
 * document rendered as its own filename in a rectangle because there was nothing else to
 * draw.
 *
 * **Four destinations, five kinds.** [Media] holds two — an image and a video are both
 * looked at, both thumbnailed ([VaultKind.canHaveThumbnail] is true for exactly those two),
 * and both belong in one chronological mosaic. The chips separate them without separating
 * the screen. The other three kinds each get their own.
 *
 * **[Memos] and not "audio in Files".** [VaultKind.FILE] means *bytes this app cannot
 * interpret*; a recording made by this app's own recorder is not that. The app has a
 * recorder, a store and a player for it, so it has a destination.
 *
 * **The kinds are the whole partition, and a test holds it.** Every [VaultKind] belongs to
 * exactly one destination — `VaultDestinationTest` fails the build for a kind that belongs
 * to none or to two. A sixth kind added later cannot silently become unreachable.
 *
 * **Not a navigation graph.** The destination is one filter in the chain `MainActivity`
 * already narrows with, and it is owned there for `drawerState`'s reason: back has to be
 * able to read it. A route the one `BackHandler` cannot see is the failure `folderPath` and
 * `fabExpanded` are both owned in the activity to avoid.
 *
 * Nothing here logs, and nothing here is written to disk. A destination is where the user
 * is looking, which is not a property of the vault.
 */
enum class VaultDestination(
    /**
     * The navigation bar's label. **One word**, because four of them share a bar measured
     * at 320dp and each has to survive the largest text scale without truncating.
     */
    @StringRes val label: Int,

    /** The bar's mark. See the note on each — no new drawable was needed for any of them. */
    val icon: Int,

    /** Exactly the kinds this destination holds. Disjoint across the four; see the class note. */
    val kinds: Set<VaultKind>,

    /**
     * The shape this destination is drawn in until the user chooses otherwise —
     * `destination-view-modes`.
     *
     * ***These are exactly what each destination drew before the toggle existed***, and that
     * is the whole requirement: no vault changes appearance because of an update, and a user
     * who never finds the toggle never sees a destination change shape on its own.
     *
     * **A default is the app's choice and a mode is the user's.** A destination may not
     * *default* to a presentation that cannot show its kind — Files defaulting to tiles would
     * draw every document as a rectangle with a filename in it. Asking for that is the user's
     * right; shipping it is a defect.
     *
     * Carried here rather than in a `when` elsewhere so that adding a destination forces a
     * decision at the point the destination is declared.
     */
    val defaultViewMode: VaultViewMode,

    /**
     * What the grid header's `+` opens here, or null for a destination that carries none —
     * `file-import`.
     *
     * Carried here for [defaultViewMode]'s reason, which is the reason this whole class
     * exists in the shape it does: **a fifth destination cannot compile without answering
     * the question.** A `when` somewhere else would let one inherit an answer.
     */
    val addAction: AddAction?,
) {

    /**
     * The mosaic, unchanged, over two kinds instead of six — `vault-grid`.
     *
     * `ic_image` and not a photograph glyph of its own: nothing else in the chrome uses it
     * to mean anything but an image.
     */
    Media(
        R.string.destination_media,
        R.drawable.ic_image,
        setOf(VaultKind.IMAGE, VaultKind.VIDEO),
        // The only one that defaults to tiles, and the only one whose items all have a
        // picture to put in one.
        VaultViewMode.Tiles,
        AddAction.Media,
    ),

    /**
     * `ic_description` is already [VaultKind.FILE]'s glyph on a grid tile. One mark, one
     * meaning — the destination that holds the kind wears the kind's own mark.
     */
    Files(
        R.string.destination_files,
        R.drawable.ic_description,
        setOf(VaultKind.FILE),
        VaultViewMode.List,
        // The reason this change exists: the empty state has described documents since
        // `vault-destinations` shipped, and nothing could put one here.
        AddAction.Files,
    ),

    /** `ic_sticky_note_2` was in the drawable set and unused. Nothing else claims it. */
    Notes(
        R.string.destination_notes,
        R.drawable.ic_sticky_note_2,
        setOf(VaultKind.NOTE),
        VaultViewMode.List,
        // No `+`. The floating button writes a note, on this same screen, one tap away — a
        // header control opening a picker whose results could not land here would be a
        // control with nothing to do.
        //
        // **And nothing a picker returns can land here.** A note is a note because it
        // carries a note blob, which `VaultItem.kind` checks before asking the filename,
        // so a picked `shopping.md` is an unrecognised extension and therefore a
        // [VaultKind.FILE]. This destination is not reachable by import in any form, which
        // is a stronger reason for the absence than the one above it.
        addAction = null,
    ),

    /**
     * ***`ic_audiotrack`, and deliberately not `ic_mic`.***
     *
     * The floating button's record action is `ic_mic`, and it is on this same screen.
     * `grid-fab` forbids two controls on one screen bearing the same mark and opening
     * different things — and these two differ in the way that rule is about: one *records*,
     * one *navigates*. The mic is the verb; the audiotrack is the content, and it is
     * already [VaultKind.AUDIO]'s glyph on a tile.
     */
    Memos(
        R.string.destination_memos,
        R.drawable.ic_audiotrack,
        setOf(VaultKind.AUDIO),
        VaultViewMode.List,
        // No `+`, for [Notes]'s reason: the floating button records a memo from here.
        addAction = null,
    );

    /** Whether [item] belongs to this destination. The whole of what the filter asks. */
    fun holds(item: VaultItem): Boolean = item.kind in kinds

    companion object {

        /** Where a vault opens. The most-held kind gets the launch surface. */
        val Default = Media

        /**
         * The destination holding [kind].
         *
         * Total by construction — see the class note and `VaultDestinationTest`. Returns
         * null rather than throwing for a kind no destination claims, so a build that has
         * added a kind and forgotten a destination still draws a vault while its test fails.
         */
        fun of(kind: VaultKind): VaultDestination? = entries.firstOrNull { kind in it.kinds }
    }
}

/**
 * Where a finished import batch went, when it went nowhere near the user — `file-import`
 * D4, `DESIGN.md` §7.2a.
 *
 * ***An item's kind is decided by its filename, so a picker cannot promise a
 * destination.*** A `.jpg` chosen from Files is an image and lands on [VaultDestination.Media].
 * Without this line the user taps `+` on Files, picks a photograph, and watches nothing
 * happen on the screen they are looking at — the feature reading as broken.
 *
 * Returns null — **say nothing** — whenever any item landed on [viewing]. The items
 * appearing under the thumb is the feedback, and a message on top of it is noise. Null too
 * for an empty batch, which is not an event.
 *
 * A free function and not a method on `MainActivity`: it is the whole of the decision, it
 * has no state, and a decision that can be read by a test is one that can be argued with.
 */
fun landedElsewhere(words: Words, landed: List<VaultItem>, viewing: VaultDestination): String? {
    if (landed.isEmpty()) return null
    if (landed.any { viewing.holds(it) }) return null
    // Enum order, so a mixed batch reads in the order the bar does rather than in the
    // order the user happened to pick the files.
    val went = VaultDestination.entries
        .filter { d -> landed.any { d.holds(it) } }
        .map { words.get(it.label) }
    return when (went.size) {
        0 -> null
        1 -> words.get(R.string.added_to, went[0])
        // Two at most in practice — a photograph and a Markdown file in one pick — but
        // written for the general case rather than the observed one.
        else -> words.get(
            R.string.added_to,
            words.get(R.string.list_and, went.dropLast(1).joinToString(words.get(R.string.list_separator)), went.last()),
        )
    }
}

/**
 * How [VaultDestination.Media] is narrowed — `vault-destinations`.
 *
 * **A single-select chip row and not a two-state toggle.** A toggle offering only Photos and
 * Videos has no "both", so it hides half the vault's media at launch and forces a choice the
 * user never asked to make. [All] is the selection a vault opens on, and there is always a
 * selection.
 *
 * **View state.** Never written to disk, and gone when the vault is locked — like everything
 * else about where a session was looking.
 */
enum class MediaFilter(@StringRes val label: Int, val kinds: Set<VaultKind>) {

    All(R.string.media_filter_all, VaultDestination.Media.kinds),
    Photos(R.string.media_filter_photos, setOf(VaultKind.IMAGE)),
    Videos(R.string.media_filter_videos, setOf(VaultKind.VIDEO));

    fun holds(item: VaultItem): Boolean = item.kind in kinds

    companion object {

        /** What Media shows until the user chooses, and what a lock returns it to. */
        val Default = All
    }
}
