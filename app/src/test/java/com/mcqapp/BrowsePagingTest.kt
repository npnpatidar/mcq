package com.mcqapp

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.QuestionEntity
import com.mcqapp.data.local.QuestionRef
import com.mcqapp.data.repository.BrowseListFilter
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Browse pages its refs through Room instead of materialising the whole paper.
 * These pin the paging list contract: the `(category order, question order)`
 * sequence is the same as the old whole-list read, and the chip/category/text
 * filters select the same rows the in-memory filters used to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowsePagingTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = McqRepository(db, context)
        runBlocking {
            db.paperDao().upsert(PaperEntity(id = "p1", title = "Paper"))
            db.categoryDao().upsert(CategoryEntity(id = "c1", paperId = "p1", title = "Alpha", sortOrder = 0))
            db.categoryDao().upsert(CategoryEntity(id = "c2", paperId = "p1", title = "Beta", sortOrder = 1))
        }
    }

    @After
    fun tearDown() = db.close()

    private fun save(id: String, categoryId: String, text: String, withKey: Boolean = true) =
        runBlocking {
            repository.saveQuestion(
                Question(
                    id = id,
                    categoryId = categoryId,
                    elements = listOf(ContentElement.TextElement(text)),
                    options = listOf(QuestionOption("a", "A")),
                    correctOptionIds = if (withKey) setOf("a") else emptySet()
                )
            )
        }

    private suspend fun source(
        query: String = "",
        filter: BrowseListFilter = BrowseListFilter.ALL,
        categoryFilterIds: Set<String>? = null
    ): PagingSource<Int, QuestionRef> = repository.browseRefsSource(
        McqRepository.BrowseScope.of(
            paperId = "p1",
            query = query,
            filter = filter,
            categoryFilterIds = categoryFilterIds
        )
    )

    /** Walks every page at a small size, crossing window boundaries. */
    private suspend fun walk(factory: suspend () -> PagingSource<Int, QuestionRef>): List<String> =
        walkPaging(2, factory) { key ->
            if (key == null) PagingSource.LoadParams.Refresh(null, 2, false)
            else PagingSource.LoadParams.Append(key, 2, false)
        }.map { it.id }

    @Test
    fun pagesPreserveTheCategoryAndQuestionOrder() = runBlocking {
        save("q3", "c1", "Gamma"); save("q1", "c1", "Alpha"); save("q2", "c1", "Beta")
        save("q5", "c2", "Epsilon"); save("q4", "c2", "Delta")
        assertEquals(listOf("q3", "q1", "q2", "q5", "q4"), walk { source() })
    }

    @Test
    fun textQueryKeepsMatchingRowsInOrder() = runBlocking {
        save("q1", "c1", "Alpha"); save("q2", "c1", "Beta"); save("q3", "c2", "Alpha beta")
        assertEquals(listOf("q1", "q3"), walk { source(query = "Alpha") })
    }

    @Test
    fun theNoAnswerChipKeepsOnlyKeylessQuestions() = runBlocking {
        save("q1", "c1", "Alpha", withKey = true)
        save("q2", "c1", "Beta", withKey = false)
        save("q3", "c2", "Gamma", withKey = false)
        assertEquals(listOf("q2", "q3"), walk { source(filter = BrowseListFilter.NO_ANSWER) })
    }

    @Test
    fun theCategoryFilterRestrictsToTheGivenCategories() = runBlocking {
        save("q1", "c1", "Alpha"); save("q2", "c2", "Beta")
        assertEquals(
            listOf("q1"),
            walk { source(categoryFilterIds = setOf("c1")) }
        )
    }

    @Test
    fun textQueryMatchesTagsAndOptionText() = runBlocking {
        // The search box promises "Text, tags, options". Matching only
        // questions.searchText silently missed the other two.
        repository.saveQuestion(
            Question(
                id = "q-tag",
                categoryId = "c1",
                elements = listOf(ContentElement.TextElement("Plain stem")),
                options = listOf(QuestionOption("a", "Nothing")),
                correctOptionIds = setOf("a"),
                tags = listOf("photosynthesis")
            )
        )
        repository.saveQuestion(
            Question(
                id = "q-option",
                categoryId = "c1",
                elements = listOf(ContentElement.TextElement("Another stem")),
                options = listOf(QuestionOption("a", "Mitochondrion")),
                correctOptionIds = setOf("a")
            )
        )
        save("q-stem", "c2", "Plain stem")

        assertEquals(listOf("q-tag"), walk { source(query = "photosynth") })
        assertEquals(listOf("q-option"), walk { source(query = "Mitochondr") })
        assertEquals(listOf("q-tag", "q-stem"), walk { source(query = "Plain stem") })
    }

    /**
     * SQL `LIKE` — and SQLite's own `lower()` — case-fold A–Z only, so a
     * query whose case differed from the stored text on a non-ASCII letter
     * rejected the row in every branch *before* the authoritative in-memory
     * filter (which folds with full Unicode) ever saw it, in browse as well
     * as in global search. Every branch now matches its Kotlin-folded column
     * (`searchTextFolded` on the question, its tags, the option, the
     * passage) against a Kotlin-lowercased query, so the SQL pass is as
     * case-insensitive as the in-memory filter.
     */
    @Test
    fun aNonAsciiCaseDifferenceStillFindsRowsInEveryBranch() = runBlocking {
        repository.saveQuestion(
            Question(
                id = "q-cafe-text",
                categoryId = "c1",
                elements = listOf(ContentElement.TextElement("CAFÉ central")),
                options = listOf(QuestionOption("a", "Nothing")),
                correctOptionIds = setOf("a")
            )
        )
        repository.saveQuestion(
            Question(
                id = "q-cafe-tag",
                categoryId = "c1",
                elements = listOf(ContentElement.TextElement("Plain stem")),
                options = listOf(QuestionOption("a", "Nothing")),
                correctOptionIds = setOf("a"),
                tags = listOf("CAFÉ Tag")
            )
        )
        repository.saveQuestion(
            Question(
                id = "q-cafe-option",
                categoryId = "c1",
                elements = listOf(ContentElement.TextElement("Another stem")),
                options = listOf(QuestionOption("a", "CAFÉ option")),
                correctOptionIds = setOf("a")
            )
        )

        val all = listOf("q-cafe-text", "q-cafe-tag", "q-cafe-option")
        assertEquals(all, walk { source(query = "café") })
        assertEquals(all, walk { source(query = "CAFÉ") })
        // The tag and option branches individually.
        assertEquals(listOf("q-cafe-tag"), walk { source(query = "café tag") })
        assertEquals(listOf("q-cafe-option"), walk { source(query = "café opt") })
        // And the count path agrees with the list path — the scope's size
        // would otherwise host the same veto one query lower.
        assertEquals(
            3,
            repository.browseFilteredCount(
                McqRepository.BrowseScope.of("p1", "café", BrowseListFilter.ALL, null)
            )
        )
    }

    @Test
    fun theNoExplanationChipMatchesTheShapesTheAppWrites() = runBlocking {
        // The editor stores `[]`; both importers store a blank text element.
        // The chip used to test `TRIM(explanation) = ''`, which matched
        // neither, so it was always empty.
        val base = QuestionEntity(
            id = "q-editor",
            categoryId = "c1",
            text = """[{"type":"text","content":"Has stem"}]""",
            searchText = "Has stem",
            searchTextFolded = "Has stem".lowercase(),
            tags = "",
            tagsFolded = "",
            explanation = "[]"
        )
        db.questionDao().upsertAll(
            listOf(
                base,
                base.copy(id = "q-import", explanation = """[{"type":"text","content":""}]"""),
                base.copy(id = "q-legacy-blank", explanation = ""),
                base.copy(id = "q-blank-space", explanation = "   "),
                base.copy(id = "q-has", explanation = """[{"type":"text","content":"Because"}]"""),
                base.copy(id = "q-legacy-text", explanation = "Plain reason")
            )
        )

        val walked = walk { source(filter = BrowseListFilter.NO_EXPLANATION) }
        assertEquals(listOf("q-editor", "q-import", "q-legacy-blank", "q-blank-space"), walked)
        // The count the list header reads must agree with the page walk.
        val scope = McqRepository.BrowseScope.of(
            paperId = "p1",
            query = "",
            filter = BrowseListFilter.NO_EXPLANATION,
            categoryFilterIds = null
        )
        assertEquals(walked.size, repository.browseFilteredCount(scope))
    }
}
