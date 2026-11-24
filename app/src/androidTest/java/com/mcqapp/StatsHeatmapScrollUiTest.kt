package com.mcqapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.stats.HeatmapDay
import com.mcqapp.ui.stats.charts.HeatmapChart
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The calendar used to squeeze all 365 days into the card, making the cells
 * too small to tap. It now keeps 12 dp cells and scrolls, and opens at the
 * newest end because the day list is oldest-first. The content width is a real
 * layout width, so this needs the Compose harness and runs on CI's emulator
 * (device only, like the other *UiTest classes).
 */
@RunWith(AndroidJUnit4::class)
class StatsHeatmapScrollUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun sampleDays(count: Int) = List(count) { index ->
        HeatmapDay(
            dayStart = index * 86_400_000L,
            reviews = if (index % 10 == 0) 2 else 0,
            seconds = 0L
        )
    }

    private fun setCalendar(days: List<HeatmapDay>) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).testTag("heatmap-host")) {
                    HeatmapChart(days = days, tooltipCount = { "$it reviews" })
                }
            }
        }
        compose.waitForIdle()
    }

    // boundsInRoot is clipped by the parent horizontalScroll viewport, which
    // hides both the real content width and the scroll offset. The unclipped
    // bounds still follow the canvas's placement, so they expose scroll.
    // This API returns a DpRect, so host and canvas bounds must both use it.
    private fun hostBounds() = compose.onNodeWithTag("heatmap-host").getUnclippedBoundsInRoot()

    private fun canvasBounds() = compose.onNodeWithTag("stats-heatmap-canvas").getUnclippedBoundsInRoot()

    private fun rightEdgeGap() = (canvasBounds().right - hostBounds().right).value

    @Test
    fun calendarOpensOnTheNewestEndWithoutScrolling() {
        setCalendar(sampleDays(365))

        val host = hostBounds()
        val canvas = canvasBounds()
        val canvasWidth = (canvas.right - canvas.left).value
        val hostWidth = (host.right - host.left).value
        assertTrue(
            "canvas width ($canvasWidth dp) must exceed the 320 dp host ($hostWidth dp)",
            canvasWidth > hostWidth
        )
        assertTrue(
            "the newest column must be visible on first composition " +
                "(canvas right ${canvas.right}, host right ${host.right})",
            abs(rightEdgeGap()) <= 2f
        )
        assertTrue(
            "the calendar must not open on the oldest end (canvas left ${canvas.left})",
            canvas.left < host.left
        )
    }

    @Test
    fun calendarReanchorsToTheNewestEndWhenTheDataChanges() {
        val shownDays = mutableStateOf(sampleDays(365))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).testTag("heatmap-host")) {
                    HeatmapChart(days = shownDays.value, tooltipCount = { "$it reviews" })
                }
            }
        }
        compose.waitForIdle()
        assertTrue(
            "the 53-week calendar starts at the newest end",
            abs(rightEdgeGap()) <= 2f
        )

        // 300 days is 43 weeks: a different content width must re-anchor too.
        compose.runOnUiThread { shownDays.value = sampleDays(300) }
        compose.waitForIdle()
        assertTrue(
            "a shorter window must also open at the newest end after a data change",
            abs(rightEdgeGap()) <= 2f
        )
    }

    @Test
    fun heatmapCanvasIsWiderThanTheViewportAndScrolls() {
        setCalendar(sampleDays(365))

        val beforeSwipe = canvasBounds().left
        // The canvas centre is outside the visible window; swipe on the host.
        // We start at the newest end, so swiping right reveals older weeks.
        compose.onNodeWithTag("heatmap-host").performTouchInput { swipeRight() }
        compose.waitForIdle()
        val afterSwipe = canvasBounds().left
        assertTrue(
            "swiping right must move the calendar towards the oldest weeks ($beforeSwipe -> $afterSwipe)",
            afterSwipe > beforeSwipe
        )
    }
}
