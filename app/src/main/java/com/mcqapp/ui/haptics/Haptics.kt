package com.mcqapp.ui.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Whether the user allows the app's own haptics. The activity reads the
 * DataStore preference once and provides it here, so a call site never has to
 * collect the flow. The default is off so an install that switched haptics
 * off never pulses in the first frames, before the preference has loaded.
 */
val LocalHapticsEnabled = compositionLocalOf { false }

/**
 * The app's whole haptic vocabulary. Keeping it small is deliberate: a tick
 * means "your input registered", a toggle means "this state changed", and
 * confirm/reject are reserved for the moment a result is shown.
 */
enum class Haptic { Tick, ToggleOn, ToggleOff, Confirm, Reject }

/**
 * The only haptic entry point in the app. It applies the Settings switch and
 * delegates the platform call to Compose, which maps each semantic type to the
 * best constant the running API and device support. No `VIBRATE` permission is
 * involved: a device without a vibrator is simply a no-op.
 */
class Haptics(
    private val feedback: HapticFeedback,
    private val enabled: Boolean
) {
    fun emit(haptic: Haptic) {
        if (!enabled) return
        feedback.performHapticFeedback(haptic.platformType())
    }

    /** A short tick for a reveal, advance or grade. */
    fun tick() = emit(Haptic.Tick)

    /** A state change, e.g. an option, flag or bookmark. */
    fun toggle(on: Boolean) = emit(if (on) Haptic.ToggleOn else Haptic.ToggleOff)

    /** The answer the user just revealed was right. */
    fun confirm() = emit(Haptic.Confirm)

    /** The answer the user just revealed was wrong. */
    fun reject() = emit(Haptic.Reject)
}

private fun Haptic.platformType(): HapticFeedbackType = when (this) {
    Haptic.Tick -> HapticFeedbackType.SegmentTick
    Haptic.ToggleOn -> HapticFeedbackType.ToggleOn
    Haptic.ToggleOff -> HapticFeedbackType.ToggleOff
    Haptic.Confirm -> HapticFeedbackType.Confirm
    Haptic.Reject -> HapticFeedbackType.Reject
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    val enabled = LocalHapticsEnabled.current
    return remember(feedback, enabled) { Haptics(feedback, enabled) }
}
