package com.mcqapp.domain

import androidx.paging.PagingSource
import androidx.paging.PagingState

/**
 * Pages the pre-submit answer-review sheet over the session's order.
 *
 * The order is already in memory, and each page resolves just that slice's
 * questions by id, so a 250k-question review never holds every row.
 * [loadQuestions] is injected so the paging logic is unit-testable without a
 * database.
 */
class SessionReviewPagingSource(
    private val order: SessionOrder,
    private val selections: Map<String, Set<String>>,
    private val flagged: Set<String>,
    private val pageSize: Int,
    private val loadQuestions: suspend (List<String>) -> List<Question>
) : PagingSource<Int, AnswerReview.Row>() {

    override suspend fun load(
        params: LoadParams<Int>
    ): LoadResult<Int, AnswerReview.Row> {
        val offset = params.key ?: 0
        if (offset >= order.size) {
            return LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
        }
        val end = minOf(offset + params.loadSize.coerceAtLeast(1), order.size)
        val ids = (offset until end).map { order.idAt(it) }
        val byId = loadQuestions(ids).associateBy { it.id }
        val rows = ids.mapIndexedNotNull { i, id ->
            byId[id]?.let { q ->
                AnswerReview.row(offset + i + 1, q, selections, flagged)
            }
        }
        return LoadResult.Page(
            data = rows,
            prevKey = if (offset > 0) (offset - pageSize).coerceAtLeast(0) else null,
            nextKey = if (end < order.size) end else null
        )
    }

    override fun getRefreshKey(state: PagingState<Int, AnswerReview.Row>): Int? = null
}
