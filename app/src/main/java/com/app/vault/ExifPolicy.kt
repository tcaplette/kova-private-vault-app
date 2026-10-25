package com.app.vault

import android.content.Context
import androidx.annotation.StringRes
import java.io.InputStream
import java.io.OutputStream

/**
 * One of the four things a photograph records that the user may choose to remove —
 * `exif-strip-choice`. Named by the user, and exactly four.
 */
enum class PhotoDetail {
    LOCATION, CAMERA, DATE, SOFTWARE;

    companion object {
        /** Stored names read back. An unknown one is ignored, not fatal — a later build may add a fifth. */
        fun namedIn(names: Collection<String>): Set<PhotoDetail> =
            names.mapNotNull { n -> entries.firstOrNull { it.name == n } }.toSet()
    }
}

/** Privacy, or the user's own selection. Stored by name, like every other choice in `GridPreference`. */
enum class PhotoDetailsPreset {
    PRIVACY, CUSTOM;

    @get:StringRes
    val label: Int get() = if (this == PRIVACY) R.string.photo_details_privacy else R.string.photo_details_custom

    companion object {
        fun of(name: String?): PhotoDetailsPreset = entries.firstOrNull { it.name == name } ?: PRIVACY
    }
}

/**
 * What a strip removes, and the only place that answers it — `exif-strip-choice`.
 *
 * ***An allowlist with four doors in it.*** Every field a photograph carries is claimed by one of
 * the user's four categories, by the small set a photograph needs to render, or by nothing — and
 * a field claimed by nothing is removed on every strip. That default is the whole reason this is
 * an allowlist: a tag the app has never heard of is removed because nobody claimed it, not
 * because somebody remembered to name it.
 *
 * **One selection, three call sites.** Import, export and share each decide only *whether* to
 * strip; what a strip removes is [removed], read from the Photo details sheet's one answer.
 *
 * **One mechanism.** All three paths stream through [copyStripped] — export included, since
 * `ExifInterface.saveAttributes()` copies the file into `File.createTempFile`, which is the
 * app's cache directory, and no path may stage a plaintext photo anywhere.
 */
object ExifPolicy {

    /**
     * The shipped default: location, camera info and edit history go, and the date stays so a
     * stripped photograph still sorts by when it was taken.
     *
     * **Read from here, never from storage, while Privacy is chosen** — so Privacy means what
     * the current build says it means, which is the reason to have a preset at all.
     */
    val PRIVACY: Set<PhotoDetail> = setOf(PhotoDetail.LOCATION, PhotoDetail.CAMERA, PhotoDetail.SOFTWARE)

    /**
     * Whether a strip can reach this type at all.
     *
     * **JPEG only, and the controls say so.** HEIC, PNG and WebP are other containers and are
     * stored and written unchanged; a switch that claims to remove a home address and silently
     * does nothing for half a library is worse than no switch.
     */
    fun strippable(mime: String?): Boolean =
        mime?.substringBefore(';')?.trim()?.lowercase() in setOf("image/jpeg", "image/jpg")

    fun resolve(preset: PhotoDetailsPreset, custom: Set<PhotoDetail>): Set<PhotoDetail> =
        if (preset == PhotoDetailsPreset.PRIVACY) PRIVACY else custom

    /** What a strip removes on this install, right now. */
    fun removed(context: Context): Set<PhotoDetail> =
        resolve(GridPreference.photoDetailsPreset(context), GridPreference.photoDetailsCustom(context))

    // ── Claims ────────────────────────────────────────────────────────────────

    /** Who a field belongs to. */
    sealed interface Claim
    /** Needed to render the photograph. Survives every strip. */
    data object Kept : Claim
    /** Claimed by nothing. Removed by every strip, kept only when nothing is stripped. */
    data object Unclaimed : Claim
    /** Belongs to one of the user's four. */
    data class In(val detail: PhotoDetail) : Claim

    /** [removed] null means no strip: everything stays, and only export's additions apply. */
    fun survives(claim: Claim, removed: Set<PhotoDetail>?): Boolean = when (claim) {
        Kept -> true
        Unclaimed -> removed == null
        is In -> removed == null || claim.detail !in removed
    }

    private val camera = In(PhotoDetail.CAMERA)
    private val date = In(PhotoDetail.DATE)
    private val software = In(PhotoDetail.SOFTWARE)
    private val location = In(PhotoDetail.LOCATION)

    /** Tag numbers, because that is what the TIFF reader sees. Design D1, row by row. */
    internal val IFD0_KEPT = setOf(
        0x0100, 0x0101, 0x0102, 0x0103, 0x0106, 0x0112, 0x0115, 0x011A, 0x011B, 0x011C,
        0x0128, 0x012D, 0x013E, 0x013F, 0x0211, 0x0212, 0x0213, 0x0214,
        TAG_EXIF_IFD,
    )
    internal val IFD0_CAMERA = setOf(0x010F, 0x0110)
    internal val IFD0_DATE = setOf(0x0132)
    internal val IFD0_SOFTWARE = setOf(0x000B, 0x0131, 0x013C)

    internal val EXIF_KEPT = setOf(0x9000, 0x9101, 0xA000, 0xA001, 0xA002, 0xA003, TAG_INTEROP_IFD, 0xA500)
    internal val EXIF_CAMERA: Set<Int> = buildSet {
        addAll(listOf(0x829A, 0x829D, 0x8822, 0x8824, 0x9214, 0x927C, 0xA420))
        addAll(0x8827..0x882A)
        addAll(0x8830..0x8835)
        addAll(0x9201..0x920A)
        addAll(0xA20B..0xA217)
        addAll(0xA300..0xA302)
        addAll(0xA401..0xA40C)
        addAll(0xA430..0xA435)
        addAll(0xA460..0xA462)
    }
    internal val EXIF_DATE: Set<Int> = buildSet {
        addAll(listOf(0x9003, 0x9004))
        addAll(0x9010..0x9012)
        addAll(0x9290..0x9292)
    }
    internal val INTEROP_KEPT = setOf(0x0001, 0x0002)

