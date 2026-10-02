package com.mcqapp

import com.mcqapp.data.anki.AnkiMediaPool
import com.mcqapp.domain.ContentElement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiMediaPoolTest {

    @Test
    fun inlineFormattingTagsSurviveInFields() {
        val html = AnkiMediaPool().elementsToHtml(
            listOf(ContentElement.TextElement("H<sub>2</sub>O <strong>x</strong> <em>y</em> <del>z</del>")),
            null
        )
        assertTrue(html.contains("H<sub>2</sub>O <strong>x</strong> <em>y</em> <del>z</del>"))
        assertFalse(html.contains("&lt;sub&gt;"))
    }

    @Test
    fun unknownTagsAreEscapedNotDropped() {
        val html = AnkiMediaPool().elementsToHtml(
            listOf(ContentElement.TextElement("Pick <one>?")),
            null
        )
        assertTrue(html.contains("Pick &lt;one&gt;?"))
    }

    @Test
    fun tableCellsRenderInlineTags() {
        val html = AnkiMediaPool().elementsToHtml(
            listOf(ContentElement.TableElement(listOf(listOf("H<sup>2</sup>")))),
            null
        )
        assertTrue(html.contains("<td>H<sup>2</sup></td>"))
    }

    @Test
    fun mathPassesThrough() {
        val html = AnkiMediaPool().elementsToHtml(
            listOf(ContentElement.MathElement("<math><mi>x</mi></math>")),
            null
        )
        assertTrue(html.contains("<math><mi>x</mi></math>"))
    }
}
