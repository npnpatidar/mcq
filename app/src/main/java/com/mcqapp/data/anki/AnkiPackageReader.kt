package com.mcqapp.data.anki

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.mcqapp.data.SafeZip
import com.mcqapp.data.io.CardScheduleDto
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.textContent
import com.mcqapp.util.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.File
import java.io.InputStream

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
 * `collection.anki2` + a `media` manifest, notetypes and decks as JSON in
 * `col`) and the modern one (schema 15+, `collection.anki21`/
 * `collection.anki21b` with per-table notetype and deck storage). A package
 * with no `meta` entry is the legacy form, which is what this app exports.
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

    private val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())
    private val json = Json { ignoreUnknownKeys = true }

    private const val DEFAULT_DECK_ID = 1L
    private const val SEPARATOR = AnkiPackageWriter.FIELD_SEPARATOR

    fun read(bytes: ByteArray, nowMillis: Long = System.currentTimeMillis()): AnkiReadResult {
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

        val media = MediaIndex(entries["media"]?.let { String(it, Charsets.UTF_8) }, { name -> entries[name] }, cache = true)
        val file = File.createTempFile("anki-import", ".anki2")
        return try {
            file.writeBytes(collectionBytes)
            val db = SQLiteDatabase.openOrCreateDatabase(file, null)
            try {
                readCollection(db, media, nowMillis)
            } finally {
                db.close()
            }
        } catch (e: AnkiPackageException) {
            throw e
        } catch (e: OutOfMemoryError) {
            // An Error, so the catch below never sees it: without this the
            // process simply died mid-import.
            throw AnkiPackageException("This package needs more memory than this device has.", e)
        } catch (e: Exception) {
            throw AnkiPackageException("Could not read the Anki collection: ${e.message}", e)
        } finally {
            if (!file.delete()) {
                Logger.w("ANKI", "Could not delete temp collection ${file.absolutePath}")
            }
        }
    }

    /** The single `col` row: schema version, the schema-11 JSON blobs, the epoch. */
    private fun readColMeta(db: SQLiteDatabase): ColMeta =
        db.rawQuery("select ver, models, decks, crt from col", null).use { c ->
            if (!c.moveToFirst()) throw AnkiPackageException("The Anki collection is empty")
            ColMeta(c.getInt(0), c.getString(1), c.getString(2), c.getLong(3))
        }

    private fun readCollection(
        db: SQLiteDatabase,
        media: MediaIndex,
        nowMillis: Long
    ): AnkiReadResult {
        val col = readColMeta(db)
        val ver = col.version
        Logger.i("IMPORT", "Reading Anki collection, schema version $ver")

        // Which layout this collection uses is a property of its tables, not of
        // `col.ver`: the notetype/deck tables arrive with the schema-15 upgrade.
        val modern = hasModernTables(db)
        val notetypes = if (modern) readNotetypesFromTables(db) else readNotetypes11(col.models)
        val decks = if (modern) readDecksFromTables(db) else readDecks11(col.decks)
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
                        lastReviewedAtMillis = lastReviews[card.id] ?: 0L,
                        nowMillis = nowMillis
                    )
                    if (state == null) unscheduled++ else scheduling[parsed.question.id] = state
                }
            }
        }
        if (noteById.isEmpty()) throw AnkiPackageException("The Anki collection has no notes")

        // Rebuild the passages our own export carried in the payload: every
        // member note repeats the whole block, so first-seen wins and the rest
        // are identical by construction.
        val passages = LinkedHashMap<String, PassageDto>()
        notes.forEach { note ->
            val payload = payloadFromField(note.fields.getOrNull(2)) ?: return@forEach
            val pid = payload.passageId ?: return@forEach
            passages.getOrPut(pid) {
                PassageDto(
                    id = pid,
                    title = payload.passageTitle.orEmpty().ifBlank { "Passage" },
                    elements = payload.passageElements,
                    image = payload.passageImage
                )
            }
        }

        val papers = buildPapers(decks, questionsByDeck)
        val noteCount = questionsByDeck.values.sumOf { it.size }
        Logger.i(
            "IMPORT",
            "Anki package: ${decks.size} decks, $noteCount questions, $recallCount without options, " +
                "${scheduling.size} scheduled, $unscheduled new or not due"
        )
        return AnkiReadResult(
            file = McqFileDto(version = 1, papers = papers, passages = passages.values.toList()),
            noteCount = noteCount,
            recallCount = recallCount,
            deckCount = decks.size,
            scheduling = scheduling
        )
    }

    // ---- streaming (.apkg straight to per-batch writes) ----

    /**
     * Receives a package while it is read, in the same order the whole-file
     * [read] builds: a paper, then its categories, then each category's
     * questions in batches. The callbacks run inline on the importing
     * coroutine, so a sink must not retain the batches it is handed.
     */
    interface StreamSink {
        suspend fun onPaper(header: LegacyParser.PaperHeader)
        suspend fun onCategory(category: CategoryDto)
        suspend fun onQuestions(
            categoryId: String,
            questions: List<QuestionDto>,
            schedules: Map<String, CardScheduleDto>
        )
    }

    /** What a streamed read produced, for the import report and its log line. */
    data class AnkiStreamTail(
        val passages: List<PassageDto>,
        val deckCount: Int,
        val noteCount: Int,
        val recallCount: Int
    )

    /**
     * Reads a package by streaming it into [sink], without ever holding the
     * archive or the bank: the zip's entries are copied to temp files (under
     * [SafeZip]'s caps), each picture is spooled rather than kept as a data
     * URI, and the collection is walked one deck at a time so only a page of
     * notes and one batch of questions is ever live. It reproduces [read]'s
     * papers, categories, questions and schedules; the equivalence test pins
     * the two against each other over the fixtures.
     *
     * [read] stays as the whole-file reader — the tests' oracle now that no
     * production path calls it.
     */
    suspend fun stream(
        input: InputStream,
        sink: StreamSink,
        batchSize: Int = LegacyParser.IMPORT_BATCH_SIZE,
        nowMillis: Long = System.currentTimeMillis()
    ): AnkiStreamTail {
        require(batchSize > 0) { "batchSize must be positive" }
        val work = AnkiImportWork()
        try {
            work.extract(input)
            val collectionFile = work.collectionFile
                ?: throw AnkiPackageException(
                    "No Anki collection found in this package (looked for ${COLLECTION_NAMES.joinToString()})"
                )
            if (startsWithZstd(collectionFile)) {
                throw AnkiPackageException(
                    "This is a zstd-compressed .colpkg, which needs Anki 2.1.50+ to unpack. " +
                        "Export the deck as .apkg from Anki and import that instead."
                )
            }
            val db = try {
                SQLiteDatabase.openOrCreateDatabase(collectionFile, null)
            } catch (e: Exception) {
                throw AnkiPackageException("Could not read the Anki collection: ${e.message}", e)
            }
            try {
                return streamCollection(db, work.mediaIndex(), sink, batchSize, nowMillis)
            } catch (e: AnkiPackageException) {
                throw e
            } catch (e: OutOfMemoryError) {
                throw AnkiPackageException("This package needs more memory than this device has.", e)
            } catch (e: Exception) {
                throw AnkiPackageException("Could not read the Anki collection: ${e.message}", e)
            } finally {
                db.close()
            }
        } finally {
            work.close()
        }
    }

    private suspend fun streamCollection(
        db: SQLiteDatabase,
        media: MediaIndex,
        sink: StreamSink,
        batchSize: Int,
        nowMillis: Long
    ): AnkiStreamTail {
        val col = readColMeta(db)
        Logger.i("IMPORT", "Streaming Anki collection, schema version ${col.version}")
        val modern = hasModernTables(db)
        val notetypes = if (modern) readNotetypesFromTables(db) else readNotetypes11(col.models)
        val decks = if (modern) readDecksFromTables(db) else readDecks11(col.decks)
        if (decks.isEmpty()) throw AnkiPackageException("The Anki collection has no decks")
        if (countRows(db, "notes") == 0L) throw AnkiPackageException("The Anki collection has no notes")

        // Deck ids in the order their first question appears — the whole-file
        // reader's `questionsByDeck` key order, and therefore which orphan
        // decks fold into one paper (only the last survives) as well as which
        // papers and categories exist at all.
        val scan = scanDecks(db, notetypes)
        val referenced = scan.order
        val referencedSet = scan.counts.keys
        val byId = decks.associateBy { it.id }
        val allDecks = decks + referenced.filter { it !in byId }.map { AnkiDeck(it, "Imported") }
        val idByName = allDecks.associateBy { it.name }
        val allNames = allDecks.map { it.name }
        val roots = LinkedHashSet<String>()
        allDecks.forEach { deck ->
            deck.name.split("::").firstOrNull { it.isNotBlank() }?.let { roots += it }
        }
        val revlog = hasRevlog(db)

        val passages = LinkedHashMap<String, PassageDto>()
        var noteCount = 0
        var recallCount = 0
        for (root in roots) {
            val rootDeck = idByName[root]
            val paperDeckIds = allDecks
                .filter { it.name == root || it.name.startsWith("$root::") }
                .map { it.id }
            // A paper with no question in any of its decks is dropped, exactly
            // as buildPapers filters it.
            if (paperDeckIds.none { it in referencedSet }) continue
            val childNames = allNames.filter { it != root && it.startsWith("$root::") }
            val nodes = buildCategories(
                prefix = root,
                childDeckNames = childNames,
                idByName = idByName,
                hasQuestions = { deck -> deck != null && deck.id in referencedSet }
            )
            val paperId = "anki-${rootDeck?.id ?: root}"
            sink.onPaper(LegacyParser.PaperHeader(paperId, root, "", 0, 0.0))
            if (nodes.isEmpty()) {
                // The paper's own deck is uncategorised, so the whole-file
                // importer synthesises "Uncategorized" for it.
                val categoryId = "$paperId-uncat"
                sink.onCategory(CategoryDto(id = categoryId, title = "Uncategorized"))
                val counts = streamDeck(db, notetypes, media, col, revlog, passages, rootDeck?.id, categoryId, sink, batchSize, nowMillis)
                noteCount += counts.total
                recallCount += counts.recall
            } else {
                for (node in nodes) {
                    // A level with no deck row of its own holds no questions.
                    sink.onCategory(node.dto)
                    val counts = streamDeck(db, notetypes, media, col, revlog, passages, node.deck?.id, node.dto.id, sink, batchSize, nowMillis)
                    noteCount += counts.total
                    recallCount += counts.recall
                }
            }
        }
        Logger.i(
            "IMPORT",
            "Streamed Anki package: ${decks.size} decks, $noteCount questions, $recallCount without options"
        )
        return AnkiStreamTail(passages.values.toList(), decks.size, noteCount, recallCount)
    }

    private class DeckCounts(val total: Int, val recall: Int)

    /** Which decks hold a question ([order] is first-question order), and how many. */
    private class DeckScan(val order: List<Long>, val counts: Map<Long, Int>)

    /**
     * Streams one deck's questions. A note's cards decide its instances (one
     * per card, as Anki's cloze deletions are); only cards in [deckId] are
     * considered, which is the restriction the whole-file reader applies when
     * it assigns each question to its card's deck. [DEFAULT_DECK_ID] also takes
     * notes with no card at all, which the whole-file reader files there.
     */
    private suspend fun streamDeck(
        db: SQLiteDatabase,
        notetypes: Map<Long, AnkiNotetype>,
        media: MediaIndex,
        col: ColMeta,
        revlog: Boolean,
        passages: LinkedHashMap<String, PassageDto>,
        deckId: Long?,
        categoryId: String,
        sink: StreamSink,
        batchSize: Int,
        nowMillis: Long
    ): DeckCounts {
        if (deckId == null) return DeckCounts(0, 0)
        var total = 0
        var recall = 0
        val batch = ArrayList<QuestionDto>()
        val schedules = HashMap<String, CardScheduleDto>()
        var after = 0L
        while (true) {
            val page = noteIdsForDeck(db, deckId, after, NOTE_PAGE)
            if (page.isEmpty()) break
            after = page.last()
            val notes = readNotesByIds(db, page)
            val cards = readCardsForNotes(db, page)
            val cardsByNote = cards.groupBy { it.nid }
            val lastReviews = if (revlog) readLastReviewTimesFor(db, cards.map { it.id }) else emptyMap()
            for (nid in page) {
                val note = notes[nid] ?: continue
                val deckCards = cardsByNote[nid].orEmpty().filter { it.did == deckId }
                val instances: List<Int?> =
                    if (deckCards.isEmpty()) listOf(null) else deckCards.map { it.ord }
                for (ord in instances) {
                    val parsed = toQuestion(note, notetypes[note.mid], ord, media) ?: continue
                    if (parsed.isRecall) recall++
                    total++
                    parsed.question.passageId?.let { passageId ->
                        recordPassage(note, passageId, passages)
                    }
                    val card = ord?.let { o -> deckCards.firstOrNull { it.ord == o } }
                    if (card != null) {
                        AnkiScheduling.fromAnki(
                            type = card.type,
                            queue = card.queue,
                            due = card.due,
                            ivl = card.ivl,
                            factor = card.factor,
                            reps = card.reps,
                            lapses = card.lapses,
                            crtSeconds = col.crtSeconds,
                            lastReviewedAtMillis = lastReviews[card.id] ?: 0L,
                            nowMillis = nowMillis
                        )?.let { schedules[parsed.question.id] = it }
                    }
                    batch += parsed.question
                    if (batch.size >= batchSize) {
                        sink.onQuestions(categoryId, batch, schedules)
                        batch.clear()
                        schedules.clear()
                    }
                }
            }
        }
        if (batch.isNotEmpty()) sink.onQuestions(categoryId, batch, schedules)
        return DeckCounts(total, recall)
    }

    /**
     * Files a passage the note's payload carries, first note wins. The content
     * is repeated on every member note by construction, so the first one seen
     * is the whole passage.
     */
    private fun recordPassage(note: AnkiNote, passageId: String, passages: LinkedHashMap<String, PassageDto>) {
        if (passageId in passages) return
        val payload = payloadFromField(note.fields.getOrNull(2)) ?: return
        if (payload.passageId != passageId) return
        passages[passageId] = PassageDto(
            id = passageId,
            title = payload.passageTitle.orEmpty().ifBlank { "Passage" },
            elements = payload.passageElements,
            image = payload.passageImage
        )
    }

    // ---- bounded reads used by the streamed walk ----

    private fun countRows(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("select count(*) from $table", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    /**
     * Which decks hold at least one question, in the order their first question
     * appears — the whole-file reader's `questionsByDeck` keys. A deck Anki
     * lists whose notes all fail to become a question (a blank front, say) is
     * absent here exactly as it is absent there, which is what keeps the
     * streamed grouping to the same papers and categories; and the order is
     * what decides which of several unlisted decks folds into the one
     * "Imported" paper.
     *
     * The scan answers only that question, so it resolves no media: a data URI
     * cannot change whether a field is blank, and inlining every picture twice
     * would cost as much as the import itself.
     */
    private fun scanDecks(db: SQLiteDatabase, notetypes: Map<Long, AnkiNotetype>): DeckScan {
        val order = ArrayList<Long>()
        val counts = HashMap<Long, Int>()
        var after = 0L
        while (true) {
            val page = noteIdsAfter(db, after, NOTE_PAGE)
            if (page.isEmpty()) break
            after = page.last()
            val notes = readNotesByIds(db, page)
            val cards = readCardsForNotes(db, page)
            val cardsByNote = cards.groupBy { it.nid }
            for (nid in page) {
                val note = notes[nid] ?: continue
                val noteCards = cardsByNote[nid].orEmpty()
                val instances: List<Int?> =
                    if (noteCards.isEmpty()) listOf(null) else noteCards.map { it.ord }
                for (ord in instances) {
                    if (toQuestion(note, notetypes[note.mid], ord, PROBE_MEDIA) == null) continue
                    val card = ord?.let { o -> noteCards.firstOrNull { it.ord == o } }
                    val deckId = card?.did ?: DEFAULT_DECK_ID
                    if (deckId !in counts) order += deckId
                    counts[deckId] = (counts[deckId] ?: 0) + 1
                }
            }
        }
        return DeckScan(order, counts)
    }

    /** Every note id above [after], in order: one page of the whole-bank scans. */
    private fun noteIdsAfter(db: SQLiteDatabase, after: Long, limit: Int): List<Long> {
        val out = ArrayList<Long>(limit)
        db.rawQuery(
            "select id from notes where id > ? order by id limit ?",
            arrayOf(after.toString(), limit.toString())
        ).use { c -> while (c.moveToNext()) out += c.getLong(0) }
        return out
    }

    /** One page of a deck's note ids, in note order. */
    private fun noteIdsForDeck(db: SQLiteDatabase, deckId: Long, after: Long, limit: Int): List<Long> {
        val out = ArrayList<Long>(limit)
        db.rawQuery(
            "select n.id from notes n where n.id > ? and (" +
                "exists (select 1 from cards c where c.nid = n.id and c.did = $deckId) " +
                "or ($deckId = $DEFAULT_DECK_ID and not exists (select 1 from cards c2 where c2.nid = n.id))) " +
                "order by n.id limit ?",
            arrayOf(after.toString(), limit.toString())
        ).use { c -> while (c.moveToNext()) out += c.getLong(0) }
        return out
    }

    private fun readNotesByIds(db: SQLiteDatabase, ids: List<Long>): Map<Long, AnkiNote> {
        val out = HashMap<Long, AnkiNote>(ids.size * 2)
        for (chunk in ids.chunked(IN_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "select id, guid, mid, tags, flds from notes where id in ($placeholders)",
                chunk.map { it.toString() }.toTypedArray()
            ).use { c -> while (c.moveToNext()) { val note = cursorToNote(c); out[note.id] = note } }
        }
        return out
    }

    private fun readCardsForNotes(db: SQLiteDatabase, nids: List<Long>): List<AnkiCard> {
        val out = mutableListOf<AnkiCard>()
        for (chunk in nids.chunked(IN_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "select id, nid, did, ord, type, queue, due, ivl, factor, reps, lapses from cards " +
                    "where nid in ($placeholders) order by id",
                chunk.map { it.toString() }.toTypedArray()
            ).use { c -> while (c.moveToNext()) out += cursorToCard(c) }
        }
        return out
    }

    /**
     * The newest review's instant per card. In Anki's `revlog`, `id` is the
     * review's epoch-millis timestamp and `time` is how long that review took
     * in millis (Anki's stats divide it by 1000 for "seconds studied"), so the
     * timestamp is `max(id)` and needs no scaling. Reading `max(time)` and
     * multiplying by 1000 turned a ~30 s duration into a 1970 instant.
     */
    private fun readLastReviewTimesFor(db: SQLiteDatabase, cardIds: List<Long>): Map<Long, Long> {
        if (cardIds.isEmpty()) return emptyMap()
        val out = HashMap<Long, Long>()
        for (chunk in cardIds.chunked(IN_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "select cid, max(id) from revlog where cid in ($placeholders) group by cid",
                chunk.map { it.toString() }.toTypedArray()
            ).use { c ->
                while (c.moveToNext()) {
                    if (!c.isNull(1)) out[c.getLong(0)] = c.getLong(1)
                }
            }
        }
        return out
    }

    private fun hasRevlog(db: SQLiteDatabase): Boolean = try {
        db.rawQuery("select 1 from revlog limit 1", null).use { true }
    } catch (e: Exception) {
        false
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

    /**
     * Whether the collection carries Anki's normalised notetype and deck
     * tables. `schema15_upgrade.sql` creates them and then sets `ver = 15`
     * (rslib `upgrades/mod.rs` runs it under `if ver < 15`), so a schema-14
     * collection still holds its notetypes and decks as JSON in `col` and has
     * neither table. Presence decides which reader runs — `col.ver` does not:
     * a version comparison puts the schema-14 boundary on the wrong side.
     */
    private fun hasModernTables(db: SQLiteDatabase): Boolean = try {
        db.rawQuery("select 1 from notetypes limit 1", null).use { true } &&
            db.rawQuery("select 1 from decks limit 1", null).use { true }
    } catch (e: Exception) {
        false
    }

    // ---- schema 15+ (notetypes/fields/templates and decks tables) ----

    private fun readNotetypesFromTables(db: SQLiteDatabase): Map<Long, AnkiNotetype> {
        val names = LinkedHashMap<Long, MutableList<String>>()
        db.rawQuery("select ntid, name from fields order by ntid, ord", null).use { c ->
            while (c.moveToNext()) names.getOrPut(c.getLong(0)) { mutableListOf() } += c.getString(1)
        }
        val kind = HashMap<Long, Int>()
        db.rawQuery("select id, config from notetypes", null).use { c ->
            while (c.moveToNext()) {
                kind[c.getLong(0)] = notetypeKind(c.getBlob(1))
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

    private fun readDecksFromTables(db: SQLiteDatabase): List<AnkiDeck> {
        val out = mutableListOf<AnkiDeck>()
        db.rawQuery("select id, name, kind from decks order by id", null).use { c ->
            while (c.moveToNext()) {
                // kind 2 is a filtered deck; it is not a real deck.
                if (deckIsFiltered(c.getBlob(2))) continue
                out += AnkiDeck(c.getLong(0), c.getString(1))
            }
        }
        return out
    }

    /**
     * `NotetypeConfig.kind` (1 = cloze), read from the notetype's protobuf
     * `config` blob. These tables and this column exist only from schema 15,
     * and they have held prost bytes since Anki created them: the 11 -> 15
     * upgrade writes `nt.config.encode(..)` (rslib
     * `storage/notetype/mod.rs`), and Anki's own read path is
     * `NotetypeConfig::decode(..as_blob())`. There is no JSON phase to fall
     * back to — the JSON parse this replaced is why every cloze notetype
     * imported as a recall notetype.
     */
    private fun notetypeKind(blob: ByteArray?): Int {
        val bytes = blob ?: return 0
        // field 1, varint.
        return protoFields(bytes).firstOrNull { it.number == 1 && it.wireType == 0 }?.varint?.toInt() ?: 0
    }

    /**
     * Whether `decks.kind` holds a filtered (dynamic) deck: `DeckKindContainer`,
     * whose `normal` member is field 1 and `filtered` field 2, stored as
     * protobuf from the moment the `decks` table exists (schema 15, both
     * `DeckCommon::decode` and `DeckKindContainer::decode` read `as_blob()`).
     * A filtered deck imports as a real paper when this degrades to `false`.
     */
    private fun deckIsFiltered(blob: ByteArray?): Boolean {
        val bytes = blob ?: return false
        return protoFields(bytes).any { it.number == 2 && it.wireType == 2 }
    }

    private class ProtoField(val number: Int, val wireType: Int, val varint: Long)

    /**
     * Walks the top-level fields of a protobuf blob, keeping varints and
     * skipping the rest. Malformed trailing bytes stop the walk, so a caller
     * sees whatever was decoded so far.
     */
    private fun protoFields(bytes: ByteArray): List<ProtoField> {
        val out = ArrayList<ProtoField>()
        var i = 0
        while (i < bytes.size) {
            val (key, afterKey) = readVarint(bytes, i) ?: break
            i = afterKey
            when ((key and 7L).toInt()) {
                0 -> {
                    val (value, next) = readVarint(bytes, i) ?: break
                    out += ProtoField((key ushr 3).toInt(), 0, value)
                    i = next
                }
                1 -> i += 8
                2 -> {
                    val (length, next) = readVarint(bytes, i) ?: break
                    if (length < 0 || length > (bytes.size - next).toLong()) break
                    out += ProtoField((key ushr 3).toInt(), 2, 0L)
                    i = next + length.toInt()
                }
                5 -> i += 4
                else -> break
            }
        }
        return out
    }

    /** One base-128 varint starting at [start], with the index just past it. */
    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = start
        while (i < bytes.size && shift < 64) {
            val b = bytes[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return result to i
            shift += 7
        }
        return null
    }

    // ---- notes and cards ----

    private fun readNotes(db: SQLiteDatabase, media: MediaIndex): List<AnkiNote> {
        val out = mutableListOf<AnkiNote>()
        db.rawQuery("select id, guid, mid, tags, flds from notes order by id", null).use { c ->
            while (c.moveToNext()) out += cursorToNote(c)
        }
        return out
    }

    private fun cursorToNote(c: Cursor): AnkiNote {
        val rawTags = c.getString(3).orEmpty()
        return AnkiNote(
            id = c.getLong(0),
            guid = c.getString(1).orEmpty(),
            mid = c.getLong(2),
            tags = parseTags(rawTags),
            rawTags = rawTags.split(' ', '\t', '\n').map { it.trim() }.filter { it.isNotEmpty() },
            fields = c.getString(4).orEmpty().split(SEPARATOR)
        )
    }

    private fun readCards(db: SQLiteDatabase): List<AnkiCard> {
        val out = mutableListOf<AnkiCard>()
        db.rawQuery(
            "select id, nid, did, ord, type, queue, due, ivl, factor, reps, lapses " +
                "from cards order by id",
            null
        ).use { c ->
            while (c.moveToNext()) out += cursorToCard(c)
        }
        return out
    }

    private fun cursorToCard(c: Cursor): AnkiCard = AnkiCard(
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

    /**
     * Each card's most recent review time, in millis, from the review log. The
     * log is the only record of when a card was last seen; without it the
     * schedule falls back to the interval, which is close enough for a card
     * that has not been overdue. A package may legitimately not carry one.
     *
     * `id` is the review's epoch-millis timestamp and `time` is its duration in
     * millis; see [readLastReviewTimesFor], which this whole-file oracle
     * mirrors.
     */
    private fun readLastReviewTimes(db: SQLiteDatabase): Map<Long, Long> = try {
        val out = LinkedHashMap<Long, Long>()
        db.rawQuery("select cid, max(id) from revlog group by cid", null).use { c ->
            while (c.moveToNext()) {
                if (!c.isNull(1)) out[c.getLong(0)] = c.getLong(1)
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

    /** Notes whose entire stem is empty (neither text nor image), for the log. */
    private var droppedEmptyStems = 0

    /**
     * The stem-image fallback for a payload that omits `image` (older or
     * hand-edited one). buildFront embeds the passage — with its picture —
     * ABOVE the question in field 0, and splitFront's "question half" keeps
     * the passage block, so a first-<img>-anywhere search attributed the
     * PASSAGE's picture to the question: duplicated under
     * `questions.image` at import, the stem rendered it twice, and any
     * branching on `question.image != null` misdirected.
     *
     * The fix is positional ONLY: scan below the passage block when the front
     * carries one (the writer separates the block from the stem with `<hr>`;
     * a stem holding its own `<hr>` before a later image is out of this
     * fallback's grammar — the writer's separator is). An elements-vs-image
     * gate was tried here and deleted: the first pass is DOCUMENTED to hydrate
     * `questions.image` from a picture authored as a content element (the
     * SamplePaperApkgRoundTripTest invariant "the first pass only adds derived
     * fields", which CI 39434947811 proved red when the gate disabled it), and
     * an element-authored image sits in the stem, below the very `<hr>` the
     * positional scan searches.
     */
    private fun stemImageFromFront(questionHtml: String): String? {
        val belowPassage = if (questionHtml.contains("<hr")) questionHtml.substringAfterLast("<hr>") else questionHtml
        return IMG_TAG.find(belowPassage)?.groupValues?.get(1)
    }

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
        if (front.first.isBlank() && front.second == null) {
            droppedEmptyStems++
            Logger.w(
                "IMPORT",
                "Dropped a note with a completely empty stem (no text, no image): " +
                    "${note.guid.ifBlank { note.id }} — dropped #$droppedEmptyStems"
            )
            return null
        }
        // A blank stem that still has an <img> is a picture question: the
        // paths below keep it — the image travels as question content (or,
        // for our own cards, the payload's elements) — instead of vanishing
        // the note from the deck.

        val backHtml = note.fields.getOrNull(1).orEmpty()
        if (payload != null) {
            // Our own export: the front also holds the options, so only what is
            // above the first lettered option belongs to the question. That
            // includes its image: the first <img> on the whole field is an
            // option's image whenever the question itself has none.
            val questionHtml = AnkiHtml.splitFront(media.rewrite(frontHtml).orEmpty()).first
            return ParsedQuestion(QuestionDto(
                id = questionId,
                text = AnkiHtml.toRichText(IMG_TAG.replace(questionHtml, "")),
                elements = payload.elements.ifEmpty {
                    listOf(com.mcqapp.domain.ContentElement.TextElement(
                        AnkiHtml.toRichText(IMG_TAG.replace(questionHtml, ""))
                    ))
                },
                image = media.rewrite(payload.image)
                    ?: stemImageFromFront(questionHtml),
                options = payload.options.map {
                    OptionDto(
                        id = it.id,
                        text = it.text,
                        elements = it.elements.ifEmpty {
                            listOf(com.mcqapp.domain.ContentElement.TextElement(it.text))
                        },
                        image = media.rewrite(it.image)
                    )
                },
                correctOptionIds = payload.correct,
                explanation = payload.explanation,
                explanationElements = payload.explanationElements.ifEmpty {
                    listOf(com.mcqapp.domain.ContentElement.TextElement(payload.explanation))
                },
                explanationImage = media.rewrite(payload.explanationImage),
                difficulty = payload.difficulty,
                marks = payload.marks,
                tags = (payload.tags + note.tags).distinct(),
                passageId = payload.passageId
            ),
            // Nothing to choose between, so this counts as a recall question.
            isRecall = payload.options.isEmpty())
        }

        val back = AnkiHtml.parseBackField(media.rewrite(backHtml))
        // Foreign packages carry no mcqapp payload, so difficulty survives
        // only as a tag; recover it before the default (medium) overwrites it.
        // rawTags: parseTags strips mcqapp-* before this point.
        val difficulty = difficultyFromTags(note.rawTags)

        // A foreign deck with no mcqapp payload: options are `<p>(a) ...</p>`
        // blocks on the front, the answer is `Answer: (a)` on the back, and
        // the explanation follows `Exp:`. Rich markup (math, tables, images)
        // becomes content elements.
        val rewrittenFront = media.rewrite(frontHtml).orEmpty()
        val (questionHtml, foreignOptions) = splitForeignFront(rewrittenFront)
        val (correctLetter, explanationHtml) = parseForeignBack(media.rewrite(backHtml).orEmpty())
        if (foreignOptions.isNotEmpty() && correctLetter != null) {
            val questionElements = htmlToElements(questionHtml, media)
            val options = foreignOptions.map { (letter, html) ->
                val elements = htmlToElements(html, media)
                OptionDto(
                    id = letter,
                    text = elements.textContent,
                    elements = elements
                )
            }
            val explanationElements = htmlToElements(explanationHtml, media)
            val correctId = options.firstOrNull { it.id.equals(correctLetter, ignoreCase = true) }?.id
            return ParsedQuestion(
                QuestionDto(
                    id = questionId,
                    text = questionElements.textContent,
                    elements = questionElements,
                    options = options,
                    correctOptionIds = listOfNotNull(correctId),
                    explanation = explanationElements.textContent,
                    explanationElements = explanationElements,
                    difficulty = difficulty,
                    tags = note.tags
                ),
                isRecall = false
            )
        }

        if (back.hasMarkers) {
            val options = back.options.mapIndexed { i, opt ->
                OptionDto("o$i", AnkiHtml.toRichText(opt.text))
            }
            return ParsedQuestion(
                QuestionDto(
                    id = questionId,
                    text = front.first,
                    image = front.second,
                    options = options,
                    correctOptionIds = options.filterIndexed { i, _ -> back.options[i].correct }.map { it.id },
                    explanation = AnkiHtml.toRichText(back.explanation),
                    difficulty = difficulty,
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
                options = listOf(
                    OptionDto("o0", text = answer.first.ifBlank { front.first }, image = answer.second)
                ),
                correctOptionIds = listOf("o0"),
                difficulty = difficulty,
                tags = note.tags
            ),
            isRecall = true
        )
    }

    /** `mcqapp-difficulty-easy|medium|hard` on a foreign note, else the default. */
    private fun difficultyFromTags(tags: List<String>): String {
        val tag = tags.firstOrNull { it.startsWith("mcqapp-difficulty-") } ?: return "medium"
        return tag.removePrefix("mcqapp-difficulty-").takeIf { it in setOf("easy", "medium", "hard") }
            ?: "medium"
    }

    // ---- rich HTML fields -> content elements ----

    /**
     * Converts one Anki field's HTML into content elements in document order:
     * text runs, `<math>` blocks, `<table>` blocks, and every `<img>` (a
     * field may carry several). Image srcs are rewritten to data URIs.
     */
    private fun htmlToElements(html: String, media: MediaIndex): List<ContentElement> {
        val rewritten = media.rewrite(html).orEmpty()
        val out = mutableListOf<ContentElement>()
        val tagPattern = Regex("<(math|table|img)\\b", RegexOption.IGNORE_CASE)
        var pos = 0
        while (pos < rewritten.length) {
            val match = tagPattern.find(rewritten, pos) ?: break
            addTextRun(out, rewritten.substring(pos, match.range.first))
            when (match.groupValues[1].lowercase()) {
                "math" -> {
                    val end = findClosingTag(rewritten, "math", match.range.first)
                    out += ContentElement.MathElement(rewritten.substring(match.range.first, end))
                    pos = end
                }
                "table" -> {
                    val end = findClosingTag(rewritten, "table", match.range.first)
                    parseTable(rewritten.substring(match.range.first, end))?.let { out += it }
                    pos = end
                }
                "img" -> {
                    val end = rewritten.indexOf('>', match.range.first)
                    if (end < 0) break
                    val tag = rewritten.substring(match.range.first, end + 1)
                    val src = IMG_TAG.find(tag)?.groupValues?.get(1)
                    if (src != null) out += ContentElement.ImageElement(media.rewrite(src) ?: src)
                    pos = end + 1
                }
            }
        }
        addTextRun(out, rewritten.substring(pos))
        return out
    }

    private fun addTextRun(out: MutableList<ContentElement>, text: String) {
        // Formatting survives; the plain check keeps tag-only runs
        // (e.g. a lone <br/>) from becoming empty-looking elements.
        if (AnkiHtml.toPlainText(text).isNotEmpty()) {
            out += ContentElement.TextElement(AnkiHtml.toRichText(text))
        }
    }

    /** Index just past the matching close tag, honouring nesting. */
    private fun findClosingTag(html: String, tag: String, from: Int): Int {
        val open = Regex("<$tag\\b", RegexOption.IGNORE_CASE)
        val close = Regex("</$tag\\s*>", RegexOption.IGNORE_CASE)
        var depth = 0
        var pos = from
        while (pos < html.length) {
            val nextOpen = open.find(html, pos)
            val nextClose = close.find(html, pos)
            if (nextClose == null) return html.length
            if (nextOpen != null && nextOpen.range.first < nextClose.range.first) {
                depth++
                pos = nextOpen.range.first + 1
            } else {
                depth--
                pos = nextClose.range.last + 1
                if (depth == 0) return pos
            }
        }
        return html.length
    }

    /** `<table>` rows and cells; inline formatting inside cells survives. */
    private fun parseTable(tableHtml: String): ContentElement.TableElement? {
        val rowPattern = Regex("(?is)<tr\\b[^>]*>(.*?)</tr>")
        val cellPattern = Regex("(?is)<t[dh]\\b[^>]*>(.*?)</t[dh]>")
        val rows = rowPattern.findAll(tableHtml).map { rowMatch ->
            cellPattern.findAll(rowMatch.groupValues[1]).map { cellMatch ->
                AnkiHtml.toRichText(cellMatch.groupValues[1])
            }.toList()
        }.toList()
        return if (rows.isEmpty()) null else ContentElement.TableElement(rows)
    }

    /**
     * Splits a foreign front field into the question part and its options.
     * Foreign decks write options as `<p>(a) ...</p>` blocks; everything
     * before the first one is the question.
     */
    private fun splitForeignFront(frontHtml: String): Pair<String, List<Pair<String, String>>> {
        val optionPattern = Regex("(?is)<p>\\s*\\(([^)]+)\\)\\s*(.*?)</p>")
        val matches = optionPattern.findAll(frontHtml).toList()
        if (matches.isEmpty()) return frontHtml to emptyList()
        val questionHtml = frontHtml.substring(0, matches.first().range.first)
        val options = matches.map { it.groupValues[1] to it.groupValues[2] }
        return questionHtml to options
    }

    /**
     * Reads a foreign back field: the correct option letter from
     * `Answer: (a)` and the explanation block after `Exp:`.
     */
    private fun parseForeignBack(backHtml: String): Pair<String?, String> {
        val answer = Regex("(?i)Answer:\\s*\\(([^)]+)\\)")
            .find(backHtml)?.groupValues?.get(1)
        val expBlock = Regex("(?is)<p>\\s*Exp:\\s*(.*?)</p>")
            .find(backHtml)?.groupValues?.get(1).orEmpty()
        return answer to expBlock
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
     *
     * `ord` is Anki's cloze number − 1, not a position: a note numbered
     * `{{c1::…}}` / `{{c3::…}}` with ord 2 must resolve to c3, so the deletion
     * is chosen by parsed number. The stem hides the deletions of the current
     * number (Anki's `[...]`) and reveals the others, so it never carries the
     * answer nor the raw `{{cN::…}}` markup.
     */
    private fun clozeQuestion(note: AnkiNote, frontHtml: String, ord: Int, media: MediaIndex): QuestionDto? {
        val deletions = CLOZE_PATTERN.findAll(frontHtml)
            .map { ClozeDeletion(it.groupValues[1].toIntOrNull() ?: 0, it) }
            .toList()
        if (deletions.isEmpty()) return null
        // By number, with the old by-position read kept as the fallback for a
        // note whose numbering is not 1-based (a duplicated or skipped number).
        val target = deletions.firstOrNull { it.number == ord + 1 } ?: deletions.getOrNull(ord)
        if (target == null) return null
        val text = fieldToText(clozeStem(frontHtml, deletions, target.number), media).first
        val answer = AnkiHtml.toPlainText(target.match.groupValues[2])
        val questionId = "anki-${note.guid.ifBlank { note.id }}-$ord"
        return QuestionDto(
            id = questionId,
            text = text.ifBlank { CLOZE_BLANK },
            options = listOf(OptionDto("o0", answer)),
            correctOptionIds = listOf("o0"),
            explanation = "Cloze card from Anki.",
            tags = note.tags + "anki-cloze"
        )
    }

    /** One `{{cN::text}}` in a cloze field, with its match for rebuilding. */
    private class ClozeDeletion(val number: Int, val match: MatchResult)

    /**
     * The field with every deletion of [number] replaced by Anki's blank and
     * the rest unwrapped to their text — Anki hides only the card's own cloze.
     */
    private fun clozeStem(frontHtml: String, deletions: List<ClozeDeletion>, number: Int): String =
        buildString {
            var last = 0
            for (deletion in deletions) {
                append(frontHtml, last, deletion.match.range.first)
                if (deletion.number == number) {
                    append(CLOZE_BLANK)
                } else {
                    append(deletion.match.groupValues[2])
                }
                last = deletion.match.range.last + 1
            }
            append(frontHtml, last, frontHtml.length)
        }

    // ---- decks -> papers and categories ----

    /** A category plus the deck its questions live in (null for a level with no deck row). */
    private data class CategoryNode(val dto: CategoryDto, val deck: AnkiDeck?)

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
            val categories = buildCategories(
                prefix = root,
                childDeckNames = childNames,
                idByName = idByName,
                hasQuestions = { d -> d != null && questionsByDeck[d.id].orEmpty().isNotEmpty() }
            )
            PaperDto(
                id = "anki-${deck?.id ?: root}",
                title = root,
                // Cards in the paper's own deck are uncategorised questions.
                // They used to become a category named after the deck, which
                // gave every paper a category sharing its own name.
                questions = questionsByDeck[deck?.id].orEmpty(),
                categories = categories.map {
                    it.dto.copy(questions = questionsByDeck[it.deck?.id].orEmpty())
                }
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
        hasQuestions: (AnkiDeck?) -> Boolean,
        parentId: String? = null
    ): List<CategoryNode> {
        // The next segment below the prefix, in the order the decks appear.
        val segments = LinkedHashSet<String>()
        childDeckNames.forEach { name ->
            segments += name.removePrefix("$prefix::").substringBefore("::")
        }
        val out = mutableListOf<CategoryNode>()
        segments.forEach { segment ->
            val fullName = "$prefix::$segment"
            val deck = idByName[fullName]
            val id = categoryId(fullName, deck)
            val children = buildCategories(
                prefix = fullName,
                childDeckNames = childDeckNames.filter { it.startsWith("$fullName::") },
                idByName = idByName,
                hasQuestions = hasQuestions,
                parentId = id
            )
            // An empty deck Anki happened to include, with nothing under it, is
            // not worth a category.
            if (hasQuestions(deck) || children.isNotEmpty()) {
                out += CategoryNode(CategoryDto(id = id, title = segment, parentId = parentId), deck)
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
        val out = try {
            // Bounded: a zip bomb must not expand without limit. Reported as
            // a package error so the user gets a sentence, not a crash.
            com.mcqapp.data.SafeZip.unzip(bytes)
        } catch (e: com.mcqapp.data.ZipLimitException) {
            throw AnkiPackageException("This package is too large to import: ${e.message}")
        } catch (e: OutOfMemoryError) {
            throw AnkiPackageException("This package needs more memory than this device has.")
        }
        if (out.isEmpty()) throw AnkiPackageException("This file is not a readable Anki package")
        return out
    }

    /** Whether the collection file begins with zstd's magic (a `.colpkg`). */
    private fun startsWithZstd(file: File): Boolean = try {
        file.inputStream().use { stream ->
            val head = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = stream.read(head, read, 4 - read)
                if (n < 0) break
                read += n
            }
            read == 4 && ZSTD_MAGIC.indices.all { head[it] == ZSTD_MAGIC[it] }
        }
    } catch (e: Exception) {
        false
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
        /** Tags before the mcqapp-* filter, so difficulty survives a round trip. */
        val rawTags: List<String>,
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

/** One `{{cN::answer}}` or `{{cN::answer::hint}}` in a cloze field. */
private val CLOZE_PATTERN =
    Regex("\\{\\{c(\\d+)::(.*?)(?:::.*?)?\\}\\}", RegexOption.DOT_MATCHES_ALL)

/** What Anki renders in place of the cloze a card is asking for. */
private const val CLOZE_BLANK = "[...]"

/**
 * The collection entry names Anki has used, in the order they are preferred
 * when a package somehow carries more than one.
 */
private val COLLECTION_NAMES = listOf(
    "collection.anki2",
    "collection.anki21",
    "collection.anki21b"
)

/** Notes fetched per page while a streamed import walks a deck. */
private const val NOTE_PAGE = 2000

/** Rows per `IN (...)` chunk; SQLite's bound-variable default is 999. */
private const val IN_CHUNK = 900

/** The `media` manifest entry: a JSON map, not the pictures themselves. */
private const val MEDIA_MANIFEST = "media"
private const val IMPORT_MEDIA_PREFIX = "mcq-media-import"
private const val TEMP_COLLECTION_PREFIX = "anki-import"

/**
 * A media index that resolves nothing. Used by the deck scan, which only needs
 * to know whether a field becomes a question: an image's src — a zip name or a
 * data URI — never changes that, and decoding every picture a second time to
 * find out would cost as much as the import itself.
 */
private val PROBE_MEDIA = MediaIndex(null, { _ -> null }, cache = false)

/**
 * Resolves `<img src>` references to inline data URIs.
 *
 * A legacy package references media by the numeric zip entry name; a modern one
 * references it by the stored filename. Both are looked up in the `media`
 * manifest, and an entry missing from the manifest but present in the package
 * is used directly.
 *
 * [lookup] hands back the bytes for a name, so the whole-file reader answers
 * from its entry map and the streamed reader from its spooled files. [cache]
 * remembers resolved data URIs — on for the whole-file reader, off for the
 * streamed one, where a cache keyed by the encoded picture is the whole
 * package held in memory again.
 */
private class MediaIndex(
    manifestJson: String?,
    private val lookup: (String) -> ByteArray?,
    cache: Boolean
) {
    /** zip entry name -> original filename. */
    private val byZipName: Map<String, String> = try {
        manifestJson?.let { jsonObjectOf(it) }.orEmpty()
    } catch (e: Exception) {
        emptyMap()
    }

    /** original filename -> zip entry name, so a filename resolves in O(1). */
    private val zipNameByFilename: Map<String, String> =
        byZipName.entries.associate { (zip, filename) -> filename to zip }

    private val resolved: HashMap<String, String?>? = if (cache) HashMap() else null

    fun rewrite(html: String?): String? {
        if (html == null || !html.contains("<img", ignoreCase = true)) return html
        return IMG_TAG.replace(html) { match ->
            val src = match.groupValues[1]
            val replacement = dataUri(src)
            if (replacement == null) match.value else "<img src=\"$replacement\">"
        }
    }

    private fun dataUri(src: String): String? {
        val cache = resolved ?: return encode(src)
        return cache.getOrPut(src) { encode(src) }
    }

    private fun encode(src: String): String? {
        val zipName = zipNameByFilename[src] ?: src
        val bytes = lookup(zipName) ?: return null
        return "data:${mimeOf(bytes)};base64,${Base64.encode(bytes)}"
    }
}

/**
 * The archive's entries on disk while a streamed import runs: the collection in
 * a temp file, media spooled into a temp directory, and the `media` manifest
 * kept as text (it is a map, not the pictures). [close] removes both; the
 * startup sweep in [AnkiTempFiles] covers a process killed mid-way.
 */
private class AnkiImportWork : Closeable {

    private var dir: File? = null
    private val collectionFiles = LinkedHashMap<String, File>()
    private var manifest: String? = null
    private val media = LinkedHashMap<String, File>()

    private val directory: File
        get() = dir ?: File.createTempFile(IMPORT_MEDIA_PREFIX, "").let {
            it.delete()
            it.mkdirs()
            it.also { made -> dir = made }
        }

    /** The collection, preferring the names in [COLLECTION_NAMES] order. */
    val collectionFile: File?
        get() = COLLECTION_NAMES.firstNotNullOfOrNull { collectionFiles[it] }

    suspend fun extract(input: InputStream) {
        SafeZip.stream(input) { name, entry ->
            when {
                name in COLLECTION_NAMES -> {
                    if (name !in collectionFiles) {
                        val file = File.createTempFile(TEMP_COLLECTION_PREFIX, ".anki2")
                        file.outputStream().use { entry.copyTo(it) }
                        collectionFiles[name] = file
                    }
                }
                name == MEDIA_MANIFEST -> manifest = entry.readBytes().toString(Charsets.UTF_8)
                // A media entry (the numeric zip name) or a foreign member: a
                // temp file either way. The manifest decides what is used.
                else -> {
                    val file = File(directory, "e${media.size}")
                    file.outputStream().use { entry.copyTo(it) }
                    media[name] = file
                }
            }
        }
    }

    fun mediaIndex(): MediaIndex =
        MediaIndex(manifest, { name -> media[name]?.readBytes() }, cache = false)

    override fun close() {
        dir?.deleteRecursively()
        dir = null
        for (file in collectionFiles.values) {
            if (!file.delete()) {
                Logger.w("ANKI", "Could not delete temp collection ${file.absolutePath}")
            }
        }
        collectionFiles.clear()
    }
}

private fun mimeOf(bytes: ByteArray): String = when {
    // Full signatures, not prefixes: PNG's 8-byte magic, JPEG's SOI plus
    // the first marker byte, GIF's versioned header, and RIFF plus the
    // form type at offset 8 (a bare RIFF is not necessarily WebP).
    bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
        bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() &&
        bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
        bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte() -> "image/png"
    bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
    bytes.size >= 6 &&
        bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() &&
        bytes[3] == '8'.code.toByte() && (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
        bytes[5] == 'a'.code.toByte() -> "image/gif"
    bytes.size >= 12 &&
        String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
    // Unrecognised bytes: still labelled as an image so the data URI
    // renders (decoders sniff the real format); the extension follows.
    else -> "image/jpeg"
}

private fun jsonObjectOf(text: String): Map<String, String> =
    kotlinx.serialization.json.Json.parseToJsonElement(text)
        .let { it as kotlinx.serialization.json.JsonObject }
        .mapValues { (_, v) -> v.jsonPrimitive.content }

private object Base64 {
    fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
}
