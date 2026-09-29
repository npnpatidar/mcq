package com.mcqapp.domain

/**
 * Pure helpers for bulk question operations. Id generation must be
 * collision-proof against re-imports and repeated duplication, so copies
 * chain suffixes (`q1-copy`, `q1-copy-2`, …) until unused.
 */
object BulkOps {

    fun copyId(existingIds: Set<String>, baseId: String): String {
        var candidate = "${baseId}-copy"
        var n = 2
        while (candidate in existingIds) {
            candidate = "${baseId}-copy-$n"
            n++
        }
        return candidate
    }
}
