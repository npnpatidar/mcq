package com.mcqapp.data.io

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
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
    val duplicateQuestions: Int
)

class Importer(private val db: AppDatabase) {

    suspend fun import(file: McqFileDto): ImportReport {
        val started = android.os.SystemClock.elapsedRealtime()
        Logger.i("IMPORT", "Starting import of ${file.papers.size} papers")
        var newPapers = 0
        var updatedPapers = 0
        var newQuestions = 0
        var updatedQuestions = 0
        var duplicateQuestions = 0

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

                        db.questionDao().upsert(
                            QuestionEntity(
                                id = questionDto.id,
                                categoryId = effectiveCatId,
                                text = questionDto.text,
                                image = questionDto.image,
                                explanation = questionDto.explanation,
                                explanationImage = questionDto.explanationImage,
                                difficulty = questionDto.difficulty,
                                tags = questionDto.tags.joinToString(","),
                                sortOrder = resolvedSortOrder,
                                contentHash = contentHash
                            )
                        )
                        existingHashes.add(contentHash)

                        db.optionDao().deleteByQuestion(questionDto.id)
                        db.optionDao().upsertAll(
                            questionDto.options.mapIndexed { index, o ->
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
        }
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1048576
        Logger.i("IMPORT", "Import complete: papers $newPapers new/$updatedPapers updated, " +
            "questions $newQuestions new/$updatedQuestions updated, $duplicateQuestions duplicates skipped " +
            "in ${elapsed}ms, heap ${usedMb}MB/${runtime.maxMemory() / 1048576}MB")
        return ImportReport(newPapers, updatedPapers, newQuestions, updatedQuestions, duplicateQuestions)
    }

}
