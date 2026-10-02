package com.mcqapp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the WebView teardown in both `AndroidView` call sites.
 *
 * This is a structural check, not a runtime proof: proving a WebView is
 * actually destroyed needs a device or a Compose UI test harness, and the
 * project deliberately has neither in the JVM suite. What it does catch is
 * the realistic regression — someone adding or editing a WebView and dropping
 * the `onRelease` that releases its renderer and JS heap.
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

    @Test
    fun everyAndroidViewReleasesItsWebView() {
        val files = listOf(
            "src/main/java/com/mcqapp/util/ContentElements.kt",
            "src/main/java/com/mcqapp/ui/editor/MathLiveEditor.kt",
            "src/main/java/com/mcqapp/ui/editor/BlockListEditor.kt"
        )
        var checked = 0
        for (path in files) {
            for (block in androidViewBlocks(path)) {
                if (!block.contains("WebView(")) continue
                checked++
                assertTrue(
                    "$path declares a WebView in AndroidView but no onRelease, so its " +
                        "renderer and JS heap leak for the life of the process",
                    block.contains("onRelease")
                )
                assertTrue("$path: onRelease must call destroy()", block.contains("destroy()"))
            }
        }
        assertTrue("expected to inspect at least two WebView call sites, saw $checked", checked >= 2)
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
