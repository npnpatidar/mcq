package com.mcqapp.ui.editor

import com.mcqapp.data.io.QuestionDto

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
 * Only ever navigates within the scope captured at open time.
 */
object EditorSession {
    /** A windowless view of a large scope: its size and an id-at-index read. */
    class Cursor(
        val size: Int,
        val index: Int,
        val idAt: suspend (Int) -> String?
    )

    var index: Int = -1
        private set
    var size: Int = 0
        private set
    var prevId: String? = null
        private set
    var nextId: String? = null
        private set

    /** Live read-through for import preview lists (set by ImportScreen). */
    var importReader: ((String) -> QuestionDto?)? = null
        private set

    /** Live write-through for import preview lists (set by ImportScreen). */
    var importWriter: ((QuestionDto) -> Unit)? = null
        private set

    private var ids: List<String> = emptyList()
    private var cursor: (suspend (Int) -> String?)? = null

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

    /** Opens the editor on a bounded in-memory list. */
    fun start(
        ids: List<String>,
        index: Int,
        reader: ((String) -> QuestionDto?)? = null,
        writer: ((QuestionDto) -> Unit)? = null
    ) {
        this.ids = ids
        this.cursor = null
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
        size = 0
        index = -1
        prevId = null
        nextId = null
        importReader = null
        importWriter = null
    }
}
