package com.mcqapp.ui.stats

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mcqapp.R
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.stats.StatsAggregator
import com.mcqapp.ui.stats.sections.CardsSection
import com.mcqapp.ui.stats.sections.MemorySection
import com.mcqapp.ui.stats.sections.OverviewSection
import com.mcqapp.ui.stats.sections.RetentionSection
import com.mcqapp.ui.stats.sections.WorkloadSection

/**
 * The statistics page: scope and range selectors on top, five tabs of graphs
 * below, and a per-card drill-down sheet reachable from every ranked list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    repository: McqRepository,
    navController: NavController,
    viewModel: StatsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cardDetail by viewModel.cardDetail.collectAsStateWithLifecycle()

    // Reviews logged elsewhere (a study session) must show up when coming back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.papers.size > 1) {
                ScopeSelector(
                    papers = state.papers,
                    selected = state.scopePaperId,
                    onSelect = viewModel::setScope
                )
            }
            PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                listOf(
                    R.string.tab_overview,
                    R.string.tab_retention,
                    R.string.tab_workload,
                    R.string.tab_memory,
                    R.string.tab_cards
                ).forEachIndexed { index, titleRes ->
                    val section = StatsViewModel.Tab.entries[index]
                    Tab(
                        selected = state.tab == section,
                        onClick = { viewModel.selectTab(section) },
                        text = { Text(stringResource(titleRes)) }
                    )
                }
            }
            RangeSelector(state.range, viewModel::setRange)
            when {
                state.loadError != null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.stats_load_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                !state.visibleSliceLoaded -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                else -> {
                    val openCard: (String, String) -> Unit = { paperId, questionId ->
                        viewModel.openCard(paperId, questionId)
                    }
                    val questionText = state.questionText
                    key(state.tab) {
                        when (state.tab) {
                            StatsViewModel.Tab.OVERVIEW -> state.overview?.let { data ->
                                OverviewSection(
                                    bundle = data,
                                    questionText = questionText,
                                    onOpenCard = { openCard(it.paperId, it.questionId) },
                                    onOpenSettings = { navController.navigate("settings") }
                                )
                            }
                            StatsViewModel.Tab.RETENTION -> state.retention?.let { RetentionSection(it) }
                            StatsViewModel.Tab.WORKLOAD -> state.workload?.let { data ->
                                WorkloadSection(
                                    bundle = data,
                                    questionText = questionText,
                                    onOpenCard = { openCard(it.paperId, it.questionId) }
                                )
                            }
                            StatsViewModel.Tab.MEMORY -> state.memory?.let { MemorySection(it) }
                            StatsViewModel.Tab.CARDS -> state.cards?.let { data ->
                                CardsSection(
                                    bundle = data,
                                    questionText = questionText,
                                    onOpenCard = { openCard(it.paperId, it.questionId) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    cardDetail?.let { detail ->
        CardInfoSheet(detail = detail, onDismiss = viewModel::dismissCard)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeSelector(
    papers: List<com.mcqapp.domain.Paper>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.stats_all_papers)) }
        )
        papers.forEach { paper ->
            FilterChip(
                selected = selected == paper.id,
                onClick = { onSelect(paper.id) },
                label = { Text(paper.title, maxLines = 1) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeSelector(
    selected: StatsAggregator.Range,
    onSelect: (StatsAggregator.Range) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            StatsAggregator.Range.D7 to R.string.range_7d,
            StatsAggregator.Range.D30 to R.string.range_30d,
            StatsAggregator.Range.D90 to R.string.range_90d,
            StatsAggregator.Range.D365 to R.string.range_365d,
            StatsAggregator.Range.ALL to R.string.range_all
        ).forEach { (range, labelRes) ->
            FilterChip(
                selected = selected == range,
                onClick = { onSelect(range) },
                label = { Text(stringResource(labelRes)) }
            )
        }
    }
}
