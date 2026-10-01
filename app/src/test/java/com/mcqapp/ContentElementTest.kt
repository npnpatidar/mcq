package com.mcqapp

import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.ContentElementJson
import com.mcqapp.domain.parseContentElements
import com.mcqapp.domain.textContent
import com.mcqapp.domain.toContentJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentElementTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun codecWritesTheFlatTypeContentShape() {
        val elements = listOf(
            ContentElement.TextElement("Hello"),
            ContentElement.ImageElement("/tmp/fig.png"),
            ContentElement.TableElement(listOf(listOf("a", "b"), listOf("1", "2"))),
            ContentElement.MathElement("<math><mi>x</mi></math>")
        )

        val encoded = elements.toContentJson(json)

        assertTrue(encoded.contains("{\"type\":\"text\",\"content\":\"Hello\"}"))
        assertTrue(encoded.contains("{\"type\":\"image\",\"content\":\"/tmp/fig.png\"}"))
        assertTrue(encoded.contains("{\"type\":\"table\",\"content\":[[\"a\",\"b\"],[\"1\",\"2\"]]}"))
        assertTrue(encoded.contains("{\"type\":\"math\",\"content\":\"<math><mi>x</mi></math>\"}"))
        assertEquals(elements, encoded.parseContentElements(json))
    }

    @Test
    fun codecRoundTripsEveryType() {
        val elements = listOf(
            ContentElement.TextElement(""),
            ContentElement.ImageElement("data:image/png;base64,AAA"),
            ContentElement.TableElement(emptyList()),
            ContentElement.MathElement("<math display=\"inline\" xmlns=\"http://www.w3.org/1998/Math/MathML\"></math>")
        )
        assertEquals(elements, elements.toContentJson(json).parseContentElements(json))
    }

    @Test
    fun legacyPlainTextBecomesOneTextElement() {
        val legacy = "What is 2+2?"
        assertEquals(
            listOf(ContentElement.TextElement(legacy)),
            legacy.parseContentElements(json)
        )
        assertEquals(legacy, legacy.parseContentElements(json).textContent)
    }

    @Test
    fun textContentConcatenatesOnlyTextElements() {
        val elements = listOf(
            ContentElement.TextElement("Hello "),
            ContentElement.TableElement(listOf(listOf("a"))),
            ContentElement.TextElement("world")
        )
        assertEquals("Hello world", elements.textContent)
    }

    @Test
    fun emptyElementsGiveEmptyText() {
        assertEquals("", emptyList<ContentElement>().toContentJson(json).parseContentElements(json).textContent)
    }

    @Test
    fun questionDtoSerializesElementsInStructuredFormat() {
        val dto = com.mcqapp.data.io.QuestionDto(
            id = "q1",
            text = "What is 2+2?",
            elements = listOf(ContentElement.TextElement("What is 2+2?")),
            options = listOf(
                com.mcqapp.data.io.OptionDto(
                    "a", "4",
                    listOf(ContentElement.TextElement("4"))
                ),
                com.mcqapp.data.io.OptionDto(
                    "b", "five",
                    listOf(ContentElement.TextElement("five"))
                )
            ),
            correctOptionIds = listOf("a"),
            explanation = "Arithmetic.",
            explanationElements = listOf(ContentElement.TextElement("Arithmetic."))
        )

        val encoded = json.encodeToString(com.mcqapp.data.io.QuestionDto.serializer(), dto)

        val parsed = json.parseToJsonElement(encoded).jsonObject
        assertTrue("question_elements key", "question_elements" in parsed)
        assertTrue("explanation_elements key", "explanation_elements" in parsed)
        assertTrue("options_elements key", "options_elements" in parsed)
        // options_elements is the {optionId: [elements]} dict from the example format
        val optionsElements = parsed["options_elements"]!!.jsonObject
        assertEquals(setOf("a", "b"), optionsElements.keys)
        val optionA = optionsElements["a"]!!.jsonArray
        assertEquals("text", optionA[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("4", optionA[0].jsonObject["content"]!!.jsonPrimitive.content)

        // And it deserializes back to the same DTO.
        val decoded = json.decodeFromString(com.mcqapp.data.io.QuestionDto.serializer(), encoded)
        assertEquals(dto.elements, decoded.elements)
        assertEquals(dto.options.map { it.elements }, decoded.options.map { it.elements })
        assertEquals(dto.explanationElements, decoded.explanationElements)
    }

    @Test
    fun structuredFormatRoundTripsThroughTheParser() {
        val question = com.mcqapp.data.io.QuestionDto(
            id = "q1",
            text = "Solve:",
            elements = listOf(
                ContentElement.TextElement("Solve:"),
                ContentElement.MathElement("<math><mi>x</mi></math>"),
                ContentElement.TableElement(listOf(listOf("a", "b"), listOf("1", "2")))
            ),
            options = listOf(
                com.mcqapp.data.io.OptionDto("a", "x = 1", listOf(ContentElement.TextElement("x = 1"))),
                com.mcqapp.data.io.OptionDto(
                    "b", "x = 2",
                    listOf(ContentElement.ImageElement("data:image/png;base64,AAA"))
                )
            ),
            correctOptionIds = listOf("a"),
            explanation = "Because",
            explanationElements = listOf(ContentElement.TextElement("Because"))
        )
        val paper = com.mcqapp.data.io.PaperDto(
            id = "p1",
            title = "P",
            categories = listOf(
                com.mcqapp.data.io.CategoryDto(id = "c1", title = "C", questions = listOf(question))
            )
        )
        val file = com.mcqapp.data.io.McqFileDto(
            version = 1,
            papers = listOf(paper)
        )

        // Export to JSON, then parse it back: the structured format round-trips.
        val exported = json.encodeToString(com.mcqapp.data.io.McqFileDto.serializer(), file)
        val restored = com.mcqapp.data.io.LegacyParser.parse(exported)
        val q = restored.papers[0].categories[0].questions[0]
        assertEquals(question.elements, q.elements)
        assertEquals(question.options.map { it.elements }, q.options.map { it.elements })
        assertEquals(question.explanationElements, q.explanationElements)
        assertEquals(listOf("a"), q.correctOptionIds)
    }
}
