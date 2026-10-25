package com.app.vault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.app.vault.R
import com.app.vault.ui.theme.Radius
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors

/**
 * The formatting toolbar — `notes-as-documents`, rebuilt by `note-format-bar`.
 *
 * **It sits on the keyboard and exists only while the text has focus.** A formatting
 * bar standing over a note nobody is editing is chrome, not a feature.
 *
 * ***It used to be seven typographic marks and the word "Attach".*** Two vocabularies
 * in one row, on a strip the same colour as the page with no line between them, which
 * made the bar read as the document's last line rather than as a control surface.
 *
 * **B, I and S stay set in the weight and slant they apply.** That is the one place
 * type beats a glyph, it is what Docs and Word have printed for thirty years, and a
 * screen reader still hears "Bold" rather than "B". Everything else is a mark that
 * looks like its result — a bulleted list looks like a bulleted list — because `✓`,
 * `•`, `1.` and `H` describe the *syntax* being written and not the thing it makes.
 *
 * **A control says whether it is on.** This is the change that matters: the bar could
 * turn a list on and off but never said which line you were standing on, so the only
 * way to find out what a tap would undo was to tap it. The active ones fill with
 * [VaultColors.Secondary] — the structural accent, which is what selection is — and
 * announce `selected` to a screen reader, so the state is not carried by colour alone
 * (§6.4). Apple Notes and Docs both draw exactly this pill.
 *
 * **Two groups, hairline-separated**: what a line *is*, and what a span *looks like*.
 * They were separated by an identical 4dp gap before, which said they were all the
 * same kind of thing.
 *
 * ***Attach is not here.*** It was, as the word "Attach", last in the row and past the
 * right-hand edge of a 360dp phone — the one control on the bar that inserts something
 * rather than marking text, and the one nobody could find. It is a top-bar action now,
 * beside undo and redo, where it is always on screen. Seven controls are what is left,
 * and they are all the same kind of act.
 *
 * **Every control is on screen, at every display size.** Seven 48dp targets and one
 * hairline are 345dp against a 360dp floor, so an ordinary phone draws one row. It
 * used to scroll, and scrolling is the wrong answer to a narrow width: it hides a
 * control from the person who raised their display size *because they need things
 * bigger*, and a fade at the edge only tells them something is missing. It wraps to a
 * second row instead. Nothing is hidden and no target shrinks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FormatBar(
    /**
     * The field's own state — read for what the controls report, and written through
     * [TextFieldState.edit] when one is pressed.
     *
     * ***It used to take a value and a function returning a new one.*** That put a second
     * write on the field after the editor had committed, which is the defect
     * `note-list-continuation-ime` exists to remove: an input method keeps its own mirror
     * of the text and overwrites a value it never issued. A tap survived it only because
     * a tap happens when no keyboard edit is in flight.
     */
    state: TextFieldState,
    modifier: Modifier = Modifier,
) {
    // Keyed on the text *and* the caret: moving the caret without typing is most of
    // how this changes, and a set recomputed per frame over a 100KB note is a scan
    // per frame.
    val text = state.text.toString()
    val caret = state.selection.start
    val active = remember(text, caret) { NoteEdits.activeAt(text, caret) }

    Column(modifier.fillMaxWidth()) {
        // The line that makes it a bar. Without it the strip is a paragraph-coloured
        // block under a paragraph, and §7.1's containers are the only other thing
        // drawing edges in this app.
        HorizontalDivider(thickness = Hairline, color = VaultColors.OutlineVariant)

        // **It wraps, and it never scrolls.** Seven 48dp targets and one hairline are
        // 345dp against a 360dp floor, so on an ordinary phone this is one row and
        // looks like one. Raise the system display size and the effective width
        // shrinks: a scrolling row answers that by putting a control off the edge and
        // fading it, which is a control the user cannot see on the setting they chose
        // *because* they need things bigger. This takes a second row instead. Nothing
        // is hidden at any display size, and no target goes under the 48dp floor.
        FlowRow(
            Modifier
                .fillMaxWidth()
                .background(VaultColors.SurfaceContainerHigh),
            verticalArrangement = Arrangement.Top,
        ) {
            Control(NoteEdits.Mark.Checklist, stringResource(R.string.format_checklist), active) {
                state.edit { NoteEdits.applyMark(this, NoteEdits.Mark.Checklist) }
            }
            Control(NoteEdits.Mark.Bullet, stringResource(R.string.format_bullet), active) {
                state.edit { NoteEdits.applyMark(this, NoteEdits.Mark.Bullet) }
            }
            Control(NoteEdits.Mark.Numbered, stringResource(R.string.format_numbered), active) {
                state.edit { NoteEdits.applyMark(this, NoteEdits.Mark.Numbered) }
            }
            Control(NoteEdits.Mark.Heading, stringResource(R.string.format_heading), active) {
                state.edit { NoteEdits.applyMark(this, NoteEdits.Mark.Heading) }
            }

            GroupBreak()

            Control(NoteEdits.Mark.Bold, stringResource(R.string.format_bold), active) {
                state.edit { NoteEdits.wrap(this, "**") }
            }
            Control(NoteEdits.Mark.Italic, stringResource(R.string.format_italic), active) {
                state.edit { NoteEdits.wrap(this, "*") }
            }
            Control(NoteEdits.Mark.Strike, stringResource(R.string.format_strike), active) {
                state.edit { NoteEdits.wrap(this, "~~") }
            }
        }
    }
}

