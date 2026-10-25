package com.app.vault

/**
 * A note's formatting — `notes-as-documents`.
 *
 * **Markdown, and only the part of it that survives being read as plain text.** A
 * note's promise is that it leaves the vault readable by anything; that is why the
 * stored form is Markdown rather than HTML or a styled-span blob, and it is why the
 * set stops where it does. Tables are the one construct that reads as noise in an
 * exported file, and links, code fences and block quotes are a writer's tools in an
 * app that is not for writing documents. Anything outside the set renders as the
 * literal characters that were typed — never as an error, and never swallowed.
 *
 * **Pure, and no Compose.** [parse] returns structure and offsets; the composable
 * turns them into an `AnnotatedString`. That split is what keeps every rule in this
 * file checkable on the JVM — the inline scanner is exactly the kind of code that is
 * wrong at the edges, and finding out on an emulator is finding out late.
 *
 * No logging. A note's text is the content itself, which P2-14 already put out of
 * bounds for a filename and which is worse here.
 */
object NoteMarkdown {

    /** The three inline styles. Nothing nests inside another. */
    enum class Style { Bold, Italic, Strike }

    /** [from] inclusive, [to] exclusive, in [Inline.text]'s own indices. */
    data class Span(val from: Int, val to: Int, val style: Style)

    /** One line's text with its markers removed, and where the styles landed. */
    data class Inline(val text: String, val spans: List<Span> = emptyList())

    /**
     * One line of the note.
     *
     * [line] is the index into the original text's lines, so a tapped checkbox can be
     * rewritten without the renderer knowing anything about the string it came from.
     */
    sealed interface Block {
        val line: Int

        data class Paragraph(override val line: Int, val content: Inline) : Block
        data class Heading(override val line: Int, val level: Int, val content: Inline) : Block
        data class Bullet(override val line: Int, val content: Inline) : Block
        data class Numbered(override val line: Int, val number: Int, val content: Inline) : Block
        data class Check(override val line: Int, val done: Boolean, val content: Inline) : Block

    }

    private val NUMBERED = Regex("""^(\d+)\.\s+(.*)$""")

    /** Every line of [text], in order, one block each. */
    fun parse(text: String): List<Block> = text.lines().mapIndexed { i, raw ->
        val line = raw.trimEnd()
        when {
            // `remove-note-attachments`. **`![](vault:<hex>)` was a block here and is
            // not any more.** A note that still holds one falls through to
            // `Block.Paragraph` and renders as the literal Markdown it is, which is
            // what every other reader of that text would do with it. Stored notes are
            // deliberately not rewritten to strip it: the app does not edit a person's
            // document without being asked, and inert text is the honest outcome.

            // Checklists before bullets: `- [ ] x` is both, and the more specific
            // reading is the one a person meant.
            line.startsWith(UNCHECKED) -> Block.Check(i, false, inline(line.removePrefix(UNCHECKED)))
            line.startsWith(CHECKED_LOWER) ->
                Block.Check(i, true, inline(line.removePrefix(CHECKED_LOWER)))
            line.startsWith(CHECKED_UPPER) ->
                Block.Check(i, true, inline(line.removePrefix(CHECKED_UPPER)))

            // Two levels and no more. `### ` is not a heading, so it stays the
            // characters that were typed rather than becoming a third size nothing
            // in the editor can produce.
            line.startsWith("## ") -> Block.Heading(i, 2, inline(line.removePrefix("## ")))
            line.startsWith("# ") -> Block.Heading(i, 1, inline(line.removePrefix("# ")))

            line.startsWith("- ") -> Block.Bullet(i, inline(line.removePrefix("- ")))
            NUMBERED.matches(line) -> NUMBERED.find(line)!!.let {
                Block.Numbered(i, it.groupValues[1].toInt(), inline(it.groupValues[2]))
            }

            else -> Block.Paragraph(i, inline(line))
        }
    }

    /**
     * [text] with the checkbox on [line] flipped, or [text] unchanged when that line
     * is not one.
     *
     * A tap rewrites one line and nothing else — the whole note is not reflowed, so a
     * checkbox in a long note cannot disturb what is above or below it. With [moveChecked]
     * the line also moves within its own list, in the same returned text, so one undo puts
     * back both the mark and the place.
     */
    fun toggle(text: String, line: Int, moveChecked: Boolean = false): String {
        val lines = text.lines().toMutableList()
        val target = lines.getOrNull(line) ?: return text
        val flipped = when {
            target.startsWith(UNCHECKED) -> CHECKED_LOWER + target.removePrefix(UNCHECKED)
            target.startsWith(CHECKED_LOWER) -> UNCHECKED + target.removePrefix(CHECKED_LOWER)
            target.startsWith(CHECKED_UPPER) -> UNCHECKED + target.removePrefix(CHECKED_UPPER)
            else -> return text
        }
        if (!moveChecked) {
            lines[line] = flipped
            return lines.joinToString("\n")
        }
        // `destination-settings-and-slideshow` D3. A list is a run of consecutive checklist
        // lines; the flipped line goes directly below the run's last unticked line — or to the
        // run's top when none is left — and nothing outside the run moves.
        var start = line
        while (start > 0 && isCheck(lines[start - 1])) start--
        var end = line
        while (end < lines.lastIndex && isCheck(lines[end + 1])) end++
        lines.removeAt(line)
        val lastUnticked = (start until end).lastOrNull { lines[it].startsWith(UNCHECKED) }
        lines.add(lastUnticked?.plus(1) ?: start, flipped)
        return lines.joinToString("\n")
    }

    private fun isCheck(line: String) =
        line.startsWith(UNCHECKED) || line.startsWith(CHECKED_LOWER) || line.startsWith(CHECKED_UPPER)

    private const val UNCHECKED = "- [ ] "
    private const val CHECKED_LOWER = "- [x] "
    private const val CHECKED_UPPER = "- [X] "

    /**
     * The inline scanner: `**bold**`, `*italic*`, `~~strike~~`.
     *
     * **Longest marker first**, which is the whole subtlety. `**` has to be tried
     * before `*` or every bold run reads as two empty italics, and `~~` has to be one
     * token rather than two tildes. An opener with no closer is not a marker at all —
     * it stays in the text, because a person typing `2 * 3` means a multiplication and
     * a renderer that eats the asterisk is a renderer they stop trusting.
     */
    private fun inline(source: String): Inline {
        val out = StringBuilder()
        val spans = mutableListOf<Span>()
        var i = 0
        while (i < source.length) {
            val marker = MARKERS.firstOrNull { source.startsWith(it.first, i) }
            if (marker == null) {
                out.append(source[i]); i++
                continue
            }
            val (token, style) = marker
            val close = source.indexOf(token, i + token.length)
            // No closer, or an empty run: the characters are literal.
            if (close < 0 || close == i + token.length) {
                out.append(source[i]); i++
                continue
            }
            val from = out.length
            out.append(source, i + token.length, close)
            spans += Span(from, out.length, style)
            i = close + token.length
        }
        return Inline(out.toString(), spans)
    }

    /** Longest first — see [inline]. */
    private val MARKERS = listOf(
        "**" to Style.Bold,
        "~~" to Style.Strike,
        "*" to Style.Italic,
    )
}
