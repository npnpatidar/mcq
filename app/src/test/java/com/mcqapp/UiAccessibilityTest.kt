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
