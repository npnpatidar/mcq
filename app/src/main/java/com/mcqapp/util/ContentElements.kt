package com.mcqapp.util

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mcqapp.R
import com.mcqapp.data.docx.MathNode
import com.mcqapp.data.docx.decodeXmlEntities
import com.mcqapp.data.docx.parseMathTree
import com.mcqapp.data.export.isPngDataUri
import com.mcqapp.data.escapeHtmlText
import com.mcqapp.data.normalizeMathText
import com.mcqapp.domain.ContentElement
import kotlinx.coroutines.delay

/** Which renderer [ContentElements] picks, exposed for pure-JVM tests. */
internal enum class ContentRenderPath { PLAIN_TEXT, NATIVE, MIXED }

/**
 * The branch decision as a pure function so it can be asserted without
 * Compose: a formula always goes to the mixed (WebView) view, a lone text run
 * keeps the legacy plain path, anything else uses the native column.
 */
internal fun contentRenderPath(elements: List<ContentElement>): ContentRenderPath = when {
    // Chemistry is drawn by the bundled SmilesDrawer JavaScript, so it shares
    // the WebView path with formulas. Never send it down the native column:
    // that path has no way to turn a SMILES string into a picture.
    elements.any { it is ContentElement.MathElement || it is ContentElement.ChemElement } ->
        ContentRenderPath.MIXED
    elements.size == 1 && elements[0] is ContentElement.TextElement -> ContentRenderPath.PLAIN_TEXT
    else -> ContentRenderPath.NATIVE
}

/**
 * Speech-friendly linearisation of MathML, used ONLY by the accessibility
 * fallback. The visual page keeps MathJax and `mathMlLinearText` untouched;
 * flattening a fraction to "ab" would lose its meaning, so structural
 * elements are named. Token elements are concatenated exactly as before.
 * Pure JVM, so it is testable on every host.
 */
internal fun accessibleMathText(mathml: String): String {
    fun MathNode.speak(): String = when (this) {
        is MathNode.Txt -> text
        is MathNode.El -> {
            val parts = children.filterIsInstance<MathNode.El>()
            fun spoken(i: Int): String = parts.getOrNull(i)?.speak().orEmpty()
            val flat = children.joinToString("") { it.speak() }
            when (name) {
                "mfrac" -> if (parts.size == 2) "${spoken(0)} over ${spoken(1)}" else flat
                "msqrt" -> "square root of $flat"
                "mroot" -> if (parts.size == 2) {
                    "root of ${spoken(0)} with index ${spoken(1)}"
                } else flat
                "msup" -> if (parts.size == 2) "${spoken(0)} superscribed ${spoken(1)}" else flat
                "msub" -> if (parts.size == 2) "${spoken(0)} subscripted ${spoken(1)}" else flat
                "msubsup" -> if (parts.size == 3) {
                    "${spoken(0)} subscripted ${spoken(1)} superscribed ${spoken(2)}"
                } else flat
                else -> flat
            }
        }
    }
    return decodeXmlEntities(parseMathTree(mathml).joinToString("") { it.speak() })
        .replace(Regex("\\s+"), " ").trim()
}

/**
 * Readable plain text for the mixed view's accessibility label: the WebView
 * is one Android view with no text semantics of its own, so the actual
 * question (text, table cells and formulas) is spelled out here instead of
 * leaving TalkBack with a fixed generic description. Pure JVM — no Android
 * or Compose runtime — so it is testable on every host.
 */
internal fun accessibleContentText(elements: List<ContentElement>): String = buildString {
    elements.forEachIndexed { index, element ->
        if (index > 0) append(' ')
        when (element) {
            is ContentElement.TextElement ->
                append(InlineHtml.parse(element.text).text.trim())
            is ContentElement.MathElement ->
                append(accessibleMathText(element.mathml))
            is ContentElement.TableElement ->
                append(element.rows.joinToString(" ") { row ->
                    row.joinToString(" ") { cell ->
                        // A cell may hold a formula or a picture; turn both
                        // into readable text before the inline parser strips
                        // the tags it does not know. Escape the spoken math
                        // first: a decoded "<b>" is data, not a bold tag.
                        val withMath = cellMathPattern.replace(cell) { match ->
                            escapeHtmlText(accessibleMathText(match.value))
                        }
                        InlineHtml.parse(cellImgPattern.replace(withMath, CELL_IMAGE_MARKER))
                            .text.trim()
                    }
                })
            is ContentElement.ImageElement -> append(CELL_IMAGE_MARKER)
            is ContentElement.ChemElement -> {
                // Spoken copy is written for screen readers and wins over the
                // caption; the SMILES itself is the last resort. The diagram
                // is base64 image data, never something TalkBack should read.
                val label = element.spoken.takeIf { it.isNotBlank() }
                    ?: element.caption.takeIf { it.isNotBlank() }
                    ?: element.smiles
                append(InlineHtml.parse(label).text.trim())
                if (element.formula.isNotBlank()) {
                    append(' ').append(InlineHtml.parse(element.formula).text.trim())
                }
            }
        }
    }
}.replace(Regex("\\s+"), " ").trim()

/**
 * The description to put on the mixed view, or null on the plain/native
 * paths: only the WebView needs an injected label, so a plain-text question
 * is never labelled twice.
 */
internal fun accessibleContentLabelFor(elements: List<ContentElement>): String? =
    if (contentRenderPath(elements) == ContentRenderPath.MIXED) {
        accessibleContentText(elements)
    } else {
        null
    }

/**
 * The exact string the merged semantics node announces. Keeping the label
 * and fallback combination in one pure function lets a JVM test assert what
 * TalkBack hears, with no Compose test harness on this host.
 */
internal fun mixedContentDescription(label: String, fallback: String): String = "$label: $fallback"

/**
 * Renders a list of content elements: text, images, tables, and MathML
 * formulas. A lone [ContentElement.TextElement] renders as plain text, which
 * is how every pre-rich-content question is stored, so legacy questions look
 * exactly as they did before rich content existed. Content holding a
 * formula renders as one flowing HTML block instead: a stacked native
 * layout would force every inline equation onto a line of its own.
 */
