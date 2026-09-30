package com.mcqapp.data.anki

import android.database.sqlite.SQLiteDatabase
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.util.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipInputStream

/** What a package contained, for the import report. */
data class AnkiReadResult(
    val file: McqFileDto,
    val noteCount: Int,
    val recallCount: Int,
    val deckCount: Int,
    /**
     * Review progress keyed by the question id in [file], so a deck studied
     * for months keeps its intervals instead of restarting at zero. Empty when
     * a package carries no schedule, such as a shared deck of new cards.
     */
    val scheduling: Map<String, com.mcqapp.data.io.CardScheduleDto> = emptyMap()
)

/** Raised when a package cannot be read at all. */
class AnkiPackageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads an Anki `.apkg` package into this app's import DTO.
 *
 * Handles both layouts Anki produces: the legacy zip (schema 11, plain
 * `collection.anki2` + a `media` manifest) and the modern one (schema 14+,
 * `collection.anki21`/`collection.anki21b` with per-table notetype and deck
 * storage). A package with no `meta` entry is the legacy form, which is what
 * this app exports.
 *
 * Deck names encode their hierarchy with `::`, so `Biology::Cells` becomes a
 * paper "Biology" with a category "Cells". Each top-level deck becomes its own
 * paper.
 *
 * Questions come from a note's structured payload field when present (an exact
 * round trip of our own exports). Otherwise the readable back field is parsed
 * for `✓`/`✗` markers, and a note with no markers becomes a single-option
 * recall question.
 */
object AnkiPackageReader {

