package com.mcqapp.util

import android.webkit.WebView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * exactly as they did before rich content existed.
 */
@Composable
fun ContentElements(
    elements: List<ContentElement>,
    modifier: Modifier = Modifier,
    textStyle: TextStyle? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
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
                is ContentElement.MathElement -> MathElementView(element)
            }
        }
    }
}

/**
 * A text run. A null [textStyle] omits the style argument so the Text uses the
 * theme default, exactly as a bare `Text(text)` did before rich content.
 */
@Composable
private fun ElementText(
    text: String,
    textStyle: TextStyle?,
    maxLines: Int,
    overflow: TextOverflow,
    modifier: Modifier = Modifier
) {
    if (textStyle != null) {
        Text(text, style = textStyle, maxLines = maxLines, overflow = overflow, modifier = modifier)
    } else {
        Text(text, maxLines = maxLines, overflow = overflow, modifier = modifier)
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
 * MathML via a WebView running MathJax. The script loads from a CDN, so a
 * formula needs a network connection to render; offline it shows the raw
 * MathML markup.
 */
@Composable
private fun MathElementView(element: ContentElement.MathElement) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.loadWithOverviewMode = true
                loadDataWithBaseURL(
                    null,
                    """
                    <html>
                    <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <script>
                    MathJax = { tex: { inlineMath: [['$', '$']] } };
                    </script>
                    <script src="https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js"></script>
                    </head>
                    <body>${element.mathml}</body>
                    </html>
                    """.trimIndent(),
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        },
        modifier = Modifier.padding(vertical = 4.dp)
    )
}
