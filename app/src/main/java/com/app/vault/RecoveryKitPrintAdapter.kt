package com.app.vault

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date

/**
 * Whose kit a printed page is — `recovery-practice` D6.
 *
 * [List] is a keyring's kit: first run's, the held kit, and the decoy's, which is a list of
 * its own and is recovered from the unlock screen like one — so it carries the decoy's
 * [name] for the "For" line. [Vault] is a vault's own kit, recovered from the menu.
 */
sealed class KitOwner {
    data class List(val name: String? = null) : KitOwner()
    data class Vault(val name: String) : KitOwner()
}

/**
 * The printed recovery kit, one page that explains itself — P1-12, `recovery-practice` D6.
 *
 * `PrintManager` is the only route to paper on Android and needs no permission.
 * The document is a single page and never touches this app's storage: the
 * framework hands over a file descriptor it owns.
 *
 * Top to bottom: the title, whose kit it is, when it was printed, the 24 words in an outlined
 * box, what the words can do, how to use the page and where to keep it. Every block is a
 * [StaticLayout] at the printable width, so every language wraps; if the page would run
 * over, the body steps down 0.5 pt at a time to a 9 pt floor, and the words never shrink.
 * The control names on the page are the controls' own string resources. **Neither the app's
 * name nor a cover identity is printed** — a name on paper would undo a cover.
 *
 * **The spooler retains what it prints**, in a system component this app cannot
 * reach. Recorded in `FORMAT.md` §10, not hidden.
 */
