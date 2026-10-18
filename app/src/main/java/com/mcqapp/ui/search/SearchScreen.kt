package com.mcqapp.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.previewText
import com.mcqapp.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    repository: McqRepository,
    navController: NavController,
    viewModel: SearchViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val debouncing by viewModel.debouncing.collectAsStateWithLifecycle()
    val hits = viewModel.pagingData.collectAsLazyPagingItems()

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
                    androidx.compose.material3.FilterChip(
                        selected = state.scope == scope,
                        onClick = { viewModel.updateScope(scope) },
                        label = { Text(scope.label) }
                    )
                }
            }
            val refresh = hits.loadState.refresh
            val appending = hits.loadState.append is LoadState.Loading
            Spacer(Modifier.height(8.dp))
            when {
                debouncing || (refresh is LoadState.Loading && hits.itemCount == 0) ->
                    Text(stringResource(R.string.searching), style = MaterialTheme.typography.bodyMedium)
                refresh is LoadState.NotLoading && hits.itemCount == 0 && state.query.isNotBlank() ->
                    Text(stringResource(R.string.no_matches), style = MaterialTheme.typography.bodyMedium)
                hits.itemCount > 0 ->
                    Text(
                        "${hits.itemCount}${if (appending) "+" else ""} " +
                            "match${if (hits.itemCount == 1 && !appending) "" else "es"}",
                        style = MaterialTheme.typography.labelMedium
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
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
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
                                    hit.question.previewText.ifBlank { "(blank text)" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    hit.paperTitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.width(8.dp))
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
            }
        }
    }
}
