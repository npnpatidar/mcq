package com.mcqapp.data.backup

import com.mcqapp.data.SafeZip
import com.mcqapp.data.ZipLimitException
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.StreamingImporter
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.io.Importer
import com.mcqapp.util.ImportTooLargeException
import com.mcqapp.util.Logger
import com.mcqapp.util.readBounded
import androidx.room.withTransaction
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The app's full backup: one zip, human-readable inside, that restores the
 * library to exactly the state it was exported from.
 *
 * Layout (pretty-printed JSON, one concern per file):
 *  - `manifest.json`  — format id, versions, counts, when it was taken
 *  - `content.json`   — every paper, category, question, option, correct
 *    answer, passage, bookmark and attempt (the full-schema shape the
 *    importer reads)
 *  - `study.json`     — `card_state` rows verbatim (per paper, with their
 *    content hashes) and the `review_log` history, neither of which the
 *    question-keyed scheduling map can carry, plus the resume snapshot
 *  - `settings.json`  — every preference with its type
 *
 * Images stay inline in `content.json` as data URIs: extracting them to
 * files and re-encoding on restore could not guarantee byte-identical
 * strings, and a changed string would orphan the schedules keyed to the
 * question's content hash.
 */
object McqBackupFormat {

    const val FORMAT = "mcqapp-backup"

    /** Bumped when the layout changes; readers accept any version ≤ [VERSION]. */
    const val VERSION = 1

    const val MANIFEST = "manifest.json"
    const val CONTENT = "content.json"
    const val STUDY = "study.json"
    const val SETTINGS = "settings.json"

    val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    /**
     * Ceiling for the two small parts — the manifest and the settings — which
     * are held whole while they are decoded. The manifest is a handful of
     * numbers and the settings a map of primitives, so anything past this is a
     * corrupt or hostile file rather than a large backup; the two parts that
     * can genuinely be large stream and have their own caps.
     */
    private const val MAX_PART_BYTES: Long = 8L * 1024 * 1024

    /** The files this app's own writer puts in a backup. */
    private val PART_NAMES = setOf(MANIFEST, CONTENT, STUDY, SETTINGS)

    /**
     * The cap [isBackupArchive] reads the manifest under, so the picker's
     * streamed classifier can read it the same way.
     */
    internal const val MANIFEST_LIMIT_BYTES: Long = MAX_PART_BYTES

    /**
     * Whether [name] is a member one of our backups can carry (or a note a file
     * manager dropped in). The picker's streamed classifier walks an archive's
     * entries with this before deciding the file is not one of ours, so a
     * foreign archive is never inflated to be told so.
     */
    internal fun isBackupMemberName(name: String): Boolean =
        name in PART_NAMES || name.startsWith("__MACOSX/")

    /**
     * The `format` field of a manifest entry as it was written — not decoded
     * through the serializer, which is lenient and would call any JSON object a
     * backup. Null when the entry is not readable JSON or has no field.
     */
    internal fun manifestFormat(text: String): String? = try {
        json.parseToJsonElement(text).jsonObject["format"]?.jsonPrimitive?.content
    } catch (e: Exception) {
        null
    }

    @Serializable
    data class Manifest(
        val format: String = FORMAT,
        val version: Int = VERSION,
        val dbVersion: Int = 0,
        val appVersion: String? = null,
        val exportedAt: Long = 0,
        val papers: Int = 0,
        val questions: Int = 0,
        val attempts: Int = 0,
        val reviews: Int = 0
    )

    @Serializable
    data class PreferenceEntry(val type: String, val value: JsonElement)

    @Serializable
    data class SettingsFile(val preferences: Map<String, PreferenceEntry> = emptyMap())

    @Serializable
    data class CardStateRow(
        val paperId: String,
        val questionId: String,
        val ease: Double,
        val intervalDays: Int,
        val dueAt: Long,
        val reps: Int,
        val lapses: Int,
        val leech: Boolean,
        val lastReviewedAt: Long,
        val contentHash: String,
        val difficulty: Double = 0.0,
        val stability: Double = 0.0,
        val cardState: String = "NEW",
        val reviewCount: Int = 0
    )

    @Serializable
    data class ReviewLogRow(
        val id: Long,
        val paperId: String,
        val questionId: String,
        val rating: Int,
        val reviewedAt: Long,
        val elapsedSeconds: Long? = null,
        val stateBefore: String? = null,
        val stabilityAfter: Double? = null,
        val difficultyAfter: Double? = null,
        val intervalDaysAfter: Int? = null
    )

