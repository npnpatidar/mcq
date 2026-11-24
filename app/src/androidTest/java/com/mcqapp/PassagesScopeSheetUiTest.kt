package com.mcqapp

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Paper
import com.mcqapp.domain.Passage
import com.mcqapp.ui.common.ScopeStep
import com.mcqapp.ui.editor.PassagesScreen
import com.mcqapp.ui.editor.PassagesScopeSheet
import com.mcqapp.ui.editor.PassagesScopeSheetContent
import com.mcqapp.ui.editor.PassagesScopeTrigger
import com.mcqapp.ui.editor.PassagesUiState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The passages scope sheet's choreography, hosted stateless like
 * SearchScopeSheetUiTest so no repository is needed. These assertions run on
 * the CI emulator; the ARM64 host cannot run instrumentation tests.
 */
@RunWith(AndroidJUnit4::class)
class PassagesScopeSheetUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<McqApplication>()

    private var open by mutableStateOf(false)
    private var step by mutableStateOf(ScopeStep.PAPERS)
    private var chosenPaperId by mutableStateOf<String?>(null)
    private var selectedCategoryIds by mutableStateOf<Set<String>>(emptySet())
    private var triggerState by mutableStateOf(PassagesUiState())
    private val pickedPapers = mutableListOf<String>()
    private val toggledCategories = mutableListOf<String>()
    private var categoryClears = 0
    private var scopeClears = 0
    private var dismisses = 0

    private val physics = Paper(
        id = "p1",
        title = "Physics",
        categories = listOf(
            CategoryNode(id = "p1-c1", paperId = "p1", title = "Mechanics", parentId = null),
            CategoryNode(
                id = "p1-c2", paperId = "p1", title = "Optics", parentId = null,
                children = listOf(
                    CategoryNode(id = "p1-c2a", paperId = "p1", title = "Lenses", parentId = "p1-c2")
                )
            )
        )
    )
    private val chemistry = Paper(
        id = "p2",
        title = "Chemistry",
        categories = listOf(
            CategoryNode(id = "p2-c1", paperId = "p2", title = "Mechanics", parentId = null)
        )
    )
    private val papers = listOf(physics, chemistry)

    @Before
    fun reset() {
        open = false
        step = ScopeStep.PAPERS
        chosenPaperId = null
        selectedCategoryIds = emptySet()
        triggerState = PassagesUiState()
        pickedPapers.clear()
        toggledCategories.clear()
        categoryClears = 0
        scopeClears = 0
        dismisses = 0
    }

    @Composable
    private fun SheetContent() {
        PassagesScopeSheetContent(
            papers = papers,
            selectedCategoryIds = selectedCategoryIds,
            step = step,
            chosenPaperId = chosenPaperId,
            onChoosePaper = { id ->
                pickedPapers += id
                chosenPaperId = id
                step = ScopeStep.CATEGORIES
            },
            onToggleCategory = { id ->
                toggledCategories += id
                selectedCategoryIds = if (id in selectedCategoryIds) {
                    selectedCategoryIds - id
                } else {
                    selectedCategoryIds + id
                }
            },
            onClearCategoriesInPaper = {
                categoryClears++
                selectedCategoryIds = emptySet()
            },
            onClearScope = {
                scopeClears++
                selectedCategoryIds = emptySet()
                triggerState = PassagesUiState()
            },
            onBackToPapers = { step = ScopeStep.PAPERS },
            onDismiss = {
                dismisses++
                open = false
            }
        )
    }

    /** Mirrors PassagesScreen: only the opening event seeds the step and paper. */
    private fun openTrigger() {
        compose.setContent {
            PassagesScopeTrigger(
                state = triggerState,
                papers = papers,
                onClick = {
                    val applied = triggerState.paperFilterIds.singleOrNull()
                    chosenPaperId = applied
                    step = if (applied != null) ScopeStep.CATEGORIES else ScopeStep.PAPERS
                    open = true
                }
            )
            if (open) SheetContent()
        }
    }

    @Test
    fun triggerOpensThePapersStepAndAllPapersAppliesImmediately() {
        openTrigger()
        compose.onNodeWithTag("passages-scope-button").assertHasClickAction().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("passages-scope-title-papers").assertIsDisplayed()
        compose.onNodeWithTag("passages-scope-papers-list").assertIsDisplayed()
        compose.onNodeWithTag("passages-scope-paper-p1").assertIsDisplayed()
        compose.onNodeWithTag("passages-scope-paper-p2").assertIsDisplayed()

        compose.onNodeWithTag("passages-scope-all-papers").performClick()
        compose.waitForIdle()
        assertEquals(1, scopeClears)
        assertEquals(1, dismisses)
        assertTrue(pickedPapers.isEmpty())
    }

    @Test
    fun tappingPaperShowsOnlyItsCategoriesAndTogglesApply() {
        open = true
        compose.setContent { SheetContent() }

        compose.onNodeWithTag("passages-scope-paper-p1").performClick()
        compose.waitForIdle()
        assertEquals(listOf("p1"), pickedPapers)
        compose.onNodeWithTag("passages-scope-title-categories")
            .assertContentDescriptionEquals("Physics")
        compose.onNodeWithTag("passages-scope-category-p1-c1").assertIsDisplayed()
        compose.onNodeWithTag("passages-scope-category-p1-c2a").assertIsDisplayed()
        compose.onNodeWithTag("passages-scope-category-p2-c1").assertDoesNotExist()
        compose.onNodeWithTag("passages-scope-all-categories").assertIsDisplayed()

        compose.onNodeWithTag("passages-scope-category-p1-c1").performClick()
        compose.onNodeWithTag("passages-scope-category-p1-c2a").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("p1-c1", "p1-c2a"), toggledCategories)

        compose.onNodeWithTag("passages-scope-all-categories").performClick()
        compose.waitForIdle()
        assertEquals(1, categoryClears)
        assertEquals(listOf("p1"), pickedPapers) // clearing categories keeps the paper
    }

    @Test
    fun backReturnsToPapersWithoutDismissingAndReopenKeepsTheSelection() {
        openTrigger()
        compose.onNodeWithTag("passages-scope-button").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("passages-scope-paper-p1").performClick()
        compose.onNodeWithTag("passages-scope-category-p1-c1").performClick()
        compose.waitForIdle()

        compose.runOnIdle {
            triggerState = triggerState.copy(
                paperFilterIds = setOf("p1"), categoryFilterIds = setOf("p1-c1")
            )
        }
        compose.onNodeWithTag("passages-scope-back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("passages-scope-title-papers").assertIsDisplayed()
        compose.onNodeWithTag("passages-scope-categories-list").assertDoesNotExist()
        assertEquals(0, dismisses)

        // Dismiss, then reopen: a scoped list goes straight to its categories.
        compose.runOnIdle { open = false }
        compose.waitForIdle()
        compose.onNodeWithTag("passages-scope-button").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("passages-scope-title-categories")
            .assertContentDescriptionEquals("Physics")
        compose.onNodeWithTag("passages-scope-category-p1-c1").assertIsOn()
        compose.onNodeWithTag("passages-scope-category-p1-c2").assertIsOff()
    }

    @Test
    fun triggerCountsTickedRowsNotExpandedSubtreeIds() {
        openTrigger()
        compose.onNodeWithText(app.getString(R.string.search_filter_all_papers)).assertIsDisplayed()

        compose.runOnIdle { triggerState = PassagesUiState(paperFilterIds = setOf("p1")) }
        compose.onNodeWithText("Physics").assertIsDisplayed()

        // Parent and child are both stored flat, but one row was ticked.
        compose.runOnIdle {
            triggerState = PassagesUiState(
                paperFilterIds = setOf("p1"),
                categoryFilterIds = setOf("p1-c2", "p1-c2a")
            )
        }
        compose.onNodeWithText(
            app.resources.getQuantityString(
                R.plurals.search_scope_summary_categories, 1, "Physics", 1
            )
        ).assertIsDisplayed()
    }

    @Test
    fun narrow320DpSheetKeepsRowsReachableAndSpeaksTheFullTitle() {
        val longTitle = "Physics with an unusually long paper name for a narrow phone screen"
        val longPaper = Paper(
            id = "long",
            title = longTitle,
            categories = (1..12).map { index ->
                CategoryNode(
                    id = "long-c$index", paperId = "long",
                    title = "A very long category name number $index", parentId = null
                )
            }
        )
        open = true
        step = ScopeStep.CATEGORIES
        chosenPaperId = "long"
        compose.setContent {
            Box(Modifier.width(320.dp).height(560.dp)) {
                PassagesScopeSheetContent(
                    papers = listOf(longPaper),
                    selectedCategoryIds = emptySet(),
                    step = ScopeStep.CATEGORIES,
                    chosenPaperId = "long",
                    onChoosePaper = {},
                    onToggleCategory = {},
                    onClearCategoriesInPaper = {},
                    onClearScope = {},
                    onBackToPapers = {},
                    onDismiss = {}
                )
            }
        }

        compose.onNodeWithTag("passages-scope-back").assertIsDisplayed()
        compose.onNodeWithTag("passages-scope-title-categories")
            .assertContentDescriptionEquals(longTitle)
        val last = compose.onNodeWithTag("passages-scope-category-long-c12")
            .performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val rowBounds = last.getBoundsInRoot()
        assertTrue("row target must be at least 48 dp",
            (rowBounds.bottom - rowBounds.top).value >= 48f)
    }

}

