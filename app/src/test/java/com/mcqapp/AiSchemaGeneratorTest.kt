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
import kotlinx.serialization.json.jsonPrimitive
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

    /**
     * With images on, the first question's text block embeds one inline image,
     * which the parser splits into text / image / text around the tag. Every
     * other question (and every back/passage) keeps the plain block list.
     */
    private fun expectedElementTypes(options: SchemaOptions, index: Int): List<String> {
        val types = options.blocks.map(::nameOf).toMutableList()
        if (options.image && index == 0) {
            val textAt = types.indexOf("text")
            types.add(textAt + 1, "image")
            types.add(textAt + 2, "text")
        }
        return types
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
                assertEquals(
                    "$label q$index: element blocks",
                    expectedElementTypes(options, index),
                    q.elements.map(::typeOf)
                )
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
                    val expectedImages = if (index == 0) 2 else 1
                    assertEquals("$label q$index: image count", expectedImages, imageCount)
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

    /** The tiny 1×1 PNG the generator uses for every image example. */
    private val tinyPng = "data:image/png;base64," +
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGNgAAIAAAUAAXpeqz8AAAAASUVORK5CYII="

    /**
     * Regression: a scalar `image`/`explanationImage` is stored and rendered
     * verbatim (LegacyParser reads it as a plain string, not an <img> tag).
     * "Inline" means a base64 data URI, never a web URL — every scalar image
     * in an image-on example must be one.
     */
    @Test
    fun scalarImageFieldsAreBareDataUrisNotHtmlTags() {
        allOptions.filter { it.image }.forEach { options ->
            val generated = AiSchemaGenerator.generate(options)
            val values = scalarImageValues(Json.parseToJsonElement(generated.example))
            assertTrue("${options}: example must carry scalar image fields", values.isNotEmpty())
            values.forEach { value ->
                assertFalse("${options}: HTML in scalar image '$value'", value.contains("<"))
                assertTrue(
                    "${options}: scalar image must be a base64 data URI: '$value'",
                    value.startsWith("data:image/")
                )
            }
            assertFalse(
                "${options}: no web URL in an image-on example",
                generated.example.contains("https://")
            )
        }
    }

    /**
     * Owner case: with images on, the prompt teaches the inline form and the
     * example's first question puts one `<img>` inside a text block. The real
     * parser must resolve it to an ImageElement flanked by text runs.
     */
    @Test
    fun imageOnDemonstratesInlineImageInFirstQuestionText() {
        val options = SchemaOptions(
            kind = QuestionKind.MIXED,
            table = true,
            math = true,
            image = true,
            passage = PassageMode.NONE,
            wrapper = SchemaWrapper.FULL_BANK
        )
        val generated = AiSchemaGenerator.generate(options)

        assertTrue("prompt mentions inline images", generated.prompt.contains("inline"))
        assertTrue(
            "prompt shows the data-URI form",
            generated.prompt.contains("<img src=\"data:image/png;base64,...\">")
        )
        assertTrue("prompt says base64", generated.prompt.contains("base64 data URI"))
        assertTrue("prompt rules out web URLs", generated.prompt.contains("not a web URL"))
        assertFalse("no web URL in the image-on prompt", generated.prompt.contains("https://"))

        val file = LegacyParser.parse(generated.example)
        val first = file.papers.single().categories.single().questions.first()
        assertEquals(listOf("text", "image", "text", "table", "math", "image"), first.elements.map(::typeOf))
        assertEquals(
            listOf(
                ContentElement.ImageElement(tinyPng),
                ContentElement.ImageElement(tinyPng)
            ),
            first.elements.filterIsInstance<ContentElement.ImageElement>()
        )
        assertEquals(emptyList<String>(), file.warnings)
    }

    /** With images off, neither the prompt nor the example carries image markup. */
    @Test
    fun imageOffHasNoImageMarkupAnywhere() {
        allOptions.filter { !it.image }.forEach { options ->
            val generated = AiSchemaGenerator.generate(options)
            assertFalse("${options}: prompt has an img tag", generated.prompt.contains("<img"))
            assertFalse("${options}: prompt suggests inline images", generated.prompt.contains("inline"))
            assertFalse("${options}: example has an img tag", generated.example.contains("<img"))
            assertFalse("${options}: example has an image data URI", generated.example.contains("data:image"))
            assertFalse("${options}: prompt has a web URL", generated.prompt.contains("https://"))
            assertFalse("${options}: example has a web URL", generated.example.contains("https://"))
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

    private fun schemaRootKeys(generated: AiSchemaGenerator.Generated): Set<String> {
        val schema = Json.parseToJsonElement(generated.schema).jsonObjectOf()
        return schema["properties"]!!.jsonObjectOf().keys
    }

    private fun schemaRootRequired(generated: AiSchemaGenerator.Generated): List<String> {
        val schema = Json.parseToJsonElement(generated.schema).jsonObjectOf()
        return schema["required"]!!.asArray().map { it.jsonPrimitive.content }
    }

    @Test
    fun rootCategoriesOnlyAreExpressibleAndImportable() {
        val options = SchemaOptions(
            kind = QuestionKind.MCQ,
            includePapers = false,
            includeCategories = true,
            includePassages = false
        )
        val generated = AiSchemaGenerator.generate(options)
        assertEquals(setOf("categories"), schemaRootKeys(generated))
        assertEquals(listOf("categories"), schemaRootRequired(generated))
        val root = Json.parseToJsonElement(generated.example).jsonObjectOf()
        assertTrue("root categories", "categories" in root)
        assertFalse("no root papers", "papers" in root)
        assertFalse("no root questions", "questions" in root)
        assertTrue("prompt names root categories", generated.prompt.contains("root categories"))

        val file = LegacyParser.parse(generated.example)
        assertEquals("no warnings: ${file.warnings}", emptyList<String>(), file.warnings)
        assertEquals(1, file.papers.size)
        val paper = file.papers.single()
        assertTrue(paper.id.startsWith(LegacyParser.EPHEMERAL_PAPER_ID_PREFIX))
        assertEquals(listOf("cat-1"), paper.categories.map { it.id })
        assertEquals(listOf("q1"), paper.categories.single().questions.map { it.id })
        assertTrue(file.passages.isEmpty())
    }

    @Test
    fun paperWithDirectQuestionsAndNoCategories() {
        val options = SchemaOptions(
            kind = QuestionKind.MCQ,
            includePapers = true,
            includeCategories = false,
            includePassages = false
        )
        val generated = AiSchemaGenerator.generate(options)
        assertEquals(setOf("papers"), schemaRootKeys(generated))
        val paperJson = Json.parseToJsonElement(generated.example).jsonObjectOf()
            .let { it["papers"]!!.asArray().single().jsonObjectOf() }
        assertTrue("paper questions", "questions" in paperJson)
        assertFalse("no paper categories", "categories" in paperJson)
        assertTrue("prompt says direct papers", generated.prompt.contains("directly into papers"))

        val paper = LegacyParser.parse(generated.example).papers.single()
        assertEquals("paper-1", paper.id)
        assertEquals(1, paper.categories.size)
        assertEquals("Sample paper", paper.categories.single().title)
        assertEquals(listOf("q1"), paper.categories.single().questions.map { it.id })
        assertTrue(paper.questions.isEmpty())
    }

    @Test
    fun paperSchemaAllowsEitherOrBothContainers() {
        val generated = AiSchemaGenerator.generate(SchemaOptions(includePapers = true))
        val schema = Json.parseToJsonElement(generated.schema).jsonObjectOf()
        val paper = schema["properties"]!!.jsonObjectOf()
            .let { it["papers"]!!.jsonObjectOf()["items"]!!.jsonObjectOf() }
        assertEquals(setOf("id", "title", "categories", "questions"), paper["properties"]!!.jsonObjectOf().keys)
        assertEquals(setOf("id"), paper["required"]!!.asArray().map { it.jsonPrimitive.content }.toSet())
    }

    @Test
    fun paperlessPassagesWithQuestions() {
        val options = SchemaOptions(
            kind = QuestionKind.FLASHCARDS,
            passage = PassageMode.RICH,
            includePapers = false,
            includeCategories = false,
            includePassages = true
        )
        val generated = AiSchemaGenerator.generate(options)
        assertEquals(setOf("questions", "passages"), schemaRootKeys(generated))
        assertEquals(
            setOf("questions", "passages"),
            schemaRootRequired(generated).toSet()
        )
        val file = LegacyParser.parse(generated.example)
        assertEquals("no warnings: ${file.warnings}", emptyList<String>(), file.warnings)
        assertEquals(1, file.papers.size)
        assertEquals(1, file.passages.size)
        assertEquals("p1", file.passages.single().id)
        val card = file.papers.single().categories.single().questions.single()
        assertEquals("p1", card.passageId)
    }

    @Test
    fun papersTogetherWithPassages() {
        val options = SchemaOptions(includePapers = true, includePassages = true)
        val generated = AiSchemaGenerator.generate(options)
        assertEquals(setOf("papers", "passages"), schemaRootKeys(generated))
        val file = LegacyParser.parse(generated.example)
        assertEquals("no warnings: ${file.warnings}", emptyList<String>(), file.warnings)
        assertEquals(1, file.papers.size)
        assertEquals(1, file.passages.size)
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
