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
 * The exact total for the committed search request. It walks the same two-phase
 * pipeline the pager walks — [McqRepository.searchPage] (SQL candidate window
 * followed by the in-memory [QuestionSearch.filter]) — and then applies the
 * paper/category chips. Counting this way (rather than trusting Paging's
 * `itemCount`, which only knows loaded pages) is why the header can print an
 * exact number instead of "120+". Runs on IO so a broad query does not jank.
 */
internal suspend fun McqRepository.searchCount(
    query: String,
    scope: QuestionSearch.Scope,
    filter: SearchScopeFilter = SearchScopeFilter()
): Int = withContext(Dispatchers.IO) {
    if (query.isBlank()) return@withContext 0
    var offset = 0
    var count = 0
    while (true) {
        val page = searchPage(query, scope, offset, SEARCH_COUNT_WINDOW)
        count += page.hits.count { it.matches(filter) }
        offset = page.nextOffset ?: break
    }
    count
}

private const val SEARCH_COUNT_WINDOW = 200

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
