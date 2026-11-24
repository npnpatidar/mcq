package com.mcqapp.ui.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcqapp.McqApplication
import com.mcqapp.R
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.io.CardContent
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.hasVisibleContent
import com.mcqapp.domain.textContent
import com.mcqapp.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What kind of study card a question is.
 *
 * A flashcard has no options: its stem is the front and its explanation is the
 * back. A multiple-choice question keeps today's shape (at least two options
 * and an answer key). The type is derived from the loaded question — see
 * [cardTypeOf] — and then belongs to the form, because a user can switch it.
 */
enum class EditorCardType {
    MULTIPLE_CHOICE,
    FLASHCARD
}

/**
 * The card type a loaded question has: no options means a flashcard.
 *
 * Exactly one option is deliberately neither shape. That combination is
 * normalised to a flashcard on import and by the database migration, so it
 * cannot reach the editor from stored data; a form that ends up with one
 * option anyway stays a question and stays unsavable until it is fixed
 * ([EditorUiState.canSave]).
 */
internal fun cardTypeOf(optionCount: Int): EditorCardType =
    if (optionCount == 0) EditorCardType.FLASHCARD else EditorCardType.MULTIPLE_CHOICE

/** Block kinds the editor can append to an element list. */
enum class EditorBlockType {
    TEXT,
    IMAGE,
    TABLE,
    MATH,
    CHEM;

    fun defaultElement(): ContentElement = when (this) {
        TEXT -> ContentElement.TextElement("")
        IMAGE -> ContentElement.ImageElement("")
        TABLE -> ContentElement.TableElement(listOf(listOf("")))
        MATH -> ContentElement.MathElement("<math><mi></mi></math>")
        // Seeded with benzene so a newly added block draws a visible structure
        // immediately; an empty SMILES field would show nothing and look broken.
        CHEM -> ChemTemplates.BENZENE.toElement()
    }
}

/**
 * What the editor is editing, carried explicitly by the route.
 *
 * The distinction used to be inferred from a process-global import holder:
 * after process death the holder was empty and an import edit silently became
 * a database edit of whatever row shared its id (question_editor_audit.md
 * H-04). An explicit source keeps that fallback from existing at all.
 */
enum class EditorSource {
    /** A stored database row named by the route's questionId. */
    DATABASE,
    /** A new question; no row exists yet. */
    NEW,
    /** An in-memory copy in the import preview's list. */
    IMPORT,
    /** Not stated by the route (older callers); resolved from the id. */
    UNSPECIFIED;

    companion object {
        /** Unknown tokens stay UNSPECIFIED, so an older link still opens. */
        fun fromRoute(raw: String): EditorSource = when (raw) {
            "database" -> DATABASE
            "new" -> NEW
            "import" -> IMPORT
            else -> UNSPECIFIED
        }

        fun token(source: EditorSource): String = when (source) {
            DATABASE -> "database"
            NEW -> "new"
            IMPORT -> "import"
            UNSPECIFIED -> ""
        }
    }
}

/**
 * Where a picked image belongs, described by stable block identity rather
 * than a callback capturing a list index.
 *
 * The write-back used to live in `remember` and captured the block's index,
 * so a configuration change lost it and a rotation could redeliver the pick
 * with nowhere to put it (question_editor_audit.md M-05). The descriptor is
 * resolved against the current state when the URI returns.
 */
sealed interface ImageTarget {
    /** The block's stable id, from [EditorUiState.blockIds]. */
    val blockId: String

    data class Body(override val blockId: String) : ImageTarget
    data class Explanation(override val blockId: String) : ImageTarget
    data class Option(val optionId: String, override val blockId: String) : ImageTarget
}

/**
 * Where a MathLive formula edit belongs, described by stable block identity
 * rather than a list index.
 *
 * The WebView's JS bridge is installed once by `AndroidView.factory` and is
 * never rebound. It can safely carry [blockId] (the WebView is keyed by that
 * identity) but not a position: after a reorder the block moves, and a captured
 * index would write the formula into whichever block now occupies the old slot
 * (review-reports/08 H5). Resolution against the current lists happens in
 * [applyMathTarget], exactly as it does for picked images.
 */
sealed interface MathTarget {
    /** The block's stable id, from the corresponding blockIds list. */
    val blockId: String

    data class Body(override val blockId: String) : MathTarget
    data class Explanation(override val blockId: String) : MathTarget
    data class Option(val optionId: String, override val blockId: String) : MathTarget
}

/**
 * What the editor is doing right now. One operation at a time: a second
 * Save/Prev/Next/Delete while one is in flight is refused, and the screen
 * disables those controls, so two moves cannot interleave their session
 * arithmetic (question_editor_audit.md H-03, M-13).
 */
enum class EditorOperation { IDLE, SAVING, MOVING, DELETING }

/**
 * Whether a block counts as content for validation. Whitespace-only text
 * and empty tables do not; any image src or formula does (even a
 * placeholder one — clearing the field is what removes it).
 */
internal fun ContentElement.isSignificant(): Boolean = when (this) {
    is ContentElement.TextElement -> text.isNotBlank()
    is ContentElement.ImageElement -> src.isNotBlank()
    is ContentElement.TableElement -> rows.any { row -> row.any { it.isNotBlank() } }
    is ContentElement.MathElement -> mathml.isNotBlank()
    is ContentElement.ChemElement -> smiles.isNotBlank() || formula.isNotBlank() ||
        caption.isNotBlank() || spoken.isNotBlank() || diagram.isNotBlank()
}

data class EditorUiState(
    val loading: Boolean = true,
    val questionId: String = "",
    val paperId: String = "",
    val categoryId: String = "",
    val elements: List<ContentElement> = emptyList(),
    /** Stable identity of each body block, parallel to [elements]. */
    val blockIds: List<String> = emptyList(),
    val image: String = "",
    val explanationElements: List<ContentElement> = emptyList(),
    /** Stable identity of each explanation block, parallel to that list. */
    val explanationBlockIds: List<String> = emptyList(),
    val explanationImage: String = "",
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val marks: String = "1",
    val tags: String = "",
    val options: List<OptionEditorState> = emptyList(),
    /**
     * Whether this question is a multiple-choice question or a flashcard.
     * Loaded from the question ([cardTypeOf]) and reset with it on Prev/Next,
     * so one card's type can never leak onto the next.
     */
    val cardType: EditorCardType = EditorCardType.MULTIPLE_CHOICE,
    val categories: List<CategoryNode> = emptyList(),
    /** Passages of this paper for the assign picker; empty when none exist. */
    val passages: List<com.mcqapp.domain.Passage> = emptyList(),
    val isNew: Boolean = true,
    /** The passage this question belongs to, editable in the picker. */
    val passageId: String? = null,
    /** Set when the initial load failed; the screen offers the message
     *  instead of an eternal "Loading…" (consolidated_bugs.md J5). */
    val loadError: String? = null,
    /** The single in-flight editor operation, if any. */
    val operation: EditorOperation = EditorOperation.IDLE,
    /** Set when a save or delete failed, so the failure is visible instead
     *  of a crash (consolidated_bugs.md J7). */
    val saveError: String? = null,
    /** A Prev/Next move failed; the current form is kept intact. */
    val moveError: String? = null,
    /** The persisted form differs from what is on screen. */
    val isDirty: Boolean = false,
    /**
     * [CardContent] fingerprint of the form as last persisted (load or save).
     * Null when there is no schedule to reset: a new question, an import
     * preview draft, or an editor that failed to load a stored row.
     */
    val baselineCardFingerprint: String? = null,
    /**
     * The route said this is an import-preview edit, but the in-memory draft
     * was gone (process death). The editor is inert: nothing may be loaded,
     * saved or deleted, least of all a database row with the same id
     * (question_editor_audit.md H-04).
     */
    val importContextMissing: Boolean = false,
    /** The block awaiting a picked image; null when no pick is pending. */
    val pendingImageTarget: ImageTarget? = null,
    /** A picked image could not be read/encoded; nothing was written. */
    val imageError: String? = null
) {
    /**
     * [CardContent] hash of THIS form, cached on the immutable state instance.
     * A `copy()` (a keystroke, a load, a move) creates a new instance and so a
     * new cache; repeated recompositions of the same instance reuse it. Before
     * this, the disclosure getter hashed every block — including base64 images
     * — on Main on every recomposition (round-2 E9).
     */
    private val cardFingerprint: String by lazy { editorCardFingerprint(this) }

    /** Any write (save/delete) or move is in flight; controls gate on it. */
    val busy: Boolean get() = operation != EditorOperation.IDLE

    /**
     * Saving this form would change the content the learner studied, so
     * `QuestionStore.saveQuestion` deletes the card's `card_state` row. Derived
     * from the same [CardContent] fingerprint that drives that deletion, not
     * from [editorSnapshot] (which also covers explanation, marks, difficulty,
     * tags and category — none of which reset a schedule). Review history stays.
     */
    val willResetSchedule: Boolean
        get() = baselineCardFingerprint != null && cardFingerprint != baselineCardFingerprint

    /** Backward-compat text for labels and previews. */
    val text: String get() = elements.textContent
    val explanation: String get() = explanationElements.textContent

    /** A nonblank marks field must hold a valid number (M-08). */
    val marksValid: Boolean get() = com.mcqapp.domain.Marks.isValid(marks)

    /** The front — the question itself — has something a learner can see. */
    val hasFrontContent: Boolean
        get() = elements.any { it.isSignificant() } || image.isNotBlank()

    /**
     * A flashcard's back carries something the learner can read. The back *is*
     * the card's answer, so a blank back has nothing to reveal: the same rule
     * Study uses to decide whether a card can be answered
     * ([hasVisibleContent] plus the scalar back image).
     */
    val hasSaveableBack: Boolean
        get() = explanationElements.hasVisibleContent || explanationImage.isNotBlank()

    /** True when at least one option holds typed content or an image. */
    val hasTypedOptions: Boolean get() = options.any { it.hasContent }

    /**
     * A question saves when it holds at least one significant block of any
     * kind and its shape is complete. Plain text is no longer required: an
     * image-only or formula-only question is valid.
     *
     * A multiple-choice question needs at least two options that each hold
     * content. A flashcard needs **no** options and a visible back. Exactly one
     * option is invalid in both modes: one option is an answer, not a choice.
     *
     * This is a property of the state object so screens derive it from the
     * *collected* state. Reading it through the ViewModel instead
     * (`viewModel.canProceed()`) left the calling composable with no state
     * read of its own, so the value was computed once while the form was
     * still loading and never again — the Save button never enabled.
     */
    val canSave: Boolean
        get() = marksValid && hasFrontContent && when (cardType) {
            EditorCardType.FLASHCARD -> options.isEmpty() && hasSaveableBack
            EditorCardType.MULTIPLE_CHOICE -> options.size >= 2 && options.all { it.hasContent }
        }
}

