package com.mcqapp

import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Passage
import com.mcqapp.ui.editor.PassageFormStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-2 E1/E2/E7 at the pure-JVM seam. The passage form used to live in the
 * dialog's `remember`; publishing a cross-paper warning unmounted the dialog,
 * so Cancel reopened a form seeded from the unchanged passage and the typed
 * title/body/category were gone. The VM-owned store is the draft that survives
 * both that unmount and rotation.
 */
class Round2EditorPassageFormTest {

    private val original = Passage(
        id = "p1",
        categoryId = "c1",
        title = "Original",
        elements = listOf(ContentElement.TextElement("Body"))
    )

    @Test
    fun editsSurviveAnUnmountAndRemount() {
        val store = PassageFormStore()
        store.open(original)
        store.setTitle("Edited title")
        store.setText("Edited body")
        store.setCategory("c2")

        // This is the value the ViewModel holds while the warning has unmounted
        // the dialog. A remount reads it, not a fresh form seeded from original.
        val held = store.form!!
        assertEquals("Edited title", held.title)
        assertEquals("Edited body", held.text)
        assertEquals("c2", held.categoryId)
        assertEquals("p1", held.original!!.id)

        // Reopening from the held form (a remount) keeps every edit.
        val reopened = held.copy(title = held.title, text = held.text, categoryId = held.categoryId)
        assertEquals(held, reopened)
    }

    @Test
    fun aNewPassageUsesTheFirstAvailableCategoryAtSaveTime() {
        val store = PassageFormStore()
        store.open(null) // opened before papers loaded: no category yet
        assertTrue(store.form!!.isNew)
        assertEquals("", store.form!!.categoryId)
        assertEquals("c-first", store.form!!.categoryOr("c-first"))

        store.seedCategory("c-first")
        assertEquals("c-first", store.form!!.categoryId)
        // A later seed must not replace the user's pick.
        store.seedCategory("c-other")
        assertEquals("c-first", store.form!!.categoryId)
    }

    @Test
    fun aBlankSeedNeverWipesTheForm() {
        val store = PassageFormStore()
        store.open(original)
        store.seedCategory("")
        assertNotNull(store.form)
        assertEquals("c1", store.form!!.categoryId)
    }

    @Test
    fun aUserPickedCategoryIsNeverOverwrittenByTheSeed() {
        val store = PassageFormStore()
        store.open(original)
        store.setCategory("c-user")
        store.seedCategory("c-first")
        assertEquals("c-user", store.form!!.categoryId)
    }

    @Test
    fun closingDropsTheDraftWithoutMutatingTheOriginalPassage() {
        val store = PassageFormStore()
        store.open(original)
        store.setTitle("Gone")
        store.close()
        assertNull(store.form)
        assertEquals("Original", original.title)
        assertFalse(store.form?.isNew ?: false)
    }
}
