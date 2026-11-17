package com.mcqapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.ui.stats.charts.ChartCard
import com.mcqapp.ui.stats.charts.LineChart
import com.mcqapp.ui.stats.charts.LineSeries
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real semantics assertions the JVM suite cannot make (no Compose harness
 * there). The card's test tag and its spoken description used to be the same
 * string, so TalkBack read "stats-…" instead of the chart. On the old code
 * both assertions below fail: the tag is also the contentDescription and no
 * "Line chart" description exists.
 */
@RunWith(AndroidJUnit4::class)
class ChartAccessibilityUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun chartTagIsNotTheSpokenDescription() {
        compose.setContent {
            MaterialTheme {
                ChartCard(testTag = "a11y-test-chart", title = {}) {
                    LineChart(
                        labels = listOf("Mon", "Tue"),
                        series = listOf(LineSeries(listOf(1.0, 2.0), Color.Red))
                    )
                }
            }
        }
        assertTrue(
            "chart tag must exist",
            compose.onAllNodesWithTag("a11y-test-chart").fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            "the test tag must not be read aloud as a label",
            compose.onAllNodesWithContentDescription("a11y-test-chart").fetchSemanticsNodes().isEmpty()
        )
        compose.onNodeWithContentDescription("Line chart", substring = true).assertIsDisplayed()
    }
}