@Composable
fun ContentElements(
    elements: List<ContentElement>,
    modifier: Modifier = Modifier,
    textStyle: TextStyle? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    chemKey: String = "",
    onChemDiagram: (key: String, pngDataUri: String) -> Unit = { _, _ -> }
) {
    val path = contentRenderPath(elements)
    if (path == ContentRenderPath.MIXED) {
        MixedContentView(elements, textStyle, modifier, chemKey, onChemDiagram)
        return
    }
    if (path == ContentRenderPath.PLAIN_TEXT) {
        ElementText((elements[0] as ContentElement.TextElement).text, textStyle, maxLines, overflow, modifier)
        return
    }
    Column(modifier = modifier) {
        elements.forEach { element ->
            when (element) {
                is ContentElement.TextElement ->
                    ElementText(element.text, textStyle, maxLines, overflow)
                is ContentElement.ImageElement -> QuestionImage(
                    src = element.src,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                is ContentElement.TableElement -> TableElement(element)
                is ContentElement.MathElement -> error("mixed view handles formulas")
                is ContentElement.ChemElement -> error("mixed view handles chemical structures")
            }
        }
    }
}

/**
 * A text run, with the bank file's inline HTML (`<b>`, `<sub>`, `&amp;`, …)
 * rendered as styled text. A null [textStyle] omits the style argument so
 * the Text uses the theme default, exactly as a bare `Text(text)` did
 * before rich content existed.
 */
@Composable
private fun ElementText(
    text: String,
    textStyle: TextStyle?,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    modifier: Modifier = Modifier
) {
    val annotated = remember(text) { InlineHtml.parse(text) }
    if (textStyle != null) {
        Text(annotated, style = textStyle, maxLines = maxLines, overflow = overflow, modifier = modifier)
    } else {
        Text(annotated, maxLines = maxLines, overflow = overflow, modifier = modifier)
    }
}

/** A native Compose table: one bordered row per [ContentElement.TableElement.rows] entry. */
@Composable
private fun TableElement(element: ContentElement.TableElement) {
    Column(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        element.rows.forEach { row ->
            // Cells must be as tall as the tallest cell in their row, or a
            // short cell stops at its last line and its own bottom border
            // draws a line mid-row: a wrapped neighbour then looks like it has
            // rowspanned (Screenshot/table.jpg). Sizing the row to its minimum
            // intrinsic height and letting every cell fill it keeps the grid
            // lines on the row boundaries.
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                row.forEach { cell ->
                    // Cells carry the same inline markup as a TextElement: a
                    // bare Text() rendered every tag literally. Pictures and
                    // formulas, which a native text run cannot show, collapse
                    // to a marker (consolidated_bugs.md G1-G3).
                    val annotated = remember(cell) { InlineHtml.parse(sanitizeTableCell(cell)) }
                    Text(
                        annotated,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .border(0.5.dp, MaterialTheme.colorScheme.outline)
                            .padding(4.dp)
                    )
                }
            }
        }
    }
}

/**
 * MathJax as a bundled `assets` script (SVG output, so glyphs are inline
 * paths and no webfont download is needed). Formulas render fully offline;
 * the script URL is `internal` so unit tests can pin the offline wiring.
 */
internal const val MATHJAX_ASSET_URL = "file:///android_asset/mathjax/tex-mml-svg.js"

/**
 * JavaScript bridge the rendered page uses to report its final height.
 * A height is only ever an `Int`, clamped in Kotlin before it is used, so
 * exposing the bridge to imported content cannot do more than ask for space.
 */
internal const val CONTENT_HEIGHT_BRIDGE = "McqContentHeight"

/**
 * Dedicated bridge for the rasterised structure. The height bridge only
 * accepts an Int, so a diagram cannot reuse it; keeping them separate also
 * means a bad PNG never affects the measured page height.
 */
internal const val CHEM_DIAGRAM_BRIDGE = "McqChemDiagram"

/** A single question's page cannot sensibly be taller than this (CSS px). */
internal const val MAX_CONTENT_HEIGHT_PX = 20_000

/** SmilesDrawer, bundled verbatim as an offline asset (no Gradle dependency). */
internal const val SMILES_DRAWER_ASSET_URL =
    "file:///android_asset/smiles-drawer/smiles-drawer.min.js"

/**
 * Display cap for a STANDALONE structure, in CSS px. SmilesDrawer's `width`
 * and `height` options are inert when it draws into an existing `<svg>` target,
 * so the cap is applied as CSS on the live element instead. A reaction uses
 * [CHEM_REACTION_STYLE] inside [CHEM_REACTION_CONTAINER_STYLE] instead: it is
 * an inline row, not a scaled-up molecule. The hidden capture SVG carries
 * neither style — only its clone's `display:none` style is removed before
 * serialisation, so the PNG keeps the drawer's full viewBox-derived size.
 *
 * [CHEM_STANDALONE_FIT_JS] replaces the fixed `width` with one derived from a
 * standalone drawing's real bounds, which lifts this cap for WIDE drawings
 * only: the element keeps `max-height:96px`, so a tall or square drawing is
 * still bounded here (see that constant for the effective scale).
 */
internal const val CHEM_DISPLAY_MAX_PX = 96

/**
 * Smallest drawing scale a STANDALONE structure may shrink to, relative to
 * SmilesDrawer's default bond length (30 user units → ~24 px bonds).
 *
 * [CHEM_DISPLAY_MAX_PX] on its own measured the drawer's SQUARE viewBox, not
 * the drawing: a ring's tight ~131×123 content got 20.7 px bonds, while a long
 * chain's tight ~245×78 content — the same square box — was squeezed to 11 px
 * bonds. [CHEM_STANDALONE_FIT_JS] crops that square padding with `getBBox()`,
 * and this floor stops a wide chain from shrinking below the scale a ring is
 * drawn at: the reference ring now renders at the 0.8 floor, a bond of about
 * 23.4 px — slightly larger than the old 20.7 px.
 *
 * It is a floor on the FIT, not a guarantee against the page: a very wide
 * chain still hits `max-width:100%`, so on a narrow page it is clamped below
 * 0.8 (a 36-carbon chain does exactly that).
 */
internal const val CHEM_STANDALONE_MIN_SCALE = 0.8

/** Compact standalone display style: one small, self-contained molecule. */
private const val CHEM_DISPLAY_STYLE =
    "width:${CHEM_DISPLAY_MAX_PX}px;max-width:100%;max-height:${CHEM_DISPLAY_MAX_PX}px;" +
        "height:auto;vertical-align:middle;"

/**
 * After a standalone molecule is drawn, crop the drawer's square viewBox to
 * the drawing's real bounds and size the element from that.
 *
 * Only the WIDTH cap is lifted, and only for wide drawings. The scale computed
 * here is `max(96 / max(w,h), 0.8)`, but the element keeps the display style's
 * `max-height:96px`, so the scale that actually reaches the screen is:
 *
 *   `min(max(96 / max(w,h), 0.8), 96 / h)`
 *
 * A tall, narrow structure is still bounded by the 96 px cap. A compact
 * drawing takes the `96/max(w,h)` branch and keeps its old scaled-up size
 * (CCO ≈ 1.32×, benzene ≈ 1.5×). A wide chain keeps the bond size a ring gets
 * instead of shrinking to fit a mostly empty square. The floor is not absolute
 * either: `max-width:100%` still applies, so a very wide chain on a narrow
 * page is clamped below 0.8.
 *
 * `data-fitted='1'` is set only once the crop and sizing ran, so a UI probe
 * cannot mistake the drawer's pre-fit square viewBox for a completed fit.
 *
 * Only for the LIVE standalone SVG: the hidden capture is `display:none`,
 * where `getBBox()` is 0, and a reaction is an inline scheme whose width the
 * reaction scale pass already owns.
 */
