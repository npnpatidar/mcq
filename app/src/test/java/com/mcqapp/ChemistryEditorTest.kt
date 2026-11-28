package com.mcqapp

import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.ContentElementListJson
import com.mcqapp.ui.editor.CHEM_DIAGRAM_PREFIX
import com.mcqapp.ui.editor.ChemTemplate
import com.mcqapp.ui.editor.ChemTemplates
import com.mcqapp.ui.editor.EditorBlockType
import com.mcqapp.ui.editor.EditorUiState
import com.mcqapp.ui.editor.OptionEditorState
import com.mcqapp.ui.editor.applyChemDiagram
import com.mcqapp.ui.editor.applyKeyedChemDiagram
import com.mcqapp.ui.editor.editorSnapshot
import com.mcqapp.ui.editor.isSignificant
import com.mcqapp.ui.editor.withChemSmiles
import com.mcqapp.util.mixedContentHtml
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BEHAVIOUR: pins the editor side of the chemistry feature — the seeded
 * benzene block, the dirty snapshot of a chem edit, the JSON round trip and
 * the rule that chem blocks are source-edited, never sent to MathLive.
 *
 * Pure JVM: no Robolectric, no Compose, so it runs on the ARM64 host too.
 */
class ChemistryEditorTest {

    /** A complete 8-byte PNG signature, so the shared strict validator accepts it. */
    private val validPng = CHEM_DIAGRAM_PREFIX + "iVBORw0KGgo="

    /** A second complete PNG, distinct from [validPng]. */
    private val otherPng = CHEM_DIAGRAM_PREFIX + "iVBORw0KGgoAAA=="

    private fun state(elements: List<ContentElement>, ids: List<String>? = null) = EditorUiState(
        loading = false,
        elements = elements,
        blockIds = ids ?: elements.indices.map { "b$it" }
    )

    @Test
    fun templatesCarryThePinnedStructures() {
        assertEquals(4, ChemTemplates.ALL.size)
        assertEquals(
            ChemTemplate(
                id = "benzene", label = "Benzene", smiles = "c1ccccc1", formula = "C6H6",
                caption = "benzene", spoken = "benzene ring, C six H six"
            ),
            ChemTemplates.BENZENE
        )
        assertEquals("C1CCCCC1", ChemTemplates.CYCLOHEXANE.smiles)
        assertEquals("C6H12", ChemTemplates.CYCLOHEXANE.formula)
        assertEquals("cyclohexane", ChemTemplates.CYCLOHEXANE.caption)
        assertEquals("CCO", ChemTemplates.ETHANOL.smiles)
        assertEquals("C2H5OH", ChemTemplates.ETHANOL.formula)
        assertEquals("ethanol", ChemTemplates.ETHANOL.caption)
        assertEquals("O", ChemTemplates.WATER.smiles)
        assertEquals("H2O", ChemTemplates.WATER.formula)
        assertEquals("water", ChemTemplates.WATER.caption)
        ChemTemplates.ALL.forEach {
            assertTrue("caption missing from ${it.id}", it.caption.isNotBlank())
            assertTrue("spoken missing from ${it.id}", it.spoken.isNotBlank())
        }
    }

    @Test
    fun addingAChemBlockSeedsBenzeneAndSelectsItsTemplate() {
        val element = EditorBlockType.CHEM.defaultElement()
        assertEquals(
            ContentElement.ChemElement(
                smiles = "c1ccccc1", formula = "C6H6", caption = "benzene",
                spoken = "benzene ring, C six H six"
            ),
            element
        )
        assertEquals(ChemTemplates.BENZENE, ChemTemplates.templateFor(element as ContentElement.ChemElement))
    }

    @Test
    fun handTypedSmilesDoesNotClaimATemplate() {
        val handTyped = ContentElement.ChemElement(smiles = "CC", formula = "C2H6")
        assertEquals(null, ChemTemplates.templateFor(handTyped))
        // Changing either handle stops the dropdown claiming the template;
        // free-text caption and spoken fields do not change its identity.
        assertEquals(null, ChemTemplates.templateFor(handTyped.copy(smiles = "c1ccccc1")))
        assertEquals(
            ChemTemplates.BENZENE,
            ChemTemplates.templateFor(ChemTemplates.BENZENE.toElement().copy(caption = "ring"))
        )
    }

    @Test
    fun chemBlockCountsAsVisibleContentOnlyWhenItCarriesSomething() {
        assertTrue(EditorBlockType.CHEM.defaultElement().isSignificant())
        assertFalse(ContentElement.ChemElement("").isSignificant())
        assertTrue(ContentElement.ChemElement("", caption = "benzene").isSignificant())
        assertTrue(ContentElement.ChemElement("", diagram = "data:image/png;base64,AAA").isSignificant())
    }