/** The hairline between two groups of controls. Decorative — [VaultColors.OutlineVariant]'s role. */
@Composable
private fun GroupBreak() {
    // Centred inside a full-height box rather than laid out on its own: a wrapping
    // row aligns its items to the top of the line, and a 24dp hairline aligned to
    // the top of a 48dp row hangs off the ceiling instead of dividing anything.
    Box(
        Modifier.height(Spacing.touchTarget).padding(horizontal = Spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        VerticalDivider(
            modifier = Modifier.height(Spacing.xl),
            thickness = Hairline,
            color = VaultColors.OutlineVariant,
        )
    }
}

/**
 * One control, drawn as its own result and filled while it is in effect.
 *
 * [mark] null is the one control with no state to report — attaching is an act, not a
 * property of the caret's line.
 */
@Composable
private fun Control(
    mark: NoteEdits.Mark?,
    name: String,
    active: Set<NoteEdits.Mark>,
    onPress: () -> Unit,
) {
    val on = mark != null && mark in active
    Box(
        Modifier
            .sizeIn(minWidth = Spacing.touchTarget, minHeight = Spacing.touchTarget)
            .height(Spacing.touchTarget)
            .padding(Spacing.xs)
            .clip(RoundedCornerShape(Radius.control))
            .then(
                if (on) Modifier.background(VaultColors.Secondary) else Modifier,
            )
            // **The ripple is back.** Every control here used to pass
            // `indication = null`, so a tap produced no feedback at all — and the
            // change it makes is at the caret, which is often not on screen. A
            // control that answers nothing reads as a broken one.
            .clickable(onClick = onPress)
            .semantics {
                contentDescription = name
                selected = on
            },
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (on) VaultColors.OnSecondary else VaultColors.OnSurface
        when (mark) {
            NoteEdits.Mark.Bold -> Text(bold("B"), style = MarkType, color = tint)
            NoteEdits.Mark.Italic -> Text(italic("I"), style = MarkType, color = tint)
            NoteEdits.Mark.Strike -> Text(struck("S"), style = MarkType, color = tint)
            else -> Icon(
                painter = painterResource(
                    when (mark) {
                        NoteEdits.Mark.Checklist -> R.drawable.ic_checklist
                        NoteEdits.Mark.Bullet -> R.drawable.ic_format_list_bulleted
                        NoteEdits.Mark.Numbered -> R.drawable.ic_format_list_numbered
                        NoteEdits.Mark.Heading -> R.drawable.ic_title
                        else -> R.drawable.ic_image
                    },
                ),
                // The Box names the control; the glyph would say it twice.
                contentDescription = null,
                tint = tint,
            )
        }
    }
}

private fun bold(s: String) = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.W700)) { append(s) }
}

private fun italic(s: String) = buildAnnotatedString {
    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s) }
}

private fun struck(s: String) = buildAnnotatedString {
    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(s) }
}

/** The three letter marks, at the size the glyphs beside them are drawn. */
private val MarkType
    @Composable get() = MaterialTheme.typography.titleMedium

/** Below the 4dp scale on purpose: a hairline is a line, not a step of spacing. */
private val Hairline = 1.dp




