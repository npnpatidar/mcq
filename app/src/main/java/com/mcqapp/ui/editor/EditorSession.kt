package com.mcqapp.ui.editor

import com.mcqapp.data.io.QuestionDto
import kotlin.jvm.Volatile

/**
 * Which list the editor was opened from, so Prev/Next can walk it.
 *
 * Two backings share one API:
 * - an in-memory [start] for bounded lists (a passage's members, the import
 *   preview);
 * - a [Cursor] for Browse's filtered list, which at 250k questions is never
 *   materialised: the scope's size and the current index come from counts, and
 *   Prev/Next resolve one id at a time through the cursor's [Cursor.idAt].
 *
 * Navigation starts within the scope captured at open time. A cursor scope
 * re-reads its live count and the anchor's position after an edit ([reanchor]),
 * so a question that leaves the result set is dropped rather than kept at a
 * stale index.
 */
object EditorSession {
    /**
     * A windowless view of a large scope: its size and an id-at-index read.
     *
     * [count] and [indexOf] are the live reads used to re-anchor after an
     * edit that may change filter membership (question_editor_audit.md M-04);
     * without them the stored size and index keep describing the result set
     * from open time.
     */
    class Cursor(
        val size: Int,
        val index: Int,
        val idAt: suspend (Int) -> String?,
        val count: (suspend () -> Int)? = null,
        val indexOf: (suspend (String) -> Int)? = null
    )

    /** A navigation target captured together with its position. */
    data class MoveTarget(val id: String, val index: Int)

    // Re-anchoring mutates these off Main; the screen reads them on Main.
    @Volatile var index: Int = -1
        private set
    @Volatile var size: Int = 0
        private set
    @Volatile var prevId: String? = null
        private set
    @Volatile var nextId: String? = null
        private set

    /** Live read-through for import preview lists (set by ImportScreen). */
    var importReader: ((String) -> QuestionDto?)? = null
        private set

    /** Live write-through for import preview lists (set by ImportScreen). */
    var importWriter: ((QuestionDto) -> Unit)? = null
        private set

    private var ids: List<String> = emptyList()
    private var cursor: (suspend (Int) -> String?)? = null
    private var cursorCount: (suspend () -> Int)? = null
    private var cursorIndexOf: (suspend (String) -> Int)? = null

    val hasQueue: Boolean get() = index in 0 until size

    private suspend fun idAt(i: Int): String? =
        cursor?.let { it(i) } ?: ids.getOrNull(i)

    /** Recomputes the immediate neighbours for the current index. */
    suspend fun refreshNeighbors() {
        val i = index
        if (i !in 0 until size) {
            prevId = null
            nextId = null
            return
        }
        prevId = if (i > 0) idAt(i - 1) else null
        nextId = if (i < size - 1) idAt(i + 1) else null
    }

    /**
     * The neighbour named by [id], captured as one immutable target: the id
     * AND the index it moves to. Resolving the index later from the mutable
     * [index] is what let two rapid Next taps both land on the same question
     * while the session advanced twice (question_editor_audit.md H-03).
     */
    fun captureTarget(id: String): MoveTarget? {
        if (!hasQueue) return null
        val delta = when (id) {
            prevId -> -1
            nextId -> 1
            else -> return null
        }
        val target = index + delta
        if (target !in 0 until size) return null
        return MoveTarget(id, target)
    }

    /**
     * Re-reads a cursor scope's live count and this anchor's position in it.
     * An anchor that left the scope leaves no coherent index: [index]
     * becomes -1 and the neighbours clear rather than describing the old
     * result set. In-memory scopes are fixed lists and need no re-anchor.
     */
    suspend fun reanchor(anchorId: String) {
        val count = cursorCount ?: return
        val indexOf = cursorIndexOf ?: return
        val newSize = count()
        val newIndex = indexOf(anchorId)
        if (newSize <= 0 || newIndex !in 0 until newSize || idAt(newIndex) != anchorId) {
            size = newSize.coerceAtLeast(0)
            index = -1
            prevId = null
            nextId = null
            return
        }
        size = newSize
        index = newIndex
        refreshNeighbors()
    }

    /**
     * Resolves [id]'s current position. A cursor scope re-verifies the row at
     * the counted index, so a stale count can never move the session onto a
     * different question.
     */
    suspend fun indexOfId(id: String): Int? {
        val indexOf = cursorIndexOf
        if (indexOf == null) {
            val i = ids.indexOf(id)
            return if (i >= 0) i else null
        }
        val i = indexOf(id)
        return if (i in 0 until size && idAt(i) == id) i else null
    }

    /** Opens the editor on a bounded in-memory list. */
    fun start(
        ids: List<String>,
        index: Int,
        reader: ((String) -> QuestionDto?)? = null,
        writer: ((QuestionDto) -> Unit)? = null
    ) {
        this.ids = ids
        this.cursor = null
        this.cursorCount = null
        this.cursorIndexOf = null
        this.size = ids.size
        this.index = index.coerceIn(-1, ids.size - 1)
        this.importReader = reader
        this.importWriter = writer
        this.prevId = if (this.index > 0) ids.getOrNull(this.index - 1) else null
        this.nextId = if (this.index in 0 until ids.size - 1) ids.getOrNull(this.index + 1) else null
    }

    /** Opens the editor on a large scope, resolving neighbours lazily. */
    suspend fun startCursor(
        cursor: Cursor,
        reader: ((String) -> QuestionDto?)? = null,
        writer: ((QuestionDto) -> Unit)? = null
    ) {
        this.ids = emptyList()
        this.cursor = cursor.idAt
        this.cursorCount = cursor.count
        this.cursorIndexOf = cursor.indexOf
        this.size = cursor.size
        this.index = cursor.index.coerceIn(-1, cursor.size - 1)
        this.importReader = reader
        this.importWriter = writer
        refreshNeighbors()
    }

    /**
     * Moves to an index. In-memory scopes update their neighbours here; a
     * cursor scope's caller must follow with [refreshNeighbors], since its
     * neighbours are a database read.
     */
    fun go(index: Int) {
        if (index !in 0 until size) return
        this.index = index
        if (cursor == null) {
            prevId = if (index > 0) ids.getOrNull(index - 1) else null
            nextId = if (index < size - 1) ids.getOrNull(index + 1) else null
        }
    }

    fun clear() {
        ids = emptyList()
        cursor = null
        cursorCount = null
        cursorIndexOf = null
        size = 0
        index = -1
        prevId = null
        nextId = null
        importReader = null
        importWriter = null
    }
}
