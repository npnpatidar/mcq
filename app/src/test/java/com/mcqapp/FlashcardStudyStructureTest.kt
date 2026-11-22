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
    fun checkCanNeverAutoGradeAKeylessQuestion() {
        val check = functionBody(viewModel, "fun check() {")
        // autoGrade reads an empty selection (or a selection against an empty
        // key) as wrong and would propose AGAIN. A card and an ungraded import
        // both have no key, so Check must refuse the whole self-graded class.
        assertTrue(
            "check() must return for a self-graded question before it proposes a grade",
            check.contains("if (!current.canAutoGrade) return")
        )
        assertTrue(
            "the guard must run before autoGrade is called",
            check.indexOf("if (!current.canAutoGrade) return") < check.indexOf("Study.autoGrade(")
        )
        assertFalse(
            "the guard must not be card-only: a keyless MCQ is self-graded too",
            check.contains("if (question.isFlashcard) return")
        )
    }

    @Test
    fun aSelfGradedQuestionNeverDeclaresAGuess() {
        val setGuess = functionBody(viewModel, "fun setGuess(guessing: Boolean) {")
        assertTrue(
            "setGuess() must refuse a self-graded question, or a hidden switch could still be set",
            setGuess.contains("if (current.selfGraded) return")
        )
    }

    @Test
    fun gradeWritesTheSelfGradedRowForEveryKeylessQuestion() {
        val grade = functionBody(viewModel, "fun grade(grade: ReviewGrade) {")
        listOf(
            // No correctness: there is no key to be right about.
            "wasCorrect = if (selfGraded) null else isCorrect(question, current.selection)",
            // No guess: the question was never declared one.
            "wasGuess = if (selfGraded) null else false",
            // Nothing was skipped: the prompt itself was studied.
            "wasSkipped = if (selfGraded) false else current.selection.isEmpty()",
            // No app proposal and no app grade — the learner chose it.
            "proposedGrade = null",
            "gradeSource = com.mcqapp.domain.GradeSource.CHOSEN"
        ).forEach { line ->
            assertTrue("grade() must write '$line'", grade.contains(line))
        }
        assertTrue(
            "a self-graded grade must still advance the summary tally",
            grade.contains("advanceStats(grade, selfGraded = if (selfGraded) 1 else 0)")
        )
        // The row decision must read the key, not the option count.
        assertTrue(
            "self-graded must be decided from the answer key",
            grade.contains("val selfGraded = current.selfGraded")
        )
        assertFalse(
            "a card-only decision would leave a keyless MCQ auto-graded",
            grade.contains("val card = question.isFlashcard")
        )
    }

    @Test
    fun aSelfGradedGradeIsIgnoredBeforeTheReveal() {
        val grade = functionBody(viewModel, "fun grade(grade: ReviewGrade) {")
        val guard = "if (current.selfGraded && !current.revealed) return"
        assertTrue(
            "grade() must refuse a self-graded question the learner has not been shown",
            grade.contains(guard)
        )
        // Behind the existing single-flight claim, and before any write. MCQ
        // grading must keep its old shape: only a self-graded question needs
        // the reveal.
        assertTrue(
            "the reveal guard must follow the grading single-flight",
            grade.indexOf("if (current.grading) return") < grade.indexOf(guard)
        )
        assertTrue(
            "the reveal guard must run before the review is persisted",
            grade.indexOf(guard) < grade.indexOf("recordStudyReview(")
        )
        assertFalse(
            "keyed MCQ grading must not gain a reveal requirement",
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
    fun theSelfGradedFooterIsShowAnswerThenTheFourSelfGrades() {
        val footer = functionBody(screen, "private fun SimplifiedFooter(")
        val start = footer.indexOf("} else if (state.selfGraded) {")
        assertTrue(
            "the footer has no self-graded branch (a keyless MCQ would fall into Check)",
            start >= 0
        )
        assertFalse(
            "the branch must not be card-only: a keyless MCQ is self-graded too",
            footer.contains("} else if (state.isFlashcard) {")
        )
        val end = footer.indexOf("} else if (!state.revealed) {", start)
        assertTrue("the self-graded branch has no end", end > start)
        val selfGradedBranch = footer.substring(start, end)

        // Before reveal: one Show answer button, wired to the reveal path.
        assertTrue("Show answer must reveal", selfGradedBranch.contains("viewModel.reveal()"))
        assertTrue("the answer button must be labelled", selfGradedBranch.contains("R.string.show_answer"))
        // After reveal: the same four buttons the classic footer uses, grading
        // straight through — no Next and no change-grade step.
        assertTrue("the question must grade with the shared buttons", selfGradedBranch.contains("GradeButtons("))
        assertTrue("the grade must persist and advance", selfGradedBranch.contains("viewModel.grade(it)"))
        assertFalse("a self-graded question has no Check", selfGradedBranch.contains("viewModel.check()"))
        assertFalse("a self-graded question has no guess switch", selfGradedBranch.contains("study-guess"))
        // These used to be asserted through the "study-next"/"study-change-grade"
        // tags, which live inside StagedResultActions — a branch that never
        // calls that helper passes for the wrong reason. Assert the real rule:
        // Simplified self-graded grades in one tap and never stages. Its result
        // card owns Change grade, so the toggle must not be consulted here.
        assertFalse(
            "Simplified self-graded must never stage a grade",
            selfGradedBranch.contains("stageGrade")
        )
        assertFalse(
            "Simplified self-graded has no staged result actions",
            selfGradedBranch.contains("StagedResultActions")
        )
        assertTrue(
            "one tap commits straight through grade()",
            selfGradedBranch.contains("viewModel.grade(it)")
        )
        assertFalse("a self-graded question has no Next step", selfGradedBranch.contains("study-next"))
        assertFalse(
            "a self-graded question has no change-grade control",
            selfGradedBranch.contains("study-change-grade")
        )

        // The stuck state must stay reachable: the footer checks it first, so a
        // question whose grade persisted before a failed pull still gets Retry.
        assertTrue(
            "Retry must come before the self-graded branch or it is unreachable",
            footer.indexOf("viewModel.loadNext()") in 1 until start
        )
        assertTrue(footer.contains("R.string.retry"))
    }

    @Test
    fun aSelfGradedQuestionShowsNoResultCard() {
        assertTrue(
            "the simplified result card must be refused for every self-graded question",
            screen.contains("if (state.revealed && !state.selfGraded) {")
        )
    }

    @Test
    fun aKeylessRevealCarriesNoVerdictTint() {
        // The option's colour, glyph and spoken state all read the same
        // key-known flag, so no branch can mark an option right or wrong when
        // the app has no key.
        assertTrue(
            "the key must gate the option verdict",
            screen.contains("val keyKnown = question.correctOptionIds.isNotEmpty()")
        )
        assertTrue("the tint must be gated", screen.contains("val verdict = revealed && keyKnown"))
        assertTrue(
            "the spoken state must know there is no key",
            screen.contains("studyOptionStateRes(revealed, isCorrect, isSelected, keyKnown)")
        )
        assertFalse(
            "no ungated 'correct' tint may survive",
            screen.contains("revealed && isCorrect -> MaterialTheme.colorScheme.primaryContainer")
        )
        assertFalse(
            "no ungated 'wrong' tint may survive",
            screen.contains("revealed && isSelected -> MaterialTheme.colorScheme.errorContainer")
        )
        assertFalse(
            "no ungated verdict glyph may survive",
            screen.contains("revealed && isCorrect -> \"✓\"")
        )
    }

    @Test
    fun theGradingHintAndClockFollowTheKeyNotTheShape() {
        assertTrue(
            "the one-time MCQ hint must be suppressed for every self-graded question",
            viewModel.contains("!gradingHintDismissed && !selfGraded &&")
        )
        assertTrue(
            "the clock must stay neutral for every self-graded question",
            screen.contains("if (state.correctnessOnlyGrading || state.selfGraded) {")
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
            "a rotation must not re-speak a reveal the learner already heard",
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
