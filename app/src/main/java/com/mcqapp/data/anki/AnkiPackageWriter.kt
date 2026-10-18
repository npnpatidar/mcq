package com.mcqapp.data.anki

import com.mcqapp.data.export.PaperStream
import com.mcqapp.data.export.optionLetter
import com.mcqapp.data.io.CardScheduleDto
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.Question
import com.mcqapp.util.Logger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import kotlin.random.Random
import java.util.zip.ZipOutputStream

/**
 * Writes an Anki `.apkg` package (schema 11, "legacy" layout) that real Anki
 * can import.
 *
 * An `.apkg` is a zip holding three kinds of entry, all at the root:
 *  - `collection.anki2` — a SQLite database using Anki's schema 11.
 *  - `media` — JSON mapping each numeric zip entry name to its original filename.
 *  - `0`, `1`, `2`, ... — the media file bytes, stored flat and uncompressed.
 *
 * Schema 11 is chosen deliberately. When a package has no `meta` entry Anki
 * falls back to locating `collection.anki2` and treats it as schema 11 (its
 * `Legacy1` path), which every Anki client from 2.1.x onwards can read. Anki
 * upgrades that schema to the current one as part of opening the package, so
 * writing an old-but-stable schema is the maximum-compatibility option.
 *
 * The note type is Basic (one field pair, one card). Anki's importer does not
 * generate cards from templates — it imports whatever `cards` rows exist and
 * silently drops any whose `nid` was not imported — so one card row per note is
 * written here explicitly.
 */
object AnkiPackageWriter {

    const val SCHEMA_VERSION = 11

    /** Anki separates notefields with the ASCII unit separator, not a newline. */
    const val FIELD_SEPARATOR = "\u001f"

    /** Depth separator in a deck name: `Paper::Category::Subcategory`. */
    const val DECK_SEPARATOR = "::"

    /** Anki recognises this as a line break inside a single notefield. */
    const val NEWLINE_BREAK = "<br>"
    private const val LINE_BREAK = NEWLINE_BREAK

    /** The `<br>` that separates one line of a card from the next. */
    const val BR = NEWLINE_BREAK

    /**
     * Notes inserted per transaction when building an export collection, so a
     * 100k-note deck does not have to hold every row before writing any.
     */
    private const val NOTE_BATCH = 500

    /** Fixed ids keep the output deterministic and diffable across exports. */
    const val DECK_ID = 1L
    const val DECK_CONFIG_ID = 1L
    const val MODEL_ID = 1_600_000_000_000L

