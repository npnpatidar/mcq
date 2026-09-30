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
    val deckCount: Int
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
        val col = db.rawQuery("select ver, models, decks from col", null).use { c ->
            if (!c.moveToFirst()) throw AnkiPackageException("The Anki collection is empty")
            Triple(c.getInt(0), c.getString(1), c.getString(2))
        }
        val ver = col.first
        Logger.i("IMPORT", "Reading Anki collection, schema version $ver")

        val notetypes = if (ver >= SCHEMA_14) readNotetypes14(db) else readNotetypes11(col.second)
        val decks = if (ver >= SCHEMA_14) readDecks14(db) else readDecks11(col.third)
        if (decks.isEmpty()) throw AnkiPackageException("The Anki collection has no decks")

        val notes = readNotes(db, media)
        val cards = readCards(db)
        // One note can hold several cards (a cloze note produces one per
        // deletion); without a card we still import the note once.
        val cardsByNote = cards.groupBy { it.nid }
        val noteById = notes.associateBy { it.id }

        var recallCount = 0
        val questionsByDeck = LinkedHashMap<Long, MutableList<QuestionDto>>()
        notes.forEach { note ->
            val template = notetypes[note.mid]
            val noteCards = cardsByNote[note.id].orEmpty()
            val instances = if (noteCards.isEmpty()) listOf(null) else noteCards.map { it.ord }
            instances.forEach { ord ->
                val q = toQuestion(note, template, ord, media) ?: return@forEach
                if (template?.isMcqLike != true) recallCount++
                val deckId = noteCards.firstOrNull { it.ord == ord }?.did ?: DEFAULT_DECK_ID
                questionsByDeck.getOrPut(deckId) { mutableListOf() }.add(q)
            }
        }
        if (noteById.isEmpty()) throw AnkiPackageException("The Anki collection has no notes")

        val papers = buildPapers(decks, questionsByDeck)
        val noteCount = questionsByDeck.values.sumOf { it.size }
        Logger.i(
            "IMPORT",
            "Anki package: ${decks.size} decks, $noteCount questions, $recallCount without options"
        )
        return AnkiReadResult(
            file = McqFileDto(version = 1, papers = papers),
            noteCount = noteCount,
            recallCount = recallCount,
            deckCount = decks.size
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
        db.rawQuery("select id, nid, did, ord from cards order by id", null).use { c ->
            while (c.moveToNext()) {
                out += AnkiCard(c.getLong(1), c.getLong(2), c.getInt(3))
            }
        }
        return out
    }

    // ---- note -> question ----

    private fun toQuestion(
        note: AnkiNote,
        notetype: AnkiNotetype?,
        ord: Int?,
        media: MediaIndex
    ): QuestionDto? {
        val payload = payloadFromField(note.fields.getOrNull(2))
        val frontHtml = note.fields.firstOrNull().orEmpty()

        if (notetype != null && notetype.isCloze) {
            return clozeQuestion(note, frontHtml, ord ?: 0, media)
        }

        val questionId = "anki-${note.guid.ifBlank { note.id }}"
        val front = fieldToText(frontHtml, media)
        if (front.first.isBlank()) return null

        val backHtml = note.fields.getOrNull(1).orEmpty()
        if (payload != null) {
            // Our own export: the front also holds the options, so only the text
            // above the first lettered option is the question.
            return QuestionDto(
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
            )
        }

        val back = AnkiHtml.parseBackField(media.rewrite(backHtml))
        if (back.hasMarkers) {
            val options = back.options.mapIndexed { i, opt ->
                OptionDto("o$i", AnkiHtml.toPlainText(opt.text))
            }
            return QuestionDto(
                id = questionId,
                text = front.first,
                image = front.second,
                options = options,
                correctOptionIds = options.filterIndexed { i, _ -> back.options[i].correct }.map { it.id },
                explanation = AnkiHtml.toPlainText(back.explanation),
                tags = note.tags
            )
        }

        // A foreign note with no options: keep the content as a single-option
        // recall question rather than dropping it.
        val answer = fieldToText(backHtml, media)
        return QuestionDto(
            id = questionId,
            text = front.first,
            image = front.second,
            options = listOf(OptionDto("o0", answer.first.ifBlank { front.first }, answer.second)),
            correctOptionIds = listOf("o0"),
            tags = note.tags
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

        return allDecks.mapNotNull { deck ->
            val segments = deck.name.split("::").filter { it.isNotBlank() }
            if (segments.isEmpty()) return@mapNotNull null
            val root = segments.first()
            // Every deck contributes to its own top-level paper; nested decks are
            // categories inside it.
            val ownQuestions = questionsByDeck[deck.id].orEmpty()
            val childNames = allDecks.map { it.name }
                .filter { it != deck.name && it.startsWith(deck.name + "::") }
            val categories = buildCategories(
                prefixSegments = segments,
                ownQuestions = ownQuestions,
                childDeckNames = childNames,
                idByName = idByName,
                questionsByDeck = questionsByDeck
            )
            PaperDto(
                id = "anki-${deck.id}",
                title = root,
                categories = categories
            )
        }.distinctBy { it.id }
            // A paper needs at least one question to be worth importing.
            .filter { paper -> paper.categories.any { it.questions.isNotEmpty() } }
    }

    private fun buildCategories(
        prefixSegments: List<String>,
        ownQuestions: List<QuestionDto>,
        childDeckNames: List<String>,
        idByName: Map<String, AnkiDeck>,
        questionsByDeck: Map<Long, List<QuestionDto>>
    ): List<CategoryDto> {
        val out = mutableListOf<CategoryDto>()
        val selfName = prefixSegments.joinToString("::")
        val deck = idByName[selfName]
        if (ownQuestions.isNotEmpty()) {
            out += CategoryDto(
                id = "anki-deck-${deck?.id ?: selfName.hashCode()}",
                title = prefixSegments.last(),
                questions = ownQuestions
            )
        }
        childDeckNames.forEach { childName ->
            val childSegments = childName.split("::").filter { it.isNotBlank() }
            if (childSegments.size != prefixSegments.size + 1) return@forEach
            val childDeck = idByName[childName]
            val grandchildren = childDeckNames.filter { it.startsWith(childName + "::") }
            out += buildCategories(
                prefixSegments = childSegments,
                ownQuestions = questionsByDeck[childDeck?.id].orEmpty(),
                childDeckNames = grandchildren,
                idByName = idByName,
                questionsByDeck = questionsByDeck
            )
        }
        return out
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
    ) {
        /** True when the note carries our structured payload, or marked options. */
        val isMcqLike: Boolean get() = fields.any { it.name == AnkiSchema11.PAYLOAD_FIELD }
    }

    private data class AnkiDeck(val id: Long, val name: String)

    private data class AnkiNote(
        val id: Long,
        val guid: String,
        val mid: Long,
        val tags: List<String>,
        val fields: List<String>
    )

    private data class AnkiCard(val nid: Long, val did: Long, val ord: Int)
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
