package com.mcqapp

import com.mcqapp.ui.settings.restoreConfirmShouldClose
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * INCIDENT: when the backup manifest could not be read, the restore confirm
 * stayed open with an enabled "replace everything" button — a wipe offered
 * over an archive we had never inspected. Pure gate so the rule runs on ARM64.
 */
class RestoreConfirmGateTest {

    @Test
    fun aFailedManifestReadClosesTheConfirm() {
        assertTrue(
            restoreConfirmShouldClose(
                confirmVisible = true,
                manifestReady = false,
                failureTitleRes = R.string.restore_failed,
                expectedFailureTitleRes = R.string.restore_failed
            )
        )
    }

    @Test
    fun aReadyPreviewKeepsTheConfirmOpenEvenIfAnotherRestoreFailed() {
        assertFalse(
            restoreConfirmShouldClose(
                confirmVisible = true,
                manifestReady = true,
                failureTitleRes = R.string.restore_failed,
                expectedFailureTitleRes = R.string.restore_failed
            )
        )
    }

    @Test
    fun unrelatedErrorsAndHiddenConfirmDoNotCloseIt() {
        assertFalse(
            restoreConfirmShouldClose(
                confirmVisible = true,
                manifestReady = false,
                failureTitleRes = R.string.export_failed,
                expectedFailureTitleRes = R.string.restore_failed
            )
        )
        assertFalse(
            restoreConfirmShouldClose(
                confirmVisible = true,
                manifestReady = false,
                failureTitleRes = null,
                expectedFailureTitleRes = R.string.restore_failed
            )
        )
        assertFalse(
            restoreConfirmShouldClose(
                confirmVisible = false,
                manifestReady = false,
                failureTitleRes = R.string.restore_failed,
                expectedFailureTitleRes = R.string.restore_failed
            )
        )
    }
}
