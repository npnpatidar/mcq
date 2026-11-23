package com.mcqapp

import com.mcqapp.data.repository.GlobalSearchPage
import com.mcqapp.data.repository.SearchCount
import com.mcqapp.data.repository.SearchScopeFilter
import com.mcqapp.data.repository.countSearchHits
import com.mcqapp.data.repository.matches
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionSearch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The search header must not wait for a full walk of a broad candidate set.
 * The counted pipeline is a pure seam (fetch + match), so both the prompt
 * bounded pass and the later exact pass run on the JVM host.
 */
class SearchCountTest {

    private fun hit(id: String, paperId: String = "p1") = QuestionSearch.Hit(
        question = Question(
            id = id,
            categoryId = "c1",
            text = id,
            options = emptyList(),
            correctOptionIds = emptySet()
        ),
        paperId = paperId,
        paperTitle = "Paper $paperId"
    )

    @Test
    fun boundedPassStopsAfterItsWindowAndReportsALowerBound() = runBlocking {
        val pages = listOf(
            GlobalSearchPage(listOf(hit("a"), hit("b")), nextOffset = 200),
            GlobalSearchPage(listOf(hit("c")), nextOffset = 400),
            GlobalSearchPage(listOf(hit("d")), null)
        )
        var fetches = 0
        val result = countSearchHits(
            fetch = { _, _ -> pages[fetches++] },
            matches = { true },
            maxWindows = 1
        )
        assertEquals(SearchCount(2, false), result)
        assertEquals(
            "the header must not materialise the rest of the bank before painting",
            1, fetches
        )
    }

    @Test
    fun exactPassWalksUntilTheCandidatesRunOutAndMarksTheTotalExact() = runBlocking {
        val pages = listOf(
            GlobalSearchPage(listOf(hit("a"), hit("b")), nextOffset = 200),
            GlobalSearchPage(listOf(hit("c")), nextOffset = 400),
            GlobalSearchPage(listOf(hit("d")), null)
        )
        var fetches = 0
        val result = countSearchHits(
            fetch = { _, _ -> pages[fetches++] },
            matches = { true }
        )
        assertEquals(SearchCount(4, true), result)
        assertEquals(3, fetches)
    }

    @Test
    fun aSmallCandidateSetIsExactEvenOnTheBoundedPass() = runBlocking {
        val result = countSearchHits(
            fetch = { _, _ -> GlobalSearchPage(listOf(hit("a")), null) },
            matches = { true },
            maxWindows = 1
        )
        assertEquals(
            "no 'N+' when the whole set fit in one window",
            SearchCount(1, true), result
        )
    }

    @Test
    fun chipFilterIsAppliedBeforeCountingAndCanMakeABoundedPassExact() = runBlocking {
        val filter = SearchScopeFilter(paperIds = setOf("p2"))
        val pages = listOf(
            GlobalSearchPage(listOf(hit("a"), hit("b", "p2")), nextOffset = 200),
            GlobalSearchPage(listOf(hit("c", "p2")), null)
        )
        var fetches = 0
        val bounded = countSearchHits(
            fetch = { _, _ -> pages[fetches++] },
            matches = { it.matches(filter) },
            maxWindows = 1
        )
        assertEquals(SearchCount(1, false), bounded)

        fetches = 0
        val exact = countSearchHits(
            fetch = { _, _ -> pages[fetches++] },
            matches = { it.matches(filter) }
        )
        assertEquals(SearchCount(2, true), exact)
    }
}