    @Serializable
    data class PaperCreated(val id: String, val createdAt: Long)

    @Serializable
    data class StudyFile(
        val cardState: List<CardStateRow> = emptyList(),
        val reviewLog: List<ReviewLogRow> = emptyList(),
        /**
         * Paper creation times. The import format has no field for them
         * (the importer stamps fresh rows), and a restored library must
         * not look like it was born during the restore.
         */
        val paperCreated: List<PaperCreated> = emptyList(),
        val inProgressTest: String? = null
    )

    /** Everything one backup zip holds, decoded. */
    data class Backup(
        val manifest: Manifest,
        val content: McqFileDto,
        val study: StudyFile,
        val settings: SettingsFile
    )

    class Invalid(message: String) : Exception(message)

    /**
     * Receives a backup's parts as its zip is read. Every callback runs while
     * its entry is still streaming, so an implementation has to be finished
     * with the stream before it returns: the next entry cannot be opened until
     * it is. Only the two small parts (the manifest, the settings) arrive
     * decoded; the bank and the study rows arrive as they are, because those
     * are the two that can outgrow memory.
     *
     * The content always comes first: study rows reference the papers and
     * questions it creates, so a backup that holds them the other way round is
     * refused before any of them is offered.
     */
    interface StreamingSink {
        /** The content entry, still streaming; read it to the end. */
        suspend fun content(json: InputStream) {}
        suspend fun cardState(row: CardStateRow) {}
        suspend fun reviewLog(row: ReviewLogRow) {}
        suspend fun paperCreated(row: PaperCreated) {}
        suspend fun inProgressTest(json: String?) {}
        suspend fun settings(settings: SettingsFile) {}
    }

    /**
     * Reads a backup straight off [input], handing each part to [sink] as the
     * archive is unzipped and returning the manifest. Neither the archive, nor
     * the bank, nor the study table ever exists as one object, which is what
     * lets a backup far larger than the heap be restored at all.
     *
     * Parts are read in the order [write] and [writeTo] lay them out. Only one
     * ordering is a hard requirement: the content must arrive before the study
     * rows, because those rows reference the papers and questions it creates.
     * A backup that holds them the other way round — a zip unpacked and
     * re-packed by hand — cannot be read without buffering the rows, which is
     * the memory this exists to avoid, so its rows are dropped and it is
     * refused with a message that says so. The app has only ever written them
     * in this order.
     */
    suspend fun readStreaming(input: InputStream, sink: StreamingSink): Manifest {
        var entries = 0
        var manifest: Manifest? = null
        var content = false
        var study = false
        var settings = false
        var studyBeforeContent = false
        try {
            SafeZip.stream(input) { name, entry ->
                when (name) {
                    MANIFEST -> manifest = validate(
                        readPart(
                            entry,
                            Manifest.serializer(),
                            failure = { "Not an mcqapp backup (unreadable manifest)." }
                        )
                    )
                    CONTENT -> {
                        content = true
                        sink.content(entry)
                    }
                    STUDY -> {
                        study = true
                        // Rows reference the bank, so they are only handed
                        // over once it has been. A backup whose content is
                        // missing or came later is refused below with the
                        // message that names the part, and its rows are
                        // dropped rather than upserted into a library that is
                        // about to be left alone.
                        if (content) readStudy(entry, sink) else studyBeforeContent = true
                    }
                    SETTINGS -> {
                        settings = true
                        sink.settings(
                            readPart(
                                entry,
                                SettingsFile.serializer(),
                                failure = { "The backup's settings file is unreadable: $it" }
                            )
                        )
                    }
                    // Anything else is ignored, the way unzip's map used to
                    // keep it and nobody ever looked.
                    else -> Unit
                }
                entries++
            }
        } catch (e: Invalid) {
            throw e
        } catch (e: ZipLimitException) {
            throw Invalid("The backup is too large to read (${e.message}).")
        } catch (e: ImportTooLargeException) {
            // The cap on the archive's own bytes is applied by the reader's
            // caller, which has a message that talks about the restore limit.
            throw e
        } catch (e: IOException) {
            throw Invalid("Not a readable zip file: ${e.message}")
        }
        if (entries == 0) throw Invalid("Not a readable zip file: the archive is empty.")
        // A zip without our manifest is not one of our backups, which is the
        // message [read] gives it too — its missing-part error surfaces from
        // inside the manifest decode and is reported as an unreadable one.
        val found = manifest ?: throw Invalid("Not an mcqapp backup (unreadable manifest).")
        if (!content) throw Invalid("This backup is missing $CONTENT — it may be truncated.")
        if (!study) throw Invalid("This backup is missing $STUDY — it may be truncated.")
        if (!settings) throw Invalid("This backup is missing $SETTINGS — it may be truncated.")
        if (studyBeforeContent) {
            throw Invalid(
                "This backup holds its study rows before its content — mcqapp " +
                    "cannot read a backup re-packed in that order."
            )
        }
        return found
    }

