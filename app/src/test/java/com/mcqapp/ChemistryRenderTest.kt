package com.mcqapp

import com.mcqapp.data.export.isPngDataUri
import com.mcqapp.domain.ContentElement
import com.mcqapp.util.CHEM_DIAGRAM_BRIDGE
import com.mcqapp.util.CHEM_DISPLAY_MAX_PX
import com.mcqapp.util.CHEM_STANDALONE_MIN_SCALE
import com.mcqapp.util.CONTENT_HEIGHT_BRIDGE
import com.mcqapp.util.ContentRenderPath
import com.mcqapp.util.SMILES_DRAWER_ASSET_URL
import com.mcqapp.util.accessibleContentLabelFor
import com.mcqapp.util.accessibleContentText
import com.mcqapp.util.contentRenderPath
import com.mcqapp.util.jsStringLiteral
import com.mcqapp.util.mixedContentHtml
import com.mcqapp.util.smilesJsLiteral
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rendering and accessibility of chemical structures. The block is drawn by
 * the bundled SmilesDrawer asset inside the mixed WebView path (the same path
 * formulas use), so these tests cover the branch decision, the spoken text,
 * the generated HTML and the two injection boundaries: the JS string literal
 * and the optional base64 PNG.
 */
class ChemistryRenderTest {

    private val darkTextCss = "rgba(230,225,229,1)"

    private fun htmlOf(
        vararg elements: ContentElement,
        fallback: String = "Unavailable",
        chemKey: String = "",
        textColorCss: String = "rgba(0,0,0,1)"
    ): String = mixedContentHtml(elements.toList(), 48f, textColorCss, fallback, chemKey)

    // ---- path selection ----------------------------------------------------

    @Test
    fun aChemistryBlockForcesTheMixedPath() {
        assertEquals(ContentRenderPath.MIXED, contentRenderPath(listOf(
            ContentElement.ChemElement("CCO", formula = "C2H6O")
        )))
        assertEquals(ContentRenderPath.MIXED, contentRenderPath(listOf(
            ContentElement.TextElement("Identify: "),
            ContentElement.ChemElement("c1ccccc1", caption = "benzene")
        )))
        // A chemistry-only question must not be mistaken for the lone plain
        // text run that keeps the legacy TextView path.
        assertNotNull(accessibleContentLabelFor(listOf(ContentElement.ChemElement("CCO"))))
    }

    @Test
    fun chemistryIsNeverSentDownTheNativeBitmapPath() {
        // The native column is kept for text/image/table lists only; if a
        // ChemElement ever reached it there would be no way to draw the SMILES.
        val renderer = File("src/main/java/com/mcqapp/util/ContentElements.kt").readText()
        assertTrue("native path must reject chemistry", renderer.contains(
            "is ContentElement.ChemElement -> error(\"mixed view handles chemical structures\")"
        ))
        assertTrue("chem must select MIXED", renderer.contains(
            "it is ContentElement.MathElement || it is ContentElement.ChemElement"
        ))
    }

    @Test
    fun pathsWithoutChemistryAreUnchanged() {
        assertEquals(
            ContentRenderPath.PLAIN_TEXT,
            contentRenderPath(listOf(ContentElement.TextElement("What is 2+2?")))
        )
        assertEquals(
            ContentRenderPath.NATIVE,
            contentRenderPath(listOf(ContentElement.TextElement("a"), ContentElement.TextElement("b")))
        )
        assertEquals(
            ContentRenderPath.MIXED,
            contentRenderPath(listOf(ContentElement.MathElement("<math><mi>x</mi></math>")))
        )
    }

    // ---- accessibility -----------------------------------------------------

    @Test
    fun spokenTextWinsThenCaptionThenSmilesAndFormulaIsAppended() {
        assertEquals(
            "ethanol C2H6O",
            accessibleContentText(listOf(ContentElement.ChemElement(
                smiles = "CCO", formula = "C2H6O", caption = "ethyl alcohol", spoken = "ethanol"
            )))
        )
        assertEquals(
            "benzene C6H6",
            accessibleContentText(listOf(ContentElement.ChemElement(
                smiles = "c1ccccc1", formula = "C6H6", caption = "benzene"
            )))
        )
        assertEquals(
            "c1ccccc1",
            accessibleContentText(listOf(ContentElement.ChemElement("c1ccccc1")))
        )
    }

    @Test
    fun accessibleTextNeverLeaksSvgOrBase64() {
        val elements = listOf(
            ContentElement.TextElement("Name: "),
            ContentElement.ChemElement(
                smiles = "CCO",
                formula = "C2H6O",
                caption = "ethanol",
                diagram = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg=="
            )
        )
        val spoken = accessibleContentText(elements)
        assertEquals("Name: ethanol C2H6O", spoken)
        assertFalse("SVG markup leaked: $spoken", spoken.contains("<svg"))
        assertFalse("base64 leaked: $spoken", spoken.contains("base64"))
        assertFalse("data URI leaked: $spoken", spoken.contains("data:"))
        assertEquals(spoken, accessibleContentLabelFor(elements))
    }

    // ---- generated HTML ----------------------------------------------------

