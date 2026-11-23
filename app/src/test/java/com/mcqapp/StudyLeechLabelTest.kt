package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.StudyStepLabel
import com.mcqapp.domain.crossedLeechThreshold
import com.mcqapp.domain.studyStepLabel
import com.mcqapp.ui.study.studyStepLabelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The leech-crossing and short-term-step labels are pure data: which grade
 * first flags a card, and whether a due step is a first exposure or a recovery
 * from a lapse. Both come from the card's own state, never from the pull reason.
 */
class StudyLeechLabelTest {

    private fun card(
        leech: Boolean = false,
        kind: CardStateKind = CardStateKind.REVIEW
    ) = CardState(questionId = "q", leech = leech, state = kind)

    @Test
    fun theNoticeFiresOnlyOnTheGradeThatFirstFlagsTheCard() {
        val flagged = card(leech = true)
        val plain = card(leech = false)
        assertTrue(crossedLeechThreshold(plain, flagged))
        assertTrue("a caller with no stored state treats the card as unflagged", crossedLeechThreshold(null, flagged))
        assertFalse(crossedLeechThreshold(flagged, flagged))
        assertFalse(crossedLeechThreshold(plain, plain))
        assertFalse("a lapse below the threshold says nothing", crossedLeechThreshold(null, plain))
    }

    @Test
    fun theStepLabelComesFromTheCardsFswsState() {
        assertEquals(StudyStepLabel.RELEARNING_AFTER_LAPSE, card(kind = CardStateKind.RELEARNING).studyStepLabel())
        assertEquals(StudyStepLabel.LEARNING_STEP, card(kind = CardStateKind.LEARNING).studyStepLabel())
        assertNull(card(kind = CardStateKind.REVIEW).studyStepLabel())
        assertNull(card(kind = CardStateKind.NEW).studyStepLabel())
    }

    @Test
    fun bothStepLabelsResolveToLocalizedCopy() {
        assertEquals(R.string.study_polish_relearning_step, studyStepLabelRes(StudyStepLabel.RELEARNING_AFTER_LAPSE))
        assertEquals(R.string.learning_step, studyStepLabelRes(StudyStepLabel.LEARNING_STEP))
        assertEquals(R.string.learning_step, studyStepLabelRes(null))
    }

    @Test
    fun theNewCopyExistsInBothLocalesAndIsTranslated() {
        val en = entries("src/main/res/values/strings_study_polish.xml")
        val hi = entries("src/main/res/values-hi/strings_study_polish_hi.xml")
        assertEquals("the two files must mirror each other", en.keys, hi.keys)
        assertTrue("expected every new name to be declared", en.keys.containsAll(listOf(
            "study_polish_leech_notice",
            "study_polish_relearning_step",
            "study_polish_take_break",
            "study_polish_on_break",
            "study_polish_resume",
            "study_polish_drill_one_pass"
        )))
        for (name in en.keys) {
            assertTrue("$name is empty in English", en.getValue(name).isNotBlank())
            assertTrue("$name is empty in Hindi", hi.getValue(name).isNotBlank())
            assertNotEquals("$name is not translated", en.getValue(name), hi.getValue(name))
        }
        assertEquals("Now flagged as tricky", en.getValue("study_polish_leech_notice"))
    }

    private fun entries(path: String): Map<String, String> =
        Regex("""<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
}