    /**
     * Builds the package for one paper.
     *
     * Each question becomes one Anki note: the front is the question with its
     * options, the back is the correct answers and the explanation. Media
     * references in either field are rewritten to the media filenames and the
     * bytes are appended to the package.
     *
     * Categories become Anki subdecks (`Paper::Category::Subcategory`), so
     * importing the package back restores the paper's structure. Questions with
     * no category go into the paper's own deck.
     *
     * The package is written straight to [out]: the paper is walked a question
     * at a time, notes are built and inserted into the export collection
     * [NOTE_BATCH] at a time, and the finished collection is copied into the
     * zip in chunks. A 100k-note deck therefore never holds its questions, its
     * notes, its collection and its archive in memory together.
     *
     * The deck list is written last, after the questions have named their
     * decks, so no pass is spent finding out which categories hold questions.
     * `col` is an ordinary row in an ordinary table: its position relative to
     * the notes means nothing to a reader of the finished database.
     *
     * @param schedulingFor review progress for a batch of question ids. A
     * question without an entry is written as a new card, which is what a
     * shared deck should be; with an entry its intervals and ease travel with
     * it. The collection is created at export time, so due days are counted
     * from then. It is asked per batch rather than handed the paper's whole
     * `card_state`, which was every studied card the paper had — ~100 MB of
     * entities at a million.
     */
    suspend fun writeTo(
        stream: PaperStream,
        out: OutputStream,
        schedulingFor: suspend (List<String>) -> Map<String, CardScheduleDto> = { emptyMap() }
    ) {
        val media = AnkiMediaPool()
        val nowSeconds = System.currentTimeMillis() / 1000
        val nowMillis = System.currentTimeMillis()
        // Random per-export component: same-millisecond exports must not share
        // an id sequence (see the note-id loop below).
        val idBase = nowMillis + Random.nextLong(1, 1_000_000_000)

        val paperTitle = stream.title
        val passages = stream.passages.associate { it.id to AnkiDtoMapper.toPassage(it) }
        val categoryPaths = categoryPaths(stream.categories)
        // Deck name -> id, in first-seen order, so a re-export numbers them the
        // same way and Anki matches decks across exports.
        val deckIds = LinkedHashMap<String, Long>()
        deckIds[paperTitle.trim()] = DECK_ID
        fun deckIdFor(name: String): Long = deckIds.getOrPut(name) { DECK_ID + deckIds.size }

        // Counts the notes the walk writes, which is also what `col.conf.nextPos`
        // and the log line report. Declared out here so both can see it.
        var index = 0

        val file = File.createTempFile("anki-export", ".anki2")
        try {
            val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
            try {
                AnkiSchema11.create(db)
                val notes = ArrayList<AnkiNoteRow>(NOTE_BATCH)
                val pending = ArrayList<PendingCard>(NOTE_BATCH)

                suspend fun flush() {
                    if (notes.isEmpty()) return
                    val scheduling = schedulingFor(pending.map { it.questionId })
                    val cards = pending.map { row ->
                        val card = scheduling[row.questionId]
                            ?.let { AnkiScheduling.toAnki(it, nowSeconds, nowMillis) }
                            ?: AnkiScheduling.NEW_CARD
                        AnkiCardRow(
                            id = row.noteId + 1,
                            noteId = row.noteId,
                            mod = nowSeconds,
                            // Anki numbers new cards from col.conf.nextPos; only the
                            // relative order matters, so an increasing sequence is
                            // correct.
                            due = if (card.queue == AnkiScheduling.QUEUE_NEW) row.index + 1 else card.due,
                            deckId = row.deckId,
                            type = card.type,
                            queue = card.queue,
                            interval = card.ivl,
                            factor = card.factor,
                            reps = card.reps,
                            lapses = card.lapses
                        )
                    }
                    AnkiSchema11.insertNotes(db, notes)
                    AnkiSchema11.insertCards(db, cards)
                    notes.clear()
                    pending.clear()
                }

                suspend fun writeNote(dto: QuestionDto, categoryId: String) {
                    // Ids only have to be unique inside the package; a timestamp base
                    // keeps them sortable in the same order as the source paper. The
                    // per-export salt keeps two packages exported in the same
                    // millisecond (on this device or different ones) from reusing
                    // the same id sequence, which would collide when both are
                    // imported into one Anki collection.
                    val noteId = idBase + index
                    val question = AnkiDtoMapper.toQuestion(dto, categoryId)
                    val passage = question.passageId?.let { passages[it] }
                    val front = buildFront(question, media, passage)
                    val back = buildBack(question, media)
                    notes += AnkiNoteRow(
                        id = noteId,
                        guid = guidFor(stream.id, question.id),
                        mod = nowSeconds,
                        tags = formatTags(question),
                        // Front, Back, then the structured payload. Anki splits fields on
                        // the unit separator; the template only renders the first two.
                        fields = "$front$FIELD_SEPARATOR$back$FIELD_SEPARATOR" +
                            payloadOf(question, passage).toField(),
                        sortField = question.text
                    )
                    pending += PendingCard(
                        noteId = noteId,
                        questionId = question.id,
                        index = index,
                        deckId = deckIdFor(deckNameFor(paperTitle, categoryPaths[categoryId]))
                    )
                    index++
                    if (notes.size >= NOTE_BATCH) flush()
                }

                var categoryId = ""
                stream.walk(
                    onCategory = { category -> categoryId = category.id },
                    onQuestion = { dto -> writeNote(dto, categoryId) }
                )
                // A paper's own question list (the shape a foreign file can
                // carry) goes to the paper's deck. It is disjoint from the
                // walk by construction: the app groups every question into a
                // category on import, and its own exports always group.
                for (dto in stream.looseQuestions) writeNote(dto, "")
                flush()

                AnkiSchema11.writeColRow(
                    db = db,
                    crt = nowSeconds,
                    mod = nowMillis,
                    conf = AnkiSchema11.configJson(
                        modelId = MODEL_ID,
                        // One note per question, and Anki numbers new cards from
                        // here. The walk wrote one note per question, so the
                        // count is what it counted.
                        nextPos = index + 1,
                        deckIds = deckIds.map { it.value }
                    ),
                    models = AnkiSchema11.modelsJson(MODEL_ID, nowSeconds),
                    decks = AnkiSchema11.decksJson(deckIds.map { (name, id) -> id to name }, nowSeconds),
                    dconf = AnkiSchema11.dconfJson(DECK_CONFIG_ID, nowSeconds)
                )
            } finally {
                db.close()
            }
            zip(file, media, out)
        } finally {
            media.close()
            if (!file.delete()) {
                Logger.w("ANKI", "Could not delete temp collection ${file.absolutePath}")
            }
        }
        Logger.i("EXPORT", "Wrote .apkg with $index notes, ${media.size} media files")
    }

