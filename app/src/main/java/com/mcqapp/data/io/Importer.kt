package com.mcqapp.data.io

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.CorrectAnswerEntity
import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.toContentJson
import com.mcqapp.util.Logger
import androidx.room.withTransaction
import kotlinx.serialization.json.Json

data class ImportReport(
    val newPapers: Int,
    val updatedPapers: Int,
    val newQuestions: Int,
    val updatedQuestions: Int,
    val duplicateQuestions: Int,
    val restoredBookmarks: Int = 0,
    val restoredAttempts: Int = 0,
    /** Cards whose review progress was carried in, e.g. from an Anki package. */
    val restoredSchedules: Int = 0
)

class Importer(private val db: AppDatabase) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param scheduling review progress keyed by question id, applied to the
     * questions this import accepts. It sits beside the file rather than in it
     * because scheduling is per-device progress, not question content: see
     * [CardScheduleDto].
     */
    suspend fun import(
        file: McqFileDto,
        scheduling: Map<String, CardScheduleDto> = emptyMap()
    ): ImportReport {
        // currentTimeMillis (not elapsedRealtime): JVM-testable, and this is log timing only.
        val started = System.currentTimeMillis()
        Logger.i("IMPORT", "Starting import of ${file.papers.size} papers")
        var newPapers = 0
        var updatedPapers = 0
        var newQuestions = 0
        var updatedQuestions = 0
        var duplicateQuestions = 0
        var restoredBookmarks = 0
        var restoredAttempts = 0
        var restoredSchedules = 0

        // Image preparation (Bitmap decode/scale/compress) runs BEFORE the
        // transaction: it is pure CPU work that must not hold the database
        // write lock. The scaled twins mirror the originals 1:1 — hashes and
        // identity still read the original DTOs, only stored bytes shrink.
        val scaledPapers = file.papers.map { it.withDownscaledImages() }

        db.withTransaction {
            // Snapshot BEFORE any writes: destructive writes cascade-delete rows,
            // so a snapshot taken later could match hashes of already-deleted rows.
            val existingHashes = db.questionDao().getAll()
                .map { it.contentHash }
                .toHashSet()

            for ((paperDto, scaledPaper) in file.papers.zip(scaledPapers)) {
                // Paper identity: match by id first. The title fallback is
                // only for ephemeral parser-generated ids (bare-array or
                // id-less files mint a fresh id on every parse, so without it
                // every re-import would create a same-named stub). A stable
                // id that merely shares a title with another paper is a
                // distinct paper and keeps its incoming id.
                val existingById = db.paperDao().getById(paperDto.id)
                val existingByTitle = if (existingById == null &&
                    paperDto.id.startsWith(LegacyParser.EPHEMERAL_PAPER_ID_PREFIX) &&
                    paperDto.title.isNotBlank()
                ) {
                    db.paperDao().getByTitle(paperDto.title)
                } else {
                    null
                }
                val effectivePaperId = existingById?.id ?: existingByTitle?.id ?: paperDto.id
                val isNewPaper = existingById == null && existingByTitle == null
                if (isNewPaper) newPapers++ else updatedPapers++
                Logger.d("IMPORT", "Paper '${paperDto.title}' (${paperDto.id} -> $effectivePaperId): " +
                    "${if (isNewPaper) "NEW" else "UPDATE"}" +
                    (if (existingByTitle != null) " (matched by title)" else "") + ", " +
                    "${paperDto.categories.size} categories")

                // Never REPLACE papers/categories: REPLACE deletes the row and
                // FK CASCADE wipes its questions, which dedup would then skip.
                if (isNewPaper) {
                    db.paperDao().insertIgnore(
                        PaperEntity(
                            id = effectivePaperId,
                            title = paperDto.title,
                            description = paperDto.description,
                            durationMinutes = paperDto.durationMinutes,
                            negativeMarking = paperDto.negativeMarking
                        )
                    )
                } else {
                    db.paperDao().updateFields(
                        id = effectivePaperId,
                        title = paperDto.title,
                        description = paperDto.description,
                        durationMinutes = paperDto.durationMinutes,
                        negativeMarking = paperDto.negativeMarking
                    )
                }

                val effectiveCategories = if (paperDto.categories.isEmpty()) {
                    listOf(CategoryDto(id = "$effectivePaperId-uncat", title = "Uncategorized", questions = paperDto.topLevelQuestions()))
                } else {
                    paperDto.categories
                }
                // Scaled twin of effectiveCategories, in the same order: the
                // synthesized category mirrors the paper's top-level questions.
                val scaledCategories = if (paperDto.categories.isEmpty()) {
                    listOf(CategoryDto(id = "$effectivePaperId-uncat", title = "Uncategorized", questions = scaledPaper.topLevelQuestions()))
                } else {
                    scaledPaper.categories
                }

                // A brand-new paper whose questions ALL already exist adds nothing:
                // skip creating the shell so the library doesn't fill with stubs.
                val incomingHashes = effectiveCategories.flatMap { cat ->
                    cat.questions.map { q ->
                        ContentHash.of(q.text, q.options.map { it.text }, q.options.map { it.image })
                    }
                }
                if (isNewPaper && incomingHashes.isNotEmpty() &&
                    incomingHashes.all { it in existingHashes }) {
                    // Matching hashes alone are not enough to skip: a question
                    // kept under the same id with a fixed answer key (or other
                    // non-hashed field) must still reach the per-question loop
                    // as an update.
                    val incomingIds = effectiveCategories.flatMap { cat ->
                        cat.questions.map { it.id }
                    }
                    val storedById = db.questionDao().getByIds(incomingIds).associateBy { it.id }
                    val storedCorrectById = db.correctAnswerDao().getForQuestions(incomingIds)
                        .groupBy({ it.questionId }, { it.optionId })
                    val hasAnswerOnlyChange = effectiveCategories.flatMap { it.questions }.any { q ->
                        storedById[q.id]?.let { stored ->
                            ContentHash.nonHashedFieldsDiffer(
                                stored,
                                storedCorrectById[q.id].orEmpty().toSet(),
                                q
                            )
                        } == true
                    }
                    if (!hasAnswerOnlyChange) {
                        duplicateQuestions += incomingHashes.size
                        Logger.i("IMPORT", "Paper '${paperDto.title}' ($effectivePaperId): " +
                            "all ${incomingHashes.size} questions already exist, skipping paper creation")
                        continue
                    }
                }

                effectiveCategories.forEachIndexed { categoryIndex, categoryDto ->
                    val scaledCategory = scaledCategories[categoryIndex]
                    // Category identity within the paper: match by id first, then
                    // by title, so re-imports reuse the same category instead of
                    // piling up same-named ones.
                    val catsInPaper = db.categoryDao().getByPaper(effectivePaperId)
                    val matchInPaper = catsInPaper.find { it.id == categoryDto.id }
                        ?: catsInPaper.find { it.title == categoryDto.title }
                    // A category id owned by a DIFFERENT paper must never be
                    // reused: remap to stay unique (else CASCADE wipe).
                    val ownedElsewhere = db.categoryDao().getById(categoryDto.id)
                        ?.takeIf { it.paperId != effectivePaperId }
                    val effectiveCatId = matchInPaper?.id
                        ?: if (ownedElsewhere != null) {
                            Logger.w("IMPORT", "Category id ${categoryDto.id} belongs to paper " +
                                "${ownedElsewhere.paperId}, remapping for paper $effectivePaperId")
                            "$effectivePaperId-${categoryDto.id}"
                        } else {
                            categoryDto.id
                        }

                    if (db.categoryDao().getById(effectiveCatId) == null) {
                        db.categoryDao().insertIgnore(
                            CategoryEntity(
                                id = effectiveCatId,
                                paperId = effectivePaperId,
                                title = categoryDto.title,
                                parentId = categoryDto.parentId,
                                sortOrder = categoryIndex
                            )
                        )
                    } else {
                        db.categoryDao().updateFields(
                            id = effectiveCatId,
                            paperId = effectivePaperId,
                            title = categoryDto.title,
                            parentId = categoryDto.parentId,
                            sortOrder = categoryIndex
                        )
                    }

                    categoryDto.questions.forEachIndexed { questionIndex, questionDto ->
                        val scaledQuestion = scaledCategory.questions[questionIndex]
                        val contentHash = ContentHash.of(questionDto.text, questionDto.options.map { it.text }, questionDto.options.map { it.image })
                        Logger.d("IMPORT", "  Question id=${questionDto.id}, hash=${contentHash.take(12)}, " +
                            "options=${questionDto.options.size}, correct=${questionDto.correctOptionIds}, " +
                            "text='${questionDto.text.take(60)}'")

                        if (contentHash in existingHashes) {
                            // Same text and options, but the hash ignores the
                            // answer key and metadata: the same question id with
                            // a fixed key (or explanation/marks/etc.) is an
                            // update, not a duplicate.
                            val changedAnswer = db.questionDao().getById(questionDto.id)?.let { existing ->
                                ContentHash.nonHashedFieldsDiffer(
                                    existing,
                                    db.correctAnswerDao().getCorrectIds(questionDto.id).toSet(),
                                    questionDto
                                )
                            } == true
                            if (!changedAnswer) {
                                duplicateQuestions++
                                Logger.d("IMPORT", "  Duplicate question skipped: id=${questionDto.id}")
                                return@forEachIndexed
                            }
                            Logger.d("IMPORT", "  Same content hash but changed answer/metadata: updating id=${questionDto.id}")
                        }

                        // New questions append after existing ones; updates keep
                        // their current position so re-imports never reorder.
                        val existingQuestion = db.questionDao().getById(questionDto.id)
                        val resolvedSortOrder = if (existingQuestion == null) {
                            newQuestions++
                            (db.questionDao().getMaxSortOrder(effectiveCatId) ?: -1) + 1
                        } else {
                            updatedQuestions++
                            existingQuestion.sortOrder
                        }
                        Logger.d("IMPORT", "  ${if (existingQuestion == null) "INSERT" else "UPDATE"} " +
                            "id=${questionDto.id}, sortOrder=$resolvedSortOrder")

                        db.questionDao().upsert(
                            QuestionEntity(
                                id = questionDto.id,
                                categoryId = effectiveCatId,
                                text = questionDto.elements
                                    .ifEmpty { listOf(com.mcqapp.domain.ContentElement.TextElement(questionDto.text)) }
                                    .toContentJson(json),
                                image = scaledQuestion.image,
                                explanation = questionDto.explanationElements
                                    .ifEmpty { listOf(com.mcqapp.domain.ContentElement.TextElement(questionDto.explanation)) }
                                    .toContentJson(json),
                                explanationImage = scaledQuestion.explanationImage,
                                difficulty = questionDto.difficulty,
                                marks = questionDto.marks,
                                tags = questionDto.tags.joinToString(","),
                                sortOrder = resolvedSortOrder,
                                contentHash = contentHash
                            )
                        )
                        existingHashes.add(contentHash)

                        // Review progress is applied only to a question this
                        // import accepted. A duplicate is skipped above, so
                        // re-importing a deck does not overwrite a schedule
                        // the user has since moved on with.
                        scheduling[questionDto.id]?.let { schedule ->
                            // The hash of the bytes as stored, because that is
                            // what the study session recomputes it from: a
                            // mismatch reads as edited content and would reset
                            // the card on the next load.
                            db.cardStateDao().upsert(
                                CardStateEntity(
                                    paperId = effectivePaperId,
                                    questionId = questionDto.id,
                                    ease = schedule.ease,
                                    intervalDays = schedule.intervalDays,
                                    dueAt = schedule.dueAt,
                                    reps = schedule.reps,
                                    lapses = schedule.lapses,
                                    leech = schedule.leech,
                                    lastReviewedAt = schedule.lastReviewedAt,
                                    contentHash = ContentHash.of(
                                        scaledQuestion.text,
                                        scaledQuestion.options.map { it.text },
                                        scaledQuestion.options.map { it.image }
                                    )
                                )
                            )
                            restoredSchedules++
                        }

                        db.optionDao().deleteByQuestion(questionDto.id)
                        db.optionDao().upsertAll(
                            scaledQuestion.options.mapIndexed { index, o ->
                                OptionEntity(
                                    id = o.id,
                                    questionId = questionDto.id,
                                    text = o.elements
                                        .ifEmpty { listOf(com.mcqapp.domain.ContentElement.TextElement(o.text)) }
                                        .toContentJson(json),
                                    image = o.image,
                                    sortOrder = index
                                )
                            }
                        )
                        db.correctAnswerDao().deleteByQuestion(questionDto.id)
                        db.correctAnswerDao().upsertAll(
                            questionDto.correctOptionIds.map { CorrectAnswerEntity(questionDto.id, it) }
                        )
                    }
                }
            }

            // Backup payload: bookmarks restore by id (harmless if the
            // question doesn't exist — BookmarksScreen skips missing rows);
            // attempts restore with fresh ids, results remapped onto them.
            for (questionId in file.bookmarks) {
                if (questionId.isNotBlank() && !db.bookmarkDao().isBookmarked(questionId)) {
                    db.bookmarkDao().add(com.mcqapp.data.local.BookmarkEntity(questionId))
                    restoredBookmarks++
                }
            }
            // Same backup re-imported twice must not double history.
            val knownAttempts = db.attemptDao().getAllAttempts()
                .map { Triple(it.paperId, it.finishedAt, it.score) }.toHashSet()
            for (attempt in file.attempts) {
                if (!knownAttempts.add(Triple(attempt.paperId, attempt.finishedAt, attempt.score))) {
                    continue
                }
                val attemptId = db.attemptDao().insertAttempt(
                    com.mcqapp.data.local.AttemptEntity(
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
                db.attemptDao().insertResults(
                    attempt.results.map { r ->
                        com.mcqapp.data.local.QuestionResultEntity(
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
                )
                restoredAttempts++
            }
            if (restoredBookmarks > 0 || restoredAttempts > 0) {
                Logger.i("IMPORT", "Restored $restoredBookmarks bookmarks, $restoredAttempts attempts")
            }
        }
        val elapsed = System.currentTimeMillis() - started
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1048576
        Logger.i("IMPORT", "Import complete: papers $newPapers new/$updatedPapers updated, " +
            "questions $newQuestions new/$updatedQuestions updated, $duplicateQuestions duplicates skipped " +
            "in ${elapsed}ms, heap ${usedMb}MB/${runtime.maxMemory() / 1048576}MB")
        return ImportReport(
            newPapers, updatedPapers, newQuestions, updatedQuestions, duplicateQuestions,
            restoredBookmarks, restoredAttempts, restoredSchedules
        )
    }

/**
 * Coerces an imported result's `optionsJson` into something decodable.
 *
 * The column is read back with a strict decoder, so a backup carrying
 * `"optionsJson": "x"` would otherwise store a value that throws forever.
 * Anything that is not a JSON array of options becomes an empty list.
 */
private fun normaliseOptionsJson(json: Json, raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "[]"
    return try {
        // Only the shape is checked here, so a valid array is stored verbatim
        // with no re-encoding drift. The strict decode happens on read, and is
        // guarded there too.
        if (json.parseToJsonElement(trimmed) is kotlinx.serialization.json.JsonArray) {
            trimmed
        } else {
            Logger.w("IMPORT", "Dropping optionsJson that is not an array")
            "[]"
        }
    } catch (e: Exception) {
        Logger.w("IMPORT", "Dropping unreadable optionsJson: ${e.message}")
        "[]"
    }
}

}

/**
 * Deep-copies the file with every question image downscaled. Pure DTO
 * work: no database calls, safe to run before the import transaction.
 */
internal fun McqFileDto.withDownscaledImages(): McqFileDto = copy(
    papers = papers.map { paper ->
        paper.copy(
            questions = paper.questions.map { it.withDownscaledImages() },
            categories = paper.categories.map { category ->
                category.copy(questions = category.questions.map { it.withDownscaledImages() })
            }
        )
    }
)

private fun PaperDto.withDownscaledImages(): PaperDto = copy(
    questions = questions.map { it.withDownscaledImages() },
    categories = categories.map { category ->
        category.copy(questions = category.questions.map { it.withDownscaledImages() })
    }
)

internal fun QuestionDto.withDownscaledImages(): QuestionDto = copy(
    image = ImageDownscale.downscaleDataUri(image),
    explanationImage = ImageDownscale.downscaleDataUri(explanationImage),
    options = options.map { it.copy(image = ImageDownscale.downscaleDataUri(it.image)) }
)
