package com.app.vault

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement

/**
 * The kit check, walked from a test — `recovery-practice` D5. Shared by every suite that passes
 * a kit shown at creation, so each reads the words off the screen the way a user copies them,
 * and answers the three positions the check asks for.
 */
internal object KitCheckWalk {

    private val numbered = Regex("""^(\d{1,2})\. ([a-z]+)$""")
    /** "Word 11", "Wort 11", "第 11 个单词" — the field's own label, in any of the nine languages. */
    private val wordLabel = Regex("""^\D*?(\d{1,2})\D*$""")

    /** The 24 words as the kit screen draws them, "1. abandon" to "24. zoo". */
    fun wordsOnScreen(compose: ComposeTestRule): List<String> {
        val found = sortedMapOf<Int, String>()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { it.config[SemanticsProperties.Text] }
            .forEach { text -> numbered.matchEntire(text.text)?.let { found[it.groupValues[1].toInt()] = it.groupValues[2] } }
        check(found.size == Mnemonic.WORD_COUNT) { "the kit's words are not all on screen: ${found.keys}" }
        return found.values.toList()
    }

    /** Whether the kit check is on screen. */
    fun showing(compose: ComposeTestRule): Boolean =
        compose.onAllNodes(hasText("Check your copy")).fetchSemanticsNodes().isNotEmpty()

    /** The 1-based positions the check asks for, in the order its fields are drawn. */
    fun asked(compose: ComposeTestRule): List<Int> =
        compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().mapNotNull { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                ?.let { wordLabel.matchEntire(it) }?.groupValues?.get(1)?.toInt()
        }

    /** Types each asked word, [wrong] replacing the one at that 1-based position. */
    fun enter(
        compose: ComposeTestRule,
        words: List<String>,
        wrong: Pair<Int, String>? = null,
        checkLabel: String = "Check words",
    ) {
        val positions = asked(compose)
        check(positions.size == 3) { "the check asks for three words, not $positions" }
        positions.forEachIndexed { i, n ->
            val word = if (wrong?.first == n) wrong.second else words[n - 1]
            compose.onAllNodes(hasSetTextAction())[i].performScrollTo().performTextReplacement(word)
        }
        compose.waitForIdle()
        // Pinned below the scroll in first run's frame, in the scroll everywhere else.
        val check = compose.onNode(hasText(checkLabel) and hasClickAction() and isEnabled())
        runCatching { check.performScrollTo() }
        check.performClick()
        compose.waitForIdle()
    }

    /** The check passed with the words from the kit. */
    fun pass(compose: ComposeTestRule, words: List<String>) {
        compose.waitUntil(15_000) { showing(compose) }
        enter(compose, words)
    }
}
