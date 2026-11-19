package com.mcqapp.ui.editor

import com.mcqapp.data.io.QuestionDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /**
     * The whole cursor state as one immutable value. Publishing a new snapshot
     * in a single write is what stops a re-anchor from showing a fresh [size]
     * with the old [prevId]/[nextId], and lets the screen observe re-anchors
     * (which used to leave the counter/Prev/Next stale until an unrelated
     * state change happened to recompose the screen).
     */
    data class Snapshot(
        val index: Int = -1,
        val size: Int = 0,
        val prevId: String? = null,
        val nextId: String? = null
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    /** Emits on every cursor change, including a re-anchor. */
    val snapshotFlow: StateFlow<Snapshot> = _snapshot.asStateFlow()

    @Volatile private var current: Snapshot = Snapshot()

    private fun publish(next: Snapshot) {
        current = next
        _snapshot.value = next
    }

    val index: Int get() = current.index
    val size: Int get() = current.size
    val prevId: String? get() = current.prevId
    val nextId: String? get() = current.nextId

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

    val hasQueue: Boolean get() = current.index in 0 until current.size

    private suspend fun idAt(i: Int): String? =
        cursor?.let { it(i) } ?: ids.getOrNull(i)

    /** Recomputes the immediate neighbours for the current index. */
    suspend fun refreshNeighbors() {
        val s = current
        if (s.index !in 0 until s.size) {
            publish(s.copy(prevId = null, nextId = null))
            return
        }
        publish(
            s.copy(
                prevId = if (s.index > 0) idAt(s.index - 1) else null,
                nextId = if (s.index < s.size - 1) idAt(s.index + 1) else null
            )
        )
    }

    /**
     * The neighbour named by [id], captured as one immutable target: the id
     * AND the index it moves to. Resolving the index later from the mutable
     * [index] is what let two rapid Next taps both land on the same question
     * while the session advanced twice (question_editor_audit.md H-03).
     */
    fun captureTarget(id: String): MoveTarget? {
        val s = current
        if (s.index !in 0 until s.size) return null
        val delta = when (id) {
            s.prevId -> -1
            s.nextId -> 1
            else -> return null
        }
        val target = s.index + delta
        if (target !in 0 until s.size) return null
        return MoveTarget(id, target)
    }

    /**
     * Re-reads a cursor scope's live count and this anchor's position in it.
     * An anchor that left the scope leaves no coherent index: [index]
     * becomes -1 and the neighbours clear rather than describing the old
     * result set. Neighbours are resolved from the new position *before* the
     * snapshot is published, so no torn intermediate state is ever visible.
     */
    suspend fun reanchor(anchorId: String) {
        val count = cursorCount ?: return
        val indexOf = cursorIndexOf ?: return
        val newSize = count()
        val newIndex = indexOf(anchorId)
        if (newSize <= 0 || newIndex !in 0 until newSize || idAt(newIndex) != anchorId) {
            publish(Snapshot(size = newSize.coerceAtLeast(0), index = -1))
            return
        }
        publish(
            Snapshot(
                index = newIndex,
                size = newSize,
                prevId = if (newIndex > 0) idAt(newIndex - 1) else null,
                nextId = if (newIndex < newSize - 1) idAt(newIndex + 1) else null
            )
        )
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
        return if (i in 0 until current.size && idAt(i) == id) i else null
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
        this.importReader = reader
        this.importWriter = writer
        val resolved = index.coerceIn(-1, ids.size - 1)
        publish(
            Snapshot(
                index = resolved,
                size = ids.size,
                prevId = if (resolved > 0) ids.getOrNull(resolved - 1) else null,
                nextId = if (resolved in 0 until ids.size - 1) ids.getOrNull(resolved + 1) else null
            )
        )
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
        this.importReader = reader
        this.importWriter = writer
        val resolved = cursor.index.coerceIn(-1, cursor.size - 1)
        publish(Snapshot(index = resolved, size = cursor.size))
        refreshNeighbors()
    }

    /**
     * Moves to an index. In-memory scopes update their neighbours here; a
     * cursor scope's caller must follow with [refreshNeighbors], since its
     * neighbours are a database read.
     */
    fun go(index: Int) {
        val s = current
        if (index !in 0 until s.size) return
        if (cursor == null) {
            publish(
                s.copy(
                    index = index,
                    prevId = if (index > 0) ids.getOrNull(index - 1) else null,
                    nextId = if (index < s.size - 1) ids.getOrNull(index + 1) else null
                )
            )
        } else {
            publish(s.copy(index = index))
        }
    }

    fun clear() {
        ids = emptyList()
        cursor = null
        cursorCount = null
        cursorIndexOf = null
        importReader = null
        importWriter = null
        publish(Snapshot())
    }
}
