package com.mcqapp.data.io

import com.mcqapp.data.export.PaperStream
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.PassageEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.data.local.QuestionPageRow
import com.mcqapp.data.local.countInCategoriesChunked
import com.mcqapp.data.local.getByIdsChunked
import com.mcqapp.data.local.getForQuestionsChunked
import com.mcqapp.domain.parseContentElements
import com.mcqapp.domain.textContent
import com.mcqapp.util.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.OutputStream

class Exporter(private val db: AppDatabase) {

    private val json = Json { prettyPrint = true }

    /**
     * Writes the whole bank to [writer] without ever holding it: papers and
     * categories are serialised one at a time straight from the database, so
     * peak memory is a single category's questions (~a hundred), not the
     * 200 MB graph plus the ~50 MB String the in-memory encode needed. The
     * output is the same schema, just compact — the importer parses either.
     *
     * @param withScheduling the question-keyed review map, for the plain JSON
     *   export. It is written a page at a time, so the bank's `card_state` is
     *   never a map in memory either. The backup zip passes [false]: it carries
     *   the rows verbatim in `study.json`, which this map cannot express, and
     *   writing both would double the review data in the file.
     */
    suspend fun exportAllTo(writer: java.io.BufferedWriter, withScheduling: Boolean = true) {
        Logger.i("EXPORT", "Streaming export of all papers")
        writer.write("{\"version\":1,\"papers\":[")
        val papers = db.paperDao().getAll()
        papers.forEachIndexed { pIndex, paper ->
            if (pIndex > 0) writer.write(",")
            val categories = db.categoryDao().getByPaper(paper.id)
            writer.write(
                "{\"id\":${JsonPrimitive(paper.id).toString()}," +
                    "\"title\":${JsonPrimitive(paper.title)}," +
                    "\"description\":${JsonPrimitive(paper.description)}," +
                    "\"durationMinutes\":${paper.durationMinutes}," +
                    "\"negativeMarking\":${paper.negativeMarking}," +
                    "\"categories\":["
            )
            categories.forEachIndexed { cIndex, category ->
                if (cIndex > 0) writer.write(",")
                writer.write(json.encodeToString(CategoryDto.serializer(), exportCategoryDto(category)))
            }
            writer.write("],\"questions\":[],\"passages\":[")
            // Passages referenced by this paper's members, discovered while
            // the categories streamed past.
            val members = categories.flatMap { cat ->
                db.questionDao().getPassageIdsByCategory(cat.id)
            }.distinct()
            db.passageDao().getByIdsChunked(members).forEachIndexed { pIdx, passage ->
                if (pIdx > 0) writer.write(",")
                writer.write(json.encodeToString(PassageDto.serializer(), passage.toPassageDto()))
            }
            writer.write("]}")
        }
        writer.write("],\"bookmarks\":[")
        // One id at a time, keyset-paged. Encoding the whole list built a
        // second copy of it as a single String, and reading it built a list;
        // re-import restores a set, so the order here is immaterial.
        var firstBookmark = true
        var afterBookmarkId = ""
        while (true) {
            val page = db.bookmarkDao().pageIdsAfter(afterBookmarkId, EXPORT_PAGE)
            if (page.isEmpty()) break
            for (questionId in page) {
                if (!firstBookmark) writer.write(",")
                firstBookmark = false
                writer.write(JsonPrimitive(questionId).toString())
            }
            afterBookmarkId = page.last()
            if (page.size < EXPORT_PAGE) break
        }
        writer.write("],\"attempts\":[")
        val attempts = db.attemptDao().getAllAttempts()
        attempts.forEachIndexed { aIndex, attempt ->
            if (aIndex > 0) writer.write(",")
            val results = db.attemptDao().getResults(attempt.id)
            writer.write(
                json.encodeToString(
                    AttemptDto.serializer(),
                    AttemptDto(
                        paperId = attempt.paperId,
                        title = attempt.title,
                        totalQuestions = attempt.totalQuestions,
                        correctCount = attempt.correctCount,
                        wrongCount = attempt.wrongCount,
                        skippedCount = attempt.skippedCount,
                        score = attempt.score,
                        maxScore = attempt.maxScore,
                        durationSeconds = attempt.durationSeconds,
                        finishedAt = attempt.finishedAt,
                        results = results.map { r ->
                            AttemptResultDto(
                                questionId = r.questionId,
                                categoryTitle = r.categoryTitle,
                                text = r.text,
                                optionsJson = r.optionsJson,
                                correctOptionIds = r.correctOptionIds,
                                selectedOptionIds = r.selectedOptionIds,
                                isCorrect = r.isCorrect,
                                explanation = r.explanation,
                                explanationImage = r.explanationImage,
                                dwellSeconds = r.dwellSeconds
                            )
                        }
                    )
                )
            )
        }
        writer.write("],\"warnings\":[],\"scheduling\":{")
        if (withScheduling) writeScheduling(writer)
        writer.write("},\"passages\":[")
        val allPassages = db.passageDao().getAll()
        allPassages.forEachIndexed { pIndex, passage ->
            if (pIndex > 0) writer.write(",")
            writer.write(json.encodeToString(PassageDto.serializer(), passage.toPassageDto()))
        }
        writer.write("]}")
        writer.flush()
        Logger.i("EXPORT", "Streamed ${papers.size} papers, ${allPassages.size} passages")
    }

