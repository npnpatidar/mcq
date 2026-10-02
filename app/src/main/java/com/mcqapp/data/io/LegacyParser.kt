package com.mcqapp.data.io

import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.textContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

object LegacyParser {

    /**
     * Deepest JSON nesting accepted. Real question banks nest about six
     * levels (file → paper → category → question → option); 200 is far beyond
     * any legitimate file and far below the parser's stack limit.
     */
    const val MAX_NESTING_DEPTH = 200

    /**
     * Prefix for paper ids generated at parse time. Such an id is ephemeral —
     * a fresh parse mints a new one — so the Importer may match these papers
     * by title. Stable ids (app-created or file-authored) never title-merge.
     */
    const val EPHEMERAL_PAPER_ID_PREFIX = "paper-import-"

    private val letterIds = ('a'..'z').map { it.toString() }

    /**
     * Nesting deeper than [MAX_NESTING_DEPTH] is rejected before parsing.
     *
     * `parseToJsonElement` recurses once per level, so a few hundred kilobytes
     * of `[[[[...` raised a StackOverflowError — an Error, which the import
     * screen's `catch (e: Exception)` could not catch, crashing instead of
     * reporting "Could not parse the file".
     */
    internal fun requireNestingDepth(json: String) {
        var depth = 0
        var maxDepth = 0
        var inString = false
        var escaped = false
        for (c in json) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    if (depth > maxDepth) maxDepth = depth
                }
                '}', ']' -> if (depth > 0) depth--
            }
            if (maxDepth > MAX_NESTING_DEPTH) {
                throw IllegalArgumentException(
                    "File is nested more than $MAX_NESTING_DEPTH levels deep."
                )
            }
        }
    }

    /**
     * Reads a list field, complaining when it is present but the wrong shape.
     *
     * A bare `as? JsonArray ?: emptyList()` silently turned a malformed
     * container into an empty one, so the import looked clean while silently
     * dropping every row inside it.
     */
    private fun arrayField(
        obj: JsonObject,
        name: String,
        where: String,
        warnings: MutableList<String>
    ): JsonArray {
        val value = obj[name] ?: return JsonArray(emptyList())
        if (value is JsonArray) return value
        val kind = value.javaClass.simpleName ?: "value"
        warnings.add("$where: \"$name\" is $kind, not a list — its contents were skipped")
        return JsonArray(emptyList())
    }

    /** A `<math>...</math>` block, attributes and line breaks included. */
    private val mathBlockPattern = Regex("<math\\b.*?</math\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun parse(json: String): McqFileDto {
        val warnings = mutableListOf<String>()
        requireNestingDepth(json)
        val element = Json.parseToJsonElement(json)
        // Scalar/null roots carry no questions; empty file beats a crash
        // (the import screen reports "No papers found").
        if (element !is JsonObject && element !is JsonArray) {
            warnings.add("File root is a scalar or null, not an object or array — no papers found")
            return McqFileDto(version = 1, warnings = warnings)
        }
        if (element is JsonArray) {
            val questions = uniqueIds(element.mapIndexedNotNull { index, el ->
                safeQuestion(el, "question ${index + 1}", warnings)
            })
            val paperId = EPHEMERAL_PAPER_ID_PREFIX + System.currentTimeMillis().toString(36)
            val paper = PaperDto(
                id = paperId,
                title = "Imported Questions",
                categories = listOf(
                    CategoryDto(
                        id = "$paperId-uncat",
                        title = "Uncategorized",
                        questions = questions
                    )
                ),
                questions = questions
            )
            localImageWarning(listOf(paper))?.let { warnings.add(it) }
            return McqFileDto(version = 1, papers = listOf(paper), warnings = warnings)
        }
        val root = element.jsonObject
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: 1
        val papersJson = arrayField(root, "papers", "File", warnings)
        val papers = papersJson.mapIndexedNotNull { index, paperEl ->
            try {
                parsePaper(paperEl.jsonObject, warnings)
            } catch (e: Exception) {
                warnings.add("Skipped malformed paper ${index + 1}: ${e.message ?: e.javaClass.simpleName}")
                null
            }
        }
        val bookmarks = (root["bookmarks"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.filter { it.isNotBlank() }
            ?: emptyList()
        val attemptsJson = arrayField(root, "attempts", "File", warnings)
        val attempts = attemptsJson.mapIndexedNotNull { index, attemptEl ->
            try {
                parseAttempt(attemptEl.jsonObject)
            } catch (e: Exception) {
                warnings.add("Skipped malformed attempt ${index + 1}: ${e.message ?: e.javaClass.simpleName}")
                null
            }
        }
        return McqFileDto(
            version = version,
            papers = papers,
            bookmarks = bookmarks,
            attempts = attempts,
            warnings = warnings
                .plus(localImageWarning(papers)?.let { listOf(it) } ?: emptyList())
        )
    }

    /**
     * One summary warning when images point at files on the author's
     * computer (absolute/relative paths, `file:` URLs): a JSON document
     * travels alone, so those bytes can never arrive on the device and the
     * images would silently show as broken. Data URIs and http(s)/content
     * links are portable and never warned about. Embedded media travels
     * with the `.apkg` format instead.
     */
    private fun localImageWarning(papers: List<PaperDto>): String? {
        var images = 0
        var questions = 0
        val seen = HashSet<String>()
        val allQuestions = papers.flatMap { it.questions + it.categories.flatMap { c -> c.questions } }
            .filter { seen.add(it.id) }
        var example = ""
        for (q in allQuestions) {
            val elements = q.elements + q.options.flatMap { it.elements } + q.explanationElements
            val bad = elements
                .filterIsInstance<ContentElement.ImageElement>()
                .filter { !isPortableImageSrc(it.src) }
            if (bad.isNotEmpty()) {
                questions++
                images += bad.size
                if (example.isEmpty()) example = bad.first().src
            }
        }
        if (images == 0) return null
        return "$images image(s) in $questions question(s) point at files on the author's computer " +
            "(e.g. '$example') and will not display after import — " +
            "embed data URIs or import the .apkg instead"
    }

    /** Data URIs and retrievable links travel with the question; file paths do not. */
    private fun isPortableImageSrc(src: String): Boolean {
        if (src.startsWith("data:", ignoreCase = true)) return true
        val scheme = src.substringBefore(':')
            .takeIf { it.length < src.length && it.matches(Regex("[a-zA-Z][a-zA-Z0-9+.-]*")) }
            ?.lowercase() ?: return false
        return scheme == "http" || scheme == "https" || scheme == "content"
    }

    private fun parseAttempt(obj: JsonObject): AttemptDto {
        fun str(key: String) = obj[key]?.jsonPrimitive?.contentOrNull ?: ""
        fun int(key: String) = obj[key]?.jsonPrimitive?.intOrNull ?: 0
        fun long(key: String) = obj[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        fun double(key: String) = obj[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
        val resultsJson = arrayField(obj, "results", "Attempt", mutableListOf())
        return AttemptDto(
            paperId = str("paperId"),
            title = str("title").ifBlank { "Test" },
            totalQuestions = int("totalQuestions"),
            correctCount = int("correctCount"),
            wrongCount = int("wrongCount"),
            skippedCount = int("skippedCount"),
            score = double("score"),
            maxScore = double("maxScore"),
            durationSeconds = long("durationSeconds"),
            finishedAt = long("finishedAt"),
            results = resultsJson.map { it.jsonObject }.map { r ->
                AttemptResultDto(
                    questionId = r["questionId"]?.jsonPrimitive?.contentOrNull ?: "",
                    categoryTitle = r["categoryTitle"]?.jsonPrimitive?.contentOrNull ?: "",
                    text = r["text"]?.jsonPrimitive?.contentOrNull ?: "",
                    optionsJson = r["optionsJson"]?.jsonPrimitive?.contentOrNull ?: "[]",
                    correctOptionIds = r["correctOptionIds"]?.jsonPrimitive?.contentOrNull ?: "",
                    selectedOptionIds = r["selectedOptionIds"]?.jsonPrimitive?.contentOrNull ?: "",
                    isCorrect = r["isCorrect"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                    explanation = r["explanation"]?.jsonPrimitive?.contentOrNull ?: "",
                    explanationImage = r["explanationImage"]?.jsonPrimitive?.contentOrNull,
                    dwellSeconds = r["dwellSeconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                )
            }
        )
    }

    private fun parsePaper(obj: JsonObject, warnings: MutableList<String>): PaperDto {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: EPHEMERAL_PAPER_ID_PREFIX + System.currentTimeMillis().toString(36)
        val title = obj["title"]?.jsonPrimitive?.contentOrNull
            ?: obj["name"]?.jsonPrimitive?.contentOrNull
            ?: "Untitled Paper"
        val description = obj["description"]?.jsonPrimitive?.contentOrNull
            ?: obj["desc"]?.jsonPrimitive?.contentOrNull
            ?: ""
        // Clamped: an imported file could otherwise overflow the Int that
        // holds the exam duration and produce a negative timer.
        val duration = com.mcqapp.domain.ExamTiming.minutesFrom(
            obj["durationMinutes"]?.jsonPrimitive?.intOrNull
                ?: obj["duration"]?.jsonPrimitive?.intOrNull
        )
        val negative = obj["negativeMarking"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?: obj["negative"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?: 0.0
        val categoriesJson = obj["categories"] as? JsonArray
            ?: obj["sections"] as? JsonArray
            ?: obj["subjects"] as? JsonArray
            ?: JsonArray(emptyList())
        val categories = categoriesJson.mapIndexedNotNull { index, catEl ->
            try {
                parseCategory(catEl.jsonObject, warnings)
            } catch (e: Exception) {
                warnings.add(
                    "Skipped malformed category ${index + 1} in paper '$title': ${e.message ?: e.javaClass.simpleName}"
                )
                null
            }
        }
        val topLevelQuestions = obj["questions"] as? JsonArray
        val parsedTopLevel = topLevelQuestions?.let {
            uniqueIds(it.mapIndexedNotNull { index, q -> safeQuestion(q, "question ${index + 1}", warnings) })
        } ?: emptyList()
        // Top-level questions land in their own category instead of being
        // silently dropped when the paper also defines categories.
        val finalCategories = if (categories.isEmpty() && topLevelQuestions != null) {
            listOf(
                CategoryDto(
                    id = id + "-root",
                    title = title,
                    questions = parsedTopLevel
                )
            )
        } else if (topLevelQuestions != null && parsedTopLevel.isNotEmpty()) {
            categories + CategoryDto(
                id = id + "-root",
                title = "Uncategorized",
                questions = parsedTopLevel
            )
        } else {
            categories
        }
        return PaperDto(
            id = id,
            title = title,
            description = description,
            durationMinutes = duration,
            negativeMarking = negative,
            categories = finalCategories,
            // The synthesized category above already holds them. Listing them
            // here as well would count every top level question twice, which
            // the Anki writer then turns into two notes sharing a guid.
            questions = emptyList()
        )
    }

    private fun parseCategory(obj: JsonObject, warnings: MutableList<String>): CategoryDto {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "cat-" + System.currentTimeMillis().toString(36) + "-" + (0..9999).random()
        val title = obj["title"]?.jsonPrimitive?.contentOrNull
            ?: obj["name"]?.jsonPrimitive?.contentOrNull
            ?: "Category"
        val parentId = obj["parentId"]?.jsonPrimitive?.contentOrNull
        val questionsJson = arrayField(obj, "questions", "Category '$title'", warnings)
        return CategoryDto(
            id = id,
            title = title,
            parentId = parentId,
            questions = uniqueIds(
                questionsJson.mapIndexedNotNull { index, q ->
                    safeQuestion(q, "question ${index + 1}", warnings)
                }
            )
        )
    }

    /**
     * Malformed rows are skipped, never fatal: one bad question must not kill
     * a bank. The drop is recorded in [warnings] so the import preview can say
     * which row was lost and why.
     */
    private fun safeQuestion(element: JsonElement, where: String, warnings: MutableList<String>): QuestionDto? = try {
        parseQuestion(element.jsonObject, warnings)
    } catch (e: Exception) {
        warnings.add("Skipped malformed $where: ${e.message ?: e.javaClass.simpleName}")
        null
    }

    /**
     * IDs generated for questions without one are deterministic (content hash),
     * so re-parsing the same JSON yields the same IDs. Random IDs broke the
     * import-screen join between the parsed file and the edited preview state:
     * any divergence silently dropped questions. Suffixes keep duplicates unique.
     */
    private fun uniqueIds(questions: List<QuestionDto>): List<QuestionDto> {
        val seen = HashSet<String>()
        return questions.map { q ->
            var id = q.id
            var n = 2
            while (!seen.add(id)) {
                id = "${q.id}-$n"
                n++
            }
            if (id != q.id) q.copy(id = id) else q
        }
    }

    private fun stableQuestionId(text: String, options: List<OptionDto>): String {
        val raw = text + "|" + options.joinToString(",") { it.id + "=" + it.text }
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return "q-" + bytes.joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun parseQuestion(obj: JsonObject, warnings: MutableList<String>): QuestionDto {
        // question_num (e.g. "1.)") is a bank serial, not question content:
        // it is deliberately ignored so it never becomes part of the text.
        val elements = parseElements(obj["question_elements"], obj["text"], obj["question"])
        val text = elements.textContent
        val image = obj["image"]?.jsonPrimitive?.contentOrNull
            ?: obj["imageUrl"]?.jsonPrimitive?.contentOrNull
        val explanationElements = parseElements(
            obj["explanation_elements"],
            obj["explanation"], obj["explain"], obj["reason"]
        )
        val explanation = explanationElements.textContent
        val explanationImage = obj["explanationImage"]?.jsonPrimitive?.contentOrNull
            ?: obj["explanation_image"]?.jsonPrimitive?.contentOrNull
            ?: obj["explainImage"]?.jsonPrimitive?.contentOrNull
            ?: obj["explanationImageUrl"]?.jsonPrimitive?.contentOrNull
        val difficulty = obj["difficulty"]?.jsonPrimitive?.contentOrNull ?: "medium"
        // Weight of a correct answer; absent/invalid/negative values fall back to 1.
        val marks = (obj["marks"] ?: obj["points"] ?: obj["weight"])
            ?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 } ?: 1.0
        val tags = when (val t = obj["tags"]) {
            is JsonArray -> t.mapNotNull { it.jsonPrimitive.contentOrNull }
            is JsonPrimitive -> t.contentOrNull?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            else -> null
        } ?: emptyList()

        val options = parseOptions(obj)
        val correctIds = parseCorrectIds(obj, options, warnings)
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: stableQuestionId(text, options)

        return QuestionDto(
            id = id,
            text = text,
            elements = elements,
            image = image,
            options = options,
            correctOptionIds = correctIds,
            explanation = explanation,
            explanationElements = explanationElements,
            explanationImage = explanationImage,
            difficulty = difficulty,
            marks = marks,
            tags = tags
        )
    }

    /**
     * Reads a structured element list, falling back to a plain string (old
     * format) wrapped as one TextElement. A text run may embed `<math>`
     * blocks, which become MathElements so formulas render instead of
     * showing raw markup.
     */
    private fun parseElements(elementsJson: JsonElement?, vararg fallbacks: JsonElement?): List<ContentElement> {
        val arr = elementsJson as? JsonArray
        if (arr != null) {
            val parsed = arr.flatMap { el ->
                if (el is JsonObject) parseContentElement(el) else emptyList()
            }
            if (parsed.isNotEmpty()) return parsed
        }
        for (fallback in fallbacks) {
            val s = fallback?.jsonPrimitive?.contentOrNull
            if (!s.isNullOrBlank()) return listOf(ContentElement.TextElement(s))
        }
        return emptyList()
    }

    /** Parses one `{type, content}` element; image content is an `<img>` tag. */
    private fun parseContentElement(obj: JsonObject): List<ContentElement> {
        return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> splitTextRuns(obj["content"]?.jsonPrimitive?.contentOrNull ?: "")
            "image" -> {
                val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                listOf(ContentElement.ImageElement(extractImgSrc(content) ?: content))
            }
            "table" -> {
                val rows = obj["content"]?.jsonArray?.map { row ->
                    row.jsonArray.map { it.jsonPrimitive.content }
                } ?: return emptyList()
                listOf(ContentElement.TableElement(rows))
            }
            "math" -> {
                val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                listOf(ContentElement.MathElement(content))
            }
            else -> emptyList()
        }
    }

    /**
     * Splits a text run on `<math>...</math>` blocks so embedded formulas
     * become MathElements. Anything else (including an unclosed `<math`)
     * stays plain text: a malformed formula must remain visible, never
     * silently reinterpreted or dropped.
     */
    private fun splitTextRuns(text: String): List<ContentElement> {
        if (!text.contains("<math", ignoreCase = true)) return listOf(ContentElement.TextElement(text))
        val out = mutableListOf<ContentElement>()
        var pos = 0
        for (match in mathBlockPattern.findAll(text)) {
            val before = text.substring(pos, match.range.first)
            if (before.isNotEmpty()) out += ContentElement.TextElement(before)
            out += ContentElement.MathElement(match.value)
            pos = match.range.last + 1
        }
        val tail = text.substring(pos)
        if (tail.isNotEmpty()) out += ContentElement.TextElement(tail)
        return out.ifEmpty { listOf(ContentElement.TextElement(text)) }
    }

    /** Pulls the `src` out of an `<img ...>` tag; null when there is none. */
    private fun extractImgSrc(html: String): String? =
        Regex("""<img[^>]*src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)

    private fun parseOptions(obj: JsonObject): List<OptionDto> {
        val elementsMap = obj["options_elements"] as? JsonObject
        if (elementsMap != null) {
            val parsed = elementsMap.entries.mapNotNull { (optionId, raw) ->
                val elements = (raw as? JsonArray)?.flatMap { el ->
                    if (el is JsonObject) parseContentElement(el) else emptyList()
                } ?: return@mapNotNull null
                OptionDto(
                    id = optionId,
                    text = elements.textContent,
                    elements = elements
                )
            }
            if (parsed.isNotEmpty()) return parsed
        }
        val raw = obj["options"] ?: return emptyList()
        return when (raw) {
            is JsonArray -> raw.mapIndexedNotNull { index, el ->
                when (el) {
                    is JsonPrimitive -> {
                        val text = el.content
                        OptionDto(
                            id = letterIds.getOrElse(index) { index.toString() },
                            text = text,
                            elements = listOf(ContentElement.TextElement(text))
                        )
                    }
                    is JsonObject -> {
                        val text = el["text"]?.jsonPrimitive?.contentOrNull
                            ?: el["value"]?.jsonPrimitive?.contentOrNull
                            ?: return@mapIndexedNotNull null
                        val oid = el["id"]?.jsonPrimitive?.contentOrNull
                            ?: letterIds.getOrElse(index) { index.toString() }
                        OptionDto(
                            id = oid,
                            text = text,
                            elements = listOf(ContentElement.TextElement(text)),
                            image = el["image"]?.jsonPrimitive?.contentOrNull
                        )
                    }
                    else -> null
                }
            }
            else -> emptyList()
        }
    }

    private fun parseCorrectIds(
        obj: JsonObject,
        options: List<OptionDto>,
        warnings: MutableList<String>
    ): List<String> = try {
        parseCorrectIdsUnsafe(obj, options)
    } catch (e: Exception) {
        warnings.add("Could not read the answer key: ${e.message ?: e.javaClass.simpleName} — question imports ungraded")
        emptyList()
    }

    private fun parseCorrectIdsUnsafe(obj: JsonObject, options: List<OptionDto>): List<String> {
        val optionIds = options.map { it.id }.toSet()
        obj["correctOptionIds"]?.let { v ->
            val ids = when (v) {
                is JsonArray -> v.mapNotNull {
                    try {
                        it.jsonPrimitive.contentOrNull
                    } catch (_: Exception) {
                        null
                    }
                }
                is JsonPrimitive -> v.contentOrNull?.split(",")?.map { it.trim() }
                else -> null
            }
            // Dangling keys reference nothing answerable; dropping them turns
            // the question ungraded (with a preview warning) instead of
            // silently unwinnable.
            val resolved = ids?.filter { it in optionIds }
            if (!resolved.isNullOrEmpty()) return resolved
        }
        obj["correctIndex"]?.jsonPrimitive?.intOrNull?.let { idx ->
            return listOfNotNull(options.getOrNull(idx)?.id)
        }
        obj["answer"]?.jsonPrimitive?.contentOrNull?.let { ans ->
            return resolveAnswer(ans, options)
        }
        obj["correct"]?.jsonPrimitive?.contentOrNull?.let { ans ->
            return resolveAnswer(ans, options)
        }
        obj["correctAnswer"]?.jsonPrimitive?.contentOrNull?.let { ans ->
            return resolveAnswer(ans, options)
        }
        return emptyList()
    }

    private fun resolveAnswer(answer: String, options: List<OptionDto>): List<String> {
        val byId = options.firstOrNull { it.id.equals(answer, ignoreCase = true) }
        if (byId != null) return listOf(byId.id)
        val byText = options.filter { it.text.equals(answer.trim(), ignoreCase = true) }
        if (byText.isNotEmpty()) return byText.map { it.id }
        val idx = answer.trim().toIntOrNull()
        if (idx != null) return listOfNotNull(options.getOrNull(idx)?.id)
        return emptyList()
    }
}
