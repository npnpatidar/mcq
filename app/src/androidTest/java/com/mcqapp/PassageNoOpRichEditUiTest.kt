package com.mcqapp

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Passage
import com.mcqapp.ui.editor.PassagesScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Saving a passage without changing its text is a no-op and must keep the
 * rich elements (tables, formulas, images) intact.
 *
 * The dialog seeds its text field from [Passage.text], the flattened
 * textContent. A passage whose text carries edge whitespace used to look
 * edited because the field was trimmed but the stored text was not — the
 * no-op save then replaced every element with one plain TextElement. The test
 * edits only the title (not the body) and waits for that persisted change
 * before asserting, so a late write cannot slip past the assertions. Runs on
 * the CI emulator with the real repository.
 */
@RunWith(AndroidJUnit4::class)
class PassageNoOpRichEditUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository
        get() = (ApplicationProvider.getApplicationContext() as McqApplication).repository

    private lateinit var passageId: String

    @Before
    fun seed() = runBlocking {
        repository.ensurePaperAndCategory("nop-edit-paper", "NoOp Paper", "nop-c1", "NoOp Cat")
        passageId = repository.savePassage(
            Passage(
                id = "",
                categoryId = "nop-c1",
                title = "Rich passage",
                elements = listOf(
                    ContentElement.TextElement("  Rich intro with edge spaces  "),
                    ContentElement.TableElement(listOf(listOf("cell"))),
                    ContentElement.MathElement("<math><mi>x</mi></math>")
                )
            )
        )
    }

    @After
    fun cleanup() = runBlocking {
        repository.deletePaper("nop-edit-paper")
    }

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun savingAnUntouchedRichPassageKeepsItsElements() = runBlocking {
        // The stored flattened text really does have edge whitespace — that is
        // the precondition the old comparison mishandled.
        assertEquals("  Rich intro with edge spaces  ", repository.getPassage(passageId)!!.text)

        compose.setContent { PassagesScreen(repository, rememberNavController()) }
        waitFor("Rich passage")
        compose.onNodeWithContentDescription("Edit passage").performClick()
        waitFor("Passage text")

        // Change only the title, leaving the body text untouched: this is
        // still a text no-op for the comparison under test, while the
        // persisted title gives a false→true signal that the async write
        // actually landed before the assertions below.
        compose.onNode(hasSetTextAction() and hasText("Rich passage"))
            .performTextReplacement("Rich passage edited")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(30_000) {
            runBlocking { repository.getPassage(passageId)!!.title } == "Rich passage edited"
        }

        val elements = repository.getPassage(passageId)!!.elements
        assertEquals("no-op save must not flatten rich content", 3, elements.size)
        assertTrue(
            "the table must survive",
            elements.any { it is ContentElement.TableElement }
        )
        assertTrue(
            "the formula must survive",
            elements.any { it is ContentElement.MathElement }
        )
    }
}
