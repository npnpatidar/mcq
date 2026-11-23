package com.mcqapp.data.repository

import androidx.paging.PagingSource
import com.mcqapp.data.local.BookmarkEntity
import com.mcqapp.data.local.addAllChunked
import com.mcqapp.data.local.getForQuestionsChunked
import com.mcqapp.data.local.removeAllChunked
import com.mcqapp.domain.BookmarkedQuestion

/** The rows a bulk removal actually deleted, kept for an Undo snackbar. */
internal data class RemovedBookmarks(val rows: List<BookmarkEntity>) {
    val size: Int get() = rows.size
}

/** Removes many bookmarks in one pass, remembering what was removed. */
internal suspend fun McqRepository.removeBookmarksBulk(questionIds: Collection<String>): RemovedBookmarks {
    if (questionIds.isEmpty()) return RemovedBookmarks(emptyList())
    val db = db()
    val rows = db.bookmarkDao().getForQuestionsChunked(questionIds)
    db.bookmarkDao().removeAllChunked(questionIds)
    return RemovedBookmarks(rows)
}

/**
 * Undo of a bulk removal. The presence check is the race guard: a bookmark
 * re-added after the removal (or a second Undo) is left untouched, so an older
 * Undo can never turn a live bookmark back off or overwrite its newer time.
 */
internal suspend fun McqRepository.restoreBookmarksBulk(snapshot: RemovedBookmarks): Int {
    if (snapshot.rows.isEmpty()) return 0
    val dao = db().bookmarkDao()
    val live = dao.getForQuestionsChunked(snapshot.rows.map { it.questionId })
        .map { it.questionId }.toHashSet()
    val missing = snapshot.rows.filterNot { it.questionId in live }
    if (missing.isEmpty()) return 0
    dao.addAllChunked(missing)
    return missing.size
}

/** Sets a bookmark state on many questions idempotently (toggle-free). */
internal suspend fun McqRepository.setBookmarked(questionIds: Collection<String>, bookmarked: Boolean): Int {
    if (questionIds.isEmpty()) return 0
    val dao = db().bookmarkDao()
    val live = dao.getForQuestionsChunked(questionIds).map { it.questionId }.toHashSet()
    val changed = if (bookmarked) questionIds.filterNot { it in live } else live.toList()
    if (changed.isEmpty()) return 0
    if (bookmarked) dao.addAllChunked(changed.map { BookmarkEntity(it) })
    else dao.removeAllChunked(changed)
    return changed.size
}

/** A paper chip filter over the paged bookmark rows (empty id = all papers). */
internal fun filterBookmarksByPaper(
    items: List<BookmarkedQuestion>,
    paperId: String?
): List<BookmarkedQuestion> =
    if (paperId.isNullOrBlank()) items else items.filter { it.paperId == paperId }

/**
 * Delegates to the repository's live bookmark source and drops rows outside
 * the selected paper. The delegate's invalidation is forwarded so a toggle (or
 * a bulk removal) still refreshes the filtered list in place; the paper filter
 * only narrows what is shown, never the keyset cursor, so no row is skipped.
 */
internal class PaperFilteredBookmarkSource(
    private val delegate: PagingSource<BookmarkPageKey, BookmarkedQuestion>,
    private val paperId: String?
) : PagingSource<BookmarkPageKey, BookmarkedQuestion>() {

    override suspend fun load(
        params: LoadParams<BookmarkPageKey>
    ): LoadResult<BookmarkPageKey, BookmarkedQuestion> =
        when (val result = delegate.load(params)) {
            is PagingSource.LoadResult.Page -> PagingSource.LoadResult.Page(
                data = filterBookmarksByPaper(result.data, paperId),
                prevKey = result.prevKey,
                nextKey = result.nextKey
            )
            is PagingSource.LoadResult.Invalid -> result
            is PagingSource.LoadResult.Error -> result
        }

    override fun getRefreshKey(
        state: androidx.paging.PagingState<BookmarkPageKey, BookmarkedQuestion>
    ): BookmarkPageKey? = delegate.getRefreshKey(state)
}