data class OptionEditorState(
    val id: String,
    val elements: List<ContentElement> = emptyList(),
    /** Stable identity of each option block, parallel to [elements]. */
    val blockIds: List<String> = emptyList(),
    val image: String = "",
    val isCorrect: Boolean = false
) {
    val text: String get() = elements.textContent

    /**
     * The option carries something a learner can read. A legacy image-only
     * option loads as one empty text element plus the scalar image; the image
     * is its content (consolidated_bugs.md J8).
     */
    val hasContent: Boolean get() = elements.any { it.isSignificant() } || image.isNotBlank()
}

/** Replaces the table at [index], or returns [elements] unchanged. */
internal fun updateTableAt(
    elements: List<ContentElement>,
    index: Int,
    transform: (ContentElement.TableElement) -> ContentElement.TableElement
): List<ContentElement> {
    val current = elements.getOrNull(index) as? ContentElement.TableElement ?: return elements
    return elements.toMutableList().also { it[index] = transform(current) }
}

/** Out-of-range cell writes are ignored, as the grid editor's bounds are. */
internal fun ContentElement.TableElement.withCell(
    row: Int,
    col: Int,
    value: String
): ContentElement.TableElement {
    if (row !in rows.indices || col !in rows[row].indices) return this
    return copy(rows = rows.mapIndexed { r, cells ->
        if (r != row) cells
        else cells.toMutableList().also { it[col] = value }
    })
}

internal fun ContentElement.TableElement.withRowAdded(): ContentElement.TableElement {
    val width = rows.firstOrNull()?.size ?: 1
    return copy(rows = rows + listOf(List(width) { "" }))
}

/** The last row is kept: a table must always have one cell to edit. */
internal fun ContentElement.TableElement.withRowRemoved(row: Int): ContentElement.TableElement {
    if (rows.size <= 1 || row !in rows.indices) return this
    return copy(rows = rows.toMutableList().also { it.removeAt(row) })
}

internal fun ContentElement.TableElement.withColumnAdded(): ContentElement.TableElement =
    copy(rows = rows.map { it + "" })

/**
 * Whether removing [col] does anything. The last column is kept, and a ragged
 * row that lacks this index makes the removal a no-op — the UI uses the same
 * rule to disable its button, so control and data agree.
 */
internal fun ContentElement.TableElement.canRemoveColumn(col: Int): Boolean {
    if ((rows.firstOrNull()?.size ?: 0) <= 1) return false
    return rows.all { col in it.indices }
}

/** The last column is kept, and a ragged row makes the removal a no-op. */
internal fun ContentElement.TableElement.withColumnRemoved(col: Int): ContentElement.TableElement {
    if (!canRemoveColumn(col)) return this
    return copy(rows = rows.map { row -> row.toMutableList().also { it.removeAt(col) } })
}

/**
 * Writes [src] into the block the target names, resolving the id against the
 * *current* lists. A target whose block or option is gone (deleted while the
 * picker was open) writes nothing rather than a neighbouring block.
 */
internal fun applyImageTarget(s: EditorUiState, target: ImageTarget, src: String): EditorUiState {
    val image = ContentElement.ImageElement(src)
    return when (target) {
        is ImageTarget.Body -> {
            val i = s.blockIds.indexOf(target.blockId)
            if (i !in s.elements.indices) s
            else s.copy(elements = s.elements.toMutableList().also { it[i] = image })
        }
        is ImageTarget.Explanation -> {
            val i = s.explanationBlockIds.indexOf(target.blockId)
            if (i !in s.explanationElements.indices) s
            else s.copy(
                explanationElements = s.explanationElements.toMutableList().also { it[i] = image }
            )
        }
        is ImageTarget.Option -> {
            val option = s.options.firstOrNull { it.id == target.optionId } ?: return s
            val i = option.blockIds.indexOf(target.blockId)
            if (i !in option.elements.indices) s
            else s.copy(options = s.options.map { o ->
                if (o.id != option.id) o
                else o.copy(elements = o.elements.toMutableList().also { it[i] = image })
            })
        }
    }
}

/**
 * Writes [mml] into the block named by [target], resolving its id against the
 * *current* lists. A target whose block or option is gone (deleted while the
 * WebView was still mounted) writes nothing rather than a neighbouring block.
 *
 * This is the formula counterpart of [applyImageTarget]: the positional index
 * a once-installed JS callback used to capture is recovered here at write time
 * from the stable id, so a reorder cannot corrupt a different formula (H5).
 */
internal fun applyMathTarget(s: EditorUiState, target: MathTarget, mml: String): EditorUiState {
    val math = ContentElement.MathElement(mml)
    return when (target) {
        is MathTarget.Body -> {
            val i = s.blockIds.indexOf(target.blockId)
            if (i !in s.elements.indices) s
            else s.copy(elements = s.elements.toMutableList().also { it[i] = math })
        }
        is MathTarget.Explanation -> {
            val i = s.explanationBlockIds.indexOf(target.blockId)
            if (i !in s.explanationElements.indices) s
            else s.copy(
                explanationElements = s.explanationElements.toMutableList().also { it[i] = math }
            )
        }
        is MathTarget.Option -> {
            val option = s.options.firstOrNull { it.id == target.optionId } ?: return s
            val i = option.blockIds.indexOf(target.blockId)
            if (i !in option.elements.indices) s
            else s.copy(options = s.options.map { o ->
                if (o.id != option.id) o
                else o.copy(elements = o.elements.toMutableList().also { it[i] = math })
            })
        }
    }
}

/** Every renderer-captured structure diagram is a PNG data URI with this prefix. */
internal const val CHEM_DIAGRAM_PREFIX = "data:image/png;base64,"

/**
 * Stores a renderer-captured PNG on the chem block named by [blockId].
 *
 * A diagram is generated by the renderer, not typed by the user: the pure
 * helper never touches the draft's dirty flag, and it returns the *same*
 * instance for a missing block, a non-PNG (or empty) value and an unchanged
 * value — the last case is what stops a capture callback from feeding itself.
 * All three block lists are searched because a chem block can live in the
 * question body, an explanation or an option, and block ids are unique here.
 */
internal fun applyChemDiagram(
    s: EditorUiState,
    blockId: String,
    pngDataUri: String
): EditorUiState {
    if (!pngDataUri.startsWith(CHEM_DIAGRAM_PREFIX) ||
        pngDataUri.length == CHEM_DIAGRAM_PREFIX.length
    ) return s

    fun replace(elements: List<ContentElement>, ids: List<String>): List<ContentElement> {
        val i = ids.indexOf(blockId)
        if (i !in elements.indices) return elements
        val el = elements[i] as? ContentElement.ChemElement ?: return elements
        if (el.diagram == pngDataUri) return elements
        return elements.toMutableList().also { it[i] = el.copy(diagram = pngDataUri) }
    }

    val body = replace(s.elements, s.blockIds)
    if (body !== s.elements) return s.copy(elements = body)
    val explanation = replace(s.explanationElements, s.explanationBlockIds)
    if (explanation !== s.explanationElements) return s.copy(explanationElements = explanation)
    val options = s.options.map { o ->
        val updated = replace(o.elements, o.blockIds)
        if (updated === o.elements) o else o.copy(elements = updated)
    }
    return if (options.indices.any { options[it] !== s.options[it] }) {
        s.copy(options = options)
    } else {
        s
    }
}