    /**
     * The question-keyed review map, one keyset page at a time.
     *
     * This is what the streaming rewrite of the whole-bank export dropped: the
     * plain JSON export wrote `{}` here, so a bank exported and imported lost
     * every schedule, and no test noticed because the method that carried them
     * had no production caller left. Untouched cards are skipped by the same
     * rule the DTO builder uses — no reps and no due date is a question nobody
     * studied — and the rows are written as they are read, never as a map.
     */
    private suspend fun writeScheduling(writer: java.io.BufferedWriter) {
        var afterRowId = Long.MIN_VALUE
        var first = true
        while (true) {
            val page = db.cardStateDao().pageAfterRowId(afterRowId, EXPORT_PAGE)
            if (page.isEmpty()) break
            for (row in page) {
                val state = row.state
                if (state.reps <= 0 && state.dueAt <= 0L) continue
                if (!first) writer.write(",")
                first = false
                writer.write(JsonPrimitive(state.questionId).toString())
                writer.write(":")
                writer.write(json.encodeToString(CardScheduleDto.serializer(), state.toCardScheduleDto()))
            }
            afterRowId = page.last().rowid
        }
    }

    /**
     * The whole bank as a DTO — the backup zip's `content.json`.
     *
     * @param withScheduling the question-keyed review map, for the plain
     *   JSON export. The backup zip leaves it out ([false]): it carries
     *   `card_state` rows verbatim (per paper, with their content hashes),
     *   which this map cannot express, so building it would be waste.
     */
    suspend fun exportAllDto(withScheduling: Boolean = true): McqFileDto {
        Logger.i("EXPORT", "Exporting all papers")
        val papers = db.paperDao().getAll()
        val paperDtos = papers.map { paper -> exportPaperDto(paper) }
        val bookmarks = db.bookmarkDao().getAll()
        val attemptDtos = db.attemptDao().getAllAttempts().map { attempt ->
            val results = db.attemptDao().getResults(attempt.id)
            AttemptDto(
                paperId = attempt.paperId,
                title = attempt.title,
                totalQuestions = attempt.totalQuestions,
                correctCount = attempt.correctCount,
                wrongCount = attempt.wrongCount,
                skippedCount = attempt.skippedCount,
                score = attempt.score,
                maxScore = attempt.maxScore,
                durationSeconds = attempt.durationSeconds,
                finishedAt = attempt.finishedAt,
                results = results.map { r ->
                    AttemptResultDto(
                        questionId = r.questionId,
                        categoryTitle = r.categoryTitle,
                        text = r.text,
                        optionsJson = r.optionsJson,
                        correctOptionIds = r.correctOptionIds,
                        selectedOptionIds = r.selectedOptionIds,
                        isCorrect = r.isCorrect,
                        explanation = r.explanation,
                        explanationImage = r.explanationImage,
                        dwellSeconds = r.dwellSeconds
                    )
                }
            )
        }
        Logger.i("EXPORT", "Exported ${paperDtos.size} papers, ${bookmarks.size} bookmarks, " +
            "${attemptDtos.size} attempts")
        return McqFileDto(
            version = 1,
            papers = paperDtos,
            bookmarks = bookmarks,
            passages = db.passageDao().getAll().map { it.toPassageDto() },
            attempts = attemptDtos,
            // Review progress travels with the JSON backup, keyed by question
            // id. The full per-paper rows ride in the backup zip's study.json
            // instead, which is why they are not built there.
            scheduling = if (withScheduling) {
                db.cardStateDao().getAll()
                    .filter { it.reps > 0 || it.dueAt > 0L }
                    .associate { state -> state.questionId to state.toCardScheduleDto() }
            } else {
                emptyMap()
            }
        )
    }

    /**
     * A whole paper's export as a walk over the database: its header, category
     * list, passages and question count are read up front — all small even for
     * a million questions — and a category's questions are resolved one keyset
     * page at a time, while the writer is on that category.
     */
    suspend fun paperStream(paperId: String): PaperStream? {
        val paper = db.paperDao().getById(paperId) ?: return null
        return streamFor(
            paper,
            db.categoryDao().getByPaper(paperId),
            db.questionDao().countForPaper(paperId)
        )
    }

