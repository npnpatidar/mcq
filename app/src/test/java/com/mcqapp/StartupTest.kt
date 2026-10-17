package com.mcqapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §5.8 of the scaling plan: the application must not need the database to
 * start. The repository (and with it the Room instance) is lazy and
 * `onCreate` only sweeps Anki temp files, so nothing may have created the
 * database by the time the app is up — a cold start must not pay for
 * opening it on the main thread.
 *
 * Creating the application is enough to check this: if anything in
 * `onCreate` reached for the repository, the file would exist already.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupTest {

    @Test
    fun applicationOnCreateLeavesTheDatabaseClosed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(
            "Application.onCreate opened the database; keep the repository lazy",
            context.getDatabasePath("mcq.db").exists()
        )
    }
}
