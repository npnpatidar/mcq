package com.mcqapp.ui.results

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * A buffered, exactly-once event for UI side effects (starting a share
 * chooser). The producer lives in a ViewModel, so rendering survives rotation
 * or Back; the screen only observes and starts the activity.
 *
 * A StateFlow is used rather than a plain SharedFlow because a new collector
 * (a recreated screen) must still see an event emitted during the gap, while
 * an already-handled event must not fire twice. Monotonic ids let the collector
 * ignore anything it has already acted on; [consume] clears the slot so a
 * later, distinct event is not mistaken for a replay.
 *
 * Kept free of Android/Compose types so the delivery contract is pure-JVM
 * testable (Round2ResultsOneShotEventTest).
 */
class OneShotEvents<T> {
    data class Event<T>(val id: Long, val value: T)

    private val nextId = AtomicLong(0)
    private val _latest = MutableStateFlow<Event<T>?>(null)
    val latest: StateFlow<Event<T>?> = _latest.asStateFlow()

    /** Publishes a new event; distinct ids are never conflated into a replay. */
    fun send(value: T) {
        _latest.value = Event(nextId.incrementAndGet(), value)
    }

    /** Clears the slot only if it still holds [id]; a newer event survives. */
    fun consume(id: Long) {
        _latest.update { current -> if (current?.id == id) null else current }
    }
}
