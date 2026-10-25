package com.app.vault.ui

import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.toTextFieldBuffer
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the editor does to a note's text — `note-format-bar`, `note-list-continuation`,
 * `note-list-continuation-ime`.
 *
 * **These now run against a `TextFieldBuffer`, which is the thing the editor commits.**
 * The previous version drove `onEdit(old, new)` with two `TextFieldValue`s it built
 * itself, and every case passed while the feature did not work on a real keyboard — the
 * defect was never in the rules, it was in writing a second value after the editor had
 * already committed one. A test that cannot see that write is a test that cannot fail on
 * it, which is why the entry point moved.
 */
class NoteEditsTest {

    /** A buffer holding [initial], with the caret at [caret] and no edit yet made. */
    private fun buffer(initial: String, caret: Int): TextFieldBuffer =
        TextFieldState(initial, TextRange(caret)).toTextFieldBuffer()

    /** [insert] typed at [caret], then the transformation run over the result. */
    private fun typed(initial: String, caret: Int, insert: String): TextFieldBuffer {
        val b = buffer(initial, caret)
        b.replace(caret, caret, insert)
        b.selection = TextRange(caret + insert.length)
        with(NoteEdits.ListContinuation) { b.transformInput() }
        return b
    }

    /** A backspace with the caret at [caret], then the transformation run. */
    private fun backspaced(initial: String, caret: Int): TextFieldBuffer {
        val b = buffer(initial, caret)
        b.replace(caret - 1, caret, "")
        b.selection = TextRange(caret - 1)
        with(NoteEdits.ListContinuation) { b.transformInput() }
        return b
    }

    private fun marked(initial: String, caret: Int, mark: NoteEdits.Mark): TextFieldBuffer {
        val b = buffer(initial, caret)
        NoteEdits.applyMark(b, mark)
        return b
    }

    private fun assertBuffer(text: String, caret: Int, actual: TextFieldBuffer) {
        assertEquals(text, actual.toString())
        assertEquals(TextRange(caret), actual.selection)
    }

    private fun at(text: String, caret: Int) = NoteEdits.activeAt(text, caret)

    // ---- blockMarkerOf ---------------------------------------------------------------

    @Test
    fun everyBlockMarkerIsReadWithItsExactPrefix() {
        assertEquals(NoteEdits.Marker(NoteEdits.Mark.Bullet, "- "), NoteEdits.blockMarkerOf("- milk"))
        assertEquals(
            NoteEdits.Marker(NoteEdits.Mark.Checklist, "- [ ] "),
            NoteEdits.blockMarkerOf("- [ ] milk"),
        )
        assertEquals(
            NoteEdits.Marker(NoteEdits.Mark.Checklist, "- [x] "),
            NoteEdits.blockMarkerOf("- [x] milk"),
        )
        assertEquals(
            NoteEdits.Marker(NoteEdits.Mark.Numbered, "1. ", 1),
            NoteEdits.blockMarkerOf("1. milk"),
        )
        assertEquals(
            NoteEdits.Marker(NoteEdits.Mark.Numbered, "12. ", 12),
            NoteEdits.blockMarkerOf("12. milk"),
        )
        assertEquals(NoteEdits.Marker(NoteEdits.Mark.Heading, "# "), NoteEdits.blockMarkerOf("# Monday"))
        assertEquals(NoteEdits.Marker(NoteEdits.Mark.Heading, "## "), NoteEdits.blockMarkerOf("## Monday"))
    }

    @Test
    fun aMarkerWithoutItsSpaceIsNotAMarker() {
        assertNull(NoteEdits.blockMarkerOf("#tag"))
        assertNull(NoteEdits.blockMarkerOf("-milk"))
        assertNull(NoteEdits.blockMarkerOf("2.5 litres"))
        assertNull(NoteEdits.blockMarkerOf("plain"))
        assertNull(NoteEdits.blockMarkerOf(""))
    }

    // ---- activeAt --------------------------------------------------------------------

    @Test
    fun anEmptyNoteHasNothingInEffect() {
        assertEquals(emptySet<NoteEdits.Mark>(), at("", 0))
    }

    @Test
    fun aChecklistLineIsNotAlsoABulletedOne() {
        assertEquals(setOf(NoteEdits.Mark.Checklist), at("- [ ] milk", 8))
        assertEquals(setOf(NoteEdits.Mark.Checklist), at("- [x] milk", 8))
        assertEquals(setOf(NoteEdits.Mark.Bullet), at("- milk", 4))
    }

