package com.mcqapp

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.ui.importscreen.ImportDataHolder
import com.mcqapp.ui.importscreen.ImportScreen
import com.mcqapp.ui.importscreen.ImportSource
import com.mcqapp.ui.importscreen.ImportViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Import preview has no emulator tests. These pin the novel-name field,
 * the import button's running gate, the completion report and the Anki-only
 * scheduling notice. CI-only (real repository / SQLite, x86_64 emulator).
 */
@RunWith(AndroidJUnit4::class)
class ImportUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext() as McqApplication
    private val repository get() = app.repository

    /**
     * The ViewModel owns the import coroutine (viewModelScope). Owning the
     * store lets teardown call clear(), which cancels that scope, instead of
     * leaving a background import running against a paper we just deleted.
     */
    private lateinit var store: ViewModelStore
    private lateinit var vm: ImportViewModel

    @Before
    fun makeViewModel() {
        store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ImportViewModel(app) as T
            }
        }
        vm = ViewModelProvider(store, factory)[ImportViewModel::class.java]
    }

    @After
    fun stopImportAndCleanUp() {
        // Drain any in-flight run before touching its data: cancelling first
        // could leave `importing` stuck true if the coroutine never started.
        // Bounded state wait here, no fixed sleep.
        compose.waitUntil(30_000) { !vm.state.value.importing }
        // Now the scope is idle; clear the store so nothing can restart work
        // against the paper we are about to delete.
        store.clear()
        runBlocking { repository.deletePaper("uiimport-paper") }
        ImportDataHolder.pendingJsonBytes = null
        ImportDataHolder.pendingJsonUri = null
        ImportDataHolder.pendingDocxBytes = null
        ImportDataHolder.pendingDocxName = null
        ImportDataHolder.pendingEditQuestion = null
    }

    private fun sampleJson(count: Int = 1, textLen: Int = 0): ByteArray {
        val questions = (1..count).map { i ->
            val stem = if (textLen > 0) "Q".repeat(textLen) else "UIIMPORT question"
            QuestionDto(
                id = "uiimport-q$i",
                text = "$stem $i",
                options = listOf(OptionDto("a", "Alpha"), OptionDto("b", "Beta")),
                correctOptionIds = listOf("a")
            )
        }
        val file = McqFileDto(
            version = 1,
            papers = listOf(
                PaperDto(
                    id = "uiimport-paper",
                    title = "UITest Import Paper",
                    categories = listOf(
                        CategoryDto(id = "uiimport-cat", title = "Cat", questions = questions)
                    )
                )
            )
        )
        return Json.encodeToString(McqFileDto.serializer(), file).toByteArray(Charsets.UTF_8)
    }

    private fun open(bytes: ByteArray) {
        compose.setContent {
            ImportScreen(
                importBytes = bytes,
                importUri = null,
                navController = rememberNavController(),
                viewModel = vm
            )
        }
    }

    private fun waitForForm() {
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasSetTextAction() and hasText("Paper title")).fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /** A name absent from the suggestions must survive typing with focus kept. */
    @Test
    fun aNovelPaperNameKeepsFocusAndTheWholeValue() {
        open(sampleJson())
        waitForForm()

        val field = compose.onNode(hasSetTextAction() and hasText("Paper title"))
        field.performTextClearance()
        val focused = hasSetTextAction() and
            SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)
        fun focusedField() = compose.onAllNodes(focused).fetchSemanticsNodes().isNotEmpty()

        field.performTextInput("B")
        compose.waitUntil(10_000) { focusedField() }
        val focusedNodes = compose.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)
        ).fetchSemanticsNodes()
        assertTrue(
            "the editable field must keep focus, not the suggestion menu",
            focusedNodes.isNotEmpty() && focusedNodes.all { hasSetTextAction().matches(it) }
        )

        field.performTextInput("rand-new paper 2026")
        field.assertTextContains("Brand-new paper 2026")
        compose.waitUntil(10_000) { focusedField() }
    }

    /** The button is gated the moment a run starts, so a second tap is inert. */
    @Test
    fun theImportButtonIsDisabledOnceARunStarts() {
        // Enough rows that the running state is observable, small enough to
        // settle quickly; teardown cancels and drains the run before deleting.
        val count = 600
        val label = "Import $count new questions"
        open(sampleJson(count = count, textLen = 400))
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(label, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(label, substring = true).performClick()

        // import() sets importing=true synchronously before launching; the
        // button's enabled gate must close while the run is in flight. Without
        // that gate a rapid second tap can start a second, concurrent import.
        // Decide and assert inside one wait so a fast finishing run can't make
        // the node re-enable between a separate wait and assertion.
        var sawGated = false
        compose.waitUntil(10_000) {
            val nodes = compose.onAllNodesWithText(label, substring = true)
                .fetchSemanticsNodes()
            val gated = nodes.isNotEmpty() &&
                nodes.all { it.config.contains(SemanticsProperties.Disabled) }
            if (gated) sawGated = true
            gated
        }
        assertTrue("the import button must be disabled while a run is in flight", sawGated)
    }

    /** The completion dialog enumerates what the import actually added. */
    @Test
    fun theCompletionDialogReportsTheImportedCounts() {
        open(sampleJson())
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Import 1 new questions", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Import 1 new questions", substring = true).performClick()

        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(app.getString(R.string.import_complete))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Locale-proof the count lines: the report is rendered from plurals, so
        // match the same quantity string the dialog uses rather than English text.
        val oneNew = app.resources.getQuantityString(
            R.plurals.followup_import_new_questions, 1, 1
        )
        val oneDuplicate = app.resources.getQuantityString(
            R.plurals.followup_import_duplicate_questions, 1, 1
        )
        // This fixture imports one genuinely new question into a fresh paper, so
        // the report must list that one addition and no duplicate line: the
        // duplicate count is only rendered when it is non-zero. A "skipped"
        // line would claim a duplicate this import never produced.
        compose.onNodeWithText(oneNew, substring = true).assertExists()
        compose.onNodeWithText(oneDuplicate, substring = true).assertDoesNotExist()
    }

    /** Only an Anki-classified source shows the scheduling-memory notice. */
    @Test
    fun theAnkiNoticeAppearsOnlyForAnAnkiSource() {
        open(sampleJson())
        waitForForm()

        val notice = app.getString(R.string.anki_memory_notice_title)
        assertTrue(
            "a plain JSON bank must not show the Anki notice",
            compose.onAllNodesWithText(notice).fetchSemanticsNodes().isEmpty()
        )

        compose.runOnIdle { vm.markSource(ImportSource.ANKI) }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(notice).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(notice).assertExists()

        compose.runOnIdle { vm.markSource(ImportSource.JSON) }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(notice).fetchSemanticsNodes().isEmpty()
        }
    }
}
