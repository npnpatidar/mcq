package com.mcqapp

import com.mcqapp.data.repository.filterBookmarksByPaper
import com.mcqapp.domain.BookmarkedQuestion
import com.mcqapp.domain.Question
import org.junit.Assert.assertEquals
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
}
