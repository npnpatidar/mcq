package com.mcqapp.domain

import com.mcqapp.data.io.FALLBACK_CLOZE_EXPLANATION

/**
 * The single rule for turning a one-option question into a flashcard's back.
 *
 * A flashcard's front is the stem; its back is the answer followed by whatever
 * explanation the card already had:
 *
 * - the option's own content becomes the answer and comes first
 *   ([optionElements], falling back to the flat [optionText] when a file only
 *   carries text);
 * - the existing explanation blocks are appended unchanged;
 * - an absent explanation, or the placeholder Anki's cloze importer writes
 *   ([FALLBACK_CLOZE_EXPLANATION]), is *replaced* by the answer rather than
 *   appended, so the back does not end in noise.
 *
 * A blank explanation with an image is still a back: [explanationImage] is
 * judged here so an image-only explanation is not treated as absent. The
 * caller keeps that image beside the returned blocks — this function only
 * decides the block list, so it stays pure and the same rule can serve the
 * migration, the importers and the editor.
 *
 * A blank [optionText] with no elements falls back to one blank text block,
 * mirroring how an empty body is normalized elsewhere; nothing throws.
 */
fun backFromSingleOption(
    optionElements: List<ContentElement>,
    optionText: String,
    explanationElements: List<ContentElement>,
    explanationImage: String?
): List<ContentElement> {
    val answer = optionElements.ifEmpty { listOf(ContentElement.TextElement(optionText)) }
    if (!hasRealExplanation(explanationElements, explanationImage)) return answer
    return answer + explanationElements
}

/** A merged card back: its visible blocks and the scalar picture beside them. */
data class BackContent(
    val elements: List<ContentElement>,
    val image: String?
)

/**
 * The complete back for a one-option question, picture included.
 *
 * [backFromSingleOption] decides the blocks; this decides where the *answer's*
 * own picture goes so nothing is lost:
 *
 * - the back had no scalar picture: the answer's picture becomes that scalar,
 *   unless the merged blocks already show it (then it stays an element only);
 * - the back already had a scalar picture: the answer's different picture is
 *   appended as an [ContentElement.ImageElement], and an identical source is
 *   not duplicated.
 *
 * The importers and the 28→29 migration call this, so a converted card looks
 * the same on every path.
 */
fun mergedBackContent(
    optionElements: List<ContentElement>,
    optionText: String,
    optionImage: String?,
    explanationElements: List<ContentElement>,
    explanationImage: String?
): BackContent {
    val blocks = backFromSingleOption(
        optionElements = optionElements,
        optionText = optionText,
        explanationElements = explanationElements,
        explanationImage = explanationImage
    )
    val answerImage = optionImage?.takeIf { it.isNotBlank() }
    val showsAnswerImage = answerImage != null &&
        blocks.any { it is ContentElement.ImageElement && it.src == answerImage }
    val appended = when {
        answerImage == null -> null
        explanationImage == null -> null
        answerImage == explanationImage -> null
        showsAnswerImage -> null
        else -> ContentElement.ImageElement(answerImage)
    }
    val image = explanationImage ?: answerImage?.takeUnless { showsAnswerImage }
    return BackContent(blocks + listOfNotNull(appended), image)
}

/**
 * Whether the explanation carries anything a learner should see. A blank
 * block list, a blank text run and Anki's cloze placeholder all read as "no
 * explanation"; a picture, table, formula or real prose reads as one.
 */
private fun hasRealExplanation(
    elements: List<ContentElement>,
    image: String?
): Boolean {
    if (!image.isNullOrBlank()) return true
    if (!elements.hasVisibleContent) return false
    val placeholderOnly = elements.all { it is ContentElement.TextElement } &&
        elements.textContent.trim() == FALLBACK_CLOZE_EXPLANATION
    return !placeholderOnly
}