    @Test
    fun theOtherTwoBlockMarksReadFromTheLine() {
        assertEquals(setOf(NoteEdits.Mark.Numbered), at("1. milk", 5))
        assertEquals(setOf(NoteEdits.Mark.Numbered), at("12. milk", 6))
        assertEquals(setOf(NoteEdits.Mark.Heading), at("# Monday", 4))
        assertEquals(emptySet<NoteEdits.Mark>(), at("#tag", 3))
    }

    @Test
    fun anInlineMarkIsInEffectBetweenItsMarkersAndNotAfterThem() {
        assertEquals(setOf(NoteEdits.Mark.Bold), at("**loud**", 4))
        assertEquals(emptySet<NoteEdits.Mark>(), at("**loud**", 8))
        assertEquals(setOf(NoteEdits.Mark.Italic), at("*soft*", 3))
        assertEquals(setOf(NoteEdits.Mark.Strike), at("~~gone~~", 4))
    }

    @Test
    fun boldIsConsumedBeforeItalic() {
        assertEquals(setOf(NoteEdits.Mark.Bold), at("**a", 3))
        assertEquals(setOf(NoteEdits.Mark.Bold, NoteEdits.Mark.Italic), at("***a", 4))
    }

    @Test
    fun anUnclosedMarkerDoesNotLeakOntoTheNextLine() {
        assertEquals(emptySet<NoteEdits.Mark>(), at("**loud\nquiet", 10))
        assertEquals(setOf(NoteEdits.Mark.Bullet), at("plain\n- milk", 10))
    }

    @Test
    fun theLineUnderTheCaretIsFoundAtEitherEdge() {
        assertEquals(setOf(NoteEdits.Mark.Bullet), at("plain\n- milk", 6))
        assertEquals(emptySet<NoteEdits.Mark>(), at("\n- milk", 0))
        assertEquals(setOf(NoteEdits.Mark.Bullet), at("\n- milk", 7))
    }

    // ---- continuation ----------------------------------------------------------------

    @Test
    fun aListContinuesAsItself() {
        assertEquals("- ", NoteEdits.continuation("- milk"))
        assertEquals("- [ ] ", NoteEdits.continuation("- [ ] milk"))
        assertEquals("2. ", NoteEdits.continuation("1. milk"))
        assertEquals("8. ", NoteEdits.continuation("7. milk"))
        assertEquals("13. ", NoteEdits.continuation("12. milk"))
    }

    @Test
    fun aCheckedItemContinuesUnchecked() {
        assertEquals("- [ ] ", NoteEdits.continuation("- [x] milk"))
        assertEquals("- [ ] ", NoteEdits.continuation("- [X] milk"))
    }

    @Test
    fun aHeadingAndAParagraphDoNotContinue() {
        assertNull(NoteEdits.continuation("# Monday"))
        assertNull(NoteEdits.continuation("## Monday"))
        assertNull(NoteEdits.continuation("plain"))
        assertNull(NoteEdits.continuation(""))
    }

    // ---- renumber --------------------------------------------------------------------

    @Test
    fun aRunAlreadyInOrderIsLeftAlone() {
        assertEquals("1. a\n2. b\n3. c", NoteEdits.renumber("1. a\n2. b\n3. c", 0))
    }

    @Test
    fun aGapInARunIsClosed() {
        assertEquals("1. a\n2. b\n3. c", NoteEdits.renumber("1. a\n3. b\n4. c", 0))
        assertEquals("1. a\n2. b\n3. c", NoteEdits.renumber("1. a\n3. b\n4. c", 2))
    }

    @Test
    fun aRunKeepsItsOwnFirstNumber() {
        assertEquals("5. a\n6. b\n7. c", NoteEdits.renumber("5. a\n9. b\n2. c", 1))
    }

    @Test
    fun anExplicitStartOverridesTheAnchor() {
        assertEquals("1. a\n2. b", NoteEdits.renumber("3. a\n4. b", 0, from = 1))
    }

    @Test
    fun twoRunsSeparatedByOtherTextAreSeparateLists() {
        assertEquals("1. a\nplain\n5. b\n6. c", NoteEdits.renumber("1. a\nplain\n5. b\n9. c", 2))
        assertEquals("1. a\n\n5. b\n6. c", NoteEdits.renumber("1. a\n\n5. b\n9. c", 3))
    }

    @Test
    fun aLineThatIsNotNumberedRenumbersNothing() {
        assertEquals("plain\n1. a", NoteEdits.renumber("plain\n1. a", 0))
        assertEquals("1. a", NoteEdits.renumber("1. a", 7))
        assertEquals("1. a", NoteEdits.renumber("1. a", -1))
    }

    // ---- caretAcross -----------------------------------------------------------------

    @Test
    fun theCaretHoldsItsCharacterWhenNumbersAheadOfItChangeWidth() {
        val text = "7. a\n8. b\n9. c\n10. d"
        val next = "9. a\n10. b\n11. c\n12. d"
        assertEquals('d', text[19])
        assertEquals('d', next[NoteEdits.caretAcross(text, 19, next, 3)])
    }

