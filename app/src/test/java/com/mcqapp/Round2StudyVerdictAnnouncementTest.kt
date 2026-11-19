package com.mcqapp

import com.mcqapp.domain.ReviewGrade
import com.mcqapp.ui.study.StudyResult
import com.mcqapp.ui.study.studyGradeTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S3: the verdict must reach TalkBack, not just the eye and the haptics. The
 * announcement key and its localized source are asserted structurally (the
 * Compose call cannot run in a JVM test); the verdict seam itself is pure.
 */
class Round2StudyVerdictAnnouncementTest {

    private val screen by lazy {
        File("src/main/java/com/mcqapp/ui/study/StudyScreen.kt").readText()
    }

    @Test
    fun aNewResultIsAnnouncedThroughTheExistingAnnouncer() {
        assertTrue(
            "the screen must use the app's LocalAnnouncer, like the error paths",
            screen.contains("val announcer = LocalAnnouncer.current")
        )
        assertTrue(
            "the result must be read from state once, so an unchanged result keeps identity",
            screen.contains("val lastResult = state.lastResult")
        )

        // The message is resolved in composition (configuration-aware) through
        // the localized seam, not built from a raw enum name or a literal.
        val message = screen.substringAfter("val verdictMessage = ")
            .substringBefore("LaunchedEffect(lastResult)")
        assertTrue(
            "a verdict must resolve through the localized resource seam",
            message.contains("stringResource(studyVerdictRes(lastResult.correct))")
        )
        assertTrue(
            "no result must resolve to no message at all",
            Regex("else\\s*\\{\\s*null\\s*\\}").containsMatchIn(message)
        )
        assertFalse(
            "the message must not be an English literal",
            Regex("=\\s*\"").containsMatchIn(message)
        )

        // Negative cases: a cleared result has no message to speak, and an
        // unchanged result does not re-enter the effect (key is the result,
        // not just its correctness or a constant).
        assertTrue(
            "the effect must be keyed on the whole result",
            screen.contains("LaunchedEffect(lastResult)") &&
                !screen.contains("LaunchedEffect(lastResult.correct)") &&
                !screen.contains("LaunchedEffect(Unit)")
        )
        val effect = screen.substringAfter("LaunchedEffect(lastResult)")
            .substringBefore("\n    }")
        assertTrue(
            "only a resolved, non-null message may be spoken",
            effect.contains("verdictMessage?.let")
        )
        assertTrue(
            "the spoken text must go through LocalAnnouncer",
            effect.contains("announcer.announce(it)")
        )
        assertFalse(
            "a bare announce(verdictMessage) would speak null on a cleared result",
            effect.contains("announcer.announce(verdictMessage)")
        )

        // Announcing the error strings alone was the pre-fix state.
        assertTrue(
            "load/grade error announcements must be kept",
            screen.contains("LaunchedEffect(state.loadError)") &&
                screen.contains("LaunchedEffect(state.gradeError)")
        )
    }

    @Test
    fun resultCarriesEverythingTheVerdictNeeds() {
        val correct = StudyResult(
            correct = true,
            grade = ReviewGrade.EASY,
            wasGuess = false,
            dwellSeconds = 3,
            nextIn = "4d"
        )
        val wrong = correct.copy(correct = false, grade = ReviewGrade.AGAIN)
        assertTrue(correct.correct)
        assertEquals(ReviewGrade.EASY, correct.grade)
        assertEquals("Easy", studyGradeTag(correct.grade))
        assertEquals(ReviewGrade.AGAIN, wrong.grade)
    }

    @Test
    fun emptySessionCopyAdmitsTheSettingIsGlobal() {
        assertTrue(
            "the empty-state button must say it applies to all papers",
            screen.contains("R.string.r2_study_enable_new_cards_all_papers")
        )
        assertTrue(
            "a short note must explain the global scope",
            screen.contains("R.string.r2_study_enable_new_cards_all_papers_note")
        )
        assertTrue("the old paper-local wording must be gone", !screen.contains("Text(stringResource(R.string.introduce_new_cards))"))
    }
}