    /** A card waiting for its batch's scheduling rows before it is written. */
    private data class PendingCard(
        val noteId: Long,
        val questionId: String,
        val index: Int,
        val deckId: Long
    )

    /**
     * The front of the card: the passage (when the question shares one), the
     * question, then its options — reviewing in Anki works like answering an
     * MCQ, and a comprehension question needs its context on the card.
     */
    private fun buildFront(
        question: Question,
        media: AnkiMediaPool,
        passage: com.mcqapp.domain.Passage?
    ): String {
        val sb = StringBuilder()
        if (passage != null) {
            sb.append("<div class=\"mcqapp-passage\"><b>")
                .append(AnkiMediaPool.escapeHtml(passage.title.ifBlank { "Passage" }))
                .append("</b>")
                .append(LINE_BREAK)
                .append(media.elementsToHtml(passage.elements, passage.image))
                .append("</div>")
                .append(LINE_BREAK).append(LINE_BREAK)
                .append("<hr>")
                .append(LINE_BREAK).append(LINE_BREAK)
        }
        sb.append(media.elementsToHtml(question.elements, question.image))
        sb.append(LINE_BREAK).append(LINE_BREAK)
        if (question.correctOptionIds.size > 1) {
            // Options alone would imply exactly one of them is right.
            sb.append("<b>Select all that apply.</b>").append(LINE_BREAK)
        }
        question.options.forEachIndexed { i, option ->
            sb.append("<b>").append(optionLetter(i)).append(".</b> ")
            sb.append(media.elementsToHtml(option.elements, option.image))
            sb.append(LINE_BREAK)
        }
        return sb.toString().trim()
    }

    /**
     * The back of the card: which options were correct and why. The options
     * themselves stay on the front, where the question was asked.
     */
    /**
     * Maps a category id to its `Parent::Child` path, which becomes the Anki
     * subdeck name. Cycles in the category tree are broken rather than
     * recursed into.
     */
    private fun categoryPaths(categories: List<CategoryDto>): Map<String, String> {
        val byId = categories.associateBy { it.id }
        val paths = mutableMapOf<String, String>()
        fun pathOf(id: String, seen: Set<String>): String = paths.getOrPut(id) {
            val category = byId[id] ?: return@getOrPut ""
            if (id in seen) return@getOrPut ""
            val parent = category.parentId
            val prefix = parent?.let { pathOf(it, seen + id) }?.takeIf { it.isNotBlank() }
            val name = segmentFor(category.title, "Untitled")
            if (prefix == null) name else "$prefix::$name"
        }
        categories.forEach { pathOf(it.id, emptySet()) }
        return paths
    }

    /** The Anki deck name for a question: the paper, plus its category path. */
    private fun deckNameFor(paperTitle: String, categoryPath: String?): String {
        val root = segmentFor(paperTitle, "Untitled")
        return if (categoryPath.isNullOrBlank()) root else "$root::$categoryPath"
    }

    /**
     * One `::`-delimited part of a deck name.
     *
     * The separator is how a deck name encodes its depth, and Anki offers no
     * way to escape it, so a title containing it would come back from the
     * reader as a hierarchy the paper never had. The characters are replaced
     * rather than dropped so two distinct titles stay distinct.
     */
    private fun segmentFor(title: String, fallback: String): String {
        val clean = title.trim().replace(DECK_SEPARATOR, ": ").trim()
        if (clean.isNotBlank()) return clean
        if (title != fallback) {
            Logger.w("EXPORT", "Deck name '$title' is blank after cleaning, using '$fallback'")
        }
        return fallback
    }

