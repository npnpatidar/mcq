package com.mcqapp.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.mcqapp.domain.previewText
import com.mcqapp.domain.isFlashcard
import com.mcqapp.R
import com.mcqapp.ui.common.PagingAppendErrorItem
import com.mcqapp.ui.common.PagingErrorRow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    navController: NavController,
    viewModel: SearchViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val debouncing by viewModel.debouncing.collectAsStateWithLifecycle()
    // The query the visible rows were actually produced for. The snippet below
    // explains a row's match, so it must use this, not the live text field.
    val committedQuery by viewModel.committedQuery.collectAsStateWithLifecycle()
    val resultCount by viewModel.resultCount.collectAsStateWithLifecycle()
    val papers by viewModel.papers.collectAsStateWithLifecycle()
    val chips by viewModel.chips.collectAsStateWithLifecycle()
    val bookmarkedIds by viewModel.bookmarkedIds.collectAsStateWithLifecycle()
    val hits = viewModel.pagingData.collectAsLazyPagingItems()
    LaunchedEffect(hits.itemSnapshotList.items) {
        viewModel.observeBookmarks(hits.itemSnapshotList.items.map { it.question.id })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_all_papers)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::updateQuery,
                label = { Text(stringResource(R.string.search_text_or_tags)) },
                singleLine = true,
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.mcqapp.domain.QuestionSearch.Scope.entries.forEach { scope ->
                    // B5: the scope labels used to be English literals on the
                    // domain enum; resolve them here so they follow the locale.
                    val scopeLabel = stringResource(
                        when (scope) {
                            com.mcqapp.domain.QuestionSearch.Scope.ALL -> R.string.r2_browse_scope_all
                            com.mcqapp.domain.QuestionSearch.Scope.QUESTION -> R.string.r2_browse_scope_questions
                            com.mcqapp.domain.QuestionSearch.Scope.OPTIONS -> R.string.r2_browse_scope_options
                        }
                    )
                    androidx.compose.material3.FilterChip(
                        selected = state.scope == scope,
                        onClick = { viewModel.updateScope(scope) },
                        label = { Text(scopeLabel) }
                    )
                }
            }
            // Paper / category chips narrow *which cards* are searched (the
            // scope chips above narrow *where in a card* to look). Many phones
            // cannot fit a long category list on one line, so both rows wrap.
            if (papers.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = state.paperFilterIds.isEmpty(),
                        onClick = { viewModel.clearScopeFilters() },
                        label = { Text(stringResource(R.string.search_filter_all_papers)) }
                    )
                    papers.forEach { paper ->
                        FilterChip(
                            selected = paper.id in state.paperFilterIds,
                            onClick = { viewModel.togglePaper(paper.id) },
                            label = { Text(paper.title) }
                        )
                    }
                }
            }
            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = state.categoryFilterIds.isEmpty(),
                        onClick = { viewModel.clearScopeFilters() },
                        label = { Text(stringResource(R.string.search_filter_all_categories)) }
                    )
                    chips.forEach { chip ->
                        FilterChip(
                            selected = chip.id in state.categoryFilterIds,
                            onClick = { viewModel.toggleCategory(chip.id) },
                            label = { Text(chip.title) }
                        )
                    }
                }
            }
            val refresh = hits.loadState.refresh
            val append = hits.loadState.append
            val appending = append is LoadState.Loading
            Spacer(Modifier.height(8.dp))
            // B6: announce status changes politely, not just the result count.
            val announced = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            when {
                debouncing ->
                    Text(
                        stringResource(R.string.searching),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = announced
                    )
                // A blank query used to fall through to a silent empty list.
                searchShowsTypeHint(state.query, debouncing) ->
                    Text(
                        stringResource(R.string.followup_search_type_to_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = announced
                    )
                refresh is LoadState.Loading && hits.itemCount == 0 ->
                    Text(
                        stringResource(R.string.searching),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = announced
                    )
                refresh is LoadState.NotLoading &&
                    resultCount?.exact == true && resultCount!!.lowerBound == 0 &&
                    state.query.isNotBlank() ->
                    Text(
                        stringResource(R.string.no_matches),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = announced
                    )
                resultCount != null && resultCount!!.lowerBound > 0 ->
                    Text(
                        // A bounded lower bound shows "N+" while the full
                        // walk finishes, then settles on the exact total from
                        // the same two-phase pipeline the pages use.
                        if (resultCount!!.exact) {
                            pluralStringResource(
                                R.plurals.search_result_count,
                                resultCount!!.lowerBound, resultCount!!.lowerBound
                            )
                        } else {
                            pluralStringResource(
                                R.plurals.search_result_count_more,
                                resultCount!!.lowerBound, resultCount!!.lowerBound
                            )
                        },
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
            }
            Spacer(Modifier.height(8.dp))
            // A failed refresh used to fall through every branch and leave a
            // blank list with no way back (consolidated_bugs.md F6).
            if (refresh is LoadState.Error) {
                PagingErrorRow(
                    messageRes = R.string.paging_refresh_failed,
                    onRetry = { hits.retry() }
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    count = hits.itemCount,
                    key = hits.itemKey { it.question.id },
                    contentType = { "hit" }
                ) { index ->
                    val hit = hits[index] ?: return@items
                    // B6: a clickable Card is a button to TalkBack, with a
                    // spoken action; without this it is just a group. The
                    // label is resolved in composition and passed to the
                    // clickable overload (its semantics lambda is not
                    // @Composable, and this BOM has no onClickLabel setter).
                    val openLabel = stringResource(R.string.r2_browse_open_question)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClickLabel = openLabel, role = Role.Button) {
                                navController.navigate(com.mcqapp.ui.navigation.browseRoute(hit.paperId, hit.question.id))
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    // previewText, not text: the row shows the
                                    // visible text (what the search matched),
                                    // while `text` is the stored shape, markup
                                    // and all.
                                    hit.question.previewText.ifBlank {
                                        stringResource(R.string.r2_browse_blank_text)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                // A hit whose only match is in the back must say
                                // so, or the row looks like a false positive:
                                // the snippet is the back text around the match,
                                // never the whole back. The label is type-aware —
                                // a card's back is its Answer, an MCQ's is its
                                // Explanation — and it reads the committed query,
                                // not `state.query`: the rows are the debounced
                                // request's, and the field may already hold the
                                // next one.
                                com.mcqapp.domain.QuestionSearch
                                    .backSnippet(hit.question, committedQuery)
                                    ?.let { snippet ->
                                        Text(
                                            stringResource(
                                                backSnippetLabelRes(hit.question.isFlashcard),
                                                snippet
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (hit.question.isFlashcard) {
                                        Text(
                                            stringResource(R.string.r2_flashcard_label),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                    }
                                    Text(
                                        hit.paperTitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.toggleBookmark(hit.question.id) }) {
                                Icon(
                                    if (hit.question.id in bookmarkedIds) Icons.Default.Bookmark
                                    else Icons.Default.BookmarkBorder,
                                    contentDescription = stringResource(
                                        if (hit.question.id in bookmarkedIds)
                                            R.string.browse_remove_bookmark
                                        else R.string.browse_add_bookmark
                                    )
                                )
                            }
                        }
                    }
                }
                if (appending) {
                    item(key = "search-load-more", contentType = "load-more") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(20.dp).height(20.dp)
                            )
                        }
                    }
                }
                if (hits.loadState.append is LoadState.Error) {
                    item(key = "search-append-error", contentType = "append-error") {
                        PagingAppendErrorItem(hits)
                    }
                }
            }
        }
    }
}

/**
 * The label a back-only result row puts in front of its snippet, chosen by the
 * type of the question that matched: a flashcard's back is its **Answer**, a
 * multiple-choice question's back is its **Explanation**. The rest of the app
 * makes the same distinction (Study's revealed card, Browse's back heading), so
 * an MCQ explanation hit must not be announced as an answer.
 *
 * `internal` and type-only so the rule is unit-testable; the caller already
 * knows the card type ([com.mcqapp.domain.isFlashcard]) from the hit it holds.
 */
@androidx.annotation.StringRes
internal fun backSnippetLabelRes(isFlashcard: Boolean): Int =
    if (isFlashcard) R.string.r2_browse_answer_snippet
    else R.string.r2_browse_explanation_snippet

