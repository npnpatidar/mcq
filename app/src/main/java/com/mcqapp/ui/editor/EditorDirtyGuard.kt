package com.mcqapp.ui.editor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped mirror of the question editor's dirty flag.
 *
 * The local leave guard in `QuestionEditorScreen` can only intercept Back and
 * its own top-bar icon; a `navController.navigate` issued elsewhere pops the
 * editor without asking. Keeping the flag here lets the navigation layer
 * consult it before such a navigation (round-2 E4). The editor owns the writes;
 * navigation only reads.
 */
object EditorDirtyGuard {
    private val _isDirty = MutableStateFlow(false)
    /** True while a question editor on screen has unsaved edits. */
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()

    /** Called by the editor as its form changes. */
    fun setDirty(dirty: Boolean) {
        _isDirty.value = dirty
    }

    /** Called when the editor leaves composition, so a stale flag never blocks
     *  a later navigation. */
    fun clear() {
        _isDirty.value = false
    }
}
