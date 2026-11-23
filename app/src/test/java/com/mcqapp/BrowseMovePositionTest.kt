package com.mcqapp

import com.mcqapp.data.local.QuestionRef
import com.mcqapp.data.repository.clampMoveTarget
import com.mcqapp.data.repository.reachableMovePositions
import com.mcqapp.data.repository.reachableMoveRange
import com.mcqapp.data.repository.resolveMoveToPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "Move to position" used to clamp to the raw list length and then report
 * success even when a passage group made the requested slot unreachable. These
 * pin the reachable edges derived from passage groups, the snap to the nearest
 * one, and the honest final position reported back to the caller.
 */
class BrowseMovePositionTest {

    private fun ref(id: String, passage: String? = null) =
        QuestionRef(id = id, categoryId = "c1", passageId = passage)

    /** s1, a three-member passage P, s2. */
    private val refs = listOf(ref("s1"), ref("g1", "P"), ref("g2", "P"), ref("g3", "P"), ref("s2"))

    @Test
    fun aPassageMemberCanReachAnySlotInsideItsOwnGroup() {
        assertEquals(listOf(1, 2, 3), reachableMovePositions(refs, "g2"))
        assertEquals(2..4, reachableMoveRange(refs, "g2"))

        val result = resolveMoveToPosition(refs, "g2", targetIndex = 3)!!
        assertEquals(3, result.actualIndex)
        assertEquals(listOf("s1", "g1", "g3", "g2", "s2"), result.order)
    }

    @Test
    fun aPassageMemberAskedForAnOuterPositionClampsToItsGroupEdge() {
        // The group owns slots 1..3; neither 0 nor 4 is reachable by a member.
        val down = resolveMoveToPosition(refs, "g2", targetIndex = 4)!!
        assertEquals(3, down.actualIndex)
        assertEquals(listOf("s1", "g1", "g3", "g2", "s2"), down.order)

        val up = resolveMoveToPosition(refs, "g2", targetIndex = 0)!!
        assertEquals(1, up.actualIndex)
        assertEquals(listOf("s1", "g2", "g1", "g3", "s2"), up.order)
    }

    @Test
    fun aStandaloneCanOnlyLandAtPassageGroupEdges() {
        // s1 sits above the group: below it, only the group's far edge (3) and
        // the list end (4) are reachable — never the group's interior (1, 2).
        assertEquals(listOf(0, 3, 4), reachableMovePositions(refs, "s1"))
        assertEquals(1..5, reachableMoveRange(refs, "s1"))
    }

    @Test
    fun aStandaloneTargetInsideAGroupSnapsToTheNearestEdge() {
        // s1 asked for slot 2 (inside P) really lands at 3, just after P.
        val fromAbove = resolveMoveToPosition(refs, "s1", targetIndex = 2)!!
        assertEquals(3, fromAbove.actualIndex)
        assertEquals(listOf("g1", "g2", "g3", "s1", "s2"), fromAbove.order)

        // s2 approaches from below: the nearest edge is 1, just before P.
        assertEquals(listOf(0, 1, 4), reachableMovePositions(refs, "s2"))
        val fromBelow = resolveMoveToPosition(refs, "s2", targetIndex = 2)!!
        assertEquals(1, fromBelow.actualIndex)
        assertEquals(listOf("s1", "s2", "g1", "g2", "g3"), fromBelow.order)
    }

    @Test
    fun theGroupStaysContiguousForEveryResolvedTarget() {
        for (id in listOf("s1", "g1", "g2", "g3", "s2")) {
            for (target in -2..7) {
                val order = resolveMoveToPosition(refs, id, target)!!.order
                val groupPositions = order.mapIndexed { index, q -> if (q.startsWith("g")) index else -1 }
                    .filter { it >= 0 }
                assertEquals(
                    "group split for $id -> $target: $order",
                    3,
                    groupPositions.last() - groupPositions.first() + 1
                )
            }
        }
    }

    @Test
    fun clampingUnknownIdsAndOutOfRangeTargetsIsSafe() {
        assertNull(clampMoveTarget(refs, "missing", 2))
        assertNull(resolveMoveToPosition(refs, "missing", 2))
        assertEquals(4, clampMoveTarget(refs, "s1", 99))
        assertEquals(0, clampMoveTarget(refs, "s1", -99))
    }
}
