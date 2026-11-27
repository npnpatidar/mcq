package com.mcqapp

import com.mcqapp.data.io.LegacyParser
import com.mcqapp.domain.ContentElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleImageParseTest {
    @Test
    fun sampleAssetYieldsImageElements() {
        val text = java.io.File("src/main/assets/sample_paper.json").readText()
        val file = LegacyParser.parse(text.byteInputStream())
        val questions = file.papers.flatMap { it.categories }.flatMap { it.questions }
        val withImages = questions.count { q ->
            q.elements.any { it is com.mcqapp.domain.ContentElement.ImageElement } ||
                q.options.any { o -> o.elements.any { it is com.mcqapp.domain.ContentElement.ImageElement } }
        }
        println("SAMPLE PARSE: ${questions.size} questions, $withImages with image elements")
        assertTrue("expected image elements in the sample bank", withImages >= 6)
    }

    @Test
    fun sampleAssetYieldsAChemistryStructure() {
        val text = java.io.File("src/main/assets/sample_paper.json").readText()
        val file = LegacyParser.parse(text.byteInputStream())
        val question = file.papers.flatMap { it.categories }.flatMap { it.questions }
            .single { it.id == "q-chem-5" }
        val structure = question.elements.filterIsInstance<ContentElement.ChemElement>().single()
        assertEquals("c1ccccc1", structure.smiles)
        assertEquals("C₆H₆", structure.formula)
        assertEquals("benzene", structure.caption)
    }
}
