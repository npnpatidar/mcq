package com.mcqapp.data.io

import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.textContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

object LegacyParser {

    /**
     * Deepest JSON nesting accepted. Real question banks nest about six
     * levels (file → paper → category → question → option); 200 is far beyond
     * any legitimate file and far below the reader's stack limit. Enforced by
     * the streaming slice reader, not a pre-scan of the document.
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
     * One questionId-keyed entry of the optional review-progress map.
     * Tolerant by design: one unreadable entry costs that card's schedule,
     * not the whole import.
     */
    private fun cardSchedule(entry: JsonObject): CardScheduleDto? = try {
        CardScheduleDto(
            ease = entry["ease"]?.jsonPrimitive?.doubleOrNull ?: 2.5,
            intervalDays = entry["intervalDays"]?.jsonPrimitive?.intOrNull ?: 0,
            dueAt = entry["dueAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            reps = entry["reps"]?.jsonPrimitive?.intOrNull ?: 0,
            lapses = entry["lapses"]?.jsonPrimitive?.intOrNull ?: 0,
            leech = entry["leech"]?.jsonPrimitive?.booleanOrNull ?: false,
            lastReviewedAt = entry["lastReviewedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            difficulty = entry["difficulty"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            stability = entry["stability"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            cardState = entry["cardState"]?.jsonPrimitive
                ?.takeIf { it.isString }?.content
                ?.let { name ->
                    runCatching { com.mcqapp.domain.CardStateKind.valueOf(name) }
                        .getOrNull()
                }?.name
                ?: com.mcqapp.domain.CardStateKind.NEW.name,
            reviewCount = entry["reviewCount"]?.jsonPrimitive?.intOrNull ?: 0
        )
    } catch (_: Exception) {
        // Keep the rest of the progress.
        null
    }

    /** The review-progress map, read one small entry at a time. */
    private fun readScheduling(reader: JsonPull): Map<String, CardScheduleDto> {
        if (reader.peek() != JsonPull.Token.BEGIN_OBJECT) {
            reader.skipValue()
            return emptyMap()
        }
        val out = mutableMapOf<String, CardScheduleDto>()
        reader.beginObject()
        while (reader.hasNext()) {
            val id = reader.nextName()
            val entry = readElement(reader)
            if (entry is JsonObject) cardSchedule(entry)?.let { out[id] = it }
        }
        reader.endObject()
        return out
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

    /**
     * Parses the legacy JSON document from a stream.
     *
     * The document is walked with a pull reader and materialised one small
     * slice at a time — a question, a passage — instead of a whole-document
     * DOM: a 48 MB bank (169k questions) built a node tree hundreds of
     * megabytes deep and every import died with an OutOfMemoryError while the
     * card itself needed only a fraction of that. The reader iterates rather
     * than recurses one level per document level, so a hostile
     * `[[[[...]]]]` cannot overflow the stack either; the slice reader keeps
     * its own [MAX_NESTING_DEPTH] budget.
     *
     * Every per-item skip and warning of the old DOM walk is preserved: one
     * malformed question still costs that question, not the bank.
     */
    fun parse(input: InputStream): McqFileDto {
        val warnings = mutableListOf<String>()
        val reader = JsonPull(input)
        val file = when (reader.peek()) {
            JsonPull.Token.BEGIN_ARRAY -> {
                val questions = uniqueIds(
                    readQuestionsArray(reader, "question", warnings)
                )
                val paper = ephemeralPaper(questions)
                localImageWarning(listOf(paper))?.let { warnings.add(it) }
                McqFileDto(version = 1, papers = listOf(paper), warnings = warnings)
            }
            JsonPull.Token.BEGIN_OBJECT -> readRootObject(reader, warnings)
            // Scalar/null roots carry no questions; empty file beats a crash
            // (the import screen reports "No papers found").
            else -> {
                warnings.add("File root is a scalar or null, not an object or array — no papers found")
                McqFileDto(version = 1, warnings = warnings)
            }
        }
        return file
    }

    fun parse(json: String): McqFileDto =
        parse(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)))

    private fun readRootObject(reader: JsonPull, warnings: MutableList<String>): McqFileDto {
        val papers = mutableListOf<PaperDto>()
        var topLevelQuestions: List<QuestionDto>? = null
        var passages = emptyList<PassageDto>()
        var attempts = emptyList<AttemptDto>()
        var bookmarks = emptyList<String>()
        var scheduling = emptyMap<String, CardScheduleDto>()
        var version = 1
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "papers" -> readPapers(reader, warnings, papers)
                "questions" -> topLevelQuestions = uniqueIds(
                    readQuestionsArray(reader, "question", warnings)
                ).ifEmpty { null }
                "passages" -> passages = readArrayOrWarn(
                    reader, "File", "passages", "passage", warnings
                ) { index, obj ->
                    try {
                        parsePassage(obj)
                    } catch (e: Exception) {
                        warnings.add("Skipped malformed passage ${index + 1}: ${e.message ?: e.javaClass.simpleName}")
                        null
                    }
                }
                "attempts" -> attempts = readArrayOrWarn(
                    reader, "File", "attempts", "attempt", warnings
                ) { index, obj ->
                    try {
                        parseAttempt(obj)
                    } catch (e: Exception) {
                        warnings.add("Skipped malformed attempt ${index + 1}: ${e.message ?: e.javaClass.simpleName}")
                        null
                    }
                }
                "bookmarks" -> bookmarks = readBookmarks(reader)
                "scheduling" -> scheduling = readScheduling(reader)
                "version" -> version = readInt(reader) ?: 1
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        // A root-level `questions` array with no `papers` is the DOCX pipeline's
        // wrapper shape (`{"questions": [...], "passages": [...]}`). It wraps the
        // questions in the same ephemeral paper the bare-array path builds, so
        // the passage block beside it is not stranded without a home.
        val effectivePapers = if (papers.isEmpty() && topLevelQuestions != null) {
            listOf(ephemeralPaper(topLevelQuestions))
        } else {
            papers
        }
        return McqFileDto(
            version = version,
            papers = effectivePapers,
            bookmarks = bookmarks,
            attempts = attempts,
            passages = passages,
            warnings = warnings.plus(
                localImageWarning(effectivePapers)?.let { listOf(it) } ?: emptyList()
            ),
            scheduling = scheduling
        )
    }

    private fun readPapers(
        reader: JsonPull,
        warnings: MutableList<String>,
        sink: MutableList<PaperDto>
    ) {
        if (reader.peek() != JsonPull.Token.BEGIN_ARRAY) {
            warnings.add("File: \"papers\" is ${kindOf(reader.peek())}, not a list — its contents were skipped")
            reader.skipValue()
            return
        }
        reader.beginArray()
        var index = 0
        while (reader.hasNext()) {
            val at = index + 1
            index = at
            try {
                sink += readPaper(reader, warnings)
            } catch (e: Exception) {
                warnings.add("Skipped malformed paper $at: ${e.message ?: e.javaClass.simpleName}")
                skipValueQuietly(reader)
            }
        }
        reader.endArray()
    }

    private fun readPaper(reader: JsonPull, warnings: MutableList<String>): PaperDto {
        var id: String? = null
        var title: String? = null
        var description: String? = null
        var duration: Int? = null
        var negative: Double? = null
        val categories = mutableListOf<CategoryDto>()
        var topLevelQuestions: MutableList<QuestionDto>? = null
        // A category skip names the paper, and a paper's title may arrive after
        // its categories in the stream; buffer the skips until the end.
        val categorySkips = mutableListOf<Pair<Int, String>>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (val key = reader.nextName()) {
                "id" -> id = readString(reader) ?: id
                "title", "name" -> title = readString(reader) ?: title
                "description", "desc" -> description = readString(reader) ?: description
                "durationMinutes", "duration" -> duration =
                    readInt(reader) ?: duration
                "negativeMarking", "negative" -> negative =
                    readDouble(reader) ?: negative
                "categories", "sections", "subjects" ->
                    readCategories(reader, warnings, categories, categorySkips)
                "questions" -> topLevelQuestions = uniqueIds(
                    readQuestionsArray(reader, "question", warnings)
                ).toMutableList().ifEmpty { null }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        for ((index, detail) in categorySkips) {
            warnings.add(
                "Skipped malformed category $index in paper '${title ?: "Untitled Paper"}': $detail"
            )
        }
        val effectiveCategories = when {
            categories.isEmpty() && topLevelQuestions != null -> listOf(
                CategoryDto(
                    id = (id ?: EPHEMERAL_PAPER_ID_PREFIX + System.currentTimeMillis().toString(36)) + "-root",
                    title = title ?: "Untitled Paper",
                    questions = topLevelQuestions
                )
            )
            topLevelQuestions != null && topLevelQuestions.isNotEmpty() ->
                categories + CategoryDto(
                    id = (id ?: EPHEMERAL_PAPER_ID_PREFIX + System.currentTimeMillis().toString(36)) + "-root",
                    title = "Uncategorized",
                    questions = topLevelQuestions
                )
            else -> categories
        }
        return PaperDto(
            id = id?.takeIf { it.isNotBlank() }
                ?: EPHEMERAL_PAPER_ID_PREFIX + System.currentTimeMillis().toString(36),
            title = title ?: "Untitled Paper",
            description = description ?: "",
            // Clamped: an imported file could otherwise overflow the Int that
            // holds the exam duration and produce a negative timer.
            durationMinutes = com.mcqapp.domain.ExamTiming.minutesFrom(duration),
            negativeMarking = negative ?: 0.0,
            categories = effectiveCategories,
            // The synthesized category above already holds them. Listing them
            // here as well would count every top level question twice, which
            // the Anki writer then turns into two notes sharing a guid.
            questions = emptyList()
        )
    }

    private fun readCategories(
        reader: JsonPull,
        warnings: MutableList<String>,
        sink: MutableList<CategoryDto>,
        skips: MutableList<Pair<Int, String>>
    ) {
        if (reader.peek() != JsonPull.Token.BEGIN_ARRAY) {
            warnings.add("File: \"categories\" is ${kindOf(reader.peek())}, not a list — its contents were skipped")
            reader.skipValue()
            return
        }
        reader.beginArray()
        var index = 0
        while (reader.hasNext()) {
            val at = index + 1
            index = at
            try {
                sink += readCategory(reader, warnings)
            } catch (e: Exception) {
                skips += at to (e.message ?: e.javaClass.simpleName)
                skipValueQuietly(reader)
            }
        }
        reader.endArray()
    }

    private fun readCategory(reader: JsonPull, warnings: MutableList<String>): CategoryDto {
        var id: String? = null
        var title: String? = null
        var parentId: String? = null
        var questions = mutableListOf<QuestionDto>()
        var typeMismatch = false
        var wrongShape: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = readString(reader) ?: id
                "title", "name" -> title = readString(reader) ?: title
                "parentId" -> parentId = readString(reader) ?: parentId
                "questions" ->
                    if (reader.peek() == JsonPull.Token.BEGIN_ARRAY) {
                        questions = readQuestionsArray(
                            reader,
                            "question",
                            warnings
                        ).toMutableList()
                    } else {
                        typeMismatch = true
                        wrongShape = kindOf(reader.peek())
                        reader.skipValue()
                    }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val label = title ?: "Category"
        if (typeMismatch) {
            warnings.add(
                "Category '$label': \"questions\" is $wrongShape, not a list — its contents were skipped"
            )
        }
        return CategoryDto(
            id = id?.takeIf { it.isNotBlank() }
                ?: "cat-" + System.currentTimeMillis().toString(36) + "-" + (0..9999).random(),
            title = label,
            parentId = parentId,
            questions = uniqueIds(questions)
        )
    }

    private fun readQuestionsArray(
        reader: JsonPull,
        label: String,
        warnings: MutableList<String>
    ): List<QuestionDto> {
        val out = ArrayList<QuestionDto>()
        // A non-array must still be consumed: leaving its token behind makes
        // the enclosing object's loop see a value where it expects a name.
        if (reader.peek() != JsonPull.Token.BEGIN_ARRAY) {
            reader.skipValue()
            return out
        }
        reader.beginArray()
        var index = 0
        while (reader.hasNext()) {
            val at = index + 1
            index = at
            when (val element = readElement(reader)) {
                is JsonObject ->
                    safeQuestion(element, "$label $at", warnings)?.let(out::add)
                else -> warnings.add("Skipped malformed $label $at: not an object")
            }
        }
        reader.endArray()
        return out
    }

    /** Reads an array of objects through [mapper]; wrong shape warns once. */
    private fun <T> readArrayOrWarn(
        reader: JsonPull,
        where: String,
        name: String,
        entryName: String,
        warnings: MutableList<String>,
        mapper: (Int, JsonObject) -> T?
    ): List<T> {
        if (reader.peek() != JsonPull.Token.BEGIN_ARRAY) {
            warnings.add("$where: \"$name\" is ${kindOf(reader.peek())}, not a list — its contents were skipped")
            reader.skipValue()
            return emptyList()
        }
        val out = ArrayList<T>()
        reader.beginArray()
        var index = 0
        while (reader.hasNext()) {
            val at = index + 1
            index = at
            val slice = readElement(reader)
            if (slice is JsonObject) {
                mapper(at - 1, slice)?.let(out::add)
            } else {
                warnings.add("Skipped malformed $entryName $at: not an object — it was skipped")
            }
        }
        reader.endArray()
        return out
    }

    private fun readBookmarks(reader: JsonPull): List<String> {
        if (reader.peek() != JsonPull.Token.BEGIN_ARRAY) {
            reader.skipValue()
            return emptyList()
        }
        val out = mutableListOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            val value = readString(reader)
            if (!value.isNullOrBlank()) out += value
        }
        reader.endArray()
        return out
    }

    /**
     * Materializes one JSON value from the reader as a small [JsonElement].
     * Only ever called for slices that stay small (a question, a passage, a
     * scalar), so the DOM the parser used to build for the whole document
     * never exists. Scalars keep their literal text — `intOrNull`/`booleanOrNull`
     * parse it exactly as they parsed the old document-wide DOM.
     */
    private fun readElement(reader: JsonPull, depth: Int = 0): JsonElement {
        if (depth > MAX_NESTING_DEPTH) {
            throw IllegalArgumentException("File is nested more than $MAX_NESTING_DEPTH levels deep.")
        }
        return when (reader.peek()) {
            JsonPull.Token.BEGIN_OBJECT -> readObjectSlice(reader, depth)
            JsonPull.Token.BEGIN_ARRAY -> readArraySlice(reader, depth)
            JsonPull.Token.STRING -> JsonPrimitive(reader.nextString())
            JsonPull.Token.NUMBER -> JsonPrimitive(reader.nextString())
            JsonPull.Token.BOOLEAN -> JsonPrimitive(reader.nextString())
            JsonPull.Token.NULL -> {
                reader.nextNull()
                JsonNull
            }
            else -> {
                reader.skipValue()
                JsonNull
            }
        }
    }

    private fun readObjectSlice(reader: JsonPull, depth: Int): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            out[name] = readElement(reader, depth + 1)
        }
        reader.endObject()
        return JsonObject(out)
    }

    private fun readArraySlice(reader: JsonPull, depth: Int): JsonArray {
        val out = ArrayList<JsonElement>()
        reader.beginArray()
        while (reader.hasNext()) out += readElement(reader, depth + 1)
        reader.endArray()
        return JsonArray(out)
    }

    /** A malformed paper/category body must not abandon its array. */
    private fun skipValueQuietly(reader: JsonPull) {
        try {
            reader.skipValue()
        } catch (_: Exception) {
            // The walk ends: a truncated document stops the read like any
            // other parse failure and surfaces as "Could not parse the file".
        }
    }

    private fun kindOf(token: JsonPull.Token): String = when (token) {
        JsonPull.Token.BEGIN_OBJECT -> "an object"
        JsonPull.Token.BEGIN_ARRAY -> "a list"
        JsonPull.Token.NULL -> "null"
        JsonPull.Token.STRING -> "text"
        JsonPull.Token.BOOLEAN -> "true/false"
        JsonPull.Token.NUMBER -> "a number"
        else -> token.toString()
    }

    private fun readString(reader: JsonPull): String? =
        (readElement(reader) as? JsonPrimitive)?.contentOrNull

    /** A scalar field: a non-scalar value reads as absent, never fatal. */
    private fun readInt(reader: JsonPull): Int? =
        (readElement(reader) as? JsonPrimitive)?.intOrNull

    private fun readDouble(reader: JsonPull): Double? =
        (readElement(reader) as? JsonPrimitive)?.doubleOrNull

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
        var example = ""
        val seen = HashSet<String>()
        // No intermediate lists: a bank can hold hundreds of thousands of
        // questions, and the old flatMap built every one of them at once.
        for (paper in papers) {
            for (category in paper.categories) {
                for (q in category.questions) {
                    if (!seen.add(q.id)) continue
                    val bad = countLocalImages(q)
                    if (bad == 0) continue
                    questions++
                    images += bad
                    if (example.isEmpty()) example = firstLocalImage(q) ?: ""
                }
            }
        }
        if (images == 0) return null
        return "$images image(s) in $questions question(s) point at files on the author's computer " +
            "(e.g. '$example') and will not display after import — " +
            "embed data URIs or import the .apkg instead"
    }

    private fun questionElementLists(q: QuestionDto): List<List<ContentElement>> =
        listOf(q.elements) + q.options.map { it.elements } + listOf(q.explanationElements)

    private fun countLocalImages(q: QuestionDto): Int =
        questionElementLists(q).sumOf { elements ->
            elements.count { it is ContentElement.ImageElement && !isPortableImageSrc(it.src) }
        }

    private fun firstLocalImage(q: QuestionDto): String? =
        questionElementLists(q).asSequence()
            .flatten()
            .filterIsInstance<ContentElement.ImageElement>()
            .firstOrNull { !isPortableImageSrc(it.src) }
            ?.src

    /** Data URIs and retrievable links travel with the question; file paths do not. */
    private fun isPortableImageSrc(src: String): Boolean {
        if (src.startsWith("data:", ignoreCase = true)) return true
        val scheme = src.substringBefore(':')
            .takeIf { it.length < src.length && it.matches(Regex("[a-zA-Z][a-zA-Z0-9+.-]*")) }
            ?.lowercase() ?: return false
        return scheme == "http" || scheme == "https" || scheme == "content"
    }

    /**
     * One `passages[]` entry. Elements round-trip as content elements; a text
     * string fallback wraps as one TextElement, like a question's body.
     */
    private fun parsePassage(obj: JsonObject): PassageDto {
        val elements = parseElements(obj["elements"], obj["text"], obj["content"])
        return PassageDto(
            id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("missing id"),
            title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "Passage",
            elements = elements,
            image = obj["image"]?.jsonPrimitive?.contentOrNull,
            categoryId = obj["categoryId"]?.jsonPrimitive?.contentOrNull
        )
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

    /**
     * The throwaway paper the bare-questions shapes (root array, DOCX wrapper)
     * are wrapped in: one paper "Imported Questions" holding one category
     * "Uncategorized". The importer's own-id path keeps re-imports merging
     * into it.
     */
    private fun ephemeralPaper(questions: List<QuestionDto>): PaperDto {
        val paperId = EPHEMERAL_PAPER_ID_PREFIX + System.currentTimeMillis().toString(36)
        return PaperDto(
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
            tags = tags,
            passageId = obj["passageId"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
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