    /**
     * The manifest's two promises: this is an mcqapp backup, and this app is
     * new enough to read it. Checked the moment the manifest is read, exactly
     * as [read] checks them — and in every archive this app has written the
     * manifest comes first, so a foreign file is refused before its content
     * can wipe anything. Even a re-packed one that moves the manifest later is
     * safe: a restore runs in a transaction that refusing rolls back.
     */
    private fun validate(manifest: Manifest): Manifest {
        if (manifest.format != FORMAT) {
            throw Invalid("Not an mcqapp backup (found format '${manifest.format}').")
        }
        if (manifest.version > VERSION) {
            throw Invalid(
                "This backup was made by a newer app version (format ${manifest.version} " +
                    "vs $VERSION here). Update the app and try again."
            )
        }
        return manifest
    }

    /**
     * The study file, row by row. Each row's text goes through the serializer
     * that wrote it, so a row gains and loses fields exactly as it does for
     * [read]; unknown fields are skipped and absent ones take their defaults.
     */
    private suspend fun readStudy(entry: InputStream, sink: StreamingSink) {
        val stream = JsonValueStream(entry)
        stream.beginObject()
        while (true) {
            val field = stream.readName() ?: break
            when (field) {
                "cardState" -> stream.readArray {
                    sink.cardState(row(CardStateRow.serializer(), it))
                }
                "reviewLog" -> stream.readArray {
                    sink.reviewLog(row(ReviewLogRow.serializer(), it))
                }
                "paperCreated" -> stream.readArray {
                    sink.paperCreated(row(PaperCreated.serializer(), it))
                }
                "inProgressTest" -> sink.inProgressTest(
                    row(String.serializer().nullable, stream.readValue())
                )
                else -> stream.skipValue()
            }
        }
        stream.endObject()
    }

    private fun <T> row(serializer: KSerializer<T>, raw: String): T = try {
        json.decodeFromString(serializer, raw)
    } catch (e: Exception) {
        throw Invalid("The backup's study file is unreadable: ${e.message}")
    }

    /**
     * A part small enough to hold whole (the manifest, the settings). Reading
     * those as text is what keeps their counts and preferences exactly the
     * bytes [write] produced; the two big parts never come through here.
     */
    private fun <T> readPart(
        entry: InputStream,
        serializer: KSerializer<T>,
        failure: (String?) -> String
    ): T {
        val text = try {
            readBounded(entry, limit = MAX_PART_BYTES)
        } catch (e: ImportTooLargeException) {
            throw Invalid(failure("it is larger than ${MAX_PART_BYTES / (1024 * 1024)} MB"))
        }
        return try {
            json.decodeFromString(serializer, text.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw Invalid(failure(e.message))
        }
    }

    /** The whole backup to zip bytes. Pure JVM: no Android types involved. */
    fun write(
        manifest: Manifest,
        content: McqFileDto,
        study: StudyFile,
        settings: SettingsFile
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry(MANIFEST, json.encodeToString(Manifest.serializer(), manifest))
            entry(CONTENT, json.encodeToString(McqFileDto.serializer(), content))
            entry(STUDY, json.encodeToString(StudyFile.serializer(), study))
            entry(SETTINGS, json.encodeToString(SettingsFile.serializer(), settings))
        }
        return out.toByteArray()
    }

