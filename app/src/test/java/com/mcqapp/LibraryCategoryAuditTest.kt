package com.mcqapp

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.ui.library.LibraryViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Library category-edit regressions: a deleted category must not stay ticked
 * in the selection, and a refused re-parent must not leave a lone rename.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class LibraryCategoryAuditTest {

    private val app: McqApplication
        get() = ApplicationProvider.getApplicationContext<McqApplication>()

    @Before
    fun startClean() = runBlocking {
        runCatching { app.repository.deletePaper("p1") }
    }

    @After
    fun cleanup() = runBlocking {
        runCatching { app.repository.deletePaper("p1") }
    }

    /** Pumps the main looper until [condition] holds, for Main.immediate work. */
    private fun pumpUntil(condition: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            looper.runToEndOfTasks()
            Thread.sleep(20)
        }
    }

    private suspend fun nestedPaper() {
        app.repository.ensurePaperAndCategory("p1", "Paper", "cRoot", "Root")
        app.repository.db().categoryDao().upsert(
            CategoryEntity(
                id = "cChild", paperId = "p1", title = "Child", parentId = "cRoot", sortOrder = 0
            )
        )
    }

    @Test
    fun tickThenDeleteCategoryRemovesIdFromSelection() = runBlocking {
        nestedPaper()
        val vm = LibraryViewModel(app)
        pumpUntil { vm.papers.value.any { it.id == "p1" } }

        // Ticking the parent writes the whole subtree on (CategorySelection).
        vm.toggleCategory("p1", "cRoot")
        assertEquals(setOf("cRoot", "cChild"), vm.selectedFor("p1"))

        vm.deleteCategory("cRoot")
        pumpUntil { runBlocking { app.repository.db().categoryDao().getById("cRoot") } == null }
        pumpUntil { !vm.selectedFor("p1").contains("cRoot") }

        // The dead id would otherwise expand Test/Study scope to empty and
        // block Start with "no questions". The surviving child stays ticked.
        assertFalse(
            "a ticked, deleted category must not stay selected",
            vm.selectedFor("p1").contains("cRoot")
        )
        assertTrue("the deleted category row is gone", app.repository.db().categoryDao().getById("cRoot") == null)
    }

    @Test
    fun renameWithRefusedReparentLeavesNoPartialRename() = runBlocking {
        nestedPaper()
        val vm = LibraryViewModel(app)
        pumpUntil { vm.papers.value.any { it.id == "p1" } }

        // cChild is cRoot's own descendant, so making cRoot its child is a
        // cycle: reparent refuses. The rename must roll back with it.
        vm.editCategory("cRoot", "Renamed", "cChild")
        pumpUntil { vm.exportError.value != null }

        assertEquals(
            "a refused reparent must not leave the rename behind",
            "Root",
            app.repository.db().categoryDao().getById("cRoot")!!.title
        )
        assertTrue(
            "a refused edit must tell the user",
            vm.exportError.value?.contains("Check its name and parent") == true
        )
    }

    @Test
    fun titleOnlyEditStillRenames() = runBlocking {
        nestedPaper()
        val vm = LibraryViewModel(app)
        pumpUntil { vm.papers.value.any { it.id == "p1" } }

        // cRoot is already a root: parentId == null is a no-op reparent, not a
        // refusal, so a title-only edit must still commit.
        vm.editCategory("cRoot", "Renamed", null)
        pumpUntil { runBlocking { app.repository.db().categoryDao().getById("cRoot")?.title } == "Renamed" }

        assertEquals("Renamed", app.repository.db().categoryDao().getById("cRoot")!!.title)
        assertNull("a valid edit must not surface an error", vm.exportError.value)
    }
}
