package com.mcqapp

import com.mcqapp.domain.Question
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.ui.study.StudyUiState
import com.mcqapp.ui.study.studyCanChangeGrade
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S4: a change-grade tap during an in-flight write must be ignored. The gate is
 * a pure predicate over [StudyUiState], so the negative case runs on the JVM;
 * the behavioural counterpart (held at the grade gate) runs on CI's x86_64.
 */
class Round2StudyGradingGuardTest {

    private val question = Question(
        id = "q1",
        categoryId = "c1",
        elements = emptyList(),
        options = emptyList(),
        correctOptionIds = setOf("q1-a")
    )

    private fun eligible(grading: Boolean) = StudyUiState(
        question = question,
        simplified = true,
        revealed = true,
        pendingGrade = ReviewGrade.GOOD,
        grading = grading
    )

    @Test
    fun aChangeIsAllowedBeforeTheWriteStarts() {
        assertTrue("an eligible, idle card accepts a grade change", studyCanChangeGrade(eligible(false)))
    }

    @Test
    fun aChangeDuringGradingIsIgnored() {
        assertFalse("a tap in the frame before recomposition must be ignored", studyCanChangeGrade(eligible(true)))
    }

    @Test
    fun otherIneligibleStatesAreStillRejected() {
        assertFalse("classic mode keeps one-tap grading until the learner opts in", studyCanChangeGrade(eligible(false).copy(simplified = false)))
        assertFalse("the answer must be revealed first", studyCanChangeGrade(eligible(false).copy(revealed = false)))
        assertFalse("there must be a proposal to change", studyCanChangeGrade(eligible(false).copy(pendingGrade = null)))
        assertFalse("a card must be on screen", studyCanChangeGrade(eligible(false).copy(question = null)))
    }

    @Test
    fun optingInOpensChangeGradeForFourButtonFlows() {
        assertTrue(
            "the opt-in toggle must let Classic correct a staged grade",
            studyCanChangeGrade(eligible(false).copy(simplified = false, confirmGradeBeforeNext = true))
        )
        assertTrue(
            "Simplified is unaffected by the toggle",
            studyCanChangeGrade(eligible(false).copy(confirmGradeBeforeNext = true))
        )
        assertFalse(
            "a write already in flight still owns the proposal",
            studyCanChangeGrade(eligible(true).copy(simplified = false, confirmGradeBeforeNext = true))
        )
    }

    @Test
    fun theViewModelAndTheToggleUseTheSameGate() {
        val vm = File("src/main/java/com/mcqapp/ui/study/StudyViewModel.kt").readText()
        val changeGrade = vm.substringAfter("fun changeGrade(").substringBefore("\n    }")
        assertTrue(
            "changeGrade must re-check the gate before touching pendingGrade",
            changeGrade.contains("studyCanChangeGrade(current)")
        )
        assertTrue(
            "the guard must run before the state write",
            changeGrade.indexOf("studyCanChangeGrade(current)") < changeGrade.indexOf("_state.update")
        )
        val screen = File("src/main/java/com/mcqapp/ui/study/StudyScreen.kt").readText()
        val toggle = screen.substringAfter("onClick = { showPicker = !showPicker }")
            .substringBefore("Text(stringResource(R.string.change_grade))")
        assertTrue(
            "the Change grade toggle must stand down while grading",
            toggle.contains("enabled = !state.grading")
        )
        assertTrue(
            "the picker buttons must still be gated too",
            Regex("""enabled = !state\.grading,\s+previews = state\.previews,\s+onGrade = \{\s+viewModel\.changeGrade\(it\)""")
                .containsMatchIn(screen)
        )
    }
}
