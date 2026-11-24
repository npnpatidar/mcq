package com.mcqapp.domain.ai

/**
 * The four controls the AI-schema screen exposes. Pure enums: the UI maps them
 * to localized labels, and the generator maps them to schema keys, so a new
 * combination cannot silently drift between the two.
 */
enum class QuestionKind { FLASHCARDS, MCQ, MIXED }

/** Text is always on; the other four are optional. */
enum class ContentBlock { TEXT, TABLE, MATH, IMAGE, CHEM }

enum class PassageMode { NONE, TEXT_ONLY, RICH }

enum class SchemaWrapper { FULL_BANK, PAPERLESS }

/**
 * One request from the generator screen. Defaults are the smallest useful
 * shape: mixed questions, text-only blocks, no passage, full bank.
 */
data class SchemaOptions(
    val kind: QuestionKind = QuestionKind.MIXED,
    val table: Boolean = false,
    val math: Boolean = false,
    val image: Boolean = false,
    val passage: PassageMode = PassageMode.NONE,
    val wrapper: SchemaWrapper = SchemaWrapper.FULL_BANK,
    /**
     * Root containers to generate. Null means "follow [wrapper]" / [passage],
     * so existing callers — including `copy(wrapper = …)` — keep their old
     * shape. The screen's toggles pass an explicit true/false instead.
     */
    val includePapers: Boolean? = null,
    val includeCategories: Boolean? = null,
    val includePassages: Boolean? = null,
    /**
     * Chemistry blocks are SMILES structures drawn offline by the bundled
     * SmilesDrawer asset. Off by default so existing callers keep their shape;
     * last in the constructor so positional callers are unaffected.
     */
    val chem: Boolean = false
) {
    /** The block types the schema must declare, text first and always present. */
    val blocks: List<ContentBlock> = buildList {
        add(ContentBlock.TEXT)
        if (table) add(ContentBlock.TABLE)
        if (math) add(ContentBlock.MATH)
        if (image) add(ContentBlock.IMAGE)
        if (chem) add(ContentBlock.CHEM)
    }

    /** Root `papers` are on unless the caller explicitly turned them off. */
    val hasPapers: Boolean get() = includePapers ?: (wrapper == SchemaWrapper.FULL_BANK)

    /** Categories are inside a paper, or at the root when papers are off. */
    val hasCategories: Boolean get() = includeCategories ?: (wrapper == SchemaWrapper.FULL_BANK)

    val hasPassages: Boolean get() = includePassages ?: (passage != PassageMode.NONE)

    /** An explicit "passages on" with no chosen mode still yields a passage. */
    val effectivePassage: PassageMode get() = when {
        !hasPassages -> PassageMode.NONE
        passage != PassageMode.NONE -> passage
        else -> PassageMode.TEXT_ONLY
    }
}