    /**
     * The streaming write: every entry's body is produced through a writer
     * while the zip entry is open, so the content (the whole bank) and the
     * study rows never exist as one object or one String. The in-memory
     * [write] stays for tests and small callers.
     */
    fun writeTo(
        output: OutputStream,
        manifest: Manifest,
        content: suspend (java.io.BufferedWriter) -> Unit,
        study: suspend (java.io.BufferedWriter) -> Unit,
        settings: SettingsFile
    ) {
        ZipOutputStream(output.buffered()).use { zip ->
            suspend fun entry(name: String, body: suspend (java.io.BufferedWriter) -> Unit) {
                zip.putNextEntry(ZipEntry(name))
                val w = java.io.BufferedWriter(
                    java.io.OutputStreamWriter(zip, Charsets.UTF_8), 1 shl 14
                )
                body(w)
                w.flush()
                zip.closeEntry()
            }
            runBlocking {
                entry(MANIFEST) { it.write(json.encodeToString(Manifest.serializer(), manifest)) }
                entry(CONTENT, content)
                entry(STUDY, study)
                entry(SETTINGS) { it.write(json.encodeToString(SettingsFile.serializer(), settings)) }
            }
        }
    }

    fun stateRow(entity: com.mcqapp.data.local.CardStateEntity): CardStateRow = CardStateRow(
        paperId = entity.paperId,
        questionId = entity.questionId,
        ease = entity.ease,
        intervalDays = entity.intervalDays,
        dueAt = entity.dueAt,
        reps = entity.reps,
        lapses = entity.lapses,
        leech = entity.leech,
        lastReviewedAt = entity.lastReviewedAt,
        contentHash = entity.contentHash,
        difficulty = entity.difficulty,
        stability = entity.stability,
        cardState = entity.cardState.name,
        reviewCount = entity.reviewCount
    )

    fun logRow(entity: com.mcqapp.data.local.ReviewLogEntity): ReviewLogRow = ReviewLogRow(
        id = entity.id,
        paperId = entity.paperId,
        questionId = entity.questionId,
        rating = entity.rating,
        reviewedAt = entity.reviewedAt,
        elapsedSeconds = entity.elapsedSeconds,
        stateBefore = entity.stateBefore,
        stabilityAfter = entity.stabilityAfter,
        difficultyAfter = entity.difficultyAfter,
        intervalDaysAfter = entity.intervalDaysAfter
    )

    /**
     * The study file, written one row at a time. The two big tables arrive as
     * emitters rather than lists, so the caller pages them ([BackupSource] does,
     * for the settings screen) and a backup of a million reviews never holds the
     * table in memory in order to write it.
     */
    suspend fun writeStudyRows(
        w: java.io.BufferedWriter,
        cardState: suspend (emit: suspend (com.mcqapp.data.local.CardStateEntity) -> Unit) -> Unit,
        reviewLog: suspend (emit: suspend (com.mcqapp.data.local.ReviewLogEntity) -> Unit) -> Unit,
        paperCreated: List<PaperCreated>,
        inProgressTest: String?
    ) {
        w.write("{\"cardState\":[")
        var first = true
        cardState { entity ->
            if (!first) w.write(",")
            first = false
            w.write(json.encodeToString(CardStateRow.serializer(), stateRow(entity)))
        }
        w.write("],\"reviewLog\":[")
        first = true
        reviewLog { entity ->
            if (!first) w.write(",")
            first = false
            w.write(json.encodeToString(ReviewLogRow.serializer(), logRow(entity)))
        }
        w.write("],\"paperCreated\":[")
        paperCreated.forEachIndexed { i, row ->
            if (i > 0) w.write(",")
            w.write(json.encodeToString(PaperCreated.serializer(), row))
        }
        w.write("],\"inProgressTest\":")
        w.write(if (inProgressTest == null) "null" else json.encodeToString(String.serializer(), inProgressTest))
        w.write("}")
    }

