package com.mcqapp.domain

import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.QuestionEntity

/**
 * Storage breakdown: database file size plus per-paper question counts and
 * embedded-image weight (base64 chars ≈ bytes). Pure over entities so the
 * math is unit-tested; the repository only fetches the lists and joins
 * categories to papers.
 */
object StorageInfo {

    data class PaperUsage(
        val paperId: String,
        val title: String,
        val questions: Int,
        val imageChars: Long
    )

    data class Report(
        val dbBytes: Long,
        val papers: Int,
        val questions: Int,
        val attempts: Int,
        val bookmarks: Int,
        val perPaper: List<PaperUsage>
    )

    fun formatBytes(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    fun imageCharsOf(question: QuestionEntity, options: List<OptionEntity>): Long =
        (question.image?.length ?: 0).toLong() +
            (question.explanationImage?.length ?: 0) +
            options.sumOf { (it.image?.length ?: 0).toLong() }

    fun usageForPaper(
        paperId: String,
        title: String,
        questions: List<QuestionEntity>,
        optionsByQuestion: Map<String, List<OptionEntity>>
    ): PaperUsage = PaperUsage(
        paperId = paperId,
        title = title,
        questions = questions.size,
        imageChars = questions.sumOf { q -> imageCharsOf(q, optionsByQuestion[q.id] ?: emptyList()) }
    )
}
