package com.mcqapp.ui.editor

import com.mcqapp.domain.ContentElement

/**
 * Changes a chem block's SMILES source without leaving a stale raster behind.
 *
 * The shared renderer prefers a valid [ContentElement.ChemElement.diagram]
 * over drawing from SMILES, and its PNG branch does not depend on the SMILES
 * text at all. Keeping the old PNG after a SMILES edit therefore produced an
 * unchanged WebView page — the preview kept showing the previous structure.
 * Trimming is only for the no-op check; the typed value itself is stored.
 */
internal fun withChemSmiles(
    element: ContentElement.ChemElement,
    smiles: String
): ContentElement.ChemElement {
    if (element.smiles.trim() == smiles.trim()) return element
    return element.copy(smiles = smiles, diagram = "")
}

/**
 * A ready-made chemical structure the editor can insert in one tap.
 *
 * Pure data: no Android, no Compose, no resources. The strings are the
 * canonical caption/spoken copy stored with the element (they are content,
 * not UI chrome — the same values the shared renderer and exports read), so
 * they stay identical across locales and are safe to assert in a JVM test.
 */
internal data class ChemTemplate(
    val id: String,
    /** Short name shown in the template dropdown. */
    val label: String,
    val smiles: String,
    val formula: String,
    val caption: String,
    val spoken: String
) {
    fun toElement(): ContentElement.ChemElement = ContentElement.ChemElement(
        smiles = smiles,
        formula = formula,
        caption = caption,
        spoken = spoken
    )
}

/**
 * The four structures offered by the editor's template dropdown.
 *
 * [BENZENE] is also what [EditorBlockType.CHEM] seeds, so adding a chem block
 * immediately shows a structure instead of an empty field.
 */
internal object ChemTemplates {
    val BENZENE = ChemTemplate(
        id = "benzene",
        label = "Benzene",
        smiles = "c1ccccc1",
        formula = "C6H6",
        caption = "benzene",
        spoken = "benzene ring, C six H six"
    )
    val CYCLOHEXANE = ChemTemplate(
        id = "cyclohexane",
        label = "Cyclohexane",
        smiles = "C1CCCCC1",
        formula = "C6H12",
        caption = "cyclohexane",
        spoken = "cyclohexane ring, C six H twelve"
    )
    val ETHANOL = ChemTemplate(
        id = "ethanol",
        label = "Ethanol",
        smiles = "CCO",
        formula = "C2H5OH",
        caption = "ethanol",
        spoken = "ethanol, C two H five O H"
    )
    val WATER = ChemTemplate(
        id = "water",
        label = "Water",
        smiles = "O",
        formula = "H2O",
        caption = "water",
        spoken = "water, H two O"
    )

    val ALL: List<ChemTemplate> = listOf(BENZENE, CYCLOHEXANE, ETHANOL, WATER)

    /**
     * The template an element currently matches, or null for hand-typed SMILES.
     * Matching both handles makes the dropdown honest: changing either field
     * turns the selection into "Custom" instead of still claiming a template.
     */
    fun templateFor(element: ContentElement.ChemElement): ChemTemplate? =
        ALL.firstOrNull { it.smiles == element.smiles && it.formula == element.formula }
}
