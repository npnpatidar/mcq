package com.mcqapp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The chemistry feature has no Gradle dependency: it ships a pinned, offline
 * copy of SmilesDrawer as a plain asset. These guards keep all three files
 * present and identifiable, so a partial copy or a rename cannot silently
 * leave the renderer without its JavaScript.
 */
class ChemistrySchemaAssetTest {

    private val dir = File("src/main/assets/smiles-drawer")

    @Test
    fun bundledSmilesDrawerIsPresentAndRecognisable() {
        val js = File(dir, "smiles-drawer.min.js")
        assertTrue("missing asset: ${js.absolutePath}", js.isFile)
        val text = js.readText()
        assertTrue("asset looks empty", text.length > 10_000)
        assertTrue(
            "not the SmilesDrawer browser build",
            text.contains("window.SmilesDrawer=SmilesDrawer")
        )
        assertTrue("parser export missing", text.contains("SmilesDrawer.Parser=Parser"))
        assertTrue(
            "SvgDrawer missing — this is the class the renderer calls",
            text.contains("SmilesDrawer.SvgDrawer")
        )
        assertTrue(
            "chroma-js notice missing from the bundle",
            text.contains("@preserve") &&
                text.contains("chroma.js - JavaScript library for color conversions")
        )
    }

    @Test
    fun licenseAndSourcePinTheBundledVersion() {
        val license = File(dir, "LICENSE")
        assertTrue("missing license: ${license.absolutePath}", license.isFile)
        val licenseText = license.readText()
        assertTrue("not an MIT license", licenseText.contains("MIT License"))
        assertTrue("upstream copyright missing", licenseText.contains("Reymond"))

        val source = File(dir, "SOURCE.txt")
        assertTrue("missing source note: ${source.absolutePath}", source.isFile)
        val sourceText = source.readText()
        assertTrue("version not pinned to 2.1.1", sourceText.contains("2.1.1"))
        assertTrue(
            "source URL missing",
            sourceText.contains("https://unpkg.com/smiles-drawer@2.1.1/dist/smiles-drawer.min.js")
        )
        assertTrue("MIT not recorded", sourceText.contains("MIT"))
        assertTrue("chroma-js not named", sourceText.contains("chroma-js 2.4.2"))
        assertTrue("chroma-js BSD licence not named", sourceText.contains("BSD-3-Clause"))
        assertTrue("colorbrewer Apache licence not named", sourceText.contains("Apache-2.0"))
        assertTrue(
            "verbatim notice location not recorded",
            sourceText.contains("@preserve")
        )
    }
}