    /**
     * A category subtree (descendants included) as the same walk, so a
     * category export reads no more than a paper export does. The count is the
     * sum of the selected categories', not the paper's.
     */
    suspend fun categoryStream(paperId: String, rootCategoryIds: Set<String>): PaperStream? {
        val paper = db.paperDao().getById(paperId) ?: return null
        val allCategories = db.categoryDao().getByPaper(paperId)
        val selected = rootCategoryIds.flatMapTo(mutableSetOf()) { rootId ->
            CategoryFilter.subtreeIds(allCategories, rootId)
        }
        val categories = allCategories.filter { it.id in selected }
        val total = db.questionDao().countInCategoriesChunked(categories.map { it.id })
        return streamFor(paper, categories, total)
    }

    /**
     * The bookmarked questions as an exportable paper, walked from the database
     * a page at a time. Grouped by the paper each bookmark came from — the same
     * grouping the in-memory DTO builder used — with the titles and the count
     * read up front (both small) and one page of questions live while its
     * heading is written. A bank with every question bookmarked used to be
     * built as one whole DTO before a byte was written.
     *
     * Null when nothing resolves to a question, the same contract the old
     * builder had.
     */
    suspend fun bookmarkStream(): PaperStream? {
        val total = db.questionDao().countBookmarkedQuestions()
        if (total == 0) return null
        // Passage-member questions ride their passage: without it the PDF and
        // HTML writers drop the member block and DOCX glues it onto an
        // unrelated earlier passage on re-import, so the referenced passages
        // load exactly the way a paper export's do. One query for the ids
        // (distinct in SQL), one chunked read back.
        val passages = passagesOfIds(
            db.questionDao().getPassageIdsOfBookmarkedQuestions()
        )
        return BookmarkStream(db.questionDao().getBookmarkedPaperTitles(), total, passages)
    }

    /**
     * The passages with the given ids, decoded once, for exports assembled
     * outside [DatabaseStream] — the selection export sources its passages
     * here. Callers pass the distinct member ids of the exact questions being
     * exported, so a passage never leaks outside what the user selected.
     */
    suspend fun passagesOfIds(ids: List<String>): List<PassageDto> =
        if (ids.isEmpty()) emptyList()
        else db.passageDao().getByIdsChunked(ids).map { it.toPassageDto() }

    private suspend fun streamFor(
        paper: PaperEntity,
        categories: List<CategoryEntity>,
        totalQuestions: Int
    ): PaperStream =
        DatabaseStream(paper, categories, totalQuestions, passagesOf(categories))

    /**
     * Every passage the given categories' questions reference — the passages a
     * paper export, or a category subset of one, still needs. A member question
     * outside the subset must not pull its passage in, or the export would leak
     * questions the user excluded; a passage whose members are all outside is
     * dropped by the same rule.
     */
    private suspend fun passagesOf(categories: List<CategoryEntity>): List<PassageDto> {
        val memberIds = categories
            .flatMap { db.questionDao().getPassageIdsByCategory(it.id) }
            .distinct()
        if (memberIds.isEmpty()) return emptyList()
        // getByIdsChunked fetches just the needed rows; getAll() read every passage
        // (and decoded its JSON) once per exported paper.
        return db.passageDao().getByIdsChunked(memberIds).map { it.toPassageDto() }
    }

    /**
     * The walk the exporters use. The header, the category list and the
     * passages are held (all small); one keyset page of a category's rows is
     * resolved while the writer is on it and released after. Walking the same
     * stream twice re-reads the pages instead of caching them, which is the
     * point: a document may take two passes, but never the paper's whole
     * question graph.
     */
    private inner class DatabaseStream(
        private val paper: PaperEntity,
        private val categoryRows: List<CategoryEntity>,
        override val totalQuestions: Int,
        override val passages: List<PassageDto>
    ) : PaperStream {

        override val id: String get() = paper.id
        override val title: String get() = paper.title
        override val description: String get() = paper.description
        override val durationMinutes: Int get() = paper.durationMinutes
        override val negativeMarking: Double get() = paper.negativeMarking
        override val categories: List<CategoryDto>
            get() = categoryRows.map { it.toHeader() }
        override val looseQuestions: List<QuestionDto> get() = emptyList()

        override suspend fun walk(
            onCategory: suspend (CategoryDto) -> Unit,
            onQuestion: suspend (QuestionDto) -> Unit
        ) {
            for (category in categoryRows) {
                onCategory(category.toHeader())
                var afterSortOrder = Int.MIN_VALUE
                var afterRowId = Long.MIN_VALUE
                while (true) {
                    val page = db.questionDao().pageByCategory(
                        category.id, afterSortOrder, afterRowId, EXPORT_PAGE
                    )
                    if (page.isEmpty()) break
                    for (dto in page.map { it.question }.toDtoBulk()) onQuestion(dto)
                    val last = page.last()
                    afterSortOrder = last.question.sortOrder
                    afterRowId = last.rowid
                }
            }
        }
    }

