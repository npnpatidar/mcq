package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Resolving a hash collision read the candidate rows per colliding question, so
 * a re-import of a large bank issued thousands of queries inside one
 * transaction. They are now gathered once per import.
 *
 * Query counts are measured through Room's query callback rather than asserted
 * from reading the code, so the number moves if this regresses. A collision can
 * only arise on a *re*-import — a first import has nothing to collide with — so
 * the cost is measured on the second pass and compared against an equivalent
 * import that has no collisions to resolve.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportCollisionQueryCountTest {

    private lateinit var db: AppDatabase
    private lateinit var appContext: Context
    private val selects = AtomicInteger()

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            // Inline executor, so the counter is updated before the call returns.
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, _ ->
                    if (sql.trimStart().startsWith("SELECT")) selects.incrementAndGet()
                },
                { it.run() }
            )
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    /**
     * `count` questions that all share one content hash, because the hash is
     * built from prose and the prose is identical while the difference lives in
     * a table the hash cannot see.
     *
     * `variant` decides what the questions actually contain:
     *  - [Variant.TABLE_DIFFERS] — each option table holds a different year, so
     *    they are genuinely different questions and all of them must import;
     *  - [Variant.IDENTICAL] — byte-identical questions, which must collapse;
     *  - [Variant.PROSE_DIFFERS] — the prose differs, so nothing collides and
     *    this is the baseline the query count is compared against.
     */
    private enum class Variant { TABLE_DIFFERS, IDENTICAL, PROSE_DIFFERS }

    private fun bank(count: Int, year: String, variant: Variant = Variant.TABLE_DIFFERS) = """
        {"version":1,"papers":[{"id":"p1","title":"Bank","categories":[{"id":"c1","title":"Cat",
        "questions":[${(1..count).joinToString(",") { i ->
            val prose = when (variant) {
                Variant.TABLE_DIFFERS -> "Who ruled in 1556?"
                Variant.IDENTICAL -> "Who ruled in 1556?"
                Variant.PROSE_DIFFERS -> "Who ruled in $year?"
            }
            val cell = if (variant == Variant.TABLE_DIFFERS) "${year.toInt() + i}" else year
            """{"id":"q$i",
                "question_elements":[{"type":"text","content":"$prose"}],
                "options_elements":{
                  "a":[{"type":"table","content":[["Ruler","Year"],["Akbar","$cell"]]}],
                  "b":[{"type":"text","content":"None"}]},
                "correctOptionIds":["a"],
                "explanation_elements":[{"type":"text","content":"From the table."}]}"""
        }}]}]}]}
    """.trimIndent()

    private fun runImport(json: String) =
        runBlocking { Importer(db).import(LegacyParser.parse(json)) }

    private fun fresh() {
        db.close()
        selects.set(0)
        setUp()
    }

    /** Seeds the bank, then counts the queries a second pass costs. */
    private fun reimportCost(count: Int, year: String, variant: Variant): Int {
        fresh()
        runImport(bank(count, year, variant))
        selects.set(0)
        runImport(bank(count, year, variant))
        return selects.get()
    }

    private fun questionsInPaper() = runBlocking {
        McqRepository(db, appContext).getQuestionsForPaper("p1")
    }

    /**
     * Resolving collisions must not cost queries per question.
     *
     * Measured against an equivalent re-import whose questions do not collide,
     * at three sizes. Per-question lookups would add roughly three selects per
     * colliding question, so going from 10 to 40 questions would add about 90;
     * gathering the candidates once adds the same handful throughout.
     */
    @Test
    fun collisionsDoNotCostQueriesPerQuestion() {
        fun extra(count: Int): Int =
            reimportCost(count, "1556", Variant.TABLE_DIFFERS) -
                reimportCost(count, "1605", Variant.PROSE_DIFFERS)

        val atTen = extra(10)
        val atForty = extra(40)
        println("COLLISION-COST extra(10)=$atTen extra(40)=$atForty")
        assertTrue(
            "thirty more colliding questions added ${atForty - atTen} selects " +
                "(extra at 10 was $atTen), which is per-question behaviour",
            atForty - atTen <= 15
        )
    }

    @Test
    fun questionsThatDifferOnlyInATableAreAllImported() {
        runImport(bank(3, "1556", Variant.TABLE_DIFFERS))
        assertEquals(3, questionsInPaper().size)
    }

    @Test
    fun identicalQuestionsInOneFileStillCollapseToOne() {
        // Guards the same-import candidate set: existingHashes grows as rows are
        // written, so a later copy reaches the content check and must be seen as
        // a duplicate rather than inserted again.
        runImport(bank(3, "1556", Variant.IDENTICAL))
        assertEquals(1, questionsInPaper().size)
    }

    @Test
    fun aGenuineReimportIsSkippedAsDuplicateNotDuplicated() {
        fresh()
        runImport(bank(2, "1556", Variant.TABLE_DIFFERS))
        assertEquals(2, questionsInPaper().size)

        val report = runImport(bank(2, "1556", Variant.TABLE_DIFFERS))
        assertEquals(0, report.newQuestions)
        assertEquals(2, report.duplicateQuestions)
        assertEquals(2, questionsInPaper().size)
    }
}