package com.mcqapp

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.util.ContentElements
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The 0.0.33 image report ("no images except questions 14 and 53") had no
 * test behind it: nothing asserted that a question's picture actually
 * appears. These render a stored-image question through the same
 * [ContentElements] pipeline the session and browse screens use, on a real
 * emulator, from data that went through the real importer.
 */
@RunWith(AndroidJUnit4::class)
class SampleImageUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    @Test
    fun theSampleAssetsPicturesSurviveImportAndRender() = runBlocking {
        // The asset ships inside the APK: read it from the target context.
        val text = InstrumentationRegistry.getInstrumentation().targetContext.assets
            .open("sample_paper.json").bufferedReader().use { it.readText() }
        val file = LegacyParser.parse(text.byteInputStream())
        val db = repository.db()
        val withImages = file.papers.flatMap { it.categories }.flatMap { it.questions }
            .filter { q -> q.elements.any { it is ContentElement.ImageElement } }
        assertTrue("the asset carries body images", withImages.isNotEmpty())

        // Round-trip one image question through the real importer, then read
        // it back the way the screens do.
        val single = withImages.first()
        val paper = file.papers.first()
        val report = Importer(db).import(file)
        assertTrue(report.newQuestions > 0)

        val stored = repository.getQuestion(single.id)!!
        val elements = stored.elements
        assertTrue("stored question keeps its image element", elements.any { it is ContentElement.ImageElement })

        compose.setContent {
            ContentElements(elements)
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("question-image", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag("question-image", useUnmergedTree = true)[0].assertIsDisplayed()
        // Cleanup: drop the imported sample paper so other tests stay isolated.
        repository.deletePaper(paper.id)
    }
}