private const val CHEM_STANDALONE_FIT_JS =
    "function fitStandalone(s){try{" +
        "var bb=s.getBBox();if(!(bb.width>0)||!(bb.height>0))return;" +
        "var p=2,w=bb.width+2*p,h=bb.height+2*p;" +
        "var sc=Math.max(${CHEM_DISPLAY_MAX_PX}/Math.max(w,h),$CHEM_STANDALONE_MIN_SCALE);" +
        "s.setAttribute('viewBox',(bb.x-p)+' '+(bb.y-p)+' '+w+' '+h);" +
        "s.style.width=(w*sc)+'px';s.style.height='auto';s.style.maxWidth='100%';" +
        "s.setAttribute('data-fitted','1');" +
        "}catch(e){}}"

/**
 * Reaction SVG style: no fixed width and no molecule cap. ReactionDrawer
 * writes the scheme's natural pixel width/height onto the target; the inline
 * draw script then rescales that width (see [CHEM_REACTION_CONSTRAINT_JS])
 * and adds constraints. Replacing the whole `cssText` would drop the width
 * and let the SVG fall back to 300 px, ignoring the scale pass entirely.
 */
private const val CHEM_REACTION_STYLE =
    "max-width:100%;height:auto;flex:0 1 auto;"

/**
 * After-draw scale for the drawer's computed width. The live reaction options
 * scale the whole scheme's user units by 2.2 so each label clears Android's
 * 8 px minimum font size (see the reaction options in [chemBlockHtml]); this
 * 1/2.2 pass then brings the rendered SVG back to the width the row used to
 * occupy. Labels end up the same on-screen size as before, while their
 * proportion to the bonds stays the standalone 11/30. `height:auto` preserves
 * the viewBox aspect ratio.
 */
private const val CHEM_REACTION_SCALE = 0.773

/** Scale the drawer's width, then constrain it — never overwrite `cssText`. */
private const val CHEM_REACTION_CONSTRAINT_JS =
    "var rw=parseFloat(svg.style.width);if(rw>0){svg.style.width=(rw*$CHEM_REACTION_SCALE)+'px';}" +
        "svg.style.maxWidth='100%';svg.style.height='auto';svg.style.flex='0 1 auto';"

/**
 * Inline row for a whole reaction: the scheme and its product label
 * sit on one line. `white-space:nowrap` keeps a short reagent caption from
 * wrapping, and `justify-content:center` centres the scheme + label in the
 * full-width block instead of leaving it flush left. Deliberately NO
 * max-height: the row's measured height must reflect its real content so the
 * UI bound can actually fail on a stacked layout.
 */
private const val CHEM_REACTION_CONTAINER_STYLE =
    "display:flex;align-items:center;gap:4px;max-width:100%;white-space:nowrap;" +
        "justify-content:center;"

/**
 * Characters a SMILES string may contain before it is handed to JavaScript.
 * `>` is included so a three-part reaction SMILES (`reactants>reagents>products`)
 * reaches ReactionParser; it is safe because [jsStringLiteral] escapes every
 * `>` as `\u003e` inside the JS string literal. Anything else (a quote, a
 * semicolon, `<`, whitespace, …) is dropped: the SMILES is wrapped in a JS
 * string literal and an HTML fallback line, so a hostile value must never be
 * able to close either one.
 */
private val SMILES_ALLOWED_CHARS =
    ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" +
        "@.+-?!()[]{}#\\=\$:*%" + "/>").toSet()

/**
 * Any Kotlin string as a JSON string literal (quotes included) for inline JS.
 * `<`, `>` and `&` are escaped too: the literal sits inside a `<script>` tag,
 * where an unescaped `</script>` would end the element before JS ever runs.
 */
internal fun jsStringLiteral(value: String): String = buildString {
    append('"')
    value.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '<' -> append("\\u003c")
            '>' -> append("\\u003e")
            '&' -> append("\\u0026")
            else -> append(ch)
        }
    }
    append('"')
}

/**
 * The SMILES as a JSON string literal for inline JS. The allowlist runs
 * first, so a quote, a tag or a control character can never reach the JS
 * string; `%` is allowed for OpenSMILES two-digit ring closures.
 */
internal fun smilesJsLiteral(smiles: String): String =
    jsStringLiteral(smiles.filter { it in SMILES_ALLOWED_CHARS })

// PNG validation lives with the exporters (com.mcqapp.data.export.isPngDataUri):
// one strict check for the signature, shared by every renderer and writer.

/**
 * One chemistry block: the drawing (or a validated PNG when one is supplied),
 * its caption/formula line, and an inert text fallback shown only if the
 * JavaScript drawer is missing or rejects the SMILES. The fallback never
 * carries SVG or base64 data — only the formula and the SMILES characters.
 */
/** The product label that sits beside the scheme in the inline row. */
private fun reactionProductLabel(formula: String): String =
    "<span class=\"mcq-chem-product\" style=\"font-size:0.85em\">" +
        escapeHtmlData(formula) + "</span>"

