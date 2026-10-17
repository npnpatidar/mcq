package com.mcqapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.ImportRunRecord
import com.mcqapp.data.io.SharedPrefsImportRunJournal
import com.mcqapp.data.io.interruptedImportNotice
import com.mcqapp.domain.splitImportWarnings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The journal is what makes an import that died with the process visible.
 * Robolectric for the SharedPreferences backing; the notice rule is pure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportRunJournalTest {

    private lateinit var context: Context
    private lateinit var journal: SharedPrefsImportRunJournal

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("import_run", Context.MODE_PRIVATE)
            .edit().clear().commit()
        journal = SharedPrefsImportRunJournal(context)
    }

    @Test
    fun roundTripsTheRunAndSurvivesANewInstance() {
        journal.begin("sha-abc", totalQuestions = 5000, startedAt = 123L)
        journal.progress(2000)

        val reread = SharedPrefsImportRunJournal(context).current()
        assertEquals(
            ImportRunRecord("sha-abc", questionsSeen = 2000, totalQuestions = 5000, startedAt = 123L),
            reread
        )
    }

    @Test
    fun clearForgetsTheRun() {
        journal.begin("sha-abc", 10, 1L)
        journal.clear()
        assertNull(journal.current())
    }

    @Test
    fun aJournalForAnotherFileIsNotAProblem() {
        journal.begin("sha-abc", 10, 1L)
        journal.progress(4)
        assertNull(interruptedImportNotice(journal.current(), "sha-other", 10))
        assertNull(interruptedImportNotice(journal.current(), null, 10))
        assertNull(interruptedImportNotice(null, "sha-abc", 10))
    }

    @Test
    fun aRunThatReachedTheEndIsNotInterrupted() {
        journal.begin("sha-abc", 10, 1L)
        journal.progress(10)
        assertNull(interruptedImportNotice(journal.current(), "sha-abc", 10))
    }

    @Test
    fun anUnfinishedRunNamesHowFarItGot() {
        journal.begin("sha-abc", 5000, 1L)
        journal.progress(2500)
        val notice = interruptedImportNotice(journal.current(), "sha-abc", 5000)
        assertTrue("should name progress: $notice", notice!!.contains("2500 of 5000"))
    }

    /**
     * The word "skipped" sends a warning to the "content was dropped" panel;
     * this notice is the opposite, so it must not contain it.
     */
    @Test
    fun theNoticeIsNotClassifiedAsDroppedContent() {
        journal.begin("sha-abc", 10, 1L)
        journal.progress(3)
        val notice = interruptedImportNotice(journal.current(), "sha-abc", 10)!!
        val groups = splitImportWarnings(listOf(notice))
        assertTrue("must not read as a drop: $notice", groups.skipped.isEmpty())
        assertEquals(1, groups.notes.size)
    }
}
