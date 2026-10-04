package com.mcqapp

import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.util.InlineHtml
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The sample bank is the reference for what a bank file may contain, so the
 * inline tags it advertises have to survive import *and* actually render.
 *
 * The renderer ([InlineHtml]) works on an `AnnotatedString`, so a tag that only
 * looks right in the JSON would show up on screen as literal `<strong>`. This
 * checks the rendered spans, not the raw text.
 */
class SampleFormattingTest {

    private val paper: PaperDto by lazy {
        LegacyParser.parse(
            listOf("src/main/assets/sample_paper.json", "app/src/main/assets/sample_paper.json")
                .firstNotNullOfOrNull { File(it).takeIf { f -> f.isFile } }
                ?.readText() ?: error("sample_paper.json not found")
        ).papers.single()
    }

    private fun allQuestions(): List<QuestionDto> =
        paper.questions + paper.categories.flatMap { it.questions }

    /** Every text run in the paper, wherever it appears. */
    private fun textRuns(): List<String> = buildList {
        fun scan(list: List<ContentElement>?) {
            list?.forEach { element ->
                if (element is ContentElement.TextElement) add(element.text)
            }
        }
        allQuestions().forEach { question ->
            scan(question.elements)
            scan(question.explanationElements)
            question.options.forEach { scan(it.elements) }
        }
    }

    @Test
    fun everyInlineTagIsDemonstratedSomewhere() {
        val text = textRuns().joinToString("\n")
        listOf("strong", "em", "u", "del", "mark", "sub", "sup").forEach { tag ->
            assertTrue("the sample bank never uses <$tag>", text.contains("<$tag>"))
        }
    }

    @Test
    fun boldBecomesABoldSpanRatherThanLiteralText() {
        val run = textRuns().first { "<strong>" in it }
        val annotated = InlineHtml.parse(run)
        assertTrue("the <strong> markup leaked onto the screen", "<strong>" !in annotated.text)
        val bold = annotated.spanStyles.filter { it.item.fontWeight != null }
        assertTrue("expected a bold span, got ${annotated.spanStyles}", bold.isNotEmpty())
        assertTrue(
            "the bold span must cover some of the text",
            bold.any { it.end > bold.first().start }
        )
    }

    @Test
    fun subscriptAndSuperscriptMoveTheBaseline() {
        val text = textRuns().joinToString("\n")
        val sub = InlineHtml.parse(textRuns().first { "<sub>" in it })
        assertTrue(
            "expected a subscript span, got ${sub.spanStyles}",
            sub.spanStyles.any { it.item.baselineShift == BaselineShift.Subscript }
        )
        val sup = InlineHtml.parse(textRuns().first { "<sup>" in it })
        assertTrue(
            "expected a superscript span, got ${sup.spanStyles}",
            sup.spanStyles.any { it.item.baselineShift == BaselineShift.Superscript }
        )
    }

    @Test
    fun underlineStrikethroughAndHighlightAllBecomeSpans() {
        fun styles(tag: String) =
            InlineHtml.parse("<$tag>x</$tag>").also { assertEquals("x", it.text) }
                .spanStyles.map { it.item }

        assertTrue(
            "no underline",
            styles("u").any { it.textDecoration == TextDecoration.Underline }
        )
        assertTrue(
            "no strikethrough",
            styles("del").any { it.textDecoration == TextDecoration.LineThrough }
        )
        assertTrue(
            "no highlight",
            styles("mark").any { it.background != androidx.compose.ui.graphics.Color.Unspecified }
        )
        assertTrue("italic missing", styles("em").any { it.fontStyle != null })
        assertTrue("bold missing", styles("strong").any { it.fontWeight != null })
    }

    @Test
    fun theFormattingQuestionsAreAnswerable() {
        val formatting = allQuestions().filter { it.id.startsWith("q-fmt") }
        assertEquals("expected the three formatting questions", 3, formatting.size)
        formatting.forEach { question ->
            val optionIds = question.options.map { it.id }.toSet()
            assertTrue(
                "${question.id} has no answer key",
                question.correctOptionIds.isNotEmpty()
            )
            assertTrue(
                "${question.id} keys an option that does not exist",
                question.correctOptionIds.all { it in optionIds }
            )
        }
    }

    /**
     * A "which two of these" question is ambiguous when two options are the same
     * words in a different order — "charge and time" versus "time and charge".
     * One such pair slipped into the SI-units question and was caught in review.
     *
     * The check is deliberately narrow. It only applies to questions that ask
     * for a *combination*, because elsewhere word order carries the meaning:
     * ordering questions list sequences like "2, 1, 4, 3", where reordering the
     * tokens is the whole difference, and sorting them makes every option look
     * alike.
     */
    @Test
    fun aCombinationQuestionHasNoTwoOptionsThatAreTheSameWordsReordered() {
        val combination = Regex(
            "which two|which three|which four|which pair|select every|select all",
            RegexOption.IGNORE_CASE
        )
        val checked = allQuestions().filter { question ->
            combination.containsMatchIn(InlineHtml.parse(question.text).text)
        }
        assertTrue("expected some combination questions to check", checked.isNotEmpty())

        checked.forEach { question ->
            // The sign must survive: "-b/a" and "b/a" are different answers.
            fun tokens(text: String) = InlineHtml.parse(text).text
                .lowercase()
                .replace("\u2212", "-")
                .split(Regex("[^a-z0-9-]+"))
                .filter { it.isNotBlank() }
                .sorted()

            val comparable = question.options
                .map { tokens(it.text) }
                .filter { it.isNotEmpty() }
            val duplicates = comparable
                .groupingBy { it.joinToString(" ") }
                .eachCount()
                .filterValues { it > 1 }
            assertTrue(
                "${question.id} has options that differ only in word order: ${duplicates.keys}",
                duplicates.isEmpty()
            )
        }
    }
}
