package com.mcqapp.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
    messageRes: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The row is composed only *after* the load fails, so a liveRegion on its
    // Text has no prior value to diff against and announces nothing. Announce
    // the message explicitly on first composition through the app-wide host;
    // keep the liveRegion too, for a later error replacing this one in place.
    val message = stringResource(messageRes)
    val announcer = LocalAnnouncer.current
    LaunchedEffect(message) { announcer.announce(message) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            // First appearance is announced above; this covers a later error
            // replacing the message in an already-composed row.
            modifier = Modifier
                .weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

/** The refresh-error item for a paged list's LazyColumn. */
@Composable
fun PagingRefreshErrorItem(items: LazyPagingItems<*>, modifier: Modifier = Modifier) {
    if (items.loadState.refresh is LoadState.Error) {
        PagingErrorRow(
            messageRes = R.string.paging_refresh_failed,
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
            messageRes = R.string.paging_append_failed,
            onRetry = { items.retry() },
            modifier = modifier
        )
    }
}
