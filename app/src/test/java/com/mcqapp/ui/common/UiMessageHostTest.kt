package com.mcqapp.ui.common

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of the app-wide feedback seam introduced for a11y: the
 * typed message, its one-consumer bus, and the presenter that announces and
 * presents it. None of this needs Compose or Android, so it runs on the ARM64
 * host like the rest of src/test's pure tests.
 *
 * Every test here fails to compile against the pre-change code (no UiMessage,
 * bus or presenter existed), which is the point: these are regression pins for
 * the new mechanism, not restatements of old behaviour.
 */
class UiMessageHostTest {

    @Test
    fun busDeliversMessagesInOrder() = runBlocking {
        val bus = UiMessageBus()
        bus.send(UiMessage("Saved", UiMessageTone.Success))
        bus.send(UiMessage("Could not export", UiMessageTone.Error))

        val first = bus.receive()
        val second = bus.receive()

        assertEquals("Saved", first.text)
        assertEquals(UiMessageTone.Success, first.tone)
        assertEquals("Could not export", second.text)
        assertEquals(UiMessageTone.Error, second.tone)
    }

    @Test
    fun eachToneCarriesADistinctIconAndRoleButLeavesTheTextAlone() {
        // Colour is a second signal, never a replacement for the message.
        assertEquals(ToneIcon.Warning, UiMessageTone.Error.icon)
        assertEquals(ToneRole.Error, UiMessageTone.Error.role)
        assertEquals(ToneIcon.Check, UiMessageTone.Success.icon)
        assertEquals(ToneRole.Primary, UiMessageTone.Success.role)
        assertEquals(ToneIcon.Info, UiMessageTone.Info.icon)
        assertEquals(ToneRole.Secondary, UiMessageTone.Info.role)

        val message = UiMessage("Could not export", UiMessageTone.Error)
        assertEquals("Could not export", message.text)
        assertEquals(ToneIcon.Warning, message.tone.icon)
    }

    @Test
    fun theHostRendersTheToneIconWithoutRewritingTheText() {
        val nav = File("src/main/java/com/mcqapp/ui/navigation/McqNavHost.kt").readText()
        val host = File("src/main/java/com/mcqapp/ui/common/UiMessageSnackbar.kt").readText()
        assertTrue(
            "the nav host must use the tone-aware snackbar host",
            nav.contains("ToneSnackbarHost(") && nav.contains("tone = currentTone")
        )
        assertTrue(
            "the presenter must hand the message tone to the host",
            nav.contains("currentTone = message.tone")
        )
        assertTrue(
            "the snackbar text must remain the sender's text",
            nav.contains("message = message.text")
        )
        assertTrue(
            "the snackbar must render a leading icon before the text",
            host.contains("toneImageVector(tone.icon)") &&
                host.indexOf("toneImageVector(tone.icon)") < host.indexOf("Text(data.visuals.message)")
        )
        assertTrue(
            "the tint must come from a Material 3 role, not a literal",
            host.contains("MaterialTheme.colorScheme.primary") &&
                host.contains("MaterialTheme.colorScheme.secondary") &&
                host.contains("MaterialTheme.colorScheme.error")
        )
    }

    @Test
    fun messageCarriesItsOptionalActionAndDefaultTone() {
        var acted = false
        val message = UiMessage("Retry", actionLabel = "Try again", onAction = { acted = true })

        assertEquals(UiMessageTone.Info, message.tone)
        assertEquals("Try again", message.actionLabel)
        message.onAction?.invoke()
        assertTrue(acted)
    }

    @Test
    fun presenterAnnouncesEveryMessageBeforePresentingIt() = runBlocking {
        val bus = UiMessageBus()
        val events = mutableListOf<String>()
        val presented = mutableListOf<Int>()
        val done = CompletableDeferred<Unit>()
        val presenter = UiMessagePresenter(
            announce = { events.add("announce:$it") },
            show = { message ->
                events.add("show:${message.text}")
                presented.add(presented.size + 1)
                if (presented.size == 2) done.complete(Unit)
                MessageResult.Dismissed
            }
        )

        bus.send(UiMessage("first"))
        bus.send(UiMessage("second"))
        val job = launch { presenter.run(bus) }
        withTimeout(1_000) { done.await() }
        job.cancelAndJoin()

        assertEquals(
            listOf("announce:first", "show:first", "announce:second", "show:second"),
            events
        )
    }

    @Test
    fun presenterInvokesActionOnlyWhenPresentationReportsIt() = runBlocking {
        val bus = UiMessageBus()
        var actions = 0
        val done = CompletableDeferred<Unit>()
        val presenter = UiMessagePresenter(
            announce = { },
            show = { message ->
                actions += 1
                if (actions == 2) done.complete(Unit)
                if (message.text == "retryable") MessageResult.ActionPerformed
                else MessageResult.Dismissed
            }
        )

        var invoked = 0
        bus.send(UiMessage("retryable", onAction = { invoked++ }))
        bus.send(UiMessage("plain", onAction = { invoked++ }))
        val job = launch { presenter.run(bus) }
        withTimeout(1_000) { done.await() }
        job.cancelAndJoin()

        // Two presentations, but only the ActionPerformed one ran its callback.
        assertEquals(2, actions)
        assertEquals(1, invoked)
        assertFalse(invoked == 2)
    }
}