    /**
     * The bookmark walk. The headings are the sorted paper titles; each
     * heading's questions are a keyset page over the bookmarks, most recently
     * bookmarked first, released as the writer moves to the next heading.
     * Walking it again re-reads the pages.
     */
    private inner class BookmarkStream(
        private val titles: List<String>,
        override val totalQuestions: Int,
        override val passages: List<PassageDto>
    ) : PaperStream {

        override val id: String get() = BookmarkExport.PAPER_ID
        override val title: String get() = BookmarkExport.PAPER_TITLE
        override val description: String get() = ""
        override val durationMinutes: Int get() = 0
        override val negativeMarking: Double get() = 0.0
        override val categories: List<CategoryDto>
            get() = titles.mapIndexed { index, name ->
                CategoryDto(id = "bookmarked-$index", title = name)
            }
        override val looseQuestions: List<QuestionDto> get() = emptyList()

        override suspend fun walk(
            onCategory: suspend (CategoryDto) -> Unit,
            onQuestion: suspend (QuestionDto) -> Unit
        ) {
            titles.forEachIndexed { index, name ->
                onCategory(CategoryDto(id = "bookmarked-$index", title = name))
                var afterCreatedAt = 0L
                var afterQuestionId: String? = null
                while (true) {
                    val page = db.questionDao().pageBookmarkedQuestionsForTitle(
                        name, afterCreatedAt, afterQuestionId, EXPORT_PAGE
                    )
                    if (page.isEmpty()) break
                    for (dto in page.map { it.question }.toDtoBulk()) onQuestion(dto)
                    val last = page.last()
                    afterCreatedAt = last.bookmarkCreatedAt
                    afterQuestionId = last.question.id
                    // A short page is the last one for this heading.
                    if (page.size < EXPORT_PAGE) break
                }
            }
        }
    }

    private fun CategoryEntity.toHeader() = CategoryDto(id = id, title = title, parentId = parentId)

    private companion object {
        /**
         * Questions resolved per export page. One page is one query for the
         * rows, one for its options and one for its answer keys, all under the
         * bind-variable limit the chunked queries live with.
         */
        const val EXPORT_PAGE = 500
    }

    private suspend fun exportPaperDto(paper: PaperEntity): PaperDto {
        val categories = db.categoryDao().getByPaper(paper.id)
        val dto = PaperDto(
            id = paper.id,
            title = paper.title,
            description = paper.description,
            durationMinutes = paper.durationMinutes,
            negativeMarking = paper.negativeMarking,
            categories = categories.map { exportCategoryDto(it) }
        )
        return dto.copy(passages = passagesOf(categories))
    }

    private suspend fun PassageEntity.toPassageDto(): PassageDto {
        val elements = text.parseContentElements(json)
        return PassageDto(
            id = id,
            title = title,
            elements = elements,
            image = image,
            categoryId = categoryId
        )
    }

    private suspend fun exportCategoryDto(category: CategoryEntity): CategoryDto {
        val questions = db.questionDao().getByCategory(category.id)
        return CategoryDto(
            id = category.id,
            title = category.title,
            parentId = category.parentId,
            questions = questions.toDtoBulk()
        )
    }

    private suspend fun List<QuestionEntity>.toDtoBulk(): List<QuestionDto> {
        if (isEmpty()) return emptyList()
        val ids = map { it.id }
        val optionsByQuestion = db.optionDao().getForQuestionsChunked(ids).groupBy { it.questionId }
        val correctByQuestion = db.correctAnswerDao().getForQuestionsChunked(ids).groupBy { it.questionId }
        return map { entity ->
            val options = optionsByQuestion[entity.id] ?: emptyList()
            val correctIds = correctByQuestion[entity.id]?.map { it.optionId } ?: emptyList()
            val elements = entity.text.parseContentElements(json)
            QuestionDto(
                id = entity.id,
                text = elements.textContent,
                elements = elements,
                image = entity.image,
                options = options.map { option ->
                    val optionElements = option.text.parseContentElements(json)
                    OptionDto(
                        id = option.id,
                        text = optionElements.textContent,
                        elements = optionElements,
                        image = option.image
                    )
                },
                correctOptionIds = correctIds,
                explanation = entity.explanation.parseContentElements(json).textContent,
                explanationElements = entity.explanation.parseContentElements(json),
                explanationImage = entity.explanationImage,
                difficulty = entity.difficulty,
                marks = entity.marks,
                tags = entity.tags.split(",").filter { it.isNotBlank() },
                passageId = entity.passageId
            )
        }
    }
}
