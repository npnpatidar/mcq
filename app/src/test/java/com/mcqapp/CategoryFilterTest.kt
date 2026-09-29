package com.mcqapp

import com.mcqapp.data.io.CategoryFilter
import com.mcqapp.data.local.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryFilterTest {

    private val categories = listOf(
        CategoryEntity("root", "p", "Root", null),
        CategoryEntity("c1", "p", "Child 1", "root"),
        CategoryEntity("c2", "p", "Child 2", "root"),
        CategoryEntity("gc", "p", "Grandchild", "c1"),
        CategoryEntity("other", "p", "Other", null)
    )

    @Test
    fun rootCollectsWholeSubtree() {
        assertEquals(
            setOf("root", "c1", "c2", "gc"),
            CategoryFilter.subtreeIds(categories, "root")
        )
    }

    @Test
    fun midLevelCollectsOnlyDescendants() {
        assertEquals(setOf("c1", "gc"), CategoryFilter.subtreeIds(categories, "c1"))
    }

    @Test
    fun leafCollectsOnlyItself() {
        assertEquals(setOf("gc"), CategoryFilter.subtreeIds(categories, "gc"))
    }

    @Test
    fun unknownRootYieldsEmpty() {
        assertEquals(emptySet<String>(), CategoryFilter.subtreeIds(categories, "missing"))
        assertEquals(emptySet<String>(), CategoryFilter.subtreeIds(emptyList(), "root"))
    }
}
