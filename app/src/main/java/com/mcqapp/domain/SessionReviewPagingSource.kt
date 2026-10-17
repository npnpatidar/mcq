package com.mcqapp.domain

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.mcqapp.data.local.SessionRef

/**
 * Pages the pre-submit answer-review sheet over the session's ordered refs.
 *
 * The refs are already in memory (ids and passage membership only); each page
 * resolves just that slice's questions, so a 250k-question review never holds
 * every row. [loadQuestions] is injected so the paging logic is unit-testable
 * without a database.
 */
class SessionReviewPagingSource(
    private val refs: List<SessionRef>,
    private val selections: Map<String, Set<String>>,
    private val flagged: Set<String>,
    private val pageSize: Int,
    private val loadQuestions: suspend (List<String>) -> List<Question>
) : PagingSource<Int, AnswerReview.Row>() {

    override suspend fun load(
        params: LoadParams<Int>
    ): LoadResult<Int, AnswerReview.Row> {
        val offset = params.key ?: 0
        if (offset >= refs.size) {
            return LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
        }
        val end = minOf(offset + params.loadSize.coerceAtLeast(1), refs.size)
        val slice = refs.subList(offset, end)
        val byId = loadQuestions(slice.map { it.id }).associateBy { it.id }
        val rows = slice.mapIndexedNotNull { i, ref ->
            byId[ref.id]?.let { q ->
                AnswerReview.row(offset + i + 1, q, selections, flagged)
            }
        }
        return LoadResult.Page(
            data = rows,
            prevKey = if (offset > 0) (offset - pageSize).coerceAtLeast(0) else null,
            nextKey = if (end < refs.size) end else null
        )
    }

    override fun getRefreshKey(state: PagingState<Int, AnswerReview.Row>): Int? = null
}