    @Test
    fun editingAnyChemFieldChangesTheEditorSnapshot() {
        val base = ContentElement.ChemElement("c1ccccc1", "C6H6", "benzene", "benzene ring", "")
        val signature = editorSnapshot(state(listOf(base)))

        val edits = listOf(
            base.copy(smiles = "CCO"),
            base.copy(formula = "C2H6O"),
            base.copy(caption = "benzene ring"),
            base.copy(spoken = "six carbon ring"),
            base.copy(diagram = "data:image/png;base64,AAA")
        )
        edits.forEach { edited ->
            assertNotEquals(
                "a change to $edited must mark the draft dirty",
                signature,
                editorSnapshot(state(listOf(edited)))
            )
        }
        assertEquals(signature, editorSnapshot(state(listOf(base.copy()))))
    }

    @Test
    fun chemSnapshotCannotCollideWithAnotherKindAndReorderingChangesIt() {
        val chem = ChemTemplates.BENZENE.toElement()
        assertNotEquals(
            editorSnapshot(state(listOf(chem))),
            editorSnapshot(state(listOf(ContentElement.TextElement("c1ccccc1"))))
        )

        val text = ContentElement.TextElement("What is shown?")
        val original = state(listOf(text, chem), ids = listOf("b0", "b1"))
        val reordered = state(listOf(chem, text), ids = listOf("b1", "b0"))
        assertNotEquals(editorSnapshot(original), editorSnapshot(reordered))
        // The id travels with its block: the chem block is still "b1" after
        // the move, so a preview keyed by identity is not handed to the text.
        assertEquals("b1", reordered.blockIds[0])
        assertEquals("b1", original.blockIds[1])
    }

    @Test
    fun chemElementsRoundTripThroughJsonAndKeepTheSameSnapshot() {
        val elements = listOf(
            ContentElement.TextElement("Identify the structures"),
            ChemTemplates.ETHANOL.toElement(),
            ContentElement.MathElement("<math><mi>x</mi></math>")
        )
        val encoded = Json.encodeToString(ContentElementListJson, elements)
        val decoded = Json.decodeFromString(ContentElementListJson, encoded)
        assertEquals(elements, decoded)
        assertEquals(editorSnapshot(state(elements)), editorSnapshot(state(decoded)))

        // The export-only diagram field survives too, though the editor never
        // writes it from its four text fields.
        val withDiagram = ChemTemplates.BENZENE.toElement().copy(diagram = "data:image/png;base64,AAA")
        val diagramJson = Json.encodeToString(ContentElementListJson, listOf(withDiagram))
        assertEquals(withDiagram, Json.decodeFromString(ContentElementListJson, diagramJson).single())
        assertEquals(
            editorSnapshot(state(listOf(withDiagram))),
            editorSnapshot(state(Json.decodeFromString(ContentElementListJson, diagramJson)))
        )
    }

    @Test
    fun capturedPngRoundTripsIntoTheChemElement() {
        val chem = ChemTemplates.BENZENE.toElement()
        val before = state(listOf(chem), listOf("chem-1"))
        val png = CHEM_DIAGRAM_PREFIX + "iVBORw0KGgoAAA"
        val after = applyChemDiagram(before, "chem-1", png)
        assertNotSame(before, after)
        assertEquals(chem.copy(diagram = png), after.elements.single())
        val encoded = Json.encodeToString(ContentElementListJson, after.elements)
        assertEquals(
            chem.copy(diagram = png),
            Json.decodeFromString(ContentElementListJson, encoded).single()
        )
    }

    @Test
    fun blankInvalidOrUnknownPngCapturesAreIgnored() {
        val before = state(listOf(ChemTemplates.BENZENE.toElement()), listOf("chem-1"))
        val rejected = listOf(
            "", "   ", "AAA", "data:image/jpeg;base64,AAA", CHEM_DIAGRAM_PREFIX,
            CHEM_DIAGRAM_PREFIX + "AAA",
            CHEM_DIAGRAM_PREFIX + "bm90IGEgcG5n",
            "https://example.com/structure.png"
        )
        rejected.forEach { assertSame("rejected $it", before, applyChemDiagram(before, "chem-1", it)) }
        assertSame(before, applyChemDiagram(before, "missing", validPng))
        // A non-chem block with the same id must not turn into a diagram either.
        val text = state(listOf(ContentElement.TextElement("plain")), listOf("chem-1"))
        assertSame(text, applyChemDiagram(text, "chem-1", validPng))
    }

