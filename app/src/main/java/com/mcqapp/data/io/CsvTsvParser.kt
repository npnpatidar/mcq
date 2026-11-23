package com.mcqapp.data.io

import com.mcqapp.domain.ContentElement

/**
 * A small, hand-written RFC-4180 reader for question banks.
 *
 * The app deliberately has no CSV dependency, so this implements the parts of
 * RFC-4180 that matter for a bank: a quoted field may contain the delimiter,
 * line breaks and doubled quotes (`""`); records end at an unquoted newline;
 * both `\n` and `\r\n` (and a bare `\r`) are accepted. A leading UTF-8 BOM is
 * stripped. Delimiter is chosen from the header: a header with more tabs than
 * commas is TSV, otherwise CSV.
 *
 * See https://www.rfc-editor.org/rfc/rfc4180 (fields, quoting, CRLF).
 */
object CsvTsvParser {

    /** Column order when the file has no header row. */
    val DEFAULT_COLUMNS = listOf(
        "question", "a", "b", "c", "d", "answer", "explanation", "tags"
    )

    data class Result(
        val file: McqFileDto,
        /** Row-level, already-human warnings (1-based row numbers). */
        val warnings: List<String>,
        val skippedRows: Int
    )

    /**
     * Whether [text] looks like a bank of this format rather than JSON.
     * A single well-formed row with a question and an answer is enough.
     */
    fun looksLikeCsvTsv(text: String): Boolean {
        val trimmed = stripBom(text).trimStart()
        if (trimmed.isEmpty() || trimmed.first() == '{' || trimmed.first() == '[') return false
        val rows = parseRows(trimmed, detectDelimiter(trimmed))
        val first = rows.firstOrNull() ?: return false
        // A header row counts; so does a positional first row with a question.
        return hasHeader(first) || (first.size >= 4 && first[0].isNotBlank())
    }

    /**
     * Comma or tab? Counted on the first line outside quotes: a TSV bank needs
     * tabs as separators, but a tab inside a CSV field must stay content.
     */
    private fun detectDelimiter(text: String): Char {
        val line = text.substringBefore('\n').substringBefore('\r')
        var commas = 0
        var tabs = 0
        var quoted = false
        var i = 0
        while (i < line.length) {
            when {
                line[i] == '"' -> {
                    if (quoted && i + 1 < line.length && line[i + 1] == '"') i++
                    else quoted = !quoted
                }
                !quoted && line[i] == ',' -> commas++
                !quoted && line[i] == '\t' -> tabs++
            }
            i++
        }
        return if (tabs > commas) '\t' else ','
    }

