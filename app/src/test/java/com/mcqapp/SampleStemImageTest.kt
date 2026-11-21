package com.mcqapp

import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A stem that talks about a picture must carry it. The sample shipped
 * `q-vis-2` — "The circle shows four sectors..." tagged `image-question` —
 * with no picture at all, so the question could not be answered as shown.
 *
 * The word list is deliberately narrow: only words that cannot sensibly
 * describe a text-only question. Adding "chart" or "table" here would flag
 * legitimate copy (the bar-chart question draws itself from a table), so the
 * guard trades breadth for zero false positives.
 */
class SampleStemImageTest {

    private val sampleJson: String by lazy {
        listOf(
            "src/main/assets/sample_paper.json",
            "app/src/main/assets/sample_paper.json"
        ).firstNotNullOfOrNull { File(it).takeIf { file -> file.isFile } }
            ?.readText()
            ?: error("sample_paper.json not found from ${File(".").absolutePath}")
    }

    private val questions: List<QuestionDto> by lazy {
        val paper = LegacyParser.parse(sampleJson).papers.single()
        paper.questions + paper.categories.flatMap { it.questions }
    }

    /** Words that promise a picture the learner must be able to see. */
    private val pictorial = Regex(
        "\\b(circle|figure|diagram|pie chart|graph|picture)\\b",
        RegexOption.IGNORE_CASE
    )

    private fun QuestionDto.carriesPicture(): Boolean =
        !image.isNullOrBlank() ||
            elements.any { element ->
                element is ContentElement.ImageElement && element.src.isNotBlank()
            }

    /** The authored image content of a question's stem, alt text included. */
    private fun authoredStemImage(id: String): String? {
        val root = Json.parseToJsonElement(sampleJson).jsonObject
        val buckets = mutableListOf<JsonArray>()
        root["papers"]?.jsonArray?.forEach { paper ->
            paper.jsonObject["categories"]?.jsonArray?.forEach { category ->
                category.jsonObject["questions"]?.jsonArray?.let { buckets += it }
            }
            paper.jsonObject["questions"]?.jsonArray?.let { buckets += it }
        }
        val question = buckets.asSequence()
            .flatMap { it.asSequence() }
            .map { it.jsonObject }
            .firstOrNull { it["id"]?.jsonPrimitive?.content == id }
            ?: return null
        return question["question_elements"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["type"]?.jsonPrimitive?.content == "image" }
            ?.get("content")?.jsonPrimitive?.content
    }

    @Test
    fun everyStemThatMentionsAPictureCarriesOne() {
        val references = questions.filter { pictorial.containsMatchIn(it.text) }
        assertTrue(
            "the guard found no picture-referencing stems — the pattern went stale",
            references.isNotEmpty()
        )
        val missing = references.filterNot { it.carriesPicture() }
        assertTrue(
            "these stems talk about a picture but carry none: " +
                missing.map { it.id },
            missing.isEmpty()
        )
    }

    @Test
    fun theSectorQuestionCarriesItsPieChart() {
        val question = questions.single { it.id == "q-vis-2" }
        val image = question.elements
            .filterIsInstance<ContentElement.ImageElement>()
            .singleOrNull { it.src.startsWith("data:image/") }
        assertTrue("q-vis-2 must carry its pie chart", image != null)

        // LegacyParser keeps the src but not the alt, so prove the authored
        // element is described for screen readers as well.
        val authored = authoredStemImage("q-vis-2")
        assertTrue("q-vis-2 must carry an authored image element", authored != null)
        assertTrue(
            "the pie chart must be an embedded picture, got ${authored?.take(40)}",
            authored!!.contains("data:image/")
        )
        val alt = Regex("alt=\"([^\"]*)\"").find(authored)?.groupValues?.get(1)
        assertTrue("the pie chart must carry a non-blank alt, got '$alt'", !alt.isNullOrBlank())
    }
}
