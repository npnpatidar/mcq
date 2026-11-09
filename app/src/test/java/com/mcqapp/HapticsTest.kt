package com.mcqapp

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.mcqapp.ui.haptics.Haptics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's haptic entry point: the Settings switch decides whether anything
 * reaches the platform, and each method uses the semantic effect Compose maps
 * for the running API. A wrong mapping makes a "short tick" feel like a long
 * press (the first prototype did exactly that), and a gate that leaks defeats
 * the switch entirely, so both are pinned here without an emulator.
 */
class HapticsTest {

    private class Recorder : HapticFeedback {
        val types = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            types += hapticFeedbackType
        }
    }

    private fun recorder() = Recorder().also { it.types.clear() }

    @Test
    fun eachActionUsesItsOwnSemanticEffect() {
        val recorder = recorder()
        val haptics = Haptics(recorder, enabled = true)

        haptics.tick()
        haptics.toggle(on = true)
        haptics.toggle(on = false)
        haptics.confirm()
        haptics.reject()

        assertEquals(
            listOf(
                HapticFeedbackType.SegmentTick,
                HapticFeedbackType.ToggleOn,
                HapticFeedbackType.ToggleOff,
                HapticFeedbackType.Confirm,
                HapticFeedbackType.Reject
            ),
            recorder.types
        )
    }

    @Test
    fun oneActionProducesOnePulse() {
        val recorder = recorder()
        val haptics = Haptics(recorder, enabled = true)

        haptics.tick()
        haptics.confirm()

        assertEquals(2, recorder.types.size)
    }

    @Test
    fun theSwitchStopsEveryEffect() {
        val recorder = recorder()
        val haptics = Haptics(recorder, enabled = false)

        haptics.tick()
        haptics.toggle(on = true)
        haptics.toggle(on = false)
        haptics.confirm()
        haptics.reject()

        assertTrue("nothing may reach the platform while the switch is off", recorder.types.isEmpty())
    }
}
