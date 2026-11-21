package com.mcqapp

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.domain.ContentElement
import com.mcqapp.ui.importscreen.ImportViewModel
import com.mcqapp.ui.importscreen.buildImportFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The preview and the importer must key on one shape.
 *
 * An id-less one-option question gets a prompt-only id when it becomes a card.
 * Normalising only inside the importer left the preview holding the parser's
 * option-inclusive id: a delete could not match the row, and an editor save was
 * written under the new id and dropped, so the raw row imported anyway.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class FlashcardImportPreviewTest {

    private lateinit var app: McqApplication

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext() as McqApplication
    }

    private fun awaitIdle(condition: () -> Boolean): Boolean {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    /** No id: the parser mints one from text + options. */
    private val idLessOneOptionBank = """
        {
          "version": 1,
          "papers": [{
            "id": "p1", "title": "Deck",
            "categories": [{
              "id": "c1", "title": "Cat",
              "questions": [{
                "text": "Capital of France?",
                "options_elements": {"a": [{"type": "text", "content": "Paris"}]},
                "correctOptionIds": ["a"],
                "explanation_elements": [{"type": "text", "content": "Capital city."}]
              }]
            }]
          }]
        }
    """.trimIndent()

    private fun loadedView(): ImportViewModel {
        val viewModel = ImportViewModel(app)
        viewModel.loadJsonText(idLessOneOptionBank)
        assertTrue(
            "the preview must load",
            awaitIdle { !viewModel.state.value.loading && viewModel.state.value.questions.isNotEmpty() }
        )
        return viewModel
    }

    @Test
    fun thePreviewHoldsTheNormalisedCardShape() {
        val question = loadedView().state.value.questions.single()

        assertTrue("the preview row must be the card", question.options.isEmpty())
        assertEquals(
            LegacyParser.stableQuestionId("Capital of France?", emptyList()),
            question.id
        )
    }

    @Test
    fun deletingThePreviewCardRemovesItFromTheImportedFile() {
        val viewModel = loadedView()
        val card = viewModel.state.value.questions.single()

        viewModel.deleteQuestion(card.id)

        val file = buildImportFile(viewModel.state.value, viewModel.state.value.originalFile)
        val remaining = file.papers.flatMap { paper ->
            paper.categories.flatMap { it.questions }
        }
        assertTrue("the deleted row must not import: ${remaining.map { it.id }}", remaining.isEmpty())
    }

    @Test
    fun anEditorSaveIsWrittenBackUnderTheSameCardId() {
        val viewModel = loadedView()
        val card = viewModel.state.value.questions.single()
        val edited = card.copy(
            explanation = "Lyon",
            explanationElements = listOf(ContentElement.TextElement("Lyon"))
        )

        viewModel.updateQuestionFromImport(edited)

        val file = buildImportFile(viewModel.state.value, viewModel.state.value.originalFile)
        val written = file.papers.flatMap { paper ->
            paper.categories.flatMap { it.questions }
        }.single()
        assertEquals("the edit must be the row that imports", card.id, written.id)
        assertTrue("the edited card keeps its zero-option shape", written.options.isEmpty())
        assertEquals(
            listOf(ContentElement.TextElement("Lyon")),
            written.explanationElements
        )
    }
}
