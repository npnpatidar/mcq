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

    /** Fixed ids keep the output deterministic and diffable across exports. */
    const val DECK_ID = 1L
    const val DECK_CONFIG_ID = 1L
    const val MODEL_ID = 1_600_000_000_000L

    /**
     * Builds the package for one paper.
     *
     * Each question becomes one Anki note: front is the question text, back is
     * the options with the correct one marked plus the explanation. Media
     * references in either field are rewritten to the numeric entry names and
     * the bytes are appended to the package.
     */
    fun write(paper: PaperDto, questions: List<Question>): ByteArray {
        val media = AnkiMediaPool()
        val nowSeconds = System.currentTimeMillis() / 1000
        val nowMillis = System.currentTimeMillis()

        val notes = mutableListOf<AnkiNoteRow>()
        val cards = mutableListOf<AnkiCardRow>()

        questions.forEachIndexed { index, question ->
            // Ids only have to be unique inside the package; a timestamp base
            // keeps them sortable in the same order as the source paper.
            val noteId = nowMillis + index
            val front = media.htmlField(question.text, question.image)
            val back = buildBack(question, media)
            notes += AnkiNoteRow(
                id = noteId,
                guid = guidFor(noteId),
                mod = nowSeconds,
                tags = formatTags(question),
                fields = "$front$FIELD_SEPARATOR$back"
            )
            cards += AnkiCardRow(
                id = noteId + 1,
                noteId = noteId,
                mod = nowSeconds,
                // Anki numbers new cards from col.conf.nextPos; only the relative
                // order matters, so an increasing sequence is correct.
                due = index + 1
            )
        }

        val collectionBytes = buildCollection(
            notes = notes,
            cards = cards,
            deckName = paper.title,
            nowSeconds = nowSeconds,
            nowMillis = nowMillis
        )

        val apkg = zip(collectionBytes, media)
        Logger.i("EXPORT", "Wrote .apkg with ${notes.size} notes, ${media.entries.size} media files")
        return apkg
    }

    private fun buildBack(question: Question, media: AnkiMediaPool): String {
        val sb = StringBuilder()
        // Single-choice questions read naturally as a list; multi-choice
        // questions must not imply exactly-one-is-right, so they are labelled.
        if (question.correctOptionIds.size > 1) {
            sb.append("<b>Select all that apply.</b>").append(LINE_BREAK)
        }
        question.options.forEach { option ->
            val isCorrect = option.id in question.correctOptionIds
            val marker = if (isCorrect) "&#10003; " else "&#10007; "
            sb.append(marker)
            sb.append(media.htmlField(option.text, option.image))
            sb.append(LINE_BREAK)
        }
        if (question.explanation.isNotBlank()) {
            sb.append("<br><b>Explanation:</b> ")
                .append(media.htmlField(question.explanation, question.explanationImage))
        }
        return sb.toString()
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
     * A stable, Anki-shaped guid. Anki keys deduplication on this value, so a
     * re-export of the same paper produces identical guids and importing the
     * updated package updates notes in place instead of duplicating them.
     */
    fun guidFor(seed: Long): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#$%&()*+,-./:;<=>?@[]^_`{|}~"
        var n = seed * 2_654_435_761L
        return buildString(10) {
            repeat(10) {
                n = n * 6_364_136_223_846_793_005L + 1
                append(alphabet[((n ushr 33) % alphabet.length).toInt()])
            }
        }
    }

    private fun buildCollection(
        notes: List<AnkiNoteRow>,
        cards: List<AnkiCardRow>,
        deckName: String,
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
                conf = AnkiSchema11.configJson(modelId = MODEL_ID, nextPos = notes.size + 1),
                models = AnkiSchema11.modelsJson(MODEL_ID, nowSeconds),
                decks = AnkiSchema11.decksJson(DECK_ID, deckName, nowSeconds),
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
        val fields: String
    )

    /**
     * One row of Anki's `cards` table. Every field is an initial value for a
     * brand-new card except [due], which sets the new-card ordering position.
     */
    data class AnkiCardRow(
        val id: Long,
        val noteId: Long,
        val mod: Long,
        val due: Int
    )
}