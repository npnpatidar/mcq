package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Paper identity: match by id first; title fallback only for ephemeral
 * parser-generated ids. Two genuinely different papers that share a title
 * must not merge, while bare-array re-imports (fresh random id per parse)
 * must still merge by title.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportPaperIdentityTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun file(paperId: String, title: String, questionText: String) = McqFileDto(
        version = 1,
        papers = listOf(
            PaperDto(
                id = paperId,
                title = title,
                categories = listOf(
                    CategoryDto(
                        id = "c-$paperId",
                        title = "Cat",
                        questions = listOf(
                            QuestionDto(
                                id = "q-$paperId",
                                text = questionText,
                                options = listOf(OptionDto("a", "4"), OptionDto("b", "five")),
                                correctOptionIds = listOf("a")
                            )
                        )
                    )
                )
            )
        )
    )

    @Test
    fun sameTitleDifferentStableIdsStayDistinct() = runBlocking {
        Importer(db).import(file("paper-abc", "Deck", "2 + 2?"))
        // Different content, so the global paper-skip cannot fire: the only
        // thing the two papers share is the title.
        val report = Importer(db).import(file("paper-def", "Deck", "3 + 3?"))

        assertEquals(1, report.newPapers)
        assertEquals(2, db.paperDao().getAll().size)
        assertTrue(db.paperDao().getById("paper-def") != null)
    }

    @Test
    fun ephemeralReimportsMergeByTitle() = runBlocking {
        val prefix = LegacyParser.EPHEMERAL_PAPER_ID_PREFIX
        Importer(db).import(file(prefix + "aaa", "Deck", "2 + 2?"))
        val report = Importer(db).import(file(prefix + "aab", "Deck", "2 + 2?"))

        assertEquals(0, report.newPapers)
        assertEquals(1, report.updatedPapers)
        assertEquals(1, db.paperDao().getAll().size)
    }

    @Test
    fun bareArrayParseMintsEphemeralIds() {
        val paper = LegacyParser.parse(
            """[{"id":"q1","text":"Q?","options":["A","B"]}]"""
        ).papers.single()

        assertTrue(
            "bare-array paper id must carry the ephemeral prefix: ${paper.id}",
            paper.id.startsWith(LegacyParser.EPHEMERAL_PAPER_ID_PREFIX)
        )
    }

    /**
     * Question ids are global (the row's primary key), but a foreign file is
     * free to reuse an id the library already uses for a *different*
     * question under another paper. The import must not move the stored row:
     * the `REPLACE` upsert rewrites its `categoryId`, so paper A silently
     * loses the question and its options/answer key are overwritten.
     */
    @Test
    fun aQuestionIdOwnedByAnotherPaperIsNotMoved() = runBlocking {
        Importer(db).import(file("paper-a", "Paper A", "Alpha"))
        val sharedId = "q-paper-a"
        assertEquals("c-paper-a", db.questionDao().getById(sharedId)!!.categoryId)

        val paperB = McqFileDto(
            version = 1,
            papers = listOf(
                PaperDto(
                    id = "paper-b",
                    title = "Paper B",
                    categories = listOf(
                        CategoryDto(
                            id = "c-paper-b",
                            title = "Cat",
                            questions = listOf(
                                QuestionDto(
                                    id = sharedId,
                                    text = "Beta",
                                    options = listOf(OptionDto("a", "6"), OptionDto("b", "seven")),
                                    correctOptionIds = listOf("b")
                                )
                            )
                        )
                    )
                )
            )
        )
        Importer(db).import(paperB)

        // Paper A's question is still there, unchanged.
        assertEquals("c-paper-a", db.questionDao().getById(sharedId)?.categoryId)
        // The text column stores the elements JSON, not the flat string.
        assertEquals("Alpha", db.questionDao().getById(sharedId)?.searchText)
        assertEquals(1, db.questionDao().getByCategory("c-paper-a").size)
    }

    /**
     * Same collision for a passage id: the passage is upserted with the
     * importing paper's category, and the stray-folding pass then moves every
     * stored member whose category differs into that category. A reused
     * passage id therefore drags another paper's questions along with it.
     */
    @Test
    fun aPassageIdOwnedByAnotherPaperDoesNotDragMembers() = runBlocking {
        val paperA = McqFileDto(
            version = 1,
            papers = listOf(
                PaperDto(
                    id = "paper-a",
                    title = "Paper A",
                    categories = listOf(
                        CategoryDto(
                            id = "c-paper-a",
                            title = "Cat",
                            questions = listOf(
                                QuestionDto(
                                    id = "qa",
                                    text = "Alpha",
                                    options = listOf(OptionDto("a", "1"), OptionDto("b", "2")),
                                    correctOptionIds = listOf("a"),
                                    passageId = "ps"
                                )
                            )
                        )
                    )
                )
            ),
            passages = listOf(PassageDto(id = "ps", title = "Read", categoryId = "c-paper-a"))
        )
        Importer(db).import(paperA)
        assertEquals(1, db.questionDao().getByCategory("c-paper-a").size)

        val paperB = McqFileDto(
            version = 1,
            papers = listOf(
                PaperDto(
                    id = "paper-b",
                    title = "Paper B",
                    categories = listOf(
                        CategoryDto(
                            id = "c-paper-b",
                            title = "Cat",
                            questions = listOf(
                                QuestionDto(
                                    id = "qb",
                                    text = "Beta",
                                    options = listOf(OptionDto("a", "3"), OptionDto("b", "4")),
                                    correctOptionIds = listOf("b"),
                                    passageId = "ps"
                                )
                            )
                        )
                    )
                )
            ),
            passages = listOf(PassageDto(id = "ps", title = "Other read", categoryId = "c-paper-b"))
        )
        Importer(db).import(paperB)

        assertEquals(1, db.questionDao().getByCategory("c-paper-a").size)
    }
}
