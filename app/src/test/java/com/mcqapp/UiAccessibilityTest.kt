package com.mcqapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guards for the accessibility findings. These cannot be exercised
 * from the JVM suite (no Compose test harness is available on this host), so
 * they assert the properties in the source instead of at runtime.
 */
class UiAccessibilityTest {

    private fun source(path: String): String {
        val file = File("src/main/java/com/mcqapp/$path")
        assertTrue("missing: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun studyOptionsAreAnnouncedAsCheckboxes() {
        val study = source("ui/study/StudyScreen.kt")
        assertTrue("option rows must expose a role", study.contains("Role.Checkbox"))
        assertTrue("option rows must expose a state description", study.contains("stateDescription"))
        assertTrue(
            "a bare clickable row is not announced as selectable",
            !study.contains(".clickable(enabled = !revealed)")
        )
    }

    @Test
    fun noFocusableControlDoesNothing() {
        // A focusable, unlabelled button that does nothing was read as a
        // broken control by screen readers.
        val offenders = walk("ui").filter { file ->
            Regex("""IconButton\(onClick = \{\}\)""").containsMatchIn(file.readText())
        }
        assertTrue("no-op IconButton still present: $offenders", offenders.isEmpty())
    }

    @Test
    fun touchTargetsAreNotSmallerThanTheMinimum() {
        // Only the control's own modifier matters: a 20dp Icon inside a 48dp
        // IconButton is correct, a 32dp IconButton is not.
        val buttonModifier = Regex(
            """(IconButton|IconToggleButton)\([^)]{0,200}?modifier = Modifier\.(height|size)\((\d+)\.dp\)"""
        )
        val offenders = walk("ui").filter { file ->
            buttonModifier.findAll(file.readText()).any { it.groupValues[3].toInt() < 48 }
        }
        assertTrue("sub-48dp control found: $offenders", offenders.isEmpty())
    }

    @Test
    fun renderedImagesAreDescribed() {
        val offenders = walk("ui").flatMap { file ->
            Regex("""QuestionImage\((?:[^)]*\n)*?\)""")
                .findAll(file.readText())
                .map { it.value }
                .filter { !it.contains("contentDescription") }
                .map { file.name }
                .toList()
        }
        assertTrue("QuestionImage without a description: $offenders", offenders.isEmpty())
    }

    @Test
    fun noDeadQuestionImageCallRemains() {
        val results = source("ui/results/ResultsScreen.kt")
        assertFalse(results.contains("QuestionImage(src = null"))
    }

    @Test
    fun formulaEditorWebViewSpeaksItsCurrentValue() {
        val editor = source("ui/editor/MathLiveEditor.kt")
        assertTrue(
            "the WebView must have a spoken description",
            editor.contains(".semantics { contentDescription = editorDescription }")
        )
        assertTrue(
            "the description must include the current LaTeX",
            editor.contains("R.string.a11y_formula_editor_value")
        )
        assertTrue(
            "the description must track the pushed value without remounting",
            editor.contains("spokenLatex.value = initialLatex")
        )
        assertFalse(
            "merging descendants hides MathLive's keyboard keys",
            editor.contains("mergeDescendants = true")
        )
        // The label is modifier-only: the keyed WebView must not be remounted.
        assertTrue("JS bridge must stay intact", editor.contains("addJavascriptInterface"))
        assertTrue("teardown must stay intact", editor.contains("onRelease ="))
    }

    @Test
    fun richContentPreviewWebViewHasASpokenLabel() {
        val content = source("util/ContentElements.kt")
        assertTrue(
            "the rendered-content WebView must be one labelled node",
            content.contains(".semantics(mergeDescendants = true) { contentDescription")
        )
        assertTrue(
            "the label must be a string resource",
            content.contains("R.string.a11y_rich_content")
        )
        assertTrue("the height bridge must stay intact", content.contains("CONTENT_HEIGHT_BRIDGE"))
    }

    @Test
    fun chartCardsUseTestTagsAsTagsNotDescriptions() {
        val kit = source("ui/stats/charts/ChartKit.kt")
        assertFalse(
            "a test tag read aloud as a label is the bug being fixed",
            kit.contains("contentDescription = testTag")
        )
        assertTrue("the tag must move to a real test tag", kit.contains(".testTag(testTag)"))
        assertTrue("ChartCard must accept a human-readable description", kit.contains("description: String? = null"))
    }

    @Test
    fun everyCanvasChartExposesASpokenSummary() {
        val charts = listOf(
            "StackedBarChart.kt",
            "LineChart.kt",
            "HeatmapChart.kt",
            "MiniCharts.kt"
        )
        charts.forEach { name ->
            val text = source("ui/stats/charts/$name")
            assertTrue("$name must build a chart summary", text.contains("chartSummary("))
            assertTrue(
                "$name must expose the summary through semantics",
                text.contains(".semantics { contentDescription = summary }")
            )
        }
    }

    @Test
    fun chartSummariesUseLocalisedFormatStrings() {
        val strings = File("src/main/res/values/strings.xml").readText()
        listOf(
            "a11y_chart_summary", "a11y_chart_point", "a11y_chart_series_points",
            "a11y_chart_bar", "a11y_chart_line",
            "a11y_chart_calendar", "a11y_chart_donut", "a11y_chart_calibration",
            "a11y_chart_more", "a11y_chart_n_reviews"
        ).forEach { name ->
            assertTrue("missing localised string $name", strings.contains("name=\"$name\""))
        }
        assertTrue("the summary needs format args", strings.contains("name=\"a11y_chart_summary\">%1\$s. %2\$s"))
        assertTrue(
            "a named series needs a label and its points",
            strings.contains("name=\"a11y_chart_series_points\">%1\$s: %2\$s")
        )
    }

    @Test
    fun chartSummariesNameEverySeriesAndGuardAllRoles() {
        val line = source("ui/stats/charts/LineChart.kt")
        assertFalse(
            "a first-only fold drops every series after the first",
            line.contains("firstNotNullOfOrNull")
        )
        assertTrue(
            "each series must be spoken with its own name and points",
            line.contains("ChartSeriesSummary(name, points)")
        )
        val kit = source("ui/stats/charts/ChartKit.kt")
        listOf("primary", "tertiary", "secondary", "error", "outline", "inversePrimary")
            .forEach { role ->
                assertTrue(
                    "$role must go through the non-text contrast guard",
                    Regex("""chartSeriesColor\(\s*MaterialTheme\.colorScheme\.$role,""")
                        .containsMatchIn(kit)
                )
            }
    }

    @Test
    fun verdictColoursComeFromThePalette() {
        // Hardcoded light-theme hexes paired badly with dark mode.
        // The palette itself is the one place these values belong.
        val palette = "ui/theme/VerdictColors.kt"
        val offenders = walk("ui").filterNot { it.invariantSeparatorsPath.endsWith(palette) }
            .filter { file ->
                Regex("""0xFFC8E6C9|0xFFFFCDD2|0xFF2E7D32|0xFFC62828|0xFFE65100|0xFFA5D6A7|0xFFFFCC80""")
                    .containsMatchIn(file.readText())
            }
        assertTrue("hardcoded verdict colours remain: $offenders", offenders.isEmpty())
        assertTrue(source("ui/theme/VerdictColors.kt").contains("fun verdictColors()"))
    }

    private fun walk(dir: String): List<File> =
        File("src/main/java/com/mcqapp/$dir").walkTopDown().filter { it.extension == "kt" }.toList()
}
