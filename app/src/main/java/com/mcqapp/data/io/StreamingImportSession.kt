package com.mcqapp.data.io

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.AttemptEntity
import com.mcqapp.data.local.BookmarkEntity
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.ContentHashMatch
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
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.passageSearchText
import com.mcqapp.domain.searchableText
import com.mcqapp.domain.toContentJson
import com.mcqapp.util.Logger
import androidx.room.withTransaction
import kotlinx.serialization.json.Json

/**
 * The per-batch writer shared by every streaming import (`StreamingImporter` for
 * JSON, `AnkiApkgImporter` for `.apkg`).
 *
 * It deliberately mirrors `Importer.import` step for step, replacing its
 * whole-file maps (`existingHashes`, `collisionCandidates`, `sameIdRows`,
 * `writtenThisImport`) with per-batch equivalents: a batch asks the database
 * only about its own ids and hashes, so nothing scales with the file. The paper
 * and category bookkeeping is emitted by the feeder — a JSON parser or the Anki
 * reader — which announces a paper, then each category, then that category's
 * questions in batches.
 *
 * Review progress ([onQuestions]' `schedules`) is applied to a question the
 * import accepted, exactly as `Importer` applies the file's `scheduling`: a
 * duplicate is skipped, so re-importing a deck does not overwrite a schedule
 * the user has since moved on with. The JSON streaming reader carries schedules
 * in its tail and warns that they are ignored; the Anki reader hands them in
 * per batch, which is where the whole-file importer had them too.
 */
