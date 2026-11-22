package com.mcqapp

import com.mcqapp.ui.study.StudyUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The opt-in "Confirm grade before next card" setting, checked without a
 * Compose/Room harness: the default (off), the full wiring from Settings to
 * the Study screen, and the three-mode behaviour of the shared staging gate.
 */
class StudyConfirmGradeToggleTest {

    private val settings by lazy {
        File("src/main/java/com/mcqapp/data/repository/RepositorySettings.kt").readText()
    }
    private val vm by lazy {
        File("src/main/java/com/mcqapp/ui/study/StudyViewModel.kt").readText()
    }
    private val screen by lazy {
        File("src/main/java/com/mcqapp/ui/study/StudyScreen.kt").readText()
    }
    private val schedulerSection by lazy {
        File("src/main/java/com/mcqapp/ui/settings/StudySchedulerSection.kt").readText()
    }
    private val english by lazy { File("src/main/res/values/strings.xml").readText() }
    private val hindi by lazy { File("src/main/res/values-hi/strings_hi_2.xml").readText() }

    @Test
    fun theSettingIsOffByDefaultAndLivesInStudySpacedRepetition() {
        assertTrue(
            "the DataStore key must default to false",
            Regex("""booleanPreferencesKey\("confirm_grade_before_next"\)""")
                .containsMatchIn(settings) &&
                settings.contains("it[confirmGradeBeforeNextKey] ?: false")
        )
        assertFalse("a fresh session must not opt in", StudyUiState().confirmGradeBeforeNext)
        assertFalse(
            "a fresh Classic session must keep one-tap grading",
            StudyUiState(simplified = false).stagedGrading
        )
        assertTrue("Simplified still stages by default", StudyUiState().stagedGrading)
        assertTrue(
            "the opt-in is a switch in the Study / Spaced repetition section",
            schedulerSection.contains("R.string.confirm_grade_before_next") &&
                schedulerSection.contains("setting-confirm-grade-before-next")
        )
        for (key in listOf("confirm_grade_before_next", "confirm_grade_before_next_help")) {
            assertTrue("$key must exist in English", english.contains("""name="$key""""))
            assertTrue("$key must exist in Hindi", hindi.contains("""name="$key""""))
        }
    }

    @Test
    fun theSessionReadsTheToggleAndSimplifiedStagesRegardless() {
        assertTrue(
            "one read per session, next to simplifiedStudy",
            vm.contains("confirmGradeBeforeNext = repository.confirmGradeBeforeNext().first()")
        )
        assertTrue(
            "staging is one derived flag, not three copies of a condition",
            vm.contains("val stagedGrading: Boolean get() = simplified || confirmGradeBeforeNext")
        )
        assertTrue(
            "Next and Stop must both honour the derived flag",
            vm.contains("!current.stagedGrading || !current.revealed") &&
                vm.contains("!current.stagedGrading || !current.revealed || current.grading")
        )
        assertTrue(
            "change-grade uses the same flag",
            vm.contains("!state.grading && state.stagedGrading && state.revealed")
        )
    }

    @Test
    fun allThreeModesShareOneStagedCardAndOneStagedActionsComposable() {
        assertTrue("one shared result block", screen.contains("private fun StagedResultBlock("))
        assertTrue("one shared actions composable", screen.contains("private fun StagedResultActions("))
        assertTrue(
            "Simplified uses it unconditionally",
            screen.contains("StagedResultActions(state = state, viewModel = viewModel)")
        )
        assertTrue(
            "Classic and self-graded stage only on the opt-in",
            screen.contains("state.confirmGradeBeforeNext && state.pendingGrade != null") &&
                screen.contains("if (state.confirmGradeBeforeNext) viewModel.stageGrade(it)")
        )
        assertTrue(
            "the default one-tap grade path is kept for when the toggle is off",
            screen.contains("else viewModel.grade(it)")
        )
        assertTrue(
            "a self-graded card gets a neutral result, never a verdict",
            screen.contains("private fun SelfGradedResultCard(") &&
                !screen.contains("studyVerdictRes(result.correct)")
        )
    }

    @Test
    fun theStagingFlagCoversAllThreeModes() {
        fun state(simplified: Boolean, optIn: Boolean) = StudyUiState(
            simplified = simplified,
            confirmGradeBeforeNext = optIn,
            revealed = true,
            pendingGrade = com.mcqapp.domain.ReviewGrade.GOOD,
            question = com.mcqapp.domain.Question(
                id = "q", categoryId = "c", elements = emptyList(),
                options = emptyList(), correctOptionIds = emptySet()
            )
        )
        // Simplified (keyed or self-graded) always stages; Classic only opts in.
        assertTrue(state(simplified = true, optIn = false).stagedGrading)
        assertTrue(state(simplified = true, optIn = true).stagedGrading)
        assertFalse(state(simplified = false, optIn = false).stagedGrading)
        assertTrue(state(simplified = false, optIn = true).stagedGrading)
    }
}
