package com.mcqapp

import com.mcqapp.domain.Attempt
import com.mcqapp.domain.ExclusionMode
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestType
import com.mcqapp.domain.Trends
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendsTest {

    private fun attempt(
        id: Long,
        paperId: String,
        title: String,
        score: Double,
        maxScore: Double,
        finishedAt: Long,
        type: String = TestType.RANDOM.name,
        requested: Int = 20,
        exclusion: String = ExclusionMode.NONE.name,
        scope: String = ScopeKey.wholePaper(paperId),
        variant: String? = null,
        categoryIds: String = ""
    ) = Attempt(
        id = id,
        paperId = paperId,
        title = title,
        totalQuestions = 20,
        correctCount = 0,
        wrongCount = 0,
        skippedCount = 0,
        score = score,
        maxScore = maxScore,
        durationSeconds = 0,
        finishedAt = finishedAt,
        testType = type,
        requestedCount = requested,
        scopeKey = scope,
        scopeCategoryIds = categoryIds,
        samplingVersion = TestPlan.SAMPLING_VERSION,
        exclusionMode = exclusion,
        variant = variant
    )

    @Test
    fun groupsByPaperAndFamilyWithLatestBestAndDelta() {
        val attempts = listOf(
            attempt(1, "p1", "Paper One", 4.0, 10.0, 1000),
            attempt(2, "p1", "Paper One", 9.0, 10.0, 3000),
            attempt(3, "p1", "Paper One", 6.0, 10.0, 2000),
            attempt(4, "p2", "Paper Two", 5.0, 5.0, 1500)
        )
        val trends = Trends.perFamily(attempts)
        assertEquals(2, trends.size)
        val p1 = trends.single { it.paperId == "p1" }
        assertEquals(3, p1.attempts)
        assertEquals(90.0, p1.latestPercent, 0.0001)
        assertEquals(90.0, p1.bestPercent, 0.0001)
        assertEquals(50.0, p1.deltaPoints, 0.0001)
        val p2 = trends.single { it.paperId == "p2" }
        assertEquals(1, p2.attempts)
        assertEquals(0.0, p2.deltaPoints, 0.0001)
    }

    @Test
    fun emptyAttemptsYieldNoTrends() {
        assertEquals(emptyList<Trends.FamilyTrend>(), Trends.perFamily(emptyList()))
    }

    @Test
    fun negativeDeltaMeansRegression() {
        val attempts = listOf(
            attempt(1, "p1", "Paper", 8.0, 10.0, 1000),
            attempt(2, "p1", "Paper", 5.0, 10.0, 2000)
        )
        val trend = Trends.perFamily(attempts).single()
        assertEquals(-30.0, trend.deltaPoints, 0.0001)
        assertEquals(80.0, trend.bestPercent, 0.0001)
    }

    @Test
    fun practiceIsNeverMixedIntoTheMockLine() {
        val attempts = listOf(
            attempt(1, "p1", "Paper", 8.0, 10.0, 1000),
            attempt(2, "p1", "Paper", 5.0, 10.0, 2000),
            attempt(3, "p1", "Paper", 2.0, 10.0, 3000, type = TestType.WEAKEST_TOPICS.name)
        )
        val trends = Trends.perFamily(attempts)
        assertEquals(2, trends.size)
        assertEquals(2, trends.single { it.type == TestType.RANDOM }.attempts)
        assertEquals(1, trends.single { it.type == TestType.WEAKEST_TOPICS }.attempts)
    }

    @Test
    fun strictAndFreshSamplesKeepSeparateLines() {
        val attempts = listOf(
            attempt(1, "p1", "Paper", 8.0, 10.0, 1000),
            attempt(
                2, "p1", "Paper", 6.0, 10.0, 2000,
                exclusion = ExclusionMode.RECENT_3.name
            )
        )
        val trends = Trends.perFamily(attempts)
        assertEquals(2, trends.size)
        assertEquals(1, trends.count { it.freshSample })
        assertNotEquals(trends[0].key, trends[1].key)
    }

    @Test
    fun differentTopicScopesProduceSeparateLines() {
        val scopeA = ScopeKey.ofExpanded(listOf("c1"))
        val scopeB = ScopeKey.ofExpanded(listOf("c2"))
        val attempts = listOf(
            attempt(1, "p1", "Paper", 4.0, 10.0, 1000, scope = scopeA),
            attempt(2, "p1", "Paper", 6.0, 10.0, 2000, scope = scopeA),
            attempt(3, "p1", "Paper", 8.0, 10.0, 3000, scope = scopeB)
        )
        val trends = Trends.perFamily(attempts)
        assertEquals(2, trends.size)
        val lineA = trends.single { it.scopeKey == scopeA }
        val lineB = trends.single { it.scopeKey == scopeB }
        assertEquals(2, lineA.attempts)
        assertEquals(1, lineB.attempts)
        assertNotEquals(lineA.key, lineB.key)
        // Trend math is unchanged: each line still reports its own figures.
        assertEquals(60.0, lineA.latestPercent, 0.0001)
        assertEquals(20.0, lineA.deltaPoints, 0.0001)
        assertEquals(80.0, lineB.latestPercent, 0.0001)
    }

    @Test
    fun differentVariantsProduceSeparateLinesThatCarryTheVariant() {
        val attempts = listOf(
            attempt(1, "p1", "Paper", 4.0, 10.0, 1000, type = TestType.TAG.name, variant = "polity"),
            attempt(2, "p1", "Paper", 6.0, 10.0, 2000, type = TestType.TAG.name, variant = "history"),
            attempt(3, "p1", "Paper", 8.0, 10.0, 3000, type = TestType.TAG.name)
        )
        val trends = Trends.perFamily(attempts)
        assertEquals(3, trends.size)
        assertEquals(setOf("polity", "history", null), trends.map { it.variant }.toSet())
        assertEquals(3, trends.map { it.key }.toSet().size)
        trends.forEach { assertEquals(TestType.TAG, it.type) }
    }

    @Test
    fun theLineReportsWhetherItsScopeIsTheWholePaper() {
        val whole = Trends.perFamily(
            listOf(attempt(1, "p1", "Paper", 5.0, 10.0, 1000))
        ).single()
        val topics = Trends.perFamily(
            listOf(attempt(1, "p1", "Paper", 5.0, 10.0, 1000, scope = ScopeKey.ofExpanded(listOf("c1"))))
        ).single()
        assertTrue(whole.isWholePaper)
        assertFalse(topics.isWholePaper)
        assertEquals(ScopeKey.wholePaper("p1"), whole.scopeKey)
    }

    @Test
    fun differentTopicScopesProduceDifferentLabelSuffixes() {
        val scopeA = ScopeKey.ofExpanded(listOf("c1"))
        val scopeB = ScopeKey.ofExpanded(listOf("c2"))
        fun trendOf(scope: String, ids: String) = Trends.perFamily(
            listOf(attempt(1, "p1", "Paper", 5.0, 10.0, 1000, scope = scope, categoryIds = ids))
        ).single()
        val lineA = trendOf(scopeA, "c1")
        val lineB = trendOf(scopeB, "c2")

        // The label appends this suffix after "topics:", so two topic scopes
        // of the same paper/type/count can no longer read as identical cards.
        assertEquals("c1", lineA.scopeSuffix())
        assertEquals("c2", lineB.scopeSuffix())
        assertNotEquals(lineA.scopeSuffix(), lineB.scopeSuffix())
    }

    @Test
    fun aWholePaperLineKeepsItsPlainScopeLabel() {
        val whole = Trends.perFamily(
            listOf(attempt(1, "p1", "Paper", 5.0, 10.0, 1000))
        ).single()
        assertEquals(null, whole.scopeSuffix())
    }

    @Test
    fun variantIsNormalizedInBothTheKeyAndTheLabel() {
        fun trendOf(variant: String?) = Trends.perFamily(
            listOf(attempt(1, "p1", "Paper", 5.0, 10.0, 1000, type = TestType.TAG.name, variant = variant))
        ).single()
        val padded = trendOf(" polity ")
        val plain = trendOf("polity")

        assertEquals("polity", padded.variant)
        assertEquals(padded.variant, plain.variant)
        assertEquals(padded.key, plain.key)
    }

    @Test
    fun aLegacyAttemptNeverGetsATrendLine() {
        val attempts = listOf(
            attempt(1, "p1", "Paper", 8.0, 10.0, 1000, type = ""),
            attempt(2, "p1", "Paper", 5.0, 10.0, 2000, type = "")
        )
        assertEquals(emptyList<Trends.FamilyTrend>(), Trends.perFamily(attempts))
    }
}
