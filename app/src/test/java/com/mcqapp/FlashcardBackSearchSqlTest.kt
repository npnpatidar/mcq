package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.data.repository.BrowseListFilter
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionSearch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The back is searchable in the SQL prefilters, which read the stored
 * Kotlin-folded `questions.backTextFolded`:
 *
 * - cross-paper search ([McqRepository.searchGlobal]) in ALL and QUESTIONS,
 * - Browse's own filter (list, count and Select all share one scope),
 * - and a back edit re-folds the column, so hits follow the edit.
 *
 * Robolectric needs a real SQLite driver, so this runs on x86_64 CI only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlashcardBackSearchSqlTest {

    /** A wedge names its frame instead of hanging a CI shard. */
    @get:Rule
    val watchdog = HangWatchdogRule(seconds = 120)

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    private val paperId = "p1"

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = McqRepository(db, context)
        repository.ensurePaperAndCategory(paperId, "Paper", "c1", "One")
    }

    @After
    fun tearDown() = runBlocking {
        repository.resetSchedulerConfig()
        db.close()
    }

    private fun card(id: String, front: String, back: String) = Question(
        id = id,
        categoryId = "c1",
        text = front,
        options = emptyList(),
        correctOptionIds = emptySet(),
        explanation = back
    )

    private fun mcq(id: String, front: String, back: String) = Question(
        id = id,
        categoryId = "c1",
        text = front,
        options = listOf(
            com.mcqapp.domain.QuestionOption("a", "One"),
            com.mcqapp.domain.QuestionOption("b", "Two")
        ),
        correctOptionIds = setOf("a"),
        explanation = back
    )

    private fun search(query: String, scope: QuestionSearch.Scope) = runBlocking {
        repository.searchGlobal(query, scope).map { it.question.id }
    }

    private fun browseIds(
        query: String = "",
        filter: BrowseListFilter = BrowseListFilter.ALL
    ) = runBlocking {
        val scope = McqRepository.BrowseScope.of(paperId, query, filter, null)
        repository.browseFilteredIds(scope)
    }

    @Test
    fun aCardsAnswerIsFoundAcrossPapers() = runBlocking {
        repository.saveQuestion(card("card1", "What is released?", "Oxygen and protons"))
        assertEquals(
            listOf("card1"),
            search("oxygen and protons", QuestionSearch.Scope.ALL)
        )
        assertEquals(
            listOf("card1"),
            search("oxygen and protons", QuestionSearch.Scope.QUESTION)
        )
        assertEquals(
            "an answer is not an option",
            emptyList<String>(),
            search("oxygen and protons", QuestionSearch.Scope.OPTIONS)
        )
    }

    @Test
    fun anExplanationIsFoundForAMultipleChoiceQuestionToo() = runBlocking {
        repository.saveQuestion(mcq("q1", "Which gas?", "Chlorophyll absorbs red light"))
        assertEquals(listOf("q1"), search("chlorophyll absorbs", QuestionSearch.Scope.ALL))
    }

    /**
     * The folded column is what the prefilter reads: a query differing in case
     * on a non-ASCII letter must find the row, and a row whose folded column
     * disagrees with its raw text must be judged by the folded one. The twins
     * go in through the DAO so the inconsistency is not repaired at write time.
     */
    @Test
    fun theBackPrefilterReadsTheFoldedColumn() = runBlocking {
        db.questionDao().upsertAll(
            listOf(
                QuestionEntity(
                    id = "card-folded", categoryId = "c1",
                    text = "x", searchText = "front", searchTextFolded = "front",
                    tags = "", tagsFolded = "",
                    explanation = "",
                    backText = "CAFÉ culture", backTextFolded = "café culture"
                ),
                QuestionEntity(
                    id = "card-raw", categoryId = "c1",
                    text = "x", searchText = "front", searchTextFolded = "front",
                    tags = "", tagsFolded = "",
                    explanation = "",
                    // Deliberately inconsistent: nothing any writer produces.
                    backText = "café culture", backTextFolded = "zzz"
                )
            )
        )
        val found = db.questionDao().search("café", "café", 10, 0).map { it.id }
        assertTrue("the folded back column must be read: $found", "card-folded" in found)
        assertFalse("this is the mutant's tell: $found", "card-raw" in found)
    }

    @Test
    fun browseFindsACardByItsAnswer() = runBlocking {
        repository.saveQuestion(card("card1", "What is released?", "Oxygen and protons"))
        repository.saveQuestion(mcq("q1", "Which gas?", "Chlorophyll absorbs red light"))
        assertEquals(listOf("card1"), browseIds(query = "oxygen and protons"))
        assertEquals(listOf("q1"), browseIds(query = "chlorophyll"))
        // The count and Select-all paths share the same scope, so they agree
        // with the list.
        val scope = McqRepository.BrowseScope.of(
            paperId, "oxygen and protons", BrowseListFilter.ALL, null
        )
        assertEquals(listOf("card1"), repository.browseFilteredIds(scope))
        assertEquals(1, repository.browseFilteredCount(scope))
    }

    @Test
    fun aBackEditRefreshesTheHits() = runBlocking {
        repository.saveQuestion(card("card1", "What is released?", "Oxygen"))
        assertEquals(emptyList<String>(), search("nitrogen", QuestionSearch.Scope.ALL))
        // The writer recomputes backText/backTextFolded, so the new answer is
        // searchable and the old one is not.
        repository.saveQuestion(card("card1", "What is released?", "Nitrogen"))
        assertEquals(listOf("card1"), search("nitrogen", QuestionSearch.Scope.ALL))
        assertEquals(emptyList<String>(), search("oxygen", QuestionSearch.Scope.ALL))
        assertEquals(listOf("card1"), browseIds(query = "nitrogen"))
    }

    /**
     * A card legitimately has no answer key, so the renamed chip — "No answer
     * key (multiple choice)" — must not list every card in the paper.
     */
    @Test
    fun theNoAnswerKeyChipExcludesFlashcards() = runBlocking {
        repository.saveQuestion(mcq("q-keyed", "Keyed?", "back"))
        repository.saveQuestion(
            Question(
                id = "q-keyless", categoryId = "c1", text = "Keyless?",
                options = listOf(
                    com.mcqapp.domain.QuestionOption("a", "One"),
                    com.mcqapp.domain.QuestionOption("b", "Two")
                ),
                correctOptionIds = emptySet(),
                explanation = "back"
            )
        )
        repository.saveQuestion(card("card1", "Card front", "Card answer"))
        assertEquals(
            listOf("q-keyless"),
            browseIds(filter = BrowseListFilter.NO_ANSWER)
        )
        val scope = McqRepository.BrowseScope.of(
            paperId, "", BrowseListFilter.NO_ANSWER, null
        )
        assertEquals(1, repository.browseFilteredCount(scope))
    }

    /**
     * A passage's member row labels a card by its own options, not by the key
     * count: a keyless multiple-choice question also has zero keys.
     */
    @Test
    fun aPassageMemberRefCarriesItsOptionCount() = runBlocking {
        repository.savePassage(
            com.mcqapp.domain.Passage(id = "pa", categoryId = "c1", title = "Passage")
        )
        repository.saveQuestion(card("card1", "Card front", "Card answer").copy(passageId = "pa"))
        repository.saveQuestion(mcq("q1", "Which gas?", "back").copy(passageId = "pa"))

        // The member list pages through `pagingRefsByPassage`, which shares this
        // projection; the ordered read proves the column is selected and mapped.
        val byId = db.questionDao().getRefsByPassageOrdered("pa").associateBy { it.id }
        assertEquals("a card has no options", 0, byId.getValue("card1").optionCount)
        assertEquals("a multiple-choice row has options", 2, byId.getValue("q1").optionCount)
    }
}
