package com.mcqapp

import com.mcqapp.domain.Question
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.ui.study.StudyUiState
import com.mcqapp.ui.study.studyCanActOnCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Bury/Suspend act on the card on screen and pull the next one, so they must
 * stand down while a grade owns that card. The gate is a pure predicate shared
 * by the screen and the ViewModel, so every negative case runs on the JVM; the
 * Compose menu is pinned by source, like [Round2StudyGradingGuardTest] does for
 * Change grade.
 */
class StudyCardActionGuardTest {

    private val question = Question(
        id = "q1",
        categoryId = "c1",
        elements = emptyList(),
        options = emptyList(),
        correctOptionIds = setOf("q1-a")
    )

    private fun eligible() = StudyUiState(question = question)

    @Test
    fun aCardActionIsAllowedOnAnIdleCard() {
        assertTrue("an idle card accepts Bury/Suspend", studyCanActOnCard(eligible()))
    }

    @Test
    fun aStagedGradeBlocksCardActions() {
        assertFalse(
            "a staged grade must not be silently discarded by Bury/Suspend",
            studyCanActOnCard(eligible().copy(pendingGrade = ReviewGrade.GOOD))
        )
    }

    @Test
    fun anInFlightWriteBlocksCardActions() {
        assertFalse(
            "a grade or card-action write already owns the card",
            studyCanActOnCard(eligible().copy(grading = true))
        )
        assertFalse(
            "a second card action must not race the first",
            studyCanActOnCard(eligible().copy(cardActionInProgress = true))
        )
    }

    @Test
    fun theOtherIneligibleStatesAreStillRejected() {
        assertFalse("no card on screen", studyCanActOnCard(eligible().copy(question = null)))
        assertFalse("the summary is not a study card", studyCanActOnCard(eligible().copy(finished = true)))
    }

    @Test
    fun theMenuAndTheViewModelUseTheSameGate() {
        val screen = File("src/main/java/com/mcqapp/ui/study/StudyScreen.kt").readText()
        val menu = screen.substringAfter("DropdownMenu(").substringBefore("\n                    }")
        assertTrue("the menu is missing", menu.contains("DropdownMenuItem"))
        assertEquals(
            2,
            Regex("enabled = studyCanActOnCard\\(state\\)").findAll(menu).count()
        )

        val vm = File("src/main/java/com/mcqapp/ui/study/StudyViewModel.kt").readText()
        val act = vm.substringAfter("private fun actOnCurrent(").substringBefore("\n    }")
        assertTrue(
            "a direct call must be refused too, not just a disabled menu item",
            act.contains("if (!studyCanActOnCard(current)) return")
        )
    }
}
