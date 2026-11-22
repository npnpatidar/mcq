package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.Exporter
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.cardScheduleKey
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.repository.SuspendStore
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plain-JSON backup round trip for a hidden card that has never been graded.
 * Such a card has only the two v30 flags on its `card_state` row, so the old
 * "studied rows only" export filter dropped the hide and the import restored a
 * studyable card. CI-only: Robolectric opening SQLite cannot run on ARM64.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SuspendExportRoundTripTest {

    private lateinit var context: Context
    private lateinit var source: AppDatabase

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext<Context>()
        source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = McqRepository(source, context)
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(
            Question(
                id = "q1",
                categoryId = "c1",
                text = "2 + 2?",
                options = listOf(QuestionOption("a", "3"), QuestionOption("b", "4")),
                correctOptionIds = setOf("b")
            )
        )
    }

    @After
    fun tearDown() {
        source.close()
    }

    private fun exportAllJson(db: AppDatabase): String {
        val text = java.io.StringWriter()
        val out = java.io.BufferedWriter(text)
        runBlocking { Exporter(db).exportAllTo(out) }
        out.flush()
        return text.toString()
    }

    private suspend fun importIntoTarget(json: String): AppDatabase {
        val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        Importer(target, preserveIds = true).import(LegacyParser.parse(json))
        return target
    }

    @Test
    fun aSuspendedNewCardKeepsItsFlagAndStaysOutOfStudy() = runBlocking {
        SuspendStore(source).suspend("p1", "q1")
        val file = LegacyParser.parse(exportAllJson(source))
        val schedule = file.scheduling[cardScheduleKey("p1", "q1")]
        assertNotNull("the flag-only row must reach the scheduling map", schedule)
        assertTrue("suspended flag exported", schedule!!.suspended)
        assertEquals(0, schedule.reps)
        assertEquals(0L, schedule.dueAt)

        val target = importIntoTarget(exportAllJson(source))
        try {
            val restored = target.cardStateDao().get("p1", "q1")!!
            assertTrue("suspend survives the export/import", restored.suspended)
            assertFalse("buried flag was not set", restored.buriedUntil != null)
            assertTrue(
                "the hidden card must not be served by Study",
                McqRepository(target, context).getStudyQueue("p1").none { it.questionId == "q1" }
            )
        } finally {
            target.close()
        }
    }

    @Test
    fun aBuriedNewCardKeepsItsDeadlineAndStaysOutOfStudy() = runBlocking {
        val until = System.currentTimeMillis() + 86_400_000L
        SuspendStore(source).bury("p1", "q1", until)
        val file = LegacyParser.parse(exportAllJson(source))
        val schedule = file.scheduling[cardScheduleKey("p1", "q1")]
        assertNotNull("the flag-only row must reach the scheduling map", schedule)
        assertEquals("buriedUntil exported", until, schedule!!.buriedUntil)
        assertFalse("not suspended", schedule.suspended)

        val target = importIntoTarget(exportAllJson(source))
        try {
            val restored = target.cardStateDao().get("p1", "q1")!!
            assertEquals("buriedUntil survives the export/import", until, restored.buriedUntil)
            assertFalse("not suspended", restored.suspended)
            assertTrue(
                "a buried card must not be served before its deadline",
                McqRepository(target, context).getStudyQueue("p1", until - 1).none { it.questionId == "q1" }
            )
        } finally {
            target.close()
        }
    }

    @Test
    fun aFlagOnlyRowInAnotherPaperSuppressesTheBareAlias() = runBlocking {
        SuspendStore(source).suspend("p1", "q1")
        // A question reused in two papers is scheduled independently; the
        // second paper's row is flag-only too, so it counts for the alias rule.
        source.cardStateDao().upsert(
            CardStateEntity(
                paperId = "p2", questionId = "q1", ease = 2.5, intervalDays = 0,
                dueAt = 0L, reps = 0, lapses = 0, leech = false, lastReviewedAt = 0L,
                contentHash = "hash", suspended = true
            )
        )
        val file = LegacyParser.parse(exportAllJson(source))
        assertTrue("both flag-only rows exported", file.scheduling.size == 2)
        assertFalse(
            "a bare alias would apply one paper's hide to the other",
            "q1" in file.scheduling
        )
        assertTrue(cardScheduleKey("p1", "q1") in file.scheduling)
        assertTrue(cardScheduleKey("p2", "q1") in file.scheduling)
    }
}
