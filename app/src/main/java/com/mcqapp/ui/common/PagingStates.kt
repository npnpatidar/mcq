package com.mcqapp.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.mcqapp.R

/**
 * The paged lists' shared error states (consolidated_bugs.md F6): a failed
 * refresh used to render as a blank list with no message or retry on the
 * results, search and bookmark screens, and a failed append simply stopped
 * the loading spinner.
 */

/** One row: the message plus a Retry that calls [LazyPagingItems.retry]. */
@Composable
fun PagingErrorRow(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

/** The refresh-error item for a paged list's LazyColumn. */
@Composable
fun PagingRefreshErrorItem(items: LazyPagingItems<*>, modifier: Modifier = Modifier) {
    if (items.loadState.refresh is LoadState.Error) {
        PagingErrorRow(
            message = stringResource(R.string.paging_refresh_failed),
            onRetry = { items.retry() },
            modifier = modifier
        )
    }
}

/** The append-error item for a paged list's LazyColumn. */
@Composable
fun PagingAppendErrorItem(items: LazyPagingItems<*>, modifier: Modifier = Modifier) {
    if (items.loadState.append is LoadState.Error) {
        PagingErrorRow(
            message = stringResource(R.string.paging_append_failed),
            onRetry = { items.retry() },
            modifier = modifier
        )
    }
}
