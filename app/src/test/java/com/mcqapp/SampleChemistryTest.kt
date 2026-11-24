package com.mcqapp

import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled sample is the first bank a new user loads, so it is also the
 * demo of the chemical-structure renderer. The chemistry block must survive
 * the real import path with every field intact — a test that only inspected
 * the JSON would pass even if LegacyParser dropped the element.
 *
 * Pure JVM: no Room or Robolectric, so it runs on the ARM64 host too.
 */
class SampleChemistryTest {

    private val sampleJson: String by lazy {
        listOf("src/main/assets/sample_paper.json", "app/src/main/assets/sample_paper.json")
            .firstNotNullOfOrNull { File(it).takeIf { file -> file.isFile } }
            ?.readText()
            ?: error("sample_paper.json not found from ${File(".").absolutePath}")
    }

    private val paper by lazy { LegacyParser.parse(sampleJson).papers.single() }

    private val questions: List<QuestionDto> by lazy {
        paper.questions + paper.categories.flatMap { it.questions }
    }

    private val chemistry: QuestionDto by lazy {
        paper.categories.single { it.id == "cat-chemistry" }
            .questions.single { it.id == "q-chem-5" }
    }

    @Test
    fun theChemistrySampleParsesIntoACompleteChemElement() {
        val elements = chemistry.elements.filterIsInstance<ContentElement.ChemElement>()
        assertEquals("q-chem-5 must carry exactly one structure", 1, elements.size)
        val element = elements.single()
        assertEquals("c1ccccc1", element.smiles)
        assertEquals("C6H6", element.formula)
        assertEquals("benzene", element.caption)
        assertEquals(
            "a ring of six carbon atoms with alternating double bonds, benzene",
            element.spoken
        )
        // Exports add the PNG; the authored sample asks the app to draw it.
        assertEquals("", element.diagram)
    }

    @Test
    fun theAuthoredBlockWritesEveryWireKey() {
        val root = Json.parseToJsonElement(sampleJson).jsonObject
        val question = root["papers"]!!.jsonArray[0].jsonObject["categories"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["id"]?.jsonPrimitive?.content == "cat-chemistry" }["questions"]!!
            .jsonArray.map { it.jsonObject }
            .single { it["id"]?.jsonPrimitive?.content == "q-chem-5" }
        val block = question["question_elements"]!!.jsonArray.map { it.jsonObject }
            .single { it["type"]?.jsonPrimitive?.content == "chem" }
        val content = block["content"]!!.jsonObject
        assertEquals(
            "all five ChemElement keys must be on the wire, empty ones included",
            setOf("smiles", "formula", "caption", "spoken", "diagram"),
            content.keys
        )
        assertEquals("c1ccccc1", content["smiles"]?.jsonPrimitive?.content)
        assertEquals("C6H6", content["formula"]?.jsonPrimitive?.content)
        assertEquals("benzene", content["caption"]?.jsonPrimitive?.content)
    }

    @Test
    fun theChemistryQuestionIsAnAnswerableMultipleChoice() {
        assertEquals("Which structure is benzene?", chemistry.text)
        assertEquals(
            "the structure must be the stem content, not an option",
            1,
            chemistry.elements.filterIsInstance<ContentElement.ChemElement>().size
        )
        assertEquals(4, chemistry.options.size)
        assertEquals(listOf("a"), chemistry.correctOptionIds)
        assertTrue("the explanation must teach the SMILES", chemistry.explanation.contains("c1ccccc1"))
    }

    /** No question or coverage shape may disappear when the chemistry demo is added. */
    @Test
    fun theSampleStillHasEveryPreviousQuestionAndCoverage() {
        val previousIds = listOf(
            "q-top-1",
            "q-sci-1", "q-sci-2",
            "q-chem-1", "q-chem-2", "q-chem-3", "q-chem-4",
            "q-phy-1", "q-phy-2", "q-phy-3", "q-fc-table",
            "q-math-1", "q-math-2", "q-math-3", "q-math-4", "q-math-5",
            "q-math-6", "q-math-7", "q-math-8", "q-math-9", "q-fc-math",
            "q-vis-1", "q-vis-2", "q-vis-3", "q-vis-4",
            "q-fc-image", "q-fc-image-back",
            "q-psg-1", "q-psg-2", "q-psg-3", "q-psg-4", "q-psg-5", "q-psg-6",
            "q-fc-passage",
            "q-his-1", "q-his-2", "q-his-3",
            "q-geo-1", "q-geo-2",
            "q-spt-1", "q-spt-2",
            "q-mc-1", "q-mc-2", "q-mc-3",
            "q-fmt-1", "q-fmt-2", "q-fmt-3",
            "q-bi-1", "q-bi-2", "q-bi-3", "q-fc-hi",
            "q-o1", "q-o2", "q-o3", "q-o4", "q-o5",
            "q-e1", "q-e2", "q-e3", "q-e5", "q-e6"
        )
        val ids = questions.mapNotNull { it.id }.toSet()
        assertTrue(
            "these previously bundled questions disappeared: ${previousIds - ids}",
            previousIds.all { it in ids }
        )
        // One extra chemistry question, and the id-less legacy question still there.
        assertEquals(previousIds.size + 2, questions.size)

        fun allElements() = buildList {
            questions.forEach { question ->
                addAll(question.elements)
                addAll(question.explanationElements)
                question.options.forEach { addAll(it.elements) }
            }
        }
        val elements = allElements()
        assertTrue(
            "the sample must still carry embedded pictures",
            elements.count { it is ContentElement.ImageElement } >= 6
        )
        assertTrue(
            "the sample must still carry MathML formulas",
            elements.any { it is ContentElement.MathElement }
        )
        assertEquals(
            "the seven flashcard demos must remain",
            7,
            questions.count { it.options.isEmpty() }
        )
        assertEquals(
            listOf("passage-1", "passage-2"),
            LegacyParser.parse(sampleJson).passages.map { it.id }
        )
    }
}
