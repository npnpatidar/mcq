package com.mcqapp

import com.mcqapp.ui.results.OneShotEvents
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R1: the share/export chooser is a one-shot event emitted by a ViewModel.
 * The screen may be recreated (rotation/Back) between emission and collection,
 * so the event must survive until consumed, and a consumed event must never
 * fire a second time. The class is pure JVM, so both cases run locally.
 */
class Round2ResultsOneShotEventTest {

    @Test
    fun emittedEventSurvivesUntilTheScreenConsumesIt() = runBlocking {
        val events = OneShotEvents<String>()
        assertNull(events.latest.value)

        events.send("chooser")

        val seen = events.latest.value!!
        assertEquals("chooser", seen.value)
        assertEquals(1L, seen.id)
    }

    @Test
    fun consumedEventIsClearedAndNeverReplaysToANewCollector() = runBlocking {
        val events = OneShotEvents<String>()
        events.send("first")
        val first = events.latest.value!!

        events.consume(first.id)

        // A recreated composable collecting afterwards sees nothing; the
        // already-handled chooser is not started twice.
        assertNull(events.latest.value)
    }

    @Test
    fun aStaleConsumeDoesNotEraseANewerEvent() = runBlocking {
        val events = OneShotEvents<String>()
        events.send("first")
        val first = events.latest.value!!
        events.send("second")
        val second = events.latest.value!!

        assertNotEquals(first.id, second.id)
        events.consume(first.id) // array-of-the-old-screen's late dispatch

        assertEquals("second", events.latest.value?.value)
        assertEquals(second.id, events.latest.value?.id)
    }

    @Test
    fun twoEventsKeepDistinctIds() = runBlocking {
        val events = OneShotEvents<Int>()
        events.send(1)
        events.send(2)
        assertNotEquals(events.latest.value?.id, 1L)
        assertEquals(2L, events.latest.value?.id)
        assertEquals(2, events.latest.value?.value)
    }
}
