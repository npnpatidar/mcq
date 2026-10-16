package com.mcqapp.data.io

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.AttemptEntity
import com.mcqapp.data.local.BookmarkEntity
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.CorrectAnswerEntity
import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.PassageEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.data.local.QuestionResultEntity
import com.mcqapp.data.local.applyQuestionStats
import com.mcqapp.data.local.getByIdsChunked
import com.mcqapp.data.local.getForQuestionsChunked
import com.mcqapp.data.local.getMatchesByContentHashesChunked
import com.mcqapp.data.local.ContentHashMatch
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.toContentJson
import com.mcqapp.util.Logger
import androidx.room.withTransaction
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Edits made in the bounded preview, applied while the file streams so the
 * preview never had to hold it. Only the first paper is affected, matching the
 * single-paper preview the streaming path serves. A null field keeps the
 * file's value; [categoryTitle] renames the paper's first category (the
 * preview only offers a category name when the paper has exactly one).
 */
data class ImportOverrides(
    val paperTitle: String? = null,
    val paperDescription: String? = null,
    val durationMinutes: Int? = null,
    val negativeMarking: Double? = null,
    val categoryTitle: String? = null,
    val deletedIds: Set<String> = emptySet(),
    val editedQuestions: Map<String, QuestionDto> = emptyMap()
) {
    val isEmpty: Boolean
        get() = paperTitle == null && paperDescription == null && durationMinutes == null &&
            negativeMarking == null && categoryTitle == null && deletedIds.isEmpty() &&
            editedQuestions.isEmpty()
}

/**
 * Imports a JSON bank by streaming it straight into the database, one batch of
 * questions at a time, so a single paper with a thousand categories and a
 * million questions is never materialised. It is the write-side twin of
 * [LegacyParser.streamImport] and it reproduces [Importer]'s per-question
 * decisions exactly; the only observable differences are the ones the
 * whole-file importer's own quirks create (see [Session.finishPaper] and the
 * hash used by its whole-paper pre-check), and they are documented there.
 *
 * Write atomicity is per batch, not per file: a crashed import leaves the
 * batches it already committed. That is the price of never holding the bank,
 * and it is stated in the import screen's report.
 */
