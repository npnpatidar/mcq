package com.mcqapp.ui.browse

import com.mcqapp.data.local.QuestionRef

/**
 * How many rows beyond the viewport Browse keeps loaded. The margin is why a
 * row scrolls in already complete: without it every row fetched its own card,
 * passage and reorder context from the row composable, i.e. after it had been
 * laid out, so the card grew under the finger and a passage header appeared
 * late and pushed the rows below it down.
 */
const val BROWSE_PREFETCH_MARGIN = 12

/**
 * The rows Browse should hold loaded for the viewport [firstVisible]..
 * [lastVisible], widened by [margin] on both sides and clamped to the list.
 *
 * Indices are LazyColumn item indices, where 0 is the controls header and the
 * cards start at 1; [peek] therefore receives a *paged* index
 * (`lazyIndex - 1`) and returns null for a row whose page has not landed. A
 * null row is skipped rather than stopping the walk: the page boundaries are
 * not where the viewport is, and skipping a placeholder costs one reload of
 * it later, while stopping early would leave the rest of the window unloaded.
 */
fun browseWindow(
    peek: (Int) -> QuestionRef?,
    itemCount: Int,
    firstVisible: Int,
    lastVisible: Int,
    margin: Int = BROWSE_PREFETCH_MARGIN
): List<QuestionRef> {
    if (firstVisible < 0 || lastVisible < firstVisible) return emptyList()
    val from = (firstVisible - margin).coerceAtLeast(1)
    // The controls header is item 0, so card lazily-indexed rows run 1..itemCount.
    val to = (lastVisible + margin).coerceAtMost(itemCount)
    if (to < from) return emptyList()
    val window = ArrayList<QuestionRef>(to - from + 1)
    for (lazyIndex in from..to) {
        peek(lazyIndex - 1)?.let { window.add(it) }
    }
    return window
}
