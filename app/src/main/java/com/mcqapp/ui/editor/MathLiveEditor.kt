package com.mcqapp.ui.editor

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

// TRIAL prototype: MathLive WYSIWYG formula editor in a WebView.
// Caller passes LaTeX in and receives MathML out (via mathMlToLatex).

internal const val MATHLIVE_JS_URL = "file:///android_asset/mathlive/mathlive.min.js"
internal const val MATHLIVE_FONTS_CSS_URL = "file:///android_asset/mathlive/mathlive-fonts.css"
internal const val MATHLIVE_FONTS_DIR = "file:///android_asset/mathlive/fonts/"

/** Formula size when the app is at its default text scale. */
internal const val DEFAULT_MATHLIVE_FONT_PX = 18

// initialLatexJson is the already-JSON-quoted LaTeX (quoted by the caller
// with JSONObject.quote, so this stays a pure string builder for tests).
// fontPx follows the app's text-size setting, which the page used to ignore
// by hardcoding 18px while the rest of the editor honoured FontScale.
internal fun mathLiveHtml(initialLatexJson: String, fontPx: Int = DEFAULT_MATHLIVE_FONT_PX): String =
    "<!DOCTYPE html><html><head>" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<link rel=\"stylesheet\" href=\"" + MATHLIVE_FONTS_CSS_URL + "\">" +
        "</head><body style=\"margin:0;background:transparent;\">" +
        "<math-field id=\"mf\" style=\"min-height:120px;font-size:" + fontPx + "px;\"></math-field>" +
        "<script src=\"" + MATHLIVE_JS_URL + "\"></script>" +
        "<script>" +
        "document.addEventListener('DOMContentLoaded', function () {" +
        "var mf = document.getElementById('mf');" +
        "if (window.MathLive) {" +
        "MathLive.MathfieldElement.fontsDirectory = \"" + MATHLIVE_FONTS_DIR + "\";" +
        "MathLive.MathfieldElement.soundsDirectory = null;" +
        "}" +
        "mf.setValue(" + initialLatexJson + ");" +
        "var t = null;" +
        // A programmatic set must not be echoed back as if the user had typed
        // it, or a recycled block would write one formula into another.
        "var applying = false;" +
        "window.setLatex = function (tex) {" +
        "applying = true;" +
        "mf.setValue(tex);" +
        "setTimeout(function () { applying = false; }, 0);" +
        "};" +
        "mf.addEventListener('input', function () {" +
        "if (applying) return;" +
        "if (t) clearTimeout(t);" +
        "t = setTimeout(function () { Android.onMathMl(mf.getValue('math-ml')); }, 400);" +
        "});" +
        "});" +
        "</script></body></html>"

/**
 * Whether the field's own value must be pushed back into the WebView.
 *
 * Pure so it can be tested: pushing unconditionally would reset the caret on
 * every recomposition, and never pushing leaves a recycled WebView showing the
 * previous block's formula.
 */
internal fun shouldPushLatex(current: String?, incoming: String): Boolean =
    current != null && current != incoming

@Composable
fun MathLiveEditor(initialLatex: String, onMathMl: (String) -> Unit, modifier: Modifier = Modifier) {
    val fontScale = com.mcqapp.util.FontScale.LocalScale.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fontPx = with(density) {
        (16 * fontScale.coerceAtLeast(0.8f)).dp.roundToPx().coerceIn(12, 96)
    }
    val initialJson = remember(initialLatex) { JSONObject.quote(initialLatex) }
    AndroidView(
        // The virtual keyboard panel is absolutely positioned at the
        // bottom of the page; the WebView must be tall enough to show
        // the field plus the whole keyboard without clipping.
        modifier = modifier.height(520.dp),
        factory = { ctx ->
            val view = WebView(ctx)
            view.settings.javaScriptEnabled = true
            view.settings.allowFileAccess = true
            view.setBackgroundColor(0x00000000)
            view.addJavascriptInterface(object {
                @JavascriptInterface
                fun onMathMl(mml: String) {
                    // Bridge callbacks arrive off the main thread.
                    view.post { onMathMl(mml) }
                }
            }, "Android")
            view.tag = initialLatex
            view.loadDataWithBaseURL(
                null,
                mathLiveHtml(initialJson, fontPx),
                "text/html",
                "utf-8",
                null
            )
            view
        },
        // AndroidView's factory runs once. Without this, a block that moved
        // or changed from outside kept showing the previous formula.
        update = { view ->
            if (shouldPushLatex(view.tag as? String, initialLatex)) {
                view.tag = initialLatex
                view.evaluateJavascript("window.setLatex && window.setLatex(${initialJson});", null)
            }
        },
        // Same reason as the preview WebView: without an explicit teardown the
        // renderer and JS heap survive for the life of the process, once per
        // math block the editor has ever shown.
        onRelease = { webView ->
            webView.removeJavascriptInterface("Android")
            webView.onPause()
            webView.stopLoading()
            webView.loadUrl("about:blank")
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    )
}
