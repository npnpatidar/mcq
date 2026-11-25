package com.mcqapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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
        // evaluateJavascript hands back the value JSON-encoded: a JS string
        // arrives with surrounding quotes, so unwrap it before comparing.
        return result.get().removePrefix("\"").removeSuffix("\"")
    }

    /**
     * SmilesDrawer 2.1.1's SvgDrawer emits bonds as <line> and atoms as
     * <text>/<g> — there is no <path> in its output (that tag only appears in
     * an arrowhead marker in a different module). Check the real geometry,
     * that the SVG is still visible, and that the failure fallback stayed
     * hidden; a bare spoken description would pass all three checks if the
     * drawer had never run.
     */
    private fun svgState(): String = evaluate(
        "(function(){" +
            "var s=document.getElementById('mcq-chem-0');" +
            "var f=document.getElementById('mcq-chem-0-fallback');" +
            "if(!s)return 'no-svg';" +
            "if(!f)return 'no-fallback';" +
            "if(getComputedStyle(s).display==='none')return 'svg-hidden';" +
            "if(getComputedStyle(f).display!=='none')return 'fallback-shown';" +
            "var b=s.getBoundingClientRect();" +
            "if(!(b.height>0))return 'zero-height';" +
            "var n=s.querySelectorAll('line,text,circle,polygon').length;" +
            "if(!(n>0))return 'no-shapes';" +
            "return 'ok:'+n+':'+Math.round(b.height)+':'+Math.round(b.width);" +
            "})()"
    )

    /**
     * The reaction is an inline flex row: compact scheme + arrow + reagent
     * caption + product label on ONE line. Probe the whole container's box (so
     * a stacked/multi-line layout fails), plus the arrow, reagent text and the
     * product label. The caption uses an ideographic baseline, so comparing
     * tspan and line boxes is not a reliable overlap probe across emulators —
     * a real (uncapped) container height plus an explicit nowrap check are.
     */
    private fun reactionSvgState(): String = evaluate(
        "(function(){" +
            "var c=document.querySelector('.mcq-chem-reaction');" +
            "var s=document.getElementById('mcq-chem-0');" +
            "var f=document.getElementById('mcq-chem-0-fallback');" +
            "if(!c)return 'no-container';" +
            "if(!s)return 'no-svg';" +
            "if(!f)return 'no-fallback';" +
            "if(getComputedStyle(s).display==='none')return 'svg-hidden';" +
            "if(getComputedStyle(f).display!=='none')return 'fallback-shown';" +
            "if(getComputedStyle(c).flexWrap!=='nowrap')return 'wrapping';" +
            "var b=c.getBoundingClientRect();" +
            "if(!(b.height>0))return 'zero-height';" +
            "if(!s.querySelector('line[marker-end]'))return 'no-arrow';" +
            "if(s.textContent.indexOf('reagent')<0)return 'no-reagent-text';" +
            "var p=c.querySelector('.mcq-chem-product');" +
            "if(!p)return 'no-product-label';" +
            "if(p.textContent.trim()!=='A')return 'wrong-product-label';" +
            "var n=s.querySelectorAll('line,text,tspan,circle,polygon').length;" +
            "if(!(n>0))return 'no-shapes';" +
            "return 'ok:'+n+':'+Math.round(b.height)+':'+Math.round(b.width);" +
            "})()"
    )

    /**
     * On the dark theme the structure must use the page's text colour; the
     * bundled 'light' theme painted atoms #222/#666, which was unreadable.
     * Compare the luminance of a coloured atom label with the body colour so
     * a difference in CSS notation (rgb vs rgba) cannot cause a false fail.
     */
    private fun structureColorState(): String = evaluate(
        "(function(){" +
            "var s=document.getElementById('mcq-chem-0');" +
            "if(!s)return 'no-svg';" +
            "var t=s.querySelector('tspan[fill]');" +
            "if(!t)return 'no-coloured-atom';" +
            "var body=getComputedStyle(document.body).color;" +
            "var paint=getComputedStyle(t).fill;" +
            "function rgb(v){var m=v.match(/[0-9.]+/g);" +
            "return m&&m.length>=3?[+m[0],+m[1],+m[2]]:null;}" +
            "var a=rgb(body),b=rgb(paint);" +
            "if(!a||!b)return 'unparsed:'+body+':'+paint;" +
            "var lum=function(c){return .2126*c[0]+.7152*c[1]+.0722*c[2];};" +
            "var d=Math.abs(lum(a)-lum(b));" +
            "return d<12?'ok:'+Math.round(d)+':'+body+':'+paint:" +
            "'mismatch:'+Math.round(d)+':'+body+':'+paint;" +
            "})()"
    )

    private fun structure() = listOf(
        ContentElement.TextElement("Compound: "),
        ContentElement.ChemElement(
            smiles = "CCO",
            formula = "C2H6O",
            caption = "ethanol",
            spoken = "ethanol"
        )
    )

    private fun reactionStructure() = listOf(
        ContentElement.TextElement("Reaction: "),
        // Empty reagents and products are allowed by ReactionParser; the
        // short caption is the reagent text above the arrow and `formula`
        // becomes the product label in the inline row.
        ContentElement.ChemElement(smiles = "CCO>>", caption = "reagent", formula = "A")
    )

    @Test
    fun chemistryFitsA320DpScreenAndActuallyRendersTheSvg() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
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
        var lastState = "not evaluated"
        try {
            // The CI emulator's WebView starts slowly: the drawing can be
            // ready just after a 10 s wait, so allow a full 30 s here.
            compose.waitUntil(timeoutMillis = 30_000) {
                lastState = svgState()
                lastState.startsWith("ok:")
            }
        } catch (t: Throwable) {
            throw AssertionError("SmilesDrawer did not draw a visible SVG; last state=$lastState", t)
        }
        val parts = lastState.split(':')
        val heightPx = parts.getOrNull(2)?.toIntOrNull()
            ?: throw AssertionError("unexpected SVG state: $lastState")
        val widthPx = parts.getOrNull(3)?.toIntOrNull()
            ?: throw AssertionError("unexpected SVG state: $lastState")
        assertTrue("structure has no height: $lastState", heightPx > 0)
        // Owner chose the compact 96 px cap for standalone structures.
        assertTrue("structure too tall ($heightPx px): $lastState", heightPx <= 100)
        assertTrue("structure too wide ($widthPx px): $lastState", widthPx <= 100)

        var lastColor = "not evaluated"
        try {
            compose.waitUntil(timeoutMillis = 30_000) {
                lastColor = structureColorState()
                lastColor.startsWith("ok:")
            }
        } catch (t: Throwable) {
            throw AssertionError(
                "drawn structure colour does not match dark-theme text; last state=$lastColor", t
            )
        }
    }

    @Test
    fun aDrawnStructureIsRasterisedAndReportedWithItsKey() {
        val captured = AtomicReference<Pair<String, String>?>(null)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.width(320.dp).height(480.dp)) {
                    ContentElements(
                        structure(),
                        chemKey = "chem-42",
                        onChemDiagram = { key, png -> captured.set(key to png) }
                    )
                }
            }
        }
        // Same slow CI WebView as the drawing test: the PNG callback can
        // arrive after a 10 s wait even though the draw itself succeeded.
        compose.waitUntil(timeoutMillis = 30_000) { captured.get() != null }
        val (key, png) = captured.get()!!
        assertEquals("chem-42", key)
        assertTrue("not a PNG data URI: ${png.take(40)}", png.startsWith("data:image/png;base64,"))
        assertTrue("empty PNG", png.length > 100)
    }

    @Test
    fun reactionFitsA320DpScreenDrawsArrowAndReagentAndCaptures() {
        val captured = AtomicReference<Pair<String, String>?>(null)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.width(320.dp).height(480.dp)) {
                    ContentElements(
                        reactionStructure(),
                        chemKey = "chem-rxn",
                        onChemDiagram = { key, png -> captured.set(key to png) }
                    )
                }
            }
        }
        val node = compose.onNodeWithContentDescription("reagent", substring = true)
        node.assertIsDisplayed()
        val bounds = node.getBoundsInRoot()
        val widthDp = bounds.right.value - bounds.left.value
        assertTrue("reaction overflows 320dp: $bounds", widthDp <= 320f)

        // The bundled ReactionDrawer must put real arrow geometry and the
        // reagent label in the SVG, not just a spoken description.
        var lastState = "not evaluated"
        try {
            compose.waitUntil(timeoutMillis = 30_000) {
                lastState = reactionSvgState()
                lastState.startsWith("ok:")
            }
        } catch (t: Throwable) {
            // Carry the probe's own failure through to CI: without it a
            // timed-out evaluateJavascript looks like a drawing failure.
            throw AssertionError(
                "ReactionDrawer did not draw an arrow and reagent; " +
                    "last state=$lastState; error=${t.message}", t
            )
        }
        val parts = lastState.split(':')
        val heightPx = parts.getOrNull(2)?.toIntOrNull()
            ?: throw AssertionError("unexpected reaction SVG state: $lastState")
        val widthPx = parts.getOrNull(3)?.toIntOrNull()
            ?: throw AssertionError("unexpected reaction SVG state: $lastState")
        assertTrue("reaction has no height: $lastState", heightPx > 0)
        // The inline flex row keeps the compact scheme, arrow, reagent and
        // product label on ONE line. With no artificial max-height the real
        // height of that row — including the reagent caption — is ~99 px at
        // 320 dp, so the bound leaves a little headroom while still rejecting
        // stacked/multi-line content, which would be much taller. The nowrap,
        // width, arrow / reagent / product-label and capture checks complete
        // the layout guard.
        assertTrue("reaction too tall ($heightPx px): $lastState", heightPx <= 110)
        assertTrue("reaction overflows 320dp ($widthPx px): $lastState", widthPx <= 320)

        // The light-theme capture bridge must still fire for a reaction.
        compose.waitUntil(timeoutMillis = 30_000) { captured.get() != null }
        val (key, png) = captured.get()!!
        assertEquals("chem-rxn", key)
        assertTrue("not a PNG data URI: ${png.take(40)}", png.startsWith("data:image/png;base64,"))
        assertTrue("empty PNG", png.length > 100)
    }
}
