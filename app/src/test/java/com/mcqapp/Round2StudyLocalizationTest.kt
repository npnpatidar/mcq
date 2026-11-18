package com.mcqapp

import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.Study
import com.mcqapp.ui.study.studyDwellBandRes
import com.mcqapp.ui.study.studyGradeLabelRes
import com.mcqapp.ui.study.studyGradeTag
import com.mcqapp.ui.study.studyOptionStateRes
import com.mcqapp.ui.study.studyVerdictRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S2/S3 copy: the grade, option-state and verdict seams are pure mappings to
 * string resources, and every new `r2_study_*` copy exists in both locales with
 * matching placeholders. Runs on the JVM with no Android resources loaded.
 */
class Round2StudyLocalizationTest {

    private val screen by lazy {
        File("src/main/java/com/mcqapp/ui/study/StudyScreen.kt").readText()
    }

    @Test
    fun gradeLabelsComeFromResourcesAndTagsStayStable() {
        assertEquals(R.string.grade_again, studyGradeLabelRes(ReviewGrade.AGAIN))
        assertEquals(R.string.grade_hard, studyGradeLabelRes(ReviewGrade.HARD))
        assertEquals(R.string.grade_good, studyGradeLabelRes(ReviewGrade.GOOD))
        assertEquals(R.string.grade_easy, studyGradeLabelRes(ReviewGrade.EASY))
        // The UI tests pin the English tag even under another locale.
        assertEquals("Again", studyGradeTag(ReviewGrade.AGAIN))
        assertEquals("Hard", studyGradeTag(ReviewGrade.HARD))
        assertEquals("Good", studyGradeTag(ReviewGrade.GOOD))
        assertEquals("Easy", studyGradeTag(ReviewGrade.EASY))
    }

    @Test
    fun optionStatesCoverEveryBranchAndNoneIsAnEnglishLiteral() {
        assertEquals(
            R.string.r2_study_option_correct,
            studyOptionStateRes(revealed = true, isCorrect = true, isSelected = false)
        )
        assertEquals(
            R.string.r2_study_option_selected_wrong,
            studyOptionStateRes(revealed = true, isCorrect = false, isSelected = true)
        )
        assertEquals(
            R.string.r2_study_option_selected,
            studyOptionStateRes(revealed = false, isCorrect = false, isSelected = true)
        )
        assertEquals(
            R.string.r2_study_option_not_selected,
            studyOptionStateRes(revealed = false, isCorrect = false, isSelected = false)
        )
        // Revealed correct wins over a selection; a wrong pick is not "Selected".
        assertEquals(
            R.string.r2_study_option_correct,
            studyOptionStateRes(revealed = true, isCorrect = true, isSelected = true)
        )
        assertFalse("options must not carry English state literals", screen.contains("\"Correct answer\""))
        assertFalse("options must not carry English state literals", screen.contains("\"Selected, wrong answer\""))
        assertFalse("options must not carry English state literals", screen.contains("\"Not selected\""))
    }

    @Test
    fun verdictAndDwellCuesAreLocalizable() {
        assertEquals(R.string.r2_study_verdict_correct, studyVerdictRes(true))
        assertEquals(R.string.r2_study_verdict_incorrect, studyVerdictRes(false))
        assertEquals(R.string.r2_study_band_neutral, studyDwellBandRes(Study.DwellBand.NEUTRAL))
        assertEquals(R.string.r2_study_band_easy, studyDwellBandRes(Study.DwellBand.EASY))
        assertEquals(R.string.r2_study_band_good, studyDwellBandRes(Study.DwellBand.GOOD))
        assertEquals(R.string.r2_study_band_hard, studyDwellBandRes(Study.DwellBand.HARD))
    }

    @Test
    fun gradeButtonsTakeAResourceIdNotALiteralLabel() {
        assertTrue(
            "GradeButton must resolve its label from a @StringRes id",
            Regex("""private fun GradeButton\(\s*\n\s*@StringRes labelRes: Int,""").containsMatchIn(screen)
        )
        assertFalse("no literal labels may be passed to GradeButton", screen.contains("GradeButton(\""))
        assertTrue(
            "the result card must derive its grade label from the resource seam",
            screen.contains("studyGradeLabelRes(result.grade)")
        )
        assertFalse(
            "the enum name must not leak into result copy",
            screen.contains("result.grade.name.lowercase()")
        )
    }

    @Test
    fun bothLocalesDeclareTheSamePrefixedStringsAndPlaceholders() {
        val en = entries(File("src/main/res/values/strings_round2_study.xml").readText())
        val hi = entries(File("src/main/res/values-hi/strings_round2_study_hi.xml").readText())
        assertTrue("English round-2 study copy is missing", en.isNotEmpty())
        assertEquals("locale name sets differ", en.keys, hi.keys)
        assertTrue("all new names must use the r2_study_ prefix", en.keys.all { it.startsWith("r2_study_") })
        for (name in en.keys) {
            val english = en.getValue(name)
            val hindi = hi.getValue(name)
            assertTrue("$name is empty in English", english.isNotBlank())
            assertTrue("$name is empty in Hindi", hindi.isNotBlank())
            assertTrue("$name is not translated", english != hindi)
            assertEquals(
                "$name placeholders differ between locales",
                specifiers(english),
                specifiers(hindi)
            )
        }
    }

    private fun entries(xml: String): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2].trim() }

    private fun specifiers(value: String): List<String> =
        Regex("""%\d+\$[a-zA-Z]""").findAll(value).map { it.value }.toList()
}