    @Test
    fun chemistryPageLoadsTheBundledDrawerAndDrawsIntoAnSvg() {
        val html = htmlOf(ContentElement.ChemElement("c1ccccc1", formula = "C6H6", caption = "Benzene"))
        assertTrue("offline script missing", html.contains(
            "<script src=\"$SMILES_DRAWER_ASSET_URL\"></script>"
        ))
        assertTrue("no remote script allowed", html.lines().none { it.contains("<script src=\"http") })
        assertTrue("svg container missing", html.contains("<svg id=\"mcq-chem-0\""))
        assertTrue("parse call missing", html.contains("SmilesDrawer.parse(\"c1ccccc1\""))
        assertTrue("SVG drawer missing", html.contains("SmilesDrawer.SvgDrawer"))
        assertTrue("drawer missing", html.contains("new window.SmilesDrawer.SvgDrawer("))
        assertTrue("hidden capture svg missing", html.contains(
            "<svg id=\"mcq-chem-0-capture\" style=\"display:none;\"></svg>"
        ))
        assertTrue("capture must use the built-in light theme",
            html.contains(".draw(tree,captureSvg,'light')"))
        assertFalse("live svg must not use light", html.contains(".draw(tree,svg,'light')"))
        assertTrue("custom theme draw missing", html.contains(".draw(tree,svg,'mcq')"))
        assertTrue("custom theme missing", html.contains(
            "themes:{mcq:{C:\"rgba(0,0,0,1)\",H:\"rgba(0,0,0,1)\"}}"
        ))
        assertTrue("caption/formula line missing",
            html.contains("<div class=\"mcq-chem-caption\">Benzene C6H6</div>"))
        assertTrue("localised fallback text missing", html.contains("Unavailable: C6H6"))
        // The page must still report its height to Compose.
        assertTrue(html.contains(CONTENT_HEIGHT_BRIDGE))
    }

    @Test
    fun chemThemeUsesTheBodyTextColourAndKeepsBuiltInThemes() {
        val html = htmlOf(ContentElement.ChemElement("CCO"), textColorCss = darkTextCss)
        assertTrue("theme colour not threaded from textColorCss", html.contains(
            "themes:{mcq:{C:\"$darkTextCss\",H:\"$darkTextCss\"}}"
        ))
        assertFalse("live svg must not use light", html.contains(".draw(tree,svg,'light')"))
        assertTrue("capture must not bake onSurface into the PNG",
            html.contains(".draw(tree,captureSvg,'light')"))
        assertFalse("capture must not use the live theme",
            html.contains(".draw(tree,captureSvg,'mcq')"))
        // The drawer deep-merges options into its defaults, so supplying a
        // single 'mcq' theme cannot drop the bundled light/dark themes.
        val js = File("src/main/assets/smiles-drawer/smiles-drawer.min.js").readText()
        assertTrue("built-in themes would be lost", js.contains("Options.extend(!0,this.defaultOptions,options)"))
    }

    @Test
    fun captureUsesASeparateHiddenSvgWithTheBuiltInLightTheme() {
        val html = htmlOf(ContentElement.ChemElement("CCO"), textColorCss = darkTextCss)
        assertTrue("no offscreen capture svg", html.contains(
            "<svg id=\"mcq-chem-0-capture\" style=\"display:none;\"></svg>"
        ))
        assertTrue("capture must not carry the live display cap",
            !html.contains("id=\"mcq-chem-0-capture\" style=\"width:"))
        // No themes override on the capture drawer: light's dark C/H and
        // conventional heteroatom colours are what white exports need.
        assertTrue("capture drawer must be theme-free", html.contains(
            "new window.SmilesDrawer.SvgDrawer({width:300,height:300,padding:2})" +
                ".draw(tree,captureSvg,'light');"
        ))
        assertTrue("live drawer lost its colour theme", html.contains(".draw(tree,svg,'mcq')"))
        assertTrue("live theme lost the text colour", html.contains(
            "themes:{mcq:{C:\"$darkTextCss\",H:\"$darkTextCss\"}}"
        ))
    }

    @Test
    fun captureSerialisesACloneWithTheDisplayStyleRemoved() {
        val html = htmlOf(ContentElement.ChemElement("CCO"))
        assertTrue("capture serialises the wrong node", html.contains("var c=captureSvg.cloneNode(true);"))
        assertTrue("display style not stripped", html.contains("c.removeAttribute('style');"))
        assertTrue("clone not serialised",
            html.contains("var xml=new XMLSerializer().serializeToString(c);"))
        assertFalse("live themed svg serialised instead",
            html.contains("XMLSerializer().serializeToString(svg)"))
        // Only a real PNG is handed to the bridge.
        assertTrue("PNG prefix guard missing",
            html.contains("png.indexOf('data:image/png;base64,')===0"))
    }

    @Test
    fun maliciousChemColourCannotEscapeTheJsLiteral() {
        val hostile = "rgba(0,0,0,1);alert(1)</script><b>&\""
        val html = htmlOf(ContentElement.ChemElement("CCO"), textColorCss = hostile)
        // textColorCss also feeds the page's CSS `color:` declaration, so
        // scope the injection check to the JS theme literal itself.
        assertFalse("colour broke out of the JS theme",
            html.contains("C:\"rgba(0,0,0,1);alert(1)</script>"))
        assertTrue("colour not escaped for JS",
            html.contains("\\u003c/script\\u003e\\u003cb\\u003e\\u0026"))
        assertTrue("colour must stay one inert JS string",
            html.contains("C:\"rgba(0,0,0,1);alert(1)\\u003c/script\\u003e\\u003cb\\u003e\\u0026\\\"\""))
        assertEquals("\"rgba(0,0,0,1);alert(1)\\u003c/script\\u003e\\u003cb\\u003e\\u0026\\\"\"",
            jsStringLiteral(hostile))
    }

