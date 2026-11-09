package com.mcqapp

import com.mcqapp.domain.stats.CoveragePaper
import com.mcqapp.domain.stats.CoverageStatsBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The row-based Coverage slice (plan §4.1/§4.3). */
class CoverageStatsTest {

    private fun paper(
        id: String,
        questions: Int,
        studied: Int,
        bookmarked: Int = 0
    ) = CoveragePaper(
        paperId = id,
        label = "Paper $id",
        questions = questions,
        studied = studied,
        bookmarked = bookmarked
    )

    @Test
    fun emptyScopeHasNoQuestions() {
        val stats = CoverageStatsBuilder.build(emptyList(), scoped = false)
        assertEquals(0, stats.questions)
        assertEquals(0, stats.studied)
        assertEquals(0, stats.unseen)
        assertEquals(0, stats.bookmarked)
        assertEquals(0.0, stats.studiedRate, 0.0001)
        assertTrue(stats.papers.isEmpty())
    }

    @Test
    fun zeroQuestionsHasNoStudiedRate() {
        val stats = CoverageStatsBuilder.build(
            listOf(paper("p1", questions = 0, studied = 0)),
            scoped = false
        )
        assertEquals(0, stats.questions)
        assertEquals(0.0, stats.studiedRate, 0.0001)
    }

    @Test
    fun scopedViewKeepsTotalsButHidesThePerPaperList() {
        val stats = CoverageStatsBuilder.build(
            listOf(paper("p1", questions = 10, studied = 4, bookmarked = 2)),
            scoped = true
        )
        assertEquals(10, stats.questions)
        assertEquals(4, stats.studied)
        assertEquals(6, stats.unseen)
        assertEquals(2, stats.bookmarked)
        assertEquals(0.4, stats.studiedRate, 0.0001)
        assertTrue(stats.papers.isEmpty())
    }

    @Test
    fun unscopedViewSumsPapersAndKeepsOnlyUnseenOnes() {
        val stats = CoverageStatsBuilder.build(
            listOf(
                paper("p1", questions = 10, studied = 4, bookmarked = 1),
                paper("p2", questions = 5, studied = 5, bookmarked = 2),
                paper("p3", questions = 8, studied = 1, bookmarked = 0)
            ),
            scoped = false
        )
        assertEquals(23, stats.questions)
        assertEquals(10, stats.studied)
        assertEquals(13, stats.unseen)
        assertEquals(3, stats.bookmarked)
        assertEquals(2, stats.papers.size)
        assertEquals(listOf("p3", "p1"), stats.papers.map { it.paperId })
        assertTrue(stats.papers.all { it.unseen > 0 })
    }

    @Test
    fun perPaperListIsSortedByUnseenAndCapped() {
        val rows = (0 until 20).map { index ->
            paper("p$index", questions = 100, studied = index)
        }
        val stats = CoverageStatsBuilder.build(rows, scoped = false, paperLimit = 15)
        assertEquals(15, stats.papers.size)
        assertEquals("p0", stats.papers.first().paperId)
        assertEquals("p14", stats.papers.last().paperId)
        assertTrue(
            stats.papers.zipWithNext().all { (a, b) -> a.unseen >= b.unseen }
        )
    }

    @Test
    fun unseenIsQuestionsMinusStudiedAndNeverNegative() {
        assertEquals(6, paper("p1", 10, 4).unseen)
        assertEquals(0, paper("p1", 4, 10).unseen)
    }
}
