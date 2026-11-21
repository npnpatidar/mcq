package com.mcqapp

import com.mcqapp.data.io.ContentHash
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.toContentJson
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One prompt = one answer: a flashcard's identity is its prompt, never its
 * back.
 *
 * [ContentHash] stays prompt-only so stored hashes and restored schedules keep
 * matching, and [ContentHash.sameQuestionContent] treats a same-prompt /
 * different-back re-import as the same question so the duplicate branch runs.
 * The back is answer content: [ContentHash.nonHashedFieldsDiffer] sees a
 * table/formula/picture-only back edit and refreshes it in place, and
 * [com.mcqapp.data.io.CardContent] resets the schedule.
 */
class FlashcardIdentityTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun flashcard(
        id: String = "q1",
        front: String = "What is 2 + 2?",
        explanation: List<ContentElement> = listOf(ContentElement.TextElement("Four.")),
        explanationImage: String? = null
    ) = QuestionDto(
        id = id,
        text = front,
        options = emptyList(),
        correctOptionIds = emptyList(),
        explanation = explanation.joinToString("") { (it as? ContentElement.TextElement)?.text.orEmpty() },
        explanationElements = explanation,
        explanationImage = explanationImage
    )

    private fun stored(
        explanation: List<ContentElement> = listOf(ContentElement.TextElement("Four.")),
        explanationImage: String? = null
    ) = QuestionEntity(
        id = "q1",
        categoryId = "c1",
        text = "What is 2 + 2?",
        searchText = "What is 2 + 2?",
        searchTextFolded = "what is 2 + 2?",
        explanation = explanation.toContentJson(json),
        explanationImage = explanationImage,
        tagsFolded = "",
        contentHash = ""
    )

    @Test
    fun thePromptOnlyHashStillIgnoresTheBack() {
        val four = flashcard(explanation = listOf(ContentElement.TextElement("Four.")))
        val five = flashcard(explanation = listOf(ContentElement.TextElement("Five.")))
        // Deliberate: stored hashes depend on this formula, so it must not move.
        assertEquals(ContentHash.of(four), ContentHash.of(five))
        assertEquals(
            ContentHash.of(four.text, emptyList(), emptyList(), null),
            ContentHash.of(four)
        )
    }

    @Test
    fun aDifferentTextBackIsTheSameQuestionWithANewAnswer() {
        val stored = stored()
        val incoming = flashcard(explanation = listOf(ContentElement.TextElement("Five.")))
        assertTrue(ContentHash.sameQuestionContent(stored, emptyList(), incoming))
        assertTrue(ContentHash.nonHashedFieldsDiffer(stored, emptySet(), incoming))
    }

    @Test
    fun aTableOnlyBackChangeIsAnAnswerUpdate() {
        // Both backs flatten to the same empty text, so only the structured
        // comparison can see the changed cell.
        val stored = stored(
            explanation = listOf(ContentElement.TableElement(listOf(listOf("two", "plus", "two"))))
        )
        val incoming = flashcard(
            explanation = listOf(ContentElement.TableElement(listOf(listOf("two", "times", "two"))))
        )
        assertTrue(ContentHash.sameQuestionContent(stored, emptyList(), incoming))
        assertTrue(ContentHash.nonHashedFieldsDiffer(stored, emptySet(), incoming))

        val unchanged = flashcard(
            explanation = listOf(ContentElement.TableElement(listOf(listOf("two", "plus", "two"))))
        )
        assertTrue(ContentHash.sameQuestionContent(stored, emptyList(), unchanged))
        assertFalse(ContentHash.nonHashedFieldsDiffer(stored, emptySet(), unchanged))
    }

    @Test
    fun aFormulaOnlyBackChangeIsAnAnswerUpdate() {
        val stored = stored(
            explanation = listOf(ContentElement.MathElement("<math><mn>3</mn></math>"))
        )
        val incoming = flashcard(
            explanation = listOf(ContentElement.MathElement("<math><mn>4</mn></math>"))
        )
        assertTrue(ContentHash.sameQuestionContent(stored, emptyList(), incoming))
        assertTrue(ContentHash.nonHashedFieldsDiffer(stored, emptySet(), incoming))
    }

    @Test
    fun anImageOnlyBackChangeIsAnAnswerUpdate() {
        val incoming = flashcard(explanationImage = "back.png")
        assertTrue(ContentHash.sameQuestionContent(stored(), emptyList(), incoming))
        assertTrue(ContentHash.nonHashedFieldsDiffer(stored(), emptySet(), incoming))
    }

    @Test
    fun anIdenticalBackIsStillTheSameQuestion() {
        val incoming = flashcard()
        assertTrue(ContentHash.sameQuestionContent(stored(), emptyList(), incoming))
        assertFalse(ContentHash.nonHashedFieldsDiffer(stored(), emptySet(), incoming))
        // A stored [] and an empty DTO back describe the same back.
        val blank = flashcard(explanation = emptyList())
        assertTrue(
            ContentHash.sameQuestionContent(stored(explanation = emptyList()), emptyList(), blank)
        )
        assertFalse(
            ContentHash.nonHashedFieldsDiffer(stored(explanation = emptyList()), emptySet(), blank)
        )
    }

    @Test
    fun theInFileCheckTreatsOnePromptAsOneQuestion() {
        val four = flashcard(id = "a")
        val five = flashcard(id = "b", explanation = listOf(ContentElement.TextElement("Five.")))
        // The in-file duplicate check agrees with the stored-row one: a changed
        // back is not a second card.
        assertTrue(ContentHash.sameQuestionContent(four, five))
        assertTrue(ContentHash.sameQuestionContent(four, four.copy(id = "c")))
    }

    @Test
    fun idLessFlashcardsKeepTheirIdWhenTheBackChanges() {
        // A re-import of the same prompt with an edited back must resolve to
        // the same row so the update happens in place.
        val first = parseOne(idLessFlashcardBank("Paris"))
        val second = parseOne(idLessFlashcardBank("Lyon"))
        assertEquals(first.id, second.id)
        // The back image is answer content too, never identity.
        assertEquals(first.id, parseOne(idLessFlashcardBank("Paris", image = "back.png")).id)
    }

    private fun idLessFlashcardBank(back: String, image: String? = null) = """
        {
          "version": 1,
          "papers": [{
            "id": "p1", "title": "P",
            "categories": [{
              "id": "c1", "title": "C",
              "questions": [
                {
                  "text": "Capital of France?",
                  "explanation": "$back"${image?.let { ", \"explanationImage\": \"$it\"" }.orEmpty()}
                }
              ]
            }]
          }]
        }
    """.trimIndent()

    private fun parseOne(bank: String) =
        LegacyParser.parse(bank).papers.single().categories.single().questions.single()

    @Test
    fun idLessMultipleChoiceQuestionsKeepTheOriginalFormula() {
        val bank = """
            {
              "version": 1,
              "papers": [{
                "id": "p1", "title": "P",
                "categories": [{
                  "id": "c1", "title": "C",
                  "questions": [
                    {
                      "text": "2 + 2?",
                      "options": [{"id": "a", "text": "3"}, {"id": "b", "text": "4"}],
                      "explanation": "First."
                    },
                    {
                      "text": "2 + 2?",
                      "options": [{"id": "a", "text": "3"}, {"id": "b", "text": "4"}],
                      "explanation": "Second."
                    }
                  ]
                }]
              }]
            }
        """.trimIndent()
        val questions = LegacyParser.parse(bank).papers.single().categories.single().questions
        val expected = stableId("2 + 2?", listOf("a" to "3", "b" to "4"))
        assertEquals(expected, questions[0].id)
        // The explanation is still not part of an MCQ's generated id.
        assertEquals("$expected-2", questions[1].id)
    }

    /** The prompt-only formula [LegacyParser] has always used for MCQs. */
    private fun stableId(text: String, options: List<Pair<String, String>>): String {
        val raw = text + "|" + options.joinToString(",") { (id, optionText) -> "$id=$optionText" }
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return "q-" + bytes.joinToString("") { "%02x".format(it) }.take(12)
    }
}