    @Test
    fun svgStructureIsCappedAtTheDisplayMax() {
        val html = htmlOf(ContentElement.ChemElement("CCO"))
        // The owner chose the compact 96 px cap for standalone structures.
        assertEquals(96, CHEM_DISPLAY_MAX_PX)
        assertTrue("SVG not capped: $html", html.contains(
            "<svg id=\"mcq-chem-0\" style=\"width:${CHEM_DISPLAY_MAX_PX}px;max-width:100%;" +
                "max-height:${CHEM_DISPLAY_MAX_PX}px;height:auto;vertical-align:middle;\"></svg>"
        ))
        assertFalse("SVG missing its height cap",
            html.contains("<svg id=\"mcq-chem-0\" style=\"width:${CHEM_DISPLAY_MAX_PX}px;" +
                "max-width:100%;height:auto;"))
        assertTrue("capture svg missing", html.contains("<svg id=\"mcq-chem-0-capture\""))
        // The width/height drawer options are inert for an <svg> target; the
        // cap lives only in the live element's CSS, never in the capture clone.
    }

    @Test
    fun standaloneDrawingIsCroppedAndSizedByAMinimumScale() {
        val html = htmlOf(ContentElement.ChemElement("CCO"))
        // 0.8 * SmilesDrawer's 30-unit default bond is a ~23.4 px bond (24 px
        // before the drawer's own geometry): the scale a ring is drawn at,
        // which a wide chain keeps too.
        assertEquals(0.8, CHEM_STANDALONE_MIN_SCALE, 0.0)
        assertTrue("fit helper not defined: $html", html.contains("function fitStandalone(s){"))
        // getBBox() crops the drawer's square viewBox to the drawing's real
        // bounds (plus the 2 px padding), instead of measuring a mostly empty
        // 254×254 square.
        assertTrue("drawing bounds not measured",
            html.contains("var bb=s.getBBox();if(!(bb.width>0)||!(bb.height>0))return;"))
        assertTrue("viewBox not cropped to the drawing's bounds",
            html.contains("s.setAttribute('viewBox',(bb.x-p)+' '+(bb.y-p)+' '+w+' '+h);"))
        assertTrue("minimum scale not applied: $html", html.contains(
            "var sc=Math.max(${CHEM_DISPLAY_MAX_PX}/Math.max(w,h),$CHEM_STANDALONE_MIN_SCALE);"
        ))
        assertTrue("fitted width/height not applied",
            html.contains("s.style.width=(w*sc)+'px';s.style.height='auto';s.style.maxWidth='100%';"))
        // The marker is set only AFTER the crop and sizing, so a UI probe can
        // tell a completed fit from the drawer's pre-fit square viewBox.
        val fitMarker = "s.setAttribute('data-fitted','1');"
        assertTrue("fit marker missing: $html", html.contains(fitMarker))
        assertTrue("fit marker must be set after the sizing",
            html.indexOf(fitMarker) > html.indexOf("s.style.width=(w*sc)+'px';"))
        // The fit belongs to the standalone live draw: after its draw and its
        // scoped styles, before the hidden capture is drawn.
        val drawAt = html.indexOf(".draw(tree,svg,'mcq')")
        val scopeAt = html.indexOf("scopeChemStyles(svg);")
        val fitAt = html.indexOf("fitStandalone(svg);")
        val captureDrawAt = html.indexOf(".draw(tree,captureSvg,'light')")
        assertTrue("standalone live draw missing", drawAt >= 0)
        assertTrue("fit must run after the live draw and its scoping",
            scopeAt > drawAt && fitAt > scopeAt)
        assertTrue("fit must run before the hidden capture", captureDrawAt > fitAt)
        assertEquals("fit called more than once for the live svg",
            1, html.split("fitStandalone(svg);").size - 1)
        // display:none makes getBBox() 0, and the capture's own viewBox sizes
        // the exported PNG: the fit must never touch it.
        assertFalse("capture must not be fitted", html.contains("fitStandalone(captureSvg)"))
        // A reaction is an inline scheme whose width the reaction scale pass
        // already owns: the helper must not be called there.
        val reaction = htmlOf(ContentElement.ChemElement(smiles = "CCO>>", caption = "reagent"))
        assertFalse("reaction must not be fitted: $reaction",
            reaction.contains("fitStandalone(svg);"))
    }

    @Test
    fun pngDiagramIsCappedAtTheDisplayMax() {
        val png = "data:image/png;base64,iVBORw0KGgo="
        val html = htmlOf(ContentElement.ChemElement("CCO", diagram = png))
        assertTrue("PNG not capped: $html", html.contains(
            "style=\"max-width:100%;max-height:${CHEM_DISPLAY_MAX_PX}px;width:auto;" +
                "height:auto;vertical-align:middle;\""
        ))
        assertFalse("PNG missing its height cap",
            html.contains("style=\"max-width:100%;height:auto;vertical-align:middle;\"/>"))
    }

    @Test
    fun aPageWithoutChemistryDoesNotLoadTheDrawer() {
        val html = htmlOf(ContentElement.MathElement("<math><mi>x</mi></math>"))
        assertFalse("unneeded chemistry script", html.contains(SMILES_DRAWER_ASSET_URL))
        assertFalse(html.contains("SmilesDrawer.parse("))
    }

    @Test
    fun multipleBlocksGetDistinctSvgIds() {
        val html = htmlOf(
            ContentElement.ChemElement("CCO"),
            ContentElement.TextElement(" and "),
            ContentElement.ChemElement("c1ccccc1")
        )
        assertTrue(html.contains("<svg id=\"mcq-chem-0\""))
        assertTrue(html.contains("<svg id=\"mcq-chem-1\""))
        // One script tag per page, not one per block.
        assertEquals(1, html.split(SMILES_DRAWER_ASSET_URL).size - 1)
    }

    @Test
    fun theBundledAssetIsActuallyPresent() {
        val js = File("src/main/assets/smiles-drawer/smiles-drawer.min.js")
        assertTrue("missing: ${js.absolutePath}", js.isFile)
    }