    /**
     * Whether [bytes] is one of this app's backup archives: a zip holding our
     * `manifest.json`.
     *
     * Only the manifest is ever held in memory — the bank, the study table and
     * any image are walked past, never read — because this runs on a file the
     * user has just picked, to say what they picked. A backup is restored from
     * Settings, not imported as a bank; the picker used to hand one to the Anki
     * reader, which answered with an Anki-shaped error about a file that was
     * never an Anki package.
     */
    fun isBackupArchive(bytes: ByteArray): Boolean {
        if (bytes.size < 4 ||
            bytes[0] != 'P'.code.toByte() ||
            bytes[1] != 'K'.code.toByte()
        ) {
            return false
        }
        return try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                // Our writer lays the manifest out first, so this is one entry
                // in practice. A re-pack — a file manager's "compress" may
                // prepend its own notes — can move it, so the scan keeps
                // looking. What it walks past says whether the archive is ours:
                // our own files, whatever their size (a re-pack can put a large
                // bank in front of the manifest) and the notes a file manager
                // drops in. Anything else ends the scan, so an Anki package or
                // a Word file is never inflated to be told it is not a backup.
                // The member's own size field cannot decide this: a zip written
                // with a data descriptor reports it as -1.
                var checked = 0
                var entry = zip.nextEntry
                while (entry != null && checked < 12) {
                    if (!entry.isDirectory && entry.name == MANIFEST) {
                        val text = readBounded(zip, limit = MAX_PART_BYTES)
                            .toString(Charsets.UTF_8)
                        return manifestFormat(text) == FORMAT
                    }
                    val ours = entry.isDirectory || isBackupMemberName(entry.name)
                    if (!ours) return false
                    zip.closeEntry()
                    entry = zip.nextEntry
                    checked++
                }
                false
            }
        } catch (e: Exception) {
            // Unreadable, truncated, or not JSON where the manifest should be:
            // all of them mean "not one of ours" to a caller deciding what to
            // do with a file.
            false
        }
    }

    /** Zip bytes to a decoded [Backup]; every part must be present and valid. */
    fun read(bytes: ByteArray): Backup {
        val entries = try {
            SafeZip.unzip(bytes)
        } catch (e: ZipLimitException) {
            throw Invalid("The backup is too large to read (${e.message}).")
        } catch (e: Exception) {
            throw Invalid("Not a readable zip file: ${e.message}")
        }
        if (entries.isEmpty()) {
            throw Invalid("Not a readable zip file: the archive is empty.")
        }
        fun part(name: String): String =
            entries[name]?.toString(Charsets.UTF_8)
                ?: throw Invalid("This backup is missing $name — it may be truncated.")
        val manifest = try {
            json.decodeFromString(Manifest.serializer(), part(MANIFEST))
        } catch (e: Exception) {
            throw Invalid("Not an mcqapp backup (unreadable manifest).")
        }
        if (manifest.format != FORMAT) {
            throw Invalid("Not an mcqapp backup (found format '${manifest.format}').")
        }
        if (manifest.version > VERSION) {
            throw Invalid(
                "This backup was made by a newer app version (format ${manifest.version} " +
                    "vs ${VERSION} here). Update the app and try again."
            )
        }
        val content = try {
            json.decodeFromString(McqFileDto.serializer(), part(CONTENT))
        } catch (e: Exception) {
            throw Invalid("The backup's content file is unreadable: ${e.message}")
        }
        val study = try {
            json.decodeFromString(StudyFile.serializer(), part(STUDY))
        } catch (e: Exception) {
            throw Invalid("The backup's study file is unreadable: ${e.message}")
        }
        val settings = try {
            json.decodeFromString(SettingsFile.serializer(), part(SETTINGS))
        } catch (e: Exception) {
            throw Invalid("The backup's settings file is unreadable: ${e.message}")
        }
        return Backup(manifest, content, study, settings)
    }
}

/** Collects the live database into a backup. */
class BackupSource(private val db: AppDatabase) {

    /**
     * One snapshot, one transaction: a concurrent study session cannot
     * slip a review between the content and the study rows, and the
     * manifest's counts come from the same content the zip carries —
     * the export is built once, not twice.
     */
    suspend fun collect(
        appVersion: String?,
        inProgressTest: String?
    ): McqBackupFormat.Backup {
        lateinit var content: McqFileDto
        lateinit var study: McqBackupFormat.StudyFile
        var papers = 0
        var attempts = 0
        var reviews = 0
        db.withTransaction {
            content = com.mcqapp.data.io.Exporter(db)
                .exportAllDto(withScheduling = false)
            val cardState = db.cardStateDao().getAll()
            val reviewLog = db.reviewLogDao().getAll()
            val paperRows = db.paperDao().getAll()
            study = McqBackupFormat.StudyFile(
                cardState = cardState.map { it.toRow() },
                reviewLog = reviewLog.map { it.toRow() },
                paperCreated = paperRows.map { McqBackupFormat.PaperCreated(it.id, it.createdAt) },
                inProgressTest = inProgressTest
            )
            papers = paperRows.size
            attempts = db.attemptDao().getAllAttempts().size
            reviews = reviewLog.size
        }
        val manifest = McqBackupFormat.Manifest(
            dbVersion = db.openHelper.readableDatabase.version,
            appVersion = appVersion,
            exportedAt = System.currentTimeMillis(),
            papers = papers,
            questions = content.papers.sumOf { paper ->
                paper.categories.sumOf { it.questions.size } + paper.questions.size
            },
            attempts = attempts,
            reviews = reviews
        )
        return McqBackupFormat.Backup(
            manifest = manifest,
            content = content,
            study = study,
            settings = McqBackupFormat.SettingsFile()
        )
    }

