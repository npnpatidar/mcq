package com.mcqapp

import com.mcqapp.data.io.LegacyParser
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.ai.AiSchemaGenerator
import com.mcqapp.domain.ai.ContentBlock
import com.mcqapp.domain.ai.PassageMode
import com.mcqapp.domain.ai.QuestionKind
import com.mcqapp.domain.ai.SchemaOptions
import com.mcqapp.domain.ai.SchemaWrapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every combination the generator screen can produce is parsed by the real
 * LegacyParser. A schema that is internally consistent but describes keys the
 * importer ignores is useless, so the example is the proof.
 */
class AiSchemaGeneratorTest {

    private val allOptions: List<SchemaOptions> = buildList {
        for (kind in QuestionKind.entries) {
            for (table in listOf(false, true)) {
                for (math in listOf(false, true)) {
                    for (image in listOf(false, true)) {
                        for (passage in PassageMode.entries) {
                            for (wrapper in SchemaWrapper.entries) {
                                add(
                                    SchemaOptions(
                                        kind = kind, table = table, math = math,
                                        image = image, passage = passage, wrapper = wrapper
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun keys(element: JsonElement): Set<String> = when (element) {
        is JsonObject -> element.keys + element.values.flatMap(::keys)
        is JsonArray -> element.flatMap(::keys).toSet()
        else -> emptySet()
    }

    private fun assertClosedObjects(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                if ((element["type"] as? JsonPrimitive)?.content == "object") {
                    assertEquals(
                        "every object must close additionalProperties",
                        JsonPrimitive(false),
                        element["additionalProperties"]
                    )
                }
                element.values.forEach(::assertClosedObjects)
            }
            is JsonArray -> element.forEach(::assertClosedObjects)
            else -> Unit
        }
    }

    private fun nameOf(block: ContentBlock): String = block.name.lowercase(java.util.Locale.ROOT)

    private fun typeOf(element: ContentElement): String = when (element) {
        is ContentElement.TextElement -> "text"
        is ContentElement.TableElement -> "table"
        is ContentElement.MathElement -> "math"
        is ContentElement.ImageElement -> "image"
    }

    @Test
    fun everyUiCombinationParsesIntoTheExpectedShape() {
        assertEquals("every UI combination is covered", 144, allOptions.size)
        allOptions.forEach { options ->
            val label = options.toString()
            val generated = AiSchemaGenerator.generate(options)
            val file = LegacyParser.parse(generated.example)

            assertEquals("$label: warnings $file.warnings", emptyList<String>(), file.warnings)
            assertEquals("$label: exactly one paper", 1, file.papers.size)
            val paper = file.papers.single()
            assertEquals("$label: exactly one category", 1, paper.categories.size)
            val questions = paper.categories.single().questions

            val expectedCount = when (options.kind) {
                QuestionKind.FLASHCARDS, QuestionKind.MCQ -> 1
                QuestionKind.MIXED -> 3
            }
            assertEquals("$label: question count", expectedCount, questions.size)

            val expectedBlocks = options.blocks.map(::nameOf)
            questions.forEachIndexed { index, q ->
                assertEquals("$label q$index: element blocks", expectedBlocks, q.elements.map(::typeOf))
                when (options.kind) {
                    QuestionKind.FLASHCARDS -> {
                        assertTrue("$label q$index: card has no options", q.options.isEmpty())
                        assertEquals(
                            "$label q$index: card back blocks",
                            expectedBlocks,
                            q.explanationElements.map(::typeOf)
                        )
                        assertTrue("$label q$index: card has a text back", q.explanationElements.isNotEmpty())
                    }
                    QuestionKind.MCQ -> {
                        assertEquals("$label q$index: four options", listOf("a", "b", "c", "d"), q.options.map { it.id })
                        assertEquals("$label q$index: answer key", listOf("b"), q.correctOptionIds)
                        assertTrue("$label q$index: MCQ needs no back", q.explanationElements.isEmpty())
                    }
                    QuestionKind.MIXED -> when (index) {
                        0 -> {
                            assertEquals("$label: keyed MCQ", listOf("b"), q.correctOptionIds)
                            assertEquals("$label: keyed MCQ options", 4, q.options.size)
                        }
                        1 -> {
                            assertTrue("$label: keyless MCQ keeps its options", q.options.isNotEmpty())
                            assertTrue("$label: keyless MCQ has no key", q.correctOptionIds.isEmpty())
                        }
                        else -> {
                            assertTrue("$label: Mixed card has no options", q.options.isEmpty())
                            assertTrue("$label: Mixed card has a back", q.explanationElements.isNotEmpty())
                        }
                    }
                }
                if (options.image) {
                    val imageCount = q.elements.count { it is ContentElement.ImageElement }
                    assertEquals("$label q$index: one image block", 1, imageCount)
                } else {
                    assertNull("$label q$index: no image field", q.image)
                    assertFalse(
                        "$label q$index: no image block",
                        q.elements.any { it is ContentElement.ImageElement }
                    )
                }

                if (options.passage == PassageMode.NONE) {
                    assertNull("$label q$index: no passage link", q.passageId)
                } else {
                    assertEquals("$label q$index: passage link", "p1", q.passageId)
                }
            }

            if (options.passage == PassageMode.NONE) {
                assertTrue("$label: no passages declared", file.passages.isEmpty())
            } else {
                assertEquals("$label: one passage", 1, file.passages.size)
                val passage = file.passages.single()
                assertEquals("$label: passage id", "p1", passage.id)
                assertEquals(
                    "$label: passage blocks",
                    if (options.passage == PassageMode.TEXT_ONLY) listOf("text") else expectedBlocks,
                    passage.elements.map(::typeOf)
                )
            }

            if (options.wrapper == SchemaWrapper.PAPERLESS) {
                assertTrue(
                    "$label: paperless wraps in an ephemeral paper",
                    paper.id.startsWith(LegacyParser.EPHEMERAL_PAPER_ID_PREFIX)
                )
            } else {
                assertEquals("$label: full bank keeps its paper id", "paper-1", paper.id)
                assertEquals("$label: full bank keeps its category", "cat-1", paper.categories.single().id)
            }

            val schemaElement = Json.parseToJsonElement(generated.schema)
            assertTrue("$label: draft 2020-12", generated.schema.contains("2020-12/schema"))
            assertClosedObjects(schemaElement)
            val declared = keys(schemaElement)
            if (options.image) {
                assertTrue("$label: image field declared", "image" in declared)
                if (options.kind != QuestionKind.MCQ) {
                    assertTrue("$label: back image declared", "explanationImage" in declared)
                }
                assertTrue("$label: image block declared", generated.schema.contains("\"const\":\"image\""))
            } else {
                assertFalse("$label: no image field in schema", "image" in declared)
                assertFalse("$label: no back-image field in schema", "explanationImage" in declared)
                assertFalse(
                    "$label: no image block in schema",
                    generated.schema.contains("\"const\":\"image\"")
                )
                assertTrue("$label: prompt says no image fields", generated.prompt.contains("no image fields"))
            }
            if (options.wrapper == SchemaWrapper.PAPERLESS) {
                assertFalse("$label: no papers key", "papers" in declared)
                assertFalse("$label: no categories key", "categories" in declared)
                assertTrue("$label: prompt says no papers/categories", generated.prompt.contains("no papers/categories"))
            } else {
                assertTrue("$label: papers key declared", "papers" in declared)
                assertTrue("$label: categories key declared", "categories" in declared)
            }
            assertTrue(
                "$label: prompt prefix",
                generated.prompt.startsWith(
                    "Output only JSON matching the schema. No markdown or commentary."
                )
            )
            assertTrue("$label: example parses as JSON", Json.parseToJsonElement(generated.example) is JsonObject)
            assertEquals(
                "$label: char count covers schema + prompt",
                generated.schema.length + generated.prompt.length,
                generated.characterCount
            )
        }
    }

    /**
     * Owner case 1: text-only passage, no image/table/math. The passage must be
     * the parser's `{"id","text"}` fallback, not a rich element array.
     */
    @Test
    fun textOnlyPassageWithoutImageTableOrMath() {
        val options = SchemaOptions(
            kind = QuestionKind.MIXED,
            table = false,
            math = false,
            image = false,
            passage = PassageMode.TEXT_ONLY,
            wrapper = SchemaWrapper.FULL_BANK
        )
        val generated = AiSchemaGenerator.generate(options)
        val root = Json.parseToJsonElement(generated.example).jsonObjectOf()
        val passage = root["passages"]!!.asArray().single().jsonObjectOf()

        assertTrue("text-only passage uses the text fallback", "text" in passage)
        assertFalse("text-only passage declares no elements", "elements" in passage)
        assertTrue("no image anywhere", "image" !in keys(root))

        val file = LegacyParser.parse(generated.example)
        val passageElements = file.passages.single().elements
        assertEquals(1, passageElements.size)
        assertTrue("text-only passage is one TextElement", passageElements.single() is ContentElement.TextElement)
        assertEquals(
            listOf("text"),
            file.papers.single().categories.single().questions.first().elements.map(::typeOf)
        )
    }

    /** Owner case 2: text + table + math blocks, still no image. */
    @Test
    fun textTableAndMathWithoutImage() {
        val options = SchemaOptions(
            kind = QuestionKind.FLASHCARDS,
            table = true,
            math = true,
            image = false,
            passage = PassageMode.TEXT_ONLY,
            wrapper = SchemaWrapper.PAPERLESS
        )
        val generated = AiSchemaGenerator.generate(options)
        val file = LegacyParser.parse(generated.example)
        val card = file.papers.single().categories.single().questions.single()

        assertEquals(listOf("text", "table", "math"), card.elements.map(::typeOf))
        assertEquals(listOf("text", "table", "math"), card.explanationElements.map(::typeOf))
        assertNull("no image field", card.image)
        assertNull("no back image", card.explanationImage)
        assertFalse("no image block", card.elements.any { it is ContentElement.ImageElement })
        assertTrue("table block declared", generated.schema.contains("\"const\":\"table\""))
        assertTrue("math block declared", generated.schema.contains("\"const\":\"math\""))
        assertFalse("image block not declared", generated.schema.contains("\"const\":\"image\""))
        assertNotNull("paperless has no papers key", Json.parseToJsonElement(generated.example).jsonObjectOf()["questions"])
    }

    /**
     * Regression: a scalar `image`/`explanationImage` is stored and rendered
     * verbatim (LegacyParser reads it as a plain string, not an <img> tag).
     * Teaching the HTML form here made every generated image render broken.
     * This assertion fails on the old example.
     */
    @Test
    fun scalarImageFieldsAreBareUrlsNotHtmlTags() {
        allOptions.filter { it.image }.forEach { options ->
            val generated = AiSchemaGenerator.generate(options)
            val values = scalarImageValues(Json.parseToJsonElement(generated.example))
            assertTrue("${options}: example must carry scalar image fields", values.isNotEmpty())
            values.forEach { value ->
                assertFalse("${options}: HTML in scalar image '$value'", value.contains("<"))
                assertTrue(
                    "${options}: scalar image must be a portable URL or data URI: '$value'",
                    value.startsWith("https://") || value.startsWith("http://") ||
                        value.startsWith("data:") || value.startsWith("content://")
                )
            }
        }
    }

    private fun scalarImageValues(element: JsonElement): List<String> = when (element) {
        is JsonObject -> element.entries.flatMap { (key, value) ->
            val scalar = if (key == "image" || key == "explanationImage") {
                listOfNotNull((value as? JsonPrimitive)?.content)
            } else {
                emptyList()
            }
            scalar + scalarImageValues(value)
        }
        is JsonArray -> element.flatMap(::scalarImageValues)
        else -> emptyList()
    }

    @Test
    fun smallerShapesReallyHaveFewerCharacters() {
        fun sizeOf(options: SchemaOptions) = AiSchemaGenerator.generate(options).characterCount
        val base = SchemaOptions()
        assertTrue(
            "paperless is smaller than a full bank",
            sizeOf(base.copy(wrapper = SchemaWrapper.PAPERLESS)) < sizeOf(base)
        )
        assertTrue(
            "leaving images out is smaller",
            sizeOf(base.copy(image = false)) < sizeOf(base.copy(image = true))
        )
        assertTrue(
            "leaving passages out is smaller",
            sizeOf(base.copy(passage = PassageMode.NONE)) <
                sizeOf(base.copy(passage = PassageMode.RICH))
        )
    }

    private fun JsonElement.jsonObjectOf(): JsonObject = this as JsonObject
    private fun JsonElement.asArray(): JsonArray = this as JsonArray
}
