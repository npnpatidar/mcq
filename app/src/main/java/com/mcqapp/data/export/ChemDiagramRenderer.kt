package com.mcqapp.data.export

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.mcqapp.util.isRendererPngDataUri
import com.mcqapp.util.smilesJsLiteral
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference

/**
 * Renders one chemical structure as a light-theme PNG data URI, or null when
 * it cannot be drawn. The interface is deliberately narrow and suspend-only so
 * the preparation pass can be tested with a fake and the export path never has
 * to know whether a WebView exists.
 */
internal interface ChemDiagramRenderer {
    /**
     * [textAbove] is the reaction caption the editor draws above the arrow
     * (for example "LiAlH₄ / ether"). A molecule ignores it; a reaction keeps
     * it so the exported scheme carries the same reagent text as the screen.
     */
    suspend fun render(smiles: String, textAbove: String = ""): String?
}

/**
 * Process-wide renderer the export path may use. MainActivity installs the
 * real WebView-backed one once its render host is composed; when nothing is
 * installed (unit tests, headless use) exports keep today's handles-only
 * fallback and never fail because of a missing renderer.
 */
internal object ChemDiagramRendererProvider {

    @Volatile
    private var installed: ChemDiagramRenderer? = null

    fun install(renderer: ChemDiagramRenderer?) {
        installed = renderer
    }

    fun current(): ChemDiagramRenderer? = installed
}

/** Reaction SMILES have exactly two `>`; a lone `>` is malformed. */
internal fun chemDrawingMode(smiles: String): String =
    if (smiles.count { it == '>' } == 2) "reaction" else "molecule"

/**
 * A structure can be drawn only when it is non-blank and has either no `>`
 * (a molecule) or exactly two (reactants > reagents > products). A lone or
 * three-part-marked SMILES is malformed and must never reach the renderer.
 */
internal fun chemSmilesIsRenderable(smiles: String): Boolean {
    if (smiles.isBlank()) return false
    return when (smiles.count { it == '>' }) {
        0, 2 -> true
        else -> false
    }
}

/**
 * The renderer cache key: drawing mode + source, plus the caption only for a
 * reaction. A reaction caption is drawn above the arrow, so the same SMILES
 * with a different caption is a different picture; a molecule caption is
 * printed beside the drawing, not inside it, so it must not split the cache.
 */
internal fun chemDrawingKey(smiles: String, textAbove: String = ""): String {
    val mode = chemDrawingMode(smiles)
    val caption = if (mode == "reaction") '\u0000' + textAbove else ""
    return mode + '\u0000' + smiles + caption
}

/**
 * A bounded access-ordered cache of rendered diagrams. Structures repeat across
 * options, explanations and a paper's later questions, but a big bank may hold
 * thousands of distinct drawings, so keeping every PNG for the process lifetime
 * would be an unbounded bitmap leak.
 */
internal class ChemDiagramCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val entries = object : LinkedHashMap<String, String>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, String>?
        ): Boolean = size > maxEntries
    }

    @Synchronized
    fun get(key: String): String? = entries[key]

    @Synchronized
    fun put(key: String, value: String) {
        entries[key] = value
    }

    @Synchronized
    fun size(): Int = entries.size

    companion object {
        internal const val DEFAULT_MAX_ENTRIES = 128
    }
}

/**
 * The renderer used by the app: it hands each SMILES to the attached offscreen
 * WebView supplied by [ChemDiagramRenderHost], awaits the raster and caches the
 * successful result by drawing mode + SMILES (plus a reaction's caption).
 * Failures are not cached, so a transient WebView problem does not poison
 * later exports.
 */
internal class SmilesWebViewRenderer : ChemDiagramRenderer {

    private val cache = ChemDiagramCache()

    override suspend fun render(smiles: String, textAbove: String): String? {
        val source = smiles.trim()
        if (!chemSmilesIsRenderable(source)) return null
        val key = chemDrawingKey(source, textAbove)
        cache.get(key)?.let { return it }
        val png = ChemDiagramRenderHost.render(source, textAbove) ?: return null
        if (!isRendererPngDataUri(png)) return null
        cache.put(key, png)
        return png
    }
}

/**
 * Owns the one offscreen WebView that renders export diagrams.
 *
 * Unattached WebViews are unreliable rasterisers (the compositor may never
 * draw them), so MainActivity composes a 1×1, nearly transparent host once and
 * registers it here. Requests are serialised by [renderMutex] and executed on
 * the main thread, one at a time; each has its own [RENDER_TIMEOUT_MS] cap so a
 * broken JS asset or a stuck page turns into a handles-only structure rather
 * than a hanging export. When no host is attached every request fails closed
 * with null.
 */
internal object ChemDiagramRenderHost {

    internal const val BRIDGE_NAME = "McqExportChemDiagram"

    /** One structure gets this long before the export moves on. */
    internal const val RENDER_TIMEOUT_MS = 8_000L

    private val lock = Any()
    private val renderMutex = Mutex()

    /**
     * The UI dispatcher the WebView work is marshalled to. Production uses
     * [Dispatchers.Main.immediate]; the host-timing test swaps in an
     * immediate test dispatcher so it does not need a paused Robolectric
     * main looper to make progress.
     */
    @Volatile
    internal var mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate

    // Weak: a destroyed Activity's WebView must never be retained by this
    // process-wide object. The composable detaches its view on release anyway,
    // so this is a second line of defence for a missed lifecycle callback.
    private var webView: WeakReference<WebView>? = null
    private val pending = HashMap<String, CompletableDeferred<String?>>()
    private var sequence = 0L