    @Test
    fun anUnchangedDiagramIsANoOpAndDoesNotTouchDirty() {
        val chem = ChemTemplates.BENZENE.toElement().copy(diagram = validPng)
        val before = state(listOf(chem), listOf("chem-1")).copy(isDirty = false)
        assertSame(before, applyChemDiagram(before, "chem-1", validPng))
        val changed = applyChemDiagram(before, "chem-1", otherPng)
        assertNotSame(before, changed)
        assertFalse("a capture is not a user edit", changed.isDirty)
        assertTrue("the captured diagram is persisted content", changed.elements.single().toString().contains(otherPng))
    }

    @Test
    fun diagramCaptureTargetsTheNamedBlockInAnyList() {
        val png = validPng
        val option = OptionEditorState(
            id = "a",
            elements = listOf(EditorBlockType.CHEM.defaultElement()),
            blockIds = listOf("opt-chem")
        )
        val before = state(listOf(ContentElement.TextElement("q")), listOf("body")).copy(
            explanationElements = listOf(EditorBlockType.CHEM.defaultElement()),
            explanationBlockIds = listOf("exp-chem"),
            options = listOf(option)
        )
        val inOption = applyChemDiagram(before, "opt-chem", png)
        assertEquals(png, (inOption.options.single().elements.single() as ContentElement.ChemElement).diagram)
        assertEquals("", (inOption.explanationElements.single() as ContentElement.ChemElement).diagram)
        val inExplanation = applyChemDiagram(before, "exp-chem", png)
        assertEquals(png, (inExplanation.explanationElements.single() as ContentElement.ChemElement).diagram)
        assertEquals("", (inExplanation.options.single().elements.single() as ContentElement.ChemElement).diagram)
    }

    @Test
    fun aiSchemaChemistryChipIsLocalisedAndLinked() {
        val en = File("src/main/res/values/strings_chem.xml").readText()
        val hi = File("src/main/res/values-hi/strings_chem.xml").readText()
        val declaration = Regex("""<string\s+name="ai_schema_block_chem"\s*>""")
        assertTrue("English strings_chem.xml is missing ai_schema_block_chem", declaration.containsMatchIn(en))
        assertTrue("Hindi strings_chem.xml is missing ai_schema_block_chem", declaration.containsMatchIn(hi))
        val screen = File("src/main/java/com/mcqapp/ui/settings/AiSchemaScreen.kt").readText()
        assertTrue(
            "AiSchemaScreen.kt must reference R.string.ai_schema_block_chem",
            "R.string.ai_schema_block_chem" in screen
        )
    }