private fun chemBlockHtml(
    element: ContentElement.ChemElement,
    index: Int,
    structureUnavailable: String,
    chemKey: String,
    chemColorCss: String
): String {
    val svgId = "mcq-chem-$index"
    val captureId = "$svgId-capture"
    val fallbackId = "mcq-chem-$index-fallback"
    // ReactionParser splits on '>' and requires exactly three parts, so a
    // reaction needs exactly two '>' (empty reagents/products are allowed:
    // "reactants>>" is the common case). A lone '>' is malformed: leave it on
    // the molecule path rather than letting ReactionParser throw.
    val isReaction = element.smiles.count { it == '>' } == 2
    val displayStyle = if (isReaction) CHEM_REACTION_STYLE else CHEM_DISPLAY_STYLE
    // The caption becomes the text above the arrow, so there is no separate
    // caption line. Keep it in the fallback too: a broken drawer must not
    // hide the reagent from a sighted reader.
    val visibleText = buildString {
        append(element.formula.takeIf { it.isNotBlank() } ?: element.smiles)
        if (isReaction && element.caption.isNotBlank()) append(' ').append(element.caption)
    }
    val caption = listOf(element.caption, element.formula)
        .filter { it.isNotBlank() }
        .joinToString(" ") { escapeHtmlData(it) }
    // In reaction mode the caption is the reagent text drawn above the arrow,
    // so there is no separate caption line under the diagram.
    val captionLine = if (isReaction || caption.isBlank()) "" else
        "<div class=\"mcq-chem-caption\">$caption</div>"
    val fallbackLine = buildString {
        if (structureUnavailable.isNotBlank()) append(escapeHtmlData(structureUnavailable)).append(": ")
        append(escapeHtmlData(visibleText))
    }

    // A validated PNG is safer and sharper than a redraw: emit it directly.
    // CSP already allows img-src data:, so no script is needed for this block.
    if (isPngDataUri(element.diagram)) {
        return buildString {
            if (isReaction) {
                append("<div class=\"mcq-chem mcq-chem-reaction\" style=\"")
                    .append(CHEM_REACTION_CONTAINER_STYLE).append("\">")
            } else {
                append("<div class=\"mcq-chem\">")
            }
            append("<img src=\"").append(escapeHtmlData(element.diagram))
                .append("\" alt=\"").append(escapeHtmlData(visibleText))
                .append("\" style=\"")
            if (isReaction) {
                append(CHEM_REACTION_STYLE)
            } else {
                append("max-width:100%;max-height:${CHEM_DISPLAY_MAX_PX}px;width:auto;")
                    .append("height:auto;vertical-align:middle;")
            }
            append("\"/>")
            if (isReaction && element.formula.isNotBlank()) append(reactionProductLabel(element.formula))
            append(captionLine)
            append("</div>")
        }
    }

    // textColorCss comes from the WebView's `color` (onSurface, always
    // rgba(...,1)), which SVG accepts as a paint value. It still goes through
    // the JSON escaper so no caller-supplied text can break out.
    // SmilesDrawer deep-merges options, so the built-in light/dark/custom
    // themes stay intact; 'mcq' only overrides C and H and falls back to C
    // for every other atom via ThemeManager.getColor.
    val atomColor = jsStringLiteral(chemColorCss)
    // In reaction mode the caption is textAbove in JS source, not HTML, so it
    // goes through the JSON escaper (which also neutralises '<', '>' and '&'
    // inside the inline <script>).
    val reagentText = jsStringLiteral(element.caption)

    return buildString {
        if (isReaction) {
            append("<div class=\"mcq-chem mcq-chem-reaction\" style=\"")
                .append(CHEM_REACTION_CONTAINER_STYLE).append("\">")
        } else {
            append("<div class=\"mcq-chem\">")
        }
        append("<svg id=\"$svgId\" style=\"$displayStyle\"></svg>")
        // The sample supplies the product as `formula`; it sits beside the
        // scheme so the whole equation reads on one line.
        if (isReaction && element.formula.isNotBlank()) append(reactionProductLabel(element.formula))
        // A second, hidden SVG is drawn with the bundled light theme so a
        // stored diagram has dark strokes on white export surfaces, whatever
        // theme the learner was using when the editor captured it.
        append("<svg id=\"$captureId\" style=\"display:none;\"></svg>")
        append(captionLine)
        append("<div id=\"$fallbackId\" class=\"mcq-chem-fallback\" style=\"display:none;\">")
        append(fallbackLine)
        append("</div>")
        append("<script>(function(){")
        append("var svg=document.getElementById('").append(svgId).append("');")
        append("var captureSvg=document.getElementById('").append(captureId).append("');")
        append("var fallback=document.getElementById('").append(fallbackId).append("');")
        append("function showFallback(){svg.style.display='none';if(fallback)fallback.style.display='';}")
        // SmilesDrawer injects a <style> element into every SVG it draws
        // (`.element`/`.sub` from SvgWrapper, `.text` for a reaction caption).
        // A <style> inside an SVG is a DOCUMENT-global stylesheet, so without
        // this every drawing on the page follows the LAST style injected. In
        // production each answer option gets its own WebView, so the leak that
        // matters happens INSIDE one page: a reaction draws its own hidden
        // light-theme capture after the live scheme, at 11 pt over 30-unit
        // bonds (the standalone defaults, now passed explicitly), so that
        // capture's rule would restyle the visible scheme — and any diagram
        // drawn later in the same WebView restyles everything before it.
        // Prefixing each selector with this SVG's id keeps the rules inside
        // their own drawing; querySelectorAll('style') also reaches the nested
        // reactant SVGs a reaction contains, and `#id .element` still matches
        // them as descendants. The capture clone keeps its id, so the scoped
        // selectors keep working inside the serialised PNG source. The guard
        // makes a second pass a no-op: without it `#id .element` would be
        // rewritten to `#id #id .element`.
        append("function scopeChemStyles(s){var id=s.id;if(!id)return;")
        append("var st=s.querySelectorAll('style');")
        append("for(var i=0;i<st.length;i++){var e=st[i];")
        append("if(e.textContent.indexOf('#'+id+' .')>=0)continue;")
        append("e.textContent=e.textContent.replace(/(^|[\\s,{}])\\.(element|sub|text)\\b/g,")
        append("'\$1#'+id+' .\$2');}}")
        append(CHEM_STANDALONE_FIT_JS)
        // Rasterise the hidden light-theme drawing so a structure authored in
        // the editor can be stored in ChemElement.diagram and travel through
        // every export with dark strokes on white surfaces. Serialise a CLONE
        // with the display style removed: the live SVG carries the theme's
        // text colour and its display cap, neither of which belongs in a PNG.
        append("function capture(){try{")
        append("var c=captureSvg.cloneNode(true);")
        append("c.removeAttribute('style');")
        append("var xml=new XMLSerializer().serializeToString(c);")
        append("var img=new Image();")
        append("img.onload=function(){try{")
        append("var canvas=document.createElement('canvas');")
        append("canvas.width=img.naturalWidth||300;canvas.height=img.naturalHeight||300;")
        append("canvas.getContext('2d').drawImage(img,0,0);")
        append("var png=canvas.toDataURL('image/png');")
        append("if(png.indexOf('data:image/png;base64,')===0 && window.").append(CHEM_DIAGRAM_BRIDGE).append("){")
        append("window.").append(CHEM_DIAGRAM_BRIDGE).append(".onDiagram(")
        append(jsStringLiteral(chemKey)).append(",png);}")
        append("}catch(e){}};")
        append("img.onerror=function(){};")
        append("img.src='data:image/svg+xml;base64,'+btoa(unescape(encodeURIComponent(xml)));")
        append("}catch(e){}}")
        append("try{")
        if (isReaction) {
            append("if(!window.SmilesDrawer||!window.SmilesDrawer.parseReaction)throw new Error('drawer');")
            // The whole scheme is drawn in user units 2.2x larger than the
            // size it finally renders at: the molecule options AND
            // ReactionDrawer's fixed chrome (spacing, plus signs, arrow),
            // which is spelled out below at the same k.
            // CHEM_REACTION_CONSTRAINT_JS then scales the SVG down by 1/k, so
            // the row keeps the size it used to have.
            //
            // Why the drawing is bigger rather than the text smaller:
            // Android WebView clamps any computed font size below its
            // minimum-font setting, and that setting defaults to 8 px
            // (https://developer.android.com/reference/android/webkit/WebSettings#setMinimumFontSize(int)).
            // The old live label was 3.7 pt = 4.93 px, so the device silently
            // drew it at 8 px: 8 px over a 10-unit bond against the
            // standalone's 14.67 px over 30 units, 1.6x too large — exactly
            // the "reaction letters look oversized" report. At k = 2.2 the
            // label is 8.1 pt = 10.8 px and the 0.8 * 8.1 pt caption is
            // 6.48 pt = 8.64 px, both clear of the floor; the SVG scale then
            // restores the old physical size.
            //
            // The proportions still match the standalone drawings: 8.1/22 =
            // 0.36818 against 11/30 = 0.36667 (0.4%), and fontSizeSmall 2.2
            // gives 2.2/8.1 ≈ 0.2716 against SvgWrapper's `.sub` rule at
            // 3/11 ≈ 0.2727. (2.1.1 defines `createSubSuperScripts` but never
            // calls it, so CH3 hydrogens are Unicode subscript characters and
            // the `.sub` rule is only a proportion guard.)
            // Every injected style is scoped to its own SVG (see
            // scopeChemStyles above), so this drawing's own hidden capture —
            // drawn next at 11 pt over 30-unit bonds — cannot restyle it.
            // width/height stay only for parity with the bundled API (inert
            // for an existing <svg> target). Terminal carbons are drawn in
            // BOTH the live and the hidden light-theme capture so on-screen
            // and exported schemes agree; the capture keeps its original
            // padding:2 drawer at full resolution.
            //
            // Every USER-SPACE length scales with the same k, not just the
            // fonts and the bond length: bondThickness 0.8 -> 1.76 and
            // padding 1 -> 2.2, and bondSpacing is supplied explicitly because
            // DrawerBase's default is a fixed 30 * 0.17 = 5.1 that does NOT
            // follow bondLength — at bondLength 22 it would draw double bonds
            // 2.2x tighter than before. The 1/k CSS scale then restores the
            // old physical size: a bond stroke of 0.8 * 2.2 = 1.76 user units
            // renders at 1.36 px (0.8 * 1.7), a double-bond gap of
            // 5.1 * 2.2 = 11.22 renders at 8.67 px (5.1 * 1.7), and the
            // padding of 1 * 2.2 = 2.2 renders at 1.7 px.
            //
            // Options.extend deep-merges `rxnOptions` into ReactionDrawer's
            // defaults, so `arrow.length` (4 * bondLength = 88) and the caption
            // fontSize (0.8 * 8.1) stay derived; only the fixed geometry is
            // given here, at the same k as the molecule options.
            //
            // The capture keeps ReactionDrawer's DEFAULT chrome
            // (`captureRxnOptions = {}`), because its molecules are drawn at
            // the 1x capture scale: the live 2.2-unit arrow at 1x would be a
            // 2.2 px arrow in the exported PNG.
            //
            // The capture options spell the molecule defaults out on purpose.
            // ReactionDrawer's constructor reads `moleculeOptions.bondLength`
            // and `moleculeOptions.fontSizeLarge` RAW, before SvgDrawer merges
            // its defaults, to build `arrow.length = 4 * bondLength` and the
            // caption `fontSize = 0.8 * fontSizeLarge`. Omit them and the
            // capture gets `viewBox="0 0 NaN …"`, a `NaN` arrow and no
            // product side — a PNG with no arrow. fontSizeSmall keeps the
            // capture's `.sub` rule at the default 3.
            append("var rxnOptions={spacing:22,plus:{size:19.8,thickness:2.2},")
            append("arrow:{headSize:13.2,thickness:2.2,margin:6.6}};")
            append("var captureRxnOptions={};")
            append("var molOptions={width:300,height:300,padding:2.2,bondThickness:1.76,bondSpacing:11.22,")
            append("bondLength:22,fontSizeLarge:8.1,fontSizeSmall:2.2,overlapResolutionIterations:2,")
            append("experimentalSSSR:true,terminalCarbons:true,")
            append("themes:{mcq:{C:").append(atomColor).append(",H:").append(atomColor).append("}}};")
            append("var captureMolOptions={width:300,height:300,padding:2,")
            append("bondLength:30,fontSizeLarge:11,fontSizeSmall:3,terminalCarbons:true};")
            append("window.SmilesDrawer.parseReaction(").append(smilesJsLiteral(element.smiles))
            append(",reaction=>{try{")
            append("new window.SmilesDrawer.ReactionDrawer(rxnOptions,molOptions)")
            append(".draw(reaction,svg,'mcq',null,").append(reagentText).append(",\"\",false);")
            append("scopeChemStyles(svg);")
            // ReactionDrawer sets the scheme's natural pixel width/height.
            // Rescale that width by 1/k, then add only the inline constraints
            // — never cssText, which would erase the width and fall back to
            // 300 px — and never touch the hidden capture SVG's style. The
            // scale shrinks the style width below the viewBox width.
            append(CHEM_REACTION_CONSTRAINT_JS)
            append("new window.SmilesDrawer.ReactionDrawer(captureRxnOptions,captureMolOptions)")
            append(".draw(reaction,captureSvg,'light',null,").append(reagentText).append(",\"\",false);")
            append("scopeChemStyles(captureSvg);")
            append("capture();")
            append("}catch(e){showFallback();}},err=>showFallback());")
        } else {
            append("if(!window.SmilesDrawer||!window.SmilesDrawer.parse)throw new Error('drawer');")
            append("window.SmilesDrawer.parse(").append(smilesJsLiteral(element.smiles))
            append(",tree=>{try{")
            // width/height are inert for an existing <svg> target (they size a
            // canvas target); they stay only for parity with the bundled API.
            append("new window.SmilesDrawer.SvgDrawer({width:300,height:300,padding:2,")
            append("themes:{mcq:{C:").append(atomColor).append(",H:").append(atomColor)
            append("}}}).draw(tree,svg,'mcq');")
            append("scopeChemStyles(svg);")
            // Crop the square viewBox to the drawing's real bounds and size it
            // from a minimum scale (CHEM_STANDALONE_FIT_JS), so a long chain
            // keeps the bond size a ring gets. The hidden capture is drawn into
            // its own display:none SVG, where getBBox() is 0: never fit it.
            append("fitStandalone(svg);")
            // The capture copy uses the built-in light theme: its conventional
            // dark C/H and heteroatom colours survive white export surfaces.
            append("new window.SmilesDrawer.SvgDrawer({width:300,height:300,padding:2})")
            append(".draw(tree,captureSvg,'light');")
            append("scopeChemStyles(captureSvg);")
            append("capture();")
            append("}catch(e){showFallback();}},err=>showFallback());")
        }
        append("}catch(e){showFallback();}")
        append("})();</script>")
        append("</div>")
    }
}

