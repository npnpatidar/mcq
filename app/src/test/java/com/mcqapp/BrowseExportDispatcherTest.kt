package com.mcqapp

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.export.ExportFormat
import com.mcqapp.data.io.PaperDto
import com.mcqapp.ui.browse.BrowseViewModel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Regression: `exportSelection` used to build the selection DTO on Main, while
 * `shareSelectionIntent` wrapped the same O(n) query-per-id work in IO. The
 * export body must now run on the dispatcher it is given.
 *
 * The dispatcher and the DTO builder are seams on [BrowseViewModel.exportSelectionOn]
 * (the `SettingsExportDispatcherTest` precedent). A single-thread executor is
 * the recording dispatcher: the thread that builds the DTO is the proof, and
 * it must be neither the caller nor Main. The file open is faked so no real
 * output stream is touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class BrowseExportDispatcherTest {

    @Test
    fun theSelectionDtoBuildRunsOnTheGivenDispatcher() {
        var worker: Thread? = null
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "browse-export-recording").also { worker = it }
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val app = ApplicationProvider.getApplicationContext<Context>() as McqApplication
        val viewModel = BrowseViewModel(app, "p")

        var dtoBuiltOn: Thread? = null
        var streamOpened = false
        val wrote = ByteArrayOutputStream()
        try {
            runBlocking {
                viewModel.exportSelectionOn(
                    uri = Uri.parse("content://unused/export.json"),
                    questionIds = setOf("q1"),
                    title = "Selected (1 question)",
                    format = ExportFormat.JSON_INLINE,
                    dispatcher = dispatcher,
                    buildDto = { _, _ ->
                        dtoBuiltOn = Thread.currentThread()
                        PaperDto(id = "p", title = "Selected")
                    },
                    open = {
                        streamOpened = true
                        wrote
                    }
                )
            }
            assertSame(
                "the DTO build must run on the given dispatcher, not the caller",
                worker, dtoBuiltOn
            )
            assertNotEquals(Thread.currentThread(), dtoBuiltOn)
            assertTrue("the fake output stream was never opened", streamOpened)
        } finally {
            executor.shutdown()
        }
    }
}