    @Test
    fun theCaretHoldsItsCharacterWhenItsOwnMarkerShrinks() {
        assertEquals(7, NoteEdits.caretAcross("12. milk", 8, "1. milk", 0))
    }

    @Test
    fun aCaretPastTheEndIsClamped() {
        assertEquals(4, NoteEdits.caretAcross("- milk", 6, "- ab", 0))
    }

    // ---- lineBreakIn / backspaceIn: the edit is read, never deduced -------------------

    @Test
    fun aBareNewlineIsALineBreak() {
        val b = buffer("1. milk", 7)
        b.replace(7, 7, "\n")
        assertEquals(8, NoteEdits.lineBreakIn(b))
    }

    /**
     * A keyboard that commits its pending word together with the break inserts `"milk\n"`,
     * not `"\n"`. The old rule demanded exactly one character and threw this away.
     */
    @Test
    fun aCommittedWordAndItsBreakAreOneLineBreak() {
        val b = buffer("1. ", 3)
        b.replace(3, 3, "milk\n")
        assertEquals(8, NoteEdits.lineBreakIn(b))
    }

    @Test
    fun anInsertionThatIsNotALineBreakIsNotOne() {
        val plain = buffer("1. mil", 6).apply { replace(6, 6, "k") }
        assertNull(NoteEdits.lineBreakIn(plain))
        val twoLines = buffer("1. milk", 7).apply { replace(7, 7, "\na\nb") }
        assertNull(NoteEdits.lineBreakIn(twoLines))
        val replaced = buffer("1. milk", 7).apply { replace(3, 7, "tea\n") }
        assertNull(NoteEdits.lineBreakIn(replaced))
    }

    @Test
    fun aSingleDeletionIsABackspaceAndALargerOneIsNot() {
        val one = buffer("- milk", 2).apply { replace(1, 2, "") }
        assertEquals(2, NoteEdits.backspaceIn(one))
        val many = buffer("- milk", 6).apply { replace(2, 6, "") }
        assertNull(NoteEdits.backspaceIn(many))
    }

    // ---- the transformation: continuation ---------------------------------------------

    @Test
    fun aBulletedListContinues() {
        assertBuffer("- milk\n- ", 9, typed("- milk", 6, "\n"))
    }

    @Test
    fun aNumberedListCountsUp() {
        assertBuffer("1. milk\n2. ", 11, typed("1. milk", 7, "\n"))
        assertBuffer("1. milk\n2. eggs\n3. ", 19, typed("1. milk\n2. eggs", 15, "\n"))
    }

    @Test
    fun aNumberedListThatStartsElsewhereCountsFromThere() {
        assertBuffer("7. milk\n8. ", 11, typed("7. milk", 7, "\n"))
    }

    @Test
    fun aCheckedItemContinuesAsAnEmptyBox() {
        assertBuffer("- [x] milk\n- [ ] ", 17, typed("- [x] milk", 10, "\n"))
    }

    @Test
    fun aHeadingStartsAParagraph() {
        assertBuffer("# Monday\n", 9, typed("# Monday", 8, "\n"))
    }

    @Test
    fun aPlainParagraphIsUnaffected() {
        assertBuffer("plain\n", 6, typed("plain", 5, "\n"))
    }

    @Test
    fun insertingAnItemRenumbersWhatFollowsIt() {
        assertBuffer("1. a\n2. \n3. b\n4. c", 8, typed("1. a\n2. b\n3. c", 4, "\n"))
    }

    /** The case this whole change exists to make work on a composing keyboard. */
    @Test
    fun aWordCommittedWithTheBreakStillContinues() {
        assertBuffer("1. milk\n2. ", 11, typed("1. ", 3, "milk\n"))
    }

    // ---- the transformation: leaving a list -------------------------------------------

    @Test
    fun aBreakOnAnEmptyItemLeavesTheList() {
        assertBuffer("", 0, typed("- ", 2, "\n"))
        assertBuffer("", 0, typed("- [ ] ", 6, "\n"))
        assertBuffer("", 0, typed("1. ", 3, "\n"))
    }

    @Test
    fun leavingAListMidNoteLeavesAnOrdinaryEmptyLine() {
        assertBuffer("a\n\nb", 2, typed("a\n- [ ] \nb", 8, "\n"))
    }

    @Test
    fun unmarkingAnItemMidRunSplitsItAndRestartsWhatIsBelow() {
        assertBuffer("1. a\n\n1. c", 5, typed("1. a\n2. \n3. c", 8, "\n"))
    }

    // ---- the transformation: backspace -------------------------------------------------

