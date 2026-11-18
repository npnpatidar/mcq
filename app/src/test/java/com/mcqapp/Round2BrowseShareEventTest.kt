package com.mcqapp

import com.mcqapp.ui.results.OneShotEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B3 — Browse's share chooser uses the same replayed one-shot event as
 * Results/Bookmarks. A rotation can happen between the VM finishing the render
 * and the new screen attaching a collector: the event must still be seen once,
 * a consumed event must not fire on a later collector, and a stale consume
 * must not erase a newer event.
 */
class Round2BrowseShareEventTest {

    @Test
    fun eventEmittedBeforeACollectorAttachesIsStillDeliveredExactlyOnce() {
        val events = OneShotEvents<String>()
        events.send("intent-1") // emitted while no screen is collecting

        // A new collector reads the replayed StateFlow value.
        val seen = events.latest.value!!
        assertEquals("intent-1", seen.value)

        events.consume(seen.id)
        assertNull(events.latest.value)
    }

    @Test
    fun aConsumedEventIsNotReshownToALaterCollector() {
        val events = OneShotEvents<String>()
        events.send("first")
        val first = events.latest.value!!
        events.consume(first.id)

        // Negative: the recreated screen sees nothing and starts no chooser.
        assertNull(events.latest.value)
    }

    @Test
    fun aStaleConsumeDoesNotEraseANewerEvent() {
        val events = OneShotEvents<String>()
        events.send("first")
        val first = events.latest.value!!
        events.send("second")
        val second = events.latest.value!!

        assertNotEquals(first.id, second.id)
        events.consume(first.id) // an old screen's late dispatch
        assertEquals(second.id, events.latest.value?.id)
        assertEquals("second", events.latest.value?.value)
    }

    @Test
    fun nullFailureEventReachesTheScreenToo() {
        // A failed render sends null so the screen still closes the dialog.
        val events = OneShotEvents<String?>()
        events.send(null)
        val event = events.latest.value!!
        assertNull(event.value)
        events.consume(event.id)
        assertNull(events.latest.value)
    }
}
