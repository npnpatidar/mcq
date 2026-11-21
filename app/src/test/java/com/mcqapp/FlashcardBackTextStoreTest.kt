package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.ContentHash
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.repository.QuestionContentMapper
import com.mcqapp.data.repository.QuestionStore
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A question's back — a flashcard's answer, or the explanation a
 * multiple-choice card reveals after answering — has its own search column.
 * Every write path must fill it from the explanation blocks, while `searchText`
 * stays the stem alone because it is the list row's preview: an answer written
 * there would show up where the question preview belongs.
 *
 * Robolectric needs a real SQLite driver, so this runs on x86_64 CI only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlashcardBackTextStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var store: QuestionStore

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Paper"))
        db.categoryDao().insertIgnore(CategoryEntity(id = "c1", paperId = "p1", title = "Cat"))
        store = QuestionStore(db, QuestionContentMapper(db))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `saving a flashcard fills both back columns`() = runBlocking {
        store.saveQuestion(
            Question(
                id = "flash",
                categoryId = "c1",
                elements = listOf(ContentElement.TextElement("Capital of France?")),
                options = emptyList<QuestionOption>(),
                correctOptionIds = emptySet<String>(),
                explanationElements = listOf(ContentElement.TextElement("Paris"))
            )
        )

        val row = db.questionDao().getById("flash")!!
        assertEquals("Paris", row.backText)
        assertEquals("paris", row.backTextFolded)
        // The front keeps its own column, which is what the row preview shows.
        assertEquals("Capital of France?", row.searchText)
        assertEquals("capital of france?", row.searchTextFolded)
        assertEquals(
            ContentHash.of("Capital of France?", emptyList(), emptyList(), null),
            row.contentHash
        )
    }

    @Test
    fun `saving an MCQ keeps searchText on the stem and the explanation in the back`() =
        runBlocking {
            store.saveQuestion(
                Question(
                    id = "mcq",
                    categoryId = "c1",
                    elements = listOf(ContentElement.TextElement("Which <b>law</b> applies")),
                    options = listOf(
                        QuestionOption("a", "zero"),
                        QuestionOption("b", "mass times acceleration")
                    ),
                    correctOptionIds = setOf("b"),
                    explanationElements = listOf(
                        ContentElement.TextElement("Newton's <i>second</i> law"),
                        ContentElement.TableElement(listOf(listOf("m", "kg")))
                    ),
                    difficulty = Difficulty.HARD
                )
            )

            val row = db.questionDao().getById("mcq")!!
            // Flattened like the in-memory filter sees it: markup gone, table
            // cells kept.
            assertEquals("Which law applies Newton's second law m kg", row.backText)
            assertEquals("which law applies newton's second law m kg", row.backTextFolded)
            // The preview column never widens: stem only, no answer, no
            // explanation.
            assertEquals("Which law applies", row.searchText)
            assertFalse(row.searchText.contains("Newton"))
            assertFalse(row.searchText.contains("mass times acceleration"))
            // The options keep their own searchable text, unchanged.
            assertEquals(
                listOf("zero", "mass times acceleration"),
                db.optionDao().getByQuestion("mcq").map { it.searchText }
            )
        }

    @Test
    fun `an explanation edit refreshes the back without touching the stem column`() =
        runBlocking {
            val card = Question(
                id = "flash",
                categoryId = "c1",
                elements = listOf(ContentElement.TextElement("The front")),
                options = emptyList<QuestionOption>(),
                correctOptionIds = emptySet<String>(),
                explanationElements = listOf(ContentElement.TextElement("first back"))
            )
            store.saveQuestion(card)
            assertEquals("first back", db.questionDao().getById("flash")!!.backText)

            store.saveQuestion(
                card.copy(
                    explanationElements = listOf(ContentElement.TextElement("Corrected back"))
                )
            )

            val row = db.questionDao().getById("flash")!!
            assertEquals("Corrected back", row.backText)
            assertEquals("corrected back", row.backTextFolded)
            assertEquals("The front", row.searchText)
        }

    @Test
    fun `a copied question carries its back text`() = runBlocking {
        store.saveQuestion(
            Question(
                id = "flash",
                categoryId = "c1",
                elements = listOf(ContentElement.TextElement("The front")),
                options = emptyList<QuestionOption>(),
                correctOptionIds = emptySet<String>(),
                explanationElements = listOf(ContentElement.TextElement("The back"))
            )
        )

        store.copyQuestionsToCategory(listOf("flash"), "c1")

        val copyIds = db.questionDao().getIdsLike("flash-copy%")
        assertEquals(1, copyIds.size)
        val copy = db.questionDao().getById(copyIds.single())!!
        assertEquals("The back", copy.backText)
        assertEquals("the back", copy.backTextFolded)
        assertTrue(db.optionDao().getByQuestion(copy.id).isEmpty())
    }
}
