package com.mcqapp.data.docx

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * DOCX bytes to the labeled question JSON our importer reads
 * (`question_num`, `question_elements`, `options_elements`,
 * `answer`, `explanation_elements`) — the same shape `ratta_ai`
 * `docx2json` produces.
 *
 * Malformed markers fail loudly with the offending question number
 * (mirroring the Python pipeline) so authors fix the document
 * instead of silently losing questions. The returned JSON feeds
 * straight into `LegacyParser`.
 */
data class DocxParseResult(val json: String, val warnings: List<String>)

private const val ZIP_MAGIC_0 = 0x50
private const val ZIP_MAGIC_1 = 0x4B

/**
 * True when [bytes] look like a .docx: a zip containing
 * `word/document.xml` near the front. Used to route picked files:
 * both .apkg and .docx are zips, so magic bytes alone cannot tell
 * them apart — and picker names/MIME types are unreliable.
 */
fun isDocxArchive(bytes: ByteArray): Boolean {
    if (bytes.size < 4 || bytes[0].toInt() != ZIP_MAGIC_0 || bytes[1].toInt() != ZIP_MAGIC_1) return false
    return try {
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
            var checked = 0
            var entry = zip.nextEntry
            while (entry != null && checked < 12) {
                if (!entry.isDirectory && entry.name == "word/document.xml") return true
                zip.closeEntry()
                entry = zip.nextEntry
                checked++
            }
            false
        }
    } catch (_: Exception) {
        false
    }
}

fun parseDocx(bytes: ByteArray): DocxParseResult {
    val read = readDocx(bytes)
    val text = StringBuilder()
    for (block in read.blocks) {
        when (block) {
            is DocxBlock.Para -> text.append(block.html).append('\n')
            is DocxBlock.Table -> {
                text.append("<table border=\"1\">")
                for (row in block.rows) {
                    text.append("<tr>")
                    for (cell in row) text.append("<td>").append(cell).append("</td>")
                    text.append("</tr>")
                }
                text.append("</table>").append('\n')
            }
        }
    }
    // Collapse blank lines like the pipeline's cleanup: markers must
    // start lines, stray empties are noise.
    val cleaned = text.toString().replace(Regex("\n{2,}"), "\n")
    val questions = extractQuestions(cleaned)
    val array = buildJsonArray {
        for (q in questions) add(questionJson(q))
    }
    return DocxParseResult(array.toString(), read.warnings)
}

internal data class RawQuestion(
    val num: String,
    val stemHtml: String,
    val options: Map<String, String>,
    val answer: String,
    val explanationHtml: String
)

private val questionSplit = Regex("(?m)^(\\d{1,7}\\.\\))")

private fun extractQuestions(cleaned: String): List<RawQuestion> {
    val matches = questionSplit.findAll(cleaned).toList()
    if (matches.isEmpty()) {
        if (cleaned.isBlank()) throw IllegalArgumentException("No questions found: the document is empty.")
        throw IllegalArgumentException(
            "No questions found: expected 'N.)' numbered questions with " +
                "'(a)..(d)', 'Ans.' and 'Exp:' markers."
        )
    }
    val out = mutableListOf<RawQuestion>()
    for (i in matches.indices) {
        val num = matches[i].groupValues[1].trim()
        val start = matches[i].range.last + 1
        val end = if (i + 1 < matches.size) matches[i + 1].range.first else cleaned.length
        var body = cleaned.substring(start, end).trim()
        // The number may hug the stem on one line ("1.) What…").
        if (body.startsWith(num)) body = body.substring(num.length).trim()
        out.add(extractQuestionData(num, body))
    }
    return out
}