internal class StreamingImportSession(
    private val db: AppDatabase,
    private val json: Json,
    private val updateAnswersOnDuplicate: Boolean,
    val batchSize: Int,
    private val overrides: ImportOverrides,
    private val onProgress: ((Int) -> Unit)?,
    /**
     * Record where each accepted question landed, so the tail's `scheduling`
     * can be applied once it is read. Off for files with no review progress:
     * the map is per accepted question, and a plain bank does not pay it.
     */
    private val captureSchedules: Boolean = false
) {
    private var newPapers = 0
    private var updatedPapers = 0
    private var newQuestions = 0
    private var updatedQuestions = 0
    private var duplicateQuestions = 0
    private var answersRefreshed = 0
    private var restoredBookmarks = 0
    private var restoredAttempts = 0
    private var restoredSchedules = 0
    private var questionsSeen = 0

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

    /**
     * Where each accepted question landed, for the tail's `scheduling` — which
     * is read after every paper, so it can only be applied at [finish]. Null
     * unless [captureSchedules] was set.
     */
    private val acceptedSchedules: HashMap<String, ScheduleTarget>? =
        if (captureSchedules) HashMap() else null

    /** The paper a question was written under, and its stored content hash. */
    private class ScheduleTarget(val paperId: String, val contentHash: String)

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

    suspend fun onQuestions(
        categoryId: String,
        questions: List<QuestionDto>,
        schedules: Map<String, CardScheduleDto> = emptyMap()
    ) {
        if (questions.isEmpty()) return
        // The feeder hands back the file's category id; the row may have been
        // matched by title or remapped, so resolve it to what we wrote.
        val effective = effectiveCatByRawId[categoryId] ?: categoryId
        currentCategoryId = effective
        db.withTransaction { processBatch(effective, questions, schedules) }
        questionsSeen += questions.size
        onProgress?.invoke(questionsSeen)
    }

    /** Everything after the papers: passages, bookmarks, attempts, schedules. */
    suspend fun finish(tail: LegacyParser.StreamedFile) {
        finishPaper()
        writePassages(tail)
        foldPassageStrays()
        restoreBookmarks(tail)
        restoreAttempts(tail)
        applyTailSchedules(tail)
    }

    fun report() = ImportReport(
        newPapers = newPapers,
        updatedPapers = updatedPapers,
        newQuestions = newQuestions,
        updatedQuestions = updatedQuestions,
        duplicateQuestions = duplicateQuestions,
        answersRefreshed = answersRefreshed,
        restoredBookmarks = restoredBookmarks,
        restoredAttempts = restoredAttempts,
        restoredSchedules = restoredSchedules
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

    private suspend fun processBatch(
        categoryId: String,
        questions: List<QuestionDto>,
        schedules: Map<String, CardScheduleDto>
    ) {
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
                searchText = dto.elements
                    .ifEmpty { listOf(ContentElement.TextElement(dto.text)) }
                    .searchableText,
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
                    val optionElements = o.elements
                        .ifEmpty { listOf(ContentElement.TextElement(o.text)) }
                    OptionEntity(
                        id = o.id,
                        questionId = dto.id,
                        text = optionElements.toContentJson(json),
                        searchText = optionElements.searchableText,
                        image = o.image,
                        sortOrder = optionIndex
                    )
                }
            )
            db.correctAnswerDao().deleteByQuestion(dto.id)
            db.correctAnswerDao().upsertAll(
                dto.correctOptionIds.map { CorrectAnswerEntity(dto.id, it) }
            )

            // Accepted, so a tail `scheduling` entry naming it applies at
            // [finish]: remember the paper it landed under and the hash of its
            // stored bytes.
            acceptedSchedules?.put(dto.id, ScheduleTarget(effectivePaperId, contentHash))

            // Review progress is applied only to a question this import
            // accepted, exactly as the whole-file importer applies the file's
            // `scheduling` map. The hash is the stored bytes' hash, because
            // that is what the study session recomputes: a mismatch reads as
            // edited content and would reset the card on the next load.
            schedules[dto.id]?.let { schedule ->
                db.cardStateDao().upsert(
                    CardStateEntity(
                        paperId = effectivePaperId,
                        questionId = dto.id,
                        ease = schedule.ease,
                        intervalDays = schedule.intervalDays,
                        dueAt = schedule.dueAt,
                        reps = schedule.reps,
                        lapses = schedule.lapses,
                        leech = schedule.leech,
                        lastReviewedAt = schedule.lastReviewedAt,
                        difficulty = schedule.difficulty,
                        stability = schedule.stability,
                        cardState = runCatching {
                            CardStateKind.valueOf(schedule.cardState)
                        }.getOrDefault(CardStateKind.NEW),
                        reviewCount = schedule.reviewCount,
                        contentHash = ContentHash.of(scaledQuestion)
                    )
                )
                restoredSchedules++
            }
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
            val passageElements = passageDto.elements
                .ifEmpty { listOf(ContentElement.TextElement("")) }
            db.passageDao().upsert(
                PassageEntity(
                    id = passageDto.id,
                    categoryId = passageCategory.getValue(passageId),
                    title = passageDto.title,
                    text = passageElements.toContentJson(json),
                    searchText = passageSearchText(passageDto.title, passageElements),
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

    /**
     * The file's `scheduling`, applied to the questions this import accepted —
     * the same rule the whole-file importer uses for `file.scheduling`. The tail
     * is read only after every paper, so the entries are applied here rather
     * than while their batch was written: [acceptedSchedules] remembered where
     * each accepted question landed. An entry naming a question that is not in
     * that map was a skipped duplicate, so it is dropped and a schedule the
     * user has since moved on with is not rolled back.
     */
    private suspend fun applyTailSchedules(tail: LegacyParser.StreamedFile) {
        if (tail.scheduling.isEmpty()) return
        val targets = acceptedSchedules
        if (targets == null) {
            Logger.w("STREAMIMPORT", "Ignoring ${tail.scheduling.size} schedule entries: " +
                "this import did not ask to restore review progress")
            return
        }
        var applied = 0
        var skipped = 0
        for ((questionId, schedule) in tail.scheduling) {
            val target = targets[questionId]
            if (target == null) {
                skipped++
                continue
            }
            db.cardStateDao().upsert(
                CardStateEntity(
                    paperId = target.paperId,
                    questionId = questionId,
                    ease = schedule.ease,
                    intervalDays = schedule.intervalDays,
                    dueAt = schedule.dueAt,
                    reps = schedule.reps,
                    lapses = schedule.lapses,
                    leech = schedule.leech,
                    lastReviewedAt = schedule.lastReviewedAt,
                    difficulty = schedule.difficulty,
                    stability = schedule.stability,
                    cardState = runCatching {
                        CardStateKind.valueOf(schedule.cardState)
                    }.getOrDefault(CardStateKind.NEW),
                    reviewCount = schedule.reviewCount,
                    contentHash = target.contentHash
                )
            )
            restoredSchedules++
            applied++
        }
        if (skipped > 0) {
            Logger.i("STREAMIMPORT", "Restored $applied card states; skipped " +
                "$skipped schedule entries for questions this import did not accept")
        }
    }
}

/**
 * Coerces an imported result's `optionsJson` into something decodable. Same
 * rule the whole-file importer uses; kept here so a streaming restore cannot
 * store a value that throws on read.
 */
internal fun normaliseOptionsJson(json: Json, raw: String): String {
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

/** A stored question that shares a content hash, with the options it needs to be told apart. */
internal data class StoredCandidate(
    val entity: QuestionEntity,
    val options: List<OptionEntity>,
    val correctIds: Set<String>
)
