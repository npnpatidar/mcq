package com.mcqapp.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.withTransaction
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.AttemptDao
import com.mcqapp.data.local.AttemptEntity
import com.mcqapp.data.local.BookmarkEntity
import com.mcqapp.data.local.CategoryBreakdownRow
import com.mcqapp.data.local.QuestionResultEntity
import com.mcqapp.data.local.ResultAggregates
import com.mcqapp.data.local.ResultScan
import com.mcqapp.data.local.applyQuestionStats
import com.mcqapp.data.local.getByIdsChunked
import com.mcqapp.data.local.getSessionRefsByIdsChunked
import com.mcqapp.domain.Attempt
import com.mcqapp.domain.Mastery
import com.mcqapp.domain.NumberedResult
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.QuestionResult
import com.mcqapp.domain.QuestionStats
import com.mcqapp.domain.ReviewFilters
import com.mcqapp.domain.SessionOrder
import com.mcqapp.domain.parseContentElements
import com.mcqapp.domain.textContent
import com.mcqapp.domain.toContentJson
import com.mcqapp.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

@kotlinx.serialization.Serializable
private data class QuestionOptionDto(
    val id: String,
    val text: String = "",
    @kotlinx.serialization.Serializable(with = com.mcqapp.domain.ContentElementListJson::class)
    val elements: List<com.mcqapp.domain.ContentElement> = emptyList(),
    val image: String? = null
)

/**
 * Bookmarks, attempts and mistakes: everything a finished test leaves behind.
 * Split out of [McqRepository]; question reads go through [QuestionStore] and
 * the entity mapping through [QuestionContentMapper].
 */