/**
 * Everything the editor does to a note's text — `notes-as-documents`, `note-format-bar`,
 * `note-list-continuation`, rebuilt by `note-list-continuation-ime`.
 *
 * Pure and separate from the bar, so all of it is checkable on the JVM: an editor that
 * puts the caret in the wrong place is the kind of defect nobody notices in a screenshot
 * and everybody notices in use, and half of what follows is caret arithmetic.
 *
 * ***It used to diff two snapshots, and that is what broke it on a real keyboard.***
 * `note-list-continuation` put an `onEdit(old, new)` in the field's `onValueChange`: it
 * compared two `TextFieldValue`s, worked out whether the user had pressed Enter, and
 * returned a **different** value than the one it was given. An input method keeps its own
 * mirror of the field's contents, so a value it never issued is a value it overwrites on
 * its next update. On a Samsung keyboard the device log shows the marker written and then
 * destroyed 2ms later; on the emulator's Gboard no such update arrives, so three test
 * suites and an emulator run all reported a feature that did not work in the hand once.
 *
 * **[ListContinuation] is the fix, and it is a deletion.** An `InputTransformation`
 * mutates the buffer that is *about to be committed* — there is one value, and nothing
 * written afterwards for a keyboard to disagree with. The buffer also *states* the edit
 * through `changes`, so the whole apparatus of length deltas and "does undoing this
 * reproduce the old text" confirmations is gone rather than corrected: it only ever
 * existed to guess at what the editor already knew.
 */
object NoteEdits {

    /** A control on the bar, and — for all but attach — a state the caret can be in. */
    enum class Mark { Checklist, Bullet, Numbered, Heading, Bold, Italic, Strike }

    /**
     * A line's block marker: which kind it is, the exact characters it occupies, and —
     * for a numbered line — the number it carries.
     *
     * [prefix] rather than a length, because the four kinds are not the same width and
     * a numbered one is not even a fixed width: `9. ` and `10. ` differ, which is the
     * whole reason [caretAcross] exists.
     */
    data class Marker(val mark: Mark, val prefix: String, val number: Int = 0)

    /**
     * The block marker on [line], or null where there is none.
     *
     * **One place, and everything else asks it.** Checklists before bullets, because
     * `- [ ] ` starts with a bullet's marker and the more specific reading is the one a
     * person meant. `## ` before `# ` for the same reason. A marker is a marker only with
     * its trailing space: `#tag` is a word and `2.5` is a number.
     */
    fun blockMarkerOf(line: String): Marker? = when {
        line.startsWith(UNCHECKED) -> Marker(Mark.Checklist, UNCHECKED)
        line.startsWith(CHECKED_LOWER) -> Marker(Mark.Checklist, CHECKED_LOWER)
        line.startsWith(CHECKED_UPPER) -> Marker(Mark.Checklist, CHECKED_UPPER)
        line.startsWith("## ") -> Marker(Mark.Heading, "## ")
        line.startsWith("# ") -> Marker(Mark.Heading, "# ")
        line.startsWith("- ") -> Marker(Mark.Bullet, "- ")
        else -> NUMBERED.find(line)?.let {
            Marker(Mark.Numbered, it.value, it.groupValues[1].toInt())
        }
    }

    /**
     * What is in effect at [caret] in [text] — `note-format-bar`.
     *
     * **It reads and never writes**, which is the whole of its risk: it is the answer to
     * "am I in a list?", and a wrong answer only ever mislabels a control.
     *
     * The block mark comes from [blockMarkerOf] on the caret's own line, and there is at
     * most one. The inline marks come from counting markers from the start of the line to
     * the caret: an odd count is an opening not yet closed, which is what being *inside*
     * bold means. `**` is consumed before a lone `*`, exactly as [wrap] writes them.
     * Line-scoped rather than document-scoped because a paragraph break ends a span in
     * every Markdown renderer, `NoteMarkdown` included.
     */
    fun activeAt(text: String, caret: Int): Set<Mark> {
        val at = caret.coerceIn(0, text.length)
        val start = lineStartOf(text, at)
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val line = text.substring(start, end)

        val marks = mutableSetOf<Mark>()
        blockMarkerOf(line)?.let { marks += it.mark }

        var bold = false
        var italic = false
        var strike = false
        var i = 0
        val stop = (at - start).coerceIn(0, line.length)
        while (i < stop) {
            when {
                line.startsWith("**", i) -> { bold = !bold; i += 2 }
                line.startsWith("~~", i) -> { strike = !strike; i += 2 }
                line[i] == '*' -> { italic = !italic; i++ }
                else -> i++
            }
        }
        if (bold) marks += Mark.Bold
        if (italic) marks += Mark.Italic
        if (strike) marks += Mark.Strike
        return marks
    }

