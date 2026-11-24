package com.mcqapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level pins for the search scope sheet. The UI tests cover behaviour on
 * the CI emulator; these run everywhere and catch the structural regressions
 * that made the old chip rows unusable (two competing scope families, no
 * ellipsis, a Back that dismissed instead of stepping).
 */
class SearchScopeSheetStructureTest {

    private val screen = File("src/main/java/com/mcqapp/ui/search/SearchScreen.kt").readText()
    // The sheet lives in shared primitives now; the search file is a thin
    // wrapper. Guards read both so a regression on either side is caught.
    private val commonSheet = File("src/main/java/com/mcqapp/ui/common/ScopeSheet.kt").readText()
    private val searchSheet = File("src/main/java/com/mcqapp/ui/search/SearchScopeSheet.kt").readText()
    private val sheet = commonSheet + searchSheet

    private fun occurrences(haystack: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(haystack).count()

    @Test
    fun oneTriggerReplacesBothOldChipRows() {
        assertEquals(
            "there must be exactly one scope entry point",
            1,
            occurrences(screen, "SearchScopeTrigger(")
        )
        assertEquals(1, occurrences(screen + sheet, "\"search-scope-button\""))
        assertTrue("the wrapping paper chip row is gone", "FlowRow" !in screen)
        assertTrue("the old paper toggle is gone", "togglePaper" !in screen)
        assertTrue("the old cross-paper chip list is gone", "viewModel.chips" !in screen)
        assertTrue("the All/Questions/Options chips remain", "state.scope == scope" in screen)
    }

    @Test
    fun sheetHasEveryContractTagAndTheTriStateSource() {
        listOf(
            "search-scope-sheet", "search-scope-back", "search-scope-title-papers",
            "search-scope-title-categories", "search-scope-papers-list",
            "search-scope-all-papers", "search-scope-paper-", "search-scope-categories-list",
            "search-scope-all-categories", "search-scope-category-"
        ).forEach { tag -> assertTrue("missing tag $tag", "\"$tag" in sheet) }
        assertTrue("explicit Done would contradict immediate apply", "search-scope-apply" !in sheet)
        assertTrue("rememberModalBottomSheetState(skipPartiallyExpanded = true)" in sheet)
        assertTrue("ModalBottomSheet(" in sheet)
        assertTrue("CategorySelection.states(" in sheet)
        assertTrue("CategorySelection.flatten(" in sheet)
        assertTrue("TriStateCheckbox(" in sheet)
    }

    @Test
    fun backHandlerLivesInsideTheModalDialogDispatcher() {
        val sheetIndex = sheet.indexOf("ModalBottomSheet(")
        val backIndex = sheet.indexOf("BackHandler(enabled = showCategories)")
        assertTrue("the modal host exists", sheetIndex >= 0)
        assertTrue("Back must register on the dialog dispatcher", backIndex > sheetIndex)
        assertTrue(
            "the Activity dispatcher cannot see a modal dialog's back press",
            "BackHandler" !in screen
        )
        assertTrue(
            "a frozen dialog property would break Back after stepping back",
            "ModalBottomSheetProperties" !in sheet &&
                "shouldDismissOnBackPress" !in sheet
        )
        assertTrue(
            "the step-2 handler must be enabled by the current step",
            "BackHandler(enabled = showCategories)" in sheet
        )
    }

    @Test
    fun openingEventSeedsStepAndRotationDoesNotReseedIt() {
        assertTrue("no open-keyed effect to re-seed the step", "LaunchedEffect(showScopeSheet)" !in screen)
        assertTrue("the opening event seeds the paper", "chosenPaperId = appliedPaper" in screen)
        assertTrue(
            "the opening event seeds the step",
            "scopeStep = if (appliedPaper != null)" in screen
        )
        assertTrue("reopen starts from the applied paper", "paperFilterIds.singleOrNull()" in screen)
    }

    @Test
    fun rowsExposeCheckboxSemanticsAndPathQualifiedLabels() {
        assertTrue("checkbox role", "role = Role.Checkbox" in sheet)
        assertTrue("localised check state", "stateDescription = checkLabel" in sheet)
        assertTrue("tri-state value", "toggleableState = when (checkState)" in sheet)
        assertTrue("partial state string", "R.string.search_scope_category_partial" in sheet)
        assertTrue("ancestor path helper", "internal fun categoryPath(" in sheet)
        assertTrue("path is the visible label", "text = path" in sheet)
        assertTrue("path is also spoken", "R.string.search_scope_category_with_paper, path, paper.title" in sheet)
        assertTrue("summary counts ticked rows", "CategorySelection.tickedIds(" in sheet)
    }

    @Test
    fun narrowLayoutKeepsTargetsAndEllipsisedTitles() {
        assertTrue("sheet padding", ".padding(horizontal = 24.dp)" in sheet)
        assertTrue("48 dp row targets", "heightIn(min = 48.dp)" in sheet)
        assertTrue("single-line titles", occurrences(sheet, "maxLines = 1") >= 4)
        assertTrue("ellipsis", occurrences(sheet, "TextOverflow.Ellipsis") >= 4)
        assertTrue("rows scroll", ".verticalScroll(rememberScrollState())" in sheet)
    }

    @Test
    fun newStringsAreDefinedInBothLocales() {
        val en = File("src/main/res/values/strings_search_scope.xml").readText()
        val hi = File("src/main/res/values-hi/strings_search_scope_hi.xml").readText()
        listOf(
            "search_scope_choose_paper", "search_scope_choose_categories",
            "search_scope_all_categories_in", "search_scope_category_with_paper",
            "search_scope_category_partial"
        ).forEach { name ->
            assertTrue("$name missing in English", "<string name=\"$name\">" in en)
            assertTrue("$name missing in Hindi", "<string name=\"$name\">" in hi)
        }
        listOf(en, hi).forEach { xml ->
            assertTrue("summary plural missing", "<plurals name=\"search_scope_summary_categories\">" in xml)
            assertTrue("plural one missing", "quantity=\"one\"" in xml)
            assertTrue("plural other missing", "quantity=\"other\"" in xml)
            assertTrue("format arguments missing", "%1\$s" in xml && "%2\$d" in xml)
        }
    }
}
