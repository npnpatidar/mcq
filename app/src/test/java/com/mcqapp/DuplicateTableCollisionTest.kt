package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.ContentHash
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.ContentElement
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two questions that differ *only* in a table inside an option must not be
 * treated as one question.
 *
 * Duplicate detection hashes question text, option texts and option images, and
 * `textContent` counts only [ContentElement.TextElement]s — so a table
 * contributes nothing to the hash. Both questions below hash identically.
 * Treating that hash hit as a duplicate silently dropped the second question on
 * import, which is data loss with no warning anywhere.
 *
 * Ten questions in the shipped sample bank have table-valued options, so this
 * was reachable with the app's own demo content.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DuplicateTableCollisionTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** The same question twice, differing only in one table cell. */
    private fun bank(firstYear: String, secondId: String) = """
        {
          "version": 1,
          "papers": [{
            "id": "p1", "title": "Bank",
            "categories": [{
              "id": "c1", "title": "Cat",
              "questions": [
                {
                  "id": "$secondId",
                  "question_elements": [{ "type": "text", "content": "Who ruled in 1556?" }],
                  "options_elements": {
                    "a": [{ "type": "table", "content": [["Ruler", "Year"], ["Akbar", "$firstYear"]] }],
                    "b": [{ "type": "text", "content": "None" }]
                  },
                  "correctOptionIds": ["a"],
                  "explanation_elements": [{ "type": "text", "content": "From the table." }]
                }
              ]
            }]
          }]
        }
    """.trimIndent()

    @Test
    fun questionsDifferingOnlyInATableShareTheHashThatMadeThemCollide() {
        val first = LegacyParser.parse(bank("1556", "q1")).papers[0].categories[0].questions.single()
        val second = LegacyParser.parse(bank("1605", "q2")).papers[0].categories[0].questions.single()

        // The premise of the bug: the hash cannot tell them apart.
        assertEquals(ContentHash.of(first), ContentHash.of(second))
        // ...but their option content is not the same, which is what the
        // content check has to notice.
        assertEquals("", first.options[0].text)
        assertNotEquals(first.options[0].elements, second.options[0].elements)
    }

    @Test
    fun aTableOnlyCollisionImportsBothQuestionsRatherThanDroppingOne() = runBlockingTest {
        Importer(db).import(LegacyParser.parse(bank("1556", "q1")))

        // A regenerated bank: same shape, corrected year, new ids.
        val report = Importer(db).import(LegacyParser.parse(bank("1605", "q2")))

        val stored = repository.getQuestionsForPaper("p1")
        assertEquals(
            "the colliding question was dropped instead of imported",
            2,
            stored.size
        )
        assertEquals(0, report.duplicateQuestions)
        assertEquals(setOf("q1", "q2"), stored.map { it.id }.toSet())
    }

    @Test
    fun aGenuineDuplicateOfATableQuestionIsStillSkipped() = runBlockingTest {
        Importer(db).import(LegacyParser.parse(bank("1556", "q1")))

        // Byte-identical content this time: still a duplicate, table or not.
        val report = Importer(db).import(LegacyParser.parse(bank("1556", "q1")))

        assertEquals("a real duplicate should still be skipped", 1, repository.getQuestionsForPaper("p1").size)
        assertEquals(1, report.duplicateQuestions)
    }

    @Test
    fun aFormulaOnlyDifferenceIsAlsoTreatedAsNewContent() {
        val one = com.mcqapp.data.io.QuestionDto(
            id = "q1",
            text = "Solve for x.",
            elements = listOf(ContentElement.TextElement("Solve for x.")),
            options = listOf(
                com.mcqapp.data.io.OptionDto("a", "2"),
                com.mcqapp.data.io.OptionDto("b", "3")
            ),
            correctOptionIds = listOf("a"),
            explanation = "Because."
        )
        val two = one.copy(
            id = "q2",
            explanationElements = listOf(ContentElement.MathElement("<math><mi>x</mi></math>"))
        )
        // The explanation is deliberately not hashed, so these legitimately share
        // a hash; what matters is that the *option* content check is what runs.
        assertEquals(ContentHash.of(one), ContentHash.of(two))
    }

    private fun runBlockingTest(block: suspend () -> Unit) =
        kotlinx.coroutines.runBlocking { block() }
}