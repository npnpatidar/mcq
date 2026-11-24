package com.mcqapp

import com.mcqapp.data.repository.BookmarkScopeFilter
import com.mcqapp.data.repository.filterBookmarksByPaper
import com.mcqapp.data.repository.filterBookmarksByScope
import com.mcqapp.domain.BookmarkedQuestion
import com.mcqapp.domain.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Bookmarks paper chip filters the paged rows without moving the keyset. */
class LibraryScaleBookmarkFilterTest {

    private fun item(id: String, paperId: String) = BookmarkedQuestion(
        paperId = paperId,
        question = Question(
            id = id, categoryId = "c-$paperId", elements = emptyList(),
            options = emptyList(), correctOptionIds = emptySet()
        )
    )

    @Test
    fun nullOrBlankFilterShowsEverything() {
        val rows = listOf(item("q1", "p1"), item("q2", "p2"))
        assertEquals(rows, filterBookmarksByPaper(rows, null))
        assertEquals(rows, filterBookmarksByPaper(rows, ""))
    }

    @Test
    fun aPaperFilterKeepsOnlyThatPapersRows() {
        val rows = listOf(item("q1", "p1"), item("q2", "p2"), item("q3", "p1"))
        assertEquals(
            listOf("q1", "q3"),
            filterBookmarksByPaper(rows, "p1").map { it.question.id }
        )
    }

    /**
     * The paper-only helper is the unscoped special case of the full filter: a
     * chosen paper with no category ids keeps every row in that paper, and a
     * category id narrows it further by the question's own category.
     */
    @Test
    fun theScopeFilterExtendsThePaperFilterWithCategoryMembership() {
        fun itemIn(id: String, paperId: String, categoryId: String) = BookmarkedQuestion(
            paperId = paperId,
            question = Question(
                id = id, categoryId = categoryId, elements = emptyList(),
                options = emptyList(), correctOptionIds = emptySet()
            )
        )
        val rows = listOf(
            itemIn("q1", "p1", "c-p1-a"),
            itemIn("q2", "p2", "c-p2"),
            itemIn("q3", "p1", "c-p1-b")
        )
        assertEquals(
            filterBookmarksByPaper(rows, "p1"),
            filterBookmarksByScope(rows, BookmarkScopeFilter("p1"))
        )
        assertEquals(
            listOf("q1"),
            filterBookmarksByScope(
                rows, BookmarkScopeFilter("p1", setOf("c-p1-a"))
            ).map { it.question.id }
        )
        assertTrue(
            filterBookmarksByScope(
                rows, BookmarkScopeFilter("p1", setOf("c-p2"))
            ).isEmpty()
        )
    }
}