class RecoveryKitPrintAdapter(
    private val context: Context,
    private val words: kotlin.collections.List<String>,
    private val owner: KitOwner,
    private val printedAt: Long,
) : PrintDocumentAdapter() {

    private var pageWidth = 0
    private var pageHeight = 0

    /** One laid-out block: where it sits, its text, and the hanging number a step carries. */
    class Block(val top: Float, val layout: StaticLayout, val left: Float, val number: String? = null, val numberPaint: TextPaint? = null)

    /** The page, laid out — what [render] draws and what `RecoveryKitPrintTest` measures. */
    class Page(
        val blocks: kotlin.collections.List<Block>,
        /** The words' box: left, top, right, bottom. */
        val box: FloatArray,
        val words: kotlin.collections.List<String>,
        val wordPaint: TextPaint,
        val numberWidth: Float,
        /** Where the content ends, which must not pass [printableBottom]. */
        val bottom: Float,
        val printableBottom: Float,
        val printableWidth: Float,
        /** The body size the page settled on, in points. */
        val bodySize: Float,
    ) {
        /** Every laid-out line and how far it reaches from the margin, for the no-overrun assertion. */
        val lines: kotlin.collections.List<Pair<String, Float>>
            get() = blocks.flatMap { b ->
                (0 until b.layout.lineCount).map {
                    b.layout.text.substring(b.layout.getLineStart(it), b.layout.getLineEnd(it)) to
                        (b.left - MARGIN + b.layout.getLineMax(it))
                }
            }

        /** All the text on the page, in order. */
        val text: String
            get() = blocks.joinToString("\n") { (it.number?.let { n -> "$n " } ?: "") + it.layout.text } +
                "\n" + words.joinToString(" ")
    }

    companion object {
        private const val DPI = 72
        private const val MARGIN = 48f

        private const val TITLE_PT = 20f
        private const val META_PT = 12f
        private const val HEADING_PT = 13f
        private const val BODY_PT = 11f
        private const val BODY_FLOOR_PT = 9f
        private const val STEP_PT = 0.5f

        private const val WORD_PT = 13f
        private const val WORD_ROW = 22f
        private const val BOX_PADDING = 12f
        private const val BOX_STROKE = 1f
        private const val ROWS = 12

        private const val GAP = 12f
        private const val SECTION_GAP = 18f
        private const val STEP_INDENT = 18f

        /**
         * Lays the page out for [widthPx] × [heightPx] at 72 dpi, stepping the body down until
         * it fits or reaches the floor.
         */
        fun layout(
            context: Context,
            words: kotlin.collections.List<String>,
            owner: KitOwner,
            printedAt: Long,
            widthPx: Int,
            heightPx: Int,
        ): Page {
            var shrink = 0f
            while (true) {
                val page = layoutAt(context, words, owner, printedAt, widthPx, heightPx, shrink)
                val smallest = BODY_PT - shrink
                if (page.bottom <= page.printableBottom || smallest - STEP_PT < BODY_FLOOR_PT) return page
                shrink += STEP_PT
            }
        }

        private fun layoutAt(
            context: Context,
            words: kotlin.collections.List<String>,
            owner: KitOwner,
            printedAt: Long,
            widthPx: Int,
            heightPx: Int,
            shrink: Float,
        ): Page {
            val width = widthPx - MARGIN * 2
            val locale = context.resources.configuration.locales[0]
            val blocks = mutableListOf<Block>()
            var y = MARGIN

            fun paint(size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }

            fun lay(text: CharSequence, p: TextPaint, w: Float): StaticLayout =
                StaticLayout.Builder.obtain(text, 0, text.length, p, w.toInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.2f)
                    .setIncludePad(false)
                    .build()

            fun add(text: CharSequence, p: TextPaint, gapAfter: Float) {
                val l = lay(text, p, width)
                blocks += Block(y, l, MARGIN)
                y += l.height + gapAfter
            }

            fun step(n: Int, text: CharSequence, p: TextPaint) {
                val l = lay(text, p, width - STEP_INDENT)
                blocks += Block(y, l, MARGIN + STEP_INDENT, "$n.", p)
                y += l.height + GAP / 2
            }

            val meta = paint(META_PT - shrink)
            val heading = paint(HEADING_PT - shrink, bold = true)
            val body = paint(BODY_PT - shrink)
            val strong = paint(BODY_PT - shrink, bold = true)

            val named = when (owner) {
                is KitOwner.List -> owner.name
                is KitOwner.Vault -> owner.name
            }

            // 1–3. What this is, whose it is, and when.
            add(context.getString(R.string.kit_print_title), paint(TITLE_PT, bold = true), GAP)
            add(
                if (named == null) context.getString(R.string.kit_print_for_list)
                else context.getString(R.string.kit_print_for_vault, named),
                meta, GAP / 3,
            )
            add(
                context.getString(
                    R.string.kit_print_date,
                    DateFormat.getDateInstance(DateFormat.LONG, locale).format(Date(printedAt)),
                ),
                meta, GAP,
            )

            // 4. The words: two columns of twelve, in an outlined box. Never shrunk.
            val wordPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = WORD_PT
                typeface = Typeface.MONOSPACE
                fontFeatureSettings = "tnum"
            }
            val box = floatArrayOf(MARGIN, y, MARGIN + width, y + BOX_PADDING * 2 + ROWS * WORD_ROW)
            y = box[3] + GAP

            // 5. What the words can do.
            add(
                if (named == null) context.getString(R.string.kit_print_anyone_list)
                else context.getString(R.string.kit_print_anyone_vault, named),
                strong, SECTION_GAP - GAP / 2,
            )

            // 6. How to use this page. The control names are the controls' own resources.
            add(context.getString(R.string.kit_print_how_heading), heading, GAP / 2)
            when (owner) {
                is KitOwner.List -> {
                    step(1, context.getString(R.string.kit_print_list_step_1), body)
                    step(2, context.getString(R.string.kit_print_list_step_2, context.getString(R.string.unlock_forgot)), body)
                    step(3, context.getString(R.string.kit_print_list_step_3), body)
                    add(context.getString(R.string.kit_print_backups), body, 0f)
                }
                is KitOwner.Vault -> {
                    step(1, context.getString(R.string.kit_print_vault_step_1), body)
                    step(2, context.getString(R.string.kit_print_vault_step_2, context.getString(R.string.drawer_recover_vault)), body)
                    step(3, context.getString(R.string.kit_print_vault_step_3, owner.name), body)
                    y -= GAP / 2
                }
            }
            y += SECTION_GAP

            // 7. Where to keep it.
            add(context.getString(R.string.kit_print_keep_heading), heading, GAP / 2)
            add(context.getString(R.string.kit_print_keep_body), body, 0f)

            return Page(
                blocks = blocks,
                box = box,
                words = words,
                wordPaint = wordPaint,
                numberWidth = wordPaint.measureText("${words.size}."),
                bottom = y,
                printableBottom = heightPx - MARGIN,
                printableWidth = width,
                bodySize = BODY_PT - shrink,
            )
        }

        private fun draw(canvas: Canvas, page: Page) {
            page.blocks.forEach { block ->
                block.number?.let { n ->
                    val p = block.numberPaint!!
                    canvas.drawText(n, block.left - STEP_INDENT, block.top - p.fontMetrics.ascent, p)
                }
                canvas.save()
                canvas.translate(block.left, block.top)
                block.layout.draw(canvas)
                canvas.restore()
            }

            val (left, top, right, bottom) = page.box.toList()
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = BOX_STROKE
            }
            canvas.drawRect(left, top, right, bottom, outline)

            // The words stay English in every language — they are the BIP-39 wordlist,
            // and a kit printed under one language must open the vault under another.
            val p = page.wordPaint
            val column = (right - left - BOX_PADDING * 2) / 2
            val numberPaint = TextPaint(p).apply { textAlign = Paint.Align.RIGHT }
            val baseline = (WORD_ROW - (p.fontMetrics.descent + p.fontMetrics.ascent)) / 2
            page.words.forEachIndexed { i, word ->
                val x = left + BOX_PADDING + (i / ROWS) * column
                val rowTop = top + BOX_PADDING + (i % ROWS) * WORD_ROW
                canvas.drawText("${i + 1}.", x + page.numberWidth, rowTop + baseline, numberPaint)
                canvas.drawText(word, x + page.numberWidth + p.measureText(" "), rowTop + baseline, p)
            }
        }

        /**
         * The page, as PDF bytes.
         *
         * Separate from [onWrite] because `LayoutResultCallback` and
         * `WriteResultCallback` have package-private constructors — only the
         * framework can build one, so a test cannot drive the adapter directly.
         * This is the part worth asserting, and it is reachable.
         */
        fun render(
            context: Context,
            words: kotlin.collections.List<String>,
            owner: KitOwner,
            printedAt: Long,
            widthPx: Int,
            heightPx: Int,
        ): ByteArray {
            val document = PdfDocument()
            try {
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(widthPx, heightPx, 0).create()
                )
                draw(page.canvas, layout(context, words, owner, printedAt, widthPx, heightPx))
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
            val pdf = render(context, words, owner, printedAt, pageWidth, pageHeight)

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
