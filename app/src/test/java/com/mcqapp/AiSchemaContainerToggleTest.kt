package com.mcqapp

import com.mcqapp.data.io.LegacyParser
import com.mcqapp.domain.ai.AiSchemaGenerator
import com.mcqapp.domain.ai.PassageMode
import com.mcqapp.domain.ai.QuestionKind
import com.mcqapp.domain.ai.SchemaOptions
import com.mcqapp.domain.ai.SchemaWrapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three container switches are independent, not a second view of the old
 * wrapper enum. Every flip must change the generated bank all the way to the
 * example, and none of the eight combinations may drop questions entirely.
 */
class AiSchemaContainerToggleTest {

    private fun options(papers: Boolean, categories: Boolean, passages: Boolean) = SchemaOptions(
        kind = QuestionKind.MIXED,
        passage = PassageMode.TEXT_ONLY,
        wrapper = SchemaWrapper.FULL_BANK,
        includePapers = papers,
        includeCategories = categories,
        includePassages = passages
    )

    private fun root(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
    private fun schemaProps(text: String): JsonObject = root(text)["properties"] as JsonObject
    private fun outputText(o: AiSchemaGenerator.Generated) = o.schema + "\n" + o.prompt + "\n" + o.example

    @Test
    fun everyToggleAloneChangesTheGeneratedBank() {
        val baseline = options(true, true, false)
        val base = AiSchemaGenerator.generate(baseline)
        assertTrue("papers missing", schemaProps(base.schema).containsKey("papers"))
        assertFalse("passages should start off", schemaProps(base.schema).containsKey("passages"))
        assertTrue("example papers missing", root(base.example).containsKey("papers"))

        val noPapers = AiSchemaGenerator.generate(options(false, true, false))
        assertFalse("papers still declared", schemaProps(noPapers.schema).containsKey("papers"))
        assertTrue("root categories missing", schemaProps(noPapers.schema).containsKey("categories"))
        assertTrue("example root categories missing", root(noPapers.example).containsKey("categories"))
        assertNotEquals("papers toggle did nothing", outputText(base), outputText(noPapers))

        val noCategories = AiSchemaGenerator.generate(options(true, false, false))
        assertFalse(
            "root categories still declared",
            schemaProps(noCategories.schema).containsKey("categories")
        )
        val paper = (schemaProps(noCategories.schema)["papers"] as JsonObject)["items"] as JsonObject
        assertTrue(
            "questions must remain when categories are off",
            (paper["properties"] as JsonObject).containsKey("questions")
        )
        val examplePapers = root(noCategories.example)["papers"] as JsonArray
        val examplePaper = examplePapers[0] as JsonObject
        assertTrue("example paper must hold questions", examplePaper.containsKey("questions"))
        assertFalse("example paper still grouped by category", examplePaper.containsKey("categories"))
        assertNotEquals("categories toggle did nothing", outputText(base), outputText(noCategories))

        val withPassages = AiSchemaGenerator.generate(options(true, true, true))
        assertTrue("passages not declared", schemaProps(withPassages.schema).containsKey("passages"))
        assertTrue("example passages missing", root(withPassages.example).containsKey("passages"))
        assertNotEquals("passages toggle did nothing", outputText(base), outputText(withPassages))
    }

    @Test
    fun categoriesToggleChangesThePaperSchemaNotJustTheExample() {
        val withCategories = AiSchemaGenerator.generate(options(true, true, false))
        val paperWith = (schemaProps(withCategories.schema)["papers"] as JsonObject)["items"] as JsonObject
        assertTrue(
            "paper schema must declare categories when the toggle is on",
            (paperWith["properties"] as JsonObject).containsKey("categories")
        )

        val withoutCategories = AiSchemaGenerator.generate(options(true, false, false))
        val paperWithout = (schemaProps(withoutCategories.schema)["papers"] as JsonObject)["items"] as JsonObject
        assertFalse(
            "paper schema must drop categories when the toggle is off",
            (paperWithout["properties"] as JsonObject).containsKey("categories")
        )
        assertTrue(
            "paper schema must still allow direct questions",
            (paperWithout["properties"] as JsonObject).containsKey("questions")
        )
        assertEquals(
            JsonPrimitive(false),
            paperWithout["additionalProperties"]
        )
    }

    @Test
    fun allEightContainerCombinationsKeepQuestionsAndParse() {
        listOf(false, true).forEach { papers ->
            listOf(false, true).forEach { categories ->
                listOf(false, true).forEach { passages ->
                    val label = "papers=$papers categories=$categories passages=$passages"
                    val generated = AiSchemaGenerator.generate(options(papers, categories, passages))
                    val props = schemaProps(generated.schema)
                    assertEquals("$label papers key", papers, props.containsKey("papers"))
                    assertEquals(
                        "$label categories key",
                        categories && !papers,
                        props.containsKey("categories")
                    )
                    assertEquals("$label passages key", passages, props.containsKey("passages"))
                    if (!papers && !categories) {
                        assertTrue("$label root questions missing", props.containsKey("questions"))
                    }
                    // The name promises parsing: feed the real parser every
                    // generated example, not just the ones without passages.
                    assertTrue("$label example root", root(generated.example).containsKey("questions") ||
                        root(generated.example).containsKey("papers") ||
                        root(generated.example).containsKey("categories"))
                    val file = LegacyParser.parse(generated.example)
                    assertTrue("$label parsed papers missing", file.papers.isNotEmpty())
                    assertEquals("$label warnings: ${file.warnings}", emptyList<String>(), file.warnings)
                }
            }
        }
    }
}
