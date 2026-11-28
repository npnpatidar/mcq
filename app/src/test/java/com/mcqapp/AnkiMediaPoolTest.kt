package com.mcqapp

import com.mcqapp.data.anki.AnkiMediaPool
import com.mcqapp.data.normalizeMathText
import com.mcqapp.domain.ContentElement
import com.mcqapp.util.CELL_IMAGE_MARKER
import com.mcqapp.util.cellImageTag
import com.mcqapp.util.chemCellHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiMediaPoolTest {

    /** A real 1×1 PNG: the export path validates a chem diagram by signature. */
    private val png =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    /** A different, shape-only portable picture, so the two do not dedupe. */
    private val otherPng = "data:image/png;base64,iVBORw0KGgo="

    @Test
    fun aLegacyBareFormulaElementBecomesRealMathInTheDeck() {
        val pool = AnkiMediaPool()
        try {
            val html = pool.elementsToHtml(
                listOf(ContentElement.MathElement("<msup><mi>x</mi><mn>2</mn></msup>")),
                null
            )
            assertTrue("the run is a complete block: $html", html.contains("<math><mrow><msup>"))
            assertFalse("never escaped tag text: $html", html.contains("&lt;msup"))
            // A complete block is untouched.
            assertEquals(
                "<math><mi>y</mi></math>",
                pool.elementsToHtml(
                    listOf(ContentElement.MathElement("<math><mi>y</mi></math>")),
                    null
                )
            )
        } finally {
            pool.close()
        }
    }

    @Test
    fun aBareMathLiveFragmentInACellBecomesRealMathInTheDeck() {
        val cell = "Find <msup><mi>x</mi><mn>2</mn></msup> here"
        val html = AnkiMediaPool().elementsToHtml(
            listOf(ContentElement.TableElement(listOf(listOf(cell)))),
            null
        )
        assertTrue("the fragment becomes live MathML: $html", html.contains("<math><mrow><msup>"))
        assertTrue("prose before survives: $html", html.contains("Find "))
        assertTrue("prose after survives: $html", html.contains(" here"))
        assertFalse("never escaped tag text: $html",
            html.contains("&lt;msup") || html.contains("&lt;mrow"))
        assertFalse("never the fallback marker: $html", html.contains("[formula]"))
    }

    @Test
    fun aRepairedFragmentDropsAttributesBeforeItReachesTheDeck() {
        val cell = "<mi onclick=\"alert(1)\" mathvariant=\"bold\">x</mi>"
        val html = AnkiMediaPool().elementsToHtml(
            listOf(ContentElement.TableElement(listOf(listOf(cell)))),
            null
        )
        assertTrue("still a formula: $html", html.contains("<math><mrow><mi>x</mi></mrow></math>"))
        assertFalse("no handler leaks: $html", html.contains("onclick"))
        assertFalse("no attribute leaks: $html", html.contains("mathvariant"))
    }

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

    @Test
    fun chemHandlesAreReadableAndItsPngIsSpooledByFilename() {
        val pool = AnkiMediaPool()
        val html = pool.elementsToHtml(
            listOf(
                ContentElement.ChemElement(
                    smiles = "c1ccccc1", formula = "C6H6", caption = "Benzene",
                    diagram = "data:image/png;base64,iVBORw0KGgo="
                )
            )
        )
        assertTrue(html.contains("Benzene"))
        assertTrue(html.contains("C6H6"))
        assertTrue(html.contains("c1ccccc1"))
        assertTrue(html.contains("mcqapp-0.png"))
        assertFalse(html.contains("<svg"))
        assertEquals(1, pool.size)
        pool.close()
        assertEquals("mcqapp-0.png", pool.manifest.values.single())
    }

    @Test
    fun aStoredCellImageIsSpooledAsMediaAndNeverWrittenAsBase64Text() {
        val pool = AnkiMediaPool()
        try {
            // The importer stores this exact tag (style + canonical alt), not
            // a bare `<img src>`; drift there must not silently export `[image]`.
            val html = pool.elementsToHtml(
                listOf(
                    ContentElement.TableElement(
                        listOf(listOf("Cell " + cellImageTag(png) + " end"))
                    )
                )
            )
            assertEquals("<table border=\"1\"><tr><td>Cell <img src=\"mcqapp-0.png\"> end</td></tr></table>", html)
            assertFalse("the card must never carry the picture's data URI", html.contains("data:"))
            assertFalse(html.contains("base64"))
            assertEquals(1, pool.size)
            assertEquals("mcqapp-0.png", pool.manifest.values.single())
        } finally {
            pool.close()
        }
    }

    @Test
    fun twoDistinctPicturesInOneCellSpoolTwoFiles() {
        val pool = AnkiMediaPool()
        try {
            val cell = "A <img src=\"$png\"> B <img src=\"$otherPng\"> C"
            val html = pool.elementsToHtml(
                listOf(ContentElement.TableElement(listOf(listOf(cell))))
            )
            assertEquals(
                "<table border=\"1\"><tr><td>A <img src=\"mcqapp-0.png\"> B " +
                    "<img src=\"mcqapp-1.png\"> C</td></tr></table>",
                html
            )
            assertEquals(2, pool.size)
            assertEquals(setOf("mcqapp-0.png", "mcqapp-1.png"), pool.manifest.values.toSet())
            assertFalse(html.contains("data:"))
        } finally {
            pool.close()
        }
    }

    @Test
    fun anImageOnlyCellExportsJustTheMediaReference() {
        val pool = AnkiMediaPool()
        try {
            val html = pool.elementsToHtml(
                listOf(ContentElement.TableElement(listOf(listOf(cellImageTag(png)))))
            )
            assertEquals(
                "<table border=\"1\"><tr><td><img src=\"mcqapp-0.png\"></td></tr></table>",
                html
            )
            assertEquals(1, pool.size)
        } finally {
            pool.close()
        }
    }

    @Test
    fun anEmptyCellStaysEmptyAndSpoolsNothing() {
        val pool = AnkiMediaPool()
        try {
            val html = pool.elementsToHtml(
                listOf(ContentElement.TableElement(listOf(listOf("", ""))))
            )
            assertEquals("<table border=\"1\"><tr><td></td><td></td></tr></table>", html)
            assertEquals(0, pool.size)
        } finally {
            pool.close()
        }
    }

    @Test
    fun aPictureInsideInlineFormattingKeepsTheTagPair() {
        val pool = AnkiMediaPool()
        try {
            val html = pool.elementsToHtml(
                listOf(
                    ContentElement.TableElement(
                        listOf(listOf("<b>a <img src=\"$png\"> b</b>"))
                    )
                )
            )
            assertEquals(
                "<table border=\"1\"><tr><td><b>a <img src=\"mcqapp-0.png\"> b</b></td></tr></table>",
                html
            )
        } finally {
            pool.close()
        }
    }

    @Test
    fun theSamePictureAtTopLevelAndInACellSpoolsOneFile() {
        val pool = AnkiMediaPool()
        try {
            val html = pool.elementsToHtml(
                listOf(
                    ContentElement.ImageElement(png),
                    ContentElement.TableElement(listOf(listOf("See <img src=\"$png\">"))),
                    ContentElement.ImageElement(png)
                )
            )
            assertEquals(1, pool.size)
            assertEquals(3, Regex("mcqapp-0\\.png").findAll(html).count())
            assertFalse(html.contains("data:"))
        } finally {
            pool.close()
        }
    }

    @Test
    fun aCellFormulaKeepsTheSameMathmlATopLevelFormulaGets() {
        val pool = AnkiMediaPool()
        try {
            val html = pool.elementsToHtml(
                listOf(
                    ContentElement.TableElement(
                        listOf(listOf("Cell <b>x</b> <math><mi>y</mi></math>"))
                    )
                )
            )
            assertTrue(html, html.contains("<td>Cell <b>x</b> <math><mi>y</mi></math></td>"))
            assertFalse("MathML must not be escaped as literal text", html.contains("&lt;math"))
        } finally {
            pool.close()
        }
    }

    @Test
    fun anUntokenisedCellFormulaIsNormalizedExactlyLikeATopLevelFormula() {
        val pool = AnkiMediaPool()
        try {
            val html = pool.elementsToHtml(
                listOf(
                    ContentElement.TableElement(
                        listOf(listOf("Cell <math>x+1</math> end"))
                    )
                )
            )
            assertTrue(
                html,
                html.contains("<td>Cell <math><mi>x</mi><mo>+</mo><mn>1</mn></math> end</td>")
            )
            // The same choice as a standalone MathElement (normalizeMathText),
            // so a bare run is tokenized rather than left as an invalid text
            // node MathJax would refuse to draw.
            assertEquals(
                normalizeMathText("<math>x+1</math>"),
                "<math><mi>x</mi><mo>+</mo><mn>1</mn></math>"
            )
        } finally {
            pool.close()
        }
    }

    @Test
    fun aCellWithTextImageFormulaAndChemExportsMediaHandlesAndMath() {
        val chem = ContentElement.ChemElement(
            smiles = "c1ccccc1",
            formula = "C6H6",
            caption = "Benzene",
            diagram = png
        )
        val cell = "See <img src=\"$otherPng\"> " +
            "<math><mi>x</mi></math> " + chemCellHtml(chem) + " end"
        val pool = AnkiMediaPool()
        try {
            val html = pool.elementsToHtml(listOf(ContentElement.TableElement(listOf(listOf(cell)))))
            assertTrue(html, html.contains("Benzene C6H6 c1ccccc1"))
            assertTrue(html, html.contains("<math><mi>x</mi></math>"))
            assertTrue(html, Regex("mcqapp-0\\.png").containsMatchIn(html))
            assertTrue(html, Regex("mcqapp-1\\.png").containsMatchIn(html))
            assertEquals(2, pool.size)
            assertEquals(setOf("mcqapp-0.png", "mcqapp-1.png"), pool.manifest.values.toSet())
            assertFalse("no data URI may reach the card", html.contains("data:"))
            assertFalse("the marker itself is storage, not a wire format", html.contains("mcq-chem-cell"))
            assertFalse("a bare data-png payload must not leak", html.contains("iVBORw0KGgo"))
        } finally {
            pool.close()
        }
    }

    @Test
    fun aChemCellWithoutAPngExportsOnlyItsHandles() {
        val chem = ContentElement.ChemElement(smiles = "CCO", caption = "ethanol", diagram = "")
        val pool = AnkiMediaPool()
        try {
            val html = pool.elementsToHtml(
                listOf(ContentElement.TableElement(listOf(listOf(chemCellHtml(chem)))))
            )
            assertTrue(html, html.contains("<td>ethanol CCO</td>"))
            assertEquals(0, pool.size)
        } finally {
            pool.close()
        }
    }

    @Test
    fun aHostileCellImageBecomesTheMarkerAndLeaksNothing() {
        val pool = AnkiMediaPool()
        try {
            val html = pool.elementsToHtml(
                listOf(
                    ContentElement.TableElement(
                        listOf(
                            listOf(
                                "A <img src=\"javascript:alert(1)\" onerror=\"alert(2)\"> B",
                                "C <img src=\"file:///etc/passwd\"> D",
                                "E <img src=\"data:image/svg+xml;base64,PHN2Zz4=\"> G",
                                "H <img src=\"$png\" I"
                            )
                        )
                    )
                )
            )
            assertFalse(html, html.contains("javascript:"))
            assertFalse(html, html.contains("onerror"))
            assertFalse(html, html.contains("file:"))
            assertFalse(html, html.contains("svg"))
            assertFalse("a torn tag's payload must not survive", html.contains("base64"))
            assertFalse(html.contains("data:"))
            assertEquals(4, Regex(Regex.escape(CELL_IMAGE_MARKER)).findAll(html).count())
            assertEquals(0, pool.size)
        } finally {
            pool.close()
        }
    }

    @Test
    fun aRemoteCellImageIsKeptAsAUrlAndSpoolsNothing() {
        val pool = AnkiMediaPool()
        try {
            val html = pool.elementsToHtml(
                listOf(
                    ContentElement.TableElement(
                        listOf(listOf("See <img src=\"https://example.com/ring.png\">"))
                    )
                )
            )
            assertTrue(html, html.contains("<img src=\"https://example.com/ring.png\">"))
            assertEquals(0, pool.size)
        } finally {
            pool.close()
        }
    }

    @Test
    fun aPictureInsideACellFormulaIsSpooledBeforeTheMathIsNormalized() {
        val pool = AnkiMediaPool()
        try {
            val html = pool.elementsToHtml(
                listOf(
                    ContentElement.TableElement(
                        listOf(listOf("<math><mi>x</mi><img src=\"$png\"></math>"))
                    )
                )
            )
            assertTrue(html, html.contains("mcqapp-0.png"))
            assertFalse(html.contains("data:"))
            assertEquals(1, pool.size)
        } finally {
            pool.close()
        }
    }
}
