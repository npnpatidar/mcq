package com.mcqapp

import com.mcqapp.domain.stats.StudySession
import com.mcqapp.ui.sessions.sessionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression for the Sessions list's lazy-list keys.
 *
 * The old key was `session-$day-$index-$startedAt`: inserting a sitting shifted
 * every later item's index, so Compose tore those rows down and recreated them —
 * losing the expanded-topics state and the scroll position. The key now comes
 * from the sitting's own start/end pair and never from its position.
 */
class SessionsKeyTest {

    private fun sitting(startedAt: Long, endedAt: Long, day: Long = 1L) = StudySession(
        startedAt = startedAt,
        endedAt = endedAt,
        day = day,
        reviews = 2,
        questions = 2,
        again = 0,
        hard = 0,
        good = 2,
        easy = 0,
        timedSeconds = 10L,
        timedReviews = 2,
        papers = emptyList(),
        categories = emptyList()
    )

    @Test
    fun insertingAnEarlierSittingDoesNotChangeExistingKeys() {
        val a = sitting(100L, 150L)
        val b = sitting(200L, 250L)
        val inserted = sitting(50L, 90L)

        val before = listOf(sessionKey(1L, a), sessionKey(1L, b))
        val after = listOf(sessionKey(1L, inserted), sessionKey(1L, a), sessionKey(1L, b))

        // With an index in the key, a moved 0 -> 1 and b moved 1 -> 2 here.
        assertEquals(before[0], after[1])
        assertEquals(before[1], after[2])
    }

    @Test
    fun sittingsThatShareAStartStillGetDistinctKeys() {
        val first = sitting(100L, 150L)
        val second = sitting(100L, 175L)

        // A start-only key would collide for two sittings beginning at the
        // same millisecond; the end time disambiguates them.
        assertNotEquals(sessionKey(1L, first), sessionKey(1L, second))
    }

    @Test
    fun theDayIsPartOfTheKey() {
        val dayOne = sitting(100L, 150L, day = 1L)
        val dayTwo = sitting(100L, 150L, day = 2L)

        assertNotEquals(sessionKey(dayOne.day, dayOne), sessionKey(dayTwo.day, dayTwo))
    }
}
