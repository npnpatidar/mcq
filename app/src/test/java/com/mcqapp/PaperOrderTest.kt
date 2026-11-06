package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Paper
import kotlinx.coroutines.flow.first
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
 * The library's manual paper order: new papers take the top slot, a move swaps
 * one card with its neighbour, and the sequence is normalised so repeated
 * moves stay deterministic (pre-v20 rows all share sortOrder 0, and without
 * normalisation swapping two zeros would be a no-op).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaperOrderTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun paper(id: String, title: String) = Paper(id = id, title = title)

    private suspend fun order(): List<String> =
        repository.observePapers().first().map { it.id }

    @Test
    fun `new papers land on top and a move normalises the sequence`() = runBlocking {
        repository.savePaper(paper("a", "A"))
        repository.savePaper(paper("b", "B"))
        repository.savePaper(paper("c", "C"))

        assertEquals(listOf("c", "b", "a"), order())

        // Down one slot, then up one: both are true moves.
        assertTrue(repository.movePaper("c", +1))
        assertEquals(listOf("b", "c", "a"), order())

        assertTrue(repository.movePaper("a", -1))
        assertEquals(listOf("b", "a", "c"), order())

        // The edge is a no-op, not a wrap-around.
        assertFalse(repository.movePaper("b", -1))
        assertEquals(listOf("b", "a", "c"), order())

        // Normalised densely: a later insert takes min - 1 and still lands on
        // top without a gap.
        assertEquals(listOf(0L, 1L, 2L), db.paperDao().getAll().map { it.sortOrder })
        repository.savePaper(paper("d", "D"))
        assertEquals(listOf("d", "b", "a", "c"), order())
        assertEquals(listOf(-1L, 0L, 1L, 2L), db.paperDao().getAll().map { it.sortOrder })
    }
}
