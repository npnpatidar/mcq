package com.mcqapp.data.io

import java.io.InputStream

/** A preview question plus the id of the category it arrived under. */
data class PreviewQuestion(val categoryId: String, val dto: QuestionDto)

/**
 * What a single pass over a JSON bank can tell the preview screen without
 * holding the bank: the first paper's header, a bounded sample of its
 * questions, and the counts. Everything else is left to the streaming import.
 */
data class ImportPreview(
    val header: LegacyParser.PaperHeader?,
    val paperCount: Int,
    val isBackup: Boolean,
    /** How many attempts the tail carried, for the restore notice. */
    val attemptCount: Int,
    /** The file carries review progress, so a streamed import must apply it. */
    val hasScheduling: Boolean,
    val firstPaperCategoryCount: Int,
    val effectiveCategoryTitle: String,
    val sample: List<PreviewQuestion>,
    /** Every question in the file, across all papers — not just the sample's. */
    val totalQuestions: Int,
    val warnings: List<String>
)

/**
 * Reads the file once, keeping only [sampleSize] questions of the first paper
 * plus the counts. It is [LegacyParser.streamImport] with a collecting sink:
 * the file is never materialised, so a million-question bank costs a bounded
 * sample on the heap.
 */
fun scanImportPreview(
    open: () -> InputStream,
    sampleSize: Int = 500
): ImportPreview {
    require(sampleSize > 0) { "sampleSize must be positive" }
    var paperCount = 0
    var firstHeader: LegacyParser.PaperHeader? = null
    var firstPaperCategoryCount = 0
    var effectiveCategoryTitle: String? = null
    var currentCategoryTitle = "Uncategorized"
    var scheduleCount = 0
    var attemptCount = 0
    val sample = ArrayList<PreviewQuestion>(minOf(sampleSize, 1024))
    var total = 0
    val tail = open().use { input ->
        LegacyParser.streamImport(input, object : LegacyParser.ImportStreamSink {
            override fun onPaper(header: LegacyParser.PaperHeader) {
                paperCount++
                if (paperCount == 1) firstHeader = header
            }

            override fun onCategory(category: CategoryDto) {
                if (paperCount == 1) {
                    firstPaperCategoryCount++
                    currentCategoryTitle = category.title
                }
            }

            override fun onQuestions(categoryId: String, questions: List<QuestionDto>) {
                // The count is the whole file's; the sample is the first
                // paper's, because that is the only paper the preview edits.
                total += questions.size
                if (paperCount != 1) return
                if (effectiveCategoryTitle == null && questions.isNotEmpty()) {
                    effectiveCategoryTitle = currentCategoryTitle
                }
                if (sample.size < sampleSize) {
                    for (q in questions) {
                        if (sample.size >= sampleSize) break
                        sample += PreviewQuestion(categoryId, q)
                    }
                }
            }

            // The tail streams past instead of being collected: the preview
            // only needs counts, so a studied 1M-question backup does not
            // build a million-entry schedule map just to show a notice.
            override fun onSchedules(entries: List<Pair<String, CardScheduleDto>>) {
                scheduleCount += entries.size
            }

            override fun onAttempts(attempts: List<AttemptDto>) {
                attemptCount += attempts.size
            }
        })
    }
    return ImportPreview(
        header = firstHeader,
        paperCount = paperCount,
        isBackup = paperCount > 1 || scheduleCount > 0,
        attemptCount = attemptCount,
        hasScheduling = scheduleCount > 0,
        firstPaperCategoryCount = firstPaperCategoryCount,
        effectiveCategoryTitle = effectiveCategoryTitle ?: "Uncategorized",
        sample = sample,
        totalQuestions = total,
        warnings = tail.warnings
    )
}
