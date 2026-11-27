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
     * The reaction is an inline flex row: scheme + arrow + reagent
     * caption + product label on ONE line. Probe the whole container's box (so
     * a stacked/multi-line layout fails), plus the arrow, reagent text and the
     * product label. The caption uses an ideographic baseline, so comparing
     * tspan and line boxes is not a reliable overlap probe across emulators —
     * a real (uncapped) container height plus an explicit nowrap check are.
     *
     * The same probe guards the two rendering regressions that only a
     * computed-style read can see: the live labels and caption must keep their
     * own small font (a document-global style from the hidden capture would
     * inflate them), and the hidden capture — the source of the exported PNG —
     * must have a finite positive viewBox and a real arrow, which
     * ReactionDrawer cannot compute when its raw molecule options omit
     * `bondLength`/`fontSizeLarge`. Its arrow must also keep the drawer's
     * default 1 px stroke: the capture is drawn at the 1x capture scale, so
     * sharing the live scheme's 2.2x chrome would thicken the exported arrow.
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
            "if(getComputedStyle(c).justifyContent!=='center')return 'not-centered';" +
            "var b=c.getBoundingClientRect();" +
            "if(!(b.height>0))return 'zero-height';" +
            "if(!s.querySelector('line[marker-end]'))return 'no-arrow';" +
            "if(s.textContent.indexOf('reagent')<0)return 'no-reagent-text';" +
            "var p=c.querySelector('.mcq-chem-product');" +
            "if(!p)return 'no-product-label';" +
            "if(p.textContent.trim()!=='A')return 'wrong-product-label';" +
            // The live scheme must keep its OWN proportions: labels come from
            // its scoped 8.1 pt `.element` rule (10.8 px, above Android's 8 px
            // minimum font size); the hidden capture's 11 pt rule (14.6667 px)
            // wins if scoping regresses.
            "var at=s.querySelector('text.element');" +
            "if(!at)return 'no-atom-label';" +
            "var af=parseFloat(getComputedStyle(at).fontSize);" +
            "if(!(Math.abs(af-10.8)<=0.2))return 'label-font:'+af;" +
            // The reagent caption uses `.text` at 0.8 * 8.1 pt (8.64 px); an
            // unscoped capture rule makes it 0.8 * 11 pt (11.73 px).
            "var ct=s.querySelector('text.text');" +
            "if(!ct)return 'no-caption-node';" +
            "var cf=parseFloat(getComputedStyle(ct).fontSize);" +
            "if(!(Math.abs(cf-8.64)<=0.2))return 'caption-font:'+cf;" +
            // The hidden light-theme capture feeds the exported PNG.
            // ReactionDrawer's arrow/caption geometry reads the RAW molecule
            // options, so a capture without bondLength/fontSizeLarge used to
            // produce viewBox="0 0 NaN <finite height>" (width NaN, height
            // still ~95.5) and x2="NaN": the PNG lost the arrow and the
            // product side.
            "var cap=document.getElementById('mcq-chem-0-capture');" +
            "if(!cap)return 'no-capture';" +
            "var cvb=cap.viewBox.baseVal;" +
            "var cw=cvb?cvb.width:NaN,ch=cvb?cvb.height:NaN;" +
            "if(!isFinite(cw)||!isFinite(ch)||!(cw>0)||!(ch>0))" +
            "return 'bad-capture:'+cw+'x'+ch;" +
            "var ca=cap.querySelector('line[marker-end]');" +
            "if(!ca)return 'bad-capture:no-arrow';" +
            "if(!(ca.x2.baseVal.value>0)||!isFinite(ca.y2.baseVal.value))" +
            "return 'bad-capture:arrow:'+ca.getAttribute('x2')+':'+ca.getAttribute('y2');" +
            // The capture is drawn at the 1x capture scale with
            // ReactionDrawer's DEFAULT chrome, whose arrow thickness is 1 user
            // unit = 1 px. Sharing the live 2.2-unit chrome would make it
            // 2.2 px here.
            "var cas=parseFloat(getComputedStyle(ca).strokeWidth);" +
            "if(!(Math.abs(cas-1)<=0.01))return 'bad-capture:arrow-stroke:'+cas;" +
            // The row is a full-width block: the visible children (skip the
            // hidden capture SVG and fallback, which have zero width) must be
            // centred, so the left and right gaps agree within 2 px.
            "var kids=c.children,minL=1e9,maxR=-1e9,seen=0;" +
            "for(var i=0;i<kids.length;i++){var k=kids[i].getBoundingClientRect();" +
            "if(!(k.width>0))continue;seen++;if(k.left<minL)minL=k.left;if(k.right>maxR)maxR=k.right;}" +
            "if(!seen)return 'no-visible-children';" +
            "if(Math.abs((minL-b.left)-(b.right-maxR))>2)return 'not-centered';" +
            "var n=s.querySelectorAll('line,text,tspan,circle,polygon').length;" +
            "if(!(n>0))return 'no-shapes';" +
            "return 'ok:'+n+':'+Math.round(b.height)+':'+Math.round(b.width);" +
            "})()"
    )

    /**
     * Each SVG carries its own `<style>` scoped to its own id. In production
     * every answer option is a separate WebView, so the leak that matters is
     * INSIDE one page: SmilesDrawer injects a document-global `<style>`, and a
     * reaction draws its hidden light-theme capture AFTER the live scheme at
     * 11 pt over 30-unit bonds, which would restyle the visible reaction
     * (labels 14.6667 px instead of 10.8 px). Any diagram drawn later in the
     * same WebView restyles everything before it — the reason the standalone
     * molecule is drawn last in this test.
     *
     * Compare the SAME glyph in both CCO drawings (the atom label containing
     * 'O'), so the computed font ratio is exactly 8.1/11 when scoped and 1.0
     * when the leak is back. Also compare the rendered label height as a
     * fraction of the longest bond, which must agree between the two.
     * Nothing is reported until both SVGs are fully drawn: the wait in the
     * test retries every state that is not `ok:`.
     */
    private fun labelProportionState(): String = evaluate(
        "(function(){" +
            "var rxn=document.querySelector('.mcq-chem-reaction svg[id^=\"mcq-chem-\"]');" +
            "var mol=document.querySelector('.mcq-chem:not(.mcq-chem-reaction) " +
            "svg[id^=\"mcq-chem-\"]');" +
            "if(!rxn)return 'no-reaction-svg';" +
            "if(!mol)return 'no-standalone-svg';" +
            // Both drawers must have finished: a finite positive viewBox, a
            // rendered width and a hidden fallback. The reaction's inline
            // width must also differ from its viewBox width: ReactionDrawer
            // sets the unscaled width, so only the scale pass (it SHRINKS the
            // style by 1/2.2) can move the style away from the viewBox.
            "function readiness(svg){" +
            "var vb=svg.viewBox.baseVal;" +
            "if(!vb||!isFinite(vb.width)||!isFinite(vb.height)||" +
            "!(vb.width>0)||!(vb.height>0))return 'viewBox';" +
            "if(!(svg.getBoundingClientRect().width>0))return 'width';" +
            "var fb=document.getElementById(svg.id+'-fallback');" +
            "if(!fb)return 'fallback';" +
            "if(getComputedStyle(fb).display!=='none')return 'fallback-shown';" +
            "return '';}" +
            "var r=readiness(rxn);if(r)return 'reaction-'+r;" +
            "if(!(Math.abs(parseFloat(rxn.style.width)-rxn.viewBox.baseVal.width)>0.5))" +
            "return 'reaction-unscaled';" +
            "r=readiness(mol);if(r)return 'standalone-'+r;" +
            // Same glyph set: both diagrams are CCO, so the label carrying
            // the oxygen exists in each one.
            "function oxygenLabel(svg){" +
            "var t=svg.querySelectorAll('text.element');" +
            "for(var i=0;i<t.length;i++){if(t[i].textContent.indexOf('O')>=0)return t[i];}" +
            "return null;}" +
            "var rxnText=oxygenLabel(rxn),molText=oxygenLabel(mol);" +
            "if(!rxnText)return 'no-reaction-label';" +
            "if(!molText)return 'no-standalone-label';" +
            "var rxnFont=parseFloat(getComputedStyle(rxnText).fontSize);" +
            "var molFont=parseFloat(getComputedStyle(molText).fontSize);" +
            "if(!(rxnFont>0)||!(molFont>0))return 'no-font';" +
            "var fontRatio=rxnFont/molFont;" +
            "if(Math.abs(fontRatio-8.1/11)>0.02)return 'font-leak:'+fontRatio.toFixed(3);" +
            // Bond length excludes the arrow (line[marker-end]); nested
            // reactant SVGs are covered because the selector reaches inside.
            "function proportion(svg,t){" +
            "var lines=svg.querySelectorAll('line:not([marker-end])'),max=0;" +
            "for(var i=0;i<lines.length;i++){var q=lines[i].getBoundingClientRect();" +
            "var d=Math.hypot(q.width,q.height);if(d>max)max=d;}" +
            "if(!(max>0))return -1;" +
            "return t.getBoundingClientRect().height/max;}" +
            "var rxnRatio=proportion(rxn,rxnText);" +
            "var molRatio=proportion(mol,molText);" +
            "if(!(rxnRatio>0))return 'no-reaction-bonds';" +
            "if(!(molRatio>0))return 'no-standalone-bonds';" +
            "if(Math.abs(rxnRatio-molRatio)>0.10)" +
            "return 'proportion-mismatch:'+rxnRatio.toFixed(3)+':'+molRatio.toFixed(3);" +
            "return 'ok:'+fontRatio.toFixed(3)+':'+rxnRatio.toFixed(3)+':'+molRatio.toFixed(3);" +
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

    /**
     * A standalone ring and a standalone chain on the same page, measured from
     * the LIVE drawings only: the median rendered bond length (`line` boxes,
     * `hypot(width,height)`, zero-length lines ignored) and the chain SVG's
     * rendered width. Every SVG must be fully drawn first: a rendered width,
     * real shapes and a hidden fallback. A positive viewBox proves nothing —
     * the drawer sets its own square viewBox before the fit runs — so the
     * `data-fitted='1'` marker, which `fitStandalone` sets only after the crop
     * and sizing, is what proves the crop actually happened.
     */
    private fun bondScaleState(): String = evaluate(
        "(function(){" +
            "var ring=document.getElementById('mcq-chem-0');" +
            "var chain=document.getElementById('mcq-chem-1');" +
            "if(!ring)return 'no-ring-svg';" +
            "if(!chain)return 'no-chain-svg';" +
            "function readiness(svg){" +
            "var vb=svg.viewBox.baseVal;" +
            "if(!vb||!isFinite(vb.width)||!isFinite(vb.height)||" +
            "!(vb.width>0)||!(vb.height>0))return 'viewBox';" +
            "if(svg.getAttribute('data-fitted')!=='1')return 'unfitted';" +
            "if(!(svg.getBoundingClientRect().width>0))return 'width';" +
            "var fb=document.getElementById(svg.id+'-fallback');" +
            "if(!fb)return 'fallback';" +
            "if(getComputedStyle(fb).display!=='none')return 'fallback-shown';" +
            "if(getComputedStyle(svg).display==='none')return 'svg-hidden';" +
            "var n=svg.querySelectorAll('line,text,circle,polygon').length;" +
            "if(!(n>0))return 'no-shapes';" +
            "return '';}" +
            "var r=readiness(ring);if(r)return 'ring-'+r;" +
            "r=readiness(chain);if(r)return 'chain-'+r;" +
            "function medianBond(svg){" +
            "var lines=svg.querySelectorAll('line'),d=[];" +
            "for(var i=0;i<lines.length;i++){var q=lines[i].getBoundingClientRect();" +
            "var v=Math.hypot(q.width,q.height);if(v>0)d.push(v);}" +
            "if(!d.length)return -1;" +
            "d.sort(function(a,b){return a-b;});var m=d.length>>1;" +
            "return d.length%2?d[m]:(d[m-1]+d[m])/2;}" +
            "var rb=medianBond(ring),cb=medianBond(chain);" +
            "if(!(rb>0))return 'ring-no-bonds';" +
            "if(!(cb>0))return 'chain-no-bonds';" +
            "var cw=chain.getBoundingClientRect().width;" +
            "var pw=document.documentElement.clientWidth;" +
            "return 'ok:'+rb.toFixed(2)+':'+cb.toFixed(2)+':'+(cb/rb).toFixed(3)+':'+" +
            "Math.round(cw)+':'+Math.round(pw);" +
            "})()"
    )

    /**
     * Owner report: an inline molecular formula (e.g. C₇H₁₄O₃ in an option
     * label) had the bottoms of its subscripts cut off. On Android WebView an
     * inline MathJax SVG can leave its descent outside the text line box, so
     * the page's old body-rect-only measurement was a couple of px short and
     * the pinned WebView clipped the glyphs. Desktop Chromium keeps the SVG
     * inside the line box, so the exact device clip cannot be reproduced on the
     * CI renderer.
     *
     * This is the old-vs-new control instead of a probe that recomputes the
     * same union (which could not fail on the old body-rect code). Once the
     * formula is typeset and Compose has pinned the view, a RELATIVELY
     * POSITIONED box is injected (`position:relative;top:200px;height:1px`).
     * Relative positioning keeps the box in the normal flow, so the page's
     * `contentHeight` union still counts it — that filter drops only
     * absolute/fixed/invisible boxes — and the union follows its visual box
     * 200 px lower. Its layout box is unchanged, so the body's own border box
     * only gains the 1 px height, and the old body-rect measurement moves by
     * at most that 1 px. The pinned `window.innerHeight` must therefore reach
     * base + 150; the measured growth is ~197 px (200 offset + 1 height - the
     * body's 0.25em bottom allowance). The offset reads as growth only while
     * the viewport is pinned to the content, which is this WebView's steady
     * state — Chromium's quirks-mode body can instead fill a taller viewport.
     */
    private fun inlineFormulaPinnedState(): String = evaluate(
        "(function(){" +
            "var s=document.querySelector('mjx-container svg');" +
            "if(!s)return 'not-typeset';" +
            "var sh=Math.ceil(s.getBoundingClientRect().height);" +
            "if(!(sh>0))return 'zero-formula';" +
            // Until the height bridge reports, Compose pins the WebView to
            // 1 dp, so window.innerHeight starts tiny and this wait retries.
            // The shipped body-rect bridge measures the body's own rect, which
            // proves the body is content-based; only then does a viewport at
            // least as tall as the typeset formula prove the settled
            // report-and-pin cycle has landed.
            "var h=window.innerHeight;" +
            "if(!(h>=sh))return 'unpinned:'+h+':'+sh;" +
            "return 'ok:'+h;" +
            "})()"
    )

    /**
     * Append the relative-offset control and return the pinned height it
     * started from. `position:relative` keeps the box in the normal flow, so
     * the descendant union counts its visual box 200 px lower; the body's own
     * border box only gains the box's 1 px height, which the old body-rect
     * measurement cannot turn into the required growth.
     */
    private fun injectRelativeOffset(): Int {
        val base = evaluate(
            "(function(){" +
                "var base=window.innerHeight;" +
                "var d=document.createElement('div');" +
                "d.id='mcq-offset-probe';" +
                "d.style.cssText='position:relative;top:200px;height:1px;';" +
                "document.body.appendChild(d);" +
                "window.dispatchEvent(new Event('resize'));" +
                "return String(base);" +
                "})()"
        )
        return base.toIntOrNull() ?: error("unexpected pinned height: $base")
    }

    /**
     * Growth the relative offset must produce. The threshold is well below the
     * measured ~197 px (200 offset + 1 height - the body's 0.25em bottom
     * allowance), so viewport and dp rounding leave ample slack, while the old
     * body-rect measurement moves by at most 1 px and cannot reach it.
     */
    private fun offsetGrowthState(base: Int): String = evaluate(
        "(function(){" +
            "var h=window.innerHeight;" +
            "var need=150;" +
            "if(h>=$base+need)return 'ok:'+h+':'+$base;" +
            "return 'growth:'+h+':'+$base+':'+need;" +
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

    /**
     * Reaction FIRST, standalone molecule SECOND. In production an answer
     * option gets its own WebView, so the trigger for this ordering is not the
     * option's style leaking into a question: it is the reaction's own hidden
     * capture (drawn after the live scheme at the 11 pt / 30-unit defaults) and
     * any diagram drawn later in the same WebView, which both inject a
     * document-global style. Without per-SVG scoping the LAST rules on the page
     * win for every drawing.
     */
    private fun reactionThenStandalone() = listOf(
        ContentElement.TextElement("Reaction: "),
        ContentElement.ChemElement(smiles = "CCO>>", caption = "reagent", formula = "A"),
        ContentElement.TextElement(" Structure: "),
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
    fun inlineFormulaHeightFollowsAnInFlowOverflowTheBodyRectMisses() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.width(320.dp).height(480.dp)) {
                    ContentElements(listOf(
                        ContentElement.TextElement("Benzene, "),
                        ContentElement.MathElement(
                            "<math><msub><mtext>C</mtext><mn>6</mn></msub>" +
                                "<msub><mtext>H</mtext><mn>6</mn></msub></math>"
                        )
                    ))
                }
            }
        }
        var lastState = "not evaluated"
        var base = -1
        try {
            compose.waitUntil(timeoutMillis = 30_000) {
                lastState = inlineFormulaPinnedState()
                lastState.startsWith("ok:")
            }
            base = lastState.removePrefix("ok:").toInt()
            lastState = "injected at ${injectRelativeOffset()}"
        } catch (t: Throwable) {
            // Carry the probe's own state through to CI: an un-typeset formula,
            // an unpinned WebView and a failed injection look identical from a
            // bare timeout.
            throw AssertionError(
                "inline formula never typeset and pinned the WebView, or the offset " +
                    "control could not be injected; last state=$lastState; error=${t.message}", t
            )
        }
        var lastGrowth = "not evaluated"
        try {
            compose.waitUntil(timeoutMillis = 30_000) {
                lastGrowth = offsetGrowthState(base)
                lastGrowth.startsWith("ok:")
            }
        } catch (t: Throwable) {
            // The old body-rect measurement lands here: the body border box
            // gains at most 1 px, so it never reports a second height 150 px
            // larger and window.innerHeight stays near base forever.
            throw AssertionError(
                "the reported height did not follow the relatively positioned probe " +
                    "200 px below the body (base=$base px; last state=$lastGrowth; " +
                    "error=${t.message})", t
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
        // The inline flex row keeps the scheme, arrow, reagent and
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

    @Test
    fun reactionLabelsMatchStandaloneProportions() {
        // Owner rule: a reaction letter keeps the same proportion to its bonds
        // as an option structure. The production triggers for the style leak
        // are the reaction's OWN hidden capture, drawn right after the live
        // scheme at the 11 pt / 30-unit defaults, and any diagram drawn later
        // in the same WebView — hence the standalone molecule SECOND here:
        // its 11 pt <style> is the last on the page and would override the
        // reaction's labels without per-SVG scoping.
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.width(320.dp).height(480.dp)) {
                    ContentElements(reactionThenStandalone())
                }
            }
        }
        var lastState = "not evaluated"
        try {
            compose.waitUntil(timeoutMillis = 30_000) {
                lastState = labelProportionState()
                lastState.startsWith("ok:")
            }
        } catch (t: Throwable) {
            // The probe's own state tells CI whether it was a font leak, a
            // proportion mismatch or a WebView that never settled.
            throw AssertionError(
                "reaction labels do not match standalone proportions; " +
                    "last state=$lastState; error=${t.message}", t
            )
        }
    }

    /**
     * A structure carried by a table cell must draw at the same scale as a
     * standalone structure, inside its own row, without widening the page.
     * The marker is the storage grammar a cell actually holds, so this goes
     * through the real mixed renderer rather than a hand-built element.
     */
    private fun chemCellTable() = listOf(
        ContentElement.TableElement(
            listOf(
                listOf("Compound", "Structure"),
                listOf(
                    "Ethanol",
                    "<span class=\"mcq-chem-cell\" data-smiles=\"CCO\" " +
                        "data-formula=\"C2H6O\" data-caption=\"ethanol\" data-spoken=\"ethanol\">" +
                        "ethanol C2H6O CCO</span>"
                )
            )
        )
    )

    /**
     * Three columns and a 36-carbon chain: the narrowest useful real case. A
     * fixed-layout chem table must clamp the wide drawing to its own column,
     * so the page never gains a horizontal scrollbar and the SVG never
     * overflows its `<td>`.
     */
    private fun wideChemCellTable(): List<ContentElement> {
        val chain = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"
        return listOf(
            ContentElement.TableElement(
                listOf(
                    listOf("Name", "Formula", "Structure"),
                    listOf(
                        "Hexatriacontane",
                        "C36H74",
                        "<span class=\"mcq-chem-cell\" data-smiles=\"$chain\" " +
                            "data-formula=\"C36H74\" data-caption=\"hexatriacontane\" " +
                            "data-spoken=\"hexatriacontane\">" +
                            "hexatriacontane C36H74 $chain</span>"
                    )
                )
            )
        )
    }

    /**
     * A reaction carried by a table cell in the narrowest real case: two
     * columns on a 320 dp screen. The chem table is `table-layout:fixed`, so
     * the cell's row must wrap (a nowrapped scheme + label would overflow its
     * column) while the scheme, arrow, reagent caption and product label still
     * draw. The `>` of the reaction SMILES is entity-escaped exactly as
     * [com.mcqapp.util.chemCellHtml] writes it: a raw `>` would end the
     * marker's attribute region.
     */
    private fun reactionCellTable() = listOf(
        ContentElement.TableElement(
            listOf(
                listOf("Reaction", "Structure"),
                listOf(
                    "Oxidation of ethanol",
                    "<span class=\"mcq-chem-cell\" data-smiles=\"CCO&gt;&gt;CC=O\" " +
                        "data-formula=\"CC=O\" data-caption=\"oxidation\" " +
                        "data-spoken=\"oxidation of ethanol to ethanal\">" +
                        "oxidation of ethanol to ethanal</span>"
                )
            )
        )
    )

    /**
     * A cell structure is ready when its SVG is fitted, visible, has real
     * shapes and sits inside its `<td>`; the page must not gain a horizontal
     * scrollbar. The cell wrapper must be the block the page CSS gives it
     * (`.mcq-chem-cell{display:block}`), so the drawing never joins the prose
     * line, and the SVG's box must stay inside the cell's box.
     *
     * Every cell of the fixed-layout table must also contain its own content:
     * a label wider than its column used to paint over the next column while
     * the `<td>` itself kept its width, so each cell's scroll width and the
     * right edge of every painted text box and descendant element are checked
     * too. The failing cell's index and rect travel back in the state.
     */
    private fun chemCellState(id: String = "mcq-chem-0"): String = evaluate(
        "(function(){" +
            "var s=document.getElementById('$id');" +
            "var f=document.getElementById('$id-fallback');" +
            "if(!s)return 'no-svg';" +
            "if(!f)return 'no-fallback';" +
            "if(getComputedStyle(s).display==='none')return 'svg-hidden';" +
            "if(getComputedStyle(f).display!=='none')return 'fallback-shown';" +
            "var vb=s.viewBox.baseVal;" +
            "if(!vb||!isFinite(vb.width)||!isFinite(vb.height)||" +
            "!(vb.width>0)||!(vb.height>0))return 'bad-viewBox';" +
            "if(s.getAttribute('data-fitted')!=='1')return 'unfitted';" +
            "var b=s.getBoundingClientRect();" +
            "if(!(b.width>0)||!(b.height>0))return 'zero-size';" +
            "var cell=s.parentElement;" +
            "if(!cell||cell.className.indexOf('mcq-chem-cell')<0)return 'no-cell';" +
            "if(getComputedStyle(cell).display!=='block')" +
            "return 'cell-display:'+getComputedStyle(cell).display;" +
            "var td=s.closest('td');" +
            "if(!td)return 'no-td';" +
            "var t=td.getBoundingClientRect();" +
            "if(!(t.height+0.5>=b.height)||!(t.bottom+0.5>=b.bottom))" +
            "return 'row-clips:'+t.height+':'+b.height;" +
            "if(!(b.width<=t.width+0.5)||!(b.right<=t.right+0.5))" +
            "return 'svg-wider-than-td:'+b.width+':'+t.width;" +
            // A fixed-layout cell can be narrower than one unbreakable word.
            // The cell's own scroll width catches the overflow, and the
            // painted boxes catch a text run that overflows without widening
            // the cell: zero-width boxes (hidden captures, fallbacks) are
            // skipped, so only what actually paints is measured.
            "var cells=document.querySelectorAll('table.mcq-chem-table td');" +
            "for(var ci=0;ci<cells.length;ci++){" +
            "var c=cells[ci],cr=c.getBoundingClientRect();" +
            "var where=ci+':'+Math.round(cr.left)+','+Math.round(cr.right);" +
            "if(c.scrollWidth>c.clientWidth)" +
            "return 'td-overflow:'+where+':'+c.scrollWidth+':'+c.clientWidth;" +
            "var els=c.querySelectorAll('*');" +
            "for(var ei=0;ei<els.length;ei++){" +
            "var er=els[ei].getBoundingClientRect();" +
            "if(!(er.width>0))continue;" +
            "if(er.right>cr.right+0.5)" +
            "return 'child-overflow:'+where+':'+els[ei].tagName+':'+Math.round(er.right);}" +
            "var tw=document.createTreeWalker(c,4,null,false),tn;" +
            "while((tn=tw.nextNode())){" +
            "if(!tn.textContent||!tn.textContent.replace(/\\s+/g,''))continue;" +
            "var rg=document.createRange();rg.selectNodeContents(tn);" +
            "var tr=rg.getBoundingClientRect();" +
            "if(!(tr.width>0))continue;" +
            "if(tr.right>cr.right+0.5)" +
            "return 'text-overflow:'+where+':'+Math.round(tr.right)+':'+" +
            "tn.textContent.replace(/\\s+/g,' ').slice(0,20);}}" +
            "var de=document.documentElement;" +
            "if(de.scrollWidth>de.clientWidth+1)return 'page-scroll:'+de.scrollWidth+':'+de.clientWidth;" +
            "var n=s.querySelectorAll('line,text,circle,polygon').length;" +
            "if(!(n>0))return 'no-shapes';" +
            "return 'ok:'+n+':'+Math.round(b.width)+':'+Math.round(b.height)+':'+" +
            "Math.round(vb.width)+':'+Math.round(vb.height)+':'+de.clientWidth;" +
            "})()"
    )

    private fun assertChemCellState(lastState: String) {
        val parts = lastState.split(':')
        val shapes = parts.getOrNull(1)?.toIntOrNull()
            ?: throw AssertionError("unexpected chem-cell state: $lastState")
        val widthPx = parts.getOrNull(2)?.toIntOrNull()
            ?: throw AssertionError("unexpected chem-cell state: $lastState")
        val heightPx = parts.getOrNull(3)?.toIntOrNull()
            ?: throw AssertionError("unexpected chem-cell state: $lastState")
        val viewBoxWidth = parts.getOrNull(4)?.toIntOrNull()
            ?: throw AssertionError("unexpected chem-cell state: $lastState")
        val viewBoxHeight = parts.getOrNull(5)?.toIntOrNull()
            ?: throw AssertionError("unexpected chem-cell state: $lastState")
        val pageWidth = parts.getOrNull(6)?.toIntOrNull()
            ?: throw AssertionError("unexpected chem-cell state: $lastState")

        assertTrue("no drawn geometry: $lastState", shapes > 0)
        assertTrue("cell structure has no width: $lastState", widthPx > 0)
        assertTrue("cell structure has no height: $lastState", heightPx > 0)
        assertTrue("cell viewBox is not positive: $lastState", viewBoxWidth > 0 && viewBoxHeight > 0)
        assertTrue(
            "cell structure overflows the page ($widthPx > $pageWidth): $lastState",
            widthPx <= pageWidth
        )
    }

    private fun awaitChemCellState(id: String = "mcq-chem-0"): String {
        var lastState = "not evaluated"
        try {
            compose.waitUntil(timeoutMillis = 30_000) {
                lastState = chemCellState(id)
                lastState.startsWith("ok:")
            }
        } catch (t: Throwable) {
            // The probe's own state says what failed: a WebView that never
            // settled, a failed draw, a clipped row or a page widened by the
            // structure.
            throw AssertionError(
                "a chem cell did not draw inside its own row; " +
                    "last state=$lastState; error=${t.message}", t
            )
        }
        return lastState
    }

    /**
     * A reaction cell is ready when the scheme, arrow, reagent caption and
     * product label are visible, the wrapper uses the WRAPPING row (normal
     * white space, `flex-wrap:wrap`), every box stays inside its `<td>`, and
     * the page has no horizontal scroll. The old non-wrapping row fails the
     * `whiteSpace`/`flexWrap` checks; with `table-layout:fixed` it also let a
     * long label push the row past the column.
     */
    private fun reactionCellState(): String = evaluate(
        "(function(){" +
            "var c=document.querySelector('.mcq-chem-reaction.mcq-chem-cell');" +
            "var s=document.getElementById('mcq-chem-0');" +
            "var f=document.getElementById('mcq-chem-0-fallback');" +
            "if(!c)return 'no-container';" +
            "if(!s)return 'no-svg';" +
            "if(!f)return 'no-fallback';" +
            "var display=getComputedStyle(c).display;" +
            "if(display!=='flex')return 'display:'+display;" +
            "var ws=getComputedStyle(c).whiteSpace;" +
            "if(ws!=='normal')return 'white-space:'+ws;" +
            "var fw=getComputedStyle(c).flexWrap;" +
            "if(fw!=='wrap')return 'flex-wrap:'+fw;" +
            "if(getComputedStyle(s).display==='none')return 'svg-hidden';" +
            "if(getComputedStyle(f).display!=='none')return 'fallback-shown';" +
            "if(!s.querySelector('line[marker-end]'))return 'no-arrow';" +
            "if(s.textContent.indexOf('oxidation')<0)return 'no-reagent-text';" +
            "var p=c.querySelector('.mcq-chem-product');" +
            "if(!p||p.textContent.trim()!=='CC=O')return 'no-product-label';" +
            "var b=s.getBoundingClientRect();" +
            "if(!(b.width>0)||!(b.height>0))return 'zero-size';" +
            "var cb=c.getBoundingClientRect();" +
            "var td=s.closest('td');" +
            "if(!td)return 'no-td';" +
            "var t=td.getBoundingClientRect();" +
            "if(!(b.width<=t.width+0.5)||!(b.right<=t.right+0.5))" +
            "return 'svg-wider-than-td:'+Math.round(b.width)+':'+Math.round(t.width);" +
            "if(!(cb.width<=t.width+0.5)||!(cb.right<=t.right+0.5))" +
            "return 'row-wider-than-td:'+Math.round(cb.width)+':'+Math.round(t.width);" +
            "if(!(t.height+0.5>=cb.height)||!(t.bottom+0.5>=cb.bottom))" +
            "return 'row-clips:'+Math.round(t.height)+':'+Math.round(cb.height);" +
            "var de=document.documentElement;" +
            "if(de.scrollWidth>de.clientWidth+1)return 'page-scroll:'+de.scrollWidth+':'+de.clientWidth;" +
            "return 'ok:'+Math.round(cb.width)+':'+Math.round(cb.height)+':'+" +
            "Math.round(t.width)+':'+de.clientWidth;" +
            "})()"
    )

    @Test
    fun chemCellInATwoColumnTableFits320DpAndDrawsInsideItsRow() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.width(320.dp).height(480.dp)) {
                    ContentElements(chemCellTable())
                }
            }
        }
        val node = compose.onNodeWithContentDescription("ethanol", substring = true)
        node.assertIsDisplayed()
        val bounds = node.getBoundsInRoot()
        assertTrue(
            "table overflows 320dp: $bounds",
            bounds.right.value - bounds.left.value <= 320f
        )
        assertChemCellState(awaitChemCellState())
    }

    @Test
    fun aWideChainInAThreeColumnTableFits320DpWithoutHorizontalOverflow() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.width(320.dp).height(480.dp)) {
                    ContentElements(wideChemCellTable())
                }
            }
        }
        val node = compose.onNodeWithContentDescription("hexatriacontane", substring = true)
        node.assertIsDisplayed()
        // The probe returns only when the page has no horizontal scroll, the
        // SVG fits its <td>, and its fitted geometry is real.
        assertChemCellState(awaitChemCellState())
        val pageState = evaluate(
            "(function(){" +
                "var de=document.documentElement;" +
                "return 'page:'+de.scrollWidth+':'+de.clientWidth;" +
                "})()"
        )
        val parts = pageState.removePrefix("page:").split(':')
        val scrollWidth = parts.getOrNull(0)?.toIntOrNull()
            ?: throw AssertionError("unexpected page state: $pageState")
        val clientWidth = parts.getOrNull(1)?.toIntOrNull()
            ?: throw AssertionError("unexpected page state: $pageState")
        assertTrue(
            "wide chain widened the page ($scrollWidth > $clientWidth): $pageState",
            scrollWidth <= clientWidth
        )
    }

    @Test
    fun aReactionInATwoColumnTableWrapsInsideItsCellOn320Dp() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.width(320.dp).height(480.dp)) {
                    ContentElements(reactionCellTable())
                }
            }
        }
        val node = compose.onNodeWithContentDescription("oxidation", substring = true)
        node.assertIsDisplayed()
        val bounds = node.getBoundsInRoot()
        assertTrue(
            "table overflows 320dp: $bounds",
            bounds.right.value - bounds.left.value <= 320f
        )
        // The probe only returns "ok" when the wrapper wraps, the scheme and
        // product label are visible, and every box stays inside its <td>.
        var lastState = "not evaluated"
        try {
            compose.waitUntil(timeoutMillis = 30_000) {
                lastState = reactionCellState()
                lastState.startsWith("ok:")
            }
        } catch (t: Throwable) {
            throw AssertionError(
                "a reaction cell did not wrap inside its column; " +
                    "last state=$lastState; error=${t.message}", t
            )
        }
        val pageState = evaluate(
            "(function(){" +
                "var de=document.documentElement;" +
                "return 'page:'+de.scrollWidth+':'+de.clientWidth;" +
                "})()"
        )
        val parts = pageState.removePrefix("page:").split(':')
        val scrollWidth = parts.getOrNull(0)?.toIntOrNull()
            ?: throw AssertionError("unexpected page state: $pageState")
        val clientWidth = parts.getOrNull(1)?.toIntOrNull()
            ?: throw AssertionError("unexpected page state: $pageState")
        assertTrue(
            "reaction cell widened the page ($scrollWidth > $clientWidth): $pageState",
            scrollWidth <= clientWidth
        )
    }

    @Test
    fun linearAndRingStructuresShareABondScale() {
        // Owner report: rings looked properly sized while linear structures
        // looked smaller. The cause was the square viewBox: the old 96 px cap
        // measured an empty 254×254 box, so a chain's ~245×78 drawing got
        // ~11 px bonds while a ring's ~131×123 got ~20.7 px. The fit crops
        // the real bounds and floors the scale at the ring's, so both drawings
        // render the same bond length (~23.4 px) and the chain is no longer
        // pinned at 96 px. With the old behaviour the ratio is ~0.55 and the
        // chain width is exactly 96, so both assertions below fail on it.
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.width(320.dp).height(480.dp)) {
                    ContentElements(listOf(
                        ContentElement.ChemElement(smiles = "OC1CCOCCCC1O", formula = "C10H20O2"),
                        ContentElement.ChemElement(smiles = "OCCCCC(O)CCO", formula = "C8H18O3")
                    ))
                }
            }
        }
        var lastState = "not evaluated"
        try {
            compose.waitUntil(timeoutMillis = 30_000) {
                lastState = bondScaleState()
                lastState.startsWith("ok:")
            }
        } catch (t: Throwable) {
            // The probe's own state says whether a drawing never settled, lost
            // its shapes or showed its fallback — not just "timed out".
            throw AssertionError(
                "linear and ring structures do not share a bond scale; " +
                    "last state=$lastState; error=${t.message}", t
            )
        }
        val parts = lastState.split(':')
        val ringBond = parts.getOrNull(1)?.toDoubleOrNull()
            ?: throw AssertionError("unexpected bond-scale state: $lastState")
        val chainBond = parts.getOrNull(2)?.toDoubleOrNull()
            ?: throw AssertionError("unexpected bond-scale state: $lastState")
        val ratio = parts.getOrNull(3)?.toDoubleOrNull()
            ?: throw AssertionError("unexpected bond-scale state: $lastState")
        val chainWidthPx = parts.getOrNull(4)?.toIntOrNull()
            ?: throw AssertionError("unexpected bond-scale state: $lastState")
        val pageWidthPx = parts.getOrNull(5)?.toIntOrNull()
            ?: throw AssertionError("unexpected bond-scale state: $lastState")

        assertTrue(
            "chain bonds $chainBond px vs ring $ringBond px (ratio $ratio) " +
                "are not on the same scale: $lastState",
            ratio >= 0.75 && ratio <= 1.35
        )
        // The chain used to be clamped to the 96 px cap; it must now grow.
        assertTrue("chain svg is still capped at 96 px ($chainWidthPx px): $lastState",
            chainWidthPx > 100)
        assertTrue(
            "chain svg overflows the 320 dp page ($chainWidthPx > $pageWidthPx): $lastState",
            chainWidthPx <= pageWidthPx
        )
    }
}
