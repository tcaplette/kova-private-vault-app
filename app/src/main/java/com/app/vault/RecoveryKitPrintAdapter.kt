package com.app.vault

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.FileOutputStream

/**
 * One page of monospace text — P1-12's printable recovery kit.
 *
 * `PrintManager` is the only route to paper on Android and needs no permission.
 * The document is a single page and never touches this app's storage: the
 * framework hands over a file descriptor it owns.
 *
 * **The spooler retains what it prints**, in a system component this app cannot
 * reach. Recorded in `FORMAT.md` §10, not hidden.
 */
class RecoveryKitPrintAdapter(
    private val context: Context,
    private val lines: List<String>,
) : PrintDocumentAdapter() {

    private var pageWidth = 0
    private var pageHeight = 0

    companion object {
        private const val DPI = 72
        private const val MARGIN = 48f
        private const val TEXT_SIZE = 12f
        private const val LINE_HEIGHT = 20f

        /**
         * The page, as PDF bytes.
         *
         * Separate from [onWrite] because `LayoutResultCallback` and
         * `WriteResultCallback` have package-private constructors — only the
         * framework can build one, so a test cannot drive the adapter directly.
         * This is the part worth asserting, and it is reachable.
         */
        fun render(context: Context, lines: List<String>, widthPx: Int, heightPx: Int): ByteArray {
            val document = PdfDocument()
            try {
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(widthPx, heightPx, 0).create()
                )

                val heading = Paint().apply { textSize = TEXT_SIZE + 4f }
                val body = Paint().apply {
                    textSize = TEXT_SIZE
                    typeface = android.graphics.Typeface.MONOSPACE
                }

                var y = MARGIN + LINE_HEIGHT
                page.canvas.drawText(context.getString(R.string.kit_print_heading), MARGIN, y, heading)
                y += LINE_HEIGHT * 2

                // The words stay English in every language — they are the BIP-39 wordlist,
                // and a kit printed under one language must open the vault under another.
                val preamble = listOf(
                    context.getString(R.string.kit_print_line_1),
                    context.getString(R.string.kit_print_line_2),
                    ""
                )
                (preamble + lines).forEach { line ->
                    page.canvas.drawText(line, MARGIN, y, body)
                    y += LINE_HEIGHT
                }

                document.finishPage(page)
                return java.io.ByteArrayOutputStream().also { document.writeTo(it) }.toByteArray()
            } finally {
                document.close()
            }
        }
    }

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }

        val media = newAttributes.mediaSize ?: PrintAttributes.MediaSize.ISO_A4
        // Media dimensions are mils (thousandths of an inch).
        pageWidth = media.widthMils * DPI / 1000
        pageHeight = media.heightMils * DPI / 1000

        callback.onLayoutFinished(
            PrintDocumentInfo.Builder("recovery-kit.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build(),
            oldAttributes != newAttributes
        )
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback
    ) {
        try {
            val pdf = render(context, lines, pageWidth, pageHeight)

            if (cancellationSignal?.isCanceled == true) {
                callback.onWriteCancelled()
                return
            }

            FileOutputStream(destination.fileDescriptor).use { it.write(pdf) }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}