    private fun buildBack(question: Question, media: AnkiMediaPool): String {
        val sb = StringBuilder()
        question.options.forEachIndexed { i, option ->
            if (option.id !in question.correctOptionIds) return@forEachIndexed
            sb.append("&#10003; <b>").append(optionLetter(i)).append(".</b> ")
            sb.append(media.elementsToHtml(option.elements, option.image))
            sb.append(LINE_BREAK)
        }
        if (question.explanationElements.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("<br>")
            sb.append("<b>Explanation:</b> ")
                .append(media.elementsToHtml(question.explanationElements, question.explanationImage))
        }
        return sb.toString().trim()
    }

    /** Anki stores tags space-delimited with a leading and trailing space. */
    private fun formatTags(question: Question): String {
        val tags = buildList {
            add("mcqapp")
            add("mcqapp-difficulty-${question.difficulty.label.lowercase()}")
            // Anki treats spaces as tag separators, so a multi-word tag is
            // normalised to hyphens rather than silently split.
            question.tags.forEach { add(it.trim().replace(Regex("\\s+"), "-").ifBlank { "untagged" }) }
        }.filter { it.isNotBlank() }.distinct()
        return " ${tags.joinToString(" ")} "
    }

    fun imageTag(src: String): String =
        "<img src=\"${src.replace("&", "&amp;").replace("\"", "&quot;")}\">"

    /**
     * A stable, Anki-shaped guid derived from the question's identity rather
     * than from when it was exported. Anki keys deduplication on this value, so
     * re-exporting a paper and importing it again updates the notes in place
     * instead of creating duplicates.
     */
    fun guidFor(paperId: String, questionId: String): String {
        // Anki guids are base91 of a u32 hash; a 64-bit FNV hash folded into 32
        // bits gives the same shape and stays stable across exports.
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest("$paperId/$questionId".toByteArray())
        var n = 0L
        repeat(4) { i -> n = (n shl 8) or (digest[i].toLong() and 0xFF) }
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#$%&()*+,-./:;<=>?@[]^_`{|}~"
        return buildString(10) {
            var v = n
            repeat(10) {
                v = v * 2_654_435_761L + 1
                append(alphabet[((v ushr 33) % alphabet.length).toInt()])
            }
        }
    }

    /**
     * Zips the temp collection into [out]. The collection is copied in chunks
     * rather than read whole, so the database bytes and the archive never sit
     * in memory together.
     */
    private fun zip(collection: File, media: AnkiMediaPool, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            // The database is deflated; media and its manifest are stored, which
            // is what Anki itself writes and keeps image bytes seekable.
            zip.putNextEntry(ZipEntry("collection.anki2"))
            collection.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()

            if (media.isNotEmpty) {
                zip.putNextEntry(ZipEntry("media"))
                // Anki's import uses the manifest's original filenames, not the
                // numeric zip names, when rewriting <img> references and storing
                // the files.
                val manifest = buildJsonObject {
                    media.manifest.forEach { (numeric, original) -> put(numeric, original) }
                }
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // Spooled to temp files by the pool, so a deck's pictures are
                // never all in memory at once.
                media.names.forEach { numeric ->
                    zip.putNextEntry(ZipEntry(numeric))
                    media.copyTo(numeric, zip)
                    zip.closeEntry()
                }
            }
        }
    }

    /** One row of Anki's `notes` table. */
    data class AnkiNoteRow(
        val id: Long,
        val guid: String,
        val mod: Long,
        val tags: String,
        val fields: String,
        /**
         * The question text alone, for Anki's `sfld` sort field. The front
         * field also holds the options, and Anki's browser and duplicate check
         * work off this column, so it must not.
         */
        val sortField: String
    )

    /**
     * One row of Anki's `cards` table. The defaults are the initial values of a
     * brand-new card, where [due] is the new-card ordering position rather than
     * a date; see [AnkiScheduling] for what each field means once reviewed.
     */
    data class AnkiCardRow(
        val id: Long,
        val noteId: Long,
        val mod: Long,
        val due: Int,
        val deckId: Long,
        val type: Int = AnkiScheduling.TYPE_NEW,
        val queue: Int = AnkiScheduling.QUEUE_NEW,
        val interval: Int = 0,
        val factor: Int = 0,
        val reps: Int = 0,
        val lapses: Int = 0
    )
}