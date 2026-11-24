package com.mcqapp.domain.ai

/**
 * The four controls the AI-schema screen exposes. Pure enums: the UI maps them
 * to localized labels, and the generator maps them to schema keys, so a new
 * combination cannot silently drift between the two.
 */
enum class QuestionKind { FLASHCARDS, MCQ, MIXED }

/** Text is always on; the other three are optional. */
enum class ContentBlock { TEXT, TABLE, MATH, IMAGE }

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
    val includePassages: Boolean? = null
) {
    /** The block types the schema must declare, text first and always present. */
    val blocks: List<ContentBlock> = buildList {
        add(ContentBlock.TEXT)
        if (table) add(ContentBlock.TABLE)
        if (math) add(ContentBlock.MATH)
        if (image) add(ContentBlock.IMAGE)
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
