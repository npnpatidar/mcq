package com.mcqapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.stats.HeatmapDay
import com.mcqapp.ui.stats.charts.HeatmapChart
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The calendar used to squeeze all 365 days into the card, making the cells
 * too small to tap. It now keeps 12 dp cells and scrolls. The content width is
 * a real layout width, so this needs the Compose harness and runs on CI's
 * emulator (device only, like the other *UiTest classes).
 */
@RunWith(AndroidJUnit4::class)
class StatsHeatmapScrollUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val days = List(365) { index ->
        HeatmapDay(
            dayStart = index * 86_400_000L,
            reviews = if (index % 10 == 0) 2 else 0,
            seconds = 0L
        )
    }

    @Test
    fun heatmapCanvasIsWiderThanTheViewportAndScrolls() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).testTag("heatmap-host")) {
                    HeatmapChart(days = days, tooltipCount = { "$it reviews" })
                }
            }
        }

        val hostWidth = compose.onNodeWithTag("heatmap-host").fetchSemanticsNode().size.width
        val canvas = compose.onNodeWithTag("stats-heatmap-canvas")
        val canvasWidth = canvas.fetchSemanticsNode().size.width
        assertTrue(
            "canvas ($canvasWidth px) must be wider than the 320 dp host ($hostWidth px)",
            canvasWidth > hostWidth
        )

        val beforeSwipe = canvas.fetchSemanticsNode().boundsInRoot.left
        // Swipe on the host: the canvas centre is outside the visible window.
        compose.onNodeWithTag("heatmap-host").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val afterSwipe = compose.onNodeWithTag("stats-heatmap-canvas")
            .fetchSemanticsNode().boundsInRoot.left
        assertTrue(
            "swiping must move the calendar content ($beforeSwipe -> $afterSwipe)",
            afterSwipe < beforeSwipe
        )
    }
}
