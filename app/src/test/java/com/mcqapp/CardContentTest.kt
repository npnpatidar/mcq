package com.mcqapp

import com.mcqapp.data.io.CardContent
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.local.OptionEntity
import com.mcqapp.data.local.PassageEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.searchableText
import com.mcqapp.domain.textContent
import com.mcqapp.domain.toContentJson
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The reset rule's authority: this fingerprint decides whether an edit makes
 * a card "never studied". It must see every block, option, key, image and
 * passage, and must *not* see explanation or metadata. It also has to give
 * the same value for the three shapes the app compares (stored row, import
 * DTO, editor model), or a harmless save looks like an edit.
 */
class CardContentTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val stem = listOf<ContentElement>(
        ContentElement.TextElement("What is 2 + 2?"),
        ContentElement.TableElement(listOf(listOf("a", "b"), listOf("1", "2"))),
        ContentElement.MathElement("<math><mn>2</mn></math>")
    )

    private fun domainQuestion(
        elements: List<ContentElement> = stem,
        image: String? = null,
        options: List<QuestionOption> = defaultOptions(),
        correct: Set<String> = setOf("o1"),
        passageId: String? = null,
        explanation: List<ContentElement> = emptyList(),
        explanationImage: String? = null,
        marks: Double = 1.0,
        tags: List<String> = emptyList()
    ) = Question(
        id = "q1",
        categoryId = "c1",
        elements = elements,
        image = image,
        options = options,
        correctOptionIds = correct,
        explanationElements = explanation,
        explanationImage = explanationImage,
        marks = marks,
        tags = tags,
        passageId = passageId
    )

    private fun defaultOptions() = listOf(
        QuestionOption(
            id = "o1",
            elements = listOf(ContentElement.TextElement("four"), ContentElement.ImageElement("opt.png")),
            image = null
        ),
        QuestionOption(id = "o2", elements = listOf(ContentElement.TextElement("five")))
    )

    private fun dto(
        elements: List<ContentElement> = stem,
        image: String? = null,
        options: List<OptionDto> = defaultOptionDtos(),
        correct: List<String> = listOf("o1"),
        passageId: String? = null,
        explanation: String = "",
        explanationElements: List<ContentElement> = emptyList(),
        explanationImage: String? = null,
        marks: Double = 1.0,
        tags: List<String> = emptyList()
    ) = QuestionDto(
        id = "q1",
        text = elements.textContent,
        elements = elements,
        image = image,
        options = options,
        correctOptionIds = correct,
        explanation = explanation,
        explanationElements = explanationElements,
        explanationImage = explanationImage,
        marks = marks,
        tags = tags,
        passageId = passageId
    )

    private fun defaultOptionDtos() = listOf(
        OptionDto(
            id = "o1",
            text = "four",
            elements = listOf(ContentElement.TextElement("four"), ContentElement.ImageElement("opt.png"))
        ),
        OptionDto(id = "o2", text = "five", elements = listOf(ContentElement.TextElement("five")))
    )

    private fun storedQuestion(
        elements: List<ContentElement> = stem,
        image: String? = null,
        options: List<OptionEntity> = defaultOptionEntities(),
        passageId: String? = null,
        explanation: List<ContentElement> = emptyList(),
        explanationImage: String? = null,
        explanationJson: String = explanation.toContentJson(json)
    ) = QuestionEntity(
        id = "q1",
        categoryId = "c1",
        passageId = passageId,
        text = elements.toContentJson(json),
        searchText = elements.searchableText,
        searchTextFolded = elements.searchableText.lowercase(),
        image = image,
        explanation = explanationJson,
        explanationImage = explanationImage,
        tagsFolded = "",
        contentHash = ""
    )

    /** A zero-option question: its explanation is the back. */
    private fun flashcard(
        elements: List<ContentElement> = stem,
        explanation: List<ContentElement> = listOf(ContentElement.TextElement("Because four.")),
        explanationImage: String? = null
    ) = domainQuestion(
        elements = elements,
        options = emptyList(),
        correct = emptySet(),
        explanation = explanation,
        explanationImage = explanationImage
    )

    private fun flashcardDto(
        elements: List<ContentElement> = stem,
        explanation: List<ContentElement> = listOf(ContentElement.TextElement("Because four.")),
        explanationImage: String? = null
    ) = dto(
        elements = elements,
        options = emptyList(),
        correct = emptyList(),
        explanationElements = explanation,
        explanationImage = explanationImage
    )

    private fun defaultOptionEntities() = listOf(
        OptionEntity(
            questionId = "q1",
            id = "o1",
            text = listOf(
                ContentElement.TextElement("four"),
                ContentElement.ImageElement("opt.png")
            ).toContentJson(json),
            searchText = "four",
            searchTextFolded = "four",
            sortOrder = 0
        ),
        OptionEntity(
            questionId = "q1",
            id = "o2",
            text = listOf(ContentElement.TextElement("five")).toContentJson(json),
            searchText = "five",
            searchTextFolded = "five",
            sortOrder = 1
        )
    )

    @Test
    fun theThreeShapesFingerprintIdentically() {
        assertEquals(
            CardContent.of(domainQuestion()),
            CardContent.of(dto())
        )
        assertEquals(
            CardContent.of(domainQuestion()),
            CardContent.of(storedQuestion(), defaultOptionEntities(), setOf("o1"))
        )
        // A scalar option picture and the same picture folded into the
        // elements are the two shapes an export/re-import cycle produces.
        val folded = listOf(
            OptionDto(
                id = "o1",
                text = "four",
                elements = listOf(ContentElement.TextElement("four"), ContentElement.ImageElement("opt.png"))
            ),
            OptionDto(id = "o2", text = "five", elements = listOf(ContentElement.TextElement("five")))
        )
        val scalar = listOf(
            OptionDto(
                id = "o1",
                text = "four",
                elements = listOf(ContentElement.TextElement("four")),
                image = "opt.png"
            ),
            OptionDto(id = "o2", text = "five", elements = listOf(ContentElement.TextElement("five")))
        )
        assertEquals(
            CardContent.of(dto(options = folded)),
            CardContent.of(dto(options = scalar))
        )
    }

    @Test
    fun anyQuestionBlockChangeResets() {
        val base = CardContent.of(domainQuestion())
        assertNotEquals(base, CardContent.of(domainQuestion(elements = stem.dropLast(1))))
        assertNotEquals(
            base,
            CardContent.of(
                domainQuestion(
                    elements = stem.map {
                        if (it is ContentElement.TableElement) {
                            ContentElement.TableElement(listOf(listOf("a", "c")))
                        } else {
                            it
                        }
                    }
                )
            )
        )
        assertNotEquals(
            base,
            CardContent.of(
                domainQuestion(
                    elements = stem.map {
                        if (it is ContentElement.MathElement) ContentElement.MathElement("<mn>3</mn>") else it
                    }
                )
            )
        )
        assertNotEquals(base, CardContent.of(domainQuestion(image = "question.png")))
    }

    @Test
    fun anyOptionChangeResets() {
        val base = CardContent.of(domainQuestion())
        assertNotEquals(
            base,
            CardContent.of(
                domainQuestion(
                    options = listOf(
                        QuestionOption("o1", listOf(ContentElement.TextElement("FOUR"))),
                        QuestionOption("o2", listOf(ContentElement.TextElement("five")))
                    )
                )
            )
        )
        // Option order is content too.
        assertNotEquals(
            base,
            CardContent.of(domainQuestion(options = defaultOptions().reversed()))
        )
        // A picture, whether inline or scalar, is content.
        assertNotEquals(
            base,
            CardContent.of(
                domainQuestion(
                    options = listOf(
                        QuestionOption("o1", listOf(ContentElement.TextElement("four"))),
                        QuestionOption("o2", listOf(ContentElement.TextElement("five")))
                    )
                )
            )
        )
        assertNotEquals(
            base,
            CardContent.of(
                domainQuestion(
                    options = listOf(
                        QuestionOption("o1", listOf(ContentElement.TextElement("four")), image = "other.png"),
                        QuestionOption("o2", listOf(ContentElement.TextElement("five")))
                    )
                )
            )
        )
    }

    @Test
    fun theAnswerKeyAndPassageAssignmentReset() {
        val base = CardContent.of(domainQuestion())
        assertNotEquals(base, CardContent.of(domainQuestion(correct = setOf("o2"))))
        assertNotEquals(base, CardContent.of(domainQuestion(correct = setOf("o1", "o2"))))
        assertNotEquals(base, CardContent.of(domainQuestion(passageId = "p1")))
    }

    @Test
    fun explanationAndMetadataKeepTheSchedule() {
        val base = CardContent.of(domainQuestion())
        assertEquals(
            base,
            CardContent.of(
                domainQuestion(
                    explanation = listOf(ContentElement.TextElement("Because four.")),
                    marks = 5.0,
                    tags = listOf("arithmetic")
                )
            )
        )
    }

    @Test
    fun aFlashcardBackIsContent() {
        val base = CardContent.of(flashcard())
        assertNotEquals(
            "a changed textual back is a new answer",
            base,
            CardContent.of(flashcard(explanation = listOf(ContentElement.TextElement("Because five."))))
        )
        assertNotEquals(
            "a table-only back change must be visible",
            base,
            CardContent.of(
                flashcard(explanation = listOf(ContentElement.TableElement(listOf(listOf("a", "b")))))
            )
        )
        assertNotEquals(
            "a formula-only back change must be visible",
            base,
            CardContent.of(
                flashcard(explanation = listOf(ContentElement.MathElement("<math><mn>4</mn></math>")))
            )
        )
        assertNotEquals(
            "a back picture is content",
            base,
            CardContent.of(flashcard(explanationImage = "back.png"))
        )
        assertNotEquals(
            "the stem is still the front",
            base,
            CardContent.of(flashcard(elements = stem.dropLast(1)))
        )
    }

    @Test
    fun aBlankFlashcardBackHasOneShape() {
        assertEquals(
            "[] and one blank text block describe the same back",
            CardContent.of(flashcard(explanation = emptyList())),
            CardContent.of(flashcard(explanation = listOf(ContentElement.TextElement(""))))
        )
        assertEquals(
            "a stored [] and an empty DTO back are the same",
            CardContent.of(
                storedQuestion(options = emptyList(), explanation = emptyList()),
                emptyList(),
                emptySet()
            ),
            CardContent.of(flashcardDto(explanation = emptyList()))
        )
        assertEquals(
            "a legacy blank explanation column and an empty DTO back are the same",
            CardContent.of(
                storedQuestion(options = emptyList(), explanationJson = ""),
                emptyList(),
                emptySet()
            ),
            CardContent.of(flashcardDto(explanation = emptyList()))
        )
    }

    @Test
    fun theThreeShapesFingerprintIdenticallyForAFlashcard() {
        val back = listOf(ContentElement.TextElement("Because four."))
        val stored = storedQuestion(
            options = emptyList(),
            explanation = back,
            explanationImage = "back.png"
        )
        assertEquals(
            CardContent.of(flashcard(explanation = back, explanationImage = "back.png")),
            CardContent.of(
                flashcardDto(explanation = back, explanationImage = "back.png")
            )
        )
        assertEquals(
            CardContent.of(flashcard(explanation = back, explanationImage = "back.png")),
            CardContent.of(stored, emptyList(), emptySet())
        )
    }

    @Test
    fun passageTitleBodyAndImageAreContent() {
        val stored = PassageEntity(
            id = "p1",
            categoryId = "c1",
            title = "Reading",
            text = listOf(ContentElement.TextElement("Body")).toContentJson(json),
            searchText = "",
            searchTextFolded = "",
            image = "map.png"
        )
        val same = PassageDto(
            id = "p1",
            title = "Reading",
            elements = listOf(ContentElement.TextElement("Body")),
            image = "map.png"
        )
        val newTitle = same.copy(title = "Other reading")
        val newBody = same.copy(elements = listOf(ContentElement.TextElement("Changed")))
        val newImage = same.copy(image = "other.png")
        assertEquals(CardContent.of(stored), CardContent.of(same))
        assertNotEquals(CardContent.of(stored), CardContent.of(newTitle))
        assertNotEquals(CardContent.of(stored), CardContent.of(newBody))
        assertNotEquals(CardContent.of(stored), CardContent.of(newImage))
        // The editor stores an empty body as [], the import writer as one
        // blank element; both describe the same (empty) content.
        assertEquals(
            CardContent.of(stored.copy(text = "[]")),
            CardContent.of(same.copy(elements = emptyList()))
        )
    }
}
