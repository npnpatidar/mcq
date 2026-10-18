package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.export.PaperStream
import com.mcqapp.data.io.Exporter
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.BookmarkEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bookmark export is walked from the database a page at a time instead of
 * being assembled as one DTO — a "bookmark everything" bank would otherwise be
 * built whole before a byte was written. The streamed walk must produce exactly
 * what the old in-memory builder did: the same headings, in the same order, with
 * each heading's questions in the same order. The builder stays as the oracle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookmarkExportStreamTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        repository.ensurePaperAndCategory("p1", "Paper One", "c1", "Cat One")
        repository.ensurePaperAndCategory("p2", "Paper Two", "c2", "Cat Two")
        for (id in listOf("q1", "q2", "q4")) repository.saveQuestion(question(id, "c1"))
        repository.saveQuestion(question("q3", "c2"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun question(id: String, categoryId: String) = Question(
        id = id,
        categoryId = categoryId,
        text = "Q $id",
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = setOf("$id-a")
    )

    /** Distinct bookmark times, so the recency order is unambiguous. */
    private suspend fun bookmark(id: String, createdAt: Long) {
        db.bookmarkDao().add(BookmarkEntity(questionId = id, createdAt = createdAt))
    }

    /** Every heading and the ids under it, as the writer would see them. */
    private suspend fun walked(stream: PaperStream): List<Pair<String, List<String>>> {
        val out = mutableListOf<Pair<String, List<String>>>()
        var heading: String? = null
        var ids = mutableListOf<String>()
        stream.walk(
            onCategory = { category ->
                heading?.let { out += it to ids }
                heading = category.title
                ids = mutableListOf()
            },
            onQuestion = { question -> ids += question.id }
        )
        heading?.let { out += it to ids }
        return out
    }

    @Test
    fun theStreamMatchesTheDtoBuilder() = runBlocking {
        // Bookmarked out of alphabetical order and across two papers: q1 (newest)
        // and q2 in Paper One, q3 in Paper Two, q4 left unbookmarked.
        bookmark("q1", createdAt = 300)
        bookmark("q2", createdAt = 100)
        bookmark("q3", createdAt = 200)

        val oracle = repository.getBookmarkExportDto()!!
        val expected = oracle.categories.map { it.title to it.questions.map { q -> q.id } }

        val stream = Exporter(db).bookmarkStream()!!
        assertEquals(expected, walked(stream))
        assertEquals(3, stream.totalQuestions)
        assertEquals(expected.map { it.first }, stream.categories.map { it.title })
    }

    @Test
    fun aBookmarkNothingResolvesIsDroppedLikeTheBuilderDoes() = runBlocking {
        bookmark("q1", createdAt = 5)
        // A dangling bookmark: allowed in the table (no foreign key), dropped by
        // both the builder and the stream because the question does not exist.
        bookmark("missing", createdAt = 9)

        val oracle = repository.getBookmarkExportDto()!!
        val stream = Exporter(db).bookmarkStream()!!
        assertEquals(
            oracle.categories.map { it.title to it.questions.map { q -> q.id } },
            walked(stream)
        )
        assertEquals(1, stream.totalQuestions)
    }

    @Test
    fun noBookmarksMeansNothingToExport() = runBlocking {
        assertNull(repository.getBookmarkExportDto())
        assertNull(Exporter(db).bookmarkStream())
    }
}
