package com.mcqapp

import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.textContent
import com.mcqapp.util.cellImageTag
import com.mcqapp.util.chemCellHtml
import java.io.File

/**
 * The one rich table row both surface matrices use: prose, an inline `<math>`
 * formula, a canonical portable `<img>` and a `mcq-chem-cell` structure, in
 * the same columns the bundled sample uses (Name, Formula, Structure, Picture).
 *
 * The cells are already canonical — the marker is built by [chemCellHtml], the
 * picture by [cellImageTag] — so a round trip that rewrites either one is a
 * visible difference, never an accepted normalisation. The object itself is
 * pure JVM: the Room-backed matrix and the pure-JVM speech/render-path test
 * share the exact same fixture.
 */
internal object RichCellFixtures {

    const val QUESTION_ID = "q-rich-row"
    const val STEM_PROSE = "The table shows water in four forms."
    const val EXPLANATION_PROSE = "The name cell says water."
    const val OPTION_PROSE = "Water is the liquid in the option row."

    /** A 1x1 PNG, so the portable payload is real and passes the validators. */
    const val TINY_PNG = "data:image/png;base64," +
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8Dw" +
        "HwAFAAH/q842iQAAAABJRU5ErkJggg=="

    val structure = ContentElement.ChemElement(
        smiles = "O",
        formula = "H₂O",
        caption = "water",
        spoken = "one oxygen atom bonded to two hydrogen atoms, water",
        // A captured raster is the export cache a real marker can carry; the
        // spoken surface must never read it back as an image.
        diagram = TINY_PNG
    )

    val formulaCell =
        "<math><msub><mtext>H</mtext><mn>2</mn></msub><mtext>O</mtext></math>"

    val structureCell = chemCellHtml(structure)

    val pictureCell = cellImageTag(TINY_PNG)

    val headerRow = listOf("Name", "Formula", "Structure", "Picture")

    fun richTable(prose: String) = ContentElement.TableElement(
        listOf(headerRow, listOf(prose, formulaCell, structureCell, pictureCell))
    )

    fun stem() = listOf(
        ContentElement.TextElement(STEM_PROSE),
        richTable("Water is the liquid in the stem row.")
    )

    fun optionTable() = listOf(richTable(OPTION_PROSE))

    fun explanation() = listOf(
        ContentElement.TextElement(EXPLANATION_PROSE),
        richTable("Water is the liquid in the explanation row.")
    )

    /**
     * The question a bank would carry: the rich table in the stem, in an
     * option and in the explanation.
     */
    fun question(): QuestionDto {
        val stem = stem()
        val explanation = explanation()
        return QuestionDto(
            id = QUESTION_ID,
            text = stem.textContent,
            elements = stem,
            options = listOf(
                OptionDto(
                    id = "a",
                    text = "Methane",
                    elements = listOf(ContentElement.TextElement("Methane"))
                ),
                OptionDto(id = "b", text = "", elements = optionTable())
            ),
            correctOptionIds = listOf("b"),
            explanation = explanation.textContent,
            explanationElements = explanation,
            tags = listOf("rich-table")
        )
    }

    fun file() = McqFileDto(
        papers = listOf(
            PaperDto(
                id = "paper-rich",
                title = "Rich cells",
                categories = listOf(
                    CategoryDto(id = "cat-rich", title = "Rich", questions = listOf(question()))
                )
            )
        )
    )

    /** Every table row this question carries, in storage order. */
    fun cells(question: QuestionDto): List<List<String>> =
        (question.elements + question.explanationElements + question.options.flatMap { it.elements })
            .filterIsInstance<ContentElement.TableElement>()
            .flatMap { it.rows }

    fun questionOf(file: McqFileDto, id: String = QUESTION_ID): QuestionDto =
        file.papers
            .flatMap { paper -> paper.questions + paper.categories.flatMap { it.questions } }
            .single { it.id == id }

    /** The bundled bank, from the working directory Gradle runs tests in. */
    fun sampleJson(): String =
        listOf("src/main/assets/sample_paper.json", "app/src/main/assets/sample_paper.json")
            .firstNotNullOfOrNull { File(it).takeIf { file -> file.isFile } }
            ?.readText()
            ?: error("sample_paper.json not found from ${File(".").absolutePath}")
}
