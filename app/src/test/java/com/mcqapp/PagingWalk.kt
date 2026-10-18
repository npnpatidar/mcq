package com.mcqapp

import androidx.paging.PagingSource

/**
 * Walks a [PagingSource] to exhaustion at page size [pageSize].
 *
 * Room's invalidation tracker dispatches asynchronously, so a write made just
 * before the source was created can still be in flight and surface as a
 * single `Invalid` load. That is a scheduling race, not a data difference, so
 * the walk restarts from a fresh source (via [factory]) instead of failing on
 * the cast. The writes in these tests are all done beforehand, so a restart
 * always reaches the same stable set.
 *
 * [paramsFor] builds the load params from the previous page's key (`null` for
 * the first page), letting both the Append-style and Refresh-key-style
 * sources use this walk.
 */
suspend fun <K : Any, V : Any> walkPaging(
    pageSize: Int,
    factory: suspend () -> PagingSource<K, V>,
    paramsFor: (K?) -> PagingSource.LoadParams<K>
): List<V> {
    repeat(50) {
        val source = factory()
        val out = mutableListOf<V>()
        var key: K? = null
        while (true) {
            val result = source.load(paramsFor(key))
            if (result !is PagingSource.LoadResult.Page) break
            out += result.data
            key = result.nextKey ?: return out
        }
    }
    error("paging source kept invalidating")
}
