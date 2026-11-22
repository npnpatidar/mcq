package com.mcqapp.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.DailyGoal

/**
 * The whole daily-goal UI: one compact, labelled progress bar. It is a gauge
 * only — no button here is disabled when [DailyGoal.Progress.exceeded] is true.
 * Both Library and Statistics → Overview render this same composable.
 */
@Composable
fun DailyGoalIndicator(
    progress: DailyGoal.Progress,
    modifier: Modifier = Modifier
) {
    if (!progress.enabled) return
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("daily-goal-progress")
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(
                    R.string.daily_goal_progress,
                    progress.reviews,
                    progress.target
                ),
                style = MaterialTheme.typography.titleSmall
            )
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag("daily-goal-progress-bar")
            )
            Text(
                text = stringResource(R.string.daily_goal_progress_help),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
