package com.mcqapp.ui.common

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.channels.Channel

/** Which leading glyph a tone uses. Pure so the mapping is JVM-testable. */
enum class ToneIcon { Check, Info, Warning }

/**
 * Which Material 3 *role* colours a tone. A role, never a hex value, so every
 * custom theme keeps its own palette and contrast.
 */
enum class ToneRole { Primary, Secondary, Error }

/**
 * One transient piece of feedback for the learner: a plain [text], a [tone]
 * and an optional single [onAction] (with [actionLabel]) for the Snackbar's
 * action button.
 *
 * A tone now carries a leading [icon] and a Material 3 [role] as well as the
 * duration. The text is never changed by the tone: colour is a second signal,
 * not the only one, so a learner who cannot distinguish the tint still reads
 * the same message. The mapping stays small and pure so it can be unit-tested
 * without Compose; [com.mcqapp.ui.common.ToneSnackbarHost] turns it into the
 * actual icon and colour.
 *
 * See https://developer.android.com/develop/ui/compose/components/snackbar
 */
enum class UiMessageTone(val icon: ToneIcon, val role: ToneRole) {
    Error(ToneIcon.Warning, ToneRole.Error),
    Success(ToneIcon.Check, ToneRole.Primary),
    Info(ToneIcon.Info, ToneRole.Secondary)
}

/** A transient message. [text] is already user-facing copy, never a raw exception. */
data class UiMessage(
    val text: String,
    val tone: UiMessageTone = UiMessageTone.Info,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null
)

/** How a snackbar presentation ended; [UiMessagePresenter] needs only this much. */
enum class MessageResult { Dismissed, ActionPerformed }

/**
 * The accessibility half of the feedback seam. The activity provides an
 * implementation backed by [android.view.View.announceForAccessibility], so a
 * message is spoken even when it is not rendered as a snackbar (e.g. an error
 * that already has a persistent inline Text).
 *
 * See https://developer.android.com/reference/android/view/View#announceForAccessibility(java.lang.CharSequence)
 */
fun interface Announcer {
    fun announce(text: String)
}

/** Default no-op so previews and tests that do not provide one stay silent. */
val NoOpAnnouncer = Announcer {}

/** Full feedback (snackbar + announcement). Provided once at the nav root. */
val NoOpMessageSender: (UiMessage) -> Unit = { _ -> }

val LocalAnnouncer = compositionLocalOf { NoOpAnnouncer }
val LocalUiMessageSender = compositionLocalOf { NoOpMessageSender }

/**
 * An unbounded, single-consumer queue of [UiMessage]s. The app owns one at the
 * nav root; screens push into it from callbacks and error collectors. Unbounded
 * is deliberate — dropping a transient error because the snackbar was still
 * dismissing the previous one is worse than a short queue.
 */
class UiMessageBus {
    private val channel = Channel<UiMessage>(Channel.UNLIMITED)

    fun send(message: UiMessage) {
        channel.trySend(message)
    }

    /** For the host collector and for pure-JVM tests. */
    suspend fun receive(): UiMessage = channel.receive()

    suspend fun consumeEach(handle: suspend (UiMessage) -> Unit) {
        for (message in channel) handle(message)
    }
}

/**
 * The pure, testable core of the host: announce every message, then present it
 * and invoke its action only when the presentation reports an action. The
 * Compose layer supplies [announce] (through [LocalAnnouncer]) and [show]
 * (through `SnackbarHostState.showSnackbar`), so this class has no Android
 * dependency and its ordering/action behaviour can be tested on the JVM.
 */
class UiMessagePresenter(
    private val announce: (String) -> Unit,
    private val show: suspend (UiMessage) -> MessageResult
) {
    suspend fun run(bus: UiMessageBus) {
        bus.consumeEach { message ->
            announce(message.text)
            if (show(message) == MessageResult.ActionPerformed) {
                message.onAction?.invoke()
            }
        }
    }
}
