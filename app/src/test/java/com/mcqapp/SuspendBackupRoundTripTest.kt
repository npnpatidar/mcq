package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.backup.BackupRestorer
import com.mcqapp.data.backup.BackupSource
import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.domain.CardStateKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A deep copy of the library must carry the suspend flags: export from one
 * database, wipe/restore into another, and both the whole-file and streaming
 * study rows must decode them. A backup written before v30 omits the fields
 * and decodes them as ordinary cards (checked in [SuspendDtoDefaultsTest]).
 *
 * Robolectric + real SQLite: CI (x86_64) is authoritative.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SuspendBackupRoundTripTest {

    @get:Rule
    val watchdog = HangWatchdogRule(seconds = 120)

    private lateinit var source: AppDatabase
    private lateinit var target: AppDatabase

    private fun newDb(): AppDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    private fun content() = McqFileDto(
        version = 1,
        papers = listOf(
            PaperDto(
                id = "paper-1",
                title = "Paper",
                categories = listOf(
                    CategoryDto(
                        id = "cat-1",
                        title = "Cat",
                        questions = listOf(
                            QuestionDto(
                                id = "q-suspended",
                                text = "Suspended one",
                                options = listOf(OptionDto("a", "A"), OptionDto("b", "B")),
                                correctOptionIds = listOf("a")
                            ),
                            QuestionDto(
                                id = "q-buried",
                                text = "Buried one",
                                options = listOf(OptionDto("a", "A"), OptionDto("b", "B")),
                                correctOptionIds = listOf("b")
                            )
                        )
                    )
                )
            )
        )
    )

    private suspend fun seed(db: AppDatabase) {
        Importer(db).import(content())
        val suspendedHash = db.questionDao().getById("q-suspended")!!.contentHash
        val buriedHash = db.questionDao().getById("q-buried")!!.contentHash
        db.cardStateDao().upsertAll(
            listOf(
                CardStateEntity(
                    paperId = "paper-1",
                    questionId = "q-suspended",
                    ease = 2.5,
                    intervalDays = 4,
                    dueAt = 1_700_000_100_000,
                    reps = 5,
                    lapses = 1,
                    leech = false,
                    lastReviewedAt = 1_700_000_000_000,
                    contentHash = suspendedHash,
                    difficulty = 6.0,
                    stability = 12.0,
                    cardState = CardStateKind.REVIEW,
                    reviewCount = 5,
                    suspended = true
                ),
                CardStateEntity(
                    paperId = "paper-1",
                    questionId = "q-buried",
                    ease = 2.5,
                    intervalDays = 0,
                    dueAt = 0L,
                    reps = 0,
                    lapses = 0,
                    leech = false,
                    lastReviewedAt = 0L,
                    contentHash = buriedHash,
                    cardState = CardStateKind.NEW,
                    buriedUntil = 1_800_000_000_000L
                )
            )
        )
    }

    @Test
    fun aWholeFileBackupRestoresSuspendedAndBuriedFlags() = runBlocking {
        source = newDb()
        target = newDb()
        seed(source)

        val backup = BackupSource(source).collect("test-1.0", inProgressTest = null)
        val bytes = McqBackupFormat.write(
            manifest = backup.manifest,
            content = backup.content,
            study = backup.study,
            settings = backup.settings
        )
        // Read the bytes back rather than reusing the in-memory object: this is
        // the deep copy that crosses a file boundary.
        val decoded = McqBackupFormat.read(bytes)

        // The study file has both write paths; compare the row objects both
        // ways so a field added to only one of them is caught.
        assertTrue(decoded.study.cardState.any { it.questionId == "q-suspended" && it.suspended })
        assertEquals(
            1_800_000_000_000L,
            decoded.study.cardState.first { it.questionId == "q-buried" }.buriedUntil
        )

        BackupRestorer(target).restore(decoded)

        val suspended = target.cardStateDao().get("paper-1", "q-suspended")
        val buried = target.cardStateDao().get("paper-1", "q-buried")
        assertNotNull(suspended)
        assertNotNull(buried)
        assertTrue("suspended survives the deep copy", suspended!!.suspended)
        assertNull(suspended.buriedUntil)
        assertFalse(buried!!.suspended)
        assertEquals(1_800_000_000_000L, buried.buriedUntil)
        assertEquals(5, suspended.reps)
        assertEquals(
            "the rest of the schedule survives too",
            source.cardStateDao().get("paper-1", "q-suspended")!!.contentHash,
            suspended.contentHash
        )
    }
}