/**
 * Unambiguous canonical snapshot of exactly what a save would persist.
 *
 * The old signature joined raw user content with `|`, `;`, `,` and newlines,
 * so different structures could encode identically — `Text("a|i:b") +
 * Image("c")` and `Text("a") + Image("b|i:c")` looked clean — and it trimmed
 * text that buildDto() stores verbatim, so a whitespace-only edit was
 * dropped by a Prev/Next auto-save (question_editor_audit.md M-02, M-03).
 *
 * Every string is length-prefixed, so a delimiter inside user content can
 * never be mistaken for structure, and content is encoded exactly as the DTO
 * carries it: raw element text, normalized image fields (buildDto trims and
 * blanks them), the persisted marks value (blank means 1), and the tags the
 * save writes.
 *
 * The card type is deliberately absent: it is implied by the option list (no
 * options = flashcard), and every switch that changes the type changes that
 * list too — clearing the options, or seeding the two blanks a question needs.
 * Carrying the enum as well would make a form look edited when nothing the
 * save writes had changed.
 */
internal fun editorSnapshot(s: EditorUiState): String {
    val sb = StringBuilder()

    fun string(value: String) {
        sb.append(value.length).append(':').append(value)
    }

    fun elements(list: List<ContentElement>) {
        sb.append(list.size).append('[')
        list.forEach { el ->
            when (el) {
                is ContentElement.TextElement -> { sb.append('T'); string(el.text) }
                is ContentElement.ImageElement -> { sb.append('I'); string(el.src) }
                is ContentElement.MathElement -> { sb.append('M'); string(el.mathml) }
                is ContentElement.ChemElement -> {
                    // All five fields are part of the persisted form, so an
                    // edit to any of them makes the draft dirty. Length-prefixed
                    // like everything else: a delimiter inside SMILES cannot be
                    // mistaken for structure.
                    sb.append('C')
                    string(el.smiles)
                    string(el.formula)
                    string(el.caption)
                    string(el.spoken)
                    string(el.diagram)
                }
                is ContentElement.TableElement -> {
                    sb.append('B').append(el.rows.size).append('<')
                    el.rows.forEach { row ->
                        sb.append(row.size).append('(')
                        row.forEach { string(it) }
                        sb.append(')')
                    }
                    sb.append('>')
                }
            }
        }
        sb.append(']')
    }

    fun image(value: String) = string(value.trim().ifBlank { "\u0000null" })

    elements(s.elements)
    image(s.image)
    elements(s.explanationElements)
    image(s.explanationImage)
    string(s.difficulty.label)
    string(EditorViewModel.parseMarks(s.marks).toString())
    string(s.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }.joinToString(","))
    string(s.categoryId)
    string(s.passageId.orEmpty())
    sb.append(s.options.size).append('{')
    s.options.forEach { o ->
        string(o.id)
        elements(o.elements)
        image(o.image)
        sb.append(if (o.isCorrect) '1' else '0')
    }
    sb.append('}')
    return sb.toString()
}

/**
 * The [CardContent] fingerprint of exactly what `buildDto()`/`save()` would
 * persist: body and option blocks, both scalar images, option order and ids, the
 * answer key and passage membership. Explanation, marks, difficulty, tags,
 * category and the scalar text join fields are deliberately absent, matching
 * [CardContent].
 *
 * [rawImages] selects which side of `QuestionStore.saveQuestion`'s comparison
 * this represents:
 *  - `false` (the default): scalar images are normalized exactly as
 *    [buildDto] stores them. This is the *incoming* side — what a save would
 *    write — and is used for the form on screen and the post-save baseline.
 *  - `true`: scalar images are taken verbatim from the editor's untrimmed
 *    state, matching `CardContent.of(existing, …)` — the *stored* side that
 *    saveQuestion compares against on load / Prev-Next.
 *
 * The two must agree only for already-trimmed stored values. A stored src with
 * stray whitespace is the exception: saveQuestion trims the incoming image but
 * compares it to the raw stored one, so saving such a row *does* delete
 * `card_state`. Fingerprinting the load baseline raw makes the disclosure warn
 * about exactly that reset; trimming both sides would hide it.
 *
 * A flashcard's back is its answer, so for a zero-option question the back
 * (blocks and scalar image) is part of the fingerprint — the same scoping
 * [CardContent] applies. Without it, editing the answer a learner studies read
 * as an explanation-only edit and never warned about the schedule reset.
 */
internal fun editorCardFingerprint(s: EditorUiState, rawImages: Boolean = false): String {
    fun image(value: String): String? =
        if (rawImages) value else value.trim().ifBlank { null }
    val isCard = s.options.isEmpty()
    return CardContent.of(
        Question(
            id = s.questionId,
            categoryId = s.categoryId,
            elements = s.elements,
            image = image(s.image),
            options = s.options.map { o ->
                QuestionOption(o.id, o.elements, image(o.image))
            },
            correctOptionIds = s.options.filter { it.isCorrect }.map { it.id }.toSet(),
            passageId = s.passageId?.takeIf { it.isNotBlank() },
            explanationElements = if (isCard) s.explanationElements else emptyList(),
            explanationImage = if (isCard) image(s.explanationImage) else null
        )
    )
}

/**
 * Which string resource explains a disabled Save, or null when nothing needs
 * saying. Pure and resource-id-based so the truth table (a card needs a back,
 * a question needs two options) is testable without Compose; the screen only
 * resolves the id. The marks and per-field cases have their own field-level
 * errors, so they return null here.
 */
internal fun saveBlockedHintRes(s: EditorUiState): Int? = when {
    s.loading || s.busy || !s.marksValid -> null
    // One hint at a time, and the shape's own missing piece comes first — in
    // the order the form is read top to bottom.
    s.cardType == EditorCardType.FLASHCARD ->
        // A card is defined by its answer, so an empty back is reported before
        // a missing front: the back is the part a question does not have.
        when {
            !s.hasSaveableBack -> R.string.r2_editor_save_blocked_flashcard_back
            !s.hasFrontContent -> R.string.save_blocked_no_content
            else -> null
        }
    // Unchanged from before flashcards: the option list (the part a save
    // rewrites) is reported before the front, so a blank new question keeps
    // saying "Every option needs content" instead of jumping to the text
    // hint (QuestionEditorUiTest pins that copy).
    s.options.size < 2 -> R.string.save_blocked_two_options
    s.options.any { !it.hasContent } -> R.string.save_blocked_empty_option
    !s.hasFrontContent -> R.string.save_blocked_no_content
    else -> null
}

/**
 * The label the editor gives a question's scalar back image.
 *
 * On a flashcard that picture is the answer, not an explanation, and Study and
 * Browse already resolve the same pair ([R.string.r2_study_answer_image] /
 * [R.string.explanation_image]); the editor used to say "Explanation image" for
 * both, which misnamed the card's answer. Pure, so the pairing is testable
 * without Compose.
 */
internal fun explanationImageLabelRes(type: EditorCardType): Int = when (type) {
    EditorCardType.FLASHCARD -> R.string.r2_study_answer_image
    EditorCardType.MULTIPLE_CHOICE -> R.string.explanation_image
}

/**
 * True when the form currently on screen differs from [signature], the state
 * actually persisted. Keystrokes typed while a write is in flight are not part
 * of the signature, so they must keep the editor dirty rather than be marked
 * clean and silently lost on Back.
 */
internal fun dirtyAfterSave(current: EditorUiState, signature: String): Boolean =
    editorSnapshot(current) != signature

