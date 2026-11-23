package com.mcqapp

import com.mcqapp.data.repository.DuplicateGroup
import com.mcqapp.ui.browse.duplicateGroupKeeperKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * `groupDuplicates` confirms candidates element-by-element after the stored
 * hash has narrowed them, so two unrelated groups can carry the same stale
 * hash. A keeper choice in one must never leak into the other.
 */
class DuplicateKeeperKeyTest {

    private fun keeperFor(
        keepers: Map<String, String>,
        group: DuplicateGroup
    ): String = keepers[duplicateGroupKeeperKey(group.questionIds)] ?: group.questionIds.first()

    @Test
    fun twoGroupsSharingAStaleHashKeepIndependentKeepers() {
        val first = DuplicateGroup(contentHash = "stale", questionIds = listOf("q1", "q2"))
        val second = DuplicateGroup(contentHash = "stale", questionIds = listOf("q3", "q4"))

        assertNotEquals(
            "two groups must have distinct keeper keys even with the same hash",
            duplicateGroupKeeperKey(first.questionIds),
            duplicateGroupKeeperKey(second.questionIds)
        )

        var keepers = emptyMap<String, String>()
        keepers = keepers + (duplicateGroupKeeperKey(first.questionIds) to "q2")

        assertEquals("q2", keeperFor(keepers, first))
        assertEquals(
            "picking a keeper in one group must not change another group's keeper",
            "q3",
            keeperFor(keepers, second)
        )
    }

    @Test
    fun keeperKeyIgnoresTheOrderTheGroupIdsArriveIn() {
        val group = DuplicateGroup(contentHash = "h", questionIds = listOf("b", "a"))
        val reordered = DuplicateGroup(contentHash = "h", questionIds = listOf("a", "b"))

        assertEquals(duplicateGroupKeeperKey(group.questionIds), duplicateGroupKeeperKey(reordered.questionIds))
    }
}
