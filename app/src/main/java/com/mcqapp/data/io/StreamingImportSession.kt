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
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.data.local.QuestionResultEntity
import com.mcqapp.data.local.applyQuestionStats
import com.mcqapp.data.local.getByIdsChunked
import com.mcqapp.data.local.getForQuestionsChunked
import com.mcqapp.data.local.getMatchesByContentHashesChunked
import com.mcqapp.domain.CardStateKind
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

    /**
     * Whether this paper had any question to consider at all — written,
     * refreshed or found to be a duplicate. The whole-file importer's
     * "all questions already exist" skip needs a non-empty set, so a
     * brand-new paper with no questions must not be skipped; see [finishPaper].
     */
    private var sawAnyQuestion = false
    private var currentCategoryId: String? = null
    private var currentMaxSort = -1
    private var maxSortLoaded = false

    /** passageId -> categoryId of its first member, for this paper. */
    private val paperPassageRecords = LinkedHashMap<String, String>()
    /** raw passage id -> the effective id this import resolves it to, first paper wins */
    private val passageEffective = HashMap<String, String>()
    /** effective passage id -> the raw id the file's tail names the passage by */
    private val passageRawByEffective = HashMap<String, String>()
    /** per paper: raw -> effective for this paper's member questions */
    private val passageIdRemap = HashMap<String, String>()

    /**
     * Where each accepted question landed, for the tail's `scheduling` — which
     * is read after every paper, so it can only be applied at [finish]. Null
     * unless [captureSchedules] was set.
     */
    private val acceptedSchedules: HashMap<String, ScheduleTarget>? =
        if (captureSchedules) HashMap() else null

    /** The paper a question was written under, and its stored content hash. */
    private class ScheduleTarget(
        val paperId: String,
        val questionId: String,
        val contentHash: String
    )

    /** Logged once when a file carries schedules but the import did not ask for them. */
    private var warnedNoScheduleCapture = false

    /** Entries naming a question this import did not accept, for the summary. */
    private var skippedSchedules = 0

    /**
     * The file's schedule pages, held from [onSchedules] until [finish] applies
     * them against the settled [acceptedSchedules]. Empty when the import did
     * not ask to restore progress (those pages are dropped and logged there).
     */
    private val bufferedSchedules = mutableListOf<Pair<String, CardScheduleDto>>()

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

    /**
     * One page of the file's `bookmarks`, written as it streams past. Replaces
     * collecting them into the tail: a "bookmark everything" backup is up to a
     * million ids, which used to be a live list until [finish].
     */
    suspend fun onBookmarks(ids: List<String>) {
        if (ids.isEmpty()) return
        db.withTransaction { restoreBookmarksPage(ids) }
    }

    /**
     * One page of the file's `scheduling`, buffered until the questions have
     * settled. Applying at streaming time only worked when a page arrived
     * after the questions it names — the order the app's own writer puts them
     * in but not one the parser owns: a backup-style file whose member order
     * puts `scheduling` before `papers` (or splits it around them) applied
     * against [acceptedSchedules] while it was still filling, skipped every
     * entry, and silently discarded the bank's review progress. The pages wait
     * here and [finish] applies them once the last paper's questions are in
     * the target map; the memory profile is the whole-file importer's, which
     * already holds the `scheduling` map outright.
     */
    suspend fun onSchedules(entries: List<Pair<String, CardScheduleDto>>) {
        if (entries.isEmpty()) return
        val targets = acceptedSchedules
        if (targets == null) {
            if (!warnedNoScheduleCapture) {
                warnedNoScheduleCapture = true
                Logger.w(
                    "STREAMIMPORT",
                    "Ignoring the file's schedule entries: this import did not " +
                        "ask to restore review progress"
                )
            }
            return
        }
        bufferedSchedules += entries
    }

    /** One page of the file's `attempts`, written as it streams past. */
    suspend fun onAttempts(attempts: List<AttemptDto>) {
        if (attempts.isEmpty()) return
        db.withTransaction { restoreAttemptsPage(attempts) }
    }

    /** Everything after the papers: passages, bookmarks, attempts, schedules. */
    suspend fun finish(tail: LegacyParser.StreamedFile) {
        finishPaper()
        writePassages(tail)
        foldPassageStrays()
        // The streamed path already wrote these through the `on*` handlers
        // above, so what is left here is the whole-tail shape (`streamPapers`,
        // the apkg importer) and the end-of-import summary.
        restoreBookmarksPage(tail.bookmarks)
        restoreAttemptsPage(tail.attempts)
        applyTailSchedules(tail)
        if (skippedSchedules > 0) {
            Logger.i(
                "STREAMIMPORT",
                "Restored $restoredSchedules card states; skipped $skippedSchedules " +
                    "schedule entries for questions this import did not accept"
            )
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
        val paperMatch = matchImportedPaper(db, editedHeader.id, editedHeader.title)
        effectivePaperId = paperMatch?.paper?.id ?: editedHeader.id
        isNewPaper = paperMatch == null
        if (paperMatch != null) {
            Logger.d("IMPORT", "Paper '${editedHeader.title}' (${editedHeader.id} -> " +
                "$effectivePaperId): UPDATE" +
                (if (paperMatch.byTitle) " (matched by title)" else "") +
                (if (paperMatch.keepStoredTiming) ", keeping its exam settings" else ""))
        }
        if (isNewPaper) {
            db.paperDao().insertIgnore(
                PaperEntity(
                    id = effectivePaperId,
                    title = editedHeader.title,
                    description = editedHeader.description,
                    durationMinutes = editedHeader.durationMinutes,
                    negativeMarking = sanitizedNegativeMarking(editedHeader.negativeMarking)
                )
            )
        } else if (paperMatch != null && paperMatch.keepStoredTiming) {
            // An empty paper the user made: fill it, but do not rewrite the
            // exam settings they typed. A blank file description must not wipe
            // theirs either.
            db.paperDao().updateTitleAndDescription(
                id = effectivePaperId,
                title = editedHeader.title,
                description = editedHeader.description.ifBlank { paperMatch.paper.description }
            )
        } else {
            db.paperDao().updateFields(
                id = effectivePaperId,
                title = editedHeader.title,
                description = editedHeader.description,
                durationMinutes = editedHeader.durationMinutes,
                negativeMarking = sanitizedNegativeMarking(editedHeader.negativeMarking)
            )
        }
        catsInPaper.clear()
        catsInPaper += db.categoryDao().getByPaper(effectivePaperId)
        effectiveCatByRawId.clear()
        pendingCategories.clear()
        // A re-import always writes its categories; a brand-new paper only
        // does once a question actually needs writing. That reproduces the
        // whole-file importer's "all questions already exist" skip, which
        // leaves the paper shell but no categories. A paper with no questions
        // at all is not that case and writes them in finishPaper instead.
        flushEnabled = !isNewPaper
        needsWrite = false
        sawAnyQuestion = false
        categoryIndex = 0
        currentCategoryId = null
        currentMaxSort = -1
        maxSortLoaded = false
        paperPassageRecords.clear()
        passageIdRemap.clear()
        paperOpen = true
    }

    private suspend fun finishPaper() {
        if (!paperOpen) return
        if (flushEnabled) flushNow()
        if (isNewPaper) newPapers++ else updatedPapers++
        // The whole-file skip is `isNewPaper && incomingHashes.isNotEmpty() &&
        // all already exist`: a brand-new paper with ZERO questions is not
        // skipped, so its categories are still created (and its paper shell is
        // already written by startPaper). Without `sawAnyQuestion` this treated
        // zero questions like all-duplicates and dropped the empty categories.
        val skipped = isNewPaper && !needsWrite && sawAnyQuestion
        if (skipped) {
            // The whole-file importer skips categories entirely when every
            // question already exists; the paper shell stays.
            pendingCategories.clear()
        } else {
            // No question ever turned writes on, so the pending categories are
            // still unwritten: a new paper with no questions writes them now.
            // (After the flush above this is a no-op.)
            flushNow()
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
        // A question of this paper was seen, whether it is written or turns out
        // to be a duplicate: the whole-file importer's skip needs at least one.
        sawAnyQuestion = true
        val scaled = applied.map { it.withDownscaledImages() }
        // Members follow their passage's remap: a question row must point at
        // the passage row this import writes (consolidated_bugs.md A3). The
        // hash follows the written shape, because that is what the study
        // layer recomputes from the row.
        val effective = scaled.map { q ->
            val pid = q.passageId ?: return@map q
            val eff = passageIdRemap.getOrPut(pid) { resolvePassageId(pid) }
            if (eff == pid) q else q.copy(passageId = eff)
        }
        val hashes = effective.map { ContentHash.of(it) }
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
            val scaledQuestion = effective[index]
            val contentHash = hashes[index]
            // Record the passage before any duplicate/refresh decision: the
            // whole-file importer writes passages while scanning a
            // category's questions, so even an all-duplicate category still
            // files its passage under this category. The id recorded is the
            // effective one the member rows carry.
            scaledQuestion.passageId?.let { passageId ->
                paperPassageRecords.putIfAbsent(passageId, currentCategoryId!!)
            }
            val inExisting = contentHash in dbHashes || contentHash in batchWritten

            if (inExisting && updateAnswersOnDuplicate) {
                val candidate = candidates[contentHash]?.firstOrNull()
                val sameContentId = candidate?.entity?.id
                if (sameContentId != null &&
                    ContentHash.nonHashedFieldsDiffer(candidate.entity, candidate.correctIds, scaledQuestion)
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
                    val tagsCsv = dto.tags.joinToString(",")
                    db.questionDao().updateNonHashedFields(
                        id = sameContentId,
                        explanation = scaledQuestion.storedExplanationJson(json),
                        marks = dto.marks,
                        difficulty = dto.difficulty,
                        // One CSV folded in Kotlin for both binds: SQL lower()
                        // is the A-Z fold this bug is.
                        tags = tagsCsv,
                        tagsFolded = tagsCsv.lowercase(),
                        image = scaledQuestion.image,
                        explanationImage = scaledQuestion.explanationImage
                    )
                    answersRefreshed++
                    return@forEachIndexed
                }
            }

            if (inExisting && isSameContentAsAnyStored(contentHash, scaledQuestion, candidates, batchWritten)) {
                val same = sameId[dto.id]
                val changedAnswer = same != null &&
                    ContentHash.nonHashedFieldsDiffer(same.first, same.second, scaledQuestion)
                if (!changedAnswer) {
                    duplicateQuestions++
                    return@forEachIndexed
                }
            }

            val existingQuestion = writtenById[dto.id] ?: sameId[dto.id]?.first
            // A question id is global, but a foreign file may reuse an id the
            // library already stores under a different paper: REPLACE would
            // move that row (and its options and answer key) into this paper.
            // Remap to stay unique, as the whole-file importer's category path
            // does (consolidated_bugs.md A2). A row this import already wrote
            // (writtenById) is adopted with its id, remapped or not.
            val existingOwner = existingQuestion?.let {
                db.categoryDao().getById(it.categoryId)?.paperId
            }
            val ownedElsewhere = existingOwner != null && existingOwner != effectivePaperId
            val effectiveQuestionId = when {
                existingQuestion != null && !ownedElsewhere -> existingQuestion.id
                ownedElsewhere -> {
                    Logger.w(
                        "STREAMIMPORT",
                        "Question id ${dto.id} belongs to paper $existingOwner, " +
                            "remapping for paper $effectivePaperId"
                    )
                    "$effectivePaperId-${dto.id}"
                }
                else -> dto.id
            }
            val questionForWrite = if (effectiveQuestionId == scaledQuestion.id) {
                scaledQuestion
            } else {
                scaledQuestion.copy(id = effectiveQuestionId)
            }
            val resolvedSortOrder = if (existingQuestion == null || ownedElsewhere) {
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
            val entity = questionForWrite.toQuestionEntity(
                categoryId = categoryId,
                sortOrder = resolvedSortOrder,
                contentHash = contentHash,
                json = json
            )
            db.questionDao().upsert(entity)
            writtenById[dto.id] = entity
            batchWritten.getOrPut(contentHash) { mutableListOf() }.add(questionForWrite)

            db.optionDao().deleteByQuestion(effectiveQuestionId)
            db.optionDao().upsertAll(questionForWrite.toOptionEntities(json))
            db.correctAnswerDao().deleteByQuestion(effectiveQuestionId)
            db.correctAnswerDao().upsertAll(
                dto.correctOptionIds.map { CorrectAnswerEntity(effectiveQuestionId, it) }
            )

            // Accepted, so a tail `scheduling` entry naming it applies at
            // [finish]: remember the paper it landed under, the row id it was
            // written under, and the hash of its stored bytes.
            acceptedSchedules?.put(dto.id, ScheduleTarget(effectivePaperId, effectiveQuestionId, contentHash))

            // Review progress is applied only to a question this import
            // accepted, exactly as the whole-file importer applies the file's
            // `scheduling` map. The hash is the stored bytes' hash, because
            // that is what the study session recomputes: a mismatch reads as
            // edited content and would reset the card on the next load.
            schedules[dto.id]?.let { schedule ->
                db.cardStateDao().upsert(
                    CardStateEntity(
                        paperId = effectivePaperId,
                        questionId = effectiveQuestionId,
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
                        contentHash = contentHash
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

    /**
     * The passage id this import writes [rawId] under, decided at the first
     * member reference (the rows themselves are written at [writePassages]).
     * A passage owned by another paper — a stored row, or a claim by an
     * earlier paper of this same import whose row only lands at the tail —
     * is remapped for this paper, as the whole-file importer remaps it
     * (consolidated_bugs.md A3).
     */
    private suspend fun resolvePassageId(rawId: String): String {
        val claimed = passageEffective[rawId]
        val effective = if (claimed != null) {
            if (claimed == rawId || claimed == "$effectivePaperId-$rawId") {
                claimed
            } else {
                Logger.w(
                    "STREAMIMPORT",
                    "Passage id $rawId belongs to another paper, " +
                        "remapping for paper $effectivePaperId"
                )
                "$effectivePaperId-$rawId"
            }
        } else {
            val existing = db.passageDao().getById(rawId)
            val owner = existing?.let { db.categoryDao().getById(it.categoryId)?.paperId }
            if (owner != null && owner != effectivePaperId) {
                Logger.w(
                    "STREAMIMPORT",
                    "Passage id $rawId belongs to paper $owner, " +
                        "remapping for paper $effectivePaperId"
                )
                "$effectivePaperId-$rawId"
            } else {
                rawId
            }
        }
        passageEffective.putIfAbsent(rawId, effective)
        passageRawByEffective[effective] = rawId
        return effective
    }

    private suspend fun writePassages(tail: LegacyParser.StreamedFile) {
        if (passagePaper.isEmpty()) return
        val byId = tail.passages.associateBy { it.id }
        for (passageId in passagePaper.keys.toList()) {
            // The maps are keyed by the effective id; the tail names the
            // passage by its raw file id.
            val rawId = passageRawByEffective[passageId] ?: passageId
            val passageDto = byId[rawId]
            if (passageDto == null) {
                // The whole-file importer only writes a passage that is
                // present in the file's `passages` list.
                passagePaper.remove(passageId)
                passageCategory.remove(passageId)
                continue
            }
            val existing = db.passageDao().getById(passageId)
            db.passageDao().upsert(
                passageDto.withDownscaledImages().toPassageEntity(
                    categoryId = passageCategory.getValue(passageId),
                    sortOrder = existing?.sortOrder ?: 0,
                    json = json
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
                val strays = db.questionDao().getMemberCategoriesByPassage(passageId)
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

    private suspend fun restoreBookmarksPage(ids: List<String>) {
        for (questionId in ids) {
            if (questionId.isNotBlank() && !db.bookmarkDao().isBookmarked(questionId)) {
                db.bookmarkDao().add(BookmarkEntity(questionId))
                restoredBookmarks++
            }
        }
    }

    /** Already-stored attempts, read once for the whole import, never per page. */
    private var knownAttempts: HashSet<Triple<String, Long, Double>>? = null

    private suspend fun restoreAttemptsPage(attempts: List<AttemptDto>) {
        if (attempts.isEmpty()) return
        val known = knownAttempts ?: db.attemptDao().getAllAttempts()
            .mapTo(HashSet()) { Triple(it.paperId, it.finishedAt, it.score) }
            .also { knownAttempts = it }
        for (attempt in attempts) {
            if (!known.add(Triple(attempt.paperId, attempt.finishedAt, attempt.score))) {
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
     * the same rule the whole-file importer uses for `file.scheduling`. The
     * tail is read only after every paper, so the entries are applied here
     * rather than while their batch was written: [acceptedSchedules] remembered
     * where each accepted question landed. An entry naming a question that is
     * not in that map was a skipped duplicate, so it is dropped and a schedule
     * the user has since moved on with is not rolled back. The streamed JSON
     * pages wait in [bufferedSchedules] and join the tail here; the tail's own
     * `scheduling` stays empty on that path (the streaming reader consumes it),
     * so both member orders stream into the same apply.
     */
    private suspend fun applyTailSchedules(tail: LegacyParser.StreamedFile) {
        val pages = bufferedSchedules + tail.scheduling.map { it.key to it.value }
        bufferedSchedules.clear()
        if (pages.isEmpty()) return
        val targets = acceptedSchedules
        if (targets == null) {
            if (!warnedNoScheduleCapture) {
                warnedNoScheduleCapture = true
                Logger.w("STREAMIMPORT", "Ignoring the file's schedule entries: " +
                    "this import did not ask to restore review progress")
            }
            return
        }
        db.withTransaction { applySchedulePage(pages, targets) }
    }

    /** One page of schedule entries for the questions [targets] remembers. */
    private suspend fun applySchedulePage(
        entries: List<Pair<String, CardScheduleDto>>,
        targets: Map<String, ScheduleTarget>
    ) {
        for ((questionId, schedule) in entries) {
            val target = targets[questionId]
            if (target == null) {
                skippedSchedules++
                continue
            }
            db.cardStateDao().upsert(
                CardStateEntity(
                    paperId = target.paperId,
                    questionId = target.questionId,
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
