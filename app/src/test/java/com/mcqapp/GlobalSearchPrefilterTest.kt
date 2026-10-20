package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cross-paper search runs a SQL `LIKE` prefilter and only then the in-memory
 * filter that actually understands rich content.
 *
 * The prefilter matches against `questions.text`, which stores the elements
 * *JSON* — markup included. So a query whose words sit either side of a tag, or
 * either side of a MathML tag, is not present in that JSON as a literal and the
 * row is discarded before the real filter runs. Browsing one paper found these
 * questions; searching across papers did not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlobalSearchPrefilterTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = McqRepository(db, context)
    }

    @After
    fun tearDown() = db.close()

    private fun save(question: Question, paperId: String = "p1", categoryId: String = "c1") =
        runBlocking {
            repository.ensurePaperAndCategory(paperId, "Paper", categoryId, "Cat")
            repository.saveQuestion(question)
        }

    private fun hits(query: String) = runBlocking {
        repository.searchGlobal(query).map { it.question.text }
    }

    /**
     * SQL `LIKE` — and SQLite's own `lower()` — case-fold A–Z only, so a
     * query whose case differed from the stored text on a non-ASCII letter
     * (`CAFÉ` vs `café`, Cyrillic, Greek) rejected the row *before* the
     * authoritative in-memory filter — which folds with full Unicode Kotlin
     * — ever saw it. The prefilter now matches this query against the
     * Kotlin-folded columns, so its case-folding is the authority's.
     */
    @Test
    fun aNonAsciiCaseDifferenceIsNotAVeto() {
        save(
            Question(
                id = "q-cafe", categoryId = "c1",
                elements = listOf(ContentElement.TextElement("CAFÉ central")),
                options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
                correctOptionIds = setOf("a")
            )
        )
        assertTrue(
            "LIKE folded A-Z only, so 'café' vetoed the 'CAFÉ' row before the " +
                "in-memory filter saw it; got ${hits("café")}",
            hits("café").isNotEmpty()
        )
        assertTrue(
            "folding must work in the other direction too; got ${hits("CAFÉ")}",
            hits("CAFÉ").isNotEmpty()
        )
    }

    /**
     * The search twin below is the discriminating proof on this path: it is
     * engine-independent, while `case_sensitive_like` proved unusable (CI run
     * 39371651193: with the pragma on and the fixed code the row was NOT
     * found, while the same question is found without the pragma in the same
     * class). The bind fold on this path is not separately observable without
     * SQLite; the column choice is what the twin pins.
     */
    @Test
    fun theSearchPrefilterReadsTheFoldedColumnNotTheRawOne() = kotlinx.coroutines.runBlocking {
        // The twins are inserted directly through the DAO, so the foreign key
        // (Room runs PRAGMA foreign_keys = ON) needs its parents here: this
        // class seeds nothing in setUp and every other test goes through
        // save(), which is what ensures the paper and category.
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        com.mcqapp.data.local.QuestionEntity(
            id = "q-folded", categoryId = "c1",
            text = "x", searchText = "CAFÉ central",
            searchTextFolded = "café central",
            tags = "", tagsFolded = ""
        ).let { db.questionDao().upsertAll(listOf(it)) }
        com.mcqapp.data.local.QuestionEntity(
            id = "q-raw", categoryId = "c1",
            text = "x", searchText = "café central",
            // Deliberately inconsistent: nothing any writer produces.
            searchTextFolded = "zzz",
            tags = "", tagsFolded = ""
        ).let { db.questionDao().upsertAll(listOf(it)) }

        val found = db.questionDao().search("café", "café", 10, 0).map { it.id }
        assertTrue(
            "the folded column must be read: got $found",
            "q-folded" in found
        )
        assertFalse(
            "the raw column must not be read (this is the mutant's tell): got $found",
            "q-raw" in found
        )
    }

    @Test
    fun aPhraseSplitByMarkupIsFoundAcrossPapers() {
        save(
            Question(
                id = "q1", categoryId = "c1",
                elements = listOf(
                    ContentElement.TextElement(
                        "In <strong>Newton's second law</strong> the acceleration is " +
                            "proportional to the net <em>external</em> force."
                    )
                ),
                options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
                correctOptionIds = setOf("a")
            )
        )
        assertTrue(
            "'external force' is split by <em> in the stored JSON, so the SQL " +
                "prefilter dropped it; got ${hits("external force")}",
            hits("external force").isNotEmpty()
        )
    }

    @Test
    fun aPhraseSpanningTwoTableCellsIsFound() {
        save(
            Question(
                id = "q2", categoryId = "c1",
                elements = listOf(
                    ContentElement.TextElement("Use the table to answer."),
                    ContentElement.TableElement(
                        listOf(listOf("Ruler", "Year"), listOf("Akbar", "1556"))
                    )
                ),
                options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
                correctOptionIds = setOf("a")
            )
        )
        assertTrue(
            "cells are separate JSON strings, so 'Akbar 1556' never matched; " +
                "got ${hits("Akbar 1556")}",
            hits("Akbar 1556").isNotEmpty()
        )
    }

    @Test
    fun aFormulaWrittenWithoutSpacesIsFound() {
        save(
            Question(
                id = "q3", categoryId = "c1",
                elements = listOf(
                    ContentElement.TextElement("Solve for "),
                    ContentElement.MathElement(
                        "<math><mi>v</mi><mo>=</mo><mi>u</mi><mo>+</mo><mi>a</mi><mi>t</mi></math>"
                    )
                ),
                options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
                correctOptionIds = setOf("a")
            )
        )
        assertTrue(
            "'v=u+at' spans MathML tags so it is not in the JSON literally; " +
                "got ${hits("v=u+at")}",
            hits("v=u+at").isNotEmpty()
        )
    }

    @Test
    fun thePrefilterStillNarrowsRatherThanMatchingEverything() {
        save(
            Question(
                id = "q4", categoryId = "c1",
                elements = listOf(ContentElement.TextElement("Alpha beta gamma.")),
                options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
                correctOptionIds = setOf("a")
            )
        )
        assertEquals(1, hits("Alpha").size)
        assertEquals(emptyList<String>(), hits("Delta"))
        // JSON structural text must not be findable either.
        assertEquals(emptyList<String>(), hits("content"))
        assertEquals(emptyList<String>(), hits("\"type\""))
    }
}