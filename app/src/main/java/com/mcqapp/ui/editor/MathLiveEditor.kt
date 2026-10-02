package com.mcqapp.ui.editor

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

// TRIAL prototype: MathLive WYSIWYG formula editor in a WebView.
// Caller passes LaTeX in and receives MathML out (via mathMlToLatex).

internal const val MATHLIVE_JS_URL = "file:///android_asset/mathlive/mathlive.min.js"
internal const val MATHLIVE_FONTS_CSS_URL = "file:///android_asset/mathlive/mathlive-fonts.css"
internal const val MATHLIVE_FONTS_DIR = "file:///android_asset/mathlive/fonts/"

// initialLatexJson is the already-JSON-quoted LaTeX (quoted by the caller
// with JSONObject.quote, so this stays a pure string builder for tests).
internal fun mathLiveHtml(initialLatexJson: String): String =
    "<!DOCTYPE html><html><head>" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<link rel=\"stylesheet\" href=\"" + MATHLIVE_FONTS_CSS_URL + "\">" +
        "</head><body style=\"margin:0;background:transparent;\">" +
        "<math-field id=\"mf\" style=\"min-height:120px;font-size:18px;\"></math-field>" +
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
        "mf.addEventListener('input', function () {" +
        "if (t) clearTimeout(t);" +
        "t = setTimeout(function () { Android.onMathMl(mf.getValue('math-ml')); }, 400);" +
        "});" +
        "});" +
        "</script></body></html>"

@Composable
fun MathLiveEditor(initialLatex: String, onMathMl: (String) -> Unit, modifier: Modifier = Modifier) {
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
            view.loadDataWithBaseURL(
                null,
                mathLiveHtml(JSONObject.quote(initialLatex)),
                "text/html",
                "utf-8",
                null
            )
            view
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
