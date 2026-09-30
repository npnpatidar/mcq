package com.mcqapp.data.anki

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest

/**
 * Creates and fills an Anki schema-11 SQLite collection (`collection.anki2`).
 *
 * The exact table layout comes from Anki's own
 * `rslib/src/storage/schema11.sql`; the JSON blobs in the `col` row follow the
 * schema-11 deserializers in `rslib/src/.../schema11.rs` (deckconfig, decks,
 * notetype). Writing this old, stable shape lets Anki's battle-tested upgrade
 * chain turn the package into the current schema on import while keeping it
 * readable by every Anki client.
 */
object AnkiSchema11 {

    fun create(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE col (
              id integer PRIMARY KEY,
              crt integer NOT NULL,
              mod integer NOT NULL,
              scm integer NOT NULL,
              ver integer NOT NULL,
              dty integer NOT NULL,
              usn integer NOT NULL,
              ls integer NOT NULL,
              conf text NOT NULL,
              models text NOT NULL,
              decks text NOT NULL,
              dconf text NOT NULL,
              tags text NOT NULL
            );
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE notes (
              id integer PRIMARY KEY,
              guid text NOT NULL,
              mid integer NOT NULL,
              mod integer NOT NULL,
              usn integer NOT NULL,
              tags text NOT NULL,
              flds text NOT NULL,
              sfld text NOT NULL,
              csum integer NOT NULL,
              flags integer NOT NULL,
              data text NOT NULL
            );
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE cards (
              id integer PRIMARY KEY,
              nid integer NOT NULL,
              did integer NOT NULL,
              ord integer NOT NULL,
              mod integer NOT NULL,
              usn integer NOT NULL,
              type integer NOT NULL,
              queue integer NOT NULL,
              due integer NOT NULL,
              ivl integer NOT NULL,
              factor integer NOT NULL,
              reps integer NOT NULL,
              lapses integer NOT NULL,
              left integer NOT NULL,
              odue integer NOT NULL,
              odid integer NOT NULL,
              flags integer NOT NULL,
              data text NOT NULL
            );
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE revlog (
              id integer PRIMARY KEY,
              cid integer NOT NULL,
              usn integer NOT NULL,
              ease integer NOT NULL,
              ivl integer NOT NULL,
              lastIvl integer NOT NULL,
              factor integer NOT NULL,
              time integer NOT NULL,
              type integer NOT NULL
            );
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE graves (
              usn integer NOT NULL,
              oid integer NOT NULL,
              type integer NOT NULL
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX ix_notes_usn ON notes (usn)")
        db.execSQL("CREATE INDEX ix_cards_usn ON cards (usn)")
        db.execSQL("CREATE INDEX ix_revlog_usn ON revlog (usn)")
        db.execSQL("CREATE INDEX ix_cards_nid ON cards (nid)")
        db.execSQL("CREATE INDEX ix_cards_sched ON cards (did, queue, due)")
        db.execSQL("CREATE INDEX ix_revlog_cid ON revlog (cid)")
        db.execSQL("CREATE INDEX ix_notes_csum ON notes (csum)")
    }

    fun writeColRow(
        db: SQLiteDatabase,
        crt: Long,
        mod: Long,
        conf: String,
        models: String,
        decks: String,
        dconf: String
    ) {
        db.execSQL(
            "INSERT INTO col (id, crt, mod, scm, ver, dty, usn, ls, conf, models, decks, dconf, tags) " +
                "VALUES (1, ?, ?, ?, ?, 0, 0, 0, ?, ?, ?, ?, '{}')",
            arrayOf(
                crt,
                mod,
                mod,
                AnkiPackageWriter.SCHEMA_VERSION,
                conf,
                models,
                decks,
                dconf
            )
        )
    }

    fun stripHtml(text: String): String =
        text.replace(Regex("<[^>]*>"), "").trim()

    fun insertNotes(db: SQLiteDatabase, notes: List<AnkiPackageWriter.AnkiNoteRow>) {
        val frontToCsum = HashMap<String, Long>()
        db.beginTransaction()
        try {
            notes.forEach { note ->
                val front = note.fields.substringBefore(AnkiPackageWriter.FIELD_SEPARATOR)
                val sfld = stripHtml(front).take(MAX_SORT_FIELD_CHARS)
                val csum = frontToCsum.getOrPut(front) { fieldChecksum(front) }
                db.execSQL(
                    "INSERT INTO notes (id, guid, mid, mod, usn, tags, flds, sfld, csum, flags, data) " +
                        "VALUES (?, ?, ${AnkiPackageWriter.MODEL_ID}, ?, 0, ?, ?, ?, ?, 0, '')",
                    arrayOf(note.id, note.guid, note.mod, note.tags, note.fields, sfld, csum)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun insertCards(db: SQLiteDatabase, cards: List<AnkiPackageWriter.AnkiCardRow>) {
        db.beginTransaction()
        try {
            cards.forEach { card ->
                db.execSQL(
                    "INSERT INTO cards (id, nid, did, ord, mod, usn, type, queue, due, ivl, factor, reps, lapses, left, odue, odid, flags, data) " +
                        "VALUES (?, ?, ${AnkiPackageWriter.DECK_ID}, 0, ?, 0, 0, 0, ?, 0, 0, 0, 0, 0, 0, 0, 0, '')",
                    arrayOf(card.id, card.noteId, card.mod, card.due)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** `col.conf` — a subset of the classic keys; the rest get defaults. */
    fun configJson(modelId: Long, nextPos: Int): String = buildJsonObject {
        put("activeDecks", buildJsonArray { add(AnkiPackageWriter.DECK_ID) })
        put("curDeck", AnkiPackageWriter.DECK_ID)
        put("newSpread", 0)
        put("collapseTime", 1200)
        put("timeLim", 0)
        put("estTimes", true)
        put("dueCounts", true)
        put("curModel", modelId)
        put("nextPos", nextPos)
        put("sortType", "noteFld")
        put("sortBackwards", false)
        put("addToCur", true)
        put("dayLearnFirst", false)
        put("cardCounts", true)
    }.toString()

    /** `col.models` — a Basic/standard notetype with one card template. */
    fun modelsJson(modelId: Long, nowSeconds: Long): String = buildJsonObject {
        putJsonObject(modelId.toString()) {
            put("id", modelId)
            put("name", "Basic")
            put("type", 0)
            put("mod", nowSeconds)
            put("usn", 0)
            put("sortf", 0)
            put("did", AnkiPackageWriter.DECK_ID)
            putJsonArray("tmpls") {
                add(buildJsonObject {
                    put("name", "Card 1")
                    put("ord", 0)
                    put("qfmt", "{{Front}}")
                    put("afmt", "{{FrontSide}}\n\n<hr id=answer>\n\n{{Back}}")
                })
            }
            putJsonArray("flds") {
                add(field("Front", 0))
                add(field("Back", 1))
                // Structured copy of the question, so importing the deck back
                // recovers options and correct answers exactly.
                add(field(PAYLOAD_FIELD, 2))
            }
            put(
                "css",
                ".card {\n font-family: arial;\n font-size: 20px;\n" +
                    " text-align: center;\n color: black;\n background-color: white;\n}"
            )
            put(
                "latexPre",
                "\\documentclass[12pt]{article}\n\\special{papersize=3in,5in}\n" +
                    "\\usepackage[utf8]{inputenc}\n\\usepackage{amssymb,amsmath}\n" +
                    "\\pagestyle{empty}\n\\setlength{\\parindent}{0in}\n\\begin{document}\n"
            )
            put("latexPost", "\\end{document}\n")
            put("latexsvg", false)
            // req: [[card_ord, kind, field_ords]] — one "any of Front" template.
            put("req", buildJsonArray {
                add(buildJsonArray { add(0); add("any"); add(buildJsonArray { add(0) }) })
            })
        }
    }.toString()

    /** `col.decks` — a single normal deck named after the paper. */
    fun decksJson(deckId: Long, name: String, nowSeconds: Long): String = buildJsonObject {
        putJsonObject(deckId.toString()) {
            put("id", deckId)
            put("mod", nowSeconds)
            put("name", name)
            put("usn", 0)
            putJsonArray("lrnToday") { add(0); add(0) }
            putJsonArray("revToday") { add(0); add(0) }
            putJsonArray("newToday") { add(0); add(0) }
            putJsonArray("timeToday") { add(0); add(0) }
            put("collapsed", false)
            put("browserCollapsed", false)
            put("desc", "")
            put("dyn", 0)
            put("conf", AnkiPackageWriter.DECK_CONFIG_ID)
            put("extendNew", 0)
            put("extendRev", 0)
        }
    }.toString()

    /** `col.dconf` — a single "Default" deck config. */
    fun dconfJson(deckConfigId: Long, nowSeconds: Long): String = buildJsonObject {
        putJsonObject(deckConfigId.toString()) {
            put("id", deckConfigId)
            put("mod", nowSeconds)
            put("name", "Default")
            put("usn", 0)
            put("maxTaken", 60)
            put("autoplay", true)
            put("timer", 0)
            put("replayq", true)
            putJsonObject("new") {
                put("bury", false)
                putJsonArray("delays") { add(1.0f); add(10.0f) }
                put("initialFactor", 2500)
                putJsonArray("ints") { add(1); add(4); add(0) }
                put("order", 1)
                put("perDay", 20)
            }
            putJsonObject("rev") {
                put("bury", false)
                put("ease4", 1.3f)
                put("ivlFct", 1.0f)
                put("maxIvl", 36500)
                put("perDay", 200)
                put("hardFactor", 1.2f)
            }
            putJsonObject("lapse") {
                putJsonArray("delays") { add(10.0f) }
                put("leechAction", 0)
                put("leechFails", 8)
                put("minInt", 1)
                put("mult", 0.0f)
            }
            put("dyn", false)
        }
    }.toString()

    private val SHA1_HEX_BYTES = 4

    /** Name of the notefield carrying the structured MCQ payload. */
    const val PAYLOAD_FIELD = "mcqapp"

    private fun field(name: String, ord: Int): JsonObject = buildJsonObject {
        put("name", name)
        put("ord", ord)
        put("sticky", false)
        put("rtl", false)
        put("font", "Arial")
        put("size", 20)
    }

    /**
     * Anki's field checksum: the first 4 bytes of the SHA-1 of the field.
     *
     * Returned as a [Long] because the value spans the whole unsigned 32-bit
     * range and Anki stores it in a 64-bit SQLite INTEGER (Anki's original
     * implementation is arbitrary-precision Python; the Rust side reads it back
     * as a `u32`).
     */
    fun fieldChecksum(field: String): Long {
        val digest = MessageDigest.getInstance("SHA-1").digest(field.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return hex.substring(0, SHA1_HEX_BYTES * 2).toLong(16)
    }

    private const val MAX_SORT_FIELD_CHARS = 1024
}