package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.TestAnswer
import com.mcqapp.domain.TestSnapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
 * The resume snapshot is a header now, and the answers are rows.
 *
 * These pin the two things that only make sense together: the document a tap
 * rewrites stays header-sized no matter how much was answered, and the answers
 * are still all there — for the resume offer, and for the backup, whose format
 * has only ever had the one inlined document.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TestProgressStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        runBlocking { repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat") }
    }

    @After
    fun tearDown() {
        runBlocking { repository.clearTestProgress() }
        db.close()
    }

    private fun header(
        index: Int = 0,
        remaining: Int = 0,
        seed: Long = 7L
    ) = TestSnapshot(
        paperId = "p1",
        categoryIds = listOf("c1"),
        currentIndex = index,
        remainingSeconds = remaining,
        totalSeconds = 600,
        questionSeed = seed,
        questionShuffle = true
    )

    /** One action: the header, plus the one question whose answer changed. */
    private suspend fun answer(questionId: String, at: Int, dwell: Long = 3L) {
        repository.saveTestProgress(
            header(index = at),
            listOf(TestAnswer(questionId, selection = setOf("$questionId-a"), dwellSeconds = dwell))
        )
    }

    @Test
    fun theDocumentStaysHeaderSizedHoweverManyAnswersLand() = runBlocking {
        repeat(300) { i -> answer("q$i", at = i) }

        val stored = repository.loadTestSnapshot()!!
        val json = stored.toJson()
        assertTrue("document should be KB-scale, was ${json.length}", json.length < 500)
        assertTrue("document should not name a question", !json.contains("q299"))
        assertEquals(299, stored.currentIndex)

        // And the answers are all still there, joined back for the offer.
        val offer = repository.resumeOffer("p1")!!
        assertEquals(300, offer.selections.size)
        assertEquals(listOf("q0-a"), offer.selections["q0"])
        assertEquals(3L, offer.dwellSeconds["q299"])
    }

    @Test
    fun theBackupStillCarriesEveryAnswerInline() = runBlocking {
        answer("q1", at = 0)
        answer("q2", at = 1)

        // The backup format has one snapshot field and no rows, so the document
        // it writes is the legacy shape: header with the answers inlined.
        val forBackup = TestSnapshot.fromJson(repository.testProgressForBackup()!!)!!
        assertEquals(listOf("q1-a"), forBackup.selections["q1"])
        assertEquals(listOf("q2-a"), forBackup.selections["q2"])
        assertEquals(1, forBackup.currentIndex)
    }

    @Test
    fun restoringABackupSupersedesTheRowsItBrings() = runBlocking {
        answer("q1", at = 0)

        val restored = TestSnapshot(
            paperId = "p1",
            categoryIds = listOf("c1"),
            selections = mapOf("q9" to listOf("q9-a")),
            currentIndex = 4
        ).toJson()
        repository.restoreTestProgress(restored)

        // The restored document is the newer truth: the row it supersedes is
        // gone rather than merged with it.
        val offer = repository.resumeOffer("p1")!!
        assertEquals(setOf("q9"), offer.selections.keys)
        assertEquals(4, offer.currentIndex)
        assertTrue(db.testProgressDao().getForPaper("p1").isEmpty())
    }

    @Test
    fun clearingDropsTheHeaderAndItsAnswers() = runBlocking {
        answer("q1", at = 0)
        assertEquals(1, db.testProgressDao().getForPaper("p1").size)

        repository.clearTestProgress()

        assertNull(repository.loadTestSnapshot())
        assertTrue(db.testProgressDao().getForPaper("p1").isEmpty())
    }

    /**
     * Two saves a few hundred ms apart both suspend between their row write
     * and their DataStore header write, so the header could land out of order
     * and end up one question behind the rows around it. The store serialises
     * saves behind a mutex; the seam [TestProgressSlot] makes that PROVABLE
     * rather than merely uncrashed: the fake holds save #1's header write
     * open (deterministically, no sleeps), then the test observes that save #2
     * has NOT written its rows while #1 is still inside — which is exactly
     * what an unserialised save would have done — and that after the release
     * the two headers land #1-then-#2, so the slot ends on the newest one.
     *
     * Opens SQLite: CI is the authority. Mutant: drop the writeLock from
     * [save] — save #2's row shows up while #1's header is still held, and the
     * header order assert goes red.
     */
    @Test
    fun interleavedSavesSerialiseRowsThenHeader() = runBlocking {
        val slot = RecordingSlot()
        val store = com.mcqapp.data.repository.TestProgressStore(db, slot)

        val rowsOne = listOf(TestAnswer("q1", selection = setOf("q1-a"), dwellSeconds = 1))
        val rowsTwo = listOf(TestAnswer("q2", selection = setOf("q2-b"), dwellSeconds = 2))
        val first = launch { saveOrThrow(store, header(index = 0), rowsOne) }

        // Deterministic (no sleeps; pure scheduler turns, bounded by the
        // repeat) wait for save #1 to enter its header write.
        var entered = false
        repeat(128) {
            if (slot.started.isNotEmpty()) {
                entered = true
                return@repeat
            }
            yield()
        }
        assertTrue("save #1 must reach its header write", entered)

        val second = launch { saveOrThrow(store, header(index = 1), rowsTwo) }
        // Fair chance for save #2 to (wrongly) proceed while #1 is inside.
        repeat(128) { yield() }
        assertTrue(
            "save #2 must wait for save #1's whole save, rows included",
            db.testProgressDao().getForPaper("p1").none { it.questionId == "q2" }
        )

        slot.release()
        first.join()
        second.join()
        assertEquals(
            "save #1's header write is held then completes, and save #2's flows " +
                "through after it: the slot ends on the newest header. (Mutant, no " +
                "writeLock: both saves park on the hold before either ends, giving " +
                "held, held, header-end, header-end — red.)",
            listOf("held", "header-end", "flowed", "header-end"),
            slot.started
        )
        assertEquals(header(index = 1).toJson(), slot.lastJson)
        assertTrue("save #2's row lands, after the header #1 completes first",
            db.testProgressDao().getForPaper("p1").any { it.questionId == "q2" })
    }

    private suspend fun saveOrThrow(
        store: com.mcqapp.data.repository.TestProgressStore,
        snapshot: TestSnapshot,
        rows: List<TestAnswer>
    ) = store.save(snapshot, rows)

    /**
     * A corrupt backup inProgressTest must not destroy the live session: the
     * live header + rows survive the restore, and a VALID document still
     * replaces both. The parse guard used to protect only the row delete
     * while the slot overwrite ran unconditionally.
     *
     * Opens SQLite: CI is the authority. Mutant: the unconditional slot
     * overwrite of the old code turns the live-snapshot assert red — the
     * truncated document replaces the live header.
     */
    @Test
    fun aCorruptRestoreKeepsTheLiveSnapshot() = runBlocking {
        val slot = RecordingSlot(gated = false)
        val store = com.mcqapp.data.repository.TestProgressStore(db, slot)
        val live = header(index = 3)
        store.save(live, listOf(TestAnswer("q5", selection = setOf("q5-a"), dwellSeconds = 4)))

        store.restore("{\"paperId\": \"p1\", \"truncat") // corrupt document

        assertEquals("the live header stays", live.toJson(), slot.loadTestProgress())
        assertEquals("the live rows stay",
            listOf("q5"), db.testProgressDao().getForPaper("p1").map { it.questionId })

        // And a VALID document still replaces both, as always.
        store.restore(
            TestSnapshot(
                paperId = "p2",
                categoryIds = listOf("c1"),
                currentIndex = 9
            ).toJson()
        )
        assertEquals("a valid document replaces", "p2", slot.loadTestProgress()!!.let {
            TestSnapshot.fromJson(it)!!.paperId
        })
        assertTrue(db.testProgressDao().getForPaper("p2").isEmpty())
    }

    /**
     * The seam: records header-write stages. #24's test needs the FIRST header
     * write held on [hold] so it can park a second save behind it; #25's only
     * records what landed, so it constructs the slot ungated.
     */
    private inner class RecordingSlot(
        private val gated: Boolean = true
    ) : com.mcqapp.data.repository.TestProgressSlot {
        val started = mutableListOf<String>()
        var lastJson: String? = null
        private val hold = kotlinx.coroutines.CompletableDeferred<Unit>()

        override suspend fun saveTestProgress(json: String) {
            val held = gated && !hold.isCompleted
            started += if (held) "held" else "flowed"
            if (held) hold.await()
            started += "header-end"
            lastJson = json
        }

        override suspend fun loadTestProgress(): String? = lastJson

        override suspend fun clearTestProgress() {
            lastJson = null
        }

        fun release() {
            hold.complete(Unit)
        }
    }
}
