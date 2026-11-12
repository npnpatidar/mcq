package com.mcqapp.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.stats.StatsAggregator

/**
 * The statistics page's range chips, shared with the Sessions history so both
 * screens offer the same five windows and can never drift apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsRangeChips(
    selected: StatsAggregator.Range,
    onSelect: (StatsAggregator.Range) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
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
