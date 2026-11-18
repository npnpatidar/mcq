package com.mcqapp

import com.mcqapp.domain.QuestionSearch
import com.mcqapp.ui.search.SearchRequest
import com.mcqapp.ui.search.isSearchPending
import com.mcqapp.ui.search.searchShowsTypeHint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `debouncing` compared only the query, so tapping a scope chip painted the
 * previous scope's hits for 300 ms with no "Searching…" cue. It now compares
 * the whole request; a blank query shows a type-to-search prompt instead.
 */
class FollowupSearchDebounceTest {

    private fun request(query: String, scope: QuestionSearch.Scope = QuestionSearch.Scope.ALL) =
        SearchRequest(query, scope)

    @Test
    fun aScopeOnlyChangeIsStillPending() {
        val current = request("biology", QuestionSearch.Scope.QUESTION)
        val committed = request("biology", QuestionSearch.Scope.ALL)
        assertTrue(isSearchPending(current, committed))
    }

    @Test
    fun aCommittedQueryIsNotPending() {
        assertFalse(isSearchPending(request("biology"), request("biology")))
    }

    @Test
    fun aQueryChangeIsPendingButABlankQueryNeverIs() {
        assertTrue(isSearchPending(request("bio"), request("biology")))
        assertFalse(isSearchPending(request(""), request("biology")))
    }

    @Test
    fun aBlankQueryShowsTheTypeHintOnlyWhenNotSearching() {
        assertTrue(searchShowsTypeHint("", debouncing = false))
        assertFalse(searchShowsTypeHint("", debouncing = true))
        assertFalse(searchShowsTypeHint("biology", debouncing = false))
    }
}
