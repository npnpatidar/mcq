package com.mcqapp

import com.mcqapp.data.io.PaperClone
import com.mcqapp.data.local.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaperCloneTest {

    private val categories = listOf(
        CategoryEntity("c1", "p1", "Root", null, sortOrder = 0),
        CategoryEntity("c2", "p1", "Child", "c1", sortOrder = 1),
        CategoryEntity("c3", "p1", "Leaf", "c2", sortOrder = 0)
    )

    @Test
    fun remapNamespacesIdsAndParents() {
        val remapped = PaperClone.remapCategories(categories, "p2")
        assertEquals(setOf("c1", "c2", "c3"), remapped.keys)
        assertEquals(
            setOf("p2-c1", "p2-c2", "p2-c3"),
            remapped.values.map { it.id }.toSet()
        )
        assertEquals("p2", remapped.getValue("c1").paperId)
        assertNull(remapped.getValue("c1").parentId)
        assertEquals("p2-c1", remapped.getValue("c2").parentId)
        assertEquals("p2-c2", remapped.getValue("c3").parentId)
        assertEquals(1, remapped.getValue("c2").sortOrder)
    }

    @Test
    fun unknownParentsAreDropped() {
        val remapped = PaperClone.remapCategories(
            listOf(CategoryEntity("c9", "p1", "Orphan", "missing")),
            "p2"
        )
        assertNull(remapped.getValue("c9").parentId)
    }

    @Test
    fun copyPaperIdChainsSuffixes() {
        assertEquals("p1-copy", PaperClone.copyPaperId(setOf("p1"), "p1"))
        assertEquals(
            "p1-copy-3",
            PaperClone.copyPaperId(setOf("p1", "p1-copy", "p1-copy-2"), "p1")
        )
    }
}