    /** Called by the host composable when its WebView is created. */
    fun attach(view: WebView) {
        synchronized(lock) { webView = WeakReference(view) }
    }

    /** Called when the activity root goes away; any in-flight request fails. */
    fun detach(view: WebView) {
        val waiting: List<CompletableDeferred<String?>>
        synchronized(lock) {
            if (webView?.get() === view) webView = null
            waiting = pending.values.toList()
            pending.clear()
        }
        waiting.forEach { it.complete(null) }
    }

    private fun attachedView(): WebView? = synchronized(lock) { webView?.get() }

    /**
     * The page's only callback. It runs on a WebView bridge thread, so the
     * deferred (thread-safe) is completed directly; the awaiting coroutine
     * resumes on its own dispatcher.
     */
    @JavascriptInterface
    fun onRendered(requestId: String, png: String) {
        val waiting = synchronized(lock) { pending[requestId] } ?: return
        waiting.complete(png.takeIf { isRendererPngDataUri(it) })
    }

    suspend fun render(
        smiles: String,
        textAbove: String = "",
        timeoutMillis: Long = RENDER_TIMEOUT_MS
    ): String? {
        return renderMutex.withLock {
            val requestId = synchronized(lock) { "chem-${++sequence}" }
            val deferred = CompletableDeferred<String?>()
            synchronized(lock) { pending[requestId] = deferred }
            try {
                // The timeout lives on the caller's dispatcher, not on the
                // main thread: a page that never calls back must still expire
                // while the UI thread is free, and an injected tiny timeout is
                // observable in tests.
                withTimeoutOrNull(timeoutMillis) {
                    withContext(mainDispatcher) {
                        // Resolve the host INSIDE the main-thread block. A
                        // detach between here and the load must yield null,
                        // never a load-after-destroy on a stale view.
                        val view = attachedView()
                        if (view == null) {
                            deferred.complete(null)
                        } else {
                            try {
                                val page = smilesRenderPage(smiles, textAbove, requestId)
                                view.loadDataWithBaseURL(null, page, "text/html", "UTF-8", null)
                            } catch (e: Exception) {
                                // A destroyed host or a WebView that refuses to
                                // load is a failed render, never a failed export.
                                deferred.complete(null)
                            }
                        }
                        deferred.await()
                    }
                }
            } finally {
                synchronized(lock) { pending.remove(requestId) }
                // Release the page as soon as the result (or timeout) lands.
                withContext(mainDispatcher) {
                    attachedView()?.stopLoading()
                }
            }
        }
    }

    /**
     * The complete offline page: the bundled drawer, one target SVG and the
     * same light-theme capture path the editor uses. No network is reachable
     * (the CSP allows the local script and data: images only), and the SMILES
     * goes through the shared JS-literal allowlist so it cannot close the
     * script or inject markup.
     */
    private fun smilesRenderPage(
        smiles: String,
        textAbove: String,
        requestId: String
    ): String = """
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' file:; img-src data:; style-src 'unsafe-inline'">
        <script src="${com.mcqapp.util.SMILES_DRAWER_ASSET_URL}"></script>
        <style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}</style>
        </head>
        <body>
        <svg id="capture"></svg>
        <script>(function(){
        var smiles=${smilesJsLiteral(smiles)};
        var textAbove=${jsLiteral(textAbove)};
        var id=${jsLiteral(requestId)};
        function finish(png){
          try{if(window.${BRIDGE_NAME})window.${BRIDGE_NAME}.onRendered(id,png||'');}catch(e){}
        }
        function fail(){finish('');}
        function rasterise(){
          try{
            var c=document.getElementById('capture').cloneNode(true);
            c.removeAttribute('style');
            var xml=new XMLSerializer().serializeToString(c);
            var img=new Image();
            img.onload=function(){try{
              var canvas=document.createElement('canvas');
              canvas.width=img.naturalWidth||300;
              canvas.height=img.naturalHeight||300;
              canvas.getContext('2d').drawImage(img,0,0);
              var png=canvas.toDataURL('image/png');
              if(png.indexOf('data:image/png;base64,')===0)finish(png);else fail();
            }catch(e){fail();}};
            img.onerror=fail;
            img.src='data:image/svg+xml;base64,'+btoa(unescape(encodeURIComponent(xml)));
          }catch(e){fail();}
        }
        try{
          if(!window.SmilesDrawer)throw new Error('drawer');
          var svg=document.getElementById('capture');
          if(smiles.split('>').length===3){
            if(!window.SmilesDrawer.parseReaction)throw new Error('reaction');
            window.SmilesDrawer.parseReaction(smiles,function(reaction){
              try{
                // The editor's hidden light-theme capture, verbatim: default
                // reaction chrome at the 1x capture scale and explicit
                // molecule defaults so arrow.length/caption never become NaN.
                var rxnOptions={};
                var molOptions={width:300,height:300,padding:2,
                  bondLength:30,fontSizeLarge:11,fontSizeSmall:3,terminalCarbons:true};
                new window.SmilesDrawer.ReactionDrawer(rxnOptions,molOptions)
                  .draw(reaction,svg,'light',null,textAbove,"",false);
                rasterise();
              }catch(e){fail();}
            },function(){fail();});
          }else{
            window.SmilesDrawer.parse(smiles,function(tree){
              try{
                new window.SmilesDrawer.SvgDrawer({width:300,height:300,padding:2})
                  .draw(tree,svg,'light');
                rasterise();
              }catch(e){fail();}
            },function(){fail();});
          }
        }catch(e){fail();}
        })();</script>
        </body>
        </html>
    """.trimIndent()

    private fun jsLiteral(value: String): String = com.mcqapp.util.jsStringLiteral(value)
}
