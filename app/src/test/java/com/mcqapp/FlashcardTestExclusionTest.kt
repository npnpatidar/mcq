package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.AttemptMetadata
import com.mcqapp.domain.BlueprintSection
import com.mcqapp.domain.ExclusionMode
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TagNormalizer
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestType
import com.mcqapp.domain.isFlashcard
import com.mcqapp.domain.signals.TestSelector
import com.mcqapp.domain.stats.ExamGroup
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A flashcard — a question with no options — is study-only and must never
 * reach a test: not the random/legacy session order, not a signal selector's
 * pool, not an ungraded (blocking) count, not a passage's block, not a tag or
 * bookmark pool, and not a setup-sheet pool count.
 *
 * Robolectric needs a real SQLite driver, so this runs on x86_64 CI only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlashcardTestExclusionTest {

    /** A wedge names its frame instead of hanging a CI shard. */
    @get:Rule
    val watchdog = HangWatchdogRule(seconds = 120)

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    private val paperId = "p1"

    /**
     * The testable (multiple-choice) ids of `p1`; everything else in the paper
     * is a flashcard.
     */
    private val mcqIds = setOf("q-mc1", "q-mc2", "q-mc3", "q-b1", "q-b2")

    /** Every flashcard id, including the two members of a card-only passage. */
    private val cardIds = setOf(
        "card1", "card2", "card3", "card-b1", "card-p1", "card-p2"
    )

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        seedMixedPaper()
        seedCardOnlyPaper()
    }

    @After
    fun tearDown() = runBlocking {
        repository.resetSchedulerConfig()
        db.close()
    }

    private fun mcq(
        id: String,
        categoryId: String,
        passageId: String? = null,
        hasKey: Boolean = true,
        tags: List<String> = emptyList()
    ) = Question(
        id = id,
        categoryId = categoryId,
        text = "Q $id",
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = if (hasKey) setOf("$id-a") else emptySet(),
        tags = tags
    ).let { if (passageId == null) it else it.copy(passageId = passageId) }

    private fun card(
        id: String,
        categoryId: String,
        passageId: String? = null,
        tags: List<String> = emptyList()
    ) = Question(
        id = id,
        categoryId = categoryId,
        text = "Front $id",
        options = emptyList(),
        correctOptionIds = emptySet(),
        explanation = "Back $id",
        tags = tags
    ).let {
        assertTrue("the seed must be a flashcard", it.isFlashcard)
        if (passageId == null) it else it.copy(passageId = passageId)
    }

    /**
     * `p1` holds both kinds in every shape that matters: standalone questions
     * in two categories, a passage of 2 MCQs + 1 flashcard, and two standalone
     * flashcards. Two MCQs have no answer key; so do all the flashcards.
     */
    private suspend fun seedMixedPaper() {
        repository.ensurePaperAndCategory(paperId, "Paper", "c1", "One")
        repository.ensurePaperAndCategory(paperId, "Paper", "c2", "Two")
        repository.saveQuestion(mcq("q-mc1", "c1", tags = listOf("Biology")))
        repository.saveQuestion(mcq("q-mc2", "c1", hasKey = false))
        repository.saveQuestion(mcq("q-mc3", "c2", hasKey = false, tags = listOf("Biology")))
        repository.saveQuestion(mcq("q-b1", "c1", passageId = "p-mixed"))
        repository.saveQuestion(mcq("q-b2", "c1", passageId = "p-mixed"))
        repository.saveQuestion(card("card1", "c1", tags = listOf("Biology")))
        repository.saveQuestion(card("card2", "c1"))
        repository.saveQuestion(card("card3", "c2"))
        repository.saveQuestion(card("card-b1", "c1", passageId = "p-mixed"))
    }

    /**
     * `p2` has no standalone question at all: one passage of 2 MCQs + 1 card
     * and one passage holding a single card. The smallest testable block is
     * therefore 2; counting the card-only passage would answer 1.
     */
    private suspend fun seedCardOnlyPaper() {
        repository.ensurePaperAndCategory("p2", "Paper 2", "c4", "Four")
        repository.saveQuestion(mcq("m1", "c4", passageId = "pb-mixed"))
        repository.saveQuestion(mcq("m2", "c4", passageId = "pb-mixed"))
        repository.saveQuestion(card("card-p1", "c4", passageId = "pb-mixed"))
        repository.saveQuestion(card("card-p2", "c4", passageId = "pb-cards"))
    }

    // ------------------------------------------------------------------
    // Session order: random, legacy and drill all read this one path.
    // ------------------------------------------------------------------

    @Test
    fun theWholePaperOrderServesOnlyMultipleChoiceQuestions() = runBlocking {
        val order = repository.getSessionOrderForPaper(paperId)
        assertEquals(mcqIds, order.idsInOrder().toSet())
        assertTrue(
            "no flashcard may enter a test order",
            order.idsInOrder().none { it in cardIds }
        )
        // The mixed passage keeps its two testable members: the block is not
        // dropped because one member is a flashcard.
        val ids = order.idsInOrder()
        assertTrue(ids.containsAll(listOf("q-b1", "q-b2")))
    }

    @Test
    fun aScopedOrderServesOnlyMultipleChoiceQuestions() = runBlocking {
        val order = repository.getSessionOrderForCategories(paperId, listOf("c1"))
        assertEquals(setOf("q-mc1", "q-mc2", "q-b1", "q-b2"), order.idsInOrder().toSet())
    }

    // ------------------------------------------------------------------
    // Signal selectors.
    // ------------------------------------------------------------------

    @Test
    fun theSignalRowsOfAPaperAreMultipleChoiceOnly() = runBlocking {
        val signals = repository.getQuestionSignals(paperId, emptyList())
        assertEquals(mcqIds, signals.map { it.questionId }.toSet())
        val scoped = repository.getQuestionSignals(paperId, listOf("c2"))
        assertEquals(setOf("q-mc3"), scoped.map { it.questionId }.toSet())
    }

    @Test
    fun everySignalTypeSelectsOnlyMultipleChoiceQuestions() = runBlocking {
        val signals = repository.getQuestionSignals(paperId, emptyList())
        // The custom pool deliberately names every question, flashcards
        // included: even then a card must not be selected.
        val everyId = mcqIds + cardIds
        val signalTypes = TestType.entries.filter { it.usesSignalSelector && it != TestType.BLUEPRINT }
        for (type in signalTypes) {
            val plan = TestPlan(
                paperId = paperId,
                type = type,
                count = 5,
                scopeKey = ScopeKey.wholePaper(paperId),
                variant = when (type) {
                    TestType.TAG -> TagNormalizer.fold("Biology")
                    TestType.AUTHORED_DIFFICULTY -> "MEDIUM"
                    TestType.FIXED_PANEL -> "1"
                    else -> null
                }
            )
            val selection = TestSelector.select(plan, signals, customPool = everyId)
            assertNotNull("$type has a selector", selection)
            assertTrue(
                "$type selected a flashcard: ${selection!!.ids}",
                selection.ids.none { it in cardIds }
            )
        }
    }

    // ------------------------------------------------------------------
    // Counts that gate the run.
    // ------------------------------------------------------------------

    @Test
    fun ungradedCountsIgnoreFlashcards() = runBlocking {
        // q-mc2 and q-mc3 have no key; every flashcard also has none, and a
        // card must never trip the blocking "no answer key" dialog.
        assertEquals(2, repository.countUngradedForPaper(paperId))
        assertEquals(1, repository.countUngradedForCategories(listOf("c1")))
        assertEquals(1, repository.countUngradedForCategories(listOf("c2")))
    }

    @Test
    fun theSmallestTestableBlockIgnoresACardOnlyPassage() = runBlocking {
        // p2: passage of 2 MCQs + 1 card, and a passage holding one card.
        assertEquals(2, repository.getMinBlockSize("p2", emptyList()))
        assertEquals(2, repository.getMinBlockSize("p2", listOf("c4")))
        // The order agrees: the mixed passage carries its two members only.
        val ids = repository.getSessionOrderForPaper("p2").idsInOrder()
        assertEquals(listOf("m1", "m2"), ids)
    }

    @Test
    fun testableCountsExcludeFlashcardsAndAgreeAcrossScopes() = runBlocking {
        assertEquals(5, repository.countTestableForPaper(paperId))
        // The empty selection is the whole paper in the test path.
        assertEquals(5, repository.countTestableForCategories(paperId, emptyList()))
        assertEquals(4, repository.countTestableForCategories(paperId, listOf("c1")))
        assertEquals(1, repository.countTestableForCategories(paperId, listOf("c2")))
        val byCategory = repository.testableCategoryCountsForPaper(paperId)
        assertEquals(mapOf("c1" to 4, "c2" to 1), byCategory)
        assertEquals(5, byCategory.values.sum())
        // The raw counts (library badges, study) still include the cards.
        assertEquals(9, repository.getPaper(paperId)!!.totalQuestions)
    }

    @Test
    fun tagCountsAndTagPoolsIgnoreFlashcards() = runBlocking {
        val folded = TagNormalizer.fold("Biology")
        val counts = repository.tagCountsForScope(paperId, emptyList())
        assertEquals(listOf(TagCountExpectation(folded, 2)), counts.map {
            TagCountExpectation(it.tagFolded, it.count)
        })
        assertEquals(
            setOf("q-mc1", "q-mc3"),
            repository.questionIdsForTagInScope(paperId, emptyList(), folded).toSet()
        )
    }

    @Test
    fun studyTagPoolIncludesFlashcardsAndItsCountAgrees() = runBlocking {
        val folded = TagNormalizer.fold("Biology")
        // Study serves flashcards, so its tag picker counts the card too —
        // q-mc1, q-mc3 and card1 all carry Biology.
        val studyCounts = repository.tagCountsForStudyScope(paperId, emptyList())
        assertEquals(
            listOf(TagCountExpectation(folded, 3)),
            studyCounts.map { TagCountExpectation(it.tagFolded, it.count) }
        )
        assertEquals(
            setOf("q-mc1", "q-mc3", "card1"),
            repository.questionIdsForStudyTagInScope(paperId, emptyList(), folded).toSet()
        )
        // A category-scoped sitting uses the same gate for count and pool.
        assertEquals(
            listOf(TagCountExpectation(folded, 2)),
            repository.tagCountsForStudyScope(paperId, listOf("c1"))
                .map { TagCountExpectation(it.tagFolded, it.count) }
        )
        assertEquals(
            setOf("q-mc1", "card1"),
            repository.questionIdsForStudyTagInScope(paperId, listOf("c1"), folded).toSet()
        )
        // Test's tag filter is untouched: still multiple-choice only.
        assertEquals(
            setOf("q-mc1", "q-mc3"),
            repository.questionIdsForTagInScope(paperId, emptyList(), folded).toSet()
        )
    }

    @Test
    fun aBookmarkedFlashcardNeverEntersTheBookmarkedPool() = runBlocking {
        repository.toggleBookmark("q-mc1")
        repository.toggleBookmark("card1")
        assertEquals(
            setOf("q-mc1"),
            repository.bookmarkedIdsInScope(paperId, emptyList()).toSet()
        )
        // The raw count still sees the card: it is what tells the setup sheet
        // an empty pool from a pool whose bookmarks are all flashcards.
        assertEquals(2, repository.bookmarkedCountInScope(paperId, emptyList()))
        assertEquals(2, repository.bookmarkedCountInScope(paperId, listOf("c1")))
    }

    @Test
    fun theRawTagPoolCountSeesFlashcardsSoTheSheetCanExplainThem() = runBlocking {
        val folded = TagNormalizer.fold("Biology")
        // q-mc1 and q-mc3 are testable; card1 carries the tag too.
        assertEquals(
            setOf("q-mc1", "q-mc3"),
            repository.questionIdsForTagInScope(paperId, emptyList(), folded).toSet()
        )
        assertEquals(3, repository.questionCountForTagInScope(paperId, emptyList(), folded))
        assertEquals(
            1,
            repository.questionCountForTagInScope(paperId, listOf("c2"), folded)
        )
    }

    // ------------------------------------------------------------------
    // Mistakes: a converted question keeps its old result rows.
    // ------------------------------------------------------------------

    /** Saves one attempt in which every passed question is answered wrong. */
    private suspend fun wrongAttempt(vararg questions: Question) {
        repository.saveAttempt(
            paperId = paperId,
            paperTitle = "Paper",
            questions = questions.toList(),
            selections = questions.associate { it.id to setOf(it.options.last().id) },
            negativeMarking = 0.0,
            durationSeconds = 60,
            finishedAt = System.currentTimeMillis()
        )
    }

    @Test
    fun aCardConvertedFromAMultipleChoiceQuestionIsNotAMistake() = runBlocking {
        val converted = mcq("q-conv", "c1")
        val kept = mcq("q-keep", "c1")
        repository.saveQuestion(converted)
        repository.saveQuestion(kept)
        wrongAttempt(converted, kept)
        // While both are multiple choice they are real mistakes.
        assertEquals(
            setOf("q-conv", "q-keep"),
            repository.getMistakenQuestionOrder(paperId).idsInOrder().toSet()
        )
        assertEquals(2, repository.getMistakeCounts()[paperId])
        assertEquals(2, repository.getScopedMistakeCount(paperId, listOf("c1")))

        // Converted to a flashcard: the old wrong result remains, but the card
        // is study-only and must not be served or counted as a mistake. The
        // multiple-choice question in the same scope still is one.
        repository.saveQuestion(card("q-conv", "c1"))

        assertEquals(
            "the paper's mistakes order must serve only the testable mistake",
            listOf("q-keep"),
            repository.getMistakenQuestionOrder(paperId).idsInOrder()
        )
        assertEquals(
            "the scoped mistakes order must serve only the testable mistake",
            listOf("q-keep"),
            repository.getMistakenQuestionOrder(paperId, listOf("c1")).idsInOrder()
        )
        assertEquals(
            "the whole-paper mistakes count (the sheet's pool) counts one",
            1,
            repository.getMistakeCounts()[paperId]
        )
        assertEquals(
            "the scoped mistakes count counts one",
            1,
            repository.getScopedMistakeCount(paperId, listOf("c1"))
        )
        assertEquals(
            listOf("q-keep"),
            db.attemptDao().getMistakenQuestionIdsForPaper(paperId)
        )
    }

    @Test
    fun aKeylessMultipleChoiceQuestionWithAnOldWrongAttemptIsStillAMistake() = runBlocking {
        val keyed = mcq("q-keyless", "c1")
        repository.saveQuestion(keyed)
        wrongAttempt(keyed)
        // The key is removed later; the question still has options, so the
        // historical wrong answer still counts as a mistake.
        repository.saveQuestion(keyed.copy(correctOptionIds = emptySet()))

        assertEquals(
            "the option predicate must not become an answer-key predicate",
            listOf("q-keyless"),
            repository.getMistakenQuestionOrder(paperId).idsInOrder()
        )
        assertEquals(1, repository.getMistakeCounts()[paperId])
        assertEquals(1, repository.getScopedMistakeCount(paperId, listOf("c1")))
    }

    // ------------------------------------------------------------------
    // Frozen sets: a panel and a blueprint run their testable subset.
    // ------------------------------------------------------------------

    @Test
    fun aPanelWithFlashcardsRunsOnlyItsTestableSubset() = runBlocking {
        val panelId = repository.createPanel(
            paperId,
            "Mixed panel",
            listOf("q-mc1", "q-mc2", "card1", "card2")
        )
        val panel = repository.panel(panelId)!!
        assertEquals("the library keeps the frozen size", 4, panel.size)

        val signals = repository.getQuestionSignals(paperId, emptyList())
        val selection = TestSelector.select(
            TestPlan(
                paperId = paperId,
                type = TestType.FIXED_PANEL,
                count = panel.size,
                scopeKey = ScopeKey.wholePaper(paperId),
                variant = panelId.toString()
            ),
            signals,
            customPool = panel.questionIds.toSet()
        )!!
        assertEquals(setOf("q-mc1", "q-mc2"), selection.ids.toSet())
    }

    @Test
    fun aBlueprintWithFlashcardsPoolsAndRunsTheTestableSubset() = runBlocking {
        val blueprintId = repository.createBlueprint(
            paperId,
            "Mixed blueprint",
            listOf(
                BlueprintSection("c1", parts = 1),
                BlueprintSection("c2", parts = 1)
            )
        )
        val blueprint = repository.blueprint(blueprintId)!!
        // Every shown section pool is a supply a test can draw from.
        val views = repository.blueprintSectionViews(blueprint)
        assertEquals(
            mapOf("c1" to 4, "c2" to 1),
            views.associate { it.categoryId to it.pool }
        )

        val planBlueprint = repository.buildPlanBlueprint(blueprint)!!
        val signals = repository.getQuestionSignals(paperId, planBlueprint.categoryIds)
        assertEquals(mcqIds, signals.map { it.questionId }.toSet())

        val preview = TestSelector.preview(
            TestPlan(
                paperId = paperId,
                categoryIds = planBlueprint.categoryIds,
                type = TestType.BLUEPRINT,
                count = 5,
                scopeKey = ScopeKey.ofExpanded(planBlueprint.categoryIds),
                variant = blueprintId.toString(),
                blueprint = planBlueprint
            ),
            signals
        )
        // 5 testable rows in the union; the 4 flashcards cannot be a supply.
        assertEquals(5, preview.capacity)
        assertTrue(preview.ids.none { it in cardIds })
    }

    @Test
    fun anAllFlashcardScopeHasContentButNothingTestable() = runBlocking {
        repository.ensurePaperAndCategory("p3", "Cards", "c5", "Five")
        repository.saveQuestion(card("card-only-1", "c5"))
        repository.saveQuestion(card("card-only-2", "c5"))

        assertEquals(2, repository.getPaper("p3")!!.totalQuestions)
        assertEquals(0, repository.countTestableForPaper("p3"))
        assertEquals(
            0,
            repository.countTestableForCategories("p3", listOf("c5"))
        )
        assertFalse(
            "the order of a flashcards-only scope is empty",
            repository.getSessionOrderForPaper("p3").idsInOrder().isNotEmpty()
        )
    }

    @Test
    fun theExamPopulationCountsOnlyTestableQuestions() = runBlocking {
        // A representative attempt needs a full-length paper: 20 testable
        // questions plus 3 flashcards that can never be sampled.
        repository.ensurePaperAndCategory("p4", "Exam paper", "c7", "Seven")
        val questions = (1..20).map { mcq("e$it", "c7") }
        questions.forEach { repository.saveQuestion(it) }
        repeat(3) { repository.saveQuestion(card("ecard$it", "c7")) }
        assertEquals(23, repository.getPaper("p4")!!.totalQuestions)

        repository.saveAttempt(
            paperId = "p4",
            paperTitle = "Exam paper",
            questions = questions,
            selections = questions.associate { it.id to setOf("${it.id}-a") },
            negativeMarking = 0.0,
            durationSeconds = 300,
            finishedAt = 1_000L,
            metadata = AttemptMetadata(
                testType = TestType.RANDOM.name,
                requestedCount = 20,
                scopeKey = ScopeKey.wholePaper("p4"),
                samplingVersion = TestPlan.SAMPLING_VERSION,
                exclusionMode = ExclusionMode.NONE.name
            )
        )

        val mock = repository.getTestProgress("p4", now = 2_000L)
            .exams
            .getValue(ExamGroup.MOCK_STRICT)
            .first()
        // The finite-population correction takes the 20 questions a test can
        // sample, not the paper's raw 23 — counting the cards would make the
        // margin over-confident.
        assertEquals(20, mock.population)
    }

    /** Equality helper: the tag count's identity and how many questions carry it. */
    private data class TagCountExpectation(val folded: String, val count: Int)
}
