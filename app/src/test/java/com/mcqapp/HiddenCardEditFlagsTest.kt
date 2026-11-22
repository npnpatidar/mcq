package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.repository.SuspendStore
import com.mcqapp.domain.CardStateKind
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
 * Both content-reset paths used to delete the whole `card_state` row, so an
 * edited hidden card came back in Study and vanished from the unsuspend list.
 * The schedule must still reset; only the two hide flags survive.
 * CI-only: Robolectric opening SQLite cannot run on ARM64.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HiddenCardEditFlagsTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun question(id: String, categoryId: String, text: String) = Question(
        id = id,
        categoryId = categoryId,
        text = text,
        options = listOf(QuestionOption("a", "3"), QuestionOption("b", "4")),
        correctOptionIds = setOf("b")
    )

    @Test
    fun editingContentKeepsASuspendThroughTheEditorSavePath() = runBlocking {
        val repository = McqRepository(db, context)
        repository.ensurePaperAndCategory("p-editor", "Paper", "c-editor", "Cat")
        repository.saveQuestion(question("q-editor", "c-editor", "2 + 2?"))
        SuspendStore(db).suspend("p-editor", "q-editor")
        assertTrue(db.cardStateDao().get("p-editor", "q-editor")!!.suspended)

        repository.saveQuestion(question("q-editor", "c-editor", "3 + 3?"))

        val after = db.cardStateDao().get("p-editor", "q-editor")
        assertNotNull("the hide flag needs a home after the edit", after)
        assertTrue("suspend survives an editor save", after!!.suspended)
        assertEquals(CardStateKind.NEW, after.cardState)
        assertEquals(0, after.reps)
        assertEquals(0L, after.dueAt)
        assertTrue(
            "still hidden from Study",
            repository.getStudyQueue("p-editor").none { it.questionId == "q-editor" }
        )
    }

    @Test
    fun aStaleHashResetKeepsABury() = runBlocking {
        val now = System.currentTimeMillis()
        val until = now + 86_400_000L
        val repository = McqRepository(db, context)
        repository.ensurePaperAndCategory("p-stale", "Paper", "c-stale", "Cat")
        repository.saveQuestion(question("q-stale", "c-stale", "2 + 2?"))
        SuspendStore(db).bury("p-stale", "q-stale", until)
        // Make the stored hash stale the way an out-of-band content write does.
        val before = db.cardStateDao().get("p-stale", "q-stale")!!
        db.cardStateDao().upsert(before.copy(contentHash = "stale"))

        // getStudyQueue runs the stale-hash repair before serving the queue.
        repository.getStudyQueue("p-stale", now)

        val after = db.cardStateDao().get("p-stale", "q-stale")
        assertNotNull("the bury flag needs a home after the reset", after)
        assertNotNull("the hide flag needs a home after the reset", after)
        assertEquals(
            "the fresh question hash replaces the stale one",
            db.questionDao().getHashById("q-stale"),
            after!!.contentHash
        )
        assertFalse("not suspended", after.suspended)
        assertEquals("buriedUntil survives the reset", until, after.buriedUntil)
        assertEquals(0, after.reps)
        assertTrue(
            "still hidden from Study before the deadline",
            repository.getStudyQueue("p-stale", until - 1).none { it.questionId == "q-stale" }
        )
    }
}
