package com.app.vault

import com.app.vault.NoteMarkdown.Block
import com.app.vault.NoteMarkdown.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The note's formatting — `notes-as-documents`.
 *
 * Two claims are worth the whole file. **The set is closed**: a table, a code fence,
 * a link and a block quote each render as the characters that were typed, because the
 * export has to stay readable and a construct that reads as noise there does not ship.
 * And **an unmatched marker is not a marker**: `2 * 3` is a multiplication, and a
 * renderer that eats the asterisk is one people stop trusting.
 */
class NoteMarkdownTest {

    private fun one(text: String) = NoteMarkdown.parse(text).single()

    // --- blocks ---------------------------------------------------------------

    @Test fun headingsHaveTwoLevelsAndNoThird() {
        assertEquals(1, (one("# Friday") as Block.Heading).level)
        assertEquals(2, (one("## Later") as Block.Heading).level)
        // `### ` is not a heading, so it stays what was typed.
        assertEquals("### Deeper", (one("### Deeper") as Block.Paragraph).content.text)
    }

    @Test fun bulletsAndNumbersAreTheirOwnBlocks() {
        assertEquals("Milk", (one("- Milk") as Block.Bullet).content.text)
        val numbered = one("2. Bread") as Block.Numbered
        assertEquals(2, numbered.number)
        assertEquals("Bread", numbered.content.text)
    }

    /** A checklist is read before a bullet: `- [ ] x` is both, and the specific wins. */
    @Test fun aChecklistIsNotABullet() {
        val open = one("- [ ] Milk") as Block.Check
        assertEquals(false, open.done)
        assertEquals("Milk", open.content.text)

        assertEquals(true, (one("- [x] Bread") as Block.Check).done)
        assertEquals(true, (one("- [X] Bread") as Block.Check).done)
    }

    /**
     * ***An old attachment reference is ordinary text now — `remove-note-attachments`.***
     *
     * `![](vault:<hex>)` was its own block and resolved to an image the note owned. Notes
     * carry no attachments, so it is no longer a syntax this parser knows: a note whose
     * body still holds one parses it as a paragraph and renders it as the literal Markdown
     * it is, which is what any other reader of that text would do.
     *
     * Stored notes are deliberately **not** rewritten to strip these. The app does not edit
     * a person's document without being asked, and inert visible text is the honest outcome
     * — they can delete it if they care.
     */
    @Test fun anOldAttachmentReferenceIsOrdinaryText() {
        val block = one("![](vault:9F3C1B)") as Block.Paragraph
        assertEquals("![](vault:9F3C1B)", block.content.text)

        // And it does not become one when it sits among other lines.
        val blocks = NoteMarkdown.parse("a\n![](vault:9F3C1B)\nb")
        assertEquals(3, blocks.size)
        assertEquals(3, blocks.filterIsInstance<Block.Paragraph>().size)
    }

    @Test fun everyLineKeepsItsIndex() {
        val blocks = NoteMarkdown.parse("# One\n- Two\nthree")
        assertEquals(listOf(0, 1, 2), blocks.map { it.line })
    }

    // --- the set is closed ----------------------------------------------------

    @Test fun whatIsOutsideTheSetRendersAsItselfAndNotAsAnError() {
        listOf(
            "| a | b |",
            "```kotlin",
            "[label](https://example.com)",
            "> quoted",
            "    indented code",
        ).forEach { line ->
            val block = one(line)
            assertTrue("$line became $block", block is Block.Paragraph)
            assertEquals(line.trimEnd(), (block as Block.Paragraph).content.text)
            assertTrue("$line gained a style", block.content.spans.isEmpty())
        }
    }

    // --- inline ---------------------------------------------------------------

    @Test fun theThreeInlineStylesApply() {
        val bold = (one("say **this** now") as Block.Paragraph).content
        assertEquals("say this now", bold.text)
        assertEquals(listOf(NoteMarkdown.Span(4, 8, Style.Bold)), bold.spans)

        val italic = (one("*soft*") as Block.Paragraph).content
        assertEquals("soft", italic.text)
        assertEquals(Style.Italic, italic.spans.single().style)

        val strike = (one("~~gone~~") as Block.Paragraph).content
        assertEquals("gone", strike.text)
        assertEquals(Style.Strike, strike.spans.single().style)
    }

