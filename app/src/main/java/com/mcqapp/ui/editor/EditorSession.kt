package com.mcqapp.ui.editor

import com.mcqapp.data.io.QuestionDto

/**
 * Which list the editor was opened from, so Prev/Next can walk it.
 * Set by browse / import screens on edit click; cleared for single-question
 * entries (library, bookmarks, new question). Only ever navigates within the
 * snapshot taken at open time.
 */
object EditorSession {
    var ids: List<String> = emptyList()
        private set
    var index: Int = -1
        private set

    /** Live read-through for import preview lists (set by ImportScreen). */
    var importReader: ((String) -> QuestionDto?)? = null
        private set

    /** Live write-through for import preview lists (set by ImportScreen). */
    var importWriter: ((QuestionDto) -> Unit)? = null
        private set

    val hasQueue: Boolean get() = index in ids.indices
    val prevId: String? get() = if (hasQueue && index > 0) ids[index - 1] else null
    val nextId: String? get() = if (hasQueue && index < ids.lastIndex) ids[index + 1] else null

    fun start(
        ids: List<String>,
        index: Int,
        reader: ((String) -> QuestionDto?)? = null,
        writer: ((QuestionDto) -> Unit)? = null
    ) {
        this.ids = ids
        this.index = index
        this.importReader = reader
        this.importWriter = writer
    }

    fun go(index: Int) {
        if (index in ids.indices) this.index = index
    }

    fun clear() {
        ids = emptyList()
        index = -1
        importReader = null
        importWriter = null
    }
}
