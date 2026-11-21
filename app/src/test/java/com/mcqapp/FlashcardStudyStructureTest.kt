package com.mcqapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-shape guards for a flashcard in Study: the card gets Show answer then
 * the four self-grading buttons, a heading with real heading semantics, an
 * announcement, and no correctness claim anywhere. Pure JVM, so it catches a
 * regression on the ARM64 host where the DB-backed session tests cannot run.
 */
class FlashcardStudyStructureTest {

    private val viewModel by lazy {
        File("src/main/java/com/mcqapp/ui/study/StudyViewModel.kt").readText()
    }
    private val screen by lazy {
        File("src/main/java/com/mcqapp/ui/study/StudyScreen.kt").readText()
    }
    private val englishXml by lazy {
        File("src/main/res/values/strings_round2_study.xml").readText()
    }
    private val hindiXml by lazy {
        File("src/main/res/values-hi/strings_round2_study_hi.xml").readText()
    }

    /** The body of one function, from its `fun` line to the next top-level close. */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature is missing", start >= 0)
        val end = source.indexOf("\n    }\n", start).let {
            if (it < 0) source.indexOf("\n}\n", start) else it
        }
        assertTrue("$signature has no closing brace", end > start)
        return source.substring(start, end)
    }

    @Test
    fun checkCanNeverAutoGradeACard() {
        val check = functionBody(viewModel, "fun check() {")
        // autoGrade reads an empty selection as wrong and would propose AGAIN;
        // a card has no options to select, so Check must refuse it outright.
        assertTrue(
            "check() must return for a flashcard before it proposes a grade",
            check.contains("if (question.isFlashcard) return")
        )
        assertTrue(
            "the guard must run before autoGrade is called",
            check.indexOf("if (question.isFlashcard) return") < check.indexOf("Study.autoGrade(")
        )
    }

    @Test
    fun aCardNeverDeclaresAGuess() {
        val setGuess = functionBody(viewModel, "fun setGuess(guessing: Boolean) {")
        assertTrue(
            "setGuess() must refuse a flashcard, or a hidden switch could still be set",
            setGuess.contains("if (current.isFlashcard) return")
        )
    }

    @Test
    fun gradeWritesTheCardRowAsSelfGraded() {
        val grade = functionBody(viewModel, "fun grade(grade: ReviewGrade) {")
        listOf(
            // No correctness: there is no answer to be right about.
            "wasCorrect = if (card) null else isCorrect(question, current.selection)",
            // No guess: the card was never declared one.
            "wasGuess = if (card) null else false",
            // Nothing was skipped: the prompt itself was studied.
            "wasSkipped = if (card) false else current.selection.isEmpty()",
            // No app proposal and no app grade — the learner chose it.
            "proposedGrade = null",
            "gradeSource = com.mcqapp.domain.GradeSource.CHOSEN"
        ).forEach { line ->
            assertTrue("grade() must write '$line'", grade.contains(line))
        }
        assertTrue(
            "a flashcard grade must still advance the per-card tally",
            grade.contains("advanceStats(grade, flashcards = if (card) 1 else 0)")
        )
    }

    @Test
    fun aCardGradeIsIgnoredBeforeTheReveal() {
        val grade = functionBody(viewModel, "fun grade(grade: ReviewGrade) {")
        val guard = "if (question.isFlashcard && !current.revealed) return"
        assertTrue(
            "grade() must refuse a card the learner has not been shown",
            grade.contains(guard)
        )
        // Behind the existing single-flight claim, and before any write. MCQ
        // grading must keep its old shape: only a card needs the reveal.
        assertTrue(
            "the reveal guard must follow the grading single-flight",
            grade.indexOf("if (current.grading) return") < grade.indexOf(guard)
        )
        assertTrue(
            "the reveal guard must run before the review is persisted",
            grade.indexOf(guard) < grade.indexOf("recordStudyReview(")
        )
        assertFalse(
            "MCQ grading must not gain a reveal requirement",
            grade.contains("if (!current.revealed) return")
        )
    }

    @Test
    fun theClassicFooterGradesACardWithTheSameWrite() {
        // The classic footer lives inline in StudyBody; the Simplified one is
        // its own function below. Its GradeError marks the start of the fixed
        // footer, and the next KDoc marks the end of StudyBody.
        val start = screen.indexOf("GradeError(state.gradeError)")
        assertTrue("the classic footer is missing", start >= 0)
        val end = screen.indexOf("/**", start)
        assertTrue("the classic footer has no end", end > start)
        val classic = screen.substring(start, end)

        // Show answer first, then the same four self-grades — no Check, no Next
        // and nothing to change afterwards.
        assertTrue("the card's answer button must be labelled", classic.contains("R.string.show_answer"))
        assertTrue("Show answer must reveal the card", classic.contains("viewModel.reveal()"))
        assertTrue("the classic footer must offer the four grades", classic.contains("GradeButtons("))
        assertTrue("the classic grade must persist through the same path", classic.contains("viewModel.grade(it)"))
        assertTrue(
            "Show answer must precede the grades",
            classic.indexOf("R.string.show_answer") < classic.indexOf("GradeButtons(")
        )
        assertFalse("classic mode has no Check", classic.contains("viewModel.check()"))
        assertFalse("a card has no Next step", classic.contains("study-next"))
        assertFalse("a card has no change-grade control", classic.contains("study-change-grade"))
    }

    @Test
    fun theFlashcardFooterIsShowAnswerThenTheFourSelfGrades() {
        val footer = functionBody(screen, "private fun SimplifiedFooter(")
        val start = footer.indexOf("} else if (state.isFlashcard) {")
        assertTrue("the footer has no flashcard branch", start >= 0)
        val end = footer.indexOf("} else if (!state.revealed) {", start)
        assertTrue("the flashcard branch has no end", end > start)
        val cardBranch = footer.substring(start, end)

        // Before reveal: one Show answer button, wired to the reveal path.
        assertTrue("the card's Show answer must reveal", cardBranch.contains("viewModel.reveal()"))
        assertTrue("the card's answer button must be labelled", cardBranch.contains("R.string.show_answer"))
        // After reveal: the same four buttons the classic footer uses, grading
        // straight through — no Next and no change-grade step.
        assertTrue("the card must grade with the shared buttons", cardBranch.contains("GradeButtons("))
        assertTrue("the card grade must persist and advance", cardBranch.contains("viewModel.grade(it)"))
        assertFalse("a card has no Check", cardBranch.contains("viewModel.check()"))
        assertFalse("a card has no guess switch", cardBranch.contains("study-guess"))
        assertFalse("a card has no Next step", cardBranch.contains("study-next"))
        assertFalse("a card has no change-grade control", cardBranch.contains("study-change-grade"))

        // The stuck state must stay reachable: the footer checks it first, so a
        // card whose grade persisted before a failed pull still gets Retry.
        assertTrue(
            "Retry must come before the card branch or it is unreachable",
            footer.indexOf("viewModel.loadNext()") in 1 until start
        )
        assertTrue(footer.contains("R.string.retry"))
    }

    @Test
    fun aCardShowsNoResultCard() {
        assertTrue(
            "the simplified result card must be refused for a card",
            screen.contains("if (state.revealed && !state.isFlashcard) {")
        )
    }

    @Test
    fun theAnswerHeadingHasRealHeadingSemantics() {
        assertTrue(
            "the revealed card must be labelled as an Answer",
            screen.contains("R.string.r2_study_answer_heading")
        )
        assertTrue(
            "the answer heading must carry the heading() semantics role",
            screen.contains("Modifier.semantics { heading() }")
        )
        // A card's image is its answer, not an explanation.
        assertTrue(
            "the card's answer image must have its own content description",
            screen.contains("R.string.r2_study_answer_image")
        )
    }

    @Test
    fun theRevealIsAnnouncedOnceThroughTheAnnouncer() {
        assertTrue(
            "a revealed card must be spoken, keyed on the card and its reveal",
            screen.contains("LaunchedEffect(state.current?.questionId, state.revealed)")
        )
        assertTrue(
            "the spoken text must be the localized answer heading",
            screen.contains("val answerHeadingMessage = stringResource(R.string.r2_study_answer_heading)")
        )
        assertTrue(
            "the reveal must go through LocalAnnouncer",
            screen.contains("announcer.announce(answerHeadingMessage)")
        )
        assertTrue(
            "a recreation must not re-speak a reveal the learner already heard",
            screen.contains(
                "var answeredRevealSpoken by rememberSaveable { mutableStateOf(false) }"
            ) && screen.contains("} else if (!answeredRevealSpoken) {")
        )
        // Correct/Incorrect is spoken only by the verdict seam, which is keyed
        // on a result card. A flashcard never builds one (check() is guarded and
        // grade() writes its row directly), so the card path cannot speak it.
        assertTrue(
            "the only verdict speaker stays the null-guarded result effect",
            screen.contains("verdictMessage?.let { announcer.announce(it) }")
        )
        assertFalse(
            "the card path must not announce a verdict literal",
            screen.contains("announcer.announce(stringResource(R.string.r2_study_verdict")
        )
    }

    @Test
    fun theCardClockMakesNoBandClaim() {
        assertTrue(
            "a flashcard is graded by the learner, so the clock must stay neutral",
            screen.contains("if (state.correctnessOnlyGrading || state.isFlashcard) {")
        )
    }

    @Test
    fun theNewCopyExistsInBothLocalesAndDiffers() {
        listOf("r2_study_answer_heading", "r2_study_answer_image").forEach { name ->
            val en = value(englishXml, name)
            val hi = value(hindiXml, name)
            assertTrue("$name is missing from English", en.isNotBlank())
            assertTrue("$name is missing from Hindi", hi.isNotBlank())
            assertTrue("$name is not translated", en != hi)
            assertFalse("$name must carry no format arguments", en.contains("%"))
        }
    }

    @Test
    fun theTimeSplitCallsReadingTimeAnswers() {
        val en = File("src/main/res/values/strings.xml").readText()
        val hi = File("src/main/res/values-hi/strings_hi_4.xml").readText()
        assertTrue(
            "the split's second bucket is answer-reading time, for a card as much as an MCQ",
            value(en, "session_time_split").contains("Answers %2\$s")
        )
        assertFalse(
            "the bucket must no longer claim it is only explanation time",
            value(en, "session_time_split").contains("Explanations")
        )
        assertTrue(
            "the Hindi bucket is an answer too",
            value(hi, "session_time_split").contains("उत्तर %2\$s")
        )
    }

    private fun value(xml: String, name: String): String =
        Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim().orEmpty()
}