    internal fun ifd0(tag: Int): Claim = when (tag) {
        in IFD0_KEPT -> Kept
        in IFD0_CAMERA -> camera
        in IFD0_DATE -> date
        in IFD0_SOFTWARE -> software
        TAG_GPS_IFD -> location
        else -> Unclaimed
    }

    internal fun exifIfd(tag: Int): Claim = when (tag) {
        in EXIF_KEPT -> Kept
        in EXIF_CAMERA -> camera
        in EXIF_DATE -> date
        else -> Unclaimed
    }

    /** Every field of the GPS IFD is where the photograph was taken, its time stamp included. */
    internal fun gpsIfd(@Suppress("UNUSED_PARAMETER") tag: Int): Claim = location

    internal fun interop(tag: Int): Claim = if (tag in INTEROP_KEPT) Kept else Unclaimed

    internal const val TAG_EXIF_IFD = 0x8769
    internal const val TAG_GPS_IFD = 0x8825
    internal const val TAG_INTEROP_IFD = 0xA005
    internal const val TAG_ORIENTATION = 0x0112
    internal const val TAG_DATETIME = 0x0132
    internal const val TAG_DATETIME_ORIGINAL = 0x9003

    // ── The stream ────────────────────────────────────────────────────────────

    /**
     * The two things export writes into a photograph as it leaves: the vault's date where the
     * file has none, and the stored rotation composed into its orientation. Neither is
     * preserved metadata — both are this app asserting what it holds.
     */
    class Additions(
        /** `yyyy:MM:dd HH:mm:ss`, or null for no stamp. Written only where the file has no date. */
        val dateStamp: String?,
        /** The orientation to write given the one the file carries, or null for no turn. */
        val orientation: ((Int) -> Int)?,
    ) {
        val isEmpty: Boolean get() = dateStamp == null && orientation == null
    }

    /** What a pass did, for the manifest. */
    class Outcome {
        /** Whether any byte left differently from how it came in. */
        var changed = false
        /** An addition that could not fit the file's EXIF block and was not written. */
        var additionsSkipped = false
    }

    /**
     * Copies [input] to [out] as a strip under [removed], or — with [removed] null — as a pass
     * that changes nothing but applies [additions].
     *
     * A stream that is not a JPEG is copied through untouched: this is not the place to decide
     * a file is broken.
     */
    fun copyStripped(
        input: InputStream,
        out: OutputStream,
        removed: Set<PhotoDetail>?,
        additions: Additions? = null,
    ): Outcome = Outcome().also { JpegStrip.copy(input, out, removed, additions, it) }

    // ── The words ─────────────────────────────────────────────────────────────

    private val order = listOf(PhotoDetail.LOCATION, PhotoDetail.CAMERA, PhotoDetail.DATE, PhotoDetail.SOFTWARE)

    private fun Words.noun(d: PhotoDetail, article: Boolean) = get(
        when (d) {
            PhotoDetail.LOCATION -> R.string.exif_noun_location
            PhotoDetail.CAMERA -> R.string.exif_noun_camera
            PhotoDetail.DATE -> if (article) R.string.exif_noun_the_date else R.string.exif_noun_date
            PhotoDetail.SOFTWARE -> R.string.exif_noun_software
        }
    )

    /**
     * "a, b and c" in the app's language — `app-languages`. The separator and the joining
     * word are each language's own; every sentence that takes the list takes it whole, and
     * agrees with its length through a plural.
     */
    private fun Words.joined(items: List<String>, @StringRes last: Int): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> get(last, items.dropLast(1).joinToString(get(R.string.list_separator)), items.last())
    }

    private fun String.capitalised() = replaceFirstChar { it.uppercase() }

    /**
     * The export control's words with the strip on — design D8's table. One function for the
     * export and share copy so the words and the behaviour read the same set.
     */
    fun exportSummary(words: Words, removed: Set<PhotoDetail>): String {
        val gone = order.filter { it in removed }
        if (gone.isEmpty()) return words.get(R.string.exif_export_names_only)
        val kept = order.filter { it !in removed }
        val removedPart = words.plural(
            R.plurals.exif_export_removed, gone.size,
            words.joined(gone.map { words.noun(it, true) }, R.string.list_and).capitalised(),
        )
        val keptPart = if (kept.isEmpty()) "" else " " + words.plural(
            if (PhotoDetail.DATE in kept) R.plurals.exif_export_kept_sorted else R.plurals.exif_export_kept,
            kept.size,
            words.joined(kept.map { words.noun(it, true) }, R.string.list_and).capitalised(),
        )
        return words.get(R.string.exif_export_summary, removedPart + keptPart)
    }

    /** The share screen's removing row. */
    fun shareSummary(words: Words, removed: Set<PhotoDetail>): String {
        val gone = order.filter { it in removed }
        if (gone.isEmpty()) return words.get(R.string.exif_share_names_only)
        val kept = order.filter { it !in removed }
        val removedPart = words.get(R.string.exif_share_removed, words.joined(gone.map { words.noun(it, false) }, R.string.list_or))
        val keptPart = if (kept.isEmpty()) "" else " " +
            words.get(R.string.exif_share_kept, words.joined(kept.map { words.noun(it, true) }, R.string.list_and))
        return removedPart + keptPart
    }
}
