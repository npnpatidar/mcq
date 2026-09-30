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
}
