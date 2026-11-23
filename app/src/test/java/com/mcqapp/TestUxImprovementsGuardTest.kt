package com.mcqapp

import com.mcqapp.domain.Attempt
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Paper
import com.mcqapp.domain.RecommendedAction
import com.mcqapp.domain.ReportRow
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestReportBuilder
import com.mcqapp.domain.TestReportInput
import com.mcqapp.domain.TestStatistics
import com.mcqapp.domain.TestType
import com.mcqapp.domain.Trends
import com.mcqapp.ui.library.examConditionLines
import com.mcqapp.ui.library.groupedTestTypes
import com.mcqapp.ui.library.negativeMarkingLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wave-1 test/exam findings. The rules are pure so they run on the ARM64 host;
 * the wiring that cannot be rendered here is checked structurally in the same
 * style as [UxRulingsStructureTest].
 *
 * BEHAVIOUR: a clean practice or mock run still offers a next step, the resume
 * route carries only identity fields, and one shared noise rule decides every
 * "is this change real" label.
 */
class TestUxImprovementsGuardTest {

    // --- Finding 1: exam conditions before Start ---

    @Test
    fun examConditionsStateEveryModeAndStrictWinsOverPractice() {
        val lines = examConditionLines(
            strictMode = true,
            autoAdvance = true,
            practiceMode = true,
            shuffleQuestions = true,
            negativeMarking = 1.0 / 3.0
        )
        val resources = lines.map { it.resId }
        assertTrue(R.string.test_ux_condition_strict in resources)
        assertTrue(R.string.test_ux_condition_auto_advance in resources)
        // Strict suppresses live feedback even when practice mode is on.
        assertTrue(R.string.test_ux_condition_not_practice in resources)
        assertFalse(R.string.test_ux_condition_practice in resources)
        assertTrue(R.string.test_ux_condition_shuffle in resources)
        assertEquals("1/3", lines.single { it.arg != null }.arg)
    }

    @Test
    fun examConditionsStateTheNegativeCaseTooAndUseANoMarkingLine() {
        val off = examConditionLines(false, false, false, false, 0.0)
        val resources = off.map { it.resId }
        assertTrue(R.string.test_ux_condition_not_strict in resources)
        assertTrue(R.string.test_ux_condition_no_auto_advance in resources)
        assertTrue(R.string.test_ux_condition_no_shuffle in resources)
        assertTrue(R.string.test_ux_condition_no_negative in resources)
        assertTrue("Every condition must be worded, never blank", off.all { it.arg == null })
    }

    @Test
    fun markingLabelCoversTheCommonFractions() {
        assertEquals("1/3", negativeMarkingLabel(0.333))
        assertEquals("1/4", negativeMarkingLabel(0.25))
        assertEquals("1/2", negativeMarkingLabel(0.5))
        assertEquals("30%", negativeMarkingLabel(0.30))
        assertEquals("", negativeMarkingLabel(0.0))
    }

    // --- Finding 2: 17 chips become two labelled groups ---

    @Test
    fun allStartableTypesAreGroupedExactlyOnce() {
        val groups = groupedTestTypes()
        assertEquals(3, groups.mocks.size)
        assertEquals(14, groups.practice.size)
        assertEquals(17, groups.mocks.size + groups.practice.size)
        assertEquals(
            "no type lost or duplicated",
            groups.mocks.toSet() + groups.practice.toSet(),
            com.mcqapp.domain.TestSetup.STARTABLE_TYPES.toSet()
        )
        assertTrue(groups.mocks.containsAll(listOf(TestType.RANDOM, TestType.BALANCED, TestType.BLUEPRINT)))
        assertTrue(groups.practice.contains(TestType.TAG))
    }

    // --- Findings 5 & 6: one margin rule and one wording ---

