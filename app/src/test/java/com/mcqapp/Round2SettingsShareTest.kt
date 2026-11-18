package com.mcqapp

import com.mcqapp.ui.settings.SettingsOperationState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SE5: the share one-shot guard. `SettingsViewModel.share` calls
 * [SettingsOperationState.tryBegin] synchronously on the tap, before launching
 * the build, so a second tap in the same frame cannot start a second build.
 * This harness mirrors that tap path using the real holder the ViewModel owns.
 */
class Round2SettingsShareTest {

    @Test
    fun secondTapBeforeTheBuildFinishesCannotStartASecondBuild() = runBlocking {
        val operations = SettingsOperationState()
        val gate = CompletableDeferred<Unit>()
        var builds = 0

        // Mirrors SettingsViewModel.share: claim the slot first, then build.
        suspend fun tap() {
            if (!operations.tryBegin()) return
            try {
                builds++
                gate.await()
            } finally {
                operations.finish()
            }
        }

        val first = launch { tap() }
        val second = launch { tap() }
        yield()
        yield()

        assertEquals("only the first tap may build", 1, builds)
        assertTrue("the winning tap owns the busy slot", operations.busy.value)

        gate.complete(Unit)
        first.join()
        second.join()
        assertFalse("busy clears when the build ends", operations.busy.value)
    }

    @Test
    fun aTapAfterTheBuildFinishesMayBuildAgain() = runBlocking {
        val operations = SettingsOperationState()
        val gate = CompletableDeferred<Unit>()
        var builds = 0

        suspend fun tap() {
            if (!operations.tryBegin()) return
            try {
                builds++
                gate.await()
            } finally {
                operations.finish()
            }
        }

        val first = launch { tap() }
        yield()
        yield()
        assertEquals(1, builds)
        gate.complete(Unit)
        first.join()

        // A fresh tap after completion claims the slot and builds again.
        tap()
        assertEquals("a later tap is allowed", 2, builds)
        assertFalse("the second build clears busy too", operations.busy.value)
    }
}
