package com.mcqapp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the WebView call sites in the preview and the editor: teardown
 * (a leaked renderer and JS heap for the life of the process) and the
 * hardening every page WebView must share — no file or content access, no
 * navigation — so the frame and its `@JavascriptInterface` stay contained.
 *
 * This is a structural check, not a runtime proof: proving a WebView's
 * settings or its destruction at runtime needs a device or a Compose UI test
 * harness, and the project deliberately has neither in the JVM suite. What it
 * does catch is the realistic regression — someone adding or editing a
 * WebView and dropping the `onRelease`, or shipping one with default settings
 * the way the MathLive editor did.
 *
 * Unit tests run with the app module as working directory.
 */
class WebViewLifecycleTest {

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("missing: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun androidViewBlocks(path: String): List<String> {
        val text = source(path)
        val blocks = mutableListOf<String>()
        var index = 0
        while (true) {
            val start = text.indexOf("AndroidView(", index)
            if (start < 0) break
            var depth = 0
            var i = start
            while (i < text.length) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                i++
            }
            blocks += text.substring(start, (i + 1).coerceAtMost(text.length))
            index = i + 1
        }
        return blocks
    }

    /** (path, block) for every `AndroidView` block that builds a WebView. */
    private fun webViewBlocks(): List<Pair<String, String>> {
        val files = listOf(
            "src/main/java/com/mcqapp/util/ContentElements.kt",
            "src/main/java/com/mcqapp/ui/editor/MathLiveEditor.kt",
            "src/main/java/com/mcqapp/ui/editor/BlockListEditor.kt"
        )
        return files.flatMap { path ->
            androidViewBlocks(path).filter { it.contains("WebView(") }.map { path to it }
        }
    }

    @Test
    fun everyAndroidViewReleasesItsWebView() {
        val sites = webViewBlocks()
        for ((path, block) in sites) {
            assertTrue(
                "$path declares a WebView in AndroidView but no onRelease, so its " +
                    "renderer and JS heap leak for the life of the process",
                block.contains("onRelease")
            )
            assertTrue("$path: onRelease must call destroy()", block.contains("destroy()"))
        }
        assertTrue("expected to inspect at least two WebView call sites, saw ${sites.size}", sites.size >= 2)
    }

    @Test
    fun everyPageWebViewRefusesFileAndContentAccess() {
        val sites = webViewBlocks()
        for ((path, block) in sites) {
            assertTrue(
                "$path: a page WebView must set allowFileAccess = false, or the " +
                    "page can read arbitrary file:// paths",
                block.contains("allowFileAccess = false")
            )
            assertTrue(
                "$path: a page WebView must set allowContentAccess = false",
                block.contains("allowContentAccess = false")
            )
        }
        assertTrue("expected to inspect at least two WebView call sites, saw ${sites.size}", sites.size >= 2)
    }

    @Test
    fun everyPageWebViewRefusesNavigation() {
        val sites = webViewBlocks()
        for ((path, block) in sites) {
            assertTrue(
                "$path: a page WebView must veto navigation, so no link or " +
                    "injected markup can take over the frame",
                block.contains("shouldOverrideUrlLoading")
            )
        }
        assertTrue("expected to inspect at least two WebView call sites, saw ${sites.size}", sites.size >= 2)
    }

    @Test
    fun theMathLiveBridgeIsRemovedBeforeDestroying() {
        val text = source("src/main/java/com/mcqapp/ui/editor/MathLiveEditor.kt")
        assertTrue(
            "the @JavascriptInterface object must be detached before destroy()",
            text.contains("removeJavascriptInterface")
        )
    }
}
