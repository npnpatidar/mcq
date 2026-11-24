package com.mcqapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Paper
import com.mcqapp.ui.search.SearchScopeSheet
import com.mcqapp.ui.search.SearchScopeSheetContent
import com.mcqapp.ui.search.SearchScopeTrigger
import com.mcqapp.ui.search.SearchUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scope sheet's choreography, hosted stateless like StudyScopeDialogUiTest
 * so no repository or ViewModel is needed. These assertions run on the CI
 * emulator; the ARM64 host cannot run instrumentation tests.
 */
@RunWith(AndroidJUnit4::class)
class SearchScopeSheetUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<McqApplication>()

    /** Locale-resolved expected spoken label: never assert an English literal. */
    private fun qualified(title: String, paper: String): String =
        app.getString(R.string.search_scope_category_with_paper, title, paper)

    private var open by mutableStateOf(false)
    private var showCategories by mutableStateOf(false)
    private var chosenPaperId by mutableStateOf<String?>(null)
    private var selectedCategoryIds by mutableStateOf<Set<String>>(emptySet())
    private var triggerState by mutableStateOf(SearchUiState())
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
        showCategories = false
        chosenPaperId = null
        selectedCategoryIds = emptySet()
        triggerState = SearchUiState()
        pickedPapers.clear()
        toggledCategories.clear()
        categoryClears = 0
        scopeClears = 0
        dismisses = 0
    }

    @Composable
    private fun SheetContent(papersForSheet: List<Paper>) {
        SearchScopeSheetContent(
            papers = papersForSheet,
            selectedCategoryIds = selectedCategoryIds,
            showCategories = showCategories,
            chosenPaperId = chosenPaperId,
            onChoosePaper = { id ->
                pickedPapers += id
                chosenPaperId = id
                showCategories = true
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
                triggerState = SearchUiState()
            },
            onBackToPapers = { showCategories = false },
            onDismiss = {
                dismisses++
                open = false
            }
        )
    }

    private fun setSheet(papersForSheet: List<Paper> = papers) {
        compose.setContent {
            if (open) SheetContent(papersForSheet)
        }
    }

    /** Mirrors SearchScreen: opening is the ONLY event that seeds step/paper. */
    private fun openTrigger(papersForTrigger: List<Paper> = papers) {
        compose.setContent {
            SearchScopeTrigger(
                state = triggerState,
                papers = papersForTrigger,
                onClick = {
                    val applied = triggerState.paperFilterIds.singleOrNull()
                    chosenPaperId = applied
                    showCategories = applied != null
                    open = true
                }
            )
            if (open) SheetContent(papersForTrigger)
        }
    }

    @Test
    fun triggerOpensThePapersStepAndAllPapersAppliesImmediately() {
        openTrigger()
        compose.onNodeWithTag("search-scope-button").assertHasClickAction().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("search-scope-title-papers").assertIsDisplayed()
        compose.onNodeWithTag("search-scope-papers-list").assertIsDisplayed()
        compose.onNodeWithTag("search-scope-paper-p1").assertIsDisplayed()
        compose.onNodeWithTag("search-scope-paper-p2").assertIsDisplayed()

        compose.onNodeWithTag("search-scope-all-papers").performClick()
        compose.waitForIdle()
        assertEquals(1, scopeClears)
        assertEquals(1, dismisses)
        assertTrue(pickedPapers.isEmpty())
    }

    @Test
    fun tappingPaperShowsOnlyItsCategoriesAndSeveralTogglesWork() {
        open = true
        setSheet()

        compose.onNodeWithTag("search-scope-paper-p1").performClick()
        compose.waitForIdle()
        assertEquals(listOf("p1"), pickedPapers)
        compose.onNodeWithTag("search-scope-title-categories")
            .assertContentDescriptionEquals("Physics")
        compose.onNodeWithTag("search-scope-category-p1-c1").assertIsDisplayed()
        compose.onNodeWithTag("search-scope-category-p1-c2a").assertIsDisplayed()
        compose.onNodeWithTag("search-scope-category-p2-c1").assertDoesNotExist()
        compose.onNodeWithTag("search-scope-all-categories").assertIsDisplayed()

        compose.onNodeWithTag("search-scope-category-p1-c1").performClick()
        compose.onNodeWithTag("search-scope-category-p1-c2a").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("p1-c1", "p1-c2a"), toggledCategories)

        compose.onNodeWithTag("search-scope-all-categories").performClick()
        compose.waitForIdle()
        assertEquals(1, categoryClears)
        assertEquals(listOf("p1"), pickedPapers) // clearing categories keeps the paper
    }

    @Test
    fun duplicateCategoryTitlesAreDistinguishedByTheirPaper() {
        open = true
        setSheet()
        compose.onNodeWithTag("search-scope-paper-p1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("search-scope-category-p1-c1")
            .assertContentDescriptionEquals(qualified("Mechanics", "Physics"))
        compose.onNodeWithContentDescription(qualified("Mechanics", "Physics")).assertIsDisplayed()
        compose.onNodeWithContentDescription(qualified("Mechanics", "Chemistry")).assertDoesNotExist()

        compose.onNodeWithTag("search-scope-back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("search-scope-title-papers").assertIsDisplayed()

        compose.onNodeWithTag("search-scope-paper-p2").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("search-scope-category-p2-c1")
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
        compose.onNodeWithTag("search-scope-paper-d").performClick()
        compose.waitForIdle()

        val first = qualified("Algebra › Mechanics", "Delta")
        val second = qualified("Geometry › Mechanics", "Delta")
        compose.onNodeWithTag("search-scope-category-d-a-m").assertContentDescriptionEquals(first)
        compose.onNodeWithTag("search-scope-category-d-g-m").assertContentDescriptionEquals(second)
        assertNotEquals("same-titled siblings must sound different", first, second)
        compose.onNodeWithTag("search-scope-category-d-a-m").assertIsDisplayed()
        compose.onNodeWithTag("search-scope-category-d-g-m").assertIsDisplayed()
        // The path is also the visible subtitle text, not just the spoken label.
        compose.onNodeWithText(first).assertIsDisplayed()
        compose.onNodeWithText(second).assertIsDisplayed()
    }

    @Test
    fun triStateRowsExposeCheckboxRoleAndLocalisedStateDescriptions() {
        // Only one child of Optics is ticked: Optics itself is indeterminate.
        selectedCategoryIds = setOf("p1-c2a")
        open = true
        showCategories = true
        chosenPaperId = "p1"
        setSheet()

        val selected = app.getString(R.string.r2_study_option_selected)
        val partial = app.getString(R.string.search_scope_category_partial)
        val notSelected = app.getString(R.string.r2_study_option_not_selected)
        compose.onNodeWithTag("search-scope-category-p1-c2a").assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, selected))
        compose.onNodeWithTag("search-scope-category-p1-c2").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Indeterminate)
        ).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, partial))
        compose.onNodeWithTag("search-scope-category-p1-c1").assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, notSelected))
    }

    @Test
    fun backOnStepTwoReturnsToPapersWithoutDismissing() {
        open = true
        setSheet()
        compose.onNodeWithTag("search-scope-paper-p1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("search-scope-back").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("search-scope-title-papers").assertIsDisplayed()
        compose.onNodeWithTag("search-scope-categories-list").assertDoesNotExist()
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
        open = true
        showCategories = true
        chosenPaperId = "long"
        compose.setContent {
            Box(Modifier.width(320.dp).height(560.dp)) {
                SearchScopeSheetContent(
                    papers = listOf(longPaper),
                    selectedCategoryIds = emptySet(),
                    showCategories = true,
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

        compose.onNodeWithTag("search-scope-back").assertIsDisplayed()
        compose.onNodeWithTag("search-scope-title-categories")
            .assertContentDescriptionEquals(longTitle)
        val last = compose.onNodeWithTag("search-scope-category-long-c12")
            .performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val bounds = last.getBoundsInRoot()
        assertTrue(
            "row target must be at least 48 dp",
            (bounds.bottom - bounds.top).value >= 48f
        )
    }

    @Test
    fun triggerCountsTickedRowsNotExpandedSubtreeIds() {
        openTrigger()
        compose.onNodeWithText(app.getString(R.string.search_filter_all_papers)).assertIsDisplayed()

        compose.runOnIdle {
            triggerState = SearchUiState(paperFilterIds = setOf("p1"))
        }
        compose.onNodeWithText("Physics").assertIsDisplayed()

        // Parent + child are both in state (the flat search contract), but the
        // learner ticked ONE row, so the summary must read "1 category".
        compose.runOnIdle {
            triggerState = SearchUiState(
                paperFilterIds = setOf("p1"),
                categoryFilterIds = setOf("p1-c2", "p1-c2a")
            )
        }
        compose.onNodeWithText(
            app.resources.getQuantityString(
                R.plurals.search_scope_summary_categories, 1, "Physics", 1
            )
        ).assertIsDisplayed()

        // Two independent ticked roots count as two.
        compose.runOnIdle {
            triggerState = SearchUiState(
                paperFilterIds = setOf("p1"),
                categoryFilterIds = setOf("p1-c1", "p1-c2", "p1-c2a")
            )
        }
        compose.onNodeWithText(
            app.resources.getQuantityString(
                R.plurals.search_scope_summary_categories, 2, "Physics", 2
            )
        ).assertIsDisplayed()
    }

    @Test
    fun allPapersAfterAScopedSearchResetsTheTrigger() {
        compose.runOnIdle {
            triggerState = SearchUiState(
                paperFilterIds = setOf("p1"),
                categoryFilterIds = setOf("p1-c1")
            )
        }
        openTrigger()
        compose.onNodeWithTag("search-scope-button").performClick()
        compose.waitForIdle()
        // A scoped search reopens at the categories step.
        compose.onNodeWithTag("search-scope-title-categories").assertIsDisplayed()
        compose.onNodeWithTag("search-scope-back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("search-scope-all-papers").performClick()
        compose.waitForIdle()

        assertEquals(1, scopeClears)
        assertEquals(SearchUiState(), triggerState)
        compose.onNodeWithText(app.getString(R.string.search_filter_all_papers)).assertIsDisplayed()
        compose.onNodeWithTag("search-scope-title-papers").assertDoesNotExist()
    }

    @Test
    fun dismissAndReopenKeepsTheAppliedSelection() {
        compose.runOnIdle {
            triggerState = SearchUiState(
                paperFilterIds = setOf("p1"),
                categoryFilterIds = setOf("p1-c1")
            )
            selectedCategoryIds = setOf("p1-c1")
        }
        openTrigger()
        compose.onNodeWithTag("search-scope-button").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("search-scope-category-p1-c1").assertIsOn()
        // A scrim dismiss changes nothing.
        compose.runOnIdle { open = false }
        compose.waitForIdle()
        compose.onNodeWithTag("search-scope-categories-list").assertDoesNotExist()

        compose.onNodeWithTag("search-scope-button").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("search-scope-title-categories")
            .assertContentDescriptionEquals("Physics")
        compose.onNodeWithTag("search-scope-category-p1-c1").assertIsOn()
        assertTrue(pickedPapers.isEmpty())
        assertTrue(toggledCategories.isEmpty())
        assertEquals(setOf("p1-c1"), triggerState.categoryFilterIds)
    }
}

/**
 * System Back goes through the modal dialog's own OnBackPressedDispatcher, not
 * the Activity's. This is the regression the old parent BackHandler missed: it
 * dismissed the whole sheet instead of stepping back. Hosts the real modal and
 * sends a real KEYCODE_BACK to the focused dialog window.
 */
@RunWith(AndroidJUnit4::class)
class SearchScopeSheetSystemBackUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun systemBackOnStepTwoStepsBackAndKeepsTheSheetOpen() {
        var showCategories by mutableStateOf(true)
        var showSheet by mutableStateOf(true)
        var dismisses = 0
        val paper = Paper(
            id = "p1",
            title = "Physics",
            categories = listOf(
                CategoryNode(id = "p1-c1", paperId = "p1", title = "Mechanics", parentId = null)
            )
        )
        compose.setContent {
            // Removal must be driven by state: counting a dismissal without
            // removing the host leaves the sheet in the semantics tree.
            if (showSheet) {
                SearchScopeSheet(
                    papers = listOf(paper),
                    selectedCategoryIds = emptySet(),
                    showCategories = showCategories,
                    chosenPaperId = "p1",
                    onChoosePaper = {},
                    onToggleCategory = {},
                    onClearCategoriesInPaper = {},
                    onClearScope = {},
                    onBackToPapers = { showCategories = false },
                    onDismiss = {
                        dismisses++
                        showSheet = false
                    }
                )
            }
        }
        // Wait until the dialog window has actually been composed and focused;
        // pressing Back before then can land on the Activity instead.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("search-scope-sheet")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("search-scope-category-p1-c1").assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        fun pressSystemBack() {
            // A real key event goes to the focused window — the modal dialog —
            // so it exercises the dialog's own OnBackPressedDispatcher.
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            instrumentation.waitForIdleSync()
            compose.waitForIdle()
        }

        pressSystemBack()
        compose.onNodeWithTag("search-scope-title-papers").assertIsDisplayed()
        compose.onNodeWithTag("search-scope-sheet").assertIsDisplayed()
        assertEquals("Back on step 2 must not dismiss", 0, dismisses)

        // The regression the frozen dialog property caused: after stepping back,
        // M3's own dismiss callback must still work on step 1.
        pressSystemBack()
        compose.onNodeWithTag("search-scope-sheet").assertDoesNotExist()
        assertEquals("Back on step 1 must dismiss", 1, dismisses)
    }
}
