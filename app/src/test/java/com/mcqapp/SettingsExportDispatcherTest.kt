package com.mcqapp

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.ui.settings.SettingsViewModel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * "Export all data (JSON)" used to serialize the whole bank and write the
 * file on the calling dispatcher; `backupAll`/`restoreBackup` in the same
 * file wrap in `Dispatchers.IO`, and the JSON export was the one sibling
 * that never hopped.
 *
 * What is tested here is the seam, not the UI gesture: the body must run on
 * the dispatcher it is given. A single-threaded executor acts as the
 * recording dispatcher — whichever thread invoked the body (and the stream
 * open, which happens inside the same context) is the proof. The public
 * `exportAll` hard-codes `Dispatchers.IO` exactly like its siblings;
 * observing THAT call needs instrumentation and is pinned by review, which
 * is stated plainly in the commit message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class SettingsExportDispatcherTest {

    @Test
    fun theExportBodyRunsOnTheDispatcherItIsGiven() {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "export-recording") }
        val dispatcher = executor.asCoroutineDispatcher()
        val viewModel = SettingsViewModel(
            ApplicationProvider.getApplicationContext() as McqApplication
        )
        try {
            var openedOn: Thread? = null
            val wrote = ByteArrayOutputStream()
            runBlocking {
                viewModel.exportAllOn(
                    Uri.parse("content://unused/export.json"),
                    onError = {},
                    dispatcher = dispatcher,
                    open = {
                        openedOn = Thread.currentThread()
                        // Fake sink: the exporter is under test, not the file.
                        object : OutputStream() {
                            override fun write(b: Int) = Unit
                            override fun write(b: ByteArray, off: Int, len: Int) {
                                wrote.write(b, off, len)
                            }
                        }
                    }
                )
            }
            assertEquals(
                "the export body must run on the given dispatcher, not on the caller",
                "export-recording", openedOn?.name
            )
            assertNotEquals(Thread.currentThread(), openedOn)
            // The body did not just hop — the exporter walked the (empty) bank.
            assertTrue("export produced no bytes: ${wrote.size()}", wrote.size() > 0)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun aStreamThatFailsToOpenIsReported() {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "export-recording") }
        val dispatcher = executor.asCoroutineDispatcher()
        val viewModel = SettingsViewModel(
            ApplicationProvider.getApplicationContext() as McqApplication
        )
        try {
            val reported = mutableListOf<String>()
            runBlocking {
                viewModel.exportAllOn(
                    Uri.parse("content://unused/export.json"),
                    onError = { reported += it },
                    dispatcher = dispatcher,
                    open = { null }
                )
            }
            assertEquals(listOf("Could not open file for writing"), reported)
        } finally {
            executor.shutdown()
        }
    }
}