    /** Splits into records and fields, honouring RFC-4180 quoting. */
    fun parseRows(text: String, delimiter: Char = ','): List<List<String>> {
        val records = ArrayList<List<String>>()
        var field = StringBuilder()
        var record = ArrayList<String>()
        var quoted = false
        var i = 0
        val input = stripBom(text)
        while (i < input.length) {
            val c = input[i]
            when {
                quoted -> when (c) {
                    '"' -> {
                        if (i + 1 < input.length && input[i + 1] == '"') {
                            field.append('"')
                            i++
                        } else {
                            quoted = false
                        }
                    }
                    else -> field.append(c)
                }
                c == '"' && field.isEmpty() -> quoted = true
                c == delimiter -> {
                    record += field.toString().trim()
                    field.setLength(0)
                }
                c == '\r' || c == '\n' -> {
                    record += field.toString().trim()
                    field.setLength(0)
                    // Treat \r\n as one record separator.
                    if (c == '\r' && i + 1 < input.length && input[i + 1] == '\n') i++
                    if (record.any { it.isNotEmpty() }) records += record.toList()
                    record = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) {
            record += field.toString().trim()
            if (record.any { it.isNotEmpty() }) records += record.toList()
        }
        return records
    }

    /**
     * Parses [text] into the same DTO the JSON importer reads, so the existing
     * preview, dedup and importer paths are reused unchanged.
     *
     * [paperTitle] overrides the generated title (a picked file's name).
     */
    fun parse(
        text: String,
        paperTitle: String? = null,
        categoryTitle: String? = null,
        rowWarning: (Int) -> String = { "Invalid answer in row $it." }
    ): Result {
        val delimiter = detectDelimiter(text)
        val rows = parseRows(text, delimiter)
        if (rows.isEmpty()) return Result(emptyFile(), emptyList(), 0)
        val headerCells = rows.first()

        val hasHeader = hasHeader(headerCells)
        val columns = if (hasHeader) headerCells.map { normalizeHeader(it) } else DEFAULT_COLUMNS
        fun cell(row: List<String>, name: String): String {
            val index = columns.indexOf(name)
            if (index < 0) return ""
            return row.getOrNull(index).orEmpty()
        }

        val paperId = "${LegacyParser.EPHEMERAL_PAPER_ID_PREFIX}csv-" +
            System.currentTimeMillis().toString(36)
        val categoryId = "$paperId-cat"
        val questions = ArrayList<QuestionDto>()
        val warnings = ArrayList<String>()
        var skipped = 0
        val dataRows = if (hasHeader) rows.drop(1) else rows
        dataRows.forEachIndexed { index, row ->
            val rowNumber = index + if (hasHeader) 2 else 1
            val questionText = cell(row, "question")
            if (questionText.isBlank()) {
                skipped++
                return@forEachIndexed
            }
            val optionTexts = listOf("a", "b", "c", "d").map { cell(row, it) }
            // Keep each option's original a-d position. `mapIndexedNotNull`
            // compresses the indices when a blank option is dropped, while the
            // answer key is written against the original letters — so a blank
            // "a" would otherwise make "b" select "c", or reject "d" entirely.
            val optionsByPosition = optionTexts.mapIndexedNotNull { i, text ->
                if (text.isBlank()) null else i to OptionDto(
                    id = "csv-${index + 1}-${('a'.code + i).toChar()}",
                    text = text,
                    elements = listOf(ContentElement.TextElement(text))
                )
            }.toMap()
            val options = optionsByPosition.values.toList()
            if (options.isEmpty()) {
                // A row with no options is a flashcard (front/back) — valid.
                val explanationText = cell(row, "explanation")
                questions += QuestionDto(
                    id = "csv-${index + 1}",
                    text = questionText,
                    elements = listOf(ContentElement.TextElement(questionText)),
                    explanation = explanationText,
                    explanationElements = listOf(ContentElement.TextElement(explanationText)),
                    tags = splitTags(cell(row, "tags"))
                )
                return@forEachIndexed
            }
            val answerRaw = cell(row, "answer").trim()
            val answerIndex = when (answerRaw.lowercase()) {
                "a", "1" -> 0
                "b", "2" -> 1
                "c", "3" -> 2
                "d", "4" -> 3
                else -> optionTexts.indexOfFirst { it.isNotBlank() && it.equals(answerRaw, ignoreCase = true) }
            }
            // Resolve against the original position, never the compressed list:
            // the answer column uses a-d (or 1-4 / option text), not the index
            // of the option after blanks were removed.
            val answerOption = optionsByPosition[answerIndex]
            if (answerOption == null) {
                warnings += rowWarning(rowNumber)
                skipped++
                return@forEachIndexed
            }
            val explanationText = cell(row, "explanation")
            questions += QuestionDto(
                id = "csv-${index + 1}",
                text = questionText,
                elements = listOf(ContentElement.TextElement(questionText)),
                options = options,
                correctOptionIds = listOf(answerOption.id),
                explanation = explanationText,
                explanationElements = listOf(ContentElement.TextElement(explanationText)),
                tags = splitTags(cell(row, "tags"))
            )
        }
        val file = McqFileDto(
            papers = listOf(
                PaperDto(
                    id = paperId,
                    title = paperTitle?.takeIf { it.isNotBlank() } ?: FALLBACK_PAPER_TITLE,
                    categories = listOf(
                        CategoryDto(
                            id = categoryId,
                            title = categoryTitle?.takeIf { it.isNotBlank() }
                                ?: FALLBACK_CATEGORY_TITLE,
                            questions = questions
                        )
                    )
                )
            )
        )
        return Result(file, warnings, skipped)
    }

    private fun emptyFile() = McqFileDto()

    private fun stripBom(text: String): String =
        text.removePrefix("\uFEFF")

    private fun hasHeader(cells: List<String>): Boolean {
        val normalized = cells.map { normalizeHeader(it) }
        return "question" in normalized && ("answer" in normalized || "options" in normalized)
    }

    private fun normalizeHeader(raw: String): String = raw.trim().lowercase()
        .replace(Regex("[\\s_]+"), " ")
        .let { normalized ->
            when {
                normalized == "question" || normalized == "questions" -> "question"
                normalized == "answer" || normalized == "correct" || normalized == "correct answer" -> "answer"
                normalized.startsWith("explain") -> "explanation"
                normalized == "option a" || normalized == "a" -> "a"
                normalized == "option b" || normalized == "b" -> "b"
                normalized == "option c" || normalized == "c" -> "c"
                normalized == "option d" || normalized == "d" -> "d"
                normalized == "tags" || normalized == "tag" -> "tags"
                else -> normalized
            }
        }

    private fun splitTags(raw: String): List<String> = raw
        .split(';', '|')
        .flatMap { it.split(',') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}