class EditorViewModel(
    application: Application,
    private val questionId: String,
    private val paperId: String,
    private val categoryId: String,
    source: EditorSource = EditorSource.UNSPECIFIED
) : AndroidViewModel(application) {

    // Resolved lazily: import-session editing never touches the database,
    // so tests can construct this with a plain Application.
    private val repository: McqRepository by lazy { (application as McqApplication).repository }

    private val _state = MutableStateFlow(
        EditorUiState(
            questionId = questionId,
            paperId = paperId,
            categoryId = categoryId,
            isNew = questionId.isBlank()
        )
    )
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** A plain, localized message; the exception itself stays in the log. */
    private fun friendly(messageRes: Int): String =
        getApplication<Application>().getString(messageRes)

    /** The route's explicit source; an unstated one is inferred from the id. */
    private val editorSource: EditorSource = when (source) {
        EditorSource.UNSPECIFIED ->
            if (questionId.isBlank()) EditorSource.NEW else EditorSource.DATABASE
        else -> source
    }

    /** True when the import preview's in-memory draft was available. */
    private var fromImportSession: Boolean = false

    /**
     * Monotonic block ids for this editor instance. Identity must not come
     * from position or content: reordering two formulas kept the same Compose
     * keys and left one WebView showing the other's formula (M-01), and an
     * option key without its question reused a WebView across two questions
     * that shared the option id (H-05).
     */
    private var nextBlockId: Long = 0

    /** Invalidates an in-flight passage re-check superseded by a newer pick. */
    private var passagePickSeq: Int = 0

    private fun freshBlockIds(count: Int): List<String> = List(count) { "b" + nextBlockId++ }

    private fun idsWithout(ids: List<String>, index: Int): List<String> =
        if (index !in ids.indices) ids else ids.toMutableList().also { it.removeAt(index) }

    private fun idsMoved(ids: List<String>, from: Int, to: Int): List<String> =
        if (from !in ids.indices || to !in ids.indices) ids
        else ids.toMutableList().also { it.add(to, it.removeAt(from)) }

    /**
     * An import edit whose draft is gone. Every write path refuses, so a
     * same-id database row can never be mistaken for the missing draft.
     */
    private val importContextMissing: Boolean
        get() = editorSource == EditorSource.IMPORT && !fromImportSession

    /**
     * Snapshot of the last persisted state (load or save). A Prev/Next move
     * with a matching snapshot writes nothing.
     */
    private var cleanSignature: String = ""

    private fun snapshotClean() {
        cleanSignature = editorSnapshot(_state.value)
        _state.update { it.copy(isDirty = false) }
    }

    private fun markClean(signature: String) {
        cleanSignature = signature
        // Fields stay editable while the write is in flight. Recompute against
        // the CURRENT form: a keystroke made after the snapshot must stay dirty
        // (it was not persisted), never be cleared by a stale "clean" flag.
        _state.update { it.copy(isDirty = dirtyAfterSave(it, signature)) }
    }

    /** Re-anchors what "will change on save" is measured against. */
    private fun setCardBaseline(fingerprint: String) {
        _state.update { it.copy(baselineCardFingerprint = fingerprint) }
    }

    /**
     * Baseline for a row just loaded from Room. Its scalar images are stored
     * verbatim, so fingerprint them raw — the same side `saveQuestion` reads
     * via `CardContent.of(existing, …)`. The current form is fingerprinted
     * trimmed (the incoming, buildDto side), so a padded stored src is flagged
     * as a reset rather than silently losing the note.
     */
    private fun setStoredCardBaseline(s: EditorUiState) {
        setCardBaseline(editorCardFingerprint(s, rawImages = true))
    }

    init {
        Logger.i("EDITORVM", "EditorViewModel created: questionId='$questionId', paperId='$paperId', categoryId='$categoryId'")
        val pendingEdit = com.mcqapp.ui.importscreen.ImportDataHolder.pendingEditQuestion
        com.mcqapp.ui.importscreen.ImportDataHolder.pendingEditQuestion = null
        Logger.d("EDITORVM", "init: holderPresent=${pendingEdit != null}, holderId=${pendingEdit?.id}")
        // The route's source is authoritative; the holder only supplies the
        // draft. Only an import route may use it — a leftover holder must not
        // redirect a database edit's save into the in-memory list.
        fromImportSession = editorSource == EditorSource.IMPORT &&
            pendingEdit != null && pendingEdit.id == questionId
        Logger.d("EDITORVM", "init: source=$editorSource, fromImportSession=$fromImportSession")
        if (editorSource == EditorSource.IMPORT) {
            if (fromImportSession && pendingEdit != null) {
                _state.value = _state.value.copy(
                    loading = false,
                    elements = pendingEdit.elements,
                    blockIds = freshBlockIds(pendingEdit.elements.size),
                    image = pendingEdit.image.orEmpty(),
                    explanationElements = pendingEdit.explanationElements,
                    explanationBlockIds = freshBlockIds(pendingEdit.explanationElements.size),
                    explanationImage = pendingEdit.explanationImage.orEmpty(),
                    difficulty = Difficulty.fromLabel(pendingEdit.difficulty),
                    marks = formatMarks(pendingEdit.marks),
                    tags = pendingEdit.tags.joinToString(", "),
                    options = pendingEdit.options.map { o ->
                        OptionEditorState(
                            id = o.id,
                            elements = o.elements,
                            blockIds = freshBlockIds(o.elements.size),
                            image = o.image.orEmpty(),
                            isCorrect = o.id in pendingEdit.correctOptionIds
                        )
                    },
                    // No options means the imported draft is a flashcard; its
                    // explanation is the back.
                    cardType = cardTypeOf(pendingEdit.options.size),
                    categories = emptyList(),
                    categoryId = "",
                    // Passage membership is part of the imported content; an
                    // edit must not turn a member into a standalone question
                    // (question_editor_audit.md H-01).
                    passageId = pendingEdit.passageId
                )
                snapshotClean()
                Logger.i("EDITORVM", "Editor ready: editing from import screen (in-memory)")
            } else {
                // Process death consumed the in-memory draft. Fail closed: an
                // id that happens to exist in the library is NOT this draft
                // (question_editor_audit.md H-04).
                Logger.w("EDITORVM", "import draft missing for '$questionId': failing closed")
                _state.value = _state.value.copy(
                    loading = false,
                    importContextMissing = true,
                    loadError = friendly(R.string.editor_import_draft_missing)
                )
                snapshotClean()
                Logger.i("EDITORVM", "Editor ready: import context missing (inert)")
            }
        } else {
            viewModelScope.launch {
            try {
                val paper = if (paperId.isNotBlank()) repository.getPaper(paperId) else null
            val categories = paper?.categories ?: emptyList()
            if (questionId.isNotBlank()) {
                val question = repository.getQuestion(questionId)
                if (question != null) {
                    _state.value = _state.value.copy(
                        loading = false,
                        elements = question.elements,
                        blockIds = freshBlockIds(question.elements.size),
                        image = question.image.orEmpty(),
                        explanationElements = question.explanationElements,
                        explanationBlockIds = freshBlockIds(question.explanationElements.size),
                        explanationImage = question.explanationImage.orEmpty(),
                        difficulty = question.difficulty,
                        marks = formatMarks(question.marks),
                        tags = question.tags.joinToString(", "),
                        options = question.options.map { o ->
                            OptionEditorState(
                                id = o.id,
                                elements = o.elements,
                                blockIds = freshBlockIds(o.elements.size),
                                image = o.image.orEmpty(),
                                isCorrect = o.id in question.correctOptionIds
                            )
                        },
                        cardType = cardTypeOf(question.options.size),
                        categories = categories,
                        categoryId = question.categoryId,
                        passages = repository.getPassagesForPaper(paperId),
                        passageId = question.passageId
                    )
                    snapshotClean()
                    // A stored row may have a schedule: from here, "will this
                    // edit restart it?" is measured against this content.
                    setStoredCardBaseline(_state.value)
                    return@launch
                }
            }
            // A restored editor whose question no longer exists (process
            // death during an import edit, a concurrently deleted row) must
            // not keep the stale id: save() would upsert a fresh row under it
            // with categoryId = "" and die on the FK (consolidated_bugs.md
            // J6). It becomes a new question instead.
            _state.value = _state.value.copy(
                loading = false,
                questionId = "",
                isNew = true,
                categories = categories,
                passages = if (paperId.isNotBlank()) repository.getPassagesForPaper(paperId) else emptyList(),
                elements = listOf(ContentElement.TextElement("")),
                blockIds = freshBlockIds(1),
                options = listOf(
                    OptionEditorState(
                        id = "a",
                        elements = listOf(ContentElement.TextElement("")),
                        blockIds = freshBlockIds(1),
                        isCorrect = true
                    ),
                    OptionEditorState(
                        id = "b",
                        elements = listOf(ContentElement.TextElement("")),
                        blockIds = freshBlockIds(1)
                    )
                ),
                cardType = EditorCardType.MULTIPLE_CHOICE
            )
            snapshotClean()
            val readyMsg = if (_state.value.isNew) "new question" else "editing $questionId"
            Logger.i("EDITORVM", "Editor ready: $readyMsg, ${categories.size} categories available")
        } catch (e: Exception) {
            // Without clearing `loading` the editor sat on "Loading…" forever
            // with no way forward (consolidated_bugs.md J5).
            Logger.e("EDITORVM", "Failed to initialize editor", e)
            _state.value = _state.value.copy(
                loading = false,
                loadError = friendly(R.string.editor_load_failed)
            )
        }
        }
        }
    }

    /** The form's save/readiness rule; see [EditorUiState.canSave]. */
    fun canProceed(): Boolean = _state.value.canSave

    fun updateImage(value: String) = updateState { it.copy(image = value) }
    fun updateExplanationImage(value: String) = updateState { it.copy(explanationImage = value) }
    fun updateTags(value: String) = updateState { it.copy(tags = value) }

    /**
     * Adds one tag label (phase 3). The state stays the comma string; the
     * difference is only that the chips make one label the unit of intent.
     * A case-folded duplicate no-ops.
     */
    fun addTag(label: String) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        updateState { state ->
            val existing = com.mcqapp.domain.TagNormalizer.fromCsv(state.tags)
            if (existing.any { it.folded == com.mcqapp.domain.TagNormalizer.fold(trimmed) }) {
                state
            } else {
                val labels = existing.map { it.tag } + trimmed
                state.copy(tags = labels.joinToString(", "))
            }
        }
    }

    /** Removes one tag label from the edited question. */
    fun removeTag(label: String) {
        updateState { state ->
            val existing = com.mcqapp.domain.TagNormalizer.fromCsv(state.tags)
            val labels = existing.filterNot {
                it.folded == com.mcqapp.domain.TagNormalizer.fold(label)
            }.map { it.tag }
            state.copy(tags = labels.joinToString(", "))
        }
    }
    fun updateDifficulty(value: Difficulty) = updateState { it.copy(difficulty = value) }
    fun updateMarks(value: String) = updateState { it.copy(marks = value) }
    /**
     * A question can only belong to a passage of its own category (the
     * group's members share one), so switching category drops an assignment
     * that no longer fits; the picker offers the new category's passages.
     */
    fun updateCategory(value: String) = updateState {
        val keepPassage = it.passageId?.takeIf { pid ->
            it.passages.any { p -> p.id == pid && p.categoryId == value }
        }
        it.copy(categoryId = value, passageId = keepPassage)
    }

    /**
     * Assigns this question to a passage (null clears membership).
     *
     * The picker's list is cached at load time, so a passage may have been
     * re-homed into another paper since. Re-read the list from the repository
     * and only apply the pick if the passage is still in this question's paper;
     * otherwise refresh the cached list and ignore the pick silently. The
     * question identity, category and a pick sequence are captured at the tap
     * and checked again when the read returns, so a stale result can never land
     * on a different question after Prev/Next, on a superseded pick, or on a
     * passage outside the question's current category after a category switch.
     */
    fun updatePassage(value: String?) {
        val seq = ++passagePickSeq
        if (value == null) {
            updateState { it.copy(passageId = null) }
            return
        }
        val s = _state.value
        val questionIdAtPick = s.questionId
        val paperIdAtPick = s.paperId
        val categoryIdAtPick = s.categoryId
        viewModelScope.launch {
            val fresh = try {
                repository.getPassagesForPaper(paperIdAtPick)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("EDITORVM", "Could not re-check the passage list", e)
                return@launch
            }
            val now = _state.value
            if (seq != passagePickSeq ||
                now.questionId != questionIdAtPick || now.paperId != paperIdAtPick
            ) return@launch
            val picked = fresh.firstOrNull { it.id == value }
            if (picked == null) {
                // The cached entry is stale: refresh it and drop the pick. No
                // assignment is written and no error copy is shown.
                _state.update { it.copy(passages = fresh) }
                return@launch
            }
            // The category can change between the tap and this re-read (and a
            // passage can move category since the list was cached). A member
            // must belong to the question's current category, so never attach
            // a passage picked under a now-abandoned one.
            if (now.categoryId != categoryIdAtPick ||
                picked.categoryId != now.categoryId
            ) return@launch
            updateState { it.copy(passageId = value) }
        }
    }

    // ---- question-body blocks ----

    fun addBlock(type: EditorBlockType) = updateState {
        it.copy(
            elements = it.elements + type.defaultElement(),
            blockIds = it.blockIds + freshBlockIds(1)
        )
    }

    fun updateBlock(index: Int, element: ContentElement) = updateState {
        if (index !in it.elements.indices) return@updateState it
        it.copy(elements = it.elements.toMutableList().also { list -> list[index] = element })
    }

    fun removeBlock(index: Int) = updateState {
        if (index !in it.elements.indices) return@updateState it
        it.copy(
            elements = it.elements.toMutableList().also { list -> list.removeAt(index) },
            blockIds = idsWithout(it.blockIds, index)
        )
    }

    fun moveBlockUp(index: Int) = updateState {
        if (index <= 0 || index >= it.elements.size) return@updateState it
        it.copy(
            elements = it.elements.toMutableList().also { list ->
                val item = list.removeAt(index)
                list.add(index - 1, item)
            },
            // The id travels with its block, so a moved formula keeps its
            // WebView instead of inheriting the neighbour's (M-01).
            blockIds = idsMoved(it.blockIds, index, index - 1)
        )
    }

    fun moveBlockDown(index: Int) = updateState {
        if (index < 0 || index >= it.elements.size - 1) return@updateState it
        it.copy(
            elements = it.elements.toMutableList().also { list ->
                val item = list.removeAt(index)
                list.add(index + 1, item)
            },
            blockIds = idsMoved(it.blockIds, index, index + 1)
        )
    }

    // ---- table cells (index addresses a TableElement in the body) ----

    private fun updateTable(index: Int, transform: (ContentElement.TableElement) -> ContentElement.TableElement) =
        updateState { it.copy(elements = updateTableAt(it.elements, index, transform)) }

    fun updateTableCell(index: Int, row: Int, col: Int, value: String) =
        updateTable(index) { it.withCell(row, col, value) }

    fun addTableRow(index: Int) = updateTable(index) { it.withRowAdded() }

    fun removeTableRow(index: Int, row: Int) =
        updateTable(index) { it.withRowRemoved(row) }

    fun addTableColumn(index: Int) = updateTable(index) { it.withColumnAdded() }

    fun removeTableColumn(index: Int, col: Int) =
        updateTable(index) { it.withColumnRemoved(col) }

    // ---- table cells (option and explanation lists) ----

    private fun updateOptionTable(
        optionId: String,
        index: Int,
        transform: (ContentElement.TableElement) -> ContentElement.TableElement
    ) = updateState { s ->
        s.copy(options = s.options.map { o ->
            if (o.id != optionId) o
            else o.copy(elements = updateTableAt(o.elements, index, transform))
        })
    }

    fun updateOptionTableCell(optionId: String, index: Int, row: Int, col: Int, value: String) =
        updateOptionTable(optionId, index) { it.withCell(row, col, value) }

    fun addOptionTableRow(optionId: String, index: Int) =
        updateOptionTable(optionId, index) { it.withRowAdded() }

    fun removeOptionTableRow(optionId: String, index: Int, row: Int) =
        updateOptionTable(optionId, index) { it.withRowRemoved(row) }

    fun addOptionTableColumn(optionId: String, index: Int) =
        updateOptionTable(optionId, index) { it.withColumnAdded() }

    fun removeOptionTableColumn(optionId: String, index: Int, col: Int) =
        updateOptionTable(optionId, index) { it.withColumnRemoved(col) }

    private fun updateExplanationTable(
        index: Int,
        transform: (ContentElement.TableElement) -> ContentElement.TableElement
    ) = updateState {
        it.copy(explanationElements = updateTableAt(it.explanationElements, index, transform))
    }

    fun updateExplanationTableCell(index: Int, row: Int, col: Int, value: String) =
        updateExplanationTable(index) { it.withCell(row, col, value) }

    fun addExplanationTableRow(index: Int) =
        updateExplanationTable(index) { it.withRowAdded() }

    fun removeExplanationTableRow(index: Int, row: Int) =
        updateExplanationTable(index) { it.withRowRemoved(row) }

    fun addExplanationTableColumn(index: Int) =
        updateExplanationTable(index) { it.withColumnAdded() }

    fun removeExplanationTableColumn(index: Int, col: Int) =
        updateExplanationTable(index) { it.withColumnRemoved(col) }

    // ---- card type ----

    /**
     * Switches the question between a multiple-choice question and a
     * flashcard.
     *
     * A flashcard holds no options, so switching to one clears them; the screen
     * asks first when they hold typed content, because that content is lost.
     * Switching back seeds the two blank options the save gate requires — a
     * "question" with no options could never be saved. They are seeded without
     * a pre-ticked answer: on a converted card the user never saw a key, so
     * silently marking A correct (and letting a second tick create a
     * multi-answer key) would be wrong. The no-correct-answer warning points at
     * what is still missing.
     */
    fun setCardType(type: EditorCardType) = updateState { s ->
        if (s.cardType == type) return@updateState s
        when (type) {
            EditorCardType.FLASHCARD ->
                s.copy(cardType = type, options = emptyList())
            EditorCardType.MULTIPLE_CHOICE -> s.copy(
                cardType = type,
                options = listOf(
                    OptionEditorState(
                        id = "a",
                        elements = listOf(ContentElement.TextElement("")),
                        blockIds = freshBlockIds(1)
                    ),
                    OptionEditorState(
                        id = "b",
                        elements = listOf(ContentElement.TextElement("")),
                        blockIds = freshBlockIds(1)
                    )
                )
            )
        }
    }

    // ---- options ----

    fun updateOptionImage(optionId: String, value: String) = updateState {
        it.copy(options = it.options.map { o -> if (o.id == optionId) o.copy(image = value) else o })
    }

    fun toggleCorrect(optionId: String) = updateState {
        it.copy(options = it.options.map { o ->
            if (o.id == optionId) o.copy(isCorrect = !o.isCorrect) else o
        })
    }

    fun addOption() = updateState {
        val used = it.options.mapTo(HashSet()) { o -> o.id }
        val nextId = generateSequence(0) { i -> i + 1 }
            .map { i -> optionIdAt(i) }
            .first { id -> id !in used }
        it.copy(options = it.options + OptionEditorState(
            id = nextId,
            elements = listOf(ContentElement.TextElement("")),
            blockIds = freshBlockIds(1)
        ))
    }

    /**
     * Spreadsheet-style lowercase ids: `a`…`z`, then `aa`…`az`, `ba`…`bz`, …
     * (bijective base-26). Ids are derived positionally elsewhere, but the new
     * option must avoid the ids already present: deriving it from `size` minted
     * a duplicate after a middle option was removed (`a, c` + Add → `"c"` twice).
     */
    private fun optionIdAt(index: Int): String = optionOrdinalLabel(index, lower = true)

    fun removeOption(optionId: String) = updateState {
        if (it.options.size <= 2) return@updateState it
        it.copy(options = it.options.filter { o -> o.id != optionId })
    }

    fun moveOptionUp(optionId: String) = updateState {
        val index = it.options.indexOfFirst { o -> o.id == optionId }
        if (index <= 0) return@updateState it
        val list = it.options.toMutableList()
        val item = list.removeAt(index)
        list.add(index - 1, item)
        it.copy(options = list)
    }

    fun moveOptionDown(optionId: String) = updateState {
        val index = it.options.indexOfFirst { o -> o.id == optionId }
        if (index < 0 || index >= it.options.size - 1) return@updateState it
        val list = it.options.toMutableList()
        val item = list.removeAt(index)
        list.add(index + 1, item)
        it.copy(options = list)
    }

    private fun updateOptionElements(
        optionId: String,
        transform: (List<ContentElement>) -> List<ContentElement>
    ) = updateState {
        it.copy(options = it.options.map { o ->
            if (o.id == optionId) o.copy(elements = transform(o.elements)) else o
        })
    }

    fun addOptionBlock(optionId: String, type: EditorBlockType) = updateState {
        it.copy(options = it.options.map { o ->
            if (o.id != optionId) o
            else o.copy(
                elements = o.elements + type.defaultElement(),
                blockIds = o.blockIds + freshBlockIds(1)
            )
        })
    }

    fun updateOptionBlock(optionId: String, index: Int, element: ContentElement) =
        updateOptionElements(optionId) { elements ->
            if (index !in elements.indices) elements
            else elements.toMutableList().also { list -> list[index] = element }
        }

    fun removeOptionBlock(optionId: String, index: Int) = updateState {
        it.copy(options = it.options.map { o ->
            if (o.id != optionId || index !in o.elements.indices) o
            else o.copy(
                elements = o.elements.toMutableList().also { list -> list.removeAt(index) },
                blockIds = idsWithout(o.blockIds, index)
            )
        })
    }

    fun moveOptionBlockUp(optionId: String, index: Int) = updateState {
        it.copy(options = it.options.map { o ->
            if (o.id != optionId || index <= 0 || index >= o.elements.size) o
            else o.copy(
                elements = o.elements.toMutableList().also { list ->
                    val item = list.removeAt(index)
                    list.add(index - 1, item)
                },
                blockIds = idsMoved(o.blockIds, index, index - 1)
            )
        })
    }

    fun moveOptionBlockDown(optionId: String, index: Int) = updateState {
        it.copy(options = it.options.map { o ->
            if (o.id != optionId || index < 0 || index >= o.elements.size - 1) o
            else o.copy(
                elements = o.elements.toMutableList().also { list ->
                    val item = list.removeAt(index)
                    list.add(index + 1, item)
                },
                blockIds = idsMoved(o.blockIds, index, index + 1)
            )
        })
    }

    // ---- explanation blocks ----

    fun addExplanationBlock(type: EditorBlockType) = updateState {
        it.copy(
            explanationElements = it.explanationElements + type.defaultElement(),
            explanationBlockIds = it.explanationBlockIds + freshBlockIds(1)
        )
    }

    fun updateExplanationBlock(index: Int, element: ContentElement) = updateState {
        if (index !in it.explanationElements.indices) return@updateState it
        it.copy(explanationElements = it.explanationElements.toMutableList().also { list -> list[index] = element })
    }

    fun removeExplanationBlock(index: Int) = updateState {
        if (index !in it.explanationElements.indices) return@updateState it
        it.copy(
            explanationElements = it.explanationElements.toMutableList().also { list ->
                list.removeAt(index)
            },
            explanationBlockIds = idsWithout(it.explanationBlockIds, index)
        )
    }

    fun moveExplanationBlockUp(index: Int) = updateState {
        if (index <= 0 || index >= it.explanationElements.size) return@updateState it
        it.copy(
            explanationElements = it.explanationElements.toMutableList().also { list ->
                val item = list.removeAt(index)
                list.add(index - 1, item)
            },
            explanationBlockIds = idsMoved(it.explanationBlockIds, index, index - 1)
        )
    }

    fun moveExplanationBlockDown(index: Int) = updateState {
        if (index < 0 || index >= it.explanationElements.size - 1) return@updateState it
        it.copy(
            explanationElements = it.explanationElements.toMutableList().also { list ->
                val item = list.removeAt(index)
                list.add(index + 1, item)
            },
            explanationBlockIds = idsMoved(it.explanationBlockIds, index, index + 1)
        )
    }

    // ---- formula writes, addressed by stable block identity ----
    //
    // The MathLive JS bridge is installed once per keyed WebView and never
    // rebound (H5). These three methods keep the resolution in the ViewModel,
    // mirroring applyImageTarget: the callback carries a block id, and the
    // index that id maps to is read from the current state when it fires.

    fun updateMathBody(blockId: String, mml: String) = updateState {
        applyMathTarget(it, MathTarget.Body(blockId), mml)
    }

    fun updateMathExplanation(blockId: String, mml: String) = updateState {
        applyMathTarget(it, MathTarget.Explanation(blockId), mml)
    }

    fun updateMathOption(optionId: String, blockId: String, mml: String) = updateState {
        applyMathTarget(it, MathTarget.Option(optionId, blockId), mml)
    }

    /**
     * Write-back for the shared renderer's captured PNG. Unlike every other
     * block edit this is not a user action, so it deliberately bypasses
     * [updateState] and preserves the dirty flag: capturing a diagram must
     * not make a clean form look edited (or clear a real edit). A no-op leaves
     * the state instance untouched, so repeated capture callbacks do not
     * churn composition.
     */
    fun updateChemDiagram(blockId: String, pngDataUri: String) {
        _state.update { current ->
            val next = applyChemDiagram(current, blockId, pngDataUri)
            if (next === current) {
                current
            } else {
                // Keep the clean baseline in step with the captured content so
                // a later edit-and-revert is not mistaken for a user change.
                if (!current.isDirty) cleanSignature = editorSnapshot(next)
                next.copy(isDirty = current.isDirty)
            }
        }
    }

    private fun updateState(block: (EditorUiState) -> EditorUiState) {
        _state.update { current ->
            val next = block(current)
            if (next === current) current
            else next.copy(isDirty = editorSnapshot(next) != cleanSignature)
        }
    }

    companion object {
        /**
         * Display whole marks without a decimal point ("2", not "2.0").
         * A negative or non-finite value inherited from imported/stored data is
         * normalised to 1 on load, so an unrelated edit is never made unsavable
         * by a stored value the user did not type.
         */
        fun formatMarks(marks: Double): String = com.mcqapp.domain.Marks.format(
            if (marks.isFinite() && marks >= 0.0) marks else 1.0
        )

        /** Invalid, non-finite or negative input falls back to 1 mark. */
        fun parseMarks(raw: String): Double = com.mcqapp.domain.Marks.parse(raw)
    }

    /**
     * Re-anchors Browse's live cursor after a write. This is a cosmetic read of
     * the result set: it must never fail the write that already succeeded, nor
     * suppress the caller's onDone, so it owns its error handling. The live DB
     * reads run off the main dispatcher (M-04).
     */
    private suspend fun reanchorAfterWrite(anchorId: String) {
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                EditorSession.reanchor(anchorId)
            }
        } catch (e: CancellationException) {
            // A cancelled scope is not a cosmetic read failure. Per the Kotlin
            // coroutine cancellation contract this must propagate, or the caller
            // mistakes a cancelled write for a completed one and still pops.
            throw e
        } catch (e: Exception) {
            // Ordinary read failures stay cosmetic: a committed write and its
            // onDone must not be turned into a failure by a stale count read.
            Logger.w("EDITORVM", "reanchor($anchorId) failed after a successful write", e)
        }
    }

    private fun buildDto(): QuestionDto? {
        val s = _state.value
        if (!canProceed()) return null
        // A flashcard persists as zero options and an empty answer key; its
        // back (the explanation) is the answer. canSave already guarantees the
        // list is empty, so this is the shape guarantee, not a repair.
        val isCard = s.cardType == EditorCardType.FLASHCARD
        val edited = if (isCard) {
            emptyList()
        } else {
            s.options.map { QuestionOption(it.id, it.elements, it.image.trim().ifBlank { null }) }
        }
        return QuestionDto(
            id = s.questionId.ifBlank { "q-" + System.currentTimeMillis().toString(36) },
            text = s.elements.textContent,
            elements = s.elements,
            image = s.image.trim().ifBlank { null },
            options = edited.map { com.mcqapp.data.io.OptionDto(it.id, it.text, it.elements, it.image) },
            correctOptionIds = if (isCard) {
                emptyList()
            } else {
                s.options.filter { it.isCorrect }.map { it.id }
            },
            explanation = s.explanationElements.textContent,
            explanationElements = s.explanationElements,
            explanationImage = s.explanationImage.trim().ifBlank { null },
            difficulty = s.difficulty.label,
            marks = parseMarks(s.marks),
            tags = s.tags.split(",").map { t -> t.trim() }.filter { t -> t.isNotBlank() },
            passageId = s.passageId?.takeIf { it.isNotBlank() }
        )
    }

    private fun domainFrom(dto: QuestionDto, categoryId: String): Question {
        return Question(
            id = dto.id,
            categoryId = categoryId,
            elements = dto.elements.ifEmpty { listOf(ContentElement.TextElement(dto.text)) },
            image = dto.image,
            options = dto.options.map { QuestionOption(
                it.id,
                it.elements.ifEmpty { listOf(ContentElement.TextElement(it.text)) },
                it.image
            ) },
            correctOptionIds = dto.correctOptionIds.toSet(),
            explanationElements = dto.explanationElements.ifEmpty {
                listOf(ContentElement.TextElement(dto.explanation))
            },
            explanationImage = dto.explanationImage,
            difficulty = Difficulty.fromLabel(dto.difficulty),
            marks = dto.marks,
            tags = dto.tags,
            passageId = dto.passageId?.takeIf { it.isNotBlank() }
        )
    }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        // A double-tap on Save used to run buildDto() twice while a new
        // question's id was still blank, minting two ids and two identical
        // rows (consolidated_bugs.md J7). One operation at a time.
        if (s.busy) return
        // An import edit whose draft is gone must not fall through to the
        // database: the id may name a real, unrelated row (H-04).
        if (importContextMissing) {
            Logger.w("EDITORVM", "save() refused: import draft is missing")
            return
        }
        Logger.d("EDITORVM", "save() called: questionId='${s.questionId}', valid=${canProceed()}, " +
            "options=${s.options.size}, " +
            "correctCount=${s.options.count { it.isCorrect }}, source=$editorSource")
        val dto = buildDto()
        if (dto == null) {
            Logger.w("EDITORVM", "save() validation FAILED - not saving")
            return
        }
        Logger.d("EDITORVM", "save(): fromImportSession=$fromImportSession, " +
            "dtoId=${dto.id}, dtoTextLength=${dto.text.length}, dtoOptions=${dto.options.size}, " +
            "dtoCorrect=${dto.correctOptionIds}")
        // Signature of exactly what is being persisted (state may keep
        // changing under an async save; snapshotting later would mark
        // unpersisted keystrokes as clean).
        val savedSig = editorSnapshot(s)
        // Capture the persisted card content now: keystrokes made during the
        // async write are NOT saved, so the post-save baseline must be this
        // snapshot, not the (possibly newer) state when the write returns.
        val savedCardFingerprint = editorCardFingerprint(s)
        if (fromImportSession) {
            // Live write-through as well: if the user moved Prev/Next before
            // saving, the holder alone would be discarded on return (id
            // mismatch with the original mark), so update the list directly.
            EditorSession.importWriter?.invoke(dto)
            com.mcqapp.ui.importscreen.ImportDataHolder.pendingEditQuestion = dto
            markClean(savedSig)
            Logger.i("EDITORVM", "Saved question ${dto.id} to import holder (in-memory)")
            onDone()
            return
        }
        val question = domainFrom(dto, s.categoryId)
        _state.update { it.copy(operation = EditorOperation.SAVING, saveError = null) }
        viewModelScope.launch {
            try {
                repository.saveQuestion(question)
                markClean(savedSig)
                // The form now equals what is stored, so the disclosure clears
                // until the next content edit changes the studied content.
                setCardBaseline(savedCardFingerprint)
                // A new question's minted id is written back, so a second tap
                // updates the same row instead of minting another (J7).
                if (s.questionId.isBlank()) {
                    _state.update { it.copy(questionId = dto.id, isNew = false) }
                }
                // The edit may have changed whether this question matches
                // Browse's filter; re-read the live scope before the screen
                // shows its counter and neighbours again (M-04). A failed read
                // is cosmetic and must not turn a successful save into a
                // failure (M2).
                reanchorAfterWrite(dto.id)
                // markClean() recomputed isDirty from the CURRENT form, so a
                // keystroke typed during the async write is still dirty. Never
                // pop on top of it: that silently discards what was not saved.
                // The Save button and the dirty state stay in place.
                if (!_state.value.isDirty) onDone()
            } catch (e: CancellationException) {
                // Cancellation is not a save failure (Kotlin's coroutine
                // cancellation contract); let the scope unwind normally.
                throw e
            } catch (e: Exception) {
                // Any Room failure used to crash here with onDone() never run
                // (consolidated_bugs.md J7).
                Logger.e("EDITORVM", "save() failed", e)
                _state.update { it.copy(saveError = friendly(R.string.editor_save_failed)) }
            } finally {
                _state.update { it.copy(operation = EditorOperation.IDLE) }
            }
        }
    }

    /**
     * Persists current edits through the same channel as save() — but only if
     * anything actually changed — then loads the target question from the
     * [EditorSession] queue. Stays on this screen: no navigation, so Back
     * still returns to the originating list. [onMoved] reports whether a
     * write happened (for user feedback).
     *
     * A *clean* form moves freely even when it is not savable (a demo edge
     * question, say): there is nothing to lose, and gating Prev/Next on
     * validity trapped the reader on such a question with every button
     * disabled. Only dirty-and-invalid edits block the move.
     */
    fun moveToQuestion(targetId: String, onMoved: (saved: Boolean) -> Unit = {}) {
        val current = _state.value
        // One move at a time: a second tap while a save/load is in flight used
        // to launch independent work that both captured the same target and
        // advanced the session twice (question_editor_audit.md H-03).
        if (current.busy) {
            Logger.w("EDITORVM", "moveToQuestion($targetId) refused: ${current.operation} in flight")
            return
        }
        if (importContextMissing) {
            Logger.w("EDITORVM", "moveToQuestion($targetId) refused: import draft is missing")
            return
        }
        // Capture the neighbour and its index together, from the session as
        // it is now. Recomputing the index later from mutable session state
        // is what let two rapid Next taps advance twice onto one question.
        val target = EditorSession.captureTarget(targetId)
        if (target == null) {
            Logger.w("EDITORVM", "moveToQuestion($targetId): not a session neighbour")
            return
        }
        val dirty = current.isDirty
        // A clean form moves freely even when it is not savable (an edge-case
        // demo question, say): nothing is lost, and gating Prev/Next on
        // validity trapped the reader on such a question with every button
        // disabled. Only dirty-and-invalid edits block the move.
        if (dirty && !current.canSave) {
            _state.update {
                it.copy(moveError = friendly(R.string.editor_move_invalid))
            }
            Logger.w("EDITORVM", "moveToQuestion($targetId) blocked: unsaved edits are invalid")
            return
        }
        val dto = if (dirty) buildDto() else null
        val savedSig = editorSnapshot(current)
        val savedCardFingerprint = if (dirty) editorCardFingerprint(current) else null
        Logger.i("EDITORVM", "moveToQuestion: ${if (dirty) "saving" else "no changes, skipping save for"} " +
            "${current.questionId}, loading $targetId (fromImportSession=$fromImportSession)")
        if (fromImportSession) {
            if (dirty && dto != null) {
                val writer = EditorSession.importWriter
                if (writer != null) {
                    writer(dto)
                } else {
                    com.mcqapp.ui.importscreen.ImportDataHolder.pendingEditQuestion = dto
                }
                markClean(savedSig)
            }
            val next = EditorSession.importReader?.invoke(target.id)
            if (next == null) {
                _state.update { it.copy(moveError = friendly(R.string.editor_import_next_missing)) }
                Logger.w("EDITORVM", "moveToQuestion($target.id): not found via import reader")
                return
            }
            EditorSession.go(target.index)
            _state.update {
                it.copy(
                    questionId = next.id,
                    elements = next.elements,
                    blockIds = freshBlockIds(next.elements.size),
                    image = next.image.orEmpty(),
                    explanationElements = next.explanationElements,
                    explanationBlockIds = freshBlockIds(next.explanationElements.size),
                    explanationImage = next.explanationImage.orEmpty(),
                    difficulty = Difficulty.fromLabel(next.difficulty),
                    marks = formatMarks(next.marks),
                    tags = next.tags.joinToString(", "),
                    options = next.options.map { o ->
                        OptionEditorState(
                            id = o.id,
                            elements = o.elements,
                            blockIds = freshBlockIds(o.elements.size),
                            image = o.image.orEmpty(),
                            isCorrect = o.id in next.correctOptionIds
                        )
                    },
                    // The type belongs to the loaded question, like its
                    // passage: a card must never make the next question look
                    // like a card too.
                    cardType = cardTypeOf(next.options.size),
                    // The membership belongs to the loaded question; keeping
                    // the previous one silently reassigned on save.
                    passageId = next.passageId,
                    categoryId = "",
                    // A previous question's failures do not belong here.
                    moveError = null,
                    imageError = null,
                    saveError = null
                )
            }
            snapshotClean()
            Logger.i("EDITORVM", "moveToQuestion: now editing ${target.id} (in-memory)")
            onMoved(dirty)
        } else {
            _state.update { it.copy(operation = EditorOperation.MOVING, moveError = null) }
            viewModelScope.launch {
                try {
                    if (dirty && dto != null) {
                        val categoryId = current.categoryId
                        repository.saveQuestion(domainFrom(dto, categoryId))
                        markClean(savedSig)
                        if (savedCardFingerprint != null) setCardBaseline(savedCardFingerprint)
                        // The save may have changed this question's filter
                        // membership; re-anchor before resolving the target
                        // so Prev/Next follow the live result set (M-04). The
                        // re-anchor is isolated so it cannot fail the write.
                        reanchorAfterWrite(dto.id)
                        // A keystroke made while this write was in flight was
                        // not persisted. Publishing the loaded question would
                        // silently discard it, so stay on the still-dirty form
                        // and let the user save before moving again.
                        if (_state.value.isDirty) return@launch
                    }
                    val next = repository.getQuestion(target.id)
                    if (next == null) {
                        _state.update {
                            it.copy(moveError = friendly(R.string.editor_question_unavailable))
                        }
                        Logger.w("EDITORVM", "moveToQuestion(${target.id}): not found in DB")
                        return@launch
                    }
                    // Same rule for a keystroke typed while the load itself was
                    // in flight: it is not on either question's stored state,
                    // so publishing the target would silently drop it.
                    if (_state.value.isDirty) return@launch
                    // Resolve the target against the LIVE scope. If it left the
                    // result set when this question was saved, the captured
                    // index is stale — do not anchor the session on it.
                    val index = EditorSession.indexOfId(target.id)
                    if (index == null) {
                        _state.update {
                            it.copy(moveError = friendly(R.string.editor_question_not_in_list))
                        }
                        Logger.w("EDITORVM", "moveToQuestion(${target.id}): target left the live scope")
                        return@launch
                    }
                    EditorSession.go(index)
                    // Cursor scopes read their neighbours from the database;
                    // do it before publishing the new question, so the screen
                    // recomposes with the new Prev/Next already in place.
                    EditorSession.refreshNeighbors()
                    _state.update {
                        it.copy(
                            questionId = next.id,
                            elements = next.elements,
                            blockIds = freshBlockIds(next.elements.size),
                            image = next.image.orEmpty(),
                            explanationElements = next.explanationElements,
                            explanationBlockIds = freshBlockIds(next.explanationElements.size),
                            explanationImage = next.explanationImage.orEmpty(),
                            difficulty = next.difficulty,
                            marks = formatMarks(next.marks),
                            tags = next.tags.joinToString(", "),
                            options = next.options.map { o ->
                                OptionEditorState(
                                    id = o.id,
                                    elements = o.elements,
                                    blockIds = freshBlockIds(o.elements.size),
                                    image = o.image.orEmpty(),
                                    isCorrect = o.id in next.correctOptionIds
                                )
                            },
                            // Same rule as the import branch: the type is part
                            // of the question, not of the editor session.
                            cardType = cardTypeOf(next.options.size),
                            // Same two fields as the import branch: an
                            // existing question is never "new", and its
                            // passage membership is its own.
                            isNew = false,
                            passageId = next.passageId,
                            categoryId = next.categoryId,
                            // A failed write/load or image pick on the question
                            // left behind must not follow us to this one.
                            moveError = null,
                            imageError = null,
                            saveError = null
                        )
                    }
                    // Publishing the loaded question clears the dirty flag:
                    // the form now equals what is stored.
                    cleanSignature = editorSnapshot(_state.value)
                    _state.update { it.copy(isDirty = false) }
                    // The disclosure is about the question now on screen.
                    setStoredCardBaseline(_state.value)
                    Logger.i("EDITORVM", "moveToQuestion: now editing ${target.id} (DB)")
                    onMoved(dirty)
                } catch (e: CancellationException) {
                    // Cancellation must not be reported as "Could not move".
                    throw e
                } catch (e: Exception) {
                    // The form is untouched: a failed save or load leaves the
                    // current draft on screen, with a visible reason (M-07).
                    _state.update {
                        it.copy(moveError = friendly(R.string.editor_move_failed))
                    }
                    Logger.e("EDITORVM", "moveToQuestion(${target.id}) failed", e)
                } finally {
                    _state.update { it.copy(operation = EditorOperation.IDLE) }
                }
            }
        }
    }

    /**
     * Records which block a gallery pick is for. Kept in the ViewModel, so a
     * rotation (or any configuration change) keeps the target; the Activity
     * Result is then delivered into that block, not into a lost closure.
     */
    fun beginImagePick(target: ImageTarget) {
        _state.update { it.copy(pendingImageTarget = target, imageError = null) }
    }

    /** The picker returned nothing: no target is left waiting. */
    fun onImagePickCancelled() {
        _state.update { it.copy(pendingImageTarget = null) }
    }

    /**
     * Reads and encodes a picked URI off the main thread, then writes it into
     * the block the pending target names. The utility used to run inline in
     * the Activity Result callback, where a large photo froze the editor
     * (question_editor_audit.md M-06); a failure now says so instead of only
     * logging (M-12) and writes nothing.
     */
    fun onImagePicked(uri: android.net.Uri) {
        val target = _state.value.pendingImageTarget ?: return
        _state.update { it.copy(pendingImageTarget = null, imageError = null) }
        viewModelScope.launch {
            val encoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.mcqapp.util.ImageUtils.encodeImageUri(getApplication(), uri)
            }
            if (encoded == null) {
                Logger.e("EDITORVM", "Picked image could not be encoded; nothing written")
                _state.update {
                    it.copy(imageError = friendly(R.string.editor_image_unreadable))
                }
                return@launch
            }
            applyPickedImage(target, encoded)
        }
    }

    private fun applyPickedImage(target: ImageTarget, src: String) =
        updateState { applyImageTarget(it, target, src) }

    /** Whether this editor instance edits an import preview's in-memory copy
     *  rather than a stored row (the Delete button hides for it — J2). True
     *  even when the draft is missing, so no delete affordance renders. */
    val editingFromImport: Boolean get() = editorSource == EditorSource.IMPORT

    fun delete(onDone: () -> Unit) {
        // Delete has no import-session branch: on the preview screen it used
        // to delete the STORED row the preview question was imported from
        // (consolidated_bugs.md J2). A missing draft is the same refusal.
        if (editorSource == EditorSource.IMPORT) {
            Logger.w("EDITORVM", "delete() refused: editing an import preview, not a stored row")
            return
        }
        val current = _state.value
        if (current.busy) return
        // Prev/Next retarget this same ViewModel via state.questionId; delete
        // read the constructor's immutable id, so it removed the question the
        // editor was opened with, not the one on screen (consolidated_bugs.md
        // J1).
        val targetId = current.questionId
        if (targetId.isBlank()) return
        _state.update { it.copy(operation = EditorOperation.DELETING, saveError = null) }
        viewModelScope.launch {
            try {
                repository.deleteQuestion(targetId)
                onDone()
            } catch (e: CancellationException) {
                // Cancellation is not a delete failure.
                throw e
            } catch (e: Exception) {
                Logger.e("EDITORVM", "delete($targetId) failed", e)
                _state.update { it.copy(saveError = friendly(R.string.editor_delete_failed)) }
            } finally {
                _state.update { it.copy(operation = EditorOperation.IDLE) }
            }
        }
    }
}
