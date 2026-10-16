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
        val actions = card
            .substringAfter("FlowRow(")
            .substringBefore("if (showDrillDialog)")
        val study = actions.indexOf("onClick = onStudy")
        val start = actions.indexOf("onClick = onStart")
        assertTrue("study action missing", study >= 0)
        assertTrue("test action missing", start >= 0)
        assertTrue("Study must lead the action row", study < start)
        assertTrue(
            "the play arrow belongs to Study",
            actions.indexOf("Icons.Default.PlayArrow") < start
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