    // ---- reaction mode -----------------------------------------------------

    @Test
    fun reactionSmilesUsesParseReactionAndDrawsCaptionAboveTheArrow() {
        val html = htmlOf(ContentElement.ChemElement(
            smiles = "CCO>>", caption = "acid catalyst", formula = "C2H4O"
        ))
        assertTrue("parseReaction missing: $html", html.contains(
            "SmilesDrawer.parseReaction(\"CCO\\u003e\\u003e\",reaction=>{try{"
        ))
        assertTrue("ReactionDrawer missing", html.contains(
            "new window.SmilesDrawer.ReactionDrawer(rxnOptions,molOptions)"
        ))
        assertTrue("caption not passed as textAbove", html.contains(
            ".draw(reaction,svg,'mcq',null,\"acid catalyst\",\"\",false);"
        ))
        assertFalse("ordinary caption line must be suppressed in reaction mode",
            html.contains("<div class=\"mcq-chem-caption\">"))
        assertTrue("text fallback missing",
            html.contains("Unavailable: C2H4O acid catalyst"))
        assertTrue("height bridge missing", html.contains(CONTENT_HEIGHT_BRIDGE))
        // A molecule must still use the ordinary parser.
        assertFalse("molecule must not use parseReaction",
            htmlOf(ContentElement.ChemElement("CCO")).contains("parseReaction"))
    }

    @Test
    fun aSingleAngleBracketStaysOnTheMoleculePath() {
        // ReactionParser splits on '>' and throws unless there are exactly
        // two. A malformed one-separator SMILES must not reach it; the
        // molecule draw (which shows the ordinary caption line) is the safe
        // fallback.
        val html = htmlOf(ContentElement.ChemElement("CCO>CCO", caption = "not a reaction"))
        assertTrue("single '>' must use the molecule parser", html.contains(
            "SmilesDrawer.parse(\"CCO\\u003eCCO\""
        ))
        assertFalse("single '>' must not use parseReaction", html.contains("parseReaction"))
        assertTrue("single '>' must use SvgDrawer", html.contains("new window.SmilesDrawer.SvgDrawer("))
        assertTrue("single '>' must keep the ordinary caption line", html.contains(
            "<div class=\"mcq-chem-caption\">not a reaction</div>"
        ))
    }

    @Test
    fun reactionWithEmptyReagentsOrProductsStillTakesTheReactionPath() {
        listOf("CCO>>", ">>CCO", "CCO>CCO>CCO").forEach { smiles ->
            val html = htmlOf(ContentElement.ChemElement(smiles, caption = "reagent"))
            assertTrue("$smiles did not use parseReaction", html.contains("SmilesDrawer.parseReaction("))
            assertFalse("$smiles leaked an ordinary caption line",
                html.contains("<div class=\"mcq-chem-caption\">"))
        }
    }

    @Test
    fun reactionCaptionIsJsEscapedBeforeItBecomesTextAbove() {
        val hostile = "cat\"</script><b>&"
        val html = htmlOf(ContentElement.ChemElement(smiles = "CCO>>", caption = hostile))
        assertFalse("caption closed the script", html.contains("\"</script><b>&\""))
        assertTrue("caption not JS-escaped", html.contains("\\u003c/script\\u003e\\u003cb\\u003e\\u0026"))
        assertTrue("escaped caption not used as textAbove", html.contains(
            ".draw(reaction,svg,'mcq',null,\"cat\\\"\\u003c/script\\u003e\\u003cb\\u003e\\u0026\",\"\",false);"
        ))
        assertFalse("ordinary caption line leaked", html.contains("<div class=\"mcq-chem-caption\">"))
    }

    @Test
    fun hostileProductLabelIsEscapedAsHtmlText() {
        val hostile = "<img src=x onerror=alert(1)>"
        val html = htmlOf(ContentElement.ChemElement(
            smiles = "CCO>>", caption = "reagent", formula = hostile
        ))
        assertFalse("product label emitted live markup",
            html.contains("<img src=x onerror=alert(1)>"))
        assertTrue("product label not escaped: $html", html.contains(
            "<span class=\"mcq-chem-product\" style=\"font-size:0.85em\">" +
                "&lt;img src=x onerror=alert(1)&gt;</span>"
        ))
        assertFalse("hostile product label injected an img tag", html.contains("<img "))
    }

    @Test
    fun reactionStructureUsesAnInlineFlexRowWithProductLabel() {
        val html = htmlOf(ContentElement.ChemElement(
            smiles = "CCO>>", caption = "reagent", formula = "A"
        ))
        assertTrue("reaction needs an inline flex row: $html", html.contains(
            "<div class=\"mcq-chem mcq-chem-reaction\" style=\"display:flex;" +
                "align-items:center;gap:4px;max-width:100%;white-space:nowrap;" +
                "justify-content:center;\">"
        ))
        assertTrue("reaction svg must be inline-sized, not capped or full-width", html.contains(
            "<svg id=\"mcq-chem-0\" style=\"max-width:100%;height:auto;" +
                "flex:0 1 auto;\"></svg>"
        ))
        assertTrue("product label missing from the inline row", html.contains(
            "<span class=\"mcq-chem-product\" style=\"font-size:0.85em\">A</span>"
        ))
        assertFalse("reaction must not use the standalone molecule cap",
            html.contains("id=\"mcq-chem-0\" style=\"width:${CHEM_DISPLAY_MAX_PX}px"))
        assertFalse("reaction svg must not be forced to full width",
            html.contains("id=\"mcq-chem-0\" style=\"max-width:100%;width:100%"))
        assertFalse("reaction must not emit the old caption line",
            html.contains("<div class=\"mcq-chem-caption\">"))
        assertFalse("container must not cap its height behind the UI bound",
            html.contains("max-height:64px"))
    }