class StreamingImporter(
    private val db: AppDatabase,
    private val updateAnswersOnDuplicate: Boolean = false,
    private val batchSize: Int = LegacyParser.IMPORT_BATCH_SIZE,
    private val overrides: ImportOverrides = ImportOverrides()
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Streams the file [source] opens and writes it. [source] is called exactly
     * once, on the parsing thread, and may be called again by a retry; it must
     * return a fresh stream over the same bytes.
     */
    suspend fun import(source: () -> InputStream): ImportReport = withContext(Dispatchers.IO) {
        val session = Session(db, json, updateAnswersOnDuplicate, batchSize, overrides)
        val queue = ArrayBlockingQueue<StreamEvent>(16)
        val tailRef = AtomicReference<LegacyParser.StreamedFile>()
        val errorRef = AtomicReference<Throwable>()

        val parser = Thread({
            try {
                val tail = source().use { input ->
                    LegacyParser.streamImport(input, object : LegacyParser.ImportStreamSink {
                        override fun onPaper(header: LegacyParser.PaperHeader) =
                            offer(queue, StreamEvent.Paper(header))
                        override fun onCategory(category: CategoryDto) =
                            offer(queue, StreamEvent.Category(category))
                        override fun onQuestions(categoryId: String, questions: List<QuestionDto>) =
                            offer(queue, StreamEvent.Questions(categoryId, questions))
                    }, session.batchSize)
                }
                tailRef.set(tail)
            } catch (t: Throwable) {
                errorRef.set(t)
            } finally {
                offer(queue, StreamEvent.End)
            }
        }, "import-stream")
        parser.isDaemon = true
        parser.start()

        try {
            while (true) {
                val event = try {
                    queue.poll(500, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                }
                if (event == null) {
                    // The parser died without its End sentinel (interrupted); stop.
                    if (!parser.isAlive) break
                    continue
                }
                when (event) {
                    is StreamEvent.Paper -> session.onPaper(event.header)
                    is StreamEvent.Category -> session.onCategory(event.category)
                    is StreamEvent.Questions -> session.onQuestions(event.categoryId, event.questions)
                    StreamEvent.End -> break
                }
            }
        } finally {
            parser.interrupt()
            parser.join(2000)
        }
        errorRef.get()?.let { throw it }
        tailRef.get()?.let { session.finish(it) }
        session.report()
    }

    private fun offer(queue: ArrayBlockingQueue<StreamEvent>, event: StreamEvent) {
        try {
            queue.put(event)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private sealed interface StreamEvent {
        class Paper(val header: LegacyParser.PaperHeader) : StreamEvent
        class Category(val category: CategoryDto) : StreamEvent
        class Questions(val categoryId: String, val questions: List<QuestionDto>) : StreamEvent
        object End : StreamEvent
    }

    private data class StoredCandidate(
        val entity: QuestionEntity,
        val options: List<OptionEntity>,
        val correctIds: Set<String>
    )

    /**
     * The per-import state. Deliberately mirrors [Importer.import] step for
     * step, replacing its whole-file maps (`existingHashes`,
     * `collisionCandidates`, `sameIdRows`, `writtenThisImport`) with
     * per-batch equivalents: a batch asks the database only about its own ids
     * and hashes, so nothing scales with the file.
     */
    private class Session(
        private val db: AppDatabase,
        private val json: Json,
        private val updateAnswersOnDuplicate: Boolean,
        val batchSize: Int,
        private val overrides: ImportOverrides
    ) {
        private var newPapers = 0
        private var updatedPapers = 0
        private var newQuestions = 0
        private var updatedQuestions = 0
        private var duplicateQuestions = 0
        private var answersRefreshed = 0
        private var restoredBookmarks = 0
        private var restoredAttempts = 0

        private var paperOpen = false
        private var paperIndex = 0
        private var effectivePaperId = ""
        private var isNewPaper = false
        private var categoryIndex = 0
        private val catsInPaper = mutableListOf<CategoryEntity>()
        /** Raw file category id -> effective id we wrote, for the current paper. */
        private val effectiveCatByRawId = HashMap<String, String>()
        private val pendingCategories = mutableListOf<CategoryEntity>()
        private var flushEnabled = false
        private var needsWrite = false
        private var currentCategoryId: String? = null
        private var currentMaxSort = -1
        private var maxSortLoaded = false

        /** passageId -> categoryId of its first member, for this paper. */
        private val paperPassageRecords = LinkedHashMap<String, String>()

        /** Written passages, globally, in first-member order. */
        private val passagePaper = LinkedHashMap<String, String>()
        private val passageCategory = LinkedHashMap<String, String>()
        private val seenPassageIds = HashSet<String>()

        suspend fun onPaper(header: LegacyParser.PaperHeader) {
            finishPaper()
            startPaper(header)
        }

        suspend fun onCategory(category: CategoryDto) {
            val paperId = effectivePaperId
            val isFirstCategory = categoryIndex == 0
            val title = if (paperIndex == 1 && isFirstCategory && overrides.categoryTitle != null) {
                overrides.categoryTitle
            } else {
                category.title
            }
            val match = catsInPaper.find { it.id == category.id }
                ?: catsInPaper.find { it.title == title }
            val ownedElsewhere = db.categoryDao().getById(category.id)
                ?.takeIf { it.paperId != paperId }
            val effectiveCatId = match?.id
                ?: if (ownedElsewhere != null) "$paperId-${category.id}" else category.id
            val entity = CategoryEntity(
                id = effectiveCatId,
                paperId = paperId,
                title = title,
                parentId = category.parentId,
                sortOrder = categoryIndex++
            )
            catsInPaper.removeAll { it.id == effectiveCatId }
            catsInPaper += entity
            effectiveCatByRawId[category.id] = effectiveCatId
            if (flushEnabled) writeCategoryRow(entity) else pendingCategories += entity
            currentCategoryId = effectiveCatId
            maxSortLoaded = false
        }

        suspend fun onQuestions(categoryId: String, questions: List<QuestionDto>) {
            if (questions.isEmpty()) return
            // The parser hands back the file's category id; the row may have been
            // matched by title or remapped, so resolve it to what we wrote.
            val effective = effectiveCatByRawId[categoryId] ?: categoryId
            currentCategoryId = effective
            db.withTransaction { processBatch(effective, questions) }
        }

        /** Everything after the papers: passages, bookmarks, attempts. */
        suspend fun finish(tail: LegacyParser.StreamedFile) {
            finishPaper()
            writePassages(tail)
            foldPassageStrays()
            restoreBookmarks(tail)
            restoreAttempts(tail)
            if (tail.scheduling.isNotEmpty()) {
                Logger.w("STREAMIMPORT", "Ignoring ${tail.scheduling.size} schedule entries: " +
                    "files carrying review progress must use the whole-file importer")
            }
        }

        fun report() = ImportReport(
            newPapers = newPapers,
            updatedPapers = updatedPapers,
            newQuestions = newQuestions,
            updatedQuestions = updatedQuestions,
            duplicateQuestions = duplicateQuestions,
            answersRefreshed = answersRefreshed,
            restoredBookmarks = restoredBookmarks,
            restoredAttempts = restoredAttempts
        )

        // --- paper / category -------------------------------------------------

        private suspend fun startPaper(header: LegacyParser.PaperHeader) {
            paperIndex++
            // Preview edits describe the first paper only.
            val editedHeader = if (paperIndex == 1) {
                header.copy(
                    title = overrides.paperTitle ?: header.title,
                    description = overrides.paperDescription ?: header.description,
                    durationMinutes = overrides.durationMinutes ?: header.durationMinutes,
                    negativeMarking = overrides.negativeMarking ?: header.negativeMarking
                )
            } else {
                header
            }
            val existingById = db.paperDao().getById(editedHeader.id)
            val existingByTitle = if (existingById == null &&
                editedHeader.id.startsWith(LegacyParser.EPHEMERAL_PAPER_ID_PREFIX) &&
                editedHeader.title.isNotBlank()
            ) {
                db.paperDao().getByTitle(editedHeader.title)
            } else {
                null
            }
            effectivePaperId = existingById?.id ?: existingByTitle?.id ?: editedHeader.id
            isNewPaper = existingById == null && existingByTitle == null
            if (isNewPaper) {
                db.paperDao().insertIgnore(
                    PaperEntity(
                        id = effectivePaperId,
                        title = editedHeader.title,
                        description = editedHeader.description,
                        durationMinutes = editedHeader.durationMinutes,
                        negativeMarking = editedHeader.negativeMarking
                    )
                )
            } else {
                db.paperDao().updateFields(
                    id = effectivePaperId,
                    title = editedHeader.title,
                    description = editedHeader.description,
                    durationMinutes = editedHeader.durationMinutes,
                    negativeMarking = editedHeader.negativeMarking
                )
            }
            catsInPaper.clear()
            catsInPaper += db.categoryDao().getByPaper(effectivePaperId)
            effectiveCatByRawId.clear()
            pendingCategories.clear()
            // A re-import always writes its categories; a brand-new paper only
            // does once a question actually needs writing. That reproduces the
            // whole-file importer's "all questions already exist" skip, which
            // leaves the paper shell but no categories.
            flushEnabled = !isNewPaper
            needsWrite = false
            categoryIndex = 0
            currentCategoryId = null
            currentMaxSort = -1
            maxSortLoaded = false
            paperPassageRecords.clear()
            paperOpen = true
        }

        private suspend fun finishPaper() {
            if (!paperOpen) return
            if (flushEnabled) flushNow()
            if (isNewPaper) newPapers++ else updatedPapers++
            val skipped = isNewPaper && !needsWrite
            if (skipped) {
                // The whole-file importer skips categories entirely when every
                // question already exists; the paper shell stays.
                pendingCategories.clear()
            } else {
                for ((passageId, categoryId) in paperPassageRecords) {
                    if (seenPassageIds.add(passageId)) {
                        passagePaper[passageId] = effectivePaperId
                        passageCategory[passageId] = categoryId
                    }
                }
            }
            paperOpen = false
        }

        private suspend fun enableWrites() {
            if (flushEnabled) return
            flushEnabled = true
            flushNow()
        }

        private suspend fun flushNow() {
            if (pendingCategories.isEmpty()) return
            for (entity in pendingCategories) writeCategoryRow(entity)
            pendingCategories.clear()
        }

        private suspend fun writeCategoryRow(entity: CategoryEntity) {
            if (db.categoryDao().getById(entity.id) == null) {
                db.categoryDao().insertIgnore(entity)
            } else {
                db.categoryDao().updateFields(
                    entity.id, entity.paperId, entity.title, entity.parentId, entity.sortOrder
                )
            }
        }

        // --- questions --------------------------------------------------------

        private suspend fun processBatch(categoryId: String, questions: List<QuestionDto>) {
            // Apply the bounded preview's edits before anything else: a deleted
            // question must not claim a passage's first member, and an edited
            // one is what the whole-file importer would have been handed.
            val applied = if (paperIndex == 1 && !overrides.isEmpty) {
                questions.mapNotNull { q ->
                    when {
                        q.id in overrides.deletedIds -> null
                        else -> overrides.editedQuestions[q.id] ?: q
                    }
                }
            } else {
                questions
            }
            if (applied.isEmpty()) return
            val scaled = applied.map { it.withDownscaledImages() }
            val hashes = scaled.map { ContentHash.of(it) }
            val matchIds = db.questionDao().getMatchesByContentHashesChunked(hashes)
            val matchedIds = matchIds.map { it.id }.distinct()
            val candidates = candidates(matchIds, matchedIds)
            val dbHashes = matchIds.mapTo(HashSet()) { it.contentHash }

            val ids = applied.map { it.id }
            val idEntities = db.questionDao().getByIdsChunked(ids).associateBy { it.id }
            val idCorrect = db.correctAnswerDao().getForQuestionsChunked(ids)
                .groupBy({ it.questionId }, { it.optionId })
            val sameId = idEntities.mapValues { (k, v) -> v to idCorrect[k].orEmpty().toSet() }

            val batchWritten = HashMap<String, MutableList<QuestionDto>>()
            val writtenById = HashMap<String, QuestionEntity>()

            applied.forEachIndexed { index, dto ->
                val scaledQuestion = scaled[index]
                val contentHash = hashes[index]
                // Record the passage before any duplicate/refresh decision: the
                // whole-file importer writes passages while scanning a
                // category's questions, so even an all-duplicate category still
                // files its passage under this category.
                dto.passageId?.let { passageId ->
                    paperPassageRecords.putIfAbsent(passageId, currentCategoryId!!)
                }
                val inExisting = contentHash in dbHashes || contentHash in batchWritten

                if (inExisting && updateAnswersOnDuplicate) {
                    val candidate = candidates[contentHash]?.firstOrNull()
                    val sameContentId = candidate?.entity?.id
                    if (sameContentId != null &&
                        ContentHash.nonHashedFieldsDiffer(candidate.entity, candidate.correctIds, dto)
                    ) {
                        // A refreshed answer means the whole-file importer would
                        // not have taken its "all questions already exist" skip,
                        // so this paper's categories must be written.
                        enableWrites()
                        needsWrite = true
                        db.correctAnswerDao().deleteByQuestion(sameContentId)
                        db.correctAnswerDao().upsertAll(
                            dto.correctOptionIds.map { CorrectAnswerEntity(sameContentId, it) }
                        )
                        db.questionDao().updateNonHashedFields(
                            id = sameContentId,
                            explanation = dto.explanationElements
                                .ifEmpty { listOf(ContentElement.TextElement(dto.explanation)) }
                                .toContentJson(json),
                            marks = dto.marks,
                            difficulty = dto.difficulty,
                            tags = dto.tags.joinToString(",")
                        )
                        answersRefreshed++
                        return@forEachIndexed
                    }
                }

                if (inExisting && isSameContentAsAnyStored(contentHash, scaledQuestion, candidates, batchWritten)) {
                    val same = sameId[dto.id]
                    val changedAnswer = same != null &&
                        ContentHash.nonHashedFieldsDiffer(same.first, same.second, dto)
                    if (!changedAnswer) {
                        duplicateQuestions++
                        return@forEachIndexed
                    }
                }

                val existingQuestion = writtenById[dto.id] ?: sameId[dto.id]?.first
                val resolvedSortOrder = if (existingQuestion == null) {
                    if (!maxSortLoaded) {
                        currentMaxSort = db.questionDao().getMaxSortOrder(currentCategoryId!!) ?: -1
                        maxSortLoaded = true
                    }
                    newQuestions++
                    ++currentMaxSort
                } else {
                    updatedQuestions++
                    existingQuestion.sortOrder
                }

                // The question row needs its category row first (FK); for a
                // brand-new paper this is also the moment its categories stop
                // being provisional. Covers updates too: an existing question
                // under a new paper's category still needs that category.
                enableWrites()
                needsWrite = true
                val entity = QuestionEntity(
                    id = dto.id,
                    categoryId = categoryId,
                    passageId = dto.passageId,
                    text = dto.elements
                        .ifEmpty { listOf(ContentElement.TextElement(dto.text)) }
                        .toContentJson(json),
                    image = scaledQuestion.image,
                    explanation = dto.explanationElements
                        .ifEmpty { listOf(ContentElement.TextElement(dto.explanation)) }
                        .toContentJson(json),
                    explanationImage = scaledQuestion.explanationImage,
                    difficulty = dto.difficulty,
                    marks = dto.marks,
                    tags = dto.tags.joinToString(","),
                    sortOrder = resolvedSortOrder,
                    contentHash = contentHash
                )
                db.questionDao().upsert(entity)
                writtenById[dto.id] = entity
                batchWritten.getOrPut(contentHash) { mutableListOf() }.add(scaledQuestion)

                db.optionDao().deleteByQuestion(dto.id)
                db.optionDao().upsertAll(
                    scaledQuestion.options.mapIndexed { optionIndex, o ->
                        OptionEntity(
                            id = o.id,
                            questionId = dto.id,
                            text = o.elements
                                .ifEmpty { listOf(ContentElement.TextElement(o.text)) }
                                .toContentJson(json),
                            image = o.image,
                            sortOrder = optionIndex
                        )
                    }
                )
                db.correctAnswerDao().deleteByQuestion(dto.id)
                db.correctAnswerDao().upsertAll(
                    dto.correctOptionIds.map { CorrectAnswerEntity(dto.id, it) }
                )
            }
        }

        private suspend fun candidates(
            matches: List<ContentHashMatch>,
            matchedIds: List<String>
        ): Map<String, List<StoredCandidate>> {
            if (matchedIds.isEmpty()) return emptyMap()
            val entities = db.questionDao().getByIdsChunked(matchedIds).associateBy { it.id }
            val options = db.optionDao().getForQuestionsChunked(matchedIds).groupBy { it.questionId }
            val correct = db.correctAnswerDao().getForQuestionsChunked(matchedIds)
                .groupBy({ it.questionId }, { it.optionId })
            return matches
                .mapNotNull { match -> entities[match.id]?.let { it to match.contentHash } }
                .groupBy({ it.second }, { pair ->
                    StoredCandidate(
                        pair.first,
                        options[pair.first.id].orEmpty(),
                        correct[pair.first.id].orEmpty().toSet()
                    )
                })
        }

        private fun isSameContentAsAnyStored(
            contentHash: String,
            scaledQuestion: QuestionDto,
            candidates: Map<String, List<StoredCandidate>>,
            batchWritten: Map<String, MutableList<QuestionDto>>
        ): Boolean {
            if (batchWritten[contentHash].orEmpty()
                    .any { ContentHash.sameQuestionContent(it, scaledQuestion) }
            ) {
                return true
            }
            return candidates[contentHash].orEmpty().any { candidate ->
                ContentHash.sameQuestionContent(candidate.entity, candidate.options, scaledQuestion)
            }
        }

        // --- tail -------------------------------------------------------------

        private suspend fun writePassages(tail: LegacyParser.StreamedFile) {
            if (passagePaper.isEmpty()) return
            val byId = tail.passages.associateBy { it.id }
            for (passageId in passagePaper.keys.toList()) {
                val passageDto = byId[passageId]
                if (passageDto == null) {
                    // The whole-file importer only writes a passage that is
                    // present in the file's `passages` list.
                    passagePaper.remove(passageId)
                    passageCategory.remove(passageId)
                    continue
                }
                val existing = db.passageDao().getById(passageId)
                db.passageDao().upsert(
                    PassageEntity(
                        id = passageDto.id,
                        categoryId = passageCategory.getValue(passageId),
                        title = passageDto.title,
                        text = passageDto.elements
                            .ifEmpty { listOf(ContentElement.TextElement("")) }
                            .toContentJson(json),
                        image = ImageDownscale.downscaleDataUri(passageDto.image),
                        sortOrder = existing?.sortOrder ?: 0
                    )
                )
            }
        }

        private suspend fun foldPassageStrays() {
            val byPaper = LinkedHashMap<String, MutableList<String>>()
            for ((passageId, paperId) in passagePaper) {
                byPaper.getOrPut(paperId) { mutableListOf() }.add(passageId)
            }
            for (passageIds in byPaper.values) {
                for (passageId in passageIds) {
                    val passage = db.passageDao().getById(passageId) ?: continue
                    val strays = db.questionDao().getMembersByPassage(passageId)
                        .filter { it.categoryId != passage.categoryId }
                    if (strays.isEmpty()) continue
                    Logger.i("STREAMIMPORT", "Folding ${strays.size} passage member(s) of " +
                        "$passageId into ${passage.categoryId}")
                    var next = (db.questionDao().getMaxSortOrder(passage.categoryId) ?: -1) + 1
                    for (stray in strays) {
                        db.questionDao().updateCategory(listOf(stray.id), passage.categoryId)
                        db.questionDao().updateSortOrder(stray.id, next++)
                    }
                }
            }
        }

        private suspend fun restoreBookmarks(tail: LegacyParser.StreamedFile) {
            for (questionId in tail.bookmarks) {
                if (questionId.isNotBlank() && !db.bookmarkDao().isBookmarked(questionId)) {
                    db.bookmarkDao().add(BookmarkEntity(questionId))
                    restoredBookmarks++
                }
            }
        }

        private suspend fun restoreAttempts(tail: LegacyParser.StreamedFile) {
            if (tail.attempts.isEmpty()) return
            val knownAttempts = db.attemptDao().getAllAttempts()
                .map { Triple(it.paperId, it.finishedAt, it.score) }.toHashSet()
            for (attempt in tail.attempts) {
                if (!knownAttempts.add(Triple(attempt.paperId, attempt.finishedAt, attempt.score))) {
                    continue
                }
                val attemptId = db.attemptDao().insertAttempt(
                    AttemptEntity(
                        paperId = attempt.paperId,
                        title = attempt.title,
                        totalQuestions = attempt.totalQuestions,
                        correctCount = attempt.correctCount,
                        wrongCount = attempt.wrongCount,
                        skippedCount = attempt.skippedCount,
                        score = attempt.score,
                        maxScore = attempt.maxScore,
                        durationSeconds = attempt.durationSeconds,
                        finishedAt = attempt.finishedAt
                    )
                )
                val restoredRows = attempt.results.map { r ->
                    QuestionResultEntity(
                        attemptId = attemptId,
                        questionId = r.questionId,
                        categoryTitle = r.categoryTitle,
                        text = r.text,
                        optionsJson = normaliseOptionsJson(json, r.optionsJson),
                        correctOptionIds = r.correctOptionIds,
                        selectedOptionIds = r.selectedOptionIds,
                        isCorrect = r.isCorrect,
                        explanation = r.explanation,
                        explanationImage = r.explanationImage,
                        dwellSeconds = r.dwellSeconds
                    )
                }
                db.attemptDao().insertResults(restoredRows)
                db.questionStatsDao().applyQuestionStats(
                    attempt.paperId, attempt.title, attempt.finishedAt, restoredRows
                )
                restoredAttempts++
            }
        }
    }
}

/**
 * Coerces an imported result's `optionsJson` into something decodable. Same
 * rule the whole-file importer uses; kept here so a streaming restore cannot
 * store a value that throws on read.
 */
private fun normaliseOptionsJson(json: Json, raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "[]"
    return try {
        if (json.parseToJsonElement(trimmed) is kotlinx.serialization.json.JsonArray) {
            trimmed
        } else {
            Logger.w("STREAMIMPORT", "Dropping optionsJson that is not an array")
            "[]"
        }
    } catch (e: Exception) {
        Logger.w("STREAMIMPORT", "Dropping unreadable optionsJson: ${e.message}")
        "[]"
    }
}
