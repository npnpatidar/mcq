package com.mcqapp.ui.common

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.mcqapp.data.export.ChemDiagramRenderHost

/**
 * The process's one offscreen drawing surface for export diagrams.
 *
 * It must be attached to a window: the WebView compositor does not reliably
 * draw an unattached view, and the export path needs its canvas. The view is
 * 1×1 and almost transparent so it is invisible in the app, but it still owns
 * a real renderer and can rasterise a structure on demand. [ChemDiagramRenderHost]
 * serialises requests and MainActivity composes this exactly once at the root.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ChemDiagramRenderHostView(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                // The page loads only the bundled drawer from android_asset;
                // it has no business reading files or reaching the network.
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean = true

                    @Deprecated("Required for API < 24")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean =
                        true
                }
                addJavascriptInterface(ChemDiagramRenderHost, ChemDiagramRenderHost.BRIDGE_NAME)
                ChemDiagramRenderHost.attach(this)
            }
        },
        onRelease = { view ->
            ChemDiagramRenderHost.detach(view)
            view.removeJavascriptInterface(ChemDiagramRenderHost.BRIDGE_NAME)
            view.stopLoading()
            view.onPause()
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        },
        modifier = modifier
            .size(1.dp)
            .alpha(0.01f)
    )
}