    @Test
    fun reactionDrawScalesTheDrawerWidthThenAddsConstraints() {
        val html = htmlOf(ContentElement.ChemElement(smiles = "CCO>>", caption = "reagent"))
        // Keep ReactionDrawer's computed width as the scale basis. The live
        // options are drawn 2.2x oversized in user units (so every label clears
        // Android WebView's 8 px minimum font size), and this pass scales the
        // whole SVG back down by 1/2.2, so the row stays the same size.
        // Replacing the whole cssText would drop that width and fall back to
        // the 300 px default, so it must never appear here.
        val constraints = "var rw=parseFloat(svg.style.width);" +
            "if(rw>0){svg.style.width=(rw*0.773)+'px';}" +
            "svg.style.maxWidth='100%';svg.style.height='auto';" +
            "svg.style.flex='0 1 auto';"
        assertTrue("width scaling/constraints not applied after draw: $html",
            html.contains(constraints))
        assertFalse("reaction must not overwrite the drawer's width with cssText",
            html.contains("svg.style.cssText="))
        val drawAt = html.indexOf(".draw(reaction,svg,'mcq'")
        val constraintsAt = html.indexOf("var rw=parseFloat(")
        assertTrue("live reaction draw missing", drawAt >= 0)
        assertTrue("scaling must happen after the draw", constraintsAt > drawAt)
        assertTrue("capture must stay hidden", html.contains(
            "<svg id=\"mcq-chem-0-capture\" style=\"display:none;\"></svg>"
        ))
        assertFalse("capture must not carry a display cap", html.contains("captureSvg.style"))
    }

    @Test
    fun reactionLiveOptionsAreTunedAndCaptureSpellsOutTheDrawerDefaults() {
        val html = htmlOf(ContentElement.ChemElement(smiles = "CCO>>", caption = "reagent"))
        // The live scheme is drawn 2.2x oversized in user units and the SVG is
        // then scaled down by 1/2.2, so the row keeps its size while every
        // label clears Android WebView's 8 px minimum font size: 8.1 pt =
        // 10.8 px for an atom and 0.8 * 8.1 = 6.48 pt = 8.64 px for the
        // caption. The 8.1/22 label-to-bond ratio and 2.2/8.1 subscript ratio
        // match the standalone drawings' 11/30 and 3/11 within rounding.
        // `fontSizeSmall` is a proportion guard on SvgWrapper's injected
        // `.sub` rule: SmilesDrawer 2.1.1 defines `createSubSuperScripts` but
        // never calls it, so CH3 hydrogens are Unicode subscript characters,
        // not `.sub` elements.
        assertTrue("reaction mol options not scaled: $html", html.contains(
            "var molOptions={width:300,height:300,padding:2.2,bondThickness:1.76," +
                "bondSpacing:11.22," +
                "bondLength:22,fontSizeLarge:8.1,fontSizeSmall:2.2," +
                "overlapResolutionIterations:2,experimentalSSSR:true,terminalCarbons:true,"
        ))
        // ReactionDrawer's fixed chrome (spacing, plus signs, arrow) is scaled
        // by the same 2.2 so the rendered scheme is unchanged. Options.extend
        // deep-merges, so `arrow.length` stays derived as 4 * bondLength.
        assertTrue("reaction chrome options missing: $html", html.contains(
            "var rxnOptions={spacing:22,plus:{size:19.8,thickness:2.2}," +
                "arrow:{headSize:13.2,thickness:2.2,margin:6.6}};"
        ))
        // Only the live options may be scaled: the capture carries the drawer
        // defaults on purpose (see the pinned string below).
        val liveOptionsAt = html.indexOf("var molOptions=")
        val captureOptionsAt = html.indexOf("var captureMolOptions=")
        assertTrue("live molOptions missing: $html", liveOptionsAt >= 0)
        assertTrue("captureMolOptions missing: $html", captureOptionsAt > liveOptionsAt)
        val liveOptions = html.substring(liveOptionsAt, captureOptionsAt)
        assertFalse("large molecule geometry leaked into the live reaction options",
            liveOptions.contains("bondLength:30") || liveOptions.contains("fontSizeLarge:13") ||
                liveOptions.contains("bondLength:16") || liveOptions.contains("bondLength:12") ||
                liveOptions.contains("fontSizeLarge:7") ||
                liveOptions.contains("fontSizeLarge:5.5") || liveOptions.contains("fontSizeLarge:4") ||
                liveOptions.contains("fontSizeLarge:3.2") || liveOptions.contains("fontSizeSmall:0.9") ||
                liveOptions.contains("fontSizeSmall:3"))
        // The capture is drawn at the 1x capture scale, so it must NOT inherit
        // the live 2.2x chrome: ReactionDrawer's defaults (arrow thickness 1)
        // are what the exported PNG needs.
        assertTrue("capture chrome options missing: $html", html.contains(
            "var captureRxnOptions={};"
        ))
        assertTrue("capture must use its own default chrome: $html", html.contains(
            "new window.SmilesDrawer.ReactionDrawer(captureRxnOptions,captureMolOptions)"
        ))
        assertFalse("capture must not use the scaled live chrome: $html",
            html.contains("new window.SmilesDrawer.ReactionDrawer(rxnOptions,captureMolOptions)"))
        assertTrue("custom mcq theme missing", html.contains(
            "themes:{mcq:{C:\"rgba(0,0,0,1)\",H:\"rgba(0,0,0,1)\"}}"
        ))
        // ReactionDrawer reads bondLength/fontSizeLarge RAW before SvgDrawer's
        // defaults, for the arrow length and the caption font. Without them the
        // capture's viewBox and arrow are NaN and the exported PNG loses the
        // arrow and the product side.
        assertTrue("capture options must pin the geometry ReactionDrawer reads raw",
            html.contains("var captureMolOptions={width:300,height:300,padding:2," +
                "bondLength:30,fontSizeLarge:11,fontSizeSmall:3,terminalCarbons:true};"))
        // Terminal CH3 labels must agree between the on-screen scheme and the
        // hidden light-theme drawing that becomes the exported PNG.
        assertEquals("terminalCarbons:true must reach both live and capture drawers",
            2, html.split("terminalCarbons:true").size - 1)
        assertTrue("capture must draw with light theme", html.contains(
            ".draw(reaction,captureSvg,'light',null,\"reagent\",\"\",false);"
        ))
        assertFalse("capture must not use the live theme",
            html.contains(".draw(reaction,captureSvg,'mcq'"))
    }

