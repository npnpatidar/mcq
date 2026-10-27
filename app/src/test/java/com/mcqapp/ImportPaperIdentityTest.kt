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
import com.mcqapp.data.io.StreamingImporter
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.PaperEntity
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Paper identity: match by id first, then by title. The import preview's title
 * field is the destination the user picks — selecting an existing title fills
 * that paper, and a title the library has never seen becomes a new paper. An
 * empty same-title paper is a hand-made container and keeps its exam settings.
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
    fun sameTitleDifferentStableIdsFillTheSamePaper() = runBlocking {
        Importer(db).import(file("paper-abc", "Deck", "2 + 2?"))
        // Different content, so nothing is deduplicated: the two files are
        // attached to one paper because the title names the destination.
        val report = Importer(db).import(file("paper-def", "Deck", "3 + 3?"))

        assertEquals(0, report.newPapers)
        assertEquals(1, report.updatedPapers)
        assertEquals(1, db.paperDao().getAll().size)
        // The paper the first import created keeps its id.
        assertEquals("paper-abc", db.paperDao().getAll().single().id)
        assertEquals(2, db.questionDao().countByPaper("paper-abc"))
    }

    /**
     * The reported flow: a paper added by hand in the library is empty, the
     * user picks its title in the import preview, and the file's paper id is a
     * stable one this device has never seen. The import must fill that paper
     * instead of adding a same-named twin — and must not overwrite the exam
     * settings typed when creating it.
     */
    @Test
    fun fillingAnEmptyPaperKeepsItsExamSettings() = runBlocking {
        db.paperDao().insertIgnore(
            PaperEntity(
                id = "paper-mvqe96a0",
                title = "EO",
                description = "my notes",
                durationMinutes = 120,
                negativeMarking = 0.33
            )
        )

        val report = Importer(db)
            .import(file("sci-tech-top-200-en", "EO", "2 + 2?"))

        assertEquals(0, report.newPapers)
        assertEquals(1, report.updatedPapers)
        val paper = db.paperDao().getById("paper-mvqe96a0")!!
        assertEquals(120, paper.durationMinutes)
        assertEquals(0.33, paper.negativeMarking, 0.0001)
        // The file carries no description, so the typed one survives.
        assertEquals("my notes", paper.description)
        assertEquals(1, db.questionDao().countByPaper("paper-mvqe96a0"))
        assertNull(db.paperDao().getById("sci-tech-top-200-en"))
    }

    @Test
    fun theStreamingPathFillsAnEmptyPaperTheSameWay() = runBlocking {
        db.paperDao().insertIgnore(
            PaperEntity(
                id = "paper-stub",
                title = "EO",
                durationMinutes = 90,
                negativeMarking = 0.25
            )
        )
        val json = """{"papers":[{"id":"sci-tech-top-200-en","title":"EO",
            "categories":[{"id":"c1","title":"Cat","questions":[
            {"id":"q1","text":"2 + 2?","options":["4","five"],
            "correctOptionIds":["a"]}]}]}]}"""

        val report = StreamingImporter(db)
            .import(source = { ByteArrayInputStream(json.toByteArray()) })

        assertEquals(0, report.newPapers)
        assertEquals(1, report.updatedPapers)
        assertEquals(90, db.paperDao().getById("paper-stub")!!.durationMinutes)
        assertEquals(1, db.questionDao().countByPaper("paper-stub"))
        assertNull(db.paperDao().getById("sci-tech-top-200-en"))
    }

    @Test
    fun aPopulatedTwinWinsOverAnEmptyOne() = runBlocking {
        // The state the old rule left behind: an empty hand-made "EO" and an
        // imported "EO" beside it, the stub older than the deck.
        db.paperDao().insertIgnore(
            PaperEntity(id = "paper-stub", title = "EO", createdAt = 1_000)
        )
        Importer(db).import(file("deck-eo", "EO", "2 + 2?"))

        val report = Importer(db).import(file("deck-eo-2", "EO", "3 + 3?"))

        assertEquals(0, report.newPapers)
        assertEquals(1, report.updatedPapers)
        // The deck the questions live in gets the new question; the empty
        // twin stays an empty shell the user can delete.
        assertEquals(2, db.questionDao().countByPaper("deck-eo"))
        assertEquals(0, db.questionDao().countByPaper("paper-stub"))
    }

    @Test
    fun aStableIdBeatsAnotherPapersTitle() = runBlocking {
        Importer(db).import(file("paper-a", "Deck A", "Alpha"))
        Importer(db).import(file("paper-b", "Deck B", "Beta"))

        // Same id as the second paper, title of the first: the id wins, so
        // paper-b is updated and Deck A keeps its own question.
        val report = Importer(db).import(file("paper-b", "Deck A", "Beta"))

        assertEquals(0, report.newPapers)
        assertEquals(1, report.updatedPapers)
        assertEquals(2, db.paperDao().getAll().size)
        assertEquals(1, db.questionDao().countByPaper("paper-a"))
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
