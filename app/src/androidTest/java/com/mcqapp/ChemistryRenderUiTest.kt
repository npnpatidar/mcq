package com.mcqapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import com.mcqapp.domain.ContentElement
import com.mcqapp.util.ContentElements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The chemistry block shares the mixed WebView path with formulas. On the
 * owner's 320 dp screen it must stay inside the viewport, keep the spoken
 * description TalkBack reads, and actually draw a structure — a content
 * description alone would still pass if SmilesDrawer had failed to load.
 * Runs on the CI emulator.
 */
@RunWith(AndroidJUnit4::class)
class ChemistryRenderUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount)
            .mapNotNull { findWebView(view.getChildAt(it)) }
            .firstOrNull()
        else -> null
    }

    private fun webView(): WebView =
        findWebView(compose.activity.window.decorView) ?: error("mixed WebView not attached")

    private fun evaluate(script: String): String {
        val result = AtomicReference<String>("null")
        val latch = CountDownLatch(1)
        compose.activity.runOnUiThread {
            webView().evaluateJavascript(script) { value ->
                result.set(value ?: "null")
                latch.countDown()
            }
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "JS evaluation timed out: $script" }
        return result.get()
    }

    private fun structure() = listOf(
        ContentElement.TextElement("Compound: "),
        ContentElement.ChemElement(
            smiles = "CCO",
            formula = "C2H6O",
            caption = "ethanol",
            spoken = "ethanol"
        )
    )

    @Test
    fun chemistryFitsA320DpScreenAndActuallyRendersTheSvg() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(480.dp)) {
                    ContentElements(structure())
                }
            }
        }
        val node = compose.onNodeWithContentDescription("ethanol", substring = true)
        node.assertIsDisplayed()
        val bounds = node.getBoundsInRoot()
        val widthDp = bounds.right.value - bounds.left.value
        assertTrue("structure overflows 320dp: $bounds", widthDp <= 320f)

        // The bundled drawer must have put real geometry in the SVG, not just
        // left an empty <svg> shell behind a spoken description.
        compose.waitUntil(timeoutMillis = 10_000) {
            evaluate("document.querySelector('#mcq-chem-0 path') != null") == "true"
        }
    }

    @Test
    fun aDrawnStructureIsRasterisedAndReportedWithItsKey() {
        val captured = AtomicReference<Pair<String, String>?>(null)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(480.dp)) {
                    ContentElements(
                        structure(),
                        chemKey = "chem-42",
                        onChemDiagram = { key, png -> captured.set(key to png) }
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) { captured.get() != null }
        val (key, png) = captured.get()!!
        assertEquals("chem-42", key)
        assertTrue("not a PNG data URI: ${png.take(40)}", png.startsWith("data:image/png;base64,"))
        assertTrue("empty PNG", png.length > 100)
    }
}
