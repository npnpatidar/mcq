package com.mcqapp.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.mcqapp.domain.QuestionSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Paper/category scope for cross-paper search. An empty set means "all" — the
 * same convention as Browse's category filter, and deliberately separate from
 * [QuestionSearch.Scope] (which is *where in a card* to look, not *which cards*).
 */
data class SearchScopeFilter(
    val paperIds: Set<String> = emptySet(),
    val categoryIds: Set<String> = emptySet()
) {
    val isAll: Boolean get() = paperIds.isEmpty() && categoryIds.isEmpty()
}

internal fun QuestionSearch.Hit.matches(filter: SearchScopeFilter): Boolean =
    (filter.paperIds.isEmpty() || paperId in filter.paperIds) &&
        (filter.categoryIds.isEmpty() || question.categoryId in filter.categoryIds)

/**
 * A count for the search header. [lowerBound] is always the number of matching
 * hits walked so far; [exact] is true only when the whole candidate set was
 * exhausted. While it is false the UI shows "N+" so a broad query paints a
 * header immediately instead of blocking on the full walk.
 */
data class SearchCount(val lowerBound: Int, val exact: Boolean)

/**
 * Walks at most [maxWindows] candidate windows of the same two-phase pipeline
 * the pager walks — [McqRepository.searchPage] (SQL candidate window followed
 * by the in-memory [QuestionSearch.filter]) — and applies the paper/category
 * chips. Extracted from the repository so the bounded/count-only path is a
 * pure, JVM-testable seam: a fake `fetch` can assert how far the walk went.
 * The bound is what keeps the header non-blocking; callers that need the exact
 * total call again with the default (unbounded) setting.
 */
internal suspend fun countSearchHits(
    fetch: suspend (offset: Int, limit: Int) -> GlobalSearchPage,
    matches: (QuestionSearch.Hit) -> Boolean,
    maxWindows: Int = Int.MAX_VALUE
): SearchCount {
    var offset = 0
    var count = 0
    repeat(maxWindows) {
        val page = fetch(offset, SEARCH_COUNT_WINDOW)
        count += page.hits.count(matches)
        offset = page.nextOffset ?: return SearchCount(count, true)
    }
    return SearchCount(count, false)
}

/**
 * The exact total for the committed search request, used once the cheap pass
 * has already shown a lower bound. Counting this way (rather than trusting
 * Paging's `itemCount`, which only knows loaded pages) is why the header can
 * settle on an exact number instead of a permanent "120+". Runs on IO so a
 * broad query does not jank.
 */
internal suspend fun McqRepository.searchCount(
    query: String,
    scope: QuestionSearch.Scope,
    filter: SearchScopeFilter = SearchScopeFilter()
): Int = withContext(Dispatchers.IO) {
    if (query.isBlank()) return@withContext 0
    countSearchHits(
        fetch = { offset, limit -> searchPage(query, scope, offset, limit) },
        matches = { it.matches(filter) }
    ).lowerBound
}

/**
 * The prompt, bounded first pass for a broad query. One SQL window (with its
 * two chunked provenance lookups) is enough to show "N+" while the exact walk
 * continues off the critical path.
 */
internal suspend fun McqRepository.searchCountBounded(
    query: String,
    scope: QuestionSearch.Scope,
    filter: SearchScopeFilter = SearchScopeFilter()
): SearchCount = withContext(Dispatchers.IO) {
    if (query.isBlank()) return@withContext SearchCount(0, true)
    countSearchHits(
        fetch = { offset, limit -> searchPage(query, scope, offset, limit) },
        matches = { it.matches(filter) },
        maxWindows = SEARCH_BOUNDED_WINDOWS
    )
}

private const val SEARCH_COUNT_WINDOW = 200
private const val SEARCH_BOUNDED_WINDOWS = 1

/**
 * A paging source that narrows the repository's already-filtered hits by the
 * paper/category chips. The phase-1 candidate offset is the key, so dropping a
 * hit for a filter mismatch shifts no later hit and no result is skipped — the
 * same argument [SearchPagingSource] uses for the in-memory filter.
 */
internal class FilteredSearchPagingSource(
    private val fetch: suspend (Int, Int) -> GlobalSearchPage,
    private val filter: SearchScopeFilter
) : PagingSource<Int, QuestionSearch.Hit>() {

    override suspend fun load(
        params: LoadParams<Int>
    ): LoadResult<Int, QuestionSearch.Hit> {
        var offset = params.key ?: 0
        val target = params.loadSize.coerceAtLeast(1)
        val out = ArrayList<QuestionSearch.Hit>(target)
        var nextKey: Int? = null
        return try {
            while (out.size < target) {
                val page = fetch(offset, SEARCH_COUNT_WINDOW)
                page.hits.filterTo(out) { it.matches(filter) }
                nextKey = page.nextOffset
                if (nextKey == null) break
                offset = nextKey
            }
            LoadResult.Page(data = out, prevKey = null, nextKey = nextKey)
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(
        state: PagingState<Int, QuestionSearch.Hit>
    ): Int? = null
}
