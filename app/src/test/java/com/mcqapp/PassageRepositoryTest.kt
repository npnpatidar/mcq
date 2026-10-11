package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Passage
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
 * Passage CRUD through the real repository (steps 1-2): guarded delete (D5),
 * member assignment and the card reset on reassignment (D6 — accepted
 * behaviour, pinned here so a change to it is a conscious one).
 * Robolectric needs a real SQLite driver, so this runs on x86_64 CI only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PassageRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1"))
        repository.saveQuestion(question("q2"))
        repository.saveQuestion(question("q3"))
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun question(id: String) = Question(
        id = id,
        categoryId = "c1",
        text = "Q $id",
        options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
        correctOptionIds = setOf("a")
    )

    @Test
    fun saveAssignAndReadBack() = runBlocking {
        val pid = repository.savePassage(
            Passage(id = "", categoryId = "c1", title = "P", elements = emptyList())
        )
        repository.assignQuestionsToPassage(listOf("q1", "q2"), pid)
        assertEquals(pid, db.questionDao().getById("q1")!!.passageId)
        assertEquals(pid, db.questionDao().getById("q2")!!.passageId)
        assertEquals(null, db.questionDao().getById("q3")!!.passageId)
        assertEquals(2, repository.passageMemberCount(pid))
        val blocks = repository.buildPassageBlocks(repository.getQuestionsForPaper("p1"))
        assertEquals(1, blocks.size)
        assertEquals(listOf("q1", "q2"), blocks.single().questions.map { it.id })
    }

    // ---- contiguity: members occur together (Browse, tests, exports) ----

    private fun orderOf(vararg ids: String): List<String> = runBlocking {
        repository.getQuestionsForPaper("p1").filter { it.id in ids }.map { it.id }
    }

    @Test
    fun assigningALateQuestionMovesItNextToTheGroup() = runBlocking {
        val pid = repository.savePassage(Passage(id = "", categoryId = "c1", title = "P"))
        // q1 and q3 form the group (q3 moves up next to q1); q2 sits after
        // it. Assigning q2 must place it with its group, not leave it behind.
        repository.assignQuestionsToPassage(listOf("q1", "q3"), pid)
        repository.assignQuestionsToPassage(listOf("q2"), pid)
        assertEquals(listOf("q1", "q3", "q2"), orderOf("q1", "q2", "q3"))
        // And the block built for test sessions is one whole group.
        val blocks = repository.buildPassageBlocks(repository.getQuestionsForPaper("p1"))
        assertEquals(1, blocks.size)
    }

    @Test
    fun savingANewMemberPlacesItAfterTheLastMember() = runBlocking {
        val pid = repository.savePassage(Passage(id = "", categoryId = "c1", title = "P"))
        repository.assignQuestionsToPassage(listOf("q2"), pid)
        // A brand-new question saved with a passage (the editor flow) would
        // otherwise land at the end of the category, far from its group.
        repository.saveQuestion(question("q9").copy(passageId = pid))
        assertEquals(listOf("q1", "q2", "q9", "q3"), orderOf("q1", "q2", "q3", "q9"))
        val blocks = repository.buildPassageBlocks(repository.getQuestionsForPaper("p1"))
        assertEquals(listOf("q2", "q9"), blocks[0].questions.map { it.id })
    }

    @Test
    fun renumberQuestionsRewritesTheCategoryOrder() = runBlocking {
        assertTrue(repository.renumberQuestions("c1", listOf("q3", "q1", "q2")))
        assertEquals(listOf("q3", "q1", "q2"), orderOf("q1", "q2", "q3"))
        // A mismatched id set is refused rather than half-applied.
        assertFalse(repository.renumberQuestions("c1", listOf("q1", "q2", "nope")))
        assertEquals(listOf("q3", "q1", "q2"), orderOf("q1", "q2", "q3"))
    }

    @Test
    fun passageMembersListFollowsCategoryOrder() = runBlocking {
        val pid = repository.savePassage(Passage(id = "", categoryId = "c1", title = "P"))
        // q3 joins the group first, q1 is placed after it: the members list
        // mirrors the category order the placement produced.
        repository.assignQuestionsToPassage(listOf("q3", "q1"), pid)
        assertEquals(listOf("q3", "q1"), repository.getPassageMembers(pid).map { it.id })
    }

    @Test
    fun deletingAPassageWithMembersIsRefused() = runBlocking {
        val pid = repository.savePassage(Passage(id = "", categoryId = "c1", title = "P"))
        repository.assignQuestionsToPassage(listOf("q1"), pid)
        assertFalse(repository.deletePassage(pid))
        assertNotNull(repository.getPassage(pid))
        // After unassigning, the delete goes through.
        repository.unassignAllFromPassage(pid)
        assertTrue(repository.deletePassage(pid))
        assertEquals(null, repository.getPassage(pid))
    }

    @Test
    fun reassigningAPassageResetsTheCardHash() = runBlocking {
        val pid = repository.savePassage(Passage(id = "", categoryId = "c1", title = "P"))
        repository.assignQuestionsToPassage(listOf("q1"), pid)
        // Seed a card at the assigned hash.
        repository.getStudyCounts("p1")
        val hashAtP = db.cardStateDao().get("p1", "q1")!!.contentHash
        assertEquals(hashAtP, db.questionDao().getById("q1")!!.contentHash)
        // Unassign: the stored hash no longer matches, so the next resolve resets.
        repository.unassignQuestionsFromPassage(listOf("q1"))
        assertEquals(null, db.questionDao().getById("q1")!!.passageId)
        assertTrue(db.questionDao().getById("q1")!!.contentHash != hashAtP)
        repository.getStudyCounts("p1")
        val after = db.cardStateDao().get("p1", "q1")!!
        assertEquals(db.questionDao().getById("q1")!!.contentHash, after.contentHash)
    }

    @Test
    fun categoryDeleteCascadesItsPassages() = runBlocking {
        val pid = repository.savePassage(Passage(id = "", categoryId = "c1", title = "P"))
        assertNotNull(repository.getPassage(pid))
        db.categoryDao().deleteById("c1")
        assertEquals(null, repository.getPassage(pid))
    }

    @Test
    fun movePassageReordersSiblings() = runBlocking {
        val first = repository.savePassage(Passage(id = "", categoryId = "c1", title = "First"))
        val second = repository.savePassage(Passage(id = "", categoryId = "c1", title = "Second"))
        val list1 = repository.getPassagesForPaper("p1").map { it.id }
        assertEquals(listOf(first, second), list1)
        assertTrue(repository.movePassage(second, -1))
        assertEquals(listOf(second, first), repository.getPassagesForPaper("p1").map { it.id })
    }

    @Test
    fun globalSearchFindsAMemberThroughItsPassageText() = runBlocking {
        val pid = repository.savePassage(
            Passage(
                id = "",
                categoryId = "c1",
                title = "The water cycle",
                elements = listOf(com.mcqapp.domain.ContentElement.TextElement("Rain fills rivers."))
            )
        )
        repository.assignQuestionsToPassage(listOf("q1"), pid)
        // The query exists only in the passage body; the question must surface.
        val hits = repository.searchGlobal("fills rivers")
        assertEquals(listOf("q1"), hits.map { it.question.id })
        // The title matches too.
        assertEquals(listOf("q1"), repository.searchGlobal("water cycle").map { it.question.id })
        // Standalone questions stay findable; nothing false-matches.
        assertTrue(repository.searchGlobal("Q q2").map { it.question.id }.contains("q2"))
        assertTrue(repository.searchGlobal("fills rivers").none { it.question.id == "q2" })
    }
}
