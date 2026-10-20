package com.mcqapp.data.repository

import androidx.room.withTransaction
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.CorrectAnswerEntity
import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.data.local.QuestionOrderRow
import com.mcqapp.data.local.COPY_ID_PROBE
import com.mcqapp.data.local.deleteByQuestionsChunked
import com.mcqapp.data.local.getIdCategoriesByIdsChunked
import com.mcqapp.data.local.getByIdsChunked
import com.mcqapp.data.local.getByCategoriesChunked
import com.mcqapp.data.local.getRefsByCategoriesChunked
import com.mcqapp.data.local.likePrefixPattern
import com.mcqapp.data.local.removeAllChunked
import com.mcqapp.data.local.updateBulkFieldsChunked
import com.mcqapp.data.local.updateCategoryChunked
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.Question
import com.mcqapp.domain.SessionOrder
import com.mcqapp.domain.searchableText
import com.mcqapp.domain.toContentJson
import com.mcqapp.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Question CRUD and bulk operations. Split out of [McqRepository]; the paper
 * delegate calls into it for [getQuestionsForCategories] and [insertCopies]
 * (paper duplication deep-copies questions), and the study delegate reads
 * through [getQuestionsForPaper] and [getQuestion].
 */
internal class QuestionStore(
    private val db: AppDatabase,
    private val mapper: QuestionContentMapper
) {

    fun observeQuestionsForPaper(paperId: String): Flow<List<Question>> =
        // Bulk load (one options + one answers query for all rows) and map off
        // the main thread: the per-row re-fetch version stalled Browse badly.
        // One IN query replaces the per-category fetch; Room's invalidation
        // works per table, so a one-row probe (`observeAnyQuestion`) refreshes
        // this paper's list on any question write exactly like the aggregate
        // did — without re-scanning a million rows to produce a value nobody
        // here reads. Any CATEGORIES write refreshes it too: a category moved
        // in the drawer or inline changes the section order, and the questions
        // follow it.
        kotlinx.coroutines.flow.combine(
            db.questionDao().observeAnyQuestion(),
            db.categoryDao().observeAll()
        ) { _, _ -> Unit }
            .map {
                val started = android.os.SystemClock.elapsedRealtime()
                val categoryIds = db.categoryDao().getByPaper(paperId).map { it.id }
                // An empty IN list is invalid SQL; a paper with no categories has
                // no questions to load.
                if (categoryIds.isEmpty()) return@map emptyList()
                val entities = db.questionDao().getByCategoriesChunked(categoryIds)
                val result = mapper.toDomainBulk(entities)
                Logger.d("REPO", "observeQuestionsForPaper($paperId): " +
                    "mapped ${result.size} questions in " +
                    "${android.os.SystemClock.elapsedRealtime() - started}ms")
                result
            }
            .flowOn(Dispatchers.IO)

    suspend fun getQuestionsForCategories(categoryIds: List<String>): List<Question> {
        Logger.d("REPO", "getQuestionsForCategories(${categoryIds.size} categories)")
        // An empty IN list is invalid SQL; nothing can match anyway.
        if (categoryIds.isEmpty()) return emptyList()
        val entities = db.questionDao().getByCategoriesChunked(categoryIds)
        val result = mapper.toDomainBulk(entities)
        Logger.d("REPO", "getQuestionsForCategories returned ${result.size} questions")
        return result
    }

    suspend fun getQuestionsForPaper(paperId: String): List<Question> {
        Logger.d("REPO", "getQuestionsForPaper($paperId)")
        val categoryIds = db.categoryDao().getByPaper(paperId).map { it.id }
        return getQuestionsForCategories(categoryIds)
    }

    /** Observed refs for browse: the list re-emits on any write (probe, not aggregate). */
    fun observeRefsForPaper(paperId: String): kotlinx.coroutines.flow.Flow<List<com.mcqapp.data.local.QuestionRef>> =
        kotlinx.coroutines.flow.combine(
            db.questionDao().observeAnyQuestion(),
            db.categoryDao().observeAll()
        ) { _, _ -> Unit }
            .map {
                val categoryIds = db.categoryDao().getByPaper(paperId).map { it.id }
                if (categoryIds.isEmpty()) emptyList()
                else db.questionDao().getRefsByCategoriesChunked(categoryIds)
            }
            .flowOn(kotlinx.coroutines.Dispatchers.IO)

    /**
     * The paper's questions as a session order — the ids it is answered by and
     * the passages they move with, never the stem, options or explanation. A
     * session walks this by index and resolves the one question on screen by
     * id.
     */
    suspend fun getOrderForPaper(paperId: String): SessionOrder {
        val categoryIds = db.categoryDao().getByPaper(paperId).map { it.id }
        return loadOrder(categoryIds)
    }

    /**
     * [getOrderForPaper] over an explicit category selection, ordered the way
     * the old paper-wide join ordered it: by `(categories.sortOrder,
     * categories.title)`, then `(questions.sortOrder, rowid)` inside each.
     */
    suspend fun getOrderForCategories(categoryIds: List<String>): SessionOrder {
        val ordered = db.categoryDao().getByIds(categoryIds)
            .sortedWith(compareBy({ it.sortOrder }, { it.title }))
            .map { it.id }
        return loadOrder(ordered)
    }

    /**
     * Streams one category at a time into [SessionOrder.Builder], a keyset
     * page at a time, so the refs are never all in memory at once: a
     * million-question paper is a byte pool and two IntArrays, not a million
     * objects. The keyset cursor is the last row's `(sortOrder, rowid)`, and
     * the sentinel is below every stored value, so one query serves the first
     * page too.
     */
    private suspend fun loadOrder(categoryIds: List<String>): SessionOrder {
        val builder = SessionOrder.Builder()
        for (categoryId in categoryIds) {
            var afterSortOrder = Int.MIN_VALUE
            var afterRowId = Long.MIN_VALUE
            while (true) {
                val page = db.questionDao().pageSessionRefsByCategory(
                    categoryId, afterSortOrder, afterRowId, ORDER_PAGE
                )
                for (row in page) builder.add(row.id, row.passageId)
                if (page.size < ORDER_PAGE) break
                val last = page.last()
                afterSortOrder = last.sortOrder
                afterRowId = last.rowId
            }
        }
        return builder.build()
    }

    /** Domain questions for entities a batch surface already loaded. */
    suspend fun toDomain(entities: List<QuestionEntity>): List<Question> =
        mapper.toDomainBulk(entities)

    suspend fun getQuestion(questionId: String): Question? {
        Logger.d("REPO", "getQuestion($questionId)")
        return db.questionDao().getById(questionId)?.let { mapper.toDomain(it) }
    }

    /**
     * Many questions by id in three queries instead of three per question.
     * Results follow the order of [ids], and unknown ids are dropped, so this
     * replaces a `mapNotNull { getQuestion(it) }` loop.
     */
    suspend fun getQuestionsByIds(ids: List<String>): List<Question> {
        if (ids.isEmpty()) return emptyList()
        val byId = mapper.toDomainBulk(db.questionDao().getByIdsChunked(ids)).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    suspend fun ensurePaperAndCategory(paperId: String, paperTitle: String, categoryId: String, categoryTitle: String) {
        db.paperDao().upsert(PaperEntity(id = paperId, title = paperTitle.ifBlank { "Imported Questions" }))
        db.categoryDao().upsert(
            CategoryEntity(
                id = categoryId,
                paperId = paperId,
                title = categoryTitle.ifBlank { "Uncategorized" }
            )
        )
    }

    suspend fun saveQuestion(question: Question) {
        Logger.d("REPO", "saveQuestion(id=${question.id}, category=${question.categoryId}, " +
            "options=${question.options.size}, correct=${question.correctOptionIds}, " +
            "textLength=${question.text.length})")
        // One transaction: the question row, its options and its answer key
        // must land together, so a crash can never leave options without a key.
        db.withTransaction {
            val existing = db.questionDao().getById(question.id)
            val sortOrder = existing?.sortOrder
                ?: ((db.questionDao().getMaxSortOrder(question.categoryId) ?: -1) + 1)
            Logger.d("REPO", "saveQuestion(id=${question.id}): existing=${existing != null}, " +
                "existingSortOrder=${existing?.sortOrder}, resolvedSortOrder=$sortOrder")
            val contentHash = mapper.contentHashOf(question)
            Logger.d("REPO", "saveQuestion(id=${question.id}): contentHash=${contentHash.take(12)}")
            db.questionDao().upsert(
                QuestionEntity(
                    id = question.id,
                    categoryId = question.categoryId,
                    passageId = question.passageId,
                    text = question.elements.toContentJson(mapper.json),
                    searchText = question.elements.searchableText,
                    searchTextFolded = question.elements.searchableText.lowercase(),
                    image = question.image,
                    explanation = question.explanationElements.toContentJson(mapper.json),
                    explanationImage = question.explanationImage,
                    difficulty = question.difficulty.label,
                    marks = question.marks,
                    tags = question.tags.joinToString(","),
                    tagsFolded = question.tags.joinToString(",").lowercase(),
                    sortOrder = sortOrder,
                    contentHash = contentHash
                )
            )
            Logger.d("REPO", "saveQuestion(id=${question.id}): question row upserted")
            db.optionDao().deleteByQuestion(question.id)
            db.optionDao().upsertAll(
                question.options.mapIndexed { index, o ->
                    OptionEntity(
                        id = o.id,
                        questionId = question.id,
                        text = o.elements.toContentJson(mapper.json),
                        searchText = o.elements.searchableText,
                        searchTextFolded = o.elements.searchableText.lowercase(),
                        image = o.image,
                        sortOrder = index
                    )
                }
            )
            Logger.d("REPO", "saveQuestion(id=${question.id}): ${question.options.size} options written")
            db.correctAnswerDao().deleteByQuestion(question.id)
            db.correctAnswerDao().upsertAll(
                question.correctOptionIds.map {
                    CorrectAnswerEntity(question.id, it)
                }
            )
            Logger.d("REPO", "saveQuestion(id=${question.id}): ${question.correctOptionIds.size} correct answers written - done")
            // A newly assigned (or newly created) member joins the passage's
            // category — a group can only occur together when its members
            // share one — and then moves next to the group's last member, so
            // "questions of a passage occur together" holds in Browse, tests
            // and exports without the user hand-placing it. Contiguous
            // imports are no-ops.
            if (question.passageId != null && existing?.passageId != question.passageId) {
                enforcePassageCategory(question.id, question.passageId)
                placeWithGroup(question.id)
            }
        }
    }

    /**
     * Writes freshly minted clones — question rows, options and keys — in one
     * pass, for callers already inside a transaction. Every candidate id
     * contains "-copy", so the caller's probe proves each one unused: no
     * read-back, no delete-before-write, and one max-sortOrder read per target
     * category instead of one per row (clones append after that category's
     * current tail, in the order given). The bound: one insert per row plus
     * one read per distinct category, all inside the caller's single
     * transaction.
     */
    internal suspend fun insertCopies(clones: List<Question>) {
        if (clones.isEmpty()) return
        // The map pass reads every base before a single row is written, so no
        // base can include a row from this run; the counter then carries each
        // category forward instead of re-reading its tail per clone.
        val nextSortOrder = mutableMapOf<String, Int>()
        val questions = clones.map { q ->
            val sortOrder = nextSortOrder.getOrPut(q.categoryId) {
                (db.questionDao().getMaxSortOrder(q.categoryId) ?: -1) + 1
            }
            nextSortOrder[q.categoryId] = sortOrder + 1
            QuestionEntity(
                id = q.id,
                categoryId = q.categoryId,
                passageId = q.passageId,
                text = q.elements.toContentJson(mapper.json),
                searchText = q.elements.searchableText,
                searchTextFolded = q.elements.searchableText.lowercase(),
                image = q.image,
                explanation = q.explanationElements.toContentJson(mapper.json),
                explanationImage = q.explanationImage,
                difficulty = q.difficulty.label,
                marks = q.marks,
                tags = q.tags.joinToString(","),
                tagsFolded = q.tags.joinToString(",").lowercase(),
                sortOrder = sortOrder,
                contentHash = mapper.contentHashOf(q)
            )
        }
        val options = clones.flatMap { q ->
            q.options.mapIndexed { index, o ->
                OptionEntity(
                    questionId = q.id,
                    id = o.id,
                    text = o.elements.toContentJson(mapper.json),
                    searchText = o.elements.searchableText,
                    searchTextFolded = o.elements.searchableText.lowercase(),
                    image = o.image,
                    sortOrder = index
                )
            }
        }
        val answers = clones.flatMap { q ->
            q.correctOptionIds.map { CorrectAnswerEntity(q.id, it) }
        }
        // Parents first: foreign keys are checked immediately, so options and
        // keys cannot land before the rows they reference.
        db.questionDao().upsertAll(questions)
        db.optionDao().upsertAll(options)
        db.correctAnswerDao().upsertAll(answers)
    }

    suspend fun deleteQuestion(questionId: String) {
        Logger.d("REPO", "deleteQuestion($questionId)")
        // One transaction: the question row, its schedule, its bookmark and
        // its review log must disappear together.
        db.withTransaction {
            db.questionDao().deleteById(questionId)
            db.cardStateDao().deleteByQuestion(questionId)
            db.reviewLogDao().deleteByQuestion(questionId)
            // No FK on bookmarks: backup restores tolerate dangling bookmark ids,
            // so stale ones are removed here instead of by cascade.
            db.bookmarkDao().remove(questionId)
        }
    }

    suspend fun deleteQuestions(questionIds: Collection<String>) {
        Logger.i("REPO", "deleteQuestions(${questionIds.size} ids)")
        // One transaction: a partial bulk delete must not strand rows.
        db.withTransaction {
            questionIds.forEach {
                db.questionDao().deleteById(it)
                db.cardStateDao().deleteByQuestion(it)
            }
            // The log has no FK to questions, so it is swept in the same bulk
            // form rather than per id — the log must not outlive its card.
            if (questionIds.isNotEmpty()) db.reviewLogDao().deleteByQuestionsChunked(questionIds)
            if (questionIds.isNotEmpty()) db.bookmarkDao().removeAllChunked(questionIds)
        }
    }

    /** Deep-copies a question (options, key, explanation, marks) after siblings. */
    suspend fun duplicateQuestion(questionId: String): String? {
        val source = getQuestion(questionId) ?: return null
        // A generated id is `${questionId}-copy(-n)`, so only ids in that
        // namespace can collide; the scoped check replaces materializing every
        // question id in the database.
        val namespace = db.questionDao()
            .getIdsLike(likePrefixPattern("$questionId-copy")).toSet()
        val newId = com.mcqapp.domain.BulkOps.copyId(namespace, questionId)
        saveQuestion(source.copy(id = newId))
        Logger.i("REPO", "duplicateQuestion($questionId -> $newId)")
        return newId
    }

    /** Moves questions into another category, appended after its siblings. */
    suspend fun moveQuestionsToCategory(questionIds: Collection<String>, targetCategoryId: String) {
        Logger.i("REPO", "moveQuestionsToCategory(${questionIds.size} ids -> $targetCategoryId)")
        // One transaction: a partial move must not strand questions, and the
        // cross-paper schedule reset below belongs to the same move.
        db.withTransaction {
            val targetPaperId = db.categoryDao().getById(targetCategoryId)?.paperId
            // A slim id+category read and one UPDATE per chunk replace a full
            // getQuestion/saveQuestion round-trip (a delete-and-reinsert of
            // options and keys with unchanged values) per question.
            val rows = db.questionDao().getIdCategoriesByIdsChunked(questionIds.toList())
            val paperIdByCategory = db.categoryDao().getByIdsChunked(
                rows.map { it.categoryId }.distinct()
            ).associate { it.id to it.paperId }
            db.questionDao().updateCategoryChunked(rows.map { it.id }, targetCategoryId)
            // A schedule is keyed to a paper. Moving a question into another
            // paper makes the old row an orphan that would still inflate the
            // source paper's due count, so start the card over instead.
            if (targetPaperId != null) {
                val crossPaperIds = rows
                    .filter { paperIdByCategory[it.categoryId] != targetPaperId }
                    .map { it.id }
                if (crossPaperIds.isNotEmpty()) {
                    db.cardStateDao().deleteByQuestionsChunked(crossPaperIds)
                    // The reset wipes history too: the old paper's log rows
                    // would otherwise describe a schedule that no longer exists.
                    db.reviewLogDao().deleteByQuestionsChunked(crossPaperIds)
                }
            }
        }
    }

    /** Deep-copies questions into another category (appended after siblings). */
    suspend fun copyQuestionsToCategory(questionIds: Collection<String>, targetCategoryId: String) {
        // One transaction: a partial copy must not leave half the selection behind.
        db.withTransaction {
            // Every copy id contains "-copy", so one probe scan returns exactly
            // the ids a generated candidate can collide with, in place of all
            // question ids in the database; ids minted below join the same set.
            val existing = db.questionDao().getIdsLike(COPY_ID_PROBE).toMutableSet()
            val clones = getQuestionsByIds(questionIds.toList()).map { q ->
                val newId = com.mcqapp.domain.BulkOps.copyId(existing, q.id)
                existing.add(newId)
                q.copy(id = newId, categoryId = targetCategoryId)
            }
            // Minting ids first and writing them in one pass replaces a
            // getQuestion/saveQuestion round-trip per clone: the ids are known
            // fresh, so nothing has to be read back or deleted before writing.
            insertCopies(clones)
            Logger.i("REPO", "copyQuestionsToCategory(${clones.size} ids -> $targetCategoryId)")
        }
    }

    /** Bulk-sets marks/difficulty/tags on questions; null fields are kept. */
    suspend fun bulkUpdateQuestions(
        questionIds: Collection<String>,
        marks: Double?,
        difficulty: Difficulty?,
        tags: List<String>?
    ) {
        // One transaction: a partial bulk edit must not apply to half the selection.
        db.withTransaction {
            // One COALESCE update per chunk replaces a getQuestion/saveQuestion
            // round-trip per question; null binds keep the column, matching the
            // null fields are kept contract. The tags pair travels together:
            // the folded bind is the same CSV Kotlin-lowercased, so a bulk edit
            // to a differently-cased or non-ASCII tag cannot leave the folded
            // twin stale (see MIGRATION_17_18's contract).
            val tagsCsv = tags?.joinToString(",")
            val count = db.questionDao().updateBulkFieldsChunked(
                questionIds,
                marks,
                difficulty?.label,
                tagsCsv,
                tagsCsv
            )
            Logger.i("REPO", "bulkUpdateQuestions($count ids)")
        }
    }

    /** Swaps the positions of two same-category questions. */
    suspend fun swapQuestionOrder(firstId: String, secondId: String): Boolean {
        // One transaction: the two position writes are a single swap, so a
        // crash must not commit the first without the second.
        return db.withTransaction {
            val a = db.questionDao().getById(firstId) ?: return@withTransaction false
            val b = db.questionDao().getById(secondId) ?: return@withTransaction false
            if (a.categoryId != b.categoryId) return@withTransaction false
            db.questionDao().updateSortOrder(a.id, b.sortOrder)
            db.questionDao().updateSortOrder(b.id, a.sortOrder)
            Logger.i("REPO", "swapQuestionOrder(${a.id} <-> ${b.id})")
            true
        }
    }

    /**
     * Rewrites one category's question order to [orderedIds], position by
     * position. The Browse block moves hand over the full new order computed
     * by [com.mcqapp.domain.PassageReorder]; plain two-row swaps keep using
     * [swapQuestionOrder], which never touches the other rows.
     */
    suspend fun renumberQuestions(categoryId: String, orderedIds: List<String>): Boolean {
        if (orderedIds.isEmpty()) return false
        return db.withTransaction {
            val rows = db.questionDao().getOrderByCategory(categoryId).associateBy { it.id }
            if (orderedIds.any { it !in rows } || rows.size != orderedIds.size) {
                Logger.w("REPO", "renumberQuestions($categoryId): id set mismatch, refusing")
                return@withTransaction false
            }
            orderedIds.forEachIndexed { index, id ->
                if (rows.getValue(id).sortOrder != index) {
                    db.questionDao().updateSortOrder(id, index)
                }
            }
            Logger.i("REPO", "renumberQuestions($categoryId): ${orderedIds.size} positions")
            true
        }
    }

    /**
     * Moves [questionId] to sit immediately after [anchorId] in their shared
     * category, renumbering the category so the order stays dense. The
     * category-wide rewrite is a few hundred rows at most — question banks —
     * and keeps every other position meaningful.
     */
    internal suspend fun moveAfterAnchor(questionId: String, anchorId: String): Boolean {
        return db.withTransaction {
            val movedCategory = db.questionDao().getCategoryIdById(questionId)
                ?: return@withTransaction false
            val anchorCategory = db.questionDao().getCategoryIdById(anchorId)
                ?: return@withTransaction false
            if (movedCategory != anchorCategory) return@withTransaction false
            val ordered = mutableListOf<QuestionOrderRow>()
            for (row in db.questionDao().getOrderByCategory(anchorCategory)) {
                if (row.id == questionId) continue
                ordered += row
                if (row.id == anchorId) ordered += QuestionOrderRow(questionId, 0)
            }
            if (ordered.none { it.id == questionId }) return@withTransaction false
            ordered.forEachIndexed { index, row ->
                if (row.sortOrder != index) db.questionDao().updateSortOrder(row.id, index)
            }
            true
        }
    }

    /**
     * Keeps a passage group's members in the passage's category (a group can
     * only occur together when its members share one): a member saved into a
     * passage that lives elsewhere is moved over before being placed with
     * the group. No-op when the categories already match.
     */
    internal suspend fun enforcePassageCategory(questionId: String, passageId: String) {
        val passage = db.passageDao().getById(passageId) ?: return
        val member = db.questionDao().getById(questionId) ?: return
        if (member.categoryId != passage.categoryId) {
            moveQuestionsToCategory(setOf(questionId), passage.categoryId)
        }
    }

    /**
     * Keeps a passage group contiguous when it grows: the member is placed
     * right after the last member of its passage **in the same category**.
     * A passage's first member, or one whose category holds no others,
     * stays where it is. No-op for already-contiguous imports.
     */
    suspend fun placeWithGroup(questionId: String) {
        val member = db.questionDao().getById(questionId) ?: return
        val passageId = member.passageId ?: return
        val anchor = db.questionDao().lastMemberInCategory(passageId, member.categoryId, member.id)
            ?: return
        if (moveAfterAnchor(member.id, anchor.id)) {
            Logger.i("REPO", "placeWithGroup(${member.id} after ${anchor.id})")
        }
    }

    private companion object {
        /**
         * Session rows read per keyset page. An ordinary category is one page
         * (a thousand questions); a category a million rows deep pages
         * through without rescanning.
         */
        const val ORDER_PAGE = 2000
    }
}