    /**
     * Pages every card into [emit] in physical (`rowid`) order — the order
     * `getAll()` returns and the order the backup has always written — with one
     * page live at a time.
     */
    suspend fun emitCards(emit: suspend (CardStateEntity) -> Unit) {
        var afterRowId = 0L
        while (true) {
            val page = db.cardStateDao().pageAfterRowId(afterRowId, PAGE)
            if (page.isEmpty()) break
            page.forEach { emit(it.state) }
            afterRowId = page.last().rowid
            if (page.size < PAGE) break
        }
    }

    /** Pages every review into [emit] in `id` order, one page at a time. */
    suspend fun emitReviews(emit: suspend (ReviewLogEntity) -> Unit) {
        var afterId = 0L
        while (true) {
            val page = db.reviewLogDao().pageAfterId(afterId, PAGE)
            if (page.isEmpty()) break
            page.forEach { emit(it) }
            afterId = page.last().id
            if (page.size < PAGE) break
        }
    }

    internal fun CardStateEntity.toRow() = McqBackupFormat.CardStateRow(
        paperId = paperId,
        questionId = questionId,
        ease = ease,
        intervalDays = intervalDays,
        dueAt = dueAt,
        reps = reps,
        lapses = lapses,
        leech = leech,
        lastReviewedAt = lastReviewedAt,
        contentHash = contentHash,
        difficulty = difficulty,
        stability = stability,
        cardState = cardState.name,
        reviewCount = reviewCount
    )

    internal fun ReviewLogEntity.toRow() = McqBackupFormat.ReviewLogRow(
        id = id,
        paperId = paperId,
        questionId = questionId,
        rating = rating,
        reviewedAt = reviewedAt,
        elapsedSeconds = elapsedSeconds,
        stateBefore = stateBefore,
        stabilityAfter = stabilityAfter,
        difficultyAfter = difficultyAfter,
        intervalDaysAfter = intervalDaysAfter
    )

    private companion object {
        /** Rows per page while streaming a table into a backup. */
        const val PAGE = 2000
    }
}

/**
 * Restores a backup: wipes the database, replays the content through the
 * importer, then writes the study rows back with their original ids and
 * hashes. The wipe and the restore share one transaction, so a failed
 * restore leaves the library exactly as it was.
 */
class BackupRestorer(private val db: AppDatabase) {

    /**
     * @param applySettings receives [McqBackupFormat.SettingsFile]; the
     *   caller owns the preference store, the core restore owns the database.
     * @param applyInProgress receives the resume snapshot (null clears it).
     * @return a human-readable report of what came back.
     */
    suspend fun restore(
        backup: McqBackupFormat.Backup,
        updateAnswersOnDuplicate: Boolean = false,
        applySettings: suspend (McqBackupFormat.SettingsFile) -> Unit = {},
        applyInProgress: suspend (String?) -> Unit = {}
    ): String {
        val content = backup.content
        // A paper's standalone questions (the bank wire format keeps a
        // paper-level `questions` list beside its categories; the importer
        // folds them into the paper's Uncategorized category and restores
        // them) are restored just the same, so both the log line and the
        // report must count them.
        val questionCount = content.papers.sumOf { paper ->
            paper.categories.sumOf { it.questions.size } + paper.questions.size
        }
        Logger.i(
            "BACKUP",
            "Restoring ${content.papers.size} papers, $questionCount questions, " +
                "${backup.study.cardState.size} card states, " +
                "${backup.study.reviewLog.size} reviews"
        )
        db.withTransaction {
            wipeAllTables()
            // The importer's own nested transaction joins this one: papers,
            // categories, questions, options, correct answers, passages,
            // bookmarks and attempts (results remapped onto fresh ids) all
            // land inside the same atomic wipe-and-replace.
            Importer(db, updateAnswersOnDuplicate = updateAnswersOnDuplicate)
                .import(content)
            db.cardStateDao().upsertAll(backup.study.cardState.map { it.toEntity() })
            db.reviewLogDao().insertAll(backup.study.reviewLog.map { it.toEntity() })
            for (paper in backup.study.paperCreated) {
                db.paperDao().updateCreatedAt(paper.id, paper.createdAt)
            }
        }
        // The database restore above is atomic and has committed by now;
        // a failure in the caller-owned preference stores must not read
        // as a failed restore — it is reported as a partial one.
        val partial = mutableListOf<String>()
        try {
            applySettings(backup.settings)
        } catch (e: Exception) {
            Logger.e("BACKUP", "Settings restore failed", e)
            partial.add("settings were not restored (${e.message ?: "error"})")
        }
        try {
            applyInProgress(backup.study.inProgressTest)
        } catch (e: Exception) {
            Logger.e("BACKUP", "Resume-snapshot restore failed", e)
            partial.add("the in-progress test was not restored (${e.message ?: "error"})")
        }
        val attemptNote = if (content.attempts.isNotEmpty()) {
            ", ${content.attempts.size} attempts"
        } else {
            ""
        }
        val newerAppNote = if (backup.manifest.dbVersion > db.openHelper.readableDatabase.version) {
            " Note: the backup was taken on a newer app (database v" +
                "${backup.manifest.dbVersion}, v${db.openHelper.readableDatabase.version} " +
                "here); its study rows may use fields this version ignores."
        } else {
            ""
        }
        val partialNote = if (partial.isEmpty()) {
            ""
        } else {
            " The library itself came back; " + partial.joinToString(" and ") + "."
        }
        return "Restored ${content.papers.size} papers, $questionCount questions" +
            "$attemptNote, ${backup.study.cardState.size} schedules, " +
            "${backup.study.reviewLog.size} reviews." + newerAppNote + partialNote
    }

