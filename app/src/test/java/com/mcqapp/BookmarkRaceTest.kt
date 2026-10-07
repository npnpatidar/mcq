package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * toggleBookmark is read-then-write: outside a transaction two taps racing
 * within one dispatcher hop read the same state and both flip the same way,
 * so the double tap ends where a single tap should have (the REPLACE insert
 * quietly absorbs the loser). Inside one transaction Room serializes the
 * toggles, so the final state is exactly the parity of the tap count. The
 * sequential round trip lives in RepositoryTest; this pins the race.
 * Robolectric needs a real SQLite driver, so this test only runs on x86_64.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookmarkRaceTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** [count] toggles launched together, with no ordering between them. */
    private fun toggleConcurrently(count: Int): Unit = runBlocking {
        coroutineScope {
            repeat(count) {
                launch(Dispatchers.Default) { repository.toggleBookmark("q1") }
            }
        }
    }

    @Test
    fun evenNumberOfConcurrentTogglesEndsUnbookmarked() {
        toggleConcurrently(8)
        runBlocking { assertFalse(repository.isBookmarked("q1")) }
    }

    @Test
    fun oddNumberOfConcurrentTogglesEndsBookmarked() {
        toggleConcurrently(7)
        runBlocking { assertTrue(repository.isBookmarked("q1")) }
    }
}
