package com.app.vault

/**
 * What an item is — P2-18.
 *
 * Four kinds, decided from the filename's extension and from nothing else. The
 * classification already existed before this file: `VaultGrid` and `Thumbnails`
 * each carried their own copy of one video extension set, and
 * `ExportDestination.mimeOf` derives the export MIME the same way. This is that
 * rule, written once.
 *
 * **The extension is the source, and the app already decided that.** P2-14 made the
 * extension **non-editable** — a user renames the stem and the ending stays — for
 * exactly this reason: it has to keep describing the bytes. The alternatives were
 * weighed and rejected in this change's design:
 *
 * - *A stored MIME at import.* Accurate for a mislabelled file, and it costs a
 *   `FORMAT.md` field, an index version bump, and a second source of truth that has
 *   to agree with the filename through every rename and every archive round trip —
 *   with a fallback to the extension for items imported before it existed, which is
 *   this file anyway.
 * - *Sniffing the bytes.* Reading a blob's head means decrypting it. Drawing a grid
 *   of five hundred items would decrypt five hundred heads.
 *
 * **The cost, named:** an item imported under a wrong extension is classified
 * wrongly — a `.zip` called `holiday.jpg` is an image that fails to decode, which is
 * precisely what it does today. P2-14 recorded that cost in the same words.
 *
 * **P2-21 adds a fifth kind that this rule does not decide.** A [NOTE] is decided
 * **structurally** — by the item carrying a body — and `VaultItem.kind` checks that
 * before calling [kindOf]. Nothing in this file changed for it: the four content
 * kinds still come from the extension, from exactly the sets below, so no item
 * already in a vault changes kind. Deciding a note from its extension instead would
 * mean a rename converting it to a file whose bytes do not exist.
 *
 * **An unrecognised extension is [FILE], never an error.** The default's failure
 * mode is "a name, a size and an export", which is the honest answer for bytes the
 * app cannot interpret — and every kind is stored, listed, renamed, trashed and
 * exported identically regardless.
 *
 * No logging here: a filename in a log line is the boundary P2-14 recorded.
 */
enum class VaultKind {
    IMAGE,
    VIDEO,
    AUDIO,

    /** Anything the app does not render. Not an error, and not a lesser item. */
    FILE,

    /**
     * Text the app itself wrote — P2-21. **Not decided here:** an item is a note
     * because it carries a body, which [kindOf] cannot see. See `VaultItem.kind`.
     */
    NOTE;

    /**
     * Kinds a thumbnail can be made for. The rest are decided, not pending — P2-18.
     *
     * [NOTE] is false, which is the whole of what keeps `Thumbnails.pending` from
     * queueing a note: no branch, just the kind answering honestly.
     */
    val canHaveThumbnail: Boolean get() = this == IMAGE || this == VIDEO

    /** Kinds the player takes. */
    val isPlayable: Boolean get() = this == VIDEO || this == AUDIO

    companion object {

        /**
         * **Exactly the set that shipped before P2-18**, moved rather than rewritten.
         * `VaultGrid.isVideo` and `Thumbnails.isVideo` both held this list; a
         * rewrite here would silently change the kind of items already in vaults.
         */
        private val VIDEO_EXTENSIONS =
            setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "avi", "ts")

        private val AUDIO_EXTENSIONS =
            setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "wma", "amr")

        private val IMAGE_EXTENSIONS =
            setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "dng")

        /**
         * [filename]'s kind. Case-insensitive, and total: every string has one,
         * including the empty one.
         */
        fun kindOf(filename: String): VaultKind {
            // `lastIndexOf` with a **positive** index, not `substringAfterLast`:
            // a dotfile's leading dot is part of its name, not the start of an
            // extension, so `.jpg` is a file called `.jpg` and not a photograph.
            // The two copies this replaced used `substringAfterLast` and got that
            // wrong; nothing noticed, because nobody imports a file called `.mp4`.
            val dot = filename.lastIndexOf('.')
            val ext = if (dot > 0) filename.substring(dot + 1).lowercase() else ""
            return when (ext) {
                in VIDEO_EXTENSIONS -> VIDEO
                in AUDIO_EXTENSIONS -> AUDIO
                in IMAGE_EXTENSIONS -> IMAGE
                else -> FILE
            }
        }

        /** For the tests that assert no item changes kind on upgrade. */
        internal fun videoExtensions(): Set<String> = VIDEO_EXTENSIONS
    }
}