    private const val SCHEMA_14 = 14
    private val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())
    private val json = Json { ignoreUnknownKeys = true }

    private val COLLECTION_NAMES = listOf(
        "collection.anki2",
        "collection.anki21",
        "collection.anki21b"
    )
    private const val DEFAULT_DECK_ID = 1L
    private const val SEPARATOR = AnkiPackageWriter.FIELD_SEPARATOR

    fun read(bytes: ByteArray): AnkiReadResult {
        val entries = unzip(bytes)
        val collectionName = entries.keys.firstOrNull { it in COLLECTION_NAMES }
            ?: throw AnkiPackageException(
                "No Anki collection found in this package (looked for ${COLLECTION_NAMES.joinToString()})"
            )
        val collectionBytes = entries.getValue(collectionName)

        if (collectionBytes.size >= 4 && ZSTD_MAGIC.indices.all { collectionBytes[it] == ZSTD_MAGIC[it] }) {
            throw AnkiPackageException(
                "This is a zstd-compressed .colpkg, which needs Anki 2.1.50+ to unpack. " +
                    "Export the deck as .apkg from Anki and import that instead."
            )
        }

        val media = MediaIndex(entries["media"]?.let { String(it, Charsets.UTF_8) }, entries)
        val file = File.createTempFile("anki-import", ".anki2")
        return try {
            file.writeBytes(collectionBytes)
            val db = SQLiteDatabase.openOrCreateDatabase(file, null)
            try {
                readCollection(db, media)
            } finally {
                db.close()
            }
        } catch (e: AnkiPackageException) {
            throw e
        } catch (e: Exception) {
            throw AnkiPackageException("Could not read the Anki collection: ${e.message}", e)
        } finally {
            file.delete()
        }
    }

    private fun readCollection(db: SQLiteDatabase, media: MediaIndex): AnkiReadResult {
        val col = db.rawQuery("select ver, models, decks, crt from col", null).use { c ->
            if (!c.moveToFirst()) throw AnkiPackageException("The Anki collection is empty")
            ColMeta(c.getInt(0), c.getString(1), c.getString(2), c.getLong(3))
        }
        val ver = col.version
        Logger.i("IMPORT", "Reading Anki collection, schema version $ver")

        val notetypes = if (ver >= SCHEMA_14) readNotetypes14(db) else readNotetypes11(col.models)
        val decks = if (ver >= SCHEMA_14) readDecks14(db) else readDecks11(col.decks)
        if (decks.isEmpty()) throw AnkiPackageException("The Anki collection has no decks")

        val notes = readNotes(db, media)
        val cards = readCards(db)
        val lastReviews = readLastReviewTimes(db)
        // One note can hold several cards (a cloze note produces one per
        // deletion); without a card we still import the note once.
        val cardsByNote = cards.groupBy { it.nid }
        val noteById = notes.associateBy { it.id }

        var recallCount = 0
        var unscheduled = 0
        val questionsByDeck = LinkedHashMap<Long, MutableList<QuestionDto>>()
        val scheduling = LinkedHashMap<String, com.mcqapp.data.io.CardScheduleDto>()
        notes.forEach { note ->
            val template = notetypes[note.mid]
            val noteCards = cardsByNote[note.id].orEmpty()
            val instances = if (noteCards.isEmpty()) listOf(null) else noteCards.map { it.ord }
            instances.forEach { ord ->
                val parsed = toQuestion(note, template, ord, media) ?: return@forEach
                if (parsed.isRecall) recallCount++
                val card = noteCards.firstOrNull { it.ord == ord }
                val deckId = card?.did ?: DEFAULT_DECK_ID
                questionsByDeck.getOrPut(deckId) { mutableListOf() }.add(parsed.question)
                if (card != null) {
                    val state = AnkiScheduling.fromAnki(
                        type = card.type,
                        queue = card.queue,
                        due = card.due,
                        ivl = card.ivl,
                        factor = card.factor,
                        reps = card.reps,
                        lapses = card.lapses,
                        crtSeconds = col.crtSeconds,
                        lastReviewedAtMillis = lastReviews[card.id] ?: 0L
                    )
                    if (state == null) unscheduled++ else scheduling[parsed.question.id] = state
                }
            }
        }
        if (noteById.isEmpty()) throw AnkiPackageException("The Anki collection has no notes")

        val papers = buildPapers(decks, questionsByDeck)
        val noteCount = questionsByDeck.values.sumOf { it.size }
        Logger.i(
            "IMPORT",
            "Anki package: ${decks.size} decks, $noteCount questions, $recallCount without options, " +
                "${scheduling.size} scheduled, $unscheduled new or not due"
        )
        return AnkiReadResult(
            file = McqFileDto(version = 1, papers = papers),
            noteCount = noteCount,
            recallCount = recallCount,
            deckCount = decks.size,
            scheduling = scheduling
        )
    }

    // ---- schema 11 (col.models / col.decks JSON) ----

    private fun readNotetypes11(models: String): Map<Long, AnkiNotetype> {
        val root = try {
            json.parseToJsonElement(models).jsonObject
        } catch (e: Exception) {
            throw AnkiPackageException("The Anki collection's notetypes are unreadable", e)
        }
        return root.mapNotNull { (key, element) ->
            val nt = element.jsonObject
            val id = key.toLongOrNull() ?: nt["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            val fields = nt["flds"]?.jsonArray?.map { f ->
                AnkiField(f.jsonObject["name"]?.jsonPrimitive?.content.orEmpty())
            }.orEmpty()
            val isCloze = (nt["type"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0) == 1
            id to AnkiNotetype(
                name = nt["name"]?.jsonPrimitive?.content.orEmpty(),
                fields = fields,
                isCloze = isCloze
            )
        }.toMap()
    }

    private fun readDecks11(decks: String): List<AnkiDeck> {
        val root = try {
            json.parseToJsonElement(decks).jsonObject
        } catch (e: Exception) {
            throw AnkiPackageException("The Anki collection's decks are unreadable", e)
        }
        return root.mapNotNull { (key, element) ->
            val d = element.jsonObject
            val id = key.toLongOrNull() ?: return@mapNotNull null
            // dyn=1 marks a filtered (temporary) deck; it is not a real deck.
            if ((d["dyn"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) == 1.0) return@mapNotNull null
            AnkiDeck(id, d["name"]?.jsonPrimitive?.content.orEmpty())
        }
    }

    // ---- schema 14+ (notetypes/fields/templates and decks tables) ----

    private fun readNotetypes14(db: SQLiteDatabase): Map<Long, AnkiNotetype> {
        val names = LinkedHashMap<Long, MutableList<String>>()
        db.rawQuery("select ntid, name from fields order by ntid, ord", null).use { c ->
            while (c.moveToNext()) names.getOrPut(c.getLong(0)) { mutableListOf() } += c.getString(1)
        }
        val kind = HashMap<Long, Int>()
        db.rawQuery("select id, config from notetypes", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                // kind 1 is cloze. The config blob is JSON in every schema we
                // accept, but tolerate an opaque blob rather than failing.
                val k = try {
                    json.parseToJsonElement(String(c.getBlob(1) ?: ByteArray(0)))
                        .jsonObject["kind"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                } catch (e: Exception) {
                    0
                }
                kind[id] = k
            }
        }
        return names.mapValues { (id, fieldNames) ->
            AnkiNotetype(
                name = "",
                fields = fieldNames.map { AnkiField(it) },
                isCloze = kind[id] == 1
            )
        }
    }

    private fun readDecks14(db: SQLiteDatabase): List<AnkiDeck> {
        val out = mutableListOf<AnkiDeck>()
        db.rawQuery("select id, name, kind from decks order by id", null).use { c ->
            while (c.moveToNext()) {
                // kind 1 is a filtered deck; it is not a real deck.
                val isDynamic = try {
                    json.parseToJsonElement(String(c.getBlob(2) ?: ByteArray(0)))
                        .jsonObject.keys.contains("terms")
                } catch (e: Exception) {
                    false
                }
                if (isDynamic) continue
                out += AnkiDeck(c.getLong(0), c.getString(1))
            }
        }
        return out
    }

    // ---- notes and cards ----

    private fun readNotes(db: SQLiteDatabase, media: MediaIndex): List<AnkiNote> {
        val out = mutableListOf<AnkiNote>()
        db.rawQuery("select id, guid, mid, tags, flds from notes order by id", null).use { c ->
            while (c.moveToNext()) {
                out += AnkiNote(
                    id = c.getLong(0),
                    guid = c.getString(1).orEmpty(),
                    mid = c.getLong(2),
                    tags = parseTags(c.getString(3).orEmpty()),
                    fields = c.getString(4).orEmpty().split(SEPARATOR)
                )
            }
        }
        return out
    }

    private fun readCards(db: SQLiteDatabase): List<AnkiCard> {
        val out = mutableListOf<AnkiCard>()
        db.rawQuery(
            "select id, nid, did, ord, type, queue, due, ivl, factor, reps, lapses " +
                "from cards order by id",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out += AnkiCard(
                    id = c.getLong(0),
                    nid = c.getLong(1),
                    did = c.getLong(2),
                    ord = c.getInt(3),
                    type = c.getInt(4),
                    queue = c.getInt(5),
                    due = c.getInt(6),
                    ivl = c.getInt(7),
                    factor = c.getInt(8),
                    reps = c.getInt(9),
                    lapses = c.getInt(10)
                )
            }
        }
        return out
    }

    /**
     * Each card's most recent review time, in millis, from the review log. The
     * log is the only record of when a card was last seen; without it the
     * schedule falls back to the interval, which is close enough for a card
     * that has not been overdue. A package may legitimately not carry one.
     */
    private fun readLastReviewTimes(db: SQLiteDatabase): Map<Long, Long> = try {
        val out = LinkedHashMap<Long, Long>()
        db.rawQuery("select cid, max(time) from revlog group by cid", null).use { c ->
            while (c.moveToNext()) {
                if (!c.isNull(1)) out[c.getLong(0)] = c.getLong(1) * 1000L
            }
        }
        out
    } catch (e: Exception) {
        Logger.w("IMPORT", "No usable review log in this package: ${e.message}")
        emptyMap()
    }

    // ---- note -> question ----

    /** A question plus whether it had to be reduced to a single option. */
    private data class ParsedQuestion(val question: QuestionDto, val isRecall: Boolean)

    private fun toQuestion(
        note: AnkiNote,
        notetype: AnkiNotetype?,
        ord: Int?,
        media: MediaIndex
    ): ParsedQuestion? {
        val payload = payloadFromField(note.fields.getOrNull(2))
        val frontHtml = note.fields.firstOrNull().orEmpty()

        if (notetype != null && notetype.isCloze) {
            return clozeQuestion(note, frontHtml, ord ?: 0, media)?.let {
                ParsedQuestion(it, isRecall = true)
            }
        }

        val questionId = "anki-${note.guid.ifBlank { note.id }}"
        val front = fieldToText(frontHtml, media)
        if (front.first.isBlank()) return null

        val backHtml = note.fields.getOrNull(1).orEmpty()
        if (payload != null) {
            // Our own export: the front also holds the options, so only the text
            // above the first lettered option is the question.
            return ParsedQuestion(QuestionDto(
                id = questionId,
                text = AnkiHtml.questionTextFromFront(front.first),
                image = front.second,
                options = payload.options.map {
                    OptionDto(it.id, AnkiHtml.toPlainText(it.text), media.rewrite(it.image))
                },
                correctOptionIds = payload.correct,
                explanation = AnkiHtml.toPlainText(payload.explanation),
                explanationImage = media.rewrite(payload.explanationImage),
                difficulty = payload.difficulty,
                marks = payload.marks,
                tags = (payload.tags + note.tags).distinct()
            ), isRecall = false)
        }

        val back = AnkiHtml.parseBackField(media.rewrite(backHtml))
        if (back.hasMarkers) {
            val options = back.options.mapIndexed { i, opt ->
                OptionDto("o$i", AnkiHtml.toPlainText(opt.text))
            }
            return ParsedQuestion(
                QuestionDto(
                    id = questionId,
                    text = front.first,
                    image = front.second,
                    options = options,
                    correctOptionIds = options.filterIndexed { i, _ -> back.options[i].correct }.map { it.id },
                    explanation = AnkiHtml.toPlainText(back.explanation),
                    tags = note.tags
                ),
                isRecall = false
            )
        }

        // A foreign note with no options: keep the content as a single-option
        // recall question rather than dropping it.
        val answer = fieldToText(backHtml, media)
        return ParsedQuestion(
            QuestionDto(
                id = questionId,
                text = front.first,
                image = front.second,
                options = listOf(OptionDto("o0", answer.first.ifBlank { front.first }, answer.second)),
                correctOptionIds = listOf("o0"),
                tags = note.tags
            ),
            isRecall = true
        )
    }

    /**
     * Splits a field into its text and the first image it references. The image
     * becomes the question's own image rather than markup inside the text.
     */
    private fun fieldToText(html: String, media: MediaIndex): Pair<String, String?> {
        val rewritten = media.rewrite(html).orEmpty()
        val image = IMG_TAG.find(rewritten)?.groupValues?.get(1)
        return AnkiHtml.toPlainText(IMG_TAG.replace(rewritten, "")) to image
    }

    /**
     * Splits a cloze note into one question per card. Anki generates a card per
     * deletion and sets that deletion's ord in the field it blanks, so the
     * hidden text is the answer.
     */
    private fun clozeQuestion(note: AnkiNote, frontHtml: String, ord: Int, media: MediaIndex): QuestionDto? {
        val text = fieldToText(frontHtml, media).first
        val deletions = Regex("\\{\\{c(\\d+)::(.*?)(?:::.*?)?\\}\\}", RegexOption.DOT_MATCHES_ALL)
            .findAll(frontHtml)
            .map { (it.groupValues[1].toIntOrNull() ?: 0) to it.groupValues[2] }
            .toList()
        if (deletions.isEmpty()) return null
        val target = deletions.getOrNull(ord)
        if (target == null) return null
        val answer = AnkiHtml.toPlainText(target.second)
        val questionId = "anki-${note.guid.ifBlank { note.id }}-$ord"
        return QuestionDto(
            id = questionId,
            text = text.ifBlank { target.first.toString() },
            options = listOf(OptionDto("o0", answer)),
            correctOptionIds = listOf("o0"),
            explanation = "Cloze card from Anki.",
            tags = note.tags + "anki-cloze"
        )
    }

    // ---- decks -> papers and categories ----

    private fun buildPapers(
        decks: List<AnkiDeck>,
        questionsByDeck: Map<Long, List<QuestionDto>>
    ): List<PaperDto> {
        val byId = decks.associateBy { it.id }
        // Cards in a deck Anki does not list (a filtered deck's leftovers) still
        // need a home.
        val orphanDecks = questionsByDeck.keys.filter { it !in byId }
            .map { AnkiDeck(it, "Imported") }
        val allDecks = decks + orphanDecks
        val idByName = allDecks.associateBy { it.name }

        // One paper per top-level deck. The roots come from the deck names
        // rather than from the deck rows, so a package that lists only a subdeck
        // without its parent still produces a paper.
        val roots = LinkedHashSet<String>()
        allDecks.forEach { deck ->
            deck.name.split("::").firstOrNull { it.isNotBlank() }?.let { roots += it }
        }
        val allNames = allDecks.map { it.name }

        return roots.map { root ->
            val deck = idByName[root]
            val childNames = allNames.filter { it != root && it.startsWith("$root::") }
            PaperDto(
                id = "anki-${deck?.id ?: root}",
                title = root,
                // Cards in the paper's own deck are uncategorised questions.
                // They used to become a category named after the deck, which
                // gave every paper a category sharing its own name.
                questions = questionsByDeck[deck?.id].orEmpty(),
                categories = buildCategories(
                    prefix = root,
                    childDeckNames = childNames,
                    idByName = idByName,
                    questionsByDeck = questionsByDeck
                )
            )
        }
            // A paper needs at least one question to be worth importing.
            .filter { paper ->
                paper.questions.isNotEmpty() || paper.categories.any { it.questions.isNotEmpty() }
            }
    }

    /**
     * Turns subdeck names into this app's flat category list, with each
     * category pointing at its parent.
     *
     * Two things this has to get right, both of which cost a hierarchy:
     *
     *  - Children carry their parent's id. Without it the reader returns a flat
     *    list and a nested category arrives as a sibling of its parent.
     *  - A level with no deck of its own still becomes a category. Anki only
     *    creates a deck for a category that holds cards, so a category whose
     *    questions all sit one level down has no deck row at all. Matching
     *    children to their parent by depth would drop that whole subtree.
     *
     * Parents are emitted before their children so the list reads in the order
     * the categories are meant to appear.
     */
    private fun buildCategories(
        prefix: String,
        childDeckNames: List<String>,
        idByName: Map<String, AnkiDeck>,
        questionsByDeck: Map<Long, List<QuestionDto>>,
        parentId: String? = null
    ): List<CategoryDto> {
        // The next segment below the prefix, in the order the decks appear.
        val segments = LinkedHashSet<String>()
        childDeckNames.forEach { name ->
            segments += name.removePrefix("$prefix::").substringBefore("::")
        }
        val out = mutableListOf<CategoryDto>()
        segments.forEach { segment ->
            val fullName = "$prefix::$segment"
            val deck = idByName[fullName]
            val id = categoryId(fullName, deck)
            val questions = questionsByDeck[deck?.id].orEmpty()
            val children = buildCategories(
                prefix = fullName,
                childDeckNames = childDeckNames.filter { it.startsWith("$fullName::") },
                idByName = idByName,
                questionsByDeck = questionsByDeck,
                parentId = id
            )
            // An empty deck Anki happened to include, with nothing under it, is
            // not worth a category.
            if (questions.isNotEmpty() || children.isNotEmpty()) {
                out += CategoryDto(
                    id = id,
                    title = segment,
                    parentId = parentId,
                    questions = questions
                )
                out += children
            }
        }
        return out
    }

    /**
     * A category id for a deck. Anki's own deck id is used when the package has
     * a row for it, which is unique and stable. A level the package does not
     * list has no id to borrow, so the name is folded into one: category ids
     * travel in a comma-separated nav argument, so a raw deck name could break
     * it, and a bare hash could collide and merge two categories.
     */
    private fun categoryId(deckName: String, deck: AnkiDeck?): String {
        deck?.let { return "anki-deck-${it.id}" }
        val slug = deckName.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(40)
        return "anki-deck-$slug-${deckName.hashCode().toString(36)}"
    }

    // ---- helpers ----

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) out[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        if (out.isEmpty()) throw AnkiPackageException("This file is not a readable Anki package")
        return out
    }

    /**
     * Anki's tags field is space-delimited with a leading and trailing space.
     */
    private fun parseTags(tags: String): List<String> =
        tags.split(' ', '\t', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("mcqapp-") && it != "mcqapp" }
            .distinct()

    private data class AnkiField(val name: String)

    private data class AnkiNotetype(
        val name: String,
        val fields: List<AnkiField>,
        val isCloze: Boolean
    )

    private data class AnkiDeck(val id: Long, val name: String)

    private data class AnkiNote(
        val id: Long,
        val guid: String,
        val mid: Long,
        val tags: List<String>,
        val fields: List<String>
    )

    private data class AnkiCard(
        val id: Long,
        val nid: Long,
        val did: Long,
        val ord: Int,
        val type: Int,
        val queue: Int,
        val due: Int,
        val ivl: Int,
        val factor: Int,
        val reps: Int,
        val lapses: Int
    )

    /** The `col` row: schema version, the schema-11 JSON blobs, and the epoch. */
    private data class ColMeta(
        val version: Int,
        val models: String,
        val decks: String,
        /**
         * Collection creation, in seconds. A review card's `due` counts days
         * from this instant, so without it a reviewed deck's due dates are
         * meaningless.
         */
        val crtSeconds: Long
    )
}

