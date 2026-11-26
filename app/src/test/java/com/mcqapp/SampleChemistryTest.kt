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

    private fun organic(id: String): QuestionDto =
        paper.categories.single { it.id == "cat-chemistry" }.questions.single { it.id == id }

    private fun structureOf(elements: List<ContentElement>): ContentElement.ChemElement =
        elements.filterIsInstance<ContentElement.ChemElement>().single()

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

    @Test
    fun theLactoneReductionQuestionParsesEveryStructureWithItsAnswer() {
        val question = organic("q-org-1")
        val scheme = structureOf(question.elements)
        assertEquals("the scheme is a reaction SMILES with an empty product", "O=C1OCCCCC(=O)C1>>", scheme.smiles)
        assertEquals("the inline product label replaces the old A = ? line", "A", scheme.formula)
        assertEquals("LiAlH₄ / ether", scheme.caption)
        assertTrue(scheme.spoken.contains("lactone"))
        assertTrue(scheme.spoken.contains("LiAlH₄"))
        assertEquals("", scheme.diagram)
        assertEquals(1, question.elements.count { it is ContentElement.ChemElement })
        assertTrue("the reagent must not be duplicated in the stem text", "LiAlH₄" !in question.text)
        assertTrue("no raw arrow line may remain in the stem", "→" !in question.text)
        assertEquals("Which is the reduction product (A) of the following compound?", question.text)
        assertTrue("no trailing A = ? text line", "A = ?" !in question.text)

        val options = mapOf(
            "a" to "OC1CCOCCCC1O",
            "b" to "O=C1OCCCCC(O)C1",
            "c" to "OCCCCC(O)CC=O",
            "d" to "OCCCCC(O)CCO"
        )
        val labels = mapOf(
            "a" to "(1) Cyclic ether diol, C₇H₁₄O₃",
            "b" to "(2) Hydroxy lactone, C₇H₁₂O₃",
            "c" to "(3) Hydroxy aldehyde, C₇H₁₄O₃",
            "d" to "(4) Heptane-1,3,7-triol, C₇H₁₆O₃"
        )
        assertEquals(options.keys, question.options.map { it.id }.toSet())
        options.forEach { (id, smiles) ->
            val option = question.options.single { it.id == id }
            assertEquals("$id must carry exactly one structure", 1,
                option.elements.count { it is ContentElement.ChemElement })
            val chem = structureOf(option.elements)
            assertEquals(smiles, chem.smiles)
            assertEquals("$id must not repeat its name in the chemistry caption", "", chem.caption)
            assertEquals("$id must not repeat its formula on the drawing", "", chem.formula)
            assertTrue("$id must keep a spoken description", chem.spoken.isNotBlank())
            assertEquals("", chem.diagram)
            assertTrue("$id must keep a readable text label", option.text.startsWith("(${'1' + (id[0] - 'a')})"))
            assertEquals("$id formula must be in the text label, not a duplicate caption", labels[id], option.text)
        }
        assertEquals(
            "option 3 has an aldehyde and one hydroxyl on a seven carbon chain",
            "a seven carbon chain with an aldehyde at one end and one hydroxyl group",
            structureOf(question.options.single { it.id == "c" }.elements).spoken
        )
        assertTrue(
            "option 4 must be named heptane-1,3,7-triol",
            structureOf(question.options.single { it.id == "d" }.elements).spoken
                .contains("heptane-1,3,7-triol")
        )
        assertEquals("LAH opens the lactone to a triol, option 4", listOf("d"), question.correctOptionIds)
        assertTrue(question.explanation.contains("OCCCCC(O)CCO"))
        assertTrue(question.explanation.contains("seven-carbon"))
        assertTrue(question.explanation.contains("heptane-1,3,7-triol"))
        assertTrue(question.explanation.contains("C₇H₁₆O₃"))
        assertTrue(question.explanation.contains("option 4"))
    }

    @Test
    fun theRuO4OxidationQuestionParsesEveryStructureWithItsAnswer() {
        val question = organic("q-org-2")
        val scheme = structureOf(question.elements)
        assertEquals("the scheme is a reaction SMILES with an empty product", "CC1=CCC(O)CC1>>", scheme.smiles)
        assertEquals("the inline product label replaces the old A = ? line", "A", scheme.formula)
        assertEquals("RuO₄ / NaIO₄", scheme.caption)
        assertTrue(scheme.spoken.contains("methyl"))
        assertTrue(scheme.spoken.contains("RuO₄ and NaIO₄"))
        assertEquals("", scheme.diagram)
        assertEquals(1, question.elements.count { it is ContentElement.ChemElement })
        assertTrue("the reagent must not be duplicated in the stem text", "RuO₄" !in question.text)
        assertTrue("the reagent must not be duplicated in the stem text", "NaIO₄" !in question.text)
        assertTrue("no raw arrow line may remain in the stem", "→" !in question.text)
        assertEquals("Which structure is product A of the reaction below?", question.text)
        assertTrue("no trailing A = ? text line", "A = ?" !in question.text)

        val options = mapOf(
            "a" to "CC1=CCC(=O)CC1",
            "b" to "CC12OC1CCC(O)C2",
            "c" to "CC(=O)CCC(=O)CC(=O)O",
            "d" to "CC(=O)CCC(O)CC(=O)O"
        )
        val labels = mapOf(
            "a" to "(1) Methyl cyclohexenone, C₇H₁₀O",
            "b" to "(2) Epoxide with OH, C₇H₁₂O₂",
            "c" to "(3) Keto acid, C₇H₁₀O₄",
            "d" to "(4) Hydroxy keto acid, C₇H₁₂O₄"
        )
        assertEquals(options.keys, question.options.map { it.id }.toSet())
        options.forEach { (id, smiles) ->
            val option = question.options.single { it.id == id }
            assertEquals("$id must carry exactly one structure", 1,
                option.elements.count { it is ContentElement.ChemElement })
            val chem = structureOf(option.elements)
            assertEquals(smiles, chem.smiles)
            assertEquals("$id must not repeat its name in the chemistry caption", "", chem.caption)
            assertEquals("$id must not repeat its formula on the drawing", "", chem.formula)
            assertTrue("$id must keep a spoken description", chem.spoken.isNotBlank())
            assertEquals("", chem.diagram)
            assertTrue("$id must keep a readable text label", option.text.startsWith("(${'1' + (id[0] - 'a')})"))
            assertEquals("$id formula must be in the text label, not a duplicate caption", labels[id], option.text)
        }
        assertEquals(
            "option 4 has one methyl ketone, one hydroxyl and one acid",
            "a chain with a methyl ketone, a hydroxyl group and a carboxylic acid",
            structureOf(question.options.single { it.id == "d" }.elements).spoken
        )
        assertEquals(
            "option 3 product spacing must match the reference chain",
            "a carboxylic acid chain with two ketones separated by two CH₂ groups, one of them a methyl ketone",
            structureOf(question.options.single { it.id == "c" }.elements).spoken
        )
        assertEquals("RuO4 also oxidises the alcohol, so option 3", listOf("c"), question.correctOptionIds)
        assertTrue(question.explanation.contains("CH₃CO–CH₂–CH₂–CO–CH₂–COOH"))
        assertTrue(question.explanation.contains("CC(=O)CCC(=O)CC(=O)O"))
        assertTrue(question.explanation.contains("option 3"))
    }

    @Test
    fun theOrganicQuestionsUseReactionSchemesNotPastedPictures() {
        val reagents = mapOf(
            "q-org-1" to "LiAlH₄ / ether",
            "q-org-2" to "RuO₄ / NaIO₄"
        )
        reagents.forEach { (id, reagent) ->
            val question = organic(id)
            val all = buildList {
                addAll(question.elements)
                addAll(question.explanationElements)
                question.options.forEach { addAll(it.elements) }
            }
            assertEquals("$id must draw every structure from SMILES", 0,
                all.count { it is ContentElement.ImageElement })
            assertEquals("$id must have one reaction scheme in its stem", 1,
                question.elements.count { it is ContentElement.ChemElement })
            val scheme = structureOf(question.elements)
            assertEquals("$id reagent must ride in the caption", reagent, scheme.caption)
            assertEquals("$id must label the inline product with A", "A", scheme.formula)
            assertTrue("$id must have no standalone A = ? text", "A = ?" !in question.text)
            assertEquals("$id must leave the SMILES product blank", 2, scheme.smiles.count { it == '>' })
            assertTrue("$id must keep the reactant before the arrow", scheme.smiles.indexOf('>') > 0)
            all.filterIsInstance<ContentElement.ChemElement>().forEach { chem ->
                assertTrue("$id has a raw markup caption: ${chem.caption}",
                    "<" !in chem.caption && ">" !in chem.caption)
                assertTrue("$id has a long SMILES: ${chem.smiles}", chem.smiles.length <= 32)
                assertTrue("$id has no spoken description", chem.spoken.isNotBlank())
                assertTrue("$id has an unlabelled diagram", chem.diagram.isEmpty())
            }
            question.options.forEach { option ->
                val chem = structureOf(option.elements)
                assertTrue("$id ${option.id} must label its drawing with text only", chem.caption.isEmpty())
                assertTrue("$id ${option.id} must not print a formula on the drawing", chem.formula.isEmpty())
            }
        }
    }

    /** No question or coverage shape may disappear when the chemistry demo is added. */
    @Test
    fun theSampleStillHasEveryPreviousQuestionAndCoverage() {
        val previousIds = listOf(
            "q-top-1",
            "q-sci-1", "q-sci-2",
            "q-chem-1", "q-chem-2", "q-chem-3", "q-chem-4",
            "q-org-1", "q-org-2",
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
        // The benzene demo and the id-less legacy question are checked by
        // their own tests, not by this id list.
        assertEquals(previousIds.size + 2, questions.size)
        assertEquals(7, paper.categories.single { it.id == "cat-chemistry" }.questions.size)

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
            "benzene, one reaction scheme in each organic stem and a structure in every option",
            11,
            elements.count { it is ContentElement.ChemElement }
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