/**
 * System Back through the modal dialog's own dispatcher, hosted on a real
 * Activity like SearchScopeSheetSystemBackUiTest: a plain Compose rule does not
 * focus the dialog window reliably enough for sent key events.
 */
@RunWith(AndroidJUnit4::class)
class PassagesScopeSheetSystemBackUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun systemBackOnStepTwoStepsBackWithoutDismissing() {
        var step by mutableStateOf(ScopeStep.CATEGORIES)
        var show by mutableStateOf(true)
        var dismisses = 0
        val paper = Paper(
            id = "p1",
            title = "Physics",
            categories = listOf(
                CategoryNode(id = "p1-c1", paperId = "p1", title = "Mechanics", parentId = null)
            )
        )
        compose.setContent {
            if (show) {
                PassagesScopeSheet(
                    papers = listOf(paper),
                    selectedCategoryIds = emptySet(),
                    step = step,
                    chosenPaperId = "p1",
                    onChoosePaper = {},
                    onToggleCategory = {},
                    onClearCategoriesInPaper = {},
                    onClearScope = {},
                    onBackToPapers = { step = ScopeStep.PAPERS },
                    onDismiss = { dismisses++; show = false }
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("passages-scope-sheet").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("passages-scope-category-p1-c1").assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        instrumentation.waitForIdleSync()
        compose.waitForIdle()

        compose.onNodeWithTag("passages-scope-title-papers").assertIsDisplayed()
        compose.onNodeWithTag("passages-scope-sheet").assertIsDisplayed()
        assertEquals(0, dismisses)
    }
}

/**
 * The real screen, with a real repository: the scope must narrow the passage
 * rows immediately, and passages that exist outside the scope get their own
 * empty state rather than the "no passages yet" one.
 */
@RunWith(AndroidJUnit4::class)
class PassagesScopeFilterUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    // Only papers this test instance created; deleting a paper cascades its
    // categories and the passages seeded into them. Never touch sample data or
    // another test's papers (PassagesUiTest uses its own pui-* ids).
    private val seededPaperIds = mutableSetOf<String>()

    private fun seedPaper(id: String, title: String, categoryId: String, categoryTitle: String) {
        seededPaperIds += id
        runBlocking { repository.ensurePaperAndCategory(id, title, categoryId, categoryTitle) }
    }

    private fun seedPassage(id: String, categoryId: String, title: String) = runBlocking {
        repository.savePassage(Passage(id = id, categoryId = categoryId, title = title))
    }

    @After
    fun cleanup() = runBlocking {
        seededPaperIds.forEach { repository.deletePaper(it) }
        seededPaperIds.clear()
    }

    private fun waitForContent(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForContentGone(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription(text, substring = true)
                .fetchSemanticsNodes().isEmpty()
        }
    }

    /** A sheet open at step 2 needs two backs: step back, then dismiss. */
    private fun dismissSheetFromCategories() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(2) {
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            instrumentation.waitForIdleSync()
            compose.waitForIdle()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("passages-scope-sheet").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun choosingAPaperAndCategoryNarrowsThePassageListImmediately() {
        seedPaper("psf-p1", "PSF Physics", "psf-c1", "PSF Mechanics")
        seedPaper("psf-p2", "PSF Chemistry", "psf-c2", "PSF Organic")
        seedPaper("psf-empty", "PSF Empty", "psf-c3", "PSF Nothing")
        seedPassage("psf-a", "psf-c1", "PSF Alpha Passage")
        seedPassage("psf-b", "psf-c2", "PSF Beta Passage")

        compose.setContent { PassagesScreen(repository, rememberNavController()) }
        waitForContent("PSF Alpha Passage")
        waitForContent("PSF Beta Passage")
        // With passages to show there is nothing to recover from.
        compose.onNodeWithTag("passages-scope-clear").assertDoesNotExist()
        compose.onNodeWithTag("passages-scope-button").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("passages-scope-paper-psf-p1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("passages-scope-category-psf-c1").performClick()
        compose.waitForIdle()
        dismissSheetFromCategories()

        waitForContentGone("PSF Beta Passage")
        compose.onNodeWithContentDescription("PSF Alpha Passage").assertIsDisplayed()
        compose.onNodeWithTag("passages-scope-empty").assertDoesNotExist()
        // A narrowed-but-nonempty list is not a dead end either.
        compose.onNodeWithTag("passages-scope-clear").assertDoesNotExist()

        // A paper with no passages is a different empty state.
        compose.onNodeWithTag("passages-scope-button").performClick()
        compose.onNodeWithTag("passages-scope-back").performClick()
        compose.onNodeWithTag("passages-scope-paper-psf-empty").performClick()
        compose.waitForIdle()
        dismissSheetFromCategories()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("passages-scope-empty").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("passages-scope-empty").assertIsDisplayed()
        compose.onNodeWithContentDescription("PSF Alpha Passage").assertDoesNotExist()
        compose.onNodeWithContentDescription("PSF Beta Passage").assertDoesNotExist()

        // One tap back to all papers: both rows return and the recovery action
        // disappears with the scoped-empty state.
        compose.onNodeWithTag("passages-scope-clear")
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
        waitForContent("PSF Alpha Passage")
        waitForContent("PSF Beta Passage")
        compose.onNodeWithTag("passages-scope-empty").assertDoesNotExist()
        compose.onNodeWithTag("passages-scope-clear").assertDoesNotExist()
        compose.onNodeWithText(appText(R.string.search_filter_all_papers)).assertIsDisplayed()
    }

    @Test
    fun longTitleEllipsizesOn320DpWithoutMovingTheFourActions() {
        val longTitle = "PSF an extraordinarily long passage title that must not wrap on a small phone"
        seedPaper("psf-long", "PSF Long Paper", "psf-cl", "PSF Long Cat")
        seedPassage("psf-long-row", "psf-cl", longTitle)

        compose.setContent {
            Box(Modifier.width(320.dp).height(560.dp)) {
                PassagesScreen(repository, rememberNavController())
            }
        }
        waitForContent(longTitle)
        val title = compose.onNodeWithContentDescription(longTitle).assertIsDisplayed()
        val titleBounds = title.getBoundsInRoot()
        assertTrue("the title must stay on one line",
            (titleBounds.bottom - titleBounds.top).value <= 32f)

        listOf(R.string.move_up, R.string.move_down, R.string.edit_passage, R.string.delete_passage)
            .map { compose.onNodeWithContentDescription(appText(it)) }
            .forEach { icon ->
                icon.assertIsDisplayed()
                assertTrue("action must stay inside 320 dp",
                    icon.getBoundsInRoot().right.value <= 320f + 1f)
            }
    }

    private fun appText(id: Int): String =
        ApplicationProvider.getApplicationContext<McqApplication>().getString(id)
}
