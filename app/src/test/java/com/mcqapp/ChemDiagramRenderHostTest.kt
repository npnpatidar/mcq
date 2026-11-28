package com.mcqapp

import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.export.ChemDiagramRenderHost
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The host's timing contract, without a real page: an injected timeout expires
 * to null, detaching fails the in-flight request, and a second request cannot
 * start until the first has released the render mutex. The fake WebView records
 * load calls instead of running SmilesDrawer; CI is authoritative.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChemDiagramRenderHostTest {

    private class RecordingWebView(context: Context) : WebView(context) {
        val loadTimes = mutableListOf<Long>()

        override fun loadDataWithBaseURL(
            baseUrl: String?,
            data: String,
            mimeType: String?,
            encoding: String?,
            historyUrl: String?
        ) {
            // Deliberately no super call: no page, no bridge, only timing.
            loadTimes += System.currentTimeMillis()
        }
    }

    private lateinit var view: RecordingWebView

    @Before
    fun setUp() {
        // The host normally marshals to the Android main looper. Robolectric's
        // paused looper would not advance while runBlocking is waiting, so the
        // test seam makes the same code run immediately and deterministically.
        ChemDiagramRenderHost.mainDispatcher = Dispatchers.Unconfined
        view = RecordingWebView(ApplicationProvider.getApplicationContext())
        ChemDiagramRenderHost.attach(view)
    }

    @After
    fun tearDown() {
        ChemDiagramRenderHost.detach(view)
        ChemDiagramRenderHost.mainDispatcher = Dispatchers.Main.immediate
        view.destroy()
    }

    @Test
    fun theInjectedTimeoutReturnsNull() = runBlocking {
        assertNull(ChemDiagramRenderHost.render("c1ccccc1", timeoutMillis = 20))
        assertEquals("the page was attempted once", 1, view.loadTimes.size)
    }

    @Test
    fun detachCompletesAnInFlightRequestWithNull() = runBlocking {
        val running = async(start = CoroutineStart.UNDISPATCHED) {
            ChemDiagramRenderHost.render("c1ccccc1", timeoutMillis = 5_000)
        }
        assertEquals("the request reached the host before detach", 1, view.loadTimes.size)
        ChemDiagramRenderHost.detach(view)
        assertNull("a detached host must fail the waiting request", running.await())
    }

    @Test
    fun twoRequestsAreSerialised() = runBlocking {
        val timeout = 40L
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            ChemDiagramRenderHost.render("CCO", timeoutMillis = timeout)
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            ChemDiagramRenderHost.render("O", timeoutMillis = timeout)
        }
        val results = listOf(first, second).awaitAll()

        assertTrue(results.all { it == null })
        assertEquals(2, view.loadTimes.size)
        val gap = view.loadTimes[1] - view.loadTimes[0]
        assertTrue(
            "the second page started after the first timeout, gap=${gap}ms",
            gap >= timeout - 10
        )
    }
}
