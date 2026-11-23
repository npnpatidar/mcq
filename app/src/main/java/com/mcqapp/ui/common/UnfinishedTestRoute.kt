package com.mcqapp.ui.common

import com.mcqapp.domain.ExamTiming
import com.mcqapp.domain.UnfinishedTest
import com.mcqapp.ui.navigation.testRoute

/**
 * The route that reopens the *exact* saved session. The session's identity is
 * the tuple the test screen compares on resume — paper, scope, drill shape,
 * type, count, exclusion rule and variant — all of which the snapshot carries.
 * The order itself is rebuilt from the snapshot's seeds when the screen
 * offers Resume, so no question ids need to travel in the route.
 */
internal fun unfinishedTestRoute(saved: UnfinishedTest): String {
    val snapshot = saved.snapshot
    return testRoute(
        paperId = snapshot.paperId,
        categoryIds = snapshot.categoryIds,
        mistakes = snapshot.mistakesOnly,
        drillCount = if (snapshot.isDrill) snapshot.drillRequested else 0,
        minutes = ExamTiming.USE_PAPER_DURATION,
        type = snapshot.testType.orEmpty(),
        count = snapshot.requestedCount,
        exclusion = snapshot.exclusionMode,
        variant = snapshot.variant.orEmpty()
    )
}
