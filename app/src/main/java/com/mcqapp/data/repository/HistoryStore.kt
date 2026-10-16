package com.mcqapp.data.repository

import androidx.room.withTransaction
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.AttemptEntity
import com.mcqapp.data.local.BookmarkEntity
import com.mcqapp.data.local.QuestionResultEntity
import com.mcqapp.data.local.getByIdsChunked
import com.mcqapp.data.local.getRefsByIdsChunked
import com.mcqapp.domain.Attempt
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.QuestionResult
import com.mcqapp.domain.parseContentElements
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

    /** Bookmark rows paired with their owning paper, for the bookmarks screen. */
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
            Logger.d("REPO", "saveAttempt stored attemptId=$attemptId with ${results.size} results")
            attemptId
        }
    }

    /**
     * The same save as [saveAttempt], but the questions are ids resolved from
     * the database in batches: a full-paper test of a 169k-question paper
     * would otherwise hold every question's content at once. Only the ordered
     * ids, the tallies and one batch of result rows are in memory.
     */
    suspend fun saveAttemptStreamed(
        paperId: String,
        paperTitle: String,
        questionIdsInOrder: List<String>,
        selections: Map<String, Set<String>>,
        negativeMarking: Double,
        durationSeconds: Long,
        finishedAt: Long,
        dwellSeconds: Map<String, Long> = emptyMap()
    ): Long = db.withTransaction {
        val tally = Tally()
        var stored = 0
        val attemptId = db.attemptDao().insertAttempt(
            AttemptEntity(
                paperId = paperId,
                title = paperTitle,
                totalQuestions = questionIdsInOrder.size,
                correctCount = 0,
                wrongCount = 0,
                skippedCount = 0,
                score = 0.0,
                maxScore = 0.0,
                durationSeconds = durationSeconds,
                finishedAt = finishedAt
            )
        )
        val categoryTitles = categoryTitlesOf(
            questionIdsInOrder.chunked(BATCH).flatMap { ids ->
                db.questionDao().getByIdsChunked(ids).map { it.categoryId }
            }.toSet()
        )
        for (batch in questionIdsInOrder.chunked(BATCH)) {
            // Authored order inside the batch, not the database's.
            val entities = db.questionDao().getByIdsChunked(batch).associateBy { it.id }
            val ordered = batch.mapNotNull { entities[it] }
            val rows = mutableListOf<QuestionResultEntity>()
            scoreBatch(
                mapper.toDomainBulk(ordered), selections, negativeMarking,
                categoryTitles, dwellSeconds, tally, rows
            )
            db.attemptDao().insertResults(rows.map { it.copy(attemptId = attemptId) })
            stored += rows.size
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
            "saveAttemptStreamed(paper=$paperId, questions=${questionIdsInOrder.size}, " +
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

    suspend fun getAttemptResults(attemptId: Long): List<QuestionResult> {
        Logger.d("REPO", "getAttemptResults($attemptId)")
        return db.attemptDao().getResults(attemptId).map { it.toDomainResult() }
    }

    suspend fun getAllQuestionResults(): List<QuestionResult> {
        Logger.d("REPO", "getAllQuestionResults()")
        return db.attemptDao().getAllResults().map { it.toDomainResult() }
    }

    suspend fun getAttempts(): List<Attempt> {
        Logger.d("REPO", "getAttempts()")
        return db.attemptDao().getAllAttempts().map { it.toDomain() }
    }

    /** Paper id -> count of distinct ever-missed questions (for badges). */
    suspend fun getMistakeCounts(): Map<String, Int> =
        // One aggregate in SQL instead of every attempt header and every
        // result row (full option JSON included) in memory.
        com.mcqapp.domain.Mistakes.mistakenIdsByPaper(
            db.attemptDao().getLatestStandings().map {
                com.mcqapp.domain.MistakeStanding(it.questionId, it.paperId, it.isCorrect, it.rowId)
            }
        ).mapValues { it.value.size }

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
    /** The mistaken questions of a paper as refs (content fetched on demand). */
    suspend fun getMistakenQuestionRefs(
        paperId: String
    ): List<com.mcqapp.data.local.QuestionRef> {
        val ids = com.mcqapp.domain.Mistakes.mistakenIdsByPaper(
            db.attemptDao().getLatestStandings().map {
                com.mcqapp.domain.MistakeStanding(it.questionId, it.paperId, it.isCorrect, it.rowId)
            }
        )[paperId] ?: return emptyList()
        if (ids.isEmpty()) return emptyList()
        val byId = db.questionDao().getRefsByIdsChunked(ids).associateBy { it.id }
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
