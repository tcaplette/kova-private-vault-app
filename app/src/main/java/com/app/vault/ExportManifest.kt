package com.app.vault

import android.net.Uri

/**
 * The receipt for an export — P1-13.
 *
 * **Appended per item, never assembled and written at the end.** A manifest built
 * in memory does not exist for the run that was killed, and that is precisely the
 * run whose record matters. Each item adds its line the moment it resolves, and
 * the line is flushed before the next item starts.
 *
 * Failures are lines too. A manifest listing only successes cannot be used to
 * check that everything left, which is the only thing it is for.
 *
 * **Written at the destination, not staged in `filesDir`.** Staging would put a
 * plaintext list of filenames inside the app's own storage on every export —
 * exactly what I3 forbids and what Gate 1's scan looks for. It lives beside the
 * exported files instead, in the clear, naming what is now outside the vault,
 * which is the right place for the record of an operation whose whole purpose was
 * to move data out of this app's protection.
 */
class ExportManifest(
    private val destination: ExportDestination,
    private val entry: ExportEntry
) {

    companion object {
        const val NAME = "unvault-manifest.txt"

        private const val HEADER =
            "# Vault export manifest\n" +
                "# outcome\tname\tbytes\tsha256\tnotes\n"

        /**
         * Opens the manifest for this export, **resuming** the one already at the
         * destination if a previous run left one.
         *
         * Resuming is found by name rather than by a URI carried in the worker's
         * input: a retried worker is handed its *original* input back, so a URI
         * threaded through it would be null on every retry and every retry would
         * begin a second manifest — which defeats the point of a record that
         * outlives the run it documents.
         *
         * The manifest is **not** pending: it is visible from its first line, so a
         * user watching an export can read what has left so far.
         */
        fun openAt(destination: ExportDestination): ExportManifest {
            destination.find(NAME)?.let { return ExportManifest(destination, it) }

            val entry = destination.create(NAME, "text/plain", null)
            // The header goes through `openOutput`, not `openAppend`, and **before**
            // `finish`. A row that is un-pended without ever being written has no
            // file behind it, and every later append fails with "No item at" —
            // found by a failing test, and invisible to a fake destination.
            destination.openOutput(entry).use { it.write(HEADER.toByteArray(Charsets.UTF_8)) }
            destination.finish(entry)
            return ExportManifest(destination, entry)
        }
    }

    /** Where this manifest lives, so a resumed run appends rather than starting a second one. */
    val uri: Uri get() = entry.uri

    private fun append(text: String) {
        destination.openAppend(entry).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    fun succeeded(
        name: String,
        bytes: Long,
        sha256: String,
        exifEdited: Boolean,
        /**
         * `exif-strip-choice`. The date or the turn would not fit the file's EXIF block and was
         * not written. Rare, and said on the line rather than nowhere.
         */
        stampSkipped: Boolean = false,
    ) = append(
        // A file whose EXIF was edited — stripped, or stamped with a date it did
        // not carry — is deliberately not hash-identical to its import. The hash
        // recorded is of the bytes as they left the vault, said per line so a later
        // comparison does not read as corruption.
        "OK\t$name\t$bytes\t$sha256\t" +
            (if (exifEdited) "exif-edited; hash is of the file as it left the vault" else "") +
            (if (stampSkipped) "; the date or rotation did not fit and was not written" else "") + "\n"
    )

    fun failed(name: String, reason: String) = append("FAILED\t$name\t0\t-\t$reason\n")

    fun lines(): List<String> =
        destination.openInput(entry).use { input ->
            input.bufferedReader().readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
        }

    fun entryCount(): Int = lines().size
}
