package com.app.vault.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Undo and redo for the note editor — `notes-as-documents`.
 *
 * **The one thing a text editor cannot ship without, and P2-21's had neither it nor
 * autosave.** A stack of whole strings rather than a diff: a note is capped at a
 * megabyte and the depth is bounded below, so the simple thing is also the cheap one.
 *
 * **Coalesced by pause, not by keystroke.** Recording every character would make undo
 * step backwards one letter at a time, which is not what anybody means by undo. A new
 * entry is pushed only when the text has settled — the editor records after its own
 * debounce — and consecutive identical states are dropped.
 *
 * Nothing here is persisted. The history dies with the composition, like the text it
 * describes, and a note's edit history is exactly the kind of record the vault does
 * not keep.
 */
class NoteHistory(initial: String) {

    private val past = ArrayDeque<String>()
    private val future = ArrayDeque<String>()
    private var current: String = initial

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** Note [text] as the newest state. A repeat of the current state is ignored. */
    fun record(text: String) {
        if (text == current) return
        past.addLast(current)
        while (past.size > DEPTH) past.removeFirst()
        future.clear()
        current = text
        refresh()
    }

    /** The previous state, or null when there is none. */
    fun undo(): String? {
        val previous = past.removeLastOrNull() ?: return null
        future.addLast(current)
        current = previous
        refresh()
        return previous
    }

    /** The state undone last, or null when nothing was undone. */
    fun redo(): String? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(current)
        current = next
        refresh()
        return next
    }

    private fun refresh() {
        canUndo = past.isNotEmpty()
        canRedo = future.isNotEmpty()
    }

    private companion object {
        /** Deep enough to be useful, bounded so a long session cannot grow without end. */
        const val DEPTH = 50
    }
}
