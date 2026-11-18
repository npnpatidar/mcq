package com.mcqapp

import com.mcqapp.domain.ContentElement
import com.mcqapp.util.accessibleContentLabelFor
import com.mcqapp.util.mixedContentDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The unit-test classpath has no `androidx.compose.ui.test` (it is an
 * androidTestImplementation only), so a real SemanticsNode assertion is not
 * possible without a new dependency. These two tests cover the next best
 * thing: the exact description the composable builds, and the wiring that
 * puts it on the merged semantics node.
 */
class FormulaA11ySemanticsTest {

    private fun contentElementsSource(): String {
        val file = File("src/main/java/com/mcqapp/util/ContentElements.kt")
        assertTrue("missing: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun mergedDescriptionContainsLabelAndRealQuestionText() {
        val elements = listOf(
            ContentElement.TextElement("Solve x: "),
            ContentElement.MathElement("<math><mfrac><mn>1</mn><mn>2</mn></mfrac></math>")
        )
        val fallback = checkNotNull(accessibleContentLabelFor(elements))
        val description = mixedContentDescription("Rendered question content with formulas", fallback)
        assertEquals("Rendered question content with formulas: Solve x: 1 over 2", description)
    }

    @Test
    fun composableAppliesBuiltDescriptionToMergedSemanticsNode() {
        val source = contentElementsSource()
        assertTrue(
            "the composable must build its label from the resource + fallback",
            source.contains(
                "val contentLabel = mixedContentDescription(stringResource(R.string.a11y_rich_content), fallback)"
            )
        )
        assertTrue(
            "the merged node must still speak that built label",
            source.contains(".semantics(mergeDescendants = true) { contentDescription = contentLabel }")
        )
    }
}
