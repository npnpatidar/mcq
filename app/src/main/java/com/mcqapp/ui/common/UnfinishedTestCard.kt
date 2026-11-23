package com.mcqapp.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.TestType
import com.mcqapp.domain.UnfinishedTest

/**
 * The visible home for a saved test. Both Library and History show the same
 * card so an unfinished run can be resumed from either place; the route it
 * opens is the exact session identity, and the test screen's own Resume dialog
 * restores the answers.
 */
@Composable
fun UnfinishedTestCard(
    saved: UnfinishedTest,
    onResume: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("unfinished-test-card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.test_ux_unfinished_line, saved.answeredCount),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
                // The stored token is a raw enum name; the setup sheet's own
                // localised label is the one place that names a test type. A
                // legacy/unknown token still reads as no subtitle, not as a
                // made-up "Random".
                Text(
                    saved.snapshot.testType
                        ?.let { TestType.fromStorage(it) }
                        ?.let { testTypeLabelText(it) }
                        .orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onResume,
                modifier = Modifier.testTag("unfinished-test-resume")
            ) {
                Text(stringResource(R.string.test_ux_unfinished_resume))
            }
        }
    }
}