    @Test
    fun reactionLabelToBondRatioMatchesStandaloneStructures() {
        val html = htmlOf(ContentElement.ChemElement(smiles = "CCO>>", caption = "reagent"))
        // Parse only the live molecule options; the capture's explicit drawer
        // defaults are pinned by their own test, and the trailing themes object
        // makes a whole-brace match awkward, so stop at the theme override.
        val liveMatch = Regex("var molOptions=\\{(.*?),themes:").find(html)
        assertNotNull("live molOptions not found: $html", liveMatch)
        val liveOptions = liveMatch!!.groupValues[1]

        fun number(key: String): Double {
            val match = Regex("$key:([0-9.]+)").find(liveOptions)
            assertNotNull("missing $key in live molOptions: $liveOptions", match)
            return match!!.groupValues[1].toDouble()
        }

        val large = number("fontSizeLarge")
        val small = number("fontSizeSmall")
        val bond = number("bondLength")

        // Exact parity with the standalone defaults: SmilesDrawer draws a
        // molecule at fontSizeLarge 11 over bondLength 30, and its injected
        // `.sub` rule uses fontSizeSmall 3, i.e. 3/11 of the label. (2.1.1's
        // `createSubSuperScripts` is unused, so a CH3 hydrogen is a Unicode
        // character, but the `.sub` rule still ships and must keep its
        // proportion.) A reaction letter must be neither bolder nor smaller
        // than the standalone, so both differences are bounded.
        val labelToBond = large / bond
        val standaloneLabelToBond = 11.0 / 30.0
        assertTrue(
            "reaction label/bond ratio $labelToBond is not within 0.02 of " +
                "the standalone $standaloneLabelToBond",
            kotlin.math.abs(labelToBond - standaloneLabelToBond) <= 0.02
        )
        val subscriptToLabel = small / large
        val standaloneSubscriptToLabel = 3.0 / 11.0
        assertTrue(
            "reaction subscript/label ratio $subscriptToLabel is not within 0.03 of " +
                "the standalone $standaloneSubscriptToLabel",
            kotlin.math.abs(subscriptToLabel - standaloneSubscriptToLabel) <= 0.03
        )
        // Android WebView clamps any computed font size below its minimum font
        // size, which defaults to 8 px
        // (https://developer.android.com/reference/android/webkit/WebSettings#setMinimumFontSize(int)).
        // The floor is therefore 8 PIXELS = 6 pt, not 8 pt. This guard is
        // deliberately conservative at 8 pt (10.67 px), so a later "smaller
        // font" tweak cannot walk back toward the clamp; the live 8.1 pt
        // (10.8 px) clears it with headroom.
        assertTrue(
            "live label $large pt is below the conservative 8 pt guard (Android's floor is 8 px)",
            large >= 8.0
        )
    }

    @Test
    fun injectedStylesAreScopedToTheirOwnSvg() {
        // `scopeChemStyles` must be defined and called after every draw, so a
        // later-drawn structure's document-wide <style> cannot restyle this
        // drawing — in production the reaction's own hidden capture is drawn
        // after the live scheme, at the standalone 11 pt / 30-unit defaults.
        // The escaped regex is pinned: it must cover the atom label (.element),
        // the `.sub` rule (unused by 2.1.1's renderer but still injected) and
        // the reagent caption (.text).
        val reaction = htmlOf(ContentElement.ChemElement(smiles = "CCO>>", caption = "reagent"))
        assertTrue("style-scoping helper missing", reaction.contains("function scopeChemStyles(s){"))
        assertTrue("helper must scope element|sub|text: $reaction",
            reaction.contains("(^|[\\s,{}])\\.(element|sub|text)\\b"))
        // A second pass over the same SVG (it is called for live and capture,
        // and capture() serialises the already-scoped clone) must not turn
        // `#id .element` into `#id #id .element`.
        val guardAt = reaction.indexOf("if(e.textContent.indexOf('#'+id+' .')>=0)continue;")
        val replaceAt = reaction.indexOf(".textContent.replace(")
        assertTrue("scoping guard missing: $reaction", guardAt >= 0)
        assertTrue("guard must run before the replace: $reaction",
            replaceAt > guardAt)

        val liveDraw = reaction.indexOf(".draw(reaction,svg,'mcq'")
        val liveScope = reaction.indexOf("scopeChemStyles(svg);")
        assertTrue("live reaction draw missing", liveDraw >= 0)
        assertTrue("live reaction style must be scoped after its draw", liveScope > liveDraw)
        val captureDraw = reaction.indexOf(".draw(reaction,captureSvg,'light'")
        val captureScope = reaction.indexOf("scopeChemStyles(captureSvg);")
        val captureCall = reaction.indexOf("capture();")
        assertTrue("capture reaction draw missing", captureDraw >= 0)
        assertTrue("capture reaction style must be scoped after its draw",
            captureScope > captureDraw)
        assertTrue("capture reaction style must be scoped before capture()",
            captureCall > captureScope)
        // The scoped selectors live in the clone too: it keeps its id.
        assertFalse("clone must not drop the id the scoped selectors need",
            reaction.contains("removeAttribute('id')"))

        val standalone = htmlOf(ContentElement.ChemElement("CCO"))
        assertTrue("style-scoping helper missing", standalone.contains("function scopeChemStyles(s){"))
        val svgDraw = standalone.indexOf(".draw(tree,svg,'mcq')")
        val svgScope = standalone.indexOf("scopeChemStyles(svg);")
        assertTrue("standalone draw missing", svgDraw >= 0)
        assertTrue("standalone style must be scoped after its draw", svgScope > svgDraw)
        val svgCaptureDraw = standalone.indexOf(".draw(tree,captureSvg,'light')")
        val svgCaptureScope = standalone.indexOf("scopeChemStyles(captureSvg);")
        val svgCaptureCall = standalone.indexOf("capture();")
        assertTrue("standalone capture draw missing", svgCaptureDraw >= 0)
        assertTrue("standalone capture style must be scoped after its draw",
            svgCaptureScope > svgCaptureDraw)
        assertTrue("standalone capture style must be scoped before capture()",
            svgCaptureCall > svgCaptureScope)
    }

