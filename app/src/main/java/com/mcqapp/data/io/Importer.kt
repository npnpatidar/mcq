package com.mcqapp.data.io

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.CorrectAnswerEntity
import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.util.Logger
import androidx.room.withTransaction
import java.security.MessageDigest

data class ImportReport(
    val newPapers: Int,
    val updatedPapers: Int,
    val newQuestions: Int,
    val updatedQuestions: Int,
    val duplicateQuestions: Int
)

class Importer(private val db: AppDatabase) {

    suspend fun import(file: McqFileDto): ImportReport {
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
                val existingPaper = db.paperDao().getById(paperDto.id)
                if (existingPaper == null) newPapers++ else updatedPapers++
                Logger.d("IMPORT", "Paper '${paperDto.title}' (${paperDto.id}): " +
                    "${if (existingPaper == null) "NEW" else "UPDATE"}, " +
                    "${paperDto.categories.size} categories")

                // Never REPLACE papers/categories: REPLACE deletes the row and
                // FK CASCADE wipes its questions, which dedup would then skip.
                if (existingPaper == null) {
                    db.paperDao().insertIgnore(
                        PaperEntity(
                            id = paperDto.id,
                            title = paperDto.title,
                            description = paperDto.description,
                            durationMinutes = paperDto.durationMinutes,
                            negativeMarking = paperDto.negativeMarking
                        )
                    )
                } else {
                    db.paperDao().updateFields(
                        id = paperDto.id,
                        title = paperDto.title,
                        description = paperDto.description,
                        durationMinutes = paperDto.durationMinutes,
                        negativeMarking = paperDto.negativeMarking
                    )
                }

                val effectiveCategories = if (paperDto.categories.isEmpty()) {
                    listOf(CategoryDto(id = paperDto.id + "-uncat", title = "Uncategorized", questions = paperDto.topLevelQuestions()))
                } else {
                    paperDto.categories
                }

                // A brand-new paper whose questions ALL already exist adds nothing:
                // skip creating the shell so the library doesn't fill with stubs.
                val incomingHashes = effectiveCategories.flatMap { cat ->
                    cat.questions.map { q ->
                        computeHash(q.text, q.options.map { it.text }, q.options.map { it.image })
                    }
                }
                if (existingPaper == null && incomingHashes.isNotEmpty() &&
                    incomingHashes.all { it in existingHashes }) {
                    duplicateQuestions += incomingHashes.size
                    Logger.i("IMPORT", "Paper '${paperDto.title}' (${paperDto.id}): " +
                        "all ${incomingHashes.size} questions already exist, skipping paper creation")
                    continue
                }

                effectiveCategories.forEachIndexed { categoryIndex, categoryDto ->
                    // A category id owned by a DIFFERENT paper would REPLACE that
                    // row and cascade-delete its questions: remap to stay unique.
                    val owningCat = db.categoryDao().getById(categoryDto.id)
                    val effectiveCatId = if (owningCat != null && owningCat.paperId != paperDto.id) {
                        Logger.w("IMPORT", "Category id ${categoryDto.id} belongs to paper " +
                            "${owningCat.paperId}, remapping for paper ${paperDto.id}")
                        paperDto.id + "-" + categoryDto.id
                    } else {
                        categoryDto.id
                    }

                    if (db.categoryDao().getById(effectiveCatId) == null) {
                        db.categoryDao().insertIgnore(
                            CategoryEntity(
                                id = effectiveCatId,
                                paperId = paperDto.id,
                                title = categoryDto.title,
                                parentId = categoryDto.parentId,
                                sortOrder = categoryIndex
                            )
                        )
                    } else {
                        db.categoryDao().updateFields(
                            id = effectiveCatId,
                            paperId = paperDto.id,
                            title = categoryDto.title,
                            parentId = categoryDto.parentId,
                            sortOrder = categoryIndex
                        )
                    }

                    categoryDto.questions.forEachIndexed { qIndex, questionDto ->
                        val contentHash = computeHash(questionDto.text, questionDto.options.map { it.text }, questionDto.options.map { it.image })
                        Logger.d("IMPORT", "  Question id=${questionDto.id}, hash=${contentHash.take(12)}, " +
                            "options=${questionDto.options.size}, correct=${questionDto.correctOptionIds}, " +
                            "text='${questionDto.text.take(60)}'")

                        if (contentHash in existingHashes) {
                            duplicateQuestions++
                            Logger.d("IMPORT", "  Duplicate question skipped: id=${questionDto.id}")
                            return@forEachIndexed
                        }

                        val existingQuestion = db.questionDao().getById(questionDto.id)
                        if (existingQuestion == null) newQuestions++ else updatedQuestions++
                        Logger.d("IMPORT", "  ${if (existingQuestion == null) "INSERT" else "UPDATE"} " +
                            "id=${questionDto.id}, sortOrder=$qIndex")

                        db.questionDao().upsert(
                            QuestionEntity(
                                id = questionDto.id,
                                categoryId = effectiveCatId,
                                text = questionDto.text,
                                image = questionDto.image,
                                explanation = questionDto.explanation,
                                difficulty = questionDto.difficulty,
                                tags = questionDto.tags.joinToString(","),
                                sortOrder = qIndex,
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
        Logger.i("IMPORT", "Import complete: papers $newPapers new/$updatedPapers updated, " +
            "questions $newQuestions new/$updatedQuestions updated, $duplicateQuestions duplicates skipped")
        return ImportReport(newPapers, updatedPapers, newQuestions, updatedQuestions, duplicateQuestions)
    }

    private fun computeHash(text: String, optionTexts: List<String>, optionImages: List<String?>): String {
        val raw = text + "|" + optionTexts.joinToString(",") + "|" + optionImages.joinToString(",")
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
