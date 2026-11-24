package com.mcqapp

import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.io.normalizedFlashcard
import com.mcqapp.domain.ContentElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled sample bank is the first paper a new user loads, so it is the
 * demo of the flashcard feature: a zero-option question is a card whose back
 * lives in the explanation fields, and it must show something on reveal.
 *
 * This pins the demo's coverage — prose, table, formula, picture and a
 * passage member — and the one-option conversion edge case, so a future edit
 * cannot quietly turn the sample back into a bank with an unanswerable
 * question in it. An option always carries its text label; an image-only
 * option is not a supported demo shape.
 */
class SampleFlashcardDemoTest {

    private val sampleFile: McqFileDto by lazy {
        val text = listOf(
            "src/main/assets/sample_paper.json",
            "app/src/main/assets/sample_paper.json"
        ).firstNotNullOfOrNull { File(it).takeIf { file -> file.isFile } }
            ?.readText()
            ?: error("sample_paper.json not found from ${File(".").absolutePath}")
        LegacyParser.parse(text)
    }

    private val questions: List<QuestionDto> by lazy {
        val paper = sampleFile.papers.single()
        paper.questions + paper.categories.flatMap { it.questions }
    }

    /** passage-1's prose, where a passage card's answer has to be stated. */
    private val passageOneProse: String by lazy {
        sampleFile.passages
            .single { it.id == "passage-1" }
            .elements
            .filterIsInstance<ContentElement.TextElement>()
            .joinToString(" ") { it.text }
    }

    /** The back is visible if any explanation element carries real content. */
    private fun QuestionDto.hasVisibleBack(): Boolean =
        !explanationImage.isNullOrBlank() ||
            explanationElements.any { element ->
                when (element) {
                    is ContentElement.TextElement -> element.text.isNotBlank()
                    is ContentElement.MathElement -> element.mathml.isNotBlank()
                    is ContentElement.TableElement ->
                        element.rows.any { row -> row.any { it.isNotBlank() } }
                    is ContentElement.ImageElement -> element.src.isNotBlank()
                    is ContentElement.ChemElement ->
                        element.smiles.isNotBlank() || element.formula.isNotBlank() ||
                            element.caption.isNotBlank() || element.spoken.isNotBlank() ||
                            element.diagram.isNotBlank()
                }
            }

    @Test
    fun everyZeroOptionQuestionIsAReadableCard() {
        val cards = questions.filter { it.options.isEmpty() }
        assertEquals(
            "the sample must keep exactly seven flashcards, got ${cards.size}",
            7,
            cards.size
        )
        cards.forEach { card ->
            assertTrue("${card.id} is a card with nothing on its back", card.hasVisibleBack())
            assertTrue(
                "${card.id} is a card but still keys an option",
                card.correctOptionIds.isEmpty()
            )
        }
    }

