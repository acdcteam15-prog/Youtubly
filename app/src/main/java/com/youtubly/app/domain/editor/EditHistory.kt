package com.youtubly.app.domain.editor

/**
 * Snapshot undo/redo. [push] ignores no-op changes so a tap that doesn't move anything
 * doesn't create an empty undo step. Not thread-safe: owned by one ViewModel on main.
 */
class EditHistory<T>(initial: T, private val limit: Int = 100) {
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()

    var present: T = initial
        private set

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Returns false (and changes nothing) when [next] equals the current state. */
    fun push(next: T): Boolean {
        if (next == present) return false
        past.addLast(present)
        while (past.size > limit) past.removeFirst()
        present = next
        future.clear()
        return true
    }

    fun undo(): Boolean {
        val previous = past.removeLastOrNull() ?: return false
        future.addLast(present)
        present = previous
        return true
    }

    fun redo(): Boolean {
        val next = future.removeLastOrNull() ?: return false
        past.addLast(present)
        present = next
        return true
    }
}
