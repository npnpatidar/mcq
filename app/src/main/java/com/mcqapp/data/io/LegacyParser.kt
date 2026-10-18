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

    /**
     * The tail of a streamed parse: everything the file carries that is not a
     * paper. In the canonical order these fields trail `papers`, so they are
     * returned once the walk ends rather than streamed ahead of it.
     *
     * [streamImport] streams the tail through [ImportStreamSink]'s
     * `onBookmarks`/`onSchedules`/`onAttempts` callbacks instead, so its result
     * carries those three fields empty and only the counts a preview needs
     * come back from the sink. [streamPapers] and [parse] still fill them.
     */
    data class StreamedFile(
        val version: Int,
        val passages: List<PassageDto>,
        val bookmarks: List<String>,
        val attempts: List<AttemptDto>,
        val scheduling: Map<String, CardScheduleDto>,
        val warnings: List<String>
    )

    /**
     * Parses the file, handing each paper to [onPaper] the moment its array
     * entry is read, so a caller can persist it without holding the whole
     * bank. Returns the non-paper tail (see [StreamedFile]).
     *
     * The emission shape mirrors [parse]: a root `papers` array streams its
     * entries; a bare array or a root `questions` wrapper (the DOCX shape)
     * streams the single ephemeral paper once its questions are read. The
     * local-image summary is accumulated as papers stream and lands in
     * [StreamedFile.warnings] exactly as [parse] appends it.
     */
    fun streamPapers(input: InputStream, onPaper: (PaperDto) -> Unit): StreamedFile {
        val warnings = mutableListOf<String>()
        val reader = JsonPull(input)
        val scan = LocalImageScan()
        val emit: (PaperDto) -> Unit = { paper ->
            scan.add(paper)
            onPaper(paper)
        }
        return when (reader.peek()) {
            JsonPull.Token.BEGIN_ARRAY -> {
                val questions = uniqueIds(readQuestionsArray(reader, "question", warnings))
                emit(ephemeralPaper(questions))
                StreamedFile(1, emptyList(), emptyList(), emptyList(), emptyMap(), warnings)
            }
            JsonPull.Token.BEGIN_OBJECT -> {
                val tail = walkRootObject(reader, warnings, emit)
                tail.copy(warnings = tail.warnings + listOfNotNull(scan.warning()))
            }
            // Scalar/null roots carry no questions; empty file beats a crash
            // (the import screen reports "No papers found").
            else -> {
                warnings.add("File root is a scalar or null, not an object or array — no papers found")
                StreamedFile(1, emptyList(), emptyList(), emptyList(), emptyMap(), warnings)
            }
        }
    }

    /**
     * Default number of questions handed to an [ImportStreamSink] at a time.
     * Big enough that a 1M-question paper costs ~500 callbacks, small enough
     * that one batch's DTOs are a couple of megabytes.
     */
    const val IMPORT_BATCH_SIZE = 2000

    /**
     * A paper's identity, emitted before its categories so a streaming import
     * can create the paper row first. Mirrors the fields the importer writes.
     */
    data class PaperHeader(
        val id: String,
        val title: String,
        val description: String,
        val durationMinutes: Int,
        val negativeMarking: Double
    )

    /**
     * Receives a file while it is still being read, in document order. The
     * callbacks run on the parsing thread and must not retain the questions
     * they are handed: the whole point is that only one batch is live.
     *
     * [onPaper] always precedes the [onCategory]/[onQuestions] calls for that
     * paper, and [onCategory] precedes the questions that belong to it. A
     * category is announced even when it holds no questions, exactly as the
     * whole-file parse keeps an empty category.
     */
    interface ImportStreamSink {
        fun onPaper(header: PaperHeader)
        fun onCategory(category: CategoryDto)
        fun onQuestions(categoryId: String, questions: List<QuestionDto>)

        /**
         * Tail entries, delivered in pages while they are read rather than
         * collected into [StreamedFile]. A file lists `bookmarks`, `scheduling`
         * and `attempts` *after* its papers, so a sink that wants them applied
         * can do so here and never hold one entry per question — which for a
         * studied 1M-question backup is the difference between a page and a
         * million-entry map.
         *
         * Defaults are no-ops, so a sink that only reads papers (the preview,
         * a parser test) ignores them; [StreamedFile] then carries empty
         * lists for those fields when written by
         * [streamImport].
         */
        fun onBookmarks(ids: List<String>) = Unit
        fun onSchedules(entries: List<Pair<String, CardScheduleDto>>) = Unit
        fun onAttempts(attempts: List<AttemptDto>) = Unit
    }

    /**
     * Like [parse], but hands papers/categories/question batches to [sink] as
     * they are read instead of materialising the whole bank, so a single paper
     * with a million questions never exists in memory. Returns the same
     * non-paper tail [streamPapers] returns.
     *
     * Canonical files list a paper's `id`/`title` before its `categories`, and
     * a category's `id`/`title` before its `questions`; this reader relies on
     * that (as every writer in this project does) so it can announce a row
     * before its children. A file that inverts the order still imports, with
     * the invented id/title that was known at the time; [parse] remains the
     * whole-file reader for callers that need exact out-of-order handling.
     */
    fun streamImport(
        input: InputStream,
        sink: ImportStreamSink,
        batchSize: Int = IMPORT_BATCH_SIZE
    ): StreamedFile {
        require(batchSize > 0) { "batchSize must be positive" }
        val warnings = mutableListOf<String>()
        val reader = JsonPull(input)
        val scan = LocalImageScan()
        return when (reader.peek()) {
            JsonPull.Token.BEGIN_ARRAY -> {
                val paperId = EPHEMERAL_PAPER_ID_PREFIX + System.currentTimeMillis().toString(36)
                val categoryId = "$paperId-uncat"
                sink.onPaper(PaperHeader(paperId, "Imported Questions", "", 0, 0.0))
                var announced = false
                val seen = HashSet<String>()
                readQuestionsStreaming(
                    reader, "question", warnings, seen, batchSize, scan,
                    onFirst = {
                        if (!announced) {
                            sink.onCategory(CategoryDto(categoryId, "Uncategorized"))
                            announced = true
                        }
                    },
                    onBatch = { sink.onQuestions(categoryId, it) }
                )
                StreamedFile(1, emptyList(), emptyList(), emptyList(), emptyMap(),
                    warnings + listOfNotNull(scan.warning()))
            }
            JsonPull.Token.BEGIN_OBJECT -> {
                val tail = walkRootObjectStreaming(reader, warnings, sink, batchSize, scan)
                tail.copy(warnings = tail.warnings + listOfNotNull(scan.warning()))
            }
            else -> {
                warnings.add("File root is a scalar or null, not an object or array — no papers found")
                StreamedFile(1, emptyList(), emptyList(), emptyList(), emptyMap(), warnings)
            }
        }
    }

    /**
     * The streaming twin of [walkRootObject]: same keys, same warnings, but a
     * paper is read through [ImportStreamSink] instead of collected.
     */
    private fun walkRootObjectStreaming(
        reader: JsonPull,
        warnings: MutableList<String>,
        sink: ImportStreamSink,
        batchSize: Int,
        scan: LocalImageScan
    ): StreamedFile {
        var passages = emptyList<PassageDto>()
        var version = 1
        var papersEmitted = 0
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "papers" -> papersEmitted += readPapersStreaming(reader, warnings, sink, batchSize, scan)
                "questions" -> {
                    // The DOCX wrapper shape: root questions with no `papers`
                    // become one ephemeral paper. When `papers` is present the
                    // questions are still read (for the malformed-row warnings)
                    // but, as in the whole-file walk, dropped.
                    val paperId = EPHEMERAL_PAPER_ID_PREFIX + System.currentTimeMillis().toString(36)
                    val categoryId = "$paperId-uncat"
                    val asPaper = papersEmitted == 0
                    var announced = false
                    if (asPaper) sink.onPaper(PaperHeader(paperId, "Imported Questions", "", 0, 0.0))
                    val seen = HashSet<String>()
                    readQuestionsStreaming(
                        reader, "question", warnings, seen, batchSize, scan,
                        onFirst = {
                            if (asPaper && !announced) {
                                sink.onCategory(CategoryDto(categoryId, "Uncategorized"))
                                announced = true
                            }
                        },
                        onBatch = { if (asPaper) sink.onQuestions(categoryId, it) }
                    )
                }
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
                "attempts" -> streamArrayOrWarn(
                    reader, "File", "attempts", "attempt", warnings, batchSize,
                    { index, obj ->
                        try {
                            parseAttempt(obj)
                        } catch (e: Exception) {
                            warnings.add("Skipped malformed attempt ${index + 1}: ${e.message ?: e.javaClass.simpleName}")
                            null
                        }
                    }
                ) { sink.onAttempts(it) }
                "bookmarks" -> streamBookmarks(reader, batchSize) { sink.onBookmarks(it) }
                "scheduling" -> streamScheduling(reader, batchSize) { sink.onSchedules(it) }
                "version" -> version = readInt(reader) ?: 1
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return StreamedFile(version, passages, emptyList(), emptyList(), emptyMap(), warnings)
    }

    /** Reads a `papers` array streaming each entry; returns how many emitted. */
    private fun readPapersStreaming(
        reader: JsonPull,
        warnings: MutableList<String>,
        sink: ImportStreamSink,
        batchSize: Int,
        scan: LocalImageScan
    ): Int {
        if (reader.peek() != JsonPull.Token.BEGIN_ARRAY) {
            warnings.add("File: \"papers\" is ${kindOf(reader.peek())}, not a list — its contents were skipped")
            reader.skipValue()
            return 0
        }
        reader.beginArray()
        var index = 0
        var emitted = 0
        while (reader.hasNext()) {
            val at = index + 1
            index = at
            try {
                readPaperStreaming(reader, warnings, sink, batchSize, scan)
                emitted++
            } catch (e: Exception) {
                warnings.add("Skipped malformed paper $at: ${e.message ?: e.javaClass.simpleName}")
                skipValueQuietly(reader)
            }
        }
        reader.endArray()
        return emitted
    }

    /** The streaming twin of [readPaper]. */
    private fun readPaperStreaming(
        reader: JsonPull,
        warnings: MutableList<String>,
        sink: ImportStreamSink,
        batchSize: Int,
        scan: LocalImageScan
    ) {
        var id: String? = null
        var title: String? = null
        var description: String? = null
        var duration: Int? = null
        var negative: Double? = null
        var categoriesSeen = 0
        var headerEmitted = false
        val categorySkips = mutableListOf<Pair<Int, String>>()
        val ephemeralId = EPHEMERAL_PAPER_ID_PREFIX + System.currentTimeMillis().toString(36)
        fun paperId(): String = id?.takeIf { it.isNotBlank() } ?: ephemeralId
        fun emitHeader() {
            if (headerEmitted) return
            sink.onPaper(
                PaperHeader(
                    id = id?.takeIf { it.isNotBlank() } ?: ephemeralId,
                    title = title ?: "Untitled Paper",
                    description = description ?: "",
                    durationMinutes = com.mcqapp.domain.ExamTiming.minutesFrom(duration),
                    negativeMarking = negative ?: 0.0
                )
            )
            headerEmitted = true
        }
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = readString(reader) ?: id
                "title", "name" -> title = readString(reader) ?: title
                "description", "desc" -> description = readString(reader) ?: description
                "durationMinutes", "duration" -> duration = readInt(reader) ?: duration
                "negativeMarking", "negative" -> negative = readDouble(reader) ?: negative
                "categories", "sections", "subjects" -> {
                    emitHeader()
                    categoriesSeen += readCategoriesStreaming(reader, warnings, sink, batchSize, scan, categorySkips)
                }
                "questions" -> {
                    emitHeader()
                    val rootCategoryId = paperId() + "-root"
                    val rootTitle = if (categoriesSeen == 0) (title ?: "Untitled Paper") else "Uncategorized"
                    var announced = false
                    val seen = HashSet<String>()
                    readQuestionsStreaming(
                        reader, "question", warnings, seen, batchSize, scan,
                        onFirst = {
                            if (!announced) {
                                sink.onCategory(CategoryDto(rootCategoryId, rootTitle))
                                announced = true
                            }
                        },
                        onBatch = { sink.onQuestions(rootCategoryId, it) }
                    )
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        emitHeader()
        for ((index, detail) in categorySkips) {
            warnings.add(
                "Skipped malformed category $index in paper '${title ?: "Untitled Paper"}': $detail"
            )
        }
    }

    /** Reads the `categories` array streaming each entry; returns how many. */
    private fun readCategoriesStreaming(
        reader: JsonPull,
        warnings: MutableList<String>,
        sink: ImportStreamSink,
        batchSize: Int,
        scan: LocalImageScan,
        skips: MutableList<Pair<Int, String>>
    ): Int {
        if (reader.peek() != JsonPull.Token.BEGIN_ARRAY) {
            warnings.add("File: \"categories\" is ${kindOf(reader.peek())}, not a list — its contents were skipped")
            reader.skipValue()
            return 0
        }
        reader.beginArray()
        var index = 0
        var count = 0
        while (reader.hasNext()) {
            val at = index + 1
            index = at
            try {
                readCategoryStreaming(reader, warnings, sink, batchSize, scan)
                count++
            } catch (e: Exception) {
                skips += at to (e.message ?: e.javaClass.simpleName)
                skipValueQuietly(reader)
            }
        }
        reader.endArray()
        return count
    }

    /** The streaming twin of [readCategory]. */
    private fun readCategoryStreaming(
        reader: JsonPull,
        warnings: MutableList<String>,
        sink: ImportStreamSink,
        batchSize: Int,
        scan: LocalImageScan
    ) {
        var id: String? = null
        var title: String? = null
        var parentId: String? = null
        var typeMismatch = false
        var wrongShape: String? = null
        var announced = false
        val seen = HashSet<String>()
        fun categoryId(): String = id?.takeIf { it.isNotBlank() }
            ?: "cat-" + System.currentTimeMillis().toString(36) + "-" + (0..9999).random()
        fun announce() {
            if (announced) return
            sink.onCategory(CategoryDto(categoryId(), title ?: "Category", parentId))
            announced = true
        }
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = readString(reader) ?: id
                "title", "name" -> title = readString(reader) ?: title
                "parentId" -> parentId = readString(reader) ?: parentId
                "questions" ->
                    if (reader.peek() == JsonPull.Token.BEGIN_ARRAY) {
                        readQuestionsStreaming(
                            reader, "question", warnings, seen, batchSize, scan,
                            onFirst = { announce() },
                            onBatch = { sink.onQuestions(categoryId(), it) }
                        )
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
        announce()
    }

    /**
     * Streams a `questions` array in batches of [batchSize], applying the same
     * per-list id dedupe [uniqueIds] applies and feeding the local-image scan.
     * [onFirst] fires once, before the first batch, so the owner can announce a
     * category only when it actually has a question.
     */
    private fun readQuestionsStreaming(
        reader: JsonPull,
        label: String,
        warnings: MutableList<String>,
        seen: MutableSet<String>,
        batchSize: Int,
        scan: LocalImageScan,
        onFirst: () -> Unit,
        onBatch: (List<QuestionDto>) -> Unit
    ) {
        if (reader.peek() != JsonPull.Token.BEGIN_ARRAY) {
            reader.skipValue()
            return
        }
        reader.beginArray()
        var index = 0
        val batch = ArrayList<QuestionDto>(minOf(batchSize, 256))
        fun flush() {
            if (batch.isEmpty()) return
            onBatch(ArrayList(batch))
            batch.clear()
        }
        while (reader.hasNext()) {
            val at = ++index
            when (val element = readElement(reader)) {
                is JsonObject -> {
                    val parsed = safeQuestion(element, "$label $at", warnings) ?: continue
                    var uniqueId = parsed.id
                    var n = 2
                    while (!seen.add(uniqueId)) {
                        uniqueId = "${parsed.id}-$n"
                        n++
                    }
                    val unique = if (uniqueId != parsed.id) parsed.copy(id = uniqueId) else parsed
                    onFirst()
                    scan.addQuestion(unique)
                    batch.add(unique)
                    if (batch.size >= batchSize) flush()
                }
                else -> warnings.add("Skipped malformed $label $at: not an object")
            }
        }
        reader.endArray()
        flush()
    }

    private fun readRootObject(reader: JsonPull, warnings: MutableList<String>): McqFileDto {
        val papers = mutableListOf<PaperDto>()
        val tail = walkRootObject(reader, warnings) { papers += it }
        return McqFileDto(
            version = tail.version,
            papers = papers,
            bookmarks = tail.bookmarks,
            attempts = tail.attempts,
            passages = tail.passages,
            warnings = tail.warnings.plus(
                localImageWarning(papers)?.let { listOf(it) } ?: emptyList()
            ),
            scheduling = tail.scheduling
        )
    }

    /**
     * Walks the root object once, handing each paper to [onPaper] as it is
     * read. Both [parse] (which collects them) and [streamPapers] (which
     * forwards them) share this walk, so the streaming path cannot drift from
     * the whole-file one.
     */
    private fun walkRootObject(
        reader: JsonPull,
        warnings: MutableList<String>,
        onPaper: (PaperDto) -> Unit
    ): StreamedFile {
        var topLevelQuestions: List<QuestionDto>? = null
        var passages = emptyList<PassageDto>()
        var attempts = emptyList<AttemptDto>()
        var bookmarks = emptyList<String>()
        var scheduling = emptyMap<String, CardScheduleDto>()
        var version = 1
        var papersEmitted = 0
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "papers" -> papersEmitted += readPapers(reader, warnings, onPaper)
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
        if (papersEmitted == 0 && topLevelQuestions != null) {
            onPaper(ephemeralPaper(topLevelQuestions!!))
        }
        return StreamedFile(version, passages, bookmarks, attempts, scheduling, warnings)
    }

    /** Reads a `papers` array through [onPaper]; returns how many were emitted. */
    private fun readPapers(
        reader: JsonPull,
        warnings: MutableList<String>,
        onPaper: (PaperDto) -> Unit
    ): Int {
        if (reader.peek() != JsonPull.Token.BEGIN_ARRAY) {
            warnings.add("File: \"papers\" is ${kindOf(reader.peek())}, not a list — its contents were skipped")
            reader.skipValue()
            return 0
        }
        reader.beginArray()
        var index = 0
        var emitted = 0
        while (reader.hasNext()) {
            val at = index + 1
            index = at
            try {
                onPaper(readPaper(reader, warnings))
                emitted++
            } catch (e: Exception) {
                warnings.add("Skipped malformed paper $at: ${e.message ?: e.javaClass.simpleName}")
                skipValueQuietly(reader)
            }
        }
        reader.endArray()
        return emitted
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

    /**
     * [readArrayOrWarn], handing the mapped rows to [onPage] in pages instead
     * of collecting them: same warnings, same per-row skips, only the
     * retention differs, so a tail array with a million entries costs one page.
     */
    private fun <T> streamArrayOrWarn(
        reader: JsonPull,
        where: String,
        name: String,
        entryName: String,
        warnings: MutableList<String>,
        pageSize: Int,
        mapper: (Int, JsonObject) -> T?,
        onPage: (List<T>) -> Unit
    ) {
        if (reader.peek() != JsonPull.Token.BEGIN_ARRAY) {
            warnings.add("$where: \"$name\" is ${kindOf(reader.peek())}, not a list — its contents were skipped")
            reader.skipValue()
            return
        }
        val page = ArrayList<T>(pageSize.coerceAtMost(1024))
        reader.beginArray()
        var index = 0
        while (reader.hasNext()) {
            val at = index + 1
            index = at
            val slice = readElement(reader)
            if (slice is JsonObject) {
                mapper(at - 1, slice)?.let(page::add)
            } else {
                warnings.add("Skipped malformed $entryName $at: not an object — it was skipped")
            }
            if (page.size >= pageSize) {
                onPage(page.toList())
                page.clear()
            }
        }
        reader.endArray()
        if (page.isNotEmpty()) onPage(page)
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

    /** [readBookmarks], handing each page of ids to [onPage] instead of collecting. */
    private fun streamBookmarks(reader: JsonPull, pageSize: Int, onPage: (List<String>) -> Unit) {
        if (reader.peek() != JsonPull.Token.BEGIN_ARRAY) {
            reader.skipValue()
            return
        }
        val page = ArrayList<String>(pageSize.coerceAtMost(1024))
        reader.beginArray()
        while (reader.hasNext()) {
            val value = readString(reader)
            if (!value.isNullOrBlank()) page += value
            if (page.size >= pageSize) {
                onPage(page.toList())
                page.clear()
            }
        }
        reader.endArray()
        if (page.isNotEmpty()) onPage(page)
    }

    /** [readScheduling], handing each page of entries to [onPage] instead of collecting. */
    private fun streamScheduling(
        reader: JsonPull,
        pageSize: Int,
        onPage: (List<Pair<String, CardScheduleDto>>) -> Unit
    ) {
        if (reader.peek() != JsonPull.Token.BEGIN_OBJECT) {
            reader.skipValue()
            return
        }
        val page = ArrayList<Pair<String, CardScheduleDto>>(pageSize.coerceAtMost(1024))
        reader.beginObject()
        while (reader.hasNext()) {
            val id = reader.nextName()
            val entry = readElement(reader)
            if (entry is JsonObject) cardSchedule(entry)?.let { page += id to it }
            if (page.size >= pageSize) {
                onPage(page.toList())
                page.clear()
            }
        }
        reader.endObject()
        if (page.isNotEmpty()) onPage(page)
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
        val scan = LocalImageScan()
        for (paper in papers) scan.add(paper)
        return scan.warning()
    }

    /**
     * Accumulates the local-image summary one paper at a time, so a streaming
     * parse can build the same warning without holding every paper. Dedup is
     * by question id, exactly as the whole-file scan did.
     */
    private class LocalImageScan {
        private var images = 0
        private var questions = 0
        private var example = ""
        private val seen = HashSet<String>()

        fun add(paper: PaperDto) {
            // No intermediate lists: a bank can hold hundreds of thousands of
            // questions, and a flatMap would build every one of them at once.
            for (category in paper.categories) {
                for (q in category.questions) addQuestion(q)
            }
        }

        /** One question as it streams past, deduped by id like [add]. */
        fun addQuestion(q: QuestionDto) {
            if (!seen.add(q.id)) return
            val bad = countLocalImages(q)
            if (bad == 0) return
            questions++
            images += bad
            if (example.isEmpty()) example = firstLocalImage(q) ?: ""
        }

        fun warning(): String? {
            if (images == 0) return null
            return "$images image(s) in $questions question(s) point at files on the author's computer " +
                "(e.g. '$example') and will not display after import — " +
                "embed data URIs or import the .apkg instead"
        }
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