    /**
     * Restores straight off [input], the way [restore] restores a decoded one:
     * the zip is unzipped as it is read, the bank goes through the streaming
     * importer and the study rows are written a batch at a time, so neither
     * the file, nor the bank, nor the study table ever exists as one object.
     * That is what lets a backup larger than the heap come back at all.
     *
     * Same promise as [restore]: the wipe and the replace share one
     * transaction, so any failure — a truncated archive, an unreadable row, a
     * full disk — leaves the library exactly as it was. The stream is read
     * inside that transaction, which holds the write lock while the file is
     * read; a restore is a deliberate, modal operation, and atomicity is worth
     * the lock. Readers are not blocked (the journal is WAL).
     *
     * The report is the same one [restore] produces for the same backup: the
     * manifest's counts come from the snapshot the archive was written from,
     * so they are the payload's counts, and the study rows are counted as they
     * stream past.
     */
    suspend fun restoreStreaming(
        input: InputStream,
        updateAnswersOnDuplicate: Boolean = false,
        applySettings: suspend (McqBackupFormat.SettingsFile) -> Unit = {},
        applyInProgress: suspend (String?) -> Unit = {}
    ): String {
        var settings = McqBackupFormat.SettingsFile()
        var inProgress: String? = null
        var schedules = 0
        var reviews = 0
        val cards = ArrayList<CardStateEntity>(BATCH)
        val logs = ArrayList<ReviewLogEntity>(BATCH)

        suspend fun flushCards() {
            if (cards.isEmpty()) return
            db.cardStateDao().upsertAll(cards.toList())
            cards.clear()
        }

        suspend fun flushLogs() {
            if (logs.isEmpty()) return
            db.reviewLogDao().insertAll(logs.toList())
            logs.clear()
        }

        val started = System.currentTimeMillis()
        Logger.i("BACKUP", "Restoring from a streamed backup")
        val manifest = db.withTransaction {
            val read = McqBackupFormat.readStreaming(
                input,
                object : McqBackupFormat.StreamingSink {
                    override suspend fun content(json: InputStream) {
                        wipeAllTables()
                        // The importer's own nested transactions join this
                        // one, so the wipe and everything after it is a single
                        // atomic swap. No dispatcher hop here: a hop would
                        // take the batch writes off this transaction's thread.
                        StreamingImporter(
                            db,
                            updateAnswersOnDuplicate = updateAnswersOnDuplicate
                        ).importInTransaction({ json })
                    }

                    override suspend fun cardState(row: McqBackupFormat.CardStateRow) {
                        cards += row.toEntity()
                        schedules++
                        if (cards.size >= BATCH) flushCards()
                    }

                    override suspend fun reviewLog(row: McqBackupFormat.ReviewLogRow) {
                        logs += row.toEntity()
                        reviews++
                        if (logs.size >= BATCH) flushLogs()
                    }

                    override suspend fun paperCreated(row: McqBackupFormat.PaperCreated) {
                        db.paperDao().updateCreatedAt(row.id, row.createdAt)
                    }

                    override suspend fun inProgressTest(json: String?) {
                        inProgress = json
                    }

                    override suspend fun settings(backup: McqBackupFormat.SettingsFile) {
                        settings = backup
                    }
                }
            )
            flushCards()
            flushLogs()
            read
        }
        // The heap after a restore reads as a gate on this path: a streamed
        // restore of a backup much bigger than the heap must finish far below
        // the file's size, and a regression that holds the archive shows up in
        // this line (the same signal the importer logs).
        val elapsed = System.currentTimeMillis() - started
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1048576
        Logger.i(
            "BACKUP",
            "Restored ${manifest.papers} papers, ${manifest.questions} questions, " +
                "$schedules card states, $reviews reviews in ${elapsed}ms, heap " +
                "${usedMb}MB/${runtime.maxMemory() / 1048576}MB"
        )
        // The database restore above is atomic and has committed by now;
        // a failure in the caller-owned preference stores must not read
        // as a failed restore — it is reported as a partial one.
        val partial = mutableListOf<String>()
        try {
            applySettings(settings)
        } catch (e: Exception) {
            Logger.e("BACKUP", "Settings restore failed", e)
            partial.add("settings were not restored (${e.message ?: "error"})")
        }
        try {
            applyInProgress(inProgress)
        } catch (e: Exception) {
            Logger.e("BACKUP", "Resume-snapshot restore failed", e)
            partial.add("the in-progress test was not restored (${e.message ?: "error"})")
        }
        val attemptNote = if (manifest.attempts > 0) ", ${manifest.attempts} attempts" else ""
        val newerAppNote = if (manifest.dbVersion > db.openHelper.readableDatabase.version) {
            " Note: the backup was taken on a newer app (database v" +
                "${manifest.dbVersion}, v${db.openHelper.readableDatabase.version} " +
                "here); its study rows may use fields this version ignores."
        } else {
            ""
        }
        val partialNote = if (partial.isEmpty()) {
            ""
        } else {
            " The library itself came back; " + partial.joinToString(" and ") + "."
        }
        return "Restored ${manifest.papers} papers, ${manifest.questions} questions" +
            "$attemptNote, $schedules schedules, $reviews reviews." + newerAppNote + partialNote
    }

