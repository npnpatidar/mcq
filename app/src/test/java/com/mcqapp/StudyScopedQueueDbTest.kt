package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.TagNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tag scope's new cards must be served in the tag index's authored order.
 * The ViewModel used to turn that ordered `List` into a `HashSet`, so a session
 * opened on a tag served its new cards in hash order instead.
 *
 * Robolectric needs a real SQLite driver: CI (x86_64) is authoritative.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudyScopedQueueDbTest {

    @get:Rule
    val watchdog = HangWatchdogRule(seconds = 120)

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    private val paperId = "p1"
    private val now = 1_800_000_000_000L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        repository.ensurePaperAndCategory(paperId, "Paper", "c1", "Cat")
    }

    @After
    fun tearDown() = db.close()

    private fun question(id: String) = Question(
        id = id,
        categoryId = "c1",
        text = "Question $id?",
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = setOf("$id-a"),
        tags = listOf("Physics")
    )

    @Test
    fun aTagScopedNewCardQueueKeepsTheIndexAuthoredOrder() = runBlocking {
        // Saved deliberately out of alphabetical order: the queue must follow
        // the tag index's (sortOrder, rowid) order, not id or hash order.
        listOf("q-second", "q-third", "q-first").forEach { repository.saveQuestion(question(it)) }
        val folded = TagNormalizer.fold("Physics")
        val ids = repository.questionIdsForStudyTagInScope(paperId, emptyList(), folded)
        assertEquals(listOf("q-second", "q-third", "q-first"), ids)

        val queue = repository.getStudyQueueForQuestions(paperId, ids, now = now)
        assertEquals(ids, queue.map { it.questionId })
    }

    @Test
    fun aScopedQueueKeepsTheCallersOrderEvenForAnOrderedSet() = runBlocking {
        listOf("q-a", "q-b", "q-c").forEach { repository.saveQuestion(question(it)) }
        val ordered = LinkedHashSet(listOf("q-c", "q-a", "q-b"))

        assertEquals(
            listOf("q-c", "q-a", "q-b"),
            repository.getStudyQueueForQuestions(paperId, ordered, now = now)
                .map { it.questionId }
        )
    }
}