    /** `**` is tried before `*`, or every bold run reads as two empty italics. */
    @Test fun boldIsNotTwoItalics() {
        val content = (one("**loud**") as Block.Paragraph).content
        assertEquals("loud", content.text)
        assertEquals(1, content.spans.size)
        assertEquals(Style.Bold, content.spans.single().style)
    }

    @Test fun anUnmatchedMarkerStaysInTheText() {
        listOf("2 * 3 = 6", "a ** b", "half ~~ open", "*") .forEach {
            val content = (one(it) as Block.Paragraph).content
            assertEquals(it, content.text)
            assertTrue("$it gained a style", content.spans.isEmpty())
        }
    }

    @Test fun anEmptyRunIsNotAStyle() {
        val content = (one("****") as Block.Paragraph).content
        assertEquals("****", content.text)
        assertTrue(content.spans.isEmpty())
    }

    @Test fun stylesInsideABlockStillApply() {
        val check = one("- [ ] buy **milk**") as Block.Check
        assertEquals("buy milk", check.content.text)
        assertEquals(Style.Bold, check.content.spans.single().style)
    }

    // --- toggling -------------------------------------------------------------

    @Test fun togglingRewritesOneLineAndNoOther() {
        val text = "# List\n- [ ] Milk\n- [ ] Bread\n- [x] Coffee"

        val done = NoteMarkdown.toggle(text, 2)
        assertEquals("# List\n- [ ] Milk\n- [x] Bread\n- [x] Coffee", done)

        val undone = NoteMarkdown.toggle(done, 3)
        assertEquals("# List\n- [ ] Milk\n- [x] Bread\n- [ ] Coffee", undone)
    }

    @Test fun togglingSomethingThatIsNotACheckboxChangesNothing() {
        val text = "# List\nplain"
        assertEquals(text, NoteMarkdown.toggle(text, 0))
        assertEquals(text, NoteMarkdown.toggle(text, 1))
        assertEquals(text, NoteMarkdown.toggle(text, 99))
    }
    // --- `destination-settings-and-slideshow` D3: moving checked items ----------

    @Test
    fun tickingMovesTheItemBelowTheUntickedOnesOfItsList() {
        val text = "- [ ] a\n- [ ] b\n- [ ] c"
        assertEquals("- [ ] b\n- [ ] c\n- [x] a", NoteMarkdown.toggle(text, 0, moveChecked = true))
    }

    @Test
    fun tickingLandsAboveEarlierTickedItems() {
        val text = "- [ ] a\n- [ ] b\n- [x] c"
        assertEquals("- [ ] b\n- [x] a\n- [x] c", NoteMarkdown.toggle(text, 0, moveChecked = true))
    }

    @Test
    fun untickingMovesTheItemBelowTheLastUntickedOne() {
        val text = "- [ ] a\n- [ ] b\n- [x] c\n- [x] d"
        assertEquals("- [ ] a\n- [ ] b\n- [ ] d\n- [x] c", NoteMarkdown.toggle(text, 3, moveChecked = true))
    }

    @Test
    fun untickingWithNothingUntickedGoesToTheTopOfTheList() {
        val text = "intro\n- [x] a\n- [X] b"
        assertEquals("intro\n- [ ] b\n- [x] a", NoteMarkdown.toggle(text, 2, moveChecked = true))
    }

    @Test
    fun anotherListAndAParagraphAreUntouched() {
        val text = "- [ ] a\n- [ ] b\nbetween\n- [ ] c\n- [ ] d"
        assertEquals(
            "- [ ] b\n- [x] a\nbetween\n- [ ] c\n- [ ] d",
            NoteMarkdown.toggle(text, 0, moveChecked = true),
        )
    }

    @Test
    fun offLeavesEveryLineWhereItWas() {
        val text = "- [ ] a\n- [ ] b\n- [ ] c"
        assertEquals("- [x] a\n- [ ] b\n- [ ] c", NoteMarkdown.toggle(text, 0, moveChecked = false))
        assertEquals("- [x] a\n- [ ] b\n- [ ] c", NoteMarkdown.toggle(text, 0))
    }
}
