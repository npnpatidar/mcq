package com.mcqapp.data.io

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.CorrectAnswerEntity
import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.util.Logger
import androidx.room.withTransaction

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

        db.withTransaction {
            // Snapshot BEFORE any writes: destructive writes cascade-delete rows,
            // so a snapshot taken later could match hashes of already-deleted rows.
            val existingHashes = db.questionDao().getAll()
                .map { it.contentHash }
                .toHashSet()

            for (paperDto in file.papers) {
                // Paper identity: match by id first, then by title. Bare-array
                // JSON has no stable id (random per parse), so without the
                // title fallback every re-import would create a same-named stub.
                val existingById = db.paperDao().getById(paperDto.id)
                val existingByTitle = if (existingById == null && paperDto.title.isNotBlank()) {
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

                // A brand-new paper whose questions ALL already exist adds nothing:
                // skip creating the shell so the library doesn't fill with stubs.
                val incomingHashes = effectiveCategories.flatMap { cat ->
                    cat.questions.map { q ->
                        ContentHash.of(q.text, q.options.map { it.text }, q.options.map { it.image })
                    }
                }
                if (isNewPaper && incomingHashes.isNotEmpty() &&
                    incomingHashes.all { it in existingHashes }) {
                    duplicateQuestions += incomingHashes.size
                    Logger.i("IMPORT", "Paper '${paperDto.title}' ($effectivePaperId): " +
                        "all ${incomingHashes.size} questions already exist, skipping paper creation")
                    continue
                }

                effectiveCategories.forEachIndexed { categoryIndex, categoryDto ->
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

                    categoryDto.questions.forEach { questionDto ->
                        val contentHash = ContentHash.of(questionDto.text, questionDto.options.map { it.text }, questionDto.options.map { it.image })
                        Logger.d("IMPORT", "  Question id=${questionDto.id}, hash=${contentHash.take(12)}, " +
                            "options=${questionDto.options.size}, correct=${questionDto.correctOptionIds}, " +
                            "text='${questionDto.text.take(60)}'")

                        if (contentHash in existingHashes) {
                            duplicateQuestions++
                            Logger.d("IMPORT", "  Duplicate question skipped: id=${questionDto.id}")
                            return@forEach
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

                        // Hash above covers the ORIGINAL bytes (preview parity);
                        // only stored bytes shrink.
                        val scaled = questionDto.copy(
                            image = ImageDownscale.downscaleDataUri(questionDto.image),
                            explanationImage = ImageDownscale.downscaleDataUri(questionDto.explanationImage),
                            options = questionDto.options.map { o ->
                                o.copy(image = ImageDownscale.downscaleDataUri(o.image))
                            }
                        )
                        db.questionDao().upsert(
                            QuestionEntity(
                                id = questionDto.id,
                                categoryId = effectiveCatId,
                                text = questionDto.text,
                                image = scaled.image,
                                explanation = questionDto.explanation,
                                explanationImage = scaled.explanationImage,
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
                                        scaled.text,
                                        scaled.options.map { it.text },
                                        scaled.options.map { it.image }
                                    )
                                )
                            )
                            restoredSchedules++
                        }

                        db.optionDao().deleteByQuestion(questionDto.id)
                        db.optionDao().upsertAll(
                            scaled.options.mapIndexed { index, o ->
                                OptionEntity(
                                    id = o.id,
                                    questionId = questionDto.id,
                                    text = o.text,
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

}