    @Test
    fun backspaceAtTheStartOfAnItemsContentRemovesItsMarker() {
        assertBuffer("milk", 0, backspaced("- milk", 2))
        assertBuffer("milk", 0, backspaced("- [ ] milk", 6))
        assertBuffer("milk", 0, backspaced("1. milk", 3))
    }

    @Test
    fun backspaceElsewhereIsAnOrdinaryBackspace() {
        assertBuffer("- mil", 5, backspaced("- milk", 6))
        assertBuffer(" milk", 0, backspaced("- milk", 1))
    }

    @Test
    fun backspaceUnmarkingANumberedItemRestartsWhatIsBelow() {
        assertBuffer("a\n1. b", 0, backspaced("1. a\n2. b", 3))
    }

    // ---- the transformation declines everything else -----------------------------------

    @Test
    fun pastedTextIsNotContinued() {
        assertBuffer("- milk\nfoo\nbar", 14, typed("- milk", 6, "\nfoo\nbar"))
    }

    @Test
    fun anOrdinaryKeystrokeIsNotAnEdit() {
        assertBuffer("- milk", 6, typed("- mil", 5, "k"))
    }

    @Test
    fun aReplacedSelectionIsNotContinued() {
        val b = buffer("- a\n- b", 7)
        b.replace(2, 6, "x\n")
        b.selection = TextRange(4)
        with(NoteEdits.ListContinuation) { b.transformInput() }
        assertBuffer("- x\nb", 4, b)
    }

    // ---- applyMark: replace, never stack -----------------------------------------------

    @Test
    fun aNumberedLineIsUnmarkedWhateverItsNumber() {
        assertEquals("eggs", marked("2. eggs", 5, NoteEdits.Mark.Numbered).toString())
        assertEquals("eggs", marked("12. eggs", 6, NoteEdits.Mark.Numbered).toString())
    }

    @Test
    fun oneMarkReplacesAnother() {
        assertEquals("- Monday", marked("# Monday", 4, NoteEdits.Mark.Bullet).toString())
        assertEquals("# Monday", marked("- Monday", 4, NoteEdits.Mark.Heading).toString())
        assertEquals("- [ ] Monday", marked("# Monday", 4, NoteEdits.Mark.Checklist).toString())
    }

    @Test
    fun aChecklistLineTakesABulletWithoutKeepingItsBox() {
        assertEquals("- milk", marked("- [ ] milk", 8, NoteEdits.Mark.Bullet).toString())
        assertEquals("- milk", marked("- [x] milk", 8, NoteEdits.Mark.Bullet).toString())
    }

    @Test
    fun theSameMarkTwiceLeavesTheLineAsItWas() {
        assertEquals("milk", marked("- milk", 4, NoteEdits.Mark.Bullet).toString())
        assertEquals("Monday", marked("# Monday", 4, NoteEdits.Mark.Heading).toString())
        assertEquals("Monday", marked("## Monday", 5, NoteEdits.Mark.Heading).toString())
    }

    @Test
    fun markingConsecutiveLinesProducesARunInOrder() {
        val first = marked("a\nb\nc", 0, NoteEdits.Mark.Numbered).toString()
        assertEquals("1. a\nb\nc", first)
        val second = marked(first, 5, NoteEdits.Mark.Numbered).toString()
        assertEquals("1. a\n2. b\nc", second)
        assertEquals("1. a\n2. b\n3. c", marked(second, 10, NoteEdits.Mark.Numbered).toString())
    }

    @Test
    fun aControlsLitStateMatchesWhatPressingItDoes() {
        assertEquals(setOf(NoteEdits.Mark.Numbered), at("12. milk", 6))
        assertEquals("milk", marked("12. milk", 6, NoteEdits.Mark.Numbered).toString())
    }

    @Test
    fun unmarkingFromTheBarRestartsTheRunBelow() {
        assertEquals("1. a\nb\n1. c", marked("1. a\n2. b\n3. c", 5, NoteEdits.Mark.Numbered).toString())
    }

    @Test
    fun theCaretFollowsItsCharacterAcrossAMarkerChange() {
        assertBuffer("eggs", 0, marked("2. eggs", 3, NoteEdits.Mark.Numbered))
    }

    // ---- wrap ---------------------------------------------------------------------------

    @Test
    fun wrapPutsAnEmptyPairAtTheCaretAndTheCaretBetweenThem() {
        val empty = buffer("", 0)
        NoteEdits.wrap(empty, "**")
        assertBuffer("****", 2, empty)

        val chosen = TextFieldState("loud", TextRange(0, 4)).toTextFieldBuffer()
        NoteEdits.wrap(chosen, "**")
        assertBuffer("**loud**", 8, chosen)
    }
}
