package com.mcqapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.ui.editor.MathLiveEditor
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The formula editor on a 320 dp screen (CI emulator only): an untouched,
 * one-line formula must not reserve the old 560dp keyboard-sized gap. The
 * pure geometry rules are pinned in MathLiveTest; this checks the Compose
 * wrapper actually uses them.
 */
@RunWith(AndroidJUnit4::class)
class MathLiveEditorUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unfocusedFormulaEditorIsCompactAt320dp() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    MathLiveEditor(
                        initialLatex = "x^2",
                        blockId = "math-ui-test",
                        onMathMl = { _, _ -> },
                        modifier = Modifier.testTag("mathlive-wrapper")
                    )
                }
            }
        }
        compose.waitForIdle()
        val heightPx = compose.onNodeWithTag("mathlive-wrapper")
            .fetchSemanticsNode().size.height
        val compactPx = with(compose.density) { 100.dp.toPx() }.toInt()
        assertTrue(
            "an unfocused one-line formula must be compact, was $heightPx px",
            heightPx <= compactPx
        )
    }
}
