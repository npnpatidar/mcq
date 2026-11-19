package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Passage
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.editor.PassagesViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Round-2 E1 at the ViewModel level: raising the cross-paper warning unmounts
 * the edit dialog, whose old `remember` fields were destroyed. Cancel must
 * reopen with the exact title/body/category the user typed. Robolectric/SQLite
 * only runs on x86_64 CI; the pure store test covers the same seam everywhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class Round2EditorDraftVmTest {

    private lateinit var app: McqApplication

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Context>() as McqApplication
        runBlocking {
            app.repository.ensurePaperAndCategory("r2-p1", "R2 Paper 1", "r2-c1", "R2 Cat 1")
            app.repository.ensurePaperAndCategory("r2-p2", "R2 Paper 2", "r2-c2", "R2 Cat 2")
        }
    }

    @After
    fun tearDown() {
        try {
            AppDatabase::class.java
                .getDeclaredField("INSTANCE")
                .apply { isAccessible = true }
                .set(null, null)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    @Test
    fun cancelThenReopenKeepsTheTypedDraft() = runBlocking {
        val pid = app.repository.savePassage(
            Passage(id = "", categoryId = "r2-c1", title = "Stored title")
        )
        app.repository.saveQuestion(
            Question(
                id = "r2-q1",
                categoryId = "r2-c1",
                elements = listOf(ContentElement.TextElement("Q")),
                options = listOf(
                    QuestionOption("r2-q1-a", "A"),
                    QuestionOption("r2-q1-b", "B")
                ),
                correctOptionIds = setOf("r2-q1-a"),
                passageId = pid
            )
        )
        val vm = PassagesViewModel(app)
        vm.openForm(app.repository.getPassage(pid))
        vm.setFormTitle("Typed title")
        vm.setFormText("Typed body")
        vm.setFormCategory("r2-c2")

        // Save raises the cross-paper warning and unmounts the dialog.
        vm.stageSave(Passage(pid, "r2-c2", "Typed title"), "r2-p1", "r2-p2")
        assertTrue(awaitCondition { vm.pendingCrossPaperSave.value?.id == pid })
        assertEquals("Typed title", vm.form.value?.title)

        // Cross-paper Cancel clears only the warning; the form must survive so
        // the remounted dialog shows the exact edits.
        vm.cancelPending()
        assertEquals(null, vm.pendingCrossPaperSave.value)
        val reopened = vm.form.value!!
        assertEquals("Typed title", reopened.title)
        assertEquals("Typed body", reopened.text)
        assertEquals("r2-c2", reopened.categoryId)
        assertEquals("r2-c1", runBlocking { app.repository.getPassage(pid) }?.categoryId)
    }
}