private val IMG_TAG =
    Regex("<img\\s+[^>]*src\\s*=\\s*\"([^\"]+)\"[^>]*>", RegexOption.IGNORE_CASE)

/**
 * Resolves `<img src>` references to inline data URIs.
 *
 * A legacy package references media by the numeric zip entry name; a modern one
 * references it by the stored filename. Both are looked up in the `media`
 * manifest, and an entry that is missing from the manifest but present in the
 * zip is used directly.
 */
private class MediaIndex(
    manifestJson: String?,
    private val entries: Map<String, ByteArray>
) {
    /** zip entry name -> original filename. */
    private val byZipName: Map<String, String> = try {
        manifestJson?.let { jsonObjectOf(it) }.orEmpty()
    } catch (e: Exception) {
        emptyMap()
    }

    private val byFilename: Map<String, ByteArray> = byZipName.mapNotNull { (zipName, filename) ->
        entries[zipName]?.let { filename to it }
    }.toMap()

    private val cache = HashMap<String, String?>()

    fun rewrite(html: String?): String? {
        if (html == null || !html.contains("<img", ignoreCase = true)) return html
        return IMG_TAG.replace(html) { match ->
            val src = match.groupValues[1]
            val replacement = dataUri(src)
            if (replacement == null) match.value else "<img src=\"$replacement\">"
        }
    }

    private fun dataUri(src: String): String? = cache.getOrPut(src) {
        val bytes = byFilename[src] ?: entries[src] ?: entries[byZipName.entries
            .firstOrNull { it.value == src }?.key]
        bytes?.let { "data:${mimeOf(it)};base64,${Base64.encode(it)}" }
    }

    private fun mimeOf(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/gif"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun jsonObjectOf(text: String): Map<String, String> =
        kotlinx.serialization.json.Json.parseToJsonElement(text)
            .let { it as kotlinx.serialization.json.JsonObject }
            .mapValues { (_, v) -> v.jsonPrimitive.content }
}

private object Base64 {
    fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
}