internal class HistoryStore(
    private val db: AppDatabase,
    private val mapper: QuestionContentMapper,
    private val questionStore: QuestionStore
) {

    fun observeBookmarks(): Flow<List<String>> = db.bookmarkDao().observeAll()

    /**
     * A signal that the bookmark set changed. The bookmarks screen pages its
     * rows with a plain [PagingSource], so all it needs to rebuild them is the
     * table's invalidation — not the ids themselves, which a "bookmark
     * everything" bank would hand back in full on every change.
     */
    fun observeBookmarkChanges(): Flow<Unit> =
        db.invalidationTracker.createFlow("bookmarks").map { Unit }

    /** How many bookmarks there are, for the header count and empty state. */
    fun observeBookmarkCount(): Flow<Int> = db.bookmarkDao().observeCount()

    suspend fun toggleBookmark(questionId: String) {
        // One transaction: the check and the flip must observe one state, or
        // a double-tap racing within one dispatcher hop reads the same value
        // twice and both calls flip the same way — REPLACE quietly absorbs
        // the second write, so two taps end where one tap should.
        db.withTransaction {
            val wasBookmarked = db.bookmarkDao().isBookmarked(questionId)
            Logger.d("REPO", "toggleBookmark($questionId) wasBookmarked=$wasBookmarked")
            if (wasBookmarked) {
                db.bookmarkDao().remove(questionId)
            } else {
                db.bookmarkDao().add(BookmarkEntity(questionId))
            }
        }
    }

    suspend fun isBookmarked(questionId: String): Boolean =
        db.bookmarkDao().isBookmarked(questionId)

    /** Category title per category id, in one query instead of one per id. */
    private suspend fun categoryTitlesOf(categoryIds: Set<String>): Map<String, String> {
        if (categoryIds.isEmpty()) return emptyMap()
        return db.categoryDao().getByIdsChunked(categoryIds.toList())
            .associate { it.id to it.title }
    }

    /** Owning paper id per category id, in one query instead of one per id. */
    private suspend fun categoryPaperIdsOf(categoryIds: Set<String>): Map<String, String> {
        if (categoryIds.isEmpty()) return emptyMap()
        return db.categoryDao().getByIdsChunked(categoryIds.toList())
            .associate { it.id to it.paperId }
    }

    /** Owning paper's title per category id, in two queries instead of two per id. */
    private suspend fun paperTitlesForCategories(categoryIds: Set<String>): Map<String, String> {
        if (categoryIds.isEmpty()) return emptyMap()
        val categories = db.categoryDao().getByIdsChunked(categoryIds.toList())
        val papers = db.paperDao().getByIdsChunked(
            categories.map { it.paperId }.toSet().toList()
        ).associateBy { it.id }
        return categories.mapNotNull { category ->
            papers[category.paperId]?.let { category.id to it.title }
        }.toMap()
    }

    /**
     * Bookmarked questions as a paged list, newest bookmark first. Content is
     * decoded per requested page only, so a "bookmark everything" bank never
     * materialises its whole reading list. The page continues from [BookmarkPageKey],
     * the last item of the previous page — not a row count — so edits ahead
     * of the cursor cannot shift the window.
     */
    fun bookmarkedPagingSource(): PagingSource<BookmarkPageKey, com.mcqapp.domain.BookmarkedQuestion> =
        BookmarksPagingSource { key, limit -> bookmarkedPage(key, limit) }

    private suspend fun bookmarkedPage(key: BookmarkPageKey?, limit: Int): BookmarkPage {
        val entities =
            db.questionDao().pageBookmarkedQuestions(
                key?.afterCreatedAt ?: 0L,
                key?.afterQuestionId,
                limit
            )
        if (entities.isEmpty()) return BookmarkPage(emptyList(), cursor = null, hasMore = false)
        val questions = mapper.toDomainBulk(entities.map { it.question })
        val paperIdByCategory = categoryPaperIdsOf(questions.map { it.categoryId }.toSet())
        val items = questions.mapNotNull { question ->
            paperIdByCategory[question.categoryId]?.let {
                com.mcqapp.domain.BookmarkedQuestion(it, question)
            }
        }
        return BookmarkPage(
            items,
            cursor = entities.last().let { BookmarkPageKey(it.bookmarkCreatedAt, it.question.id) },
            hasMore = entities.size == limit
        )
    }

    /**
     * Bookmark rows paired with their owning paper, for the bookmarks screen.
     *
     * Linear: it reads every bookmark id and every one of their questions. It
     * is what the tests assert provenance against; the screen pages its rows
     * with [bookmarkedPagingSource], and `NoDeadCodeTest` refuses a production
     * call here.
     */
    suspend fun getBookmarkedQuestions(): List<com.mcqapp.domain.BookmarkedQuestion> {
        val ids = db.bookmarkDao().getAll()
        if (ids.isEmpty()) return emptyList()
        val questions = questionStore.getQuestionsByIds(ids)
        if (questions.isEmpty()) return emptyList()
        val paperIdByCategory = categoryPaperIdsOf(questions.map { it.categoryId }.toSet())
        return questions.mapNotNull { question ->
            paperIdByCategory[question.categoryId]?.let {
                com.mcqapp.domain.BookmarkedQuestion(it, question)
            }
        }
    }

    /** Running score tallies for the per-question loop below. */
    private class Tally {
        var correct = 0
        var wrong = 0
        var skipped = 0
        var ungraded = 0
        var score = 0.0
        var maxScore = 0.0
    }

    /**
     * Scores one batch of questions and materialises their result rows.
     * Shared by [saveAttempt] and [saveAttemptStreamed] so both count exactly
     * the same way.
     */
    private fun scoreBatch(
        questions: List<Question>,
        selections: Map<String, Set<String>>,
        negativeMarking: Double,
        categoryTitles: Map<String, String>,
        dwellSeconds: Map<String, Long>,
        tally: Tally,
        rows: MutableList<QuestionResultEntity>
    ) {
        for (q in questions) {
            val selected = selections[q.id].orEmpty()
            // Computed once and shared by the aggregate counters and the
            // stored row below: for an ungraded question (no answer key) this
            // is always false, matching its exclusion from scoring — every
            // reader of the stored row guards on the empty key first.
            val isCorrect = selected.isNotEmpty() && selected == q.correctOptionIds
            if (q.correctOptionIds.isEmpty()) {
                // No answer key: excluded from scoring entirely (no credit, no penalty).
                tally.ungraded++
            } else {
                tally.maxScore += q.marks
                when {
                    selected.isEmpty() -> tally.skipped++
                    isCorrect -> {
                        tally.correct++
                        tally.score += q.marks
                    }
                    else -> {
                        tally.wrong++
                        tally.score -= q.marks * negativeMarking
                    }
                }
            }
            rows.add(
                QuestionResultEntity(
                    attemptId = 0,
                    questionId = q.id,
                    categoryTitle = categoryTitles[q.categoryId] ?: "",
                    text = q.elements.toContentJson(mapper.json),
                    optionsJson = mapper.json.encodeToString(
                        ListSerializer(QuestionOptionDto.serializer()),
                        q.options.map { QuestionOptionDto(it.id, it.text, it.elements, it.image) }
                    ),
                    correctOptionIds = q.correctOptionIds.joinToString(","),
                    selectedOptionIds = selected.joinToString(","),
                    isCorrect = isCorrect,
                    explanation = q.explanationElements.toContentJson(mapper.json),
                    explanationImage = q.explanationImage,
                    dwellSeconds = dwellSeconds[q.id] ?: 0L
                )
            )
        }
    }

    companion object {
        /** Question rows resolved per batch by [saveAttemptStreamed]. */
        const val BATCH = 500
    }

    suspend fun saveAttempt(
        paperId: String,
        paperTitle: String,
        questions: List<Question>,
        selections: Map<String, Set<String>>,
        negativeMarking: Double,
        durationSeconds: Long,
        finishedAt: Long,
        dwellSeconds: Map<String, Long> = emptyMap()
    ): Long {
        val tally = Tally()
        val results = mutableListOf<QuestionResultEntity>()
        // Resolved once for the whole attempt: was two lookups per question.
        val categoryTitles = categoryTitlesOf(questions.map { it.categoryId }.toSet())
        scoreBatch(
            questions, selections, negativeMarking, categoryTitles,
            dwellSeconds, tally, results
        )
        Logger.d("REPO", "saveAttempt(paper=$paperId, questions=${questions.size}, " +
            "correct=${tally.correct}, wrong=${tally.wrong}, skipped=${tally.skipped}, " +
            "ungraded=${tally.ungraded}, score=${tally.score}/maxScore=${tally.maxScore}")
        // One transaction: the attempt header and its per-question rows must
        // land together, so history never shows an attempt without results.
        return db.withTransaction {
            val attemptId = db.attemptDao().insertAttempt(
                AttemptEntity(
                    paperId = paperId,
                    title = paperTitle,
                    totalQuestions = questions.size,
                    correctCount = tally.correct,
                    wrongCount = tally.wrong,
                    skippedCount = tally.skipped,
                    score = tally.score,
                    maxScore = tally.maxScore,
                    durationSeconds = durationSeconds,
                    finishedAt = finishedAt
                )
            )
            db.attemptDao().insertResults(results.map { it.copy(attemptId = attemptId) })
            db.questionStatsDao().applyQuestionStats(paperId, paperTitle, finishedAt, results)
            Logger.d("REPO", "saveAttempt stored attemptId=$attemptId with ${results.size} results")
            attemptId
        }
    }

    /**
     * The same save as [saveAttempt], but every question is resolved from the
     * database a batch at a time, straight off the session's [order]: a
     * full-paper test of a 169k-question paper would otherwise hold every
     * question's content at once — and, handed one id per question, its whole
     * id list, which at a million questions is ~80 MB of strings held for the
     * length of the save on top of the content.
     *
     * The category titles a batch needs are resolved as batches meet new
     * categories, then memoised. Reading them up front cost a second pass over
     * every question row of the paper; the order is grouped by category, so a
     * category is looked up at most once.
     */
    suspend fun saveAttemptStreamed(
        paperId: String,
        paperTitle: String,
        order: SessionOrder,
        selections: Map<String, Set<String>>,
        negativeMarking: Double,
        durationSeconds: Long,
        finishedAt: Long,
        dwellSeconds: Map<String, Long> = emptyMap(),
        onProgress: (stored: Int, total: Int) -> Unit = { _, _ -> }
    ): Long = db.withTransaction {
        val tally = Tally()
        var stored = 0
        val attemptId = db.attemptDao().insertAttempt(
            AttemptEntity(
                paperId = paperId,
                title = paperTitle,
                totalQuestions = order.size,
                correctCount = 0,
                wrongCount = 0,
                skippedCount = 0,
                score = 0.0,
                maxScore = 0.0,
                durationSeconds = durationSeconds,
                finishedAt = finishedAt
            )
        )
        val categoryTitles = HashMap<String, String>()
        val knownCategories = HashSet<String>()
        var start = 0
        while (start < order.size) {
            val end = minOf(start + BATCH, order.size)
            val ids = (start until end).map { order.idAt(it) }
            // Session order inside the batch, not the database's.
            val entities = db.questionDao().getByIdsChunked(ids).associateBy { it.id }
            val batch = ids.mapNotNull { entities[it] }
            val newCategories = batch.map { it.categoryId }.toSet() - knownCategories
            if (newCategories.isNotEmpty()) {
                knownCategories += newCategories
                categoryTitles += categoryTitlesOf(newCategories)
            }
            val rows = mutableListOf<QuestionResultEntity>()
            scoreBatch(
                mapper.toDomainBulk(batch), selections, negativeMarking,
                categoryTitles, dwellSeconds, tally, rows
            )
            db.attemptDao().insertResults(rows.map { it.copy(attemptId = attemptId) })
            db.questionStatsDao().applyQuestionStats(paperId, paperTitle, finishedAt, rows)
            stored += rows.size
            onProgress(stored, order.size)
            start = end
        }
        db.attemptDao().updateTally(
            attemptId,
            tally.correct,
            tally.wrong,
            tally.skipped,
            tally.score,
            tally.maxScore
        )
        Logger.d(
            "REPO",
            "saveAttemptStreamed(paper=$paperId, questions=${order.size}, " +
                "correct=${tally.correct}, wrong=${tally.wrong}, skipped=${tally.skipped}, " +
                "ungraded=${tally.ungraded}, score=${tally.score}/maxScore=${tally.maxScore})"
        )
        attemptId
    }

    fun observeAttempts(): Flow<List<Attempt>> =
        db.attemptDao().observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getAttempt(attemptId: Long): Attempt? {
        Logger.d("REPO", "getAttempt($attemptId)")
        return db.attemptDao().getById(attemptId)?.toDomain()
    }

    private fun AttemptEntity.toDomain(): Attempt = Attempt(
        id = id,
        paperId = paperId,
        title = title,
        totalQuestions = totalQuestions,
        correctCount = correctCount,
        wrongCount = wrongCount,
        skippedCount = skippedCount,
        score = score,
        maxScore = maxScore,
        durationSeconds = durationSeconds,
        finishedAt = finishedAt
    )

    /**
     * Every result row of an attempt, decoded. Linear in the attempt: it is what
     * the tests assert the streamed save against; the results screen pages its
     * rows with [resultPagingSource] and reads its header from the SQL
     * aggregates, and `NoDeadCodeTest` refuses a production call here.
     */
    suspend fun getAttemptResults(attemptId: Long): List<QuestionResult> {
        Logger.d("REPO", "getAttemptResults($attemptId)")
        return db.attemptDao().getResults(attemptId).map { it.toDomainResult() }
    }

    /**
     * Attempt rows as a paged list. The key is `(last scanned id, skipped
     * count)` rather than a filtered offset: chip filters drop rows, but each
     * row still carries its index in the *unfiltered* attempt, which is what
     * the review screen numbers questions by. The "All" chip is served
     * directly; the others scan a light column projection and fetch content
     * only for the rows that match.
     */
    fun resultPagingSource(
        attemptId: Long,
        filter: String,
        bookmarked: Set<String>
    ): PagingSource<ResultPageKey, NumberedResult> = ResultsPagingSource(
        attemptId = attemptId,
        filter = filter,
        bookmarked = bookmarked,
        dao = db.attemptDao(),
        map = { it.toDomainResult() }
    )

    suspend fun getResultAggregates(attemptId: Long): ResultAggregates =
        db.attemptDao().getResultAggregates(attemptId)

    suspend fun getResultCategoryBreakdown(attemptId: Long): List<CategoryBreakdownRow> =
        db.attemptDao().getResultCategoryBreakdown(attemptId)

    suspend fun getAllQuestionResults(): List<QuestionResult> {
        Logger.d("REPO", "getAllQuestionResults()")
        return db.attemptDao().getAllResults().map { it.toDomainResult() }
    }

    suspend fun getAttempts(): List<Attempt> {
        Logger.d("REPO", "getAttempts()")
        return db.attemptDao().getAllAttempts().map { it.toDomain() }
    }

    /** Hardest questions, read from the lifetime aggregate (two indexed reads). */
    suspend fun getHardestQuestions(limit: Int = 5): List<QuestionStats.Stat> =
        db.questionStatsDao().getHardestQuestions(limit).map {
            QuestionStats.Stat(
                questionId = it.questionId,
                // The aggregate stores the result row's content JSON verbatim;
                // the in-memory pipeline and the UI both read the plain text,
                // so parse it back (legacy plain values pass through unchanged).
                text = it.text.parseContentElements(mapper.json).textContent,
                attempts = it.attempts,
                correct = it.correct,
                wrong = it.wrong,
                skipped = it.skipped,
                ungraded = it.ungraded
            )
        }

    /** Weakest (paper, category) pairs, read from the lifetime aggregate. */
    suspend fun getWeakestCategories(limit: Int = 5): List<Mastery.CategoryMastery> =
        db.questionStatsDao().getWeakestCategories(limit).map {
            Mastery.CategoryMastery(
                paperId = it.paperId,
                paperTitle = it.paperTitle,
                categoryTitle = it.categoryTitle,
                correct = it.correct,
                graded = it.graded
            )
        }

    /** Paper id -> count of distinct ever-missed questions (for badges). */
    suspend fun getMistakeCounts(): Map<String, Int> =
        // Counted in SQL: one row per paper, not one standing per question ever
        // graded. The library recomputes this after every finished test.
        db.attemptDao().countMistakesByPaper().associate { it.paperId to it.count }

    /** Export DTO of all bookmarked questions, grouped by source paper. */
    suspend fun getBookmarkExportDto(): com.mcqapp.data.io.PaperDto? {
        val ids = db.bookmarkDao().getAll()
        if (ids.isEmpty()) return null
        // Three queries, not three per bookmark.
        val questions = questionStore.getQuestionsByIds(ids)
        if (questions.isEmpty()) return null
        val titles = paperTitlesForCategories(questions.map { it.categoryId }.toSet())
        return com.mcqapp.data.io.BookmarkExport.paperDto(questions, titles)
    }

    /** Ever-missed questions of one paper, most-recently-missed first. */
    /** The mistaken questions of a paper as session refs (content is fetched on demand). */
    suspend fun getMistakenQuestionRefs(
        paperId: String
    ): List<com.mcqapp.data.local.SessionRef> {
        // Scoped to the paper in SQL: the ordering key (rowid) travels with the
        // id list, so the whole bank's standings never have to be materialised.
        val ids = db.attemptDao().getMistakenQuestionIdsForPaper(paperId)
        if (ids.isEmpty()) return emptyList()
        val byId = db.questionDao().getSessionRefsByIdsChunked(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    suspend fun getMistakenQuestions(paperId: String): List<Question> {
        // Same single aggregate as the badge counts; the map lookup filters
        // to this paper and preserves the most-recently-missed-first order.
        val ids = com.mcqapp.domain.Mistakes.mistakenIdsByPaper(
            db.attemptDao().getLatestStandings().map {
                com.mcqapp.domain.MistakeStanding(it.questionId, it.paperId, it.isCorrect, it.rowId)
            }
        )[paperId] ?: return emptyList()
        if (ids.isEmpty()) return emptyList()
        val byId = mapper.toDomainBulk(db.questionDao().getByIdsChunked(ids)).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    private fun QuestionResultEntity.toDomainResult(): QuestionResult {
        // optionsJson arrives from an imported backup and is stored verbatim,
        // so it cannot be trusted to decode. An unguarded throw here made one
        // bad row permanently empty the History screen and mistake badges,
        // with no way to clear it from the UI.
        val options = try {
            mapper.json.decodeFromString(ListSerializer(QuestionOptionDto.serializer()), optionsJson)
        } catch (e: Exception) {
            Logger.w("REPO", "Unreadable optionsJson on result $id: ${e.message}")
            emptyList()
        }
        return QuestionResult(
            attemptId = attemptId,
            dwellSeconds = dwellSeconds,
            questionId = questionId,
            categoryTitle = categoryTitle,
            elements = text.parseContentElements(mapper.json),
            options = options.map {
                QuestionOption(it.id, elements = it.elements, image = it.image)
            },
            correctOptionIds = correctOptionIds.split(",").filter { it.isNotBlank() }.toSet(),
            selectedOptionIds = selectedOptionIds.split(",").filter { it.isNotBlank() }.toSet(),
            isCorrect = isCorrect,
            explanationElements = explanation.parseContentElements(mapper.json),
            explanationImage = explanationImage
        )
    }

    suspend fun deleteAttempt(attemptId: Long) {
        db.attemptDao().deleteById(attemptId)
    }
}

/** One bookmarks page plus whether more rows exist past it. */
private data class BookmarkPage(
    val items: List<com.mcqapp.domain.BookmarkedQuestion>,
    /** The cursor the next page continues strictly after, when [hasMore]. */
    val cursor: BookmarkPageKey?,
    val hasMore: Boolean
)

/** Cursor for [BookmarksPagingSource]: the last item's bookmark key. A page
 * continues strictly after it, so deleting or adding a bookmark anywhere
 * ahead of the cursor cannot duplicate or skip a following page the way
 * `LIMIT/OFFSET` did. */
data class BookmarkPageKey(val afterCreatedAt: Long, val afterQuestionId: String)

private class BookmarksPagingSource(
    private val fetch: suspend (BookmarkPageKey?, Int) -> BookmarkPage
) : PagingSource<BookmarkPageKey, com.mcqapp.domain.BookmarkedQuestion>() {

    override suspend fun load(
        params: LoadParams<BookmarkPageKey>
    ): LoadResult<BookmarkPageKey, com.mcqapp.domain.BookmarkedQuestion> {
        return try {
            val page = fetch(params.key, params.loadSize.coerceAtLeast(1))
            LoadResult.Page(
                data = page.items,
                prevKey = null,
                nextKey = if (page.hasMore) page.cursor else null
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    /**
     * A refresh resumes from the user's reached position, not from the top:
     * with pages keyed by cursor, the key that continues past the loaded page
     * before the anchor is exactly that page's nextKey. Only an anchor inside
     * the first loaded page restarts from the initial load, which for a list
     * that only appends older rows is the same thing the first load already
     * is. The null refresh key used to scroll every invalidation back to the
     * newest bookmark no matter how deep the reader was.
     */
    override fun getRefreshKey(
        state: PagingState<BookmarkPageKey, com.mcqapp.domain.BookmarkedQuestion>
    ): BookmarkPageKey? {
        val anchor = state.anchorPosition ?: return null
        var seen = 0
        var candidate: BookmarkPageKey? = null
        for (page in state.pages) {
            if (anchor >= seen + page.data.size) {
                (page.nextKey as? BookmarkPageKey)?.let { candidate = it }
                seen += page.data.size
            } else break
        }
        return candidate
    }
}

/** Cursor for [ResultsPagingSource]: last scanned row id plus rows skipped. */
data class ResultPageKey(val afterId: Long, val nextIndex: Int)

/** Rows examined per filter-window scan; matched rows are then loaded in full. */
private const val RESULT_SCAN_WINDOW = 200

private class ResultsPagingSource(
    private val attemptId: Long,
    private val filter: String,
    private val bookmarked: Set<String>,
    private val dao: AttemptDao,
    private val map: (QuestionResultEntity) -> QuestionResult
) : PagingSource<ResultPageKey, NumberedResult>() {

    override suspend fun load(
        params: LoadParams<ResultPageKey>
    ): LoadResult<ResultPageKey, NumberedResult> {
        val key = params.key ?: ResultPageKey(afterId = 0L, nextIndex = 0)
        var afterId = key.afterId
        var index = key.nextIndex
        val target = params.loadSize.coerceAtLeast(1)
        val out = mutableListOf<NumberedResult>()
        return try {
            if (filter == ReviewFilters.ALL) {
                val window = dao.getResultsAfter(attemptId, afterId, target)
                window.forEachIndexed { offset, entity ->
                    out += NumberedResult(index + offset, map(entity))
                }
                index += window.size
                if (window.isNotEmpty()) afterId = window.last().id
                val nextKey = if (window.size == target) ResultPageKey(afterId, index) else null
                LoadResult.Page(out, prevKey = null, nextKey = nextKey)
            } else {
                var nextKey: ResultPageKey? = null
                while (out.size < target) {
                    val window = dao.scanResults(attemptId, afterId, RESULT_SCAN_WINDOW)
                    if (window.isEmpty()) {
                        nextKey = null
                        break
                    }
                    val matchedIds = window.filter { matches(it) }.map { it.id }.toHashSet()
                    if (matchedIds.isNotEmpty()) {
                        val full = dao.getResultsByIds(matchedIds.toList()).associateBy { it.id }
                        window.forEachIndexed { offset, scan ->
                            if (scan.id in matchedIds) {
                                full[scan.id]?.let { out += NumberedResult(index + offset, map(it)) }
                            }
                        }
                    }
                    index += window.size
                    afterId = window.last().id
                    nextKey = ResultPageKey(afterId, index)
                    if (window.size < RESULT_SCAN_WINDOW) {
                        nextKey = null
                        break
                    }
                }
                LoadResult.Page(out, prevKey = null, nextKey = nextKey)
            }
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    private fun matches(scan: ResultScan): Boolean = when (filter) {
        ReviewFilters.CORRECT -> scan.isCorrect
        ReviewFilters.WRONG ->
            !scan.isCorrect && scan.selectedOptionIds.isNotBlank() &&
                scan.correctOptionIds.isNotBlank()
        ReviewFilters.SKIPPED ->
            scan.selectedOptionIds.isBlank() && scan.correctOptionIds.isNotBlank()
        ReviewFilters.UNGRADED -> scan.correctOptionIds.isBlank()
        ReviewFilters.SAVED -> scan.questionId in bookmarked
        else -> true
    }

    override fun getRefreshKey(state: PagingState<ResultPageKey, NumberedResult>): ResultPageKey? =
        null
}
