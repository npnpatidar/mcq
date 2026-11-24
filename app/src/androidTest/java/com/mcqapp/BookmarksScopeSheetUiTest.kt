package com.mcqapp

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import androidx.compose.ui.test.onAllNodesWithText
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
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.common.ScopeStep
import com.mcqapp.ui.history.BookmarksScreen
import com.mcqapp.ui.history.BookmarksScopeSheet
import com.mcqapp.ui.history.BookmarksScopeSheetContent
import com.mcqapp.ui.history.BookmarksScopeTrigger
import com.mcqapp.ui.history.BookmarksUiState
import com.mcqapp.ui.history.BookmarksViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Bookmarks scope sheet's choreography, hosted stateless so no repository
 * or ViewModel is needed, plus the scoped-empty screen state. These assertions
 * run on the CI emulator; the ARM64 host cannot run instrumentation tests.
 */
@RunWith(AndroidJUnit4::class)
class BookmarksScopeSheetUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<McqApplication>()

    /** Locale-resolved expected spoken label: never assert an English literal. */
    private fun qualified(title: String, paper: String): String =
        app.getString(R.string.search_scope_category_with_paper, title, paper)

    private var open by mutableStateOf(false)
    private var step by mutableStateOf(ScopeStep.PAPERS)
    private var chosenPaperId by mutableStateOf<String?>(null)
    private var selectedCategoryIds by mutableStateOf<Set<String>>(emptySet())
    private var triggerState by mutableStateOf(BookmarksUiState())
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
            // Same title as p1-c1: only the paper qualifier tells them apart.
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
        triggerState = BookmarksUiState()
        pickedPapers.clear()
        toggledCategories.clear()
        categoryClears = 0
        scopeClears = 0
        dismisses = 0
    }

    private fun setSheet(papersForSheet: List<Paper> = papers) {
        compose.setContent {
            if (open) {
                BookmarksScopeSheetContent(
                    papers = papersForSheet,
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
                        triggerState = BookmarksUiState()
                    },
                    onBackToPapers = { step = ScopeStep.PAPERS },
                    onDismiss = {
                        dismisses++
                        open = false
                    }
                )
            }
        }
    }

    /** Mirrors BookmarksScreen: opening is the ONLY event that seeds step/paper. */
    private fun openTrigger(papersForTrigger: List<Paper> = papers) {
        compose.setContent {
            BookmarksScopeTrigger(state = triggerState, papers = papersForTrigger) {
                val applied = triggerState.filterPaperId
                chosenPaperId = applied
                step = if (applied != null) ScopeStep.CATEGORIES else ScopeStep.PAPERS
                open = true
            }
            if (open) {
                BookmarksScopeSheetContent(
                    papers = papersForTrigger,
                    selectedCategoryIds = selectedCategoryIds,
                    step = step,
                    chosenPaperId = chosenPaperId,
                    onChoosePaper = { id ->
                        pickedPapers += id
                        chosenPaperId = id
                        step = ScopeStep.CATEGORIES
                    },
                    onToggleCategory = { id -> toggledCategories += id },
                    onClearCategoriesInPaper = { categoryClears++ },
                    onClearScope = { scopeClears++ },
                    onBackToPapers = { step = ScopeStep.PAPERS },
                    onDismiss = { dismisses++; open = false }
                )
            }
        }
    }

    @Test
    fun triggerOpensThePapersStepAndAllPapersAppliesImmediately() {
        openTrigger()
        compose.onNodeWithTag("bookmarks-scope-button").assertHasClickAction().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("bookmarks-scope-title-papers").assertIsDisplayed()
        compose.onNodeWithTag("bookmarks-scope-papers-list").assertIsDisplayed()
        compose.onNodeWithTag("bookmarks-scope-paper-p1").assertIsDisplayed()
        compose.onNodeWithTag("bookmarks-scope-paper-p2").assertIsDisplayed()

        compose.onNodeWithTag("bookmarks-scope-all-papers").performClick()
        compose.waitForIdle()
        assertEquals(1, scopeClears)
        assertEquals(1, dismisses)
        assertTrue(pickedPapers.isEmpty())
    }

    @Test
    fun tappingPaperShowsOnlyItsCategoriesAndSeveralTogglesWork() {
        open = true
        setSheet()

        compose.onNodeWithTag("bookmarks-scope-paper-p1").performClick()
        compose.waitForIdle()
        assertEquals(listOf("p1"), pickedPapers)
        compose.onNodeWithTag("bookmarks-scope-title-categories")
            .assertContentDescriptionEquals("Physics")
        compose.onNodeWithTag("bookmarks-scope-category-p1-c1").assertIsDisplayed()
        compose.onNodeWithTag("bookmarks-scope-category-p1-c2a").assertIsDisplayed()
        compose.onNodeWithTag("bookmarks-scope-category-p2-c1").assertDoesNotExist()
        compose.onNodeWithTag("bookmarks-scope-all-categories").assertIsDisplayed()

        compose.onNodeWithTag("bookmarks-scope-category-p1-c1").performClick()
        compose.onNodeWithTag("bookmarks-scope-category-p1-c2a").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("p1-c1", "p1-c2a"), toggledCategories)

        compose.onNodeWithTag("bookmarks-scope-all-categories").performClick()
        compose.waitForIdle()
        assertEquals(1, categoryClears)
        assertEquals(listOf("p1"), pickedPapers) // clearing categories keeps the paper
    }

    @Test
    fun duplicateCategoryTitlesAreDistinguishedByTheirPaper() {
        open = true
        setSheet()
        compose.onNodeWithTag("bookmarks-scope-paper-p1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("bookmarks-scope-category-p1-c1")
            .assertContentDescriptionEquals(qualified("Mechanics", "Physics"))
        compose.onNodeWithContentDescription(qualified("Mechanics", "Physics")).assertIsDisplayed()
        compose.onNodeWithContentDescription(qualified("Mechanics", "Chemistry")).assertDoesNotExist()

        compose.onNodeWithTag("bookmarks-scope-back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("bookmarks-scope-title-papers").assertIsDisplayed()

        compose.onNodeWithTag("bookmarks-scope-paper-p2").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("bookmarks-scope-category-p2-c1")
            .assertContentDescriptionEquals(qualified("Mechanics", "Chemistry"))
        compose.onNodeWithContentDescription(qualified("Mechanics", "Physics")).assertDoesNotExist()
    }

    @Test
    fun sameTitledCategoriesInOnePaperUseTheirParentPath() {
        val paper = Paper(
            id = "d",
            title = "Delta",
            categories = listOf(
                CategoryNode(
                    id = "d-a", paperId = "d", title = "Algebra", parentId = null,
                    children = listOf(
                        CategoryNode(id = "d-a-m", paperId = "d", title = "Mechanics", parentId = "d-a")
                    )
                ),
                CategoryNode(
                    id = "d-g", paperId = "d", title = "Geometry", parentId = null,
                    children = listOf(
                        CategoryNode(id = "d-g-m", paperId = "d", title = "Mechanics", parentId = "d-g")
                    )
                )
            )
        )
        open = true
        setSheet(listOf(paper))
        compose.onNodeWithTag("bookmarks-scope-paper-d").performClick()
        compose.waitForIdle()

        val first = qualified("Algebra › Mechanics", "Delta")
        val second = qualified("Geometry › Mechanics", "Delta")
        compose.onNodeWithTag("bookmarks-scope-category-d-a-m").assertContentDescriptionEquals(first)
        compose.onNodeWithTag("bookmarks-scope-category-d-g-m").assertContentDescriptionEquals(second)
        assertNotEquals("same-titled siblings must sound different", first, second)
        compose.onNodeWithText(first).assertIsDisplayed()
        compose.onNodeWithText(second).assertIsDisplayed()
    }

    @Test
    fun triStateRowsExposeCheckboxRoleAndLocalisedStateDescriptions() {
        // Only one child of Optics is ticked: Optics itself is indeterminate.
        selectedCategoryIds = setOf("p1-c2a")
        open = true
        step = ScopeStep.CATEGORIES
        chosenPaperId = "p1"
        setSheet()

        val selected = app.getString(R.string.r2_study_option_selected)
        val partial = app.getString(R.string.search_scope_category_partial)
        val notSelected = app.getString(R.string.r2_study_option_not_selected)
        compose.onNodeWithTag("bookmarks-scope-category-p1-c2a").assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, selected))
        compose.onNodeWithTag("bookmarks-scope-category-p1-c2").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Indeterminate)
        ).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, partial))
        compose.onNodeWithTag("bookmarks-scope-category-p1-c1").assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, notSelected))
    }

    @Test
    fun backOnStepTwoReturnsToPapersWithoutDismissing() {
        open = true
        setSheet()
        compose.onNodeWithTag("bookmarks-scope-paper-p1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("bookmarks-scope-back").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("bookmarks-scope-title-papers").assertIsDisplayed()
        compose.onNodeWithTag("bookmarks-scope-categories-list").assertDoesNotExist()
        assertEquals(0, dismisses)
        assertEquals(listOf("p1"), pickedPapers) // back changes no filter
    }

    @Test
    fun narrowScreenKeepsRowsReachableAndSpeaksTheFullTitle() {
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
        compose.setContent {
            Box(Modifier.width(320.dp).height(560.dp)) {
                BookmarksScopeSheetContent(
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

        compose.onNodeWithTag("bookmarks-scope-back").assertIsDisplayed()
        compose.onNodeWithTag("bookmarks-scope-title-categories")
            .assertContentDescriptionEquals(longTitle)
        val last = compose.onNodeWithTag("bookmarks-scope-category-long-c12")
            .performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val bounds = last.getBoundsInRoot()
        assertTrue("row target must be at least 48 dp", (bounds.bottom - bounds.top).value >= 48f)
    }

    @Test
    fun triggerCountsTickedRowsNotExpandedSubtreeIds() {
        openTrigger()
        compose.onNodeWithText(app.getString(R.string.search_filter_all_papers)).assertIsDisplayed()

        compose.runOnIdle { triggerState = BookmarksUiState(filterPaperId = "p1") }
        compose.onNodeWithText("Physics").assertIsDisplayed()

        // Parent + child are both in state (the flat contract), but the learner
        // ticked ONE row, so the summary must read "1 category".
        compose.runOnIdle {
            triggerState = BookmarksUiState(
                filterPaperId = "p1", categoryFilterIds = setOf("p1-c2", "p1-c2a")
            )
        }
        compose.onNodeWithText(
            app.resources.getQuantityString(
                R.plurals.search_scope_summary_categories, 1, "Physics", 1
            )
        ).assertIsDisplayed()

        compose.runOnIdle {
            triggerState = BookmarksUiState(
                filterPaperId = "p1", categoryFilterIds = setOf("p1-c1", "p1-c2", "p1-c2a")
            )
        }
        compose.onNodeWithText(
            app.resources.getQuantityString(
                R.plurals.search_scope_summary_categories, 2, "Physics", 2
            )
        ).assertIsDisplayed()
    }
}

/**
 * System Back goes through the modal dialog's own OnBackPressedDispatcher, not
 * the Activity's. Hosted on a real Activity like the Search/Passages tests: a
 * plain Compose rule does not focus the dialog window reliably enough for sent
 * key events.
 */
@RunWith(AndroidJUnit4::class)
class BookmarksScopeSheetSystemBackUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun systemBackOnStepTwoStepsBackAndSecondBackDismisses() {
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
                BookmarksScopeSheet(
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
            compose.onAllNodesWithTag("bookmarks-scope-sheet")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("bookmarks-scope-category-p1-c1").assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        fun pressSystemBack() {
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            instrumentation.waitForIdleSync()
            compose.waitForIdle()
        }

        pressSystemBack()
        compose.onNodeWithTag("bookmarks-scope-title-papers").assertIsDisplayed()
        compose.onNodeWithTag("bookmarks-scope-sheet").assertIsDisplayed()
        assertEquals("Back on step 2 must not dismiss", 0, dismisses)

        // After stepping back, M3's own dismiss callback must still work.
        pressSystemBack()
        compose.onNodeWithTag("bookmarks-scope-sheet").assertDoesNotExist()
        assertEquals("Back on step 1 must dismiss", 1, dismisses)
    }
}

/**
 * The screen-level scoped empty state and action gating. Driven through the
 * ViewModel scope directly so the assertions are deterministic on CI. The real
 * repository opens SQLite, so this class runs on the x86_64 emulator only.
 */
@RunWith(AndroidJUnit4::class)
class BookmarksScopedEmptyUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<McqApplication>()
    private val repository get() = app.repository

    private fun question(id: String, text: String, categoryId: String) = Question(
        id = id,
        categoryId = categoryId,
        text = text,
        options = listOf(QuestionOption("$id-a", "Alpha")),
        correctOptionIds = setOf("$id-a")
    )

    @Before
    fun seed() = runBlocking {
        repository.ensurePaperAndCategory(WITH_PAPER, WITH_TITLE, WITH_CAT, "Scoped With")
        repository.ensurePaperAndCategory(WITHOUT_PAPER, WITHOUT_TITLE, WITHOUT_CAT, "Scoped Without")
        repository.saveQuestion(question("scopebox-q1", "SCOPED bookmarked question?", WITH_CAT))
        if (!repository.isBookmarked("scopebox-q1")) repository.toggleBookmark("scopebox-q1")
    }

    @org.junit.After
    fun cleanup() = runBlocking {
        repository.deletePaper(WITH_PAPER)
        repository.deletePaper(WITHOUT_PAPER)
    }

    private fun waitFor(text: String) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun showScreen(scopePaperId: String?): BookmarksViewModel {
        val vm = BookmarksViewModel(app)
        if (scopePaperId != null) vm.choosePaper(scopePaperId)
        compose.setContent {
            BookmarksScreen(repository = repository, navController = rememberNavController(), viewModel = vm)
        }
        return vm
    }

    @Test
    fun scopedEmptyStateIsDistinctAndTheTriggerStaysReachable() {
        showScreen(WITHOUT_PAPER)
        waitFor(app.getString(R.string.no_bookmarks_in_scope))
        compose.onNodeWithText(app.getString(R.string.no_bookmarks_in_scope)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.no_bookmarked_questions)).assertDoesNotExist()
        // A scope with no hits must still be changeable.
        compose.onNodeWithTag("bookmarks-scope-button").assertIsDisplayed().assertHasClickAction()
        // Select/Share are gated on the scoped count, not the global one.
        assertTrue(
            compose.onAllNodesWithContentDescription(app.getString(R.string.export_bookmarks))
                .fetchSemanticsNodes().isEmpty()
        )
    }

    private fun waitForSummary(paperTitle: String, count: Int) {
        val label = app.resources.getQuantityString(
            R.plurals.search_scope_summary_categories, count, paperTitle, count
        )
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun scopedCountGatesShareAndFollowsTheWholeAppliedScope() {
        val vm = showScreen(WITH_PAPER)
        waitFor("SCOPED bookmarked question?")
        // Papers must be loaded before a category toggle can resolve; the
        // trigger naming the paper is the signal that they are.
        waitFor(WITH_TITLE)
        compose.runOnIdle { vm.toggleCategory(WITH_CAT) }
        waitForSummary(WITH_TITLE, 1)
        compose.onNodeWithContentDescription(app.getString(R.string.export_bookmarks)).assertIsDisplayed()

        // Switching paper drops the old paper's categories and its bookmark.
        compose.runOnIdle { vm.choosePaper(WITHOUT_PAPER) }
        waitFor(app.getString(R.string.no_bookmarks_in_scope))
        assertTrue(
            compose.onAllNodesWithContentDescription(app.getString(R.string.export_bookmarks))
                .fetchSemanticsNodes().isEmpty()
        )

        // Scope back to the paper AND category that hold the bookmark: the row
        // and share action return, proving the count followed the whole scope.
        compose.runOnIdle { vm.choosePaper(WITH_PAPER) }
        waitFor(WITH_TITLE)
        compose.runOnIdle { vm.toggleCategory(WITH_CAT) }
        waitForSummary(WITH_TITLE, 1)
        waitFor("SCOPED bookmarked question?")
        compose.onNodeWithContentDescription(app.getString(R.string.export_bookmarks)).assertIsDisplayed()
    }

    private companion object {
        const val WITH_PAPER = "scopebox-with"
        const val WITHOUT_PAPER = "scopebox-without"
        const val WITH_CAT = "scopebox-cat-with"
        const val WITHOUT_CAT = "scopebox-cat-without"
        const val WITH_TITLE = "Scoped Paper With"
        const val WITHOUT_TITLE = "Scoped Paper Without"
    }
}