    @Test
    fun reactionBlockStillCapturesTheLightDrawingToThePngBridge() {
        val html = htmlOf(
            ContentElement.ChemElement(smiles = "CCO>>", caption = "reagent"),
            chemKey = "chem-42"
        )
        assertTrue("capture clone missing", html.contains("var c=captureSvg.cloneNode(true);"))
        assertTrue("display style not stripped", html.contains("c.removeAttribute('style');"))
        assertTrue("bridge call missing", html.contains(
            "window.$CHEM_DIAGRAM_BRIDGE.onDiagram(\"chem-42\",png)"
        ))
        assertTrue("PNG prefix guard missing", html.contains(
            "png.indexOf('data:image/png;base64,')===0"
        ))
        val captureDrawAt = html.indexOf(".draw(reaction,captureSvg,'light'")
        val captureAt = html.indexOf("capture();")
        assertTrue("capture draw missing", captureDrawAt >= 0)
        assertTrue("bridge must run after the capture draw", captureAt > captureDrawAt)
    }

    // ---- injection boundaries ---------------------------------------------

    @Test
    fun smilesIsJsonEscapedAndRestrictedToTheAllowlist() {
        assertEquals("\"c1ccccc1\"", smilesJsLiteral("c1ccccc1"))
        // A backslash is in the allowlist, so it must survive as a JSON escape.
        assertEquals("\"\\\\\"", smilesJsLiteral("\\"))
        assertEquals("\"alert(1)//\"", smilesJsLiteral("\";alert(1)//"))
        // OpenSMILES two-digit ring closures must survive (%10 is ring 10).
        assertEquals("\"C1%10CCCCC1\"", smilesJsLiteral("C1%10CCCCC1"))
        // Control characters and '<' are dropped; '>' is now allowed for
        // reaction SMILES, but jsStringLiteral escapes it as \u003e.
        assertEquals("\"Cscr\\u003eipt\\u003eC\"", smilesJsLiteral("C\n<scr>ipt>C"))
        assertEquals("\"CCO\\u003e\\u003e\"", smilesJsLiteral("CCO>>"))
    }

    @Test
    fun maliciousSmilesCannotEscapeTheJsStringOrInjectMarkup() {
        val html = htmlOf(
            ContentElement.ChemElement(
                smiles = "\";alert(1)//",
                formula = "X",
                caption = "<img src=x onerror=alert(1)>"
            )
        )
        // The quote and semicolon never reach the JS string literal.
        assertFalse("JS string escaped: $html", html.contains("\"\";alert(1)//"))
        assertTrue("allowlist-filtered SMILES missing", html.contains("SmilesDrawer.parse(\"alert(1)//\""))
        // Caption is HTML text, never live markup.
        assertFalse("caption injected markup", html.contains("<img src=x onerror=alert(1)>"))
        assertTrue("caption not escaped", html.contains("&lt;img src=x onerror=alert(1)&gt;"))
        assertFalse("script injected", html.contains("<script>alert(1)</script>"))
    }

    @Test
    fun onlyAStrictPngDataUriIsAccepted() {
        assertTrue(isPngDataUri("data:image/png;base64,iVBORw0KGgo="))
        assertFalse(isPngDataUri("DATA:IMAGE/PNG;BASE64,iVBORw0KGgo"))
        assertFalse(isPngDataUri("data:image/jpeg;base64,iVBORw0KGgo"))
        assertFalse(isPngDataUri("data:image/svg+xml;base64,AAAA"))
        assertFalse(isPngDataUri("data:image/png;base64,"))
        assertFalse(isPngDataUri("data:image/png;base64,AA\" onerror=\"alert(1)"))
    }