    @Test
    fun chemEditorStringsAreLocalisedAndReferencedByTheEditorSink() {
        val english = File("src/main/res/values/strings_chemistry_editor.xml").readText()
        val hindi = File("src/main/res/values-hi/strings_chemistry_editor_hi.xml").readText()
        val entry = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""")
        fun entries(text: String) = entry.findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val en = entries(english)
        val hi = entries(hindi)
        assertEquals(en.keys, hi.keys)
        assertTrue("expected the editor's own string set", en.keys.all { it.startsWith("chem_editor_") })
        assertTrue("%1\$s" in en.getValue("chem_editor_structure_cd"))
        assertTrue("%1\$s" in hi.getValue("chem_editor_structure_cd"))

        val sink = File("src/main/java/com/mcqapp/ui/editor/BlockListEditor.kt").readText()
        en.keys.forEach { name ->
            assertTrue("$name is declared but never used by the editor", "R.string.$name" in sink)
        }
    }

    @Test
    fun chemBlocksAreNeverRoutedToMathLive() {
        val editor = File("src/main/java/com/mcqapp/ui/editor/BlockListEditor.kt").readText()
        val start = editor.indexOf("is ContentElement.ChemElement ->")
        assertTrue("BlockListEditor has no chem branch", start >= 0)
        val end = editor.indexOf("is ContentElement.TableElement ->", start)
        assertTrue("chem branch has no following branch boundary", end > start)
        val chemBranch = editor.substring(start, end)
        assertTrue("chem must use the shared renderer", "ChemBlockEditor(" in chemBranch)
        assertFalse("MathLive silently drops \\ce{} into empty MathML", "MathLiveEditor(" in chemBranch)
        assertFalse("mathMlToLatex(" in chemBranch)
        assertFalse("\\ce{" in chemBranch)
        // The shared preview, not a second WYSIWYG implementation.
        assertTrue("ContentElements(" in editor && "elements = listOf(element)" in editor)
    }

    @Test
    fun editingSmilesDropsTheCapturedPngButKeepsTheOtherFields() {
        val png = CHEM_DIAGRAM_PREFIX + "BENZENE"
        val original = ChemTemplates.BENZENE.toElement().copy(diagram = png)
        val edited = withChemSmiles(original, "c")

        assertNotSame(original, edited)
        assertEquals("c", edited.smiles)
        assertEquals("", edited.diagram)
        assertEquals(original.formula, edited.formula)
        assertEquals(original.caption, edited.caption)
        assertEquals(original.spoken, edited.spoken)
    }

    @Test
    fun editedChemElementRendersFromSmilesInsteadOfTheStalePng() {
        // A real PNG signature, so the renderer's diagram validation accepts
        // it and takes the raster branch before the edit.
        val png = CHEM_DIAGRAM_PREFIX + "iVBORw0KGgo="
        val stale = ChemTemplates.BENZENE.toElement().copy(diagram = png)
        val edited = withChemSmiles(stale, "c")

        val staleHtml = mixedContentHtml(listOf(stale), 18f, "#000000")
        assertTrue("the old diagram is rendered as a raster", "<img src=\"$png\"" in staleHtml)
        assertFalse("SmilesDrawer.parse" in staleHtml)

        val freshHtml = mixedContentHtml(listOf(edited), 18f, "#000000")
        assertTrue("the edited source must be drawn by SmilesDrawer", "SmilesDrawer.parse" in freshHtml)
        assertTrue("the drawer needs its SVG target", "<svg id=" in freshHtml)
        assertFalse("no stale raster may survive the edit", "<img src=\"$CHEM_DIAGRAM_PREFIX" in freshHtml)
        assertNotEquals("the preview HTML must change", staleHtml, freshHtml)
    }

    @Test
    fun aCaptureQueuedForAnOldSmilesIsRejectedButACurrentOneIsStored() {
        val chem = ChemTemplates.BENZENE.toElement()
        val before = state(listOf(chem), listOf("chem-1"))
        val oldKey = "chem-1@${chem.smiles}"
        val current = withChemSmiles(chem, "c")
        val edited = before.copy(elements = listOf(current))

        // The callback that was already in flight when the user typed carries
        // the old key; it must not stamp the benzene raster onto "c".
        assertSame(edited, applyKeyedChemDiagram(edited, oldKey, otherPng))
        assertSame(edited, applyKeyedChemDiagram(edited, "chem-1", otherPng))
        assertSame(edited, applyKeyedChemDiagram(edited, "chem-1@", otherPng))

        val freshPng = validPng
        val stored = applyKeyedChemDiagram(edited, "chem-1@c", freshPng)
        assertNotSame(edited, stored)
        assertEquals(freshPng, (stored.elements.single() as ContentElement.ChemElement).diagram)
        assertFalse("a capture is still not a user edit", stored.isDirty)
        // The invalid-PNG and no-op guards still apply on the keyed path.
        assertSame(edited, applyKeyedChemDiagram(edited, "chem-1@c", "data:image/jpeg;base64,AAA"))
        assertSame(edited, applyKeyedChemDiagram(edited, "chem-1@c", CHEM_DIAGRAM_PREFIX + "AAA"))
        assertSame(stored, applyKeyedChemDiagram(stored, "chem-1@c", freshPng))
    }

    @Test
    fun repeatedIdenticalSmilesEditIsANoOp() {
        val chem = ChemTemplates.BENZENE.toElement().copy(diagram = CHEM_DIAGRAM_PREFIX + "AAA")
        assertSame(chem, withChemSmiles(chem, chem.smiles))
        assertSame(chem, withChemSmiles(chem, "  ${chem.smiles}  "))
        val edited = withChemSmiles(chem, "c")
        assertSame(edited, withChemSmiles(edited, "c"))
    }

    @Test
    fun pickingATemplateStartsFromAFreshDiagramFreeElement() {
        ChemTemplates.ALL.forEach { template ->
            val element = template.toElement()
            assertEquals("", element.diagram)
            assertEquals(template.smiles, element.smiles)
        }
        // Changing the SMILES by template goes through the same stale-raster
        // contract as typing it: toElement() never carries the old PNG over.
        val withDiagram = ChemTemplates.BENZENE.toElement().copy(diagram = CHEM_DIAGRAM_PREFIX + "AAA")
        assertEquals("", withChemSmiles(withDiagram, ChemTemplates.WATER.smiles).diagram)
    }
}
