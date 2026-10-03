package com.mcqapp.ui.editor

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
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
//
// It is in **CSS pixels**, which are density-independent: passing device
// pixels here made the field (and MathLive's virtual keyboard, which sizes
// itself from the font) render at ~48px on a 3x screen, so the keyboard was
// taller than the WebView and got clipped.
internal fun mathLiveHtml(initialLatexJson: String, fontPx: Int = DEFAULT_MATHLIVE_FONT_PX): String =
    "<!DOCTYPE html><html><head>" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<link rel=\"stylesheet\" href=\"" + MATHLIVE_FONTS_CSS_URL + "\">" +
        "</head><body style=\"margin:0;background:transparent;overflow:auto;\">" +
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
        // Keep the caret where the user left it: a bare setValue drops the
        // selection, so the next character lands at the start of the field,
        // which reads as the formula deleting what was already typed.
        "var pos = null;" +
        "try { pos = mf.selection.position; } catch (e) {}" +
        "mf.setValue(tex);" +
        "try { if (pos !== null) mf.selection.position = pos; } catch (e) {}" +
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
 * Formula font size for the page, in **CSS pixels**.
 *
 * CSS pixels are density-independent, so this must never be computed from
 * `roundToPx()`: on a 3x screen that produced a 48px field, and since
 * MathLive sizes its virtual keyboard from the font size the panel grew past
 * the WebView and was clipped. The scale is clamped so the keyboard stays
 * inside the editor however large the user's text setting is.
 */
internal fun mathLiveFontPx(fontScale: Float): Int =
    (16f * fontScale.coerceIn(0.8f, 1.6f)).roundToInt().coerceIn(12, 26)

/**
 * Whether a programmatic write into the field is allowed at all.
 *
 * Once the user has typed in this field, they own it: writing to it again can
 * only destroy their input. A bare `setValue` also drops the selection, so the
 * next character lands at the start of the field and the formula appears to
 * delete what was already there — which is exactly what was reported. So this
 * is a one-way latch: once [userEdited] is true, the host never writes again
 * for the life of this WebView. A different block gets a different WebView
 * (the block key includes its position and kind), so nothing is left stale.
 *
 * Before the user has touched it, an external change is still pushed, which is
 * what makes loading or restoring a formula work.
 */
internal fun shouldPushLatex(
    current: String?,
    incoming: String,
    userEdited: Boolean
): Boolean = !userEdited && current != null && current != incoming

@Composable
fun MathLiveEditor(
    initialLatex: String,
    onMathMl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val fontScale = com.mcqapp.util.FontScale.LocalScale.current
    val fontPx = mathLiveFontPx(fontScale)
    val initialJson = remember(initialLatex) { JSONObject.quote(initialLatex) }
    // Latches on the first keystroke; see [shouldPushLatex].
    val userEdited = remember { mutableStateOf(false) }
    AndroidView(
        // The virtual keyboard panel is absolutely positioned at the
        // bottom of the page; the WebView must be tall enough to show
        // the field plus the whole keyboard without clipping.
        // Tall enough for the formula plus MathLive's keyboard panel; the
        // page scrolls if the layout is bigger than that.
        modifier = modifier.height(560.dp),
        factory = { ctx ->
            val view = WebView(ctx)
            view.settings.javaScriptEnabled = true
            view.settings.allowFileAccess = true
            view.setBackgroundColor(0x00000000)
            view.addJavascriptInterface(object {
                @JavascriptInterface
                fun onMathMl(mml: String) {
                    // Bridge callbacks arrive off the main thread.
                    view.post {
                        userEdited.value = true
                        onMathMl(mml)
                    }
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
            if (shouldPushLatex(view.tag as? String, initialLatex, userEdited.value)) {
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
