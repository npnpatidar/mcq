package com.mcqapp.util

import android.webkit.WebView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mcqapp.domain.ContentElement

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
    overflow: TextOverflow = TextOverflow.Clip
) {
    if (elements.any { it is ContentElement.MathElement }) {
        MixedContentView(elements, textStyle, modifier)
        return
    }
    if (elements.size == 1 && elements[0] is ContentElement.TextElement) {
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
            Row {
                row.forEach { cell ->
                    Text(
                        cell,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .weight(1f)
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
    textColorCss: String
): String {
    val body = buildString {
        for (element in elements) {
            when (element) {
                // Text runs are already HTML (`<sub>`, `<br/>`, …): the
                // browser is lenient with bare `&`/`<`, as Anki is.
                is ContentElement.TextElement ->
                    append(element.text.replace("\n", "<br/>"))
                is ContentElement.MathElement -> append(element.mathml)
                is ContentElement.TableElement -> {
                    append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\">")
                    for (row in element.rows) {
                        append("<tr>")
                        for (cell in row) append("<td>").append(escapeHtmlData(cell)).append("</td>")
                        append("</tr>")
                    }
                    append("</table>")
                }
                is ContentElement.ImageElement -> append("<img src=\"")
                    .append(escapeHtmlData(element.src))
                    .append("\" style=\"max-width:100%;height:auto;vertical-align:middle;\"/>")
            }
        }
    }
    return """
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <script>
        MathJax = { tex: { inlineMath: [['$', '$']] } };
        </script>
        <script src="$MATHJAX_ASSET_URL"></script>
        <style>
        html,body{margin:0;padding:0;background:transparent;color:$textColorCss;font-size:${fontSizePx}px}
        table{border-collapse:collapse;margin:4px 0}
        img{vertical-align:middle}
        </style>
        </head>
        <body>$body</body>
        </html>
        """.trimIndent()
}

/** Compose [androidx.compose.ui.graphics.Color] as a CSS `rgba()` string. */
internal fun androidx.compose.ui.graphics.Color.toCssRgba(): String {
    fun channel(v: Float) = (v * 255 + 0.5f).toInt().coerceIn(0, 255)
    val a = if (alpha >= 1f) "1" else alpha.toString()
    return "rgba(${channel(red)},${channel(green)},${channel(blue)},$a)"
}

private fun escapeHtmlData(s: String): String = buildString(s.length) {
    s.forEach { ch ->
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(ch)
        }
    }
}

@Composable
private fun MixedContentView(
    elements: List<ContentElement>,
    textStyle: TextStyle?,
    modifier: Modifier = Modifier
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val colorScheme = MaterialTheme.colorScheme
    val defaultSize = MaterialTheme.typography.bodyLarge.fontSize
    val html = remember(elements, textStyle, colorScheme, density) {
        // WebView CSS px are density-independent like dp: scale the Sp
        // value by the font scale only. toPx() would also multiply by
        // the screen density and render several times too big, while
        // ignoring fontScale would stop honouring system font size.
        val cssPx = (textStyle?.fontSize?.takeIf { it.isSp }?.value
            ?: defaultSize.value) * density.fontScale
        val color = textStyle?.color?.takeUnless { it == androidx.compose.ui.graphics.Color.Unspecified }
            ?: colorScheme.onSurface
        mixedContentHtml(elements, cssPx, color.toCssRgba())
    }
    // Recreate the WebView when the page changes (e.g. theme toggle),
    // so the colors baked into the HTML never go stale.
    androidx.compose.runtime.key(html) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.loadWithOverviewMode = true
                    // The MathJax bundle lives under file:///android_asset.
                    settings.allowFileAccess = true
                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
            },
            modifier = modifier.padding(vertical = 4.dp)
        )
    }
}