private fun extractQuestionData(num: String, block: String): RawQuestion {
    val label = "$num"
    fun failMissing(missing: List<String>): Nothing {
        throw IllegalArgumentException(
            "Malformed question $label: missing ${missing.joinToString(", ")}. " +
                "Expected format 'N.) question (a) .. (b) .. (c) .. (d) .. Ans. .. Exp: ..'."
        )
    }
    val expParts = block.split("\nExp:", limit = 2)
    val explanation = if (expParts.size > 1) expParts[1].trim() else ""
    val preExp = expParts[0]
    val ansParts = preExp.split("\nAns.", limit = 2)
    val answer = if (ansParts.size > 1) ansParts[1].trim() else ""
    var rest = ansParts[0]
    // Options split from the end so earlier markers can't swallow later ones.
    fun rsplit(marker: String): String {
        val idx = rest.lastIndexOf(marker)
        if (idx < 0) return ""
        val value = rest.substring(idx + marker.length).trim()
        rest = rest.substring(0, idx)
        return value
    }
    val optionD = rsplit("\n(d)")
    val optionC = rsplit("\n(c)")
    val optionB = rsplit("\n(b)")
    val optionA = rsplit("\n(a)")
    val stem = rest.trim()
    val missing = mutableListOf<String>()
    if (stem.isBlank()) missing.add("question stem")
    if (optionA.isBlank()) missing.add("option (a)")
    if (optionB.isBlank()) missing.add("option (b)")
    if (optionC.isBlank()) missing.add("option (c)")
    if (optionD.isBlank()) missing.add("option (d)")
    if (answer.isBlank()) missing.add("answer")
    if (explanation.isBlank()) missing.add("explanation")
    if (missing.isNotEmpty()) failMissing(missing)
    return RawQuestion(
        num = num,
        stemHtml = stem,
        options = mapOf("a" to optionA, "b" to optionB, "c" to optionC, "d" to optionD),
        answer = answer,
        explanationHtml = explanation
    )
}

private val imgPattern = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
private val tablePattern = Regex("<table\\b.*?</table\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val rowPattern = Regex("<tr\\b[^>]*>(.*?)</tr\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val cellPattern = Regex("<t[dh]\\b[^>]*>(.*?)</t[dh]\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val tableOrImg = Regex(
    "<table\\b.*?</table\\s*>|<img\\b[^>]*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

/** Split field HTML into text/image/table elements (math stays in text). */
internal fun fieldElements(html: String): List<Map<String, Any>> {
    val out = mutableListOf<Map<String, Any>>()
    var pos = 0
    fun flushText(end: Int) {
        val chunk = html.substring(pos, end)
        if (chunk.isBlank()) return
        if (out.isNotEmpty() && out.last()["type"] == "text") {
            val merged = out.removeLast().toMutableMap()
            merged["content"] = (merged["content"] as String) + chunk
            out.add(merged)
        } else {
            out.add(mapOf("type" to "text", "content" to chunk))
        }
    }
    for (match in tableOrImg.findAll(html)) {
        flushText(match.range.first)
        val tag = match.value
        if (tag.startsWith("<img", ignoreCase = true)) {
            out.add(mapOf("type" to "image", "content" to tag))
        } else {
            out.add(mapOf("type" to "table", "content" to parseTableBlock(tag)))
        }
        pos = match.range.last + 1
    }
    flushText(html.length)
    // Drop whitespace-only text runs (stray breaks around blocks).
    return out.filterNot { it["type"] == "text" && (it["content"] as String).isBlank() }
}

private fun parseTableBlock(tableHtml: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    for (rowMatch in rowPattern.findAll(tableHtml)) {
        val cells = cellPattern.findAll(rowMatch.groupValues[1]).map { cellMatch ->
            cellMatch.groupValues[1]
        }.toList()
        rows.add(cells)
    }
    return rows
}

private fun elementJson(el: Map<String, Any>): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(el["type"] as String))
    when (el["type"]) {
        "table" -> {
            @Suppress("UNCHECKED_CAST")
            val rows = el["content"] as List<List<String>>
            put("content", buildJsonArray {
                for (row in rows) {
                    add(buildJsonArray {
                        for (cell in row) add(JsonPrimitive(cell))
                    })
                }
            })
        }
        else -> put("content", JsonPrimitive(el["content"] as String))
    }
}

private fun questionJson(q: RawQuestion): JsonObject = buildJsonObject {
    put("question_num", JsonPrimitive(q.num))
    put("question_elements", buildJsonArray {
        for (el in fieldElements(q.stemHtml)) add(elementJson(el))
    })
    put("options_elements", buildJsonObject {
        for ((key, value) in q.options) {
            put(key, buildJsonArray {
                for (el in fieldElements(value)) add(elementJson(el))
            })
        }
    })
    put("answer", JsonPrimitive(q.answer))
    put("explanation_elements", buildJsonArray {
        for (el in fieldElements(q.explanationHtml)) add(elementJson(el))
    })
}

/** Visible for tests: the marker splitter without zip/DOM. */
internal fun splitDocxMarkersForTest(coded: String): List<RawQuestion> = extractQuestions(coded)

/** Visible for tests: the raw JSON object for one parsed question. */
internal fun questionJsonForTest(q: RawQuestion): JsonObject = questionJson(q)

/** Visible for tests: re-exposes the private shape. */
internal fun rawQuestionForTest(
    num: String,
    stemHtml: String,
    options: Map<String, String>,
    answer: String,
    explanationHtml: String
) = RawQuestion(num, stemHtml, options, answer, explanationHtml)