    /**
     * Deletes every user table, derived from the schema so a future table
     * cannot silently survive a restore. Room's and Android's own bookkeeping
     * tables are excluded — wiping those would break the framework, not the
     * library. Every foreign key in the schema is ON DELETE CASCADE, so any
     * order is safe.
     */
    private fun wipeAllTables() {
        val database = db.openHelper.writableDatabase
        database.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE 'android_%' " +
                "AND name NOT LIKE 'room_%'"
        ).use { cursor ->
            val tables = buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            for (table in tables) {
                database.execSQL("DELETE FROM \"$table\"")
            }
        }
    }

    private fun McqBackupFormat.CardStateRow.toEntity() = CardStateEntity(
        paperId = paperId,
        questionId = questionId,
        ease = ease,
        intervalDays = intervalDays,
        dueAt = dueAt,
        reps = reps,
        lapses = lapses,
        leech = leech,
        lastReviewedAt = lastReviewedAt,
        contentHash = contentHash,
        difficulty = difficulty,
        stability = stability,
        cardState = com.mcqapp.domain.CardStateKind.valueOf(cardState),
        reviewCount = reviewCount
    )

    private fun McqBackupFormat.ReviewLogRow.toEntity() = ReviewLogEntity(
        id = id,
        paperId = paperId,
        questionId = questionId,
        rating = rating,
        reviewedAt = reviewedAt,
        elapsedSeconds = elapsedSeconds,
        stateBefore = stateBefore,
        stabilityAfter = stabilityAfter,
        difficultyAfter = difficultyAfter,
        intervalDaysAfter = intervalDaysAfter
    )

    private companion object {
        /**
         * Study rows per insert while a streamed restore runs. The same size
         * as the importer's batches: big enough that a million reviews cost
         * hundreds of statements, small enough that a batch stays a couple of
         * megabytes.
         */
        const val BATCH = 2000
    }
}
