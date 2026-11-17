package com.mcqapp.ui.editor

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import com.mcqapp.R
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
        // Every input crosses the bridge immediately (consolidated_bugs.md
        // J3): the old 400 ms debounce left the last keystrokes inside the
        // WebView, so save() and Prev/Next snapshotted a stale formula and
        // the dirty-check could read a pre-edit signature. Formulas are
        // short; serialising one per keystroke is cheap next to losing an
        // edit. The `applying` guard still stops a programmatic set from
        // echoing back.
        "mf.addEventListener('input', function () {" +
        "if (applying) return;" +
        "Android.onMathMl(mf.getValue('math-ml'));" +
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
    /**
     * Stable identity of the block this WebView belongs to. The JS bridge is
     * installed once by [AndroidView.factory] and never rebound, so it must
     * carry the block's id — never its list position, which changes on reorder.
     */
    blockId: String,
    onMathMl: (blockId: String, mml: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val fontScale = com.mcqapp.util.FontScale.LocalScale.current
    val fontPx = mathLiveFontPx(fontScale)
    val initialJson = remember(initialLatex) { JSONObject.quote(initialLatex) }
    // Latches on the first keystroke; see [shouldPushLatex].
    val userEdited = remember { mutableStateOf(false) }
    // The WebView is one view with no semantics of its own, so a static label
    // left TalkBack saying only "Formula editor" while hiding both the typed
    // formula and MathLive's virtual-keyboard children. Name the node without
    // merging descendants (so the keys stay reachable) and speak the current
    // LaTeX. The spoken value mirrors what `update` actually pushed into the
    // WebView; writing it there recomposes the description without remounting
    // this identity-keyed WebView or touching its bridge/teardown.
    val spokenLatex = remember { mutableStateOf(initialLatex) }
    val editorDescription = stringResource(R.string.a11y_formula_editor_value, spokenLatex.value)
    AndroidView(
        // The virtual keyboard panel is absolutely positioned at the
        // bottom of the page; the WebView must be tall enough to show
        // the field plus the whole keyboard without clipping.
        // Tall enough for the formula plus MathLive's keyboard panel; the
        // page scrolls if the layout is bigger than that.
        modifier = modifier
            .height(560.dp)
            .semantics { contentDescription = editorDescription },
        factory = { ctx ->
            val view = WebView(ctx)
            view.settings.javaScriptEnabled = true
            // Same hardening as the preview WebView: assets and resources stay
            // reachable without this (the page loads MathLive from
            // file:///android_asset), it only stops the page reading arbitrary
            // file:// paths — and this frame additionally holds a
            // @JavascriptInterface, so nothing may load into it from outside.
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = false
            // Refuse to navigate anywhere: the page never needs to, so no
            // link or injected markup can take over the frame and reach the
            // bridge. minSdk 26 reaches only this overload; the deprecated
            // String variant is for API < 24.
            view.webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest
                ): Boolean = true
            }
            view.setBackgroundColor(0x00000000)
            // The bridge is installed once, so it captures this lambda as it
            // was on first composition. That is safe only because everything
            // it closes over is stable: [blockId] travels with this keyed
            // WebView, and the host callback resolves the id against the
            // *current* block list (EditorViewModel.applyMathTarget). The old
            // version captured the positional index, so typing in a reordered
            // formula wrote into whichever block now occupied the old slot.
            val emitMathMl = onMathMl
            view.addJavascriptInterface(object {
                @JavascriptInterface
                fun onMathMl(mml: String) {
                    // Bridge callbacks arrive off the main thread.
                    view.post {
                        userEdited.value = true
                        emitMathMl(blockId, mml)
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
            // Keep the spoken description in step with the value the field now
            // holds. No remount: `factory` runs once and `onRelease` is
            // untouched; only this snapshot-backed label changes.
            if (spokenLatex.value != initialLatex) spokenLatex.value = initialLatex
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