/**
 * Final page heights, keyed by the exact HTML.
 *
 * A WebView has no intrinsic height before the page loads, and MathJax
 * replaces every `<math>` with an SVG of a different height once typesetting
 * settles. A wrap-content WebView therefore measures twice on the frame its
 * question appears — raw MathML first, then the typeset SVG — which is what
 * made tables and formulas look like they were being built on screen.
 * Remembering the settled height lets content that comes back (next/previous
 * question, a row scrolled back into view, a list rebuilt) reserve its space
 * from the first frame, so nothing below it moves.
 */
internal object ContentHeightCache {
    private const val MAX_ENTRIES = 32

    // Access-ordered LRU: pages are re-visited far more often than created.
    private val heights = object : LinkedHashMap<String, Int>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(html: String): Int? = heights[html]

    @Synchronized
    fun put(html: String, heightPx: Int) {
        heights[html] = heightPx
    }
}

/**
 * One flowing HTML block for content holding formulas: text and equations
 * share the browser's inline layout, so `हल करें: [x²..] x का मान?` reads
 * as one sentence. Tables stay block-level; images scale to the width.
 * The page is transparent and inherits the theme's text color and size,
 * so the block never shows as a white box in dark mode. Pure so tests
 * can assert its shape.
 */
internal fun mixedContentHtml(
    elements: List<ContentElement>,
    fontSizePx: Float,
    textColorCss: String,
    structureUnavailable: String = "",
    chemKey: String = ""
): String {
    var chemIndex = 0
    val hasChem = elements.any { it is ContentElement.ChemElement }
    val body = buildString {
        for (element in elements) {
            when (element) {
                // Text runs may carry inline formatting the editor stored as
                // HTML (<sub>, <br/>, …), but they originate in imported files,
                // so they are sanitised: only allow-listed tags survive, with
                // their attributes dropped. This page runs with JavaScript
                // enabled for MathJax, so unescaped markup here would run.
                is ContentElement.TextElement ->
                    append(com.mcqapp.data.renderInlineHtml(element.text).replace("\n", "<br/>"))
                is ContentElement.MathElement -> append(stripMathAttributes(element.mathml))
                is ContentElement.TableElement -> {
                    append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\">")
                    for (row in element.rows) {
                        append("<tr>")
                        // Cells carry inline markup; pictures collapse to a
                        // marker and formulas go through the same strip as a
                        // MathElement, so MathJax typesets them
                        // (consolidated_bugs.md G1-G3).
                        for (cell in row) {
                            append("<td>").append(tableCellHtml(cell) { stripMathAttributes(it) })
                            append("</td>")
                        }
                        append("</tr>")
                    }
                    append("</table>")
                }
                is ContentElement.ImageElement -> append("<img src=\"")
                    .append(escapeHtmlData(element.src))
                    .append("\" style=\"max-width:100%;height:auto;vertical-align:middle;\"/>")
                is ContentElement.ChemElement -> {
                    append(chemBlockHtml(element, chemIndex, structureUnavailable, chemKey, textColorCss))
                    chemIndex++
                }
            }
        }
    }
    // Loaded once per chemistry page, before the inline draw scripts in the
    // body run. The asset is local and offline, like MathJax.
    val chemScript = if (hasChem) "<script src=\"$SMILES_DRAWER_ASSET_URL\"></script>" else ""
    // The policy mirrors what this WebView can actually load: images are
    // data URIs (the picker encodes them, imports downscale to them), so
    // there is no file: image or stylesheet to allow — allowFileAccess=false
    // rules out real files and this page never references an android_asset
    // image or CSS. script-src keeps file: for the bundled MathJax and
    // SmilesDrawer assets, and everything else stays off the network,
    // same as the exports.
    return """
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' file:; img-src data:; style-src 'unsafe-inline'">
        <script>
        // Single-$ TeX delimiters stay OFF: an ordinary sentence containing
        // two "$" signs typeset its middle as garbled math, and the HTML
        // export never enabled them (consolidated_bugs.md G9). Formulas are
        // stored and rendered as MathML blocks.
        </script>
        <script src="$MATHJAX_ASSET_URL"></script>
        $chemScript
        <style>
        html,body{margin:0;padding:0;background:transparent;color:$textColorCss;font-size:${fontSizePx}px}
        body{padding:0 0 0.25em}
        table{border-collapse:collapse;margin:4px 0}
        img{vertical-align:middle}
        .mcq-chem{margin:4px 0}
        .mcq-chem-caption{font-size:0.9em}
        .mcq-chem-fallback{color:inherit}
        </style>
        </head>
        <body>$body</body>
        <script>
        // Report the height the page settles at, not the height it has while
        // the browser is still working: MathJax's own promise resolves after
        // the initial typeset pass, and images (data: URIs) are in by the
        // load event, so a single report then is final. Without this the
        // Compose side sees the raw-MathML height, then the typeset height,
        // and the question visibly rebuilds.
        (function () {
          var reported = -1;
          // Nothing may be reported before the typeset pass has finished:
          // raw MathML is much taller than its SVG, and an early report would
          // pin that height, reveal the block, and then move it again.
          var settled = false;
          // The true content bottom, not just the body's border box: on Android
          // WebView an inline MathJax SVG can leave its descent outside the
          // text line box, so body.getBoundingClientRect().height misses a few
          // px and the pinned WebView clips the bottom of a subscript. Union
          // every rendered descendant box instead. Deliberately not
          // scrollHeight: that is at least the viewport height, and the
          // viewport is the height Compose pins us to — a block that was ever
          // pinned too tall could then never shrink, leaving blank space after
          // the question.
          //
          // A box only counts when it is painted in the normal flow: MathJax's
          // <mjx-assistive-mml> is position:absolute, clipped to 1 px and
          // invisible, yet its MathML content can be far TALLER than the
          // visible SVG (a matrix especially), so an unfiltered union pins
          // several px of blank space. Its descendants are position:static, so
          // the whole ancestry has to be checked, not just the element itself.
          function outOfFlow(el, body) {
            for (var n = el; n && n !== body; n = n.parentElement) {
              if (n.tagName && n.tagName.toUpperCase() === 'MJX-ASSISTIVE-MML') return true;
              var st = getComputedStyle(n);
              if (st.position === 'absolute' || st.position === 'fixed' ||
                  st.visibility === 'hidden' || st.display === 'none') return true;
            }
            return false;
          }
          function contentHeight() {
            var body = document.body, b = body.getBoundingClientRect(), bottom = b.height;
            var els = body.getElementsByTagName('*');
            for (var i = 0; i < els.length; i++) {
              var el = els[i], r = el.getBoundingClientRect();
              if (!(r.width > 0 && r.height > 0)) continue;
              var d = r.bottom - b.top;
              // Computed style is the expensive read on this page, so only a
              // box that would actually raise the max is inspected at all:
              // most pages then do no style reads whatsoever.
              if (d <= bottom) continue;
              if (outOfFlow(el, body)) continue;
              bottom = d;
            }
            return Math.ceil(bottom);
          }
          function report() {
            if (!settled) return;
            try {
              var h = contentHeight();
              if (h > 0 && h !== reported && window.$CONTENT_HEIGHT_BRIDGE) {
                reported = h;
                window.$CONTENT_HEIGHT_BRIDGE.onHeight(h);
              }
            } catch (e) { /* a broken bridge must not break the page */ }
          }
          function settle() {
            settled = true;
            report();
          }
          function afterTypeset() {
            var mj = window.MathJax;
            if (mj && mj.startup && mj.startup.promise && mj.startup.promise.then) {
              mj.startup.promise.then(settle, settle);
            } else if (mj && mj.typesetPromise) {
              mj.typesetPromise().then(settle, settle);
            } else {
              settle();
            }
          }
          // Width changes (rotation, split screen, a font-scale change) reflow
          // the page; the new height has to reach Compose too.
          window.addEventListener('resize', function () { setTimeout(report, 0); });
          if (document.readyState === 'complete') {
            setTimeout(afterTypeset, 0);
          } else {
            window.addEventListener('load', function () { setTimeout(afterTypeset, 0); });
          }
        })();
        </script>
        </html>
        """.trimIndent()
}

