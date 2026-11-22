package com.mcqapp.ui.study

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.data.repository.SuspendedCard

/**
 * The unsuspend route. Browse and the card editor are owned by another work
 * stream, so this small dialog lives in Study and is opened from its card
 * menu; a suspended card can never reach the pull, so without a route like
 * this one it would be unrecoverable from the UI.
 *
 * A buried card is listed too — with its own label — so hiding a card for the
 * day never strands it with no way back before the boundary.
 */
@Composable
internal fun SuspendedCardsDialog(
    cards: List<SuspendedCard>,
    onUnsuspend: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.suspend_dialog_title)) },
        text = {
            if (cards.isEmpty()) {
                Text(stringResource(R.string.suspend_dialog_empty))
            } else {
                LazyColumn(modifier = Modifier.testTag("study-suspended-list")) {
                    items(cards, key = { it.questionId }) { card ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                card.text.ifBlank { stringResource(R.string.suspend_indicator) },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("study-suspended-row-" + card.questionId)
                            )
                            Text(
                                stringResource(
                                    if (card.suspended) R.string.suspend_indicator
                                    else R.string.suspend_indicator_buried
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            TextButton(onClick = { onUnsuspend(card.questionId) }) {
                                Text(stringResource(R.string.suspend_dialog_unsuspend))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}