    /**
     * The marker the next line starts with, given the line a break was entered on, or
     * null where the line does not continue.
     *
     * **A checked item continues unchecked.** Pressing return in a checklist is how a
     * person adds a task, and a task born complete is never what was meant.
     *
     * **A heading does not continue**: a heading names what is under it, and what is
     * under it is a paragraph.
     */
    fun continuation(line: String): String? {
        val marker = blockMarkerOf(line) ?: return null
        return when (marker.mark) {
            Mark.Checklist -> UNCHECKED
            Mark.Bullet -> BULLET
            Mark.Numbered -> "${marker.number + 1}. "
            else -> null
        }
    }

    /**
     * [text] with the contiguous numbered run containing [line] rewritten in order.
     *
     * A run is a maximal block of consecutive `<n>. ` lines. A blank line or a paragraph
     * ends it, which is what makes two lists on one page two lists rather than one with a
     * gap in it.
     *
     * [from] null anchors the run to its own first number, so a list deliberately begun at
     * 5 stays begun at 5. **The anchor has to let go at a split**, which is the one case
     * the call site knows about and this function cannot: unmarking a line inside a run
     * leaves the lines below it a run beginning somewhere new, and anchoring that to the
     * number it inherited reproduces the defect this exists to remove. Those call sites
     * pass `1`.
     *
     * A [line] that is not numbered, or out of range, returns [text] untouched — which is
     * what makes "renumber below the line I just unmarked" safe to call at a list's foot.
     */
    fun renumber(text: String, line: Int, from: Int? = null): String {
        val lines = text.lines()
        if (line !in lines.indices) return text
        if (blockMarkerOf(lines[line])?.mark != Mark.Numbered) return text

        var first = line
        while (first > 0 && blockMarkerOf(lines[first - 1])?.mark == Mark.Numbered) first--
        var last = line
        while (last < lines.size - 1 && blockMarkerOf(lines[last + 1])?.mark == Mark.Numbered) last++

        val start = from ?: blockMarkerOf(lines[first])!!.number
        val out = lines.toMutableList()
        for (i in first..last) {
            val marker = blockMarkerOf(out[i])!!
            out[i] = "${start + i - first}. " + out[i].substring(marker.prefix.length)
        }
        return out.joinToString("\n")
    }

    /**
     * A caret carried across a rewrite, as a line and a column rather than an offset.
     *
     * **An absolute position is wrong the moment a number changes width.** Renumbering
     * `9.` to `10.` anywhere above the caret shifts every later index by one, and doing it
     * to the caret's *own* line shifts the caret within it. So the position is taken apart
     * into the line it is on and how far into that line it sits, and put back together
     * against [next].
     *
     * The column is measured from the end of the line's marker where the caret is past it,
     * so a caret on the `m` of `9. milk` is still on the `m` of `10. milk`.
     */
    fun caretAcross(text: String, caret: Int, next: String, nextLine: Int): Int {
        val start = lineStartOf(text, caret.coerceIn(0, text.length))
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val was = blockMarkerOf(text.substring(start, end))?.prefix?.length ?: 0
        val lines = next.lines()
        val index = nextLine.coerceIn(0, lines.size - 1)
        val now = blockMarkerOf(lines[index])?.prefix?.length ?: 0

        val column = caret - start
        val moved = if (column >= was) now + (column - was) else column
        return offsetOf(next, index, moved)
    }

    /**
     * Where a line break was entered, or null when this edit is not one.
     *
     * **It reads the edit rather than deducing it.** A break is one change, replacing
     * nothing, whose inserted text ends in a newline and holds no other — which is the
     * rule the `encrypted-notes` spec states and the reason a keyboard that commits a
     * pending word *together with* the break is accepted. Samsung's does exactly that:
     * the insertion is `"coffee\n"`, not `"\n"`, and the old snapshot arithmetic threw it
     * away as "not exactly one character".
     *
     * The returned offset is just past the newline — the start of the new line.
     */
    @OptIn(ExperimentalFoundationApi::class)
    fun lineBreakIn(buffer: TextFieldBuffer): Int? {
        val changes = buffer.changes
        if (changes.changeCount != 1) return null
        if (!changes.getOriginalRange(0).collapsed) return null
        val range = changes.getRange(0)
        if (range.collapsed) return null
        val inserted = buffer.asCharSequence().subSequence(range.min, range.max).toString()
        if (!inserted.endsWith("\n")) return null
        if (inserted.indexOf('\n') != inserted.length - 1) return null
        return range.max
    }