/** Compose [androidx.compose.ui.graphics.Color] as a CSS `rgba()` string. */
internal fun androidx.compose.ui.graphics.Color.toCssRgba(): String {
    fun channel(v: Float) = (v * 255 + 0.5f).toInt().coerceIn(0, 255)
    val a = if (alpha >= 1f) "1" else alpha.toString()
    return "rgba(${channel(red)},${channel(green)},${channel(blue)},$a)"
}

/**
 * MathJax's MathML input is strict about the root `<math>` element and
 * doesn't handle MathLive's `<semantics>`/`<annotation>` wrapper. Strip
 * `display`/`xmlns` from `<math>`, unwrap `<semantics>` (dropping
 * `<annotation>`), and ensure an `<mrow>` wrapper remains.
 */
internal fun stripMathAttributes(mathml: String): String {
    val trimmed = mathml.trim()
    // Surrounding prose is kept, escaped as text (as splitTextRuns does), not
    // dropped: the function used to keep only the first <math> block and
    // silently discard everything around it (consolidated_bugs.md G7).
    val firstMatch = Regex("<math[\\s>].*?</math\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(trimmed)
    val prefix = firstMatch?.let { trimmed.substring(0, it.range.first) }.orEmpty()
    val suffix = firstMatch?.let { trimmed.substring(it.range.last + 1) }.orEmpty()
    val block = firstMatch?.value
        // No <math> block: the field is not MathML at all. Emit the content
        // escaped as text — wrapping it in <math> would hand a hostile
        // payload to the JS-enabled page as live markup.
        ?: return listOf(prefix, suffix).filter { it.isNotBlank() }
            .joinToString(" ") { com.mcqapp.data.sanitizeMathml(it) }
            .ifBlank { com.mcqapp.data.sanitizeMathml(trimmed) }
    var result = block
        .replace(Regex("<math\\s+[^>]*>", RegexOption.IGNORE_CASE)) { "<math>" }
        .replace(Regex("</math\\s*>", RegexOption.IGNORE_CASE)) { "</math>" }
        .replace(Regex("<semantics\\s*>", RegexOption.IGNORE_CASE)) { "" }
        .replace(Regex("</semantics\\s*>", RegexOption.IGNORE_CASE)) { "" }
        .replace(Regex("<annotation\\s+[^>]*>.*?</annotation\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))) { "" }
    // Inner tags are reduced to bare, attribute-free, allow-listed tags: an
    // imported bank could otherwise smuggle event handlers into a JS-enabled
    // WebView. Unknown tags are escaped as text.
    val (sanitized, escapedUnknownTag) = com.mcqapp.data.sanitizeMathmlReporting(result)
    if (escapedUnknownTag) {
        // Hostile or malformed markup: keep it inert and stop there.
        // Tokenizing escaped attacker text would only re-typeset it.
        return sanitized
    }
    // Well-formed MathML may still carry bare text (compact DOCX output),
    // which MathJax rejects with "Unexpected text node". normalizeMathText
    // tokenizes it; re-emitting tags verbatim is safe because the sanitizer
    // has already stripped every attribute.
    result = com.mcqapp.data.normalizeMathText(sanitized)
    val inner = result.removePrefix("<math>").removeSuffix("</math>").trim()
    val wrapped = if (inner.startsWith("<mrow", ignoreCase = true)) {
        "<math>$inner</math>"
    } else {
        "<math><mrow>$inner</mrow></math>"
    }
    return buildString {
        if (prefix.isNotBlank()) append(com.mcqapp.data.escapeHtmlText(prefix))
        append(wrapped)
        if (suffix.isNotBlank()) append(com.mcqapp.data.escapeHtmlText(suffix))
    }
}


// --- table cells (consolidated_bugs.md G1-G3) ---
//
// Cells deliberately carry inline markup (DOCX paraHtml, Anki's parseTable,
// the Anki export), but two of their rich cases have no place in a plain text
// pipeline: a picture (G2 — the DOCX reader emits a data-URI <img> into the
// cell, which text renderers dumped as base64) and a formula (G3 — raw
// MathML outside the mixed view's MathJax).

internal const val CELL_IMAGE_MARKER = "[image]"
internal const val CELL_FORMULA_MARKER = "[formula]"

private val cellImgPattern = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
private val cellMathPattern =
    Regex("<math\\b.*?</math\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/**
 * The cell on a surface that can show neither pictures nor formulas: both
 * collapse to a visible marker, the rest of the markup stays for the inline
 * pipeline.
 */
internal fun sanitizeTableCell(cell: String): String =
    cellImgPattern.replace(cellMathPattern.replace(cell, CELL_FORMULA_MARKER), CELL_IMAGE_MARKER)

/**
 * The cell as HTML: the markup between formulas goes through the same inline
 * pipeline as a TextElement (newlines as breaks, G10), each formula through
 * [mathHtml] (the mixed view's MathJax strip, the HTML export's sanitizer),
 * pictures as a marker.
 */
internal fun tableCellHtml(cell: String, mathHtml: (String) -> String): String {
    val marked = cellImgPattern.replace(cell, CELL_IMAGE_MARKER)
    val out = StringBuilder()
    var pos = 0
    for (match in cellMathPattern.findAll(marked)) {
        out.append(com.mcqapp.data.renderInlineHtml(
            marked.substring(pos, match.range.first).replace("\n", "<br/>")
        ))
        out.append(mathHtml(match.value))
        pos = match.range.last + 1
    }
    out.append(com.mcqapp.data.renderInlineHtml(
        marked.substring(pos).replace("\n", "<br/>")
    ))
    return out.toString()
}

/** Same escaping as the exporters: one escaper, not four copies. */
private fun escapeHtmlData(s: String): String = com.mcqapp.data.escapeHtmlText(s)

@Composable
private fun MixedContentView(
    elements: List<ContentElement>,
    textStyle: TextStyle?,
    modifier: Modifier = Modifier,
    chemKey: String = "",
    onChemDiagram: (key: String, pngDataUri: String) -> Unit = { _, _ -> }
) {
    // The WebView is keyed by the page, so a recomposition must not leave it
    // holding a stale callback or a stale key from the question it first drew.
    val currentChemKey by rememberUpdatedState(chemKey)
    val currentOnChemDiagram by rememberUpdatedState(onChemDiagram)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val colorScheme = MaterialTheme.colorScheme
    val defaultSize = MaterialTheme.typography.bodyLarge.fontSize
    // Visible fallback copy is resolved here (the composable has the locale)
    // and handed to the pure HTML builder, which only escapes and embeds it.
    val chemUnavailable = stringResource(R.string.chem_structure_unavailable)
    val targetHtml = remember(elements, textStyle, colorScheme, density, chemUnavailable, chemKey) {
        // WebView CSS px are density-independent like dp: scale the Sp
        // value by the font scale only. toPx() would also multiply by
        // the screen density and render several times too big, while
        // ignoring fontScale would stop honouring system font size.
        val cssPx = (textStyle?.fontSize?.takeIf { it.isSp }?.value
            ?: defaultSize.value) * density.fontScale
        val color = textStyle?.color?.takeUnless { it == androidx.compose.ui.graphics.Color.Unspecified }
            ?: colorScheme.onSurface
        mixedContentHtml(elements, cssPx, color.toCssRgba(), chemUnavailable, currentChemKey)
    }
    // Coalesce preview updates: the WebView is keyed by the page, so a page
    // rebuilt on every keystroke was destroyed and recreated (with its
    // renderer) for each letter typed — question_editor_audit.md M-09. The
    // last page still wins; only the intermediate ones are skipped.
    var html by remember { mutableStateOf(targetHtml) }
    LaunchedEffect(targetHtml) {
        if (html != targetHtml) {
            delay(180)
            html = targetHtml
        }
    }
    // The settled height of this exact page, if it has been rendered before.
    // Remembered per page so a resize of the block (and everything below it)
    // only ever happens the first time a page is seen.
    var heightPx by remember(html) { mutableStateOf(ContentHeightCache.get(html)) }
    // A page that cannot report (no JS, a missing asset) must still appear.
    var revealUnmeasured by remember(html) { mutableStateOf(false) }
    LaunchedEffect(html) {
        if (heightPx == null) {
            // Generous: this only fires when the bridge never does, and a
            // blank block would be worse than the old wrap-content layout.
            delay(1500)
            if (heightPx == null) revealUnmeasured = true
        }
    }
    val measuredPx = heightPx
    val showContent = measuredPx != null || revealUnmeasured
    // The rendered page is one Android view with no accessible text: merge it
    // into one labelled node, but make the label carry the real content (text,
    // cells, formulas) instead of a fixed description that said nothing.
    // Modifier only — the WebView's factory, bridge and teardown are untouched.
    // Parsing MathML/HTML to build the spoken text is expensive; do it once
    // per content change, not on every recomposition (scrolling, theme toggles).
    val fallback = remember(elements) {
        checkNotNull(accessibleContentLabelFor(elements)) {
            "MixedContentView requires formula-bearing content"
        }
    }
    val contentLabel = mixedContentDescription(stringResource(R.string.a11y_rich_content), fallback)
    // Recreate the WebView when the page changes (e.g. theme toggle),
    // so the colors baked into the HTML never go stale.
    androidx.compose.runtime.key(html) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    // Hidden until the page reports a settled height (see the
                    // update block); a cached height reveals it at once.
                    alpha = 0f
                    settings.javaScriptEnabled = true
                    settings.loadWithOverviewMode = true
                    // Assets and resources stay reachable without this; it only
                    // stops the page reading arbitrary file:// paths.
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    // The page is pinned to its content height, so it has
                    // nothing to scroll; leaving it scrollable would let the
                    // WebView eat drags meant for the question list.
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                    // The page content comes from imported files. Refuse to
                    // navigate anywhere, so a link or injected markup cannot
                    // take over the frame.
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: android.webkit.WebResourceRequest
                        ): Boolean = true

                        @Deprecated("Required for API < 24")
                        override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean = true
                    }
                    // The page reports the height it settles at; see the
                    // script in mixedContentHtml. It runs on the bridge's
                    // thread, so hop back to the main thread and set state.
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onHeight(px: Int) {
                                this@apply.post {
                                    val clamped = px.coerceIn(1, MAX_CONTENT_HEIGHT_PX)
                                    ContentHeightCache.put(html, clamped)
                                    heightPx = clamped
                                }
                            }
                        },
                        CONTENT_HEIGHT_BRIDGE
                    )
                    // The captured PNG travels through a dedicated bridge: the
                    // height bridge accepts only an Int. The JS bridge thread
                    // hops back to main before the app callback runs.
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onDiagram(key: String, png: String) {
                                this@apply.post { currentOnChemDiagram(key, png) }
                            }
                        },
                        CHEM_DIAGRAM_BRIDGE
                    )
                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
            },
            // Every WebView owns a renderer and a JS heap for the life of the
            // process unless it is destroyed. Without this, scrolling a
            // maths-heavy list leaked one per row scrolled into view, and a
            // theme or font-scale change leaked every visible one at once.
            onRelease = { webView ->
                webView.onPause()
                webView.stopLoading()
                webView.removeJavascriptInterface(CONTENT_HEIGHT_BRIDGE)
                webView.removeJavascriptInterface(CHEM_DIAGRAM_BRIDGE)
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                webView.destroy()
            },
            // Hidden until it has something final to show: the intermediate
            // layouts (empty view, raw MathML, typeset SVG) are what look like
            // the question being built on screen.
            update = { webView ->
                webView.alpha = if (showContent) 1f else 0f
            },
            modifier = modifier
                .padding(vertical = 4.dp)
                .semantics(mergeDescendants = true) { contentDescription = contentLabel }
                .then(
                    when {
                        // CSS px in this page are density-independent like dp
                        // (initial-scale=1 and device-width), so the reported
                        // number is already the height in dp.
                        measuredPx != null -> Modifier.height(measuredPx.dp)
                        // Exactly pinned: wrapping content here would measure
                        // the page mid-load, which is the jump being removed.
                        !revealUnmeasured -> Modifier.height(1.dp)
                        else -> Modifier
                    }
                )
        )
    }
}
