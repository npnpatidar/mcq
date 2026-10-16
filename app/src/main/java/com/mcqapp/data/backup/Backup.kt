package com.mcqapp.data.backup

import com.mcqapp.data.SafeZip
import com.mcqapp.data.ZipLimitException
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.io.Importer
import com.mcqapp.util.Logger
import androidx.room.withTransaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
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

    /** The study file's rows, written one at a time from their entities. */
    suspend fun writeStudyRows(
        w: java.io.BufferedWriter,
        cardState: List<com.mcqapp.data.local.CardStateEntity>,
        reviewLog: List<com.mcqapp.data.local.ReviewLogEntity>,
        paperCreated: List<PaperCreated>,
        inProgressTest: String?,
        toStateRow: (com.mcqapp.data.local.CardStateEntity) -> CardStateRow,
        toLogRow: (com.mcqapp.data.local.ReviewLogEntity) -> ReviewLogRow
    ) {
        w.write("{\"cardState\":[")
        cardState.forEachIndexed { i, entity ->
            if (i > 0) w.write(",")
            w.write(json.encodeToString(CardStateRow.serializer(), toStateRow(entity)))
        }
        w.write("],\"reviewLog\":[")
        reviewLog.forEachIndexed { i, entity ->
            if (i > 0) w.write(",")
            w.write(json.encodeToString(ReviewLogRow.serializer(), toLogRow(entity)))
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
                paper.categories.sumOf { it.questions.size }
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
        val questionCount = content.papers.sumOf { paper ->
            paper.categories.sumOf { it.questions.size }
        }
        Logger.i(
            "BACKUP",
            "Restoring ${content.papers.size} papers, $questionCount questions, " +
                "${backup.study.cardState.size} card states, " +
                "${backup.study.reviewLog.size} reviews"
        )
        db.withTransaction {
            // Every user table, derived from the schema so a future table
            // cannot silently survive a restore. Room's and Android's own
            // bookkeeping tables are excluded — wiping those would break
            // the framework, not the library. Every foreign key in the
            // schema is ON DELETE CASCADE, so any order is safe.
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
}