    @Test
    fun theDemoCoversEveryBackShape() {
        val cards = questions.filter { it.options.isEmpty() }
        fun back(card: QuestionDto) = card.explanationElements

        assertTrue(
            "expected a card whose back is text only",
            cards.any { card ->
                back(card).isNotEmpty() &&
                    back(card).all { it is ContentElement.TextElement } &&
                    back(card).any { it is ContentElement.TextElement && it.text.isNotBlank() }
            }
        )
        assertTrue(
            "expected a card whose back is a table",
            cards.any { card -> back(card).any { it is ContentElement.TableElement } }
        )
        assertTrue(
            "expected a card whose back is a formula",
            cards.any { card -> back(card).any { it is ContentElement.MathElement } }
        )
        assertTrue(
            "expected a card whose back is a picture",
            cards.any { card -> back(card).any { it is ContentElement.ImageElement } }
        )
        assertTrue(
            "expected a card that belongs to a reading passage",
            cards.any { !it.passageId.isNullOrBlank() }
        )

        // A bilingual card: the back carries both Devanagari and Latin text.
        val devanagari = Regex("[\\u0900-\\u097F]")
        val latin = Regex("[A-Za-z]")
        assertTrue(
            "expected a bilingual card whose back carries both scripts",
            cards.any { card ->
                val text = back(card).filterIsInstance<ContentElement.TextElement>()
                    .joinToString(" ") { it.text }
                devanagari.containsMatchIn(text) && latin.containsMatchIn(text)
            }
        )

        // A picture back: real embedded bytes plus a caption the learner can read.
        assertTrue(
            "expected a card whose back carries an embedded picture and a caption",
            cards.any { card ->
                val image = back(card).filterIsInstance<ContentElement.ImageElement>()
                    .firstOrNull { it.src.startsWith("data:image/") }
                image != null &&
                    back(card).filterIsInstance<ContentElement.TextElement>()
                        .any { it.text.isNotBlank() }
            }
        )

        // The table back opens with its header row, not a data row.
        assertTrue(
            "expected a card whose back table opens with the Quantity/Unit/Symbol header",
            cards.any { card ->
                back(card).filterIsInstance<ContentElement.TableElement>()
                    .any { it.rows.firstOrNull() == listOf("Quantity", "Unit", "Symbol") }
            }
        )

        // The picture prompt must show its picture on the *front*: a
        // "which shape is shown?" card whose image only sits on the back is
        // unanswerable as shown.
        val shapePrompt = cards.single { it.text.contains("Which shape is shown?") }
        assertTrue(
            "the shape prompt's front must carry the embedded picture",
            shapePrompt.elements.any { element ->
                element is ContentElement.ImageElement && element.src.startsWith("data:image/")
            }
        )
    }

    @Test
    fun theSingleOptionConversionEdgeIsStillDemonstrated() {
        val edge = questions.filter { it.id == "q-e5" }
        assertEquals("q-e5 is the one-option conversion edge case", 1, edge.size)
        val question = edge.single()
        assertEquals("q-e5 keeps exactly one option", 1, question.options.size)
        assertEquals("q-e5 keeps its key", listOf("a"), question.correctOptionIds)
        assertTrue(
            "q-e5's copy must say it becomes a flashcard",
            question.text.contains("becomes a flashcard", ignoreCase = true)
        )
        assertTrue(
            "q-e5's copy must say the option becomes the answer",
            question.text.contains("answer", ignoreCase = true)
        )

        // The importer's normalisation is the shape actually stored: the
        // option becomes the back and the key disappears.
        val stored = question.normalizedFlashcard()
        assertTrue("the importer must convert q-e5 into a card", stored.options.isEmpty())
        assertTrue("a converted card must have no key", stored.correctOptionIds.isEmpty())
        assertTrue(
            "the converted back must contain the option's answer, got '${stored.explanation}'",
            stored.explanation.contains("Only choice")
        )
        assertEquals("a file's own id must survive the conversion", "q-e5", stored.id)
    }

    @Test
    fun thePassageCardIsAnswerableFromItsPassage() {
        val card = questions.single { it.id == "q-fc-passage" }
        assertEquals("the passage card must stay a passage-1 member", "passage-1", card.passageId)
        val answer = card.explanationElements
            .filterIsInstance<ContentElement.TextElement>()
            .joinToString(" ") { it.text }
            .trim()
            .trimEnd('.')
        assertTrue("the passage card must have an answer", answer.isNotBlank())
        assertTrue(
            "'$answer' must appear in passage-1's prose, or the card is unanswerable",
            passageOneProse.contains(answer, ignoreCase = true)
        )
    }

    @Test
    fun theOldUnanswerableEdgeCaseIsGone() {
        val edge = questions.single { it.id == "q-e2" }
        assertTrue("q-e2 must be a flashcard now", edge.options.isEmpty())
        assertTrue("q-e2 must have a back", edge.hasVisibleBack())
        val copy = buildList {
            add(edge.text)
            add(edge.explanation)
            addAll(
                edge.explanationElements.mapNotNull { element ->
                    (element as? ContentElement.TextElement)?.text
                }
            )
        }.joinToString(" ")
        assertFalse(
            "q-e2 still describes itself as unanswerable: $copy",
            copy.contains("unanswerable", ignoreCase = true)
        )
    }
}