    /**
     * The caret as it stood *before* a backspace, or null when this edit is not one.
     *
     * One change, inserting nothing, removing exactly one character. A selection deleted
     * in one stroke removes more and is left alone.
     */
    @OptIn(ExperimentalFoundationApi::class)
    fun backspaceIn(buffer: TextFieldBuffer): Int? {
        val changes = buffer.changes
        if (changes.changeCount != 1) return null
        if (!changes.getRange(0).collapsed) return null
        val original = changes.getOriginalRange(0)
        if (original.length != 1) return null
        return original.max
    }

    /**
     * The list behaviour, as an edit to the buffer the editor is about to commit.
     *
     * **This is the whole of the fix.** Everything below was already written and already
     * tested; what changed is *where it runs*. Inside the transformation there is one
     * value — the buffer — and the editor commits what this leaves in it, so there is no
     * second write for an input method to overwrite and no snapshot to reverse-engineer.
     */
    object ListContinuation : InputTransformation {
        override fun TextFieldBuffer.transformInput() {
            val broke = lineBreakIn(this)
            if (broke != null) {
                continueList(this, broke)
                return
            }
            backspaceIn(this)?.let { unmark(this, it) }
        }
    }

    /** Continue the list the break was entered in, or leave it when the item was empty. */
    private fun continueList(buffer: TextFieldBuffer, at: Int) {
        val text = buffer.toString()
        val lineStart = if (at < 2) 0 else text.lastIndexOf('\n', at - 2) + 1
        val line = text.substring(lineStart, at - 1)
        val marker = blockMarkerOf(line) ?: return
        if (marker.mark == Mark.Heading) return
        val index = lineIndexOf(text, lineStart)

        // An item with nothing in it: the break leaves the list rather than extending it.
        // The newline goes with the marker — one keypress, one result, and the caret stays
        // on the line it was already on.
        if (line.substring(marker.prefix.length).isBlank()) {
            val stripped = text.replaceRange(lineStart, at, "")
            val out = if (marker.mark == Mark.Numbered) {
                renumber(stripped, index + 1, from = 1)
            } else {
                stripped
            }
            buffer.write(out, offsetOf(out, index, 0))
            return
        }

        val next = continuation(line) ?: return
        val extended = text.replaceRange(at, at, next)
        val out = if (marker.mark == Mark.Numbered) renumber(extended, index + 1) else extended
        val column = blockMarkerOf(out.lines()[index + 1])?.prefix?.length ?: next.length
        buffer.write(out, offsetOf(out, index + 1, column))
    }

    /**
     * A backspace that landed at the end of a marker takes the whole marker.
     *
     * The other route out of a list, and the one reached for by a person who marked a line
     * by accident. Worked from the text as it stood *before* the keypress, which is what
     * `originalText` is for.
     */
    private fun unmark(buffer: TextFieldBuffer, caretBefore: Int) {
        val before = buffer.originalText.toString()
        val start = if (caretBefore == 0) 0 else before.lastIndexOf('\n', caretBefore - 1) + 1
        val end = before.indexOf('\n', start).let { if (it < 0) before.length else it }
        val marker = blockMarkerOf(before.substring(start, end)) ?: return
        if (caretBefore != start + marker.prefix.length) return

        val index = lineIndexOf(before, start)
        val stripped = before.replaceRange(start, start + marker.prefix.length, "")
        val out = if (marker.mark == Mark.Numbered) {
            renumber(stripped, index + 1, from = 1)
        } else {
            stripped
        }
        buffer.write(out, offsetOf(out, index, 0))
    }

    /**
     * [mark] on the caret's line: removed when it is already there, and **replacing**
     * whatever other block marker is.
     *
     * ***It used to compare the line against the string it was about to write.*** That
     * worked for `- ` and `# ` and never for a numbered line carrying any number but 1: on
     * `2. eggs` the control lit up and then stacked, giving `1. 2. eggs`, a line no control
     * on this bar could undo. Marks of different kinds stacked the same way.
     *
     * A numbered mark is stamped one greater than the numbered line directly above, so
     * marking consecutive lines in turn produces a run in order rather than 1, 1, 1.
     *
     * **It writes through the buffer now**, on the same commit path [ListContinuation]
     * uses. It used to hand back a competing value too, and survived only because a tap
     * happens when no input-method edit is in flight — luck rather than design.
     */
    fun applyMark(buffer: TextFieldBuffer, mark: Mark) {
        val text = buffer.toString()
        val caret = buffer.selection.start.coerceIn(0, text.length)
        val start = lineStartOf(text, caret)
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val index = lineIndexOf(text, start)
        val existing = blockMarkerOf(text.substring(start, end))

        val removing = existing != null && existing.mark == mark
        val written = if (removing) "" else markerFor(mark, text, index)
        if (!removing && written.isEmpty()) return

        val replaced = text.replaceRange(start, start + (existing?.prefix?.length ?: 0), written)
        val nowNumbered = !removing && mark == Mark.Numbered
        val out = when {
            nowNumbered -> renumber(replaced, index)
            existing?.mark == Mark.Numbered -> renumber(replaced, index + 1, from = 1)
            else -> replaced
        }
        buffer.write(out, caretAcross(text, caret, out, index))
    }