    @Test
    fun aValidDiagramReplacesTheSvgAndAnInvalidOneFallsBackToDrawing() {
        val png = "data:image/png;base64,iVBORw0KGgo="
        val withDiagram = htmlOf(ContentElement.ChemElement("CCO", diagram = png))
        assertTrue("PNG img missing", withDiagram.contains("<img src=\"$png\""))
        assertFalse("SVG should not be drawn for a PNG", withDiagram.contains("<svg id=\"mcq-chem-0\""))
        assertFalse("no draw needed for a PNG", withDiagram.contains("SmilesDrawer.parse("))

        val hostile = "data:image/png;base64,\"/><script>alert(1)</script>"
        val fallback = htmlOf(ContentElement.ChemElement("CCO", diagram = hostile))
        assertTrue("invalid diagram must still draw", fallback.contains("<svg id=\"mcq-chem-0\""))
        assertTrue(fallback.contains("SmilesDrawer.parse(\"CCO\""))
        assertFalse("data URI injected markup", fallback.contains("<script>alert(1)</script>"))
    }

    @Test
    fun aBase64PayloadThatIsNotAPngIsRejectedAndKeepsTheTextFallback() {
        // "not a png" is valid base64 with the PNG prefix, but the bytes do
        // not carry the PNG signature — the shared strict validator rejects it.
        val notAPng = "data:image/png;base64,bm90IGEgcG5n"
        assertFalse("non-PNG bytes accepted", isPngDataUri(notAPng))
        val html = htmlOf(ContentElement.ChemElement("CCO", formula = "C2H6O", diagram = notAPng))
        assertTrue("SVG must be drawn instead", html.contains("<svg id=\"mcq-chem-0\""))
        assertFalse("bad picture embedded", html.contains("<img src=\"$notAPng\""))
        assertTrue("text fallback missing", html.contains("Unavailable: C2H6O"))
    }

    // ---- PNG capture bridge -------------------------------------------------

    @Test
    fun captureJsSerialisesTheSvgAndHandsThePngToTheBridgeWithItsKey() {
        val html = htmlOf(
            ContentElement.ChemElement("CCO", formula = "C2H6O"),
            chemKey = "chem-42"
        )
        assertTrue("capture bridge missing", html.contains(CHEM_DIAGRAM_BRIDGE))
        assertTrue("bridge name missing", html.contains("window.$CHEM_DIAGRAM_BRIDGE"))
        assertTrue("wrong key handed to bridge", html.contains(
            "window.$CHEM_DIAGRAM_BRIDGE.onDiagram(\"chem-42\",png)"
        ))
        assertTrue("capture clone not serialised",
            html.contains("var xml=new XMLSerializer().serializeToString(c);"))
        assertTrue("not drawn into a canvas", html.contains("canvas.getContext('2d').drawImage(img,0,0)"))
        assertTrue("not rasterised to PNG", html.contains("canvas.toDataURL('image/png')"))
        assertTrue("SVG data URI missing", html.contains("img.src='data:image/svg+xml;base64,'"))
        assertTrue("capture not called after draw", html.contains("capture();"))
        // A plain page has no bridge calls at all.
        val plain = htmlOf(ContentElement.MathElement("<math><mi>x</mi></math>"))
        assertFalse(plain.contains(CHEM_DIAGRAM_BRIDGE))
    }

    @Test
    fun captureFailureLeavesTheTextFallbackAndNeverCallsTheBridge() {
        val html = htmlOf(ContentElement.ChemElement("CCO", formula = "C2H6O"), chemKey = "chem-42")
        assertTrue("no parse-error fallback", html.contains("err=>showFallback()"))
        assertTrue("no missing-drawer fallback",
            html.contains("if(!window.SmilesDrawer||!window.SmilesDrawer.parse)throw new Error('drawer');"))
        assertTrue("no fallback reveal",
            html.contains("function showFallback(){svg.style.display='none';if(fallback)fallback.style.display='';}"))
        assertTrue("no fallback text", html.contains("Unavailable: C2H6O"))
        // The bridge call is in the success path only: nothing after the error
        // callback may hand a PNG anywhere.
        val errorCallback = html.indexOf("err=>showFallback()")
        assertTrue("error callback not found", errorCallback >= 0)
        assertFalse("bridge called on failure",
            html.substring(errorCallback).contains("onDiagram"))
    }

    @Test
    fun theChemKeyIsJsEscapedAndCannotBreakOutOfTheScript() {
        val hostileKey = "a\"</script><b>&"
        val html = htmlOf(ContentElement.ChemElement("CCO"), chemKey = hostileKey)
        assertFalse("key closed the script tag", html.contains("\"</script><b>&\""))
        assertTrue("key not escaped for the JS string",
            html.contains("\\u003c/script\\u003e\\u003cb\\u003e\\u0026"))
        assertEquals("\"a\\\"\\u003c/script\\u003e\\u003cb\\u003e\\u0026\"", jsStringLiteral(hostileKey))
    }

    // ---- localised copy ----------------------------------------------------

    @Test
    fun fallbackCopyExistsInBothLocalesAndIsUsedByTheRenderer() {
        val en = File("src/main/res/values/strings_chemistry_render.xml").readText()
        val hi = File("src/main/res/values-hi/strings_chemistry_render_hi.xml").readText()
        assertTrue("English fallback copy missing", en.contains("name=\"chem_structure_unavailable\""))
        assertTrue("Hindi fallback copy missing", hi.contains("name=\"chem_structure_unavailable\""))
        val renderer = File("src/main/java/com/mcqapp/util/ContentElements.kt").readText()
        assertTrue("fallback copy not resolved", renderer.contains("R.string.chem_structure_unavailable"))
        assertTrue("fallback copy not passed to the HTML sink", renderer.contains(
            "mixedContentHtml(elements, cssPx, color.toCssRgba(), chemUnavailable, currentChemKey)"
        ))
        assertTrue("no PNG capture bridge", renderer.contains("CHEM_DIAGRAM_BRIDGE"))
        assertTrue("bridge not registered", renderer.contains("addJavascriptInterface("))
    }
}
