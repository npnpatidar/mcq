package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.editor.EditorSource
import com.mcqapp.ui.editor.EditorViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * User-facing editor errors are plain localized copy; exception detail is a
 * log-only concern. Robolectric + the real repository, so the DB-backed case
 * runs on CI's x86_64 runners like the other editor DB tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class EditorErrorCopyTest {

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as McqApplication

    @After
    fun tearDown() {
        AppDatabase::class.java
            .getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .set(null, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    @Test
    fun aMissingImportDraftShowsAFriendlyMessage() {
        val vm = EditorViewModel(app, "gone-q", "p1", "c1", EditorSource.IMPORT)

        val message = vm.state.value.loadError
        assertEquals(app.getString(R.string.editor_import_draft_missing), message)
        assertFalse(
            "no technical detail belongs in the message: $message",
            message!!.contains("Exception", ignoreCase = true)
        )
    }

    @Test
    fun aFailedSaveShowsAFriendlyMessageWithoutRawException() = runBlocking {
        val question = Question(
            id = "q1",
            categoryId = "c1",
            text = "Q?",
            options = listOf(QuestionOption("q1-a", "A"), QuestionOption("q1-b", "B")),
            correctOptionIds = setOf("q1-a")
        )
        app.repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        app.repository.saveQuestion(question)
        val vm = EditorViewModel(app, "q1", "p1", "c1")
        assertTrue(awaitCondition { !vm.state.value.loading })

        app.repository.db().close()
        // Capture the real exception text a write now produces, so the
        // assertion names exactly the detail that must stay out of the UI.
        val raw = try {
            app.repository.saveQuestion(question)
            ""
        } catch (e: Exception) {
            e.message.orEmpty()
        }

        vm.save {}
        assertTrue(awaitCondition { vm.state.value.saveError != null })

        val message = vm.state.value.saveError!!
        assertEquals(app.getString(R.string.editor_save_failed), message)
        if (raw.isNotBlank()) {
            assertFalse(
                "raw exception detail must not reach the editor: $message",
                message.contains(raw)
            )
        }
    }
}
