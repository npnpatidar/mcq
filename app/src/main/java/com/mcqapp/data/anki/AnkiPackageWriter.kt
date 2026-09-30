package com.mcqapp.data.anki

import com.mcqapp.data.io.PaperDto
import com.mcqapp.domain.Question
import com.mcqapp.util.Logger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.zip.ZipEntry
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

    /** Anki recognises this as a line break inside a single notefield. */
    const val NEWLINE_BREAK = "<br>"
    private const val LINE_BREAK = NEWLINE_BREAK

    /** The `<br>` that separates one line of a card from the next. */
    const val BR = NEWLINE_BREAK

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
     */
    fun write(paper: PaperDto, questions: List<Question>): ByteArray {
        val media = AnkiMediaPool()
        val nowSeconds = System.currentTimeMillis() / 1000
        val nowMillis = System.currentTimeMillis()

        val categoryPaths = categoryPaths(paper)
        // Deck name -> id, in first-seen order, so a re-export numbers them the
        // same way and Anki matches decks across exports.
        val deckIds = LinkedHashMap<String, Long>()
        deckIds[paper.title.trim()] = DECK_ID
        fun deckIdFor(name: String): Long = deckIds.getOrPut(name) { DECK_ID + deckIds.size }
        questions.forEach { deckIdFor(deckNameFor(paper.title, categoryPaths[it.categoryId])) }

        val notes = mutableListOf<AnkiNoteRow>()
        val cards = mutableListOf<AnkiCardRow>()

        questions.forEachIndexed { index, question ->
            // Ids only have to be unique inside the package; a timestamp base
            // keeps them sortable in the same order as the source paper.
            val noteId = nowMillis + index
            val front = buildFront(question, media)
            val back = buildBack(question, media)
            notes += AnkiNoteRow(
                id = noteId,
                guid = guidFor(paper.id, question.id),
                mod = nowSeconds,
                tags = formatTags(question),
                // Front, Back, then the structured payload. Anki splits fields on
                // the unit separator; the template only renders the first two.
                fields = "$front$FIELD_SEPARATOR$back$FIELD_SEPARATOR" + payloadOf(question).toField(),
                sortField = question.text
            )
            cards += AnkiCardRow(
                id = noteId + 1,
                noteId = noteId,
                mod = nowSeconds,
                // Anki numbers new cards from col.conf.nextPos; only the relative
                // order matters, so an increasing sequence is correct.
                due = index + 1,
                deckId = deckIdFor(deckNameFor(paper.title, categoryPaths[question.categoryId]))
            )
        }
        val collectionBytes = buildCollection(
            notes = notes,
            cards = cards,
            decks = deckIds.map { (name, id) -> id to name },
            nowSeconds = nowSeconds,
            nowMillis = nowMillis
        )

        val apkg = zip(collectionBytes, media)
        Logger.i("EXPORT", "Wrote .apkg with ${notes.size} notes, ${media.entries.size} media files")
        return apkg
    }

    /**
     * The front of the card: the question followed by its options, so reviewing
     * in Anki works like answering an MCQ rather than recalling an answer.
     */
    private fun buildFront(question: Question, media: AnkiMediaPool): String {
        val sb = StringBuilder()
        sb.append(media.htmlField(question.text, question.image))
        sb.append(LINE_BREAK).append(LINE_BREAK)
        if (question.correctOptionIds.size > 1) {
            // Options alone would imply exactly one of them is right.
            sb.append("<b>Select all that apply.</b>").append(LINE_BREAK)
        }
        question.options.forEachIndexed { i, option ->
            sb.append("<b>").append(letterFor(i)).append(".</b> ")
            sb.append(media.htmlField(option.text, option.image))
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
    private fun categoryPaths(paper: PaperDto): Map<String, String> {
        val byId = paper.categories.associateBy { it.id }
        val paths = mutableMapOf<String, String>()
        fun pathOf(id: String, seen: Set<String>): String = paths.getOrPut(id) {
            val category = byId[id] ?: return@getOrPut ""
            if (id in seen) return@getOrPut ""
            val parent = category.parentId
            val prefix = parent?.let { pathOf(it, seen + id) }?.takeIf { it.isNotBlank() }
            val name = category.title.trim()
            if (prefix == null) name else "$prefix::$name"
        }
        paper.categories.forEach { pathOf(it.id, emptySet()) }
        return paths
    }

    /** The Anki deck name for a question: the paper, plus its category path. */
    private fun deckNameFor(paperTitle: String, categoryPath: String?): String {
        val root = paperTitle.trim().ifBlank { "Untitled" }
        return if (categoryPath.isNullOrBlank()) root else "$root::$categoryPath"
    }

    private fun buildBack(question: Question, media: AnkiMediaPool): String {
        val sb = StringBuilder()
        question.options.forEachIndexed { i, option ->
            if (option.id !in question.correctOptionIds) return@forEachIndexed
            sb.append("&#10003; <b>").append(letterFor(i)).append(".</b> ")
            sb.append(media.htmlField(option.text, option.image))
            sb.append(LINE_BREAK)
        }
        if (question.explanation.isNotBlank()) {
            if (sb.isNotEmpty()) sb.append("<br>")
            sb.append("<b>Explanation:</b> ")
                .append(media.htmlField(question.explanation, question.explanationImage))
        }
        return sb.toString().trim()
    }

    private fun letterFor(index: Int): String =
        if (index < 26) ('A' + index).toString() else "(${index / 26}${'A' + index % 26})"

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

    private fun buildCollection(
        notes: List<AnkiNoteRow>,
        cards: List<AnkiCardRow>,
        decks: List<Pair<Long, String>>,
        nowSeconds: Long,
        nowMillis: Long
    ): ByteArray {
        val file = File.createTempFile("anki-export", ".anki2")
        try {
            val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
            AnkiSchema11.create(db)
            AnkiSchema11.writeColRow(
                db = db,
                crt = nowSeconds,
                mod = nowMillis,
                conf = AnkiSchema11.configJson(
                    modelId = MODEL_ID,
                    nextPos = notes.size + 1,
                    deckIds = decks.map { it.first }
                ),
                models = AnkiSchema11.modelsJson(MODEL_ID, nowSeconds),
                decks = AnkiSchema11.decksJson(decks, nowSeconds),
                dconf = AnkiSchema11.dconfJson(DECK_CONFIG_ID, nowSeconds)
            )
            AnkiSchema11.insertNotes(db, notes)
            AnkiSchema11.insertCards(db, cards)
            db.close()
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private fun zip(collection: ByteArray, media: AnkiMediaPool): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // The database is deflated; media and its manifest are stored, which
            // is what Anki itself writes and keeps image bytes seekable.
            zip.putNextEntry(ZipEntry("collection.anki2"))
            zip.write(collection)
            zip.closeEntry()

            if (media.entries.isNotEmpty()) {
                zip.putNextEntry(ZipEntry("media"))
                // Anki's import uses the manifest's original filenames, not the
                // numeric zip names, when rewriting <img> references and storing
                // the files.
                val manifest = buildJsonObject {
                    media.manifest.forEach { (numeric, original) -> put(numeric, original) }
                }
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                media.entries.forEach { (numeric, bytes) ->
                    zip.putNextEntry(ZipEntry(numeric))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
        return out.toByteArray()
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
     * One row of Anki's `cards` table. Every field is an initial value for a
     * brand-new card except [due], which sets the new-card ordering position.
     */
    data class AnkiCardRow(
        val id: Long,
        val noteId: Long,
        val mod: Long,
        val due: Int,
        val deckId: Long
    )
}