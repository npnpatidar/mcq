package com.mcqapp

import com.mcqapp.data.repository.AppLanguage
import com.mcqapp.data.repository.buildExamStatsProfile
import com.mcqapp.data.repository.examStatsLocale
import com.mcqapp.domain.Attempt
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.ExclusionMode
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestType
import com.mcqapp.domain.stats.ExamGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * Regression guard for the repository wiring that localizes exam trend labels.
 * The Stats agent added `locale` to ExamStatsBuilder.build; these tests prove
 * McqRepository resolves the selected in-app language with the same mechanism
 * StatsStore uses and threads it through the production build seam.
 *
 * Pure JVM — no Robolectric, no database, no new dependencies.
 */
class Round2ExamStatsLocaleTest {

    private val day = DayBoundary.DAY_MS

    private fun attempt(id: Long, score: Double, finishedAt: Long) = Attempt(
        id = id,
        paperId = "p1",
        title = "Paper p1",
        totalQuestions = 20,
        correctCount = 10,
        wrongCount = 5,
        skippedCount = 5,
        score = score,
        maxScore = 20.0,
        durationSeconds = 1200,
        finishedAt = finishedAt,
        testType = TestType.RANDOM.name,
        requestedCount = 20,
        scopeKey = ScopeKey.wholePaper("p1"),
        samplingVersion = TestPlan.SAMPLING_VERSION,
        exclusionMode = ExclusionMode.NONE.name,
        variant = null,
        compositionJson = ""
    )

    // Two attempts months apart force MONTHLY bucketing ("MMM yy"), where the
    // month name is locale-sensitive; a Hindi label must differ from English.
    private val attempts: List<Attempt>
        get() {
            val t0 = LocalDate.of(2025, 1, 1).toEpochDay() * day
            return listOf(
                attempt(1, 16.0, t0),
                attempt(2, 18.0, t0 + 200 * day)
            )
        }

    private fun trendLabel(locale: Locale): String =
        buildExamStatsProfile(attempts, ExamGroup.MOCK_STRICT, locale)
            .trend.first().label

    @Test
    fun hindiSelectionResolvesToHindiLocaleAndProducesHindiLabel() {
        val locale = examStatsLocale(AppLanguage.HINDI)
        assertEquals(Locale.forLanguageTag("hi"), locale)
        assertTrue("trend must have a label", trendLabel(Locale.ENGLISH).isNotEmpty())
        // Positive: the selected Hindi language changes the trend label.
        assertNotEquals(trendLabel(Locale.ENGLISH), trendLabel(locale))
    }

    @Test
    fun systemSelectionKeepsTheDeviceDefaultUntouched() {
        val system = examStatsLocale(AppLanguage.SYSTEM)
        assertEquals("SYSTEM must follow the device, not an override", Locale.getDefault(), system)
        // Negative: the SYSTEM-resolved locale yields the unchanged/default label.
        assertEquals(trendLabel(Locale.getDefault()), trendLabel(system))
    }

    @Test
    fun englishOverrideStaysEnglishRegardlessOfDeviceDefault() {
        assertEquals(Locale.forLanguageTag("en"), examStatsLocale(AppLanguage.ENGLISH))
        assertEquals(trendLabel(Locale.ENGLISH), trendLabel(examStatsLocale(AppLanguage.ENGLISH)))
    }
}
