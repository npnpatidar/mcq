package com.mcqapp

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.ui.navigation.McqNavHost
import com.mcqapp.util.SharedImport
import com.mcqapp.util.nextSharedFile
import com.mcqapp.util.shareIntent
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Share renders land under `cacheDir/shared` through `FileProvider` and are
 * handed out as a raw `ACTION_SEND` intent with a read grant and its
 * `ClipData`; a file shared to the app is copied first and routed into the
 * import preview without opening the picker.
 */
@RunWith(AndroidJUnit4::class)
class ShareExportUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    @After
    fun cleanup() {
        // The singleton outlives the composition: never leak a pending import
        // into the next test.
        SharedImport.consume()
        SharedImport.consumeError()
    }

    /**
     * The share intent carries the file through `FileProvider` — action,
     * type, stream, clip data and read grant — and the granted uri resolves
     * back to the rendered bytes. The chooser itself is an external activity;
     * asserting it needs espresso-intents, which this project does not depend
     * on, so the intent is asserted directly instead.
     */
    @Test
    fun theShareIntentCarriesTheFileWithItsGrant() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bytes = "shared csv bytes".toByteArray(Charsets.UTF_8)
        val file = nextSharedFile(context.cacheDir, "report.csv")
        java.io.FileOutputStream(file).use { fos -> fos.write(bytes) }

        val intent = shareIntent(context, file, "text/csv")

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/csv", intent.type)
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertTrue("expected a content uri, got $uri", uri?.scheme == "content")
        assertTrue("expected our fileprovider, got $uri", uri?.authority?.endsWith(".fileprovider") == true)
        assertEquals(
            ClipData.newRawUri(null, uri).getItemAt(0).uri,
            intent.clipData?.getItemAt(0)?.uri
        )
        assertTrue(
            "expected the read grant, got flags=${intent.flags}",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        )
        val readBack = context.contentResolver.openInputStream(uri!!)!!.use { it.readBytes() }
        assertArrayEquals(bytes, readBack)
    }

    /**
     * A file shared to the app routes into the import preview: the copy in
     * `cacheDir/imports` is sniffed and the nav host opens `import/direct`
     * instead of leaving the user on the library.
     */
    @Test
    fun aSharedFileRoutesToTheImportPreview() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val importsDir = File(context.cacheDir, "imports").apply { mkdirs() }
        val copy = File(importsDir, "send-test.json")
        copy.writeText("{\"version\":1,\"papers\":[{\"id\":\"send-paper\",\"title\":\"Send Test\",\"description\":\"\",\"durationMinutes\":0,\"negativeMarking\":0.0,\"categories\":[]}]}")

        compose.setContent { McqNavHost(repository = repository) }
        SharedImport.request(Uri.fromFile(copy), "send-test.json")

        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Import questions", substring = false)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Import questions", substring = false).assertIsDisplayed()
    }
}