    /**
     * [marker] around the selection, or an empty pair at the caret with the caret left
     * between them — which is what a person means when they press Bold before typing
     * rather than after.
     */
    fun wrap(buffer: TextFieldBuffer, marker: String) {
        val text = buffer.toString()
        val from = minOf(buffer.selection.start, buffer.selection.end).coerceIn(0, text.length)
        val to = maxOf(buffer.selection.start, buffer.selection.end).coerceIn(0, text.length)
        val chosen = text.substring(from, to)
        val next = text.replaceRange(from, to, "$marker$chosen$marker")
        val caret = if (chosen.isEmpty()) from + marker.length else to + marker.length * 2
        buffer.write(next, caret)
    }

    /**
     * [next] into the buffer as the **smallest** edit that produces it, and [caret] after.
     *
     * A whole-text replace would work and is wrong: it tells the editor every character
     * changed, which throws away the composing region the user is mid-word in. Matching
     * the common prefix and suffix keeps the edit to the span that actually moved — a
     * marker, or the numbers in one run.
     */
    private fun TextFieldBuffer.write(next: String, caret: Int) {
        val current = toString()
        if (current != next) {
            val limit = minOf(current.length, next.length)
            var head = 0
            while (head < limit && current[head] == next[head]) head++
            var tail = 0
            while (tail < limit - head &&
                current[current.length - 1 - tail] == next[next.length - 1 - tail]
            ) {
                tail++
            }
            replace(head, current.length - tail, next.substring(head, next.length - tail))
        }
        selection = TextRange(caret.coerceIn(0, next.length))
    }

    /** The characters [mark] writes. Numbered counts from the line above; inline marks write nothing. */
    private fun markerFor(mark: Mark, text: String, index: Int): String = when (mark) {
        Mark.Checklist -> UNCHECKED
        Mark.Bullet -> BULLET
        Mark.Heading -> HEADING
        Mark.Numbered -> "${numberAbove(text, index) + 1}. "
        else -> ""
    }

    /** The number on the numbered line directly above [index], or 0 where there is none. */
    private fun numberAbove(text: String, index: Int): Int {
        if (index < 1) return 0
        val above = text.lines().getOrNull(index - 1) ?: return 0
        return blockMarkerOf(above)?.takeIf { it.mark == Mark.Numbered }?.number ?: 0
    }

    /**
     * The start of the line [caret] is on.
     *
     * `lastIndexOf` from the character *before* the caret: with the caret sitting just
     * after a newline the line is the new one, which is where typing goes. -1 for "no
     * newline behind us" becomes 0, the first line's start.
     */
    private fun lineStartOf(text: String, caret: Int): Int =
        if (caret == 0) 0 else text.lastIndexOf('\n', caret - 1) + 1

    /** Which line [offset] falls on. Walked rather than `take`n — a note runs to a megabyte. */
    private fun lineIndexOf(text: String, offset: Int): Int {
        var n = 0
        for (i in 0 until offset.coerceIn(0, text.length)) if (text[i] == '\n') n++
        return n
    }

    /** [column] of [line] as an offset into [text], both clamped to what exists. */
    private fun offsetOf(text: String, line: Int, column: Int): Int {
        val lines = text.lines()
        val index = line.coerceIn(0, lines.size - 1)
        var offset = 0
        for (n in 0 until index) offset += lines[n].length + 1
        return offset + column.coerceIn(0, lines[index].length)
    }

    /** A numbered line, as written here and as a person may have renumbered it by hand. */
    private val NUMBERED = Regex("""^(\d+)\. """)

    private const val UNCHECKED = "- [ ] "
    private const val CHECKED_LOWER = "- [x] "
    private const val CHECKED_UPPER = "- [X] "
    private const val BULLET = "- "
    private const val HEADING = "# "
}
