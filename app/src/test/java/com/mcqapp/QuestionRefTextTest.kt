package com.mcqapp

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.local.QuestionRef
import com.mcqapp.data.repository.BrowseListFilter
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Passage
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A ref's `text` is the flattened stem a list row shows — never the elements
 * JSON. The passage screen rendered it verbatim, so every member row read
 * `[{"type":"text","text":"..."}]`, and the browse row's one-frame seat showed
 * the same. `questions.text` holds the JSON and `searchText` holds the readable
 * flattening, so every ref query has to alias `searchText` — these walk all of
 * them, including the paged ones, because one query left behind is one screen
 * showing markup at the reader.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuestionRefTextTest {

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
            db.categoryDao().upsert(
                CategoryEntity(id = "c1", paperId = "p1", title = "Alpha", sortOrder = 0)
            )
            repository.savePassage(Passage(id = "pa", categoryId = "c1", title = "Passage"))
            repository.saveQuestion(
                Question(
                    id = "q1",
                    categoryId = "c1",
                    passageId = "pa",
                    elements = listOf(ContentElement.TextElement("<b>Bold</b> stem text")),
                    options = listOf(QuestionOption("a", "A")),
                    correctOptionIds = setOf("a")
                )
            )
        }
    }

    @After
    fun tearDown() = db.close()

    /** Readable, markup gone — and emphatically not JSON. */
    private fun assertReadable(refs: List<QuestionRef>) {
        assertEquals(listOf("Bold stem text"), refs.map { it.text })
        refs.forEach { ref ->
            assertFalse(
                "ref text is the elements JSON: ${ref.text}",
                ref.text.trimStart().startsWith("[")
            )
        }
    }

    private fun paramsFor(pageSize: Int = 2): (Int?) -> PagingSource.LoadParams<Int> =
        { key ->
            if (key == null) PagingSource.LoadParams.Refresh(null, pageSize, false)
            else PagingSource.LoadParams.Append(key, pageSize, false)
        }

    @Test
    fun everyRefQueryCarriesTheFlattenedStem() = runBlocking {
        assertReadable(db.questionDao().getRefsByCategories(listOf("c1")))
        assertReadable(db.questionDao().getRefsByCategoryOrdered("c1"))
        assertReadable(db.questionDao().getRefsByIds(listOf("q1")))
        assertReadable(listOfNotNull(db.questionDao().getRefById("q1")))
        assertReadable(db.questionDao().getRefsByPassageOrdered("pa"))
        assertReadable(db.questionDao().observeRefsByCategories(listOf("c1")).first())

        val browse = McqRepository.BrowseScope.of(
            categoryIds = listOf("c1"),
            query = "",
            matchingPassageIds = listOf(""),
            filter = BrowseListFilter.ALL,
            categoryFilterIds = null
        )
        assertReadable(walkPaging(2, { repository.browseRefsSource(browse) }, paramsFor()))
        assertReadable(walkPaging(2, { repository.passageMembersSource("pa") }, paramsFor()))
    }
}