    @Test
    fun noiseBandIsTheSumOfBothMarginsNotJustTheCurrentOne() {
        // A 9-point change is beyond a 5-point current margin, but inside the
        // combined 5 + 6 band. The old header said "outside", the trend card
        // said "noise"; both now call this one helper.
        assertTrue(TestStatistics.deltaWithinNoise(9.0, 5.0, 6.0))
        assertTrue("the boundary itself counts as noise", TestStatistics.deltaWithinNoise(9.0, 5.0, 4.0))
        assertFalse(TestStatistics.deltaWithinNoise(9.0, 5.0, 3.0))
        assertTrue(TestStatistics.deltaWithinNoise(-4.0, 3.0, 2.0))
        assertFalse(TestStatistics.deltaWithinNoise(-6.0, 3.0, 2.0))
        assertTrue(TestStatistics.deltaWithinNoise(0.0, null, null))
    }

    private fun trendAttempt(id: Long, score: Double, max: Double, at: Long) = Attempt(
        id = id,
        paperId = "p1",
        title = "Paper",
        totalQuestions = 20,
        correctCount = 0,
        wrongCount = 0,
        skippedCount = 0,
        score = score,
        maxScore = max,
        durationSeconds = 0,
        finishedAt = at,
        testType = TestType.RANDOM.name,
        requestedCount = 20,
        scopeKey = ScopeKey.wholePaper("p1"),
        samplingVersion = TestPlan.SAMPLING_VERSION
    )

    @Test
    fun historyTrendUsesTheSharedNoiseRuleForItsFirstToLatestChange() {
        // 48% -> 52% over 20-question attempts is inside the combined Wilson
        // band, so the History card must not claim a direction.
        val trend = Trends.perFamily(
            listOf(
                trendAttempt(1, 9.6, 20.0, 1000),
                trendAttempt(2, 10.4, 20.0, 2000)
            )
        ).single()
        assertEquals(4.0, trend.deltaPoints, 0.0001)
        assertTrue("small change is noise", trend.withinNoise)
        assertFalse("no arrow may claim a direction", !trend.withinNoise)

        val clear = Trends.perFamily(
            listOf(
                trendAttempt(3, 4.0, 20.0, 1000),
                trendAttempt(4, 18.0, 20.0, 2000)
            )
        ).single()
        assertTrue("large change is real", !clear.withinNoise)
    }

    // --- Finding 8: a clean run still has a next step ---

    private fun cleanReport(type: TestType): com.mcqapp.domain.TestReport = TestReportBuilder.build(
        TestReportInput(
            attempt = Attempt(
                id = 1L,
                paperId = "p1",
                title = "Paper",
                totalQuestions = 20,
                correctCount = 20,
                wrongCount = 0,
                skippedCount = 0,
                score = 20.0,
                maxScore = 20.0,
                durationSeconds = 600,
                finishedAt = 1000L,
                testType = type.name,
                requestedCount = 20,
                scopeCategoryIds = "c1",
                scopeKey = ScopeKey.wholePaper("p1"),
                samplingVersion = TestPlan.SAMPLING_VERSION
            ),
            rows = List(20) { index ->
                ReportRow(
                    questionId = "q$index",
                    categoryTitle = "Polity",
                    currentCategoryId = "c1",
                    dwellSeconds = 30,
                    isCorrect = true,
                    graded = true,
                    answered = true
                )
            },
            paper = Paper(
                id = "p1",
                title = "Paper",
                categories = listOf(CategoryNode("c1", "p1", "Polity", null, questionCount = 40))
            )
        )
    )

    @Test
    fun aCleanPracticeRunStillGetsAFallbackAction() {
        val report = cleanReport(TestType.MIXED)
        assertEquals(0, report.score.wrong)
        val fallback = report.actions.single()
        assertEquals(RecommendedAction.Rule.KeepGoing, fallback.rule)
        assertEquals(RecommendedAction.Kind.TEST, fallback.kind)
        assertEquals(TestType.MIXED, fallback.testType)
        assertTrue(fallback.categoryIds == listOf("c1"))
    }

