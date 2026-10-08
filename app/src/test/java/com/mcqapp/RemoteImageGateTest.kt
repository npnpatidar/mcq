package com.mcqapp

import com.mcqapp.util.isRemoteImageSrc
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the remote-image privacy toggle (audit F10): a bank-authored
 * http(s) picture is fetched by Coil, which shows whoever authored the bank
 * the learner's IP and the moment they opened the question. The setting
 * defaults to off, QuestionImage must consult it before handing a URL to
 * Coil, and the reader must see an explanation instead of an empty slot.
 *
 * The wiring check is structural, like WebViewLifecycleTest: proving a
 * compositionLocal's value at runtime needs a Compose UI test harness the
 * JVM suite does not have. The preview WebView is out of scope here — its
 * CSP already blocks every network image (RichTextSanitizingTest).
 *
 * Unit tests run with the app module as working directory.
 */
class RemoteImageGateTest {

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("missing: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun onlyNetworkUrlsAreRemote() {
        assertTrue(isRemoteImageSrc("https://example.org/a.png"))
        assertTrue(isRemoteImageSrc("http://example.org/a.png"))
        assertTrue(isRemoteImageSrc("HTTPS://EXAMPLE.ORG/a.png"))
    }

    @Test
    fun localSourcesAreNeverRemote() {
        for (src in listOf(
            "data:image/png;base64,AAAA",
            "content://media/external/images/1",
            "file:///sdcard/a.png",
            "android.resource://com.mcqapp/drawable/a",
            "mcq-1.png",
            ""
        )) {
            assertFalse("$src must not be treated as a network fetch", isRemoteImageSrc(src))
        }
    }

    @Test
    fun remoteImagesAreOffByDefault() {
        // The setting lives in RepositorySettings.kt, which McqRepository
        // delegates to; the guard follows the implementation.
        val repository = source("src/main/java/com/mcqapp/data/repository/RepositorySettings.kt")
        val flow = repository
            .substringAfter("fun loadRemoteImages()")
            .substringBefore("suspend fun setLoadRemoteImages")
        assertTrue(
            "loadRemoteImages must default to off: an on-by-default setting " +
                "leaks every viewer's IP to the bank author",
            flow.contains("?: false")
        )
        assertFalse("loadRemoteImages must not default to on", flow.contains("?: true"))
    }

    @Test
    fun questionImageGatesCoilBehindTheSetting() {
        val image = source("src/main/java/com/mcqapp/util/QuestionImage.kt")
        val gate = "isRemoteImageSrc(src) && !LocalLoadRemoteImages.current"
        val gateIndex = image.indexOf(gate)
        assertTrue("QuestionImage must consult the setting for remote URLs", gateIndex >= 0)
        assertTrue(
            "the gate must run before Coil receives the URL",
            gateIndex < image.indexOf("AsyncImage(")
        )
        assertTrue(
            "a hidden image must explain itself instead of showing an empty slot",
            image.contains("stringResource(R.string.remote_image_hidden")
        )
        val strings = source("src/main/res/values/strings.xml")
        assertTrue(
            "strings.xml must define the hidden-image placeholder",
            strings.contains("remote_image_hidden_turn_on_load_remote_images_in_settin")
        )
    }

    @Test
    fun settingIsProvidedAndWiredToTheScreen() {
        val navHost = source("src/main/java/com/mcqapp/ui/navigation/McqNavHost.kt")
        assertTrue(
            "McqNavHost must provide LocalLoadRemoteImages to the tree",
            navHost.contains("LocalLoadRemoteImages provides loadRemoteImages")
        )
        val screen = source("src/main/java/com/mcqapp/ui/settings/SettingsScreen.kt")
        assertTrue(
            "Settings must expose the toggle that writes the setting",
            screen.contains("setLoadRemoteImages")
        )
    }
}
