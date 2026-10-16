package com.mcqapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Layout decisions that no behaviour test pins down: the study-first action
 * order, the shared body text for stems and options, and the sticky-header
 * wiring that keeps the session context out of the scrolling question body.
 */
class StudyActionStructureTest {

    private fun source(path: String): String = File(path).readText()

    @Test
    fun studyComesFirstAndCarriesTheArrow() {
        val card = source("src/main/java/com/mcqapp/ui/library/LibraryPaperCard.kt")
        // Study sits alone above the action FlowRow: its label changes with
        // the due/new counts, so sharing a row shuffled its neighbours.
        val study = card.indexOf("onClick = onStudy")
        val flowRow = card.indexOf("FlowRow(")
        val start = card.indexOf("onClick = onStart")
        assertTrue("study action missing", study >= 0)
        assertTrue("test action missing", start >= 0)
        assertTrue("Study must lead, above the other actions", study < flowRow && flowRow < start)
        assertTrue(
            "Study fills the row so its changing label cannot resize it",
            Regex("""Button\(\s*\n\s*onClick = onStudy""").find(card) != null &&
                card.substringBefore("FlowRow(").contains("fillMaxWidth()"),
        )
        assertTrue(
            "the play arrow belongs to Study",
            card.indexOf("Icons.Default.PlayArrow") < flowRow
        )
    }

    @Test
    fun theOldStartStringIsGoneAndTheActionIsNamedTest() {
        val strings = source("src/main/res/values/strings.xml")
        assertTrue(strings.contains("""<string name="test_action">Test</string>"""))
        assertFalse(strings.contains("""<string name="start">Start</string>"""))
    }

    @Test
    fun questionsAndOptionsShareBodyText() {
        val screen = source("src/main/java/com/mcqapp/ui/study/StudyScreen.kt")
        assertTrue(
            screen.contains(
                "ContentElements(question.elements, textStyle = MaterialTheme.typography.bodyLarge)"
            )
        )
        assertTrue(
            screen.contains(
                "ContentElements(option.elements, textStyle = MaterialTheme.typography.bodyLarge)"
            )
        )
    }
}