    @Test
    fun aCleanMockRunKeepsItsRandomMockFallback() {
        val report = cleanReport(TestType.RANDOM)
        val fallback = report.actions.single()
        assertEquals(RecommendedAction.Rule.KeepGoing, fallback.rule)
        assertEquals(TestType.RANDOM, fallback.testType)
    }

    // --- Finding 3: resume route preserves the session identity ---

    @Test
    fun unfinishedTestRouteIsBuiltFromTheIdentityFieldsNotTheAnswerPayload() {
        // The route itself uses android.net.Uri.encode, so its exact string is
        // pinned in TestUxDbTest (Robolectric). Here the contract is that the
        // builder reads identity fields only — never selections or question ids.
        val routeBuilder = File("src/main/java/com/mcqapp/ui/common/UnfinishedTestRoute.kt").readText()
        listOf(
            "snapshot.paperId", "snapshot.categoryIds", "snapshot.mistakesOnly",
            "snapshot.isDrill", "snapshot.drillRequested", "snapshot.testType",
            "snapshot.requestedCount", "snapshot.exclusionMode", "snapshot.variant"
        ).forEach { field -> assertTrue("route drops $field", field in routeBuilder) }
        assertFalse("answers must not travel in the route", "snapshot.selections" in routeBuilder)
        assertFalse("frozen ids must not travel in the route", "snapshot.selectedQuestionIds" in routeBuilder)
    }

    // --- Both locales, isolated from strings.xml ---

    private fun names(path: String): Set<String> =
        Regex("""<string name="([a-z0-9_]+)"[^>]*>""")
            .findAll(File("src/main/res/$path").readText())
            .map { it.groupValues[1] }.toSet()

    @Test
    fun testUxCopyIsCompleteInBothLocalesAndNotInStringsXml() {
        val en = names("values/strings_test_ux.xml")
        val hi = names("values-hi/strings_test_ux_hi.xml")
        assertTrue("Hindi is missing ${en - hi}", en == hi)
        assertTrue("the new block must not be empty", en.size >= 30)
        val core = names("values/strings.xml")
        assertTrue(
            "new keys must stay in the isolated file",
            (en intersect core).isEmpty()
        )
    }

    @Test
    fun allEightFindingsAreWiredIntoTheirScreens() {
        val setup = File("src/main/java/com/mcqapp/ui/library/TestSetupSheet.kt").readText()
        assertTrue("exam conditions", "test_ux_conditions_title" in setup)
        assertTrue("settings shortcut", "onOpenSettings" in setup)
        assertTrue("grouped chips", "groupedTestTypes" in setup)
        assertTrue("replacement notice", "test_ux_replace_notice" in setup)

        val test = File("src/main/java/com/mcqapp/ui/test/TestSessionScreen.kt").readText()
        assertTrue("strict jump grid", "test-jump-grid-toggle" in test)
        assertTrue("replacement banner", "test-replace-notice" in test)

        val results = File("src/main/java/com/mcqapp/ui/results/ResultsScreen.kt").readText()
        assertTrue("next-step hero", "results-next-step" in results)
        assertTrue("one margin helper", "TestStatistics.deltaWithinNoise" in results)
        assertTrue("passage above stem", "result-passage" in results)

        val history = File("src/main/java/com/mcqapp/ui/history/HistoryScreen.kt").readText()
        assertTrue("since-first label", "test_ux_history_delta_line" in history)
        assertTrue("unfinished home", "UnfinishedTestCard" in history)

        val library = File("src/main/java/com/mcqapp/ui/library/LibraryScreen.kt").readText()
        assertTrue("unfinished home", "UnfinishedTestCard" in library)
        assertTrue(
            "both screens use one tagged card",
            "unfinished-test-card" in File("src/main/java/com/mcqapp/ui/common/UnfinishedTestCard.kt").readText()
        )
    }
}
