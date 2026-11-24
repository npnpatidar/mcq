package com.mcqapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level pins for the passages scope sheet. The behaviour lives in pure
 * helpers (PassagesScopeFilterTest) and on the CI emulator (PassagesScopeSheetUiTest);
 * these guards catch the wiring regressions that run everywhere — a missing
 * tag prefix, a summary without subtree expansion, a stale expanded passage.
 */
class PassagesScopeSheetStructureTest {

    private val screen = File("src/main/java/com/mcqapp/ui/editor/PassagesScreen.kt").readText()
    private val shared = File("src/main/java/com/mcqapp/ui/common/ScopeSheet.kt").readText()
    private val sheet = screen + shared

    private fun occurrences(haystack: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(haystack).count()

    @Test
    fun oneTriggerWithThePassagesTagAboveTheList() {
        // One call site plus the thin wrapper declaration in the same file.
        assertEquals(2, occurrences(screen, "PassagesScopeTrigger("))
        assertEquals(1, occurrences(screen, "\"passages-scope-button\""))
        assertTrue("the shared trigger is reused", "ScopeTrigger(" in screen)
        assertTrue("the trigger sits above the list", screen.indexOf("PassagesScopeTrigger(") <
            screen.indexOf("visiblePassages.forEach"))
        assertTrue("the trigger is outside the LazyColumn", screen.indexOf("PassagesScopeTrigger(") <
            screen.indexOf("LazyColumn("))
    }

    @Test
    fun sheetUsesTheSharedTwoStepContractWithItsOwnPrefix() {
        assertEquals(2, occurrences(screen, "tagPrefix = \"passages-scope\""))
        assertTrue("modal host delegates to the shared sheet", "ScopeModalSheet(" in screen)
        assertTrue("stateless body delegates to the shared sheet", "ScopeSheetContent(" in screen)
        assertTrue("rotation-safe step", "rememberSaveable { mutableStateOf(ScopeStep.PAPERS) }" in screen)
        assertTrue("rotation-safe chosen paper", "rememberSaveable { mutableStateOf<String?>(null) }" in screen)
        assertTrue("the opening event seeds the step",
            "scopeStep = if (applied != null) ScopeStep.CATEGORIES else ScopeStep.PAPERS" in screen)
        assertTrue("no open-keyed effect to re-seed the step", "LaunchedEffect(showScopeSheet)" !in screen)

        // Tags are all derived from the prefix in the shared primitive.
        listOf(
            "-sheet", "-back", "-title-papers", "-title-categories", "-papers-list",
            "-all-papers", "-paper-", "-categories-list", "-all-categories", "-category-"
        ).forEach { suffix -> assertTrue("missing tag suffix $suffix", "\"\${tagPrefix}$suffix" in shared) }
        assertTrue("skipPartiallyExpanded keeps the last row reachable",
            "rememberModalBottomSheetState(skipPartiallyExpanded = true)" in shared)
        assertTrue("immediate apply — no Done button", "passages-scope-apply" !in screen)
    }

    @Test
    fun scopeStateIsInTheViewModelAndPrunedOnPapersLoad() {
        listOf("paperFilterIds: Set<String>", "categoryFilterIds: Set<String>").forEach { field ->
            assertTrue("missing state field $field", field in screen)
        }
        assertTrue("stale ids pruned as papers load", "prunePassagesScope(it, papers)" in screen)
        assertTrue("choose paper is a pure helper", "internal fun choosePassagesPaper(" in screen)
        assertTrue("toggle expands the subtree", "CategorySelection.subtreeIds(" in screen)
        assertTrue("filter expands ticked roots", "CategorySelection.orderedIds(" in screen)
        assertTrue("unknown ids are ignored", "if (subtree.isEmpty()) return state" in screen)
    }

    @Test
    fun changingScopeClosesAnyExpandedMemberList() {
        val update = screen.substringAfter("private fun updateScope(")
            .substringBefore("fun choosePaper(")
        assertTrue("both states are cleared", "expandedId.value = null" in update)
        assertTrue("state copy clears the row", "next.copy(expandedId = null)" in update)
        assertTrue("a no-op transition does not collapse", "next === current" in update)
        assertTrue("the sheet calls the ViewModel", "viewModel.choosePaper(id)" in screen)
        assertTrue("category toggles go through the ViewModel", "viewModel::toggleCategory" in screen)
    }

    @Test
    fun filteringUsesThePassagesOwnCategoryAndHasItsOwnEmptyState() {
        assertTrue("filter reads passage.categoryId", "paperIndex[it.categoryId]?.id" in screen)
        assertTrue("scope checks the passage category", "it.categoryId in inScope" in screen)
        assertTrue("no member-derived scope", "member.categoryId" !in
            screen.substringBefore("visiblePassages.forEach"))
        assertTrue("distinct scoped empty state", "R.string.passages_scope_empty" in screen)
        assertTrue("scoped empty state is test-tagged", "\"passages-scope-empty\"" in screen)
        assertTrue("all passages still show the original empty state", "R.string.passages_empty" in screen)
    }

    @Test
    fun narrowRowsKeepTheFourActionsAndSpeakTheFullNames() {
        val listBody = screen.substringAfter("visiblePassages.forEach")
            .substringBefore("// The member questions")
        assertEquals("four action icons unchanged", 4, occurrences(listBody, "IconButton(onClick"))
        assertEquals(1, occurrences(listBody, "contentDescription = title"))
        assertEquals(1, occurrences(listBody, "contentDescription = paperName"))
        assertEquals(2, occurrences(listBody, "overflow = TextOverflow.Ellipsis"))
        assertEquals(2, occurrences(listBody, "maxLines = 1"))
        assertTrue("the member row tag is unchanged", "passage-member-\${member.id}" in screen)
        assertTrue("the unassign tag is unchanged", "passage-unassign-\${member.id}" in screen)
    }

    @Test
    fun scopedEmptyStateOffersOneTapAllPapersRecovery() {
        val emptyBranch = screen.substringAfter("passages_scope_empty")
            .substringBefore("LazyColumn(")
        assertTrue("the recovery button belongs to the scoped-empty branch",
            "passages-scope-clear" in emptyBranch)
        assertTrue("recovery clears the whole scope", "onClick = viewModel::clearScopeFilters" in emptyBranch)
        assertTrue("recovery reuses the shared All papers copy",
            "R.string.search_filter_all_papers" in emptyBranch)
    }

    @Test
    fun scopedEmptyStringExistsInBothLocales() {
        val en = File("src/main/res/values/strings_passages_scope.xml").readText()
        val hi = File("src/main/res/values-hi/strings_passages_scope_hi.xml").readText()
        listOf(en, hi).forEach { xml ->
            assertTrue("missing scoped empty string", "<string name=\"passages_scope_empty\">" in xml)
        }
        assertTrue("sheet copy is reused, not duplicated", "search_scope_choose_paper" !in screen)
    }
}
