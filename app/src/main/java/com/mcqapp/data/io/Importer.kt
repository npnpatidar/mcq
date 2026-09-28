package com.mcqapp.data.io

import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.CorrectAnswerEntity
import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.util.Logger

data class ImportReport(
    val newPapers: Int,
    val updatedPapers: Int,
    val newQuestions: Int,
    val updatedQuestions: Int
)

class Importer(private val db: AppDatabase) {

    suspend fun import(file: McqFileDto): ImportReport {
        Logger.i("IMPORT", "Starting import of ${file.papers.size} papers")
        var newPapers = 0
        var updatedPapers = 0
        var newQuestions = 0
        var updatedQuestions = 0

        for (paperDto in file.papers) {
            val existingPaper = db.paperDao().getById(paperDto.id)
            if (existingPaper == null) newPapers++ else updatedPapers++
            Logger.d("IMPORT", "Paper '${paperDto.title}' (${paperDto.id}): " +
                "${if (existingPaper == null) "NEW" else "UPDATE"}, " +
                "${paperDto.categories.size} categories")

            db.paperDao().upsert(
                PaperEntity(
                    id = paperDto.id,
                    title = paperDto.title,
                    description = paperDto.description,
                    durationMinutes = paperDto.durationMinutes,
                    negativeMarking = paperDto.negativeMarking
                )
            )

            paperDto.categories.forEachIndexed { categoryIndex, categoryDto ->
                db.categoryDao().upsert(
                    CategoryEntity(
                        id = categoryDto.id,
                        paperId = paperDto.id,
                        title = categoryDto.title,
                        parentId = categoryDto.parentId,
                        sortOrder = categoryIndex
                    )
                )

                for (questionDto in categoryDto.questions) {
                    val existingQuestion = db.questionDao().getById(questionDto.id)
                    if (existingQuestion == null) newQuestions++ else updatedQuestions++
                    Logger.d("IMPORT", "  Question '${questionDto.text.take(50)}' (${questionDto.id}): " +
                        "${if (existingQuestion == null) "NEW" else "UPDATE"}, " +
                        "${questionDto.options.size} options, correct=${questionDto.correctOptionIds}")

                    db.questionDao().upsert(
                        QuestionEntity(
                            id = questionDto.id,
                            categoryId = categoryDto.id,
                            text = questionDto.text,
                            image = questionDto.image,
                            explanation = questionDto.explanation,
                            difficulty = questionDto.difficulty,
                            tags = questionDto.tags.joinToString(",")
                        )
                    )
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
        Logger.i("IMPORT", "Import complete: papers $newPapers new/$updatedPapers updated, " +
            "questions $newQuestions new/$updatedQuestions updated")
        return ImportReport(newPapers, updatedPapers, newQuestions, updatedQuestions)
    }
}
