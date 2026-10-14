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

/** Collects the live database and settings into backup parts. */
class BackupSource(private val db: AppDatabase) {

    suspend fun content(): McqFileDto =
        com.mcqapp.data.io.Exporter(db).exportAllDto().copy(scheduling = emptyMap())

    suspend fun study(inProgressTest: String?): McqBackupFormat.StudyFile =
        McqBackupFormat.StudyFile(
            cardState = db.cardStateDao().getAll().map { it.toRow() },
            reviewLog = db.reviewLogDao().getAll().map { it.toRow() },
            paperCreated = db.paperDao().getAll()
                .map { McqBackupFormat.PaperCreated(it.id, it.createdAt) },
            inProgressTest = inProgressTest
        )

    suspend fun manifest(appVersion: String?): McqBackupFormat.Manifest {
        val papers = db.paperDao().getAll()
        return McqBackupFormat.Manifest(
            dbVersion = db.openHelper.readableDatabase.version,
            appVersion = appVersion,
            exportedAt = System.currentTimeMillis(),
            papers = papers.size,
            questions = content().papers.sumOf { paper ->
                paper.categories.sumOf { it.questions.size }
            },
            attempts = db.attemptDao().getAllAttempts().size,
            reviews = db.reviewLogDao().getAll().size
        )
    }

    private fun CardStateEntity.toRow() = McqBackupFormat.CardStateRow(
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

    private fun ReviewLogEntity.toRow() = McqBackupFormat.ReviewLogRow(
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
            // Children before parents, so the foreign keys never see a
            // dangling child mid-delete.
            for (table in listOf(
                "question_results", "review_log", "card_state", "correct_answers",
                "options", "questions", "passages", "categories", "bookmarks",
                "attempts", "papers"
            )) {
                db.openHelper.writableDatabase.execSQL("DELETE FROM $table")
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
        applySettings(backup.settings)
        applyInProgress(backup.study.inProgressTest)
        val attemptNote = if (content.attempts.isNotEmpty()) {
            ", ${content.attempts.size} attempts"
        } else {
            ""
        }
        return "Restored ${content.papers.size} papers, $questionCount questions" +
            "$attemptNote, ${backup.study.cardState.size} schedules, " +
            "${backup.study.reviewLog.size} reviews."
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
