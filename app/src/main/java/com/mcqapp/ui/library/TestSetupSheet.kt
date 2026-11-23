package com.mcqapp.ui.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mcqapp.R
import com.mcqapp.domain.BlueprintQuotas
import com.mcqapp.domain.CategorySelection
import com.mcqapp.domain.ExclusionMode
import com.mcqapp.domain.BlueprintSection
import com.mcqapp.domain.BlueprintSectionView
import com.mcqapp.domain.BlueprintSummary
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.PanelSummary
import com.mcqapp.domain.Paper
import com.mcqapp.domain.PlanBlueprint
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestComparability
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestSetup
import com.mcqapp.domain.TestTime
import com.mcqapp.domain.UnfinishedTest
import com.mcqapp.domain.TestType
import com.mcqapp.domain.signals.Breadth
import com.mcqapp.domain.signals.Composition
import com.mcqapp.domain.signals.SelectionPreview
import com.mcqapp.ui.common.testTypeLabelRes
import com.mcqapp.ui.common.testTypeLabelText
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The blueprint a save must target. Kept as a value so the edit/create choice
 * is made from the *saveable* edit id, not from the asynchronously-loaded
 * blueprint summary — that summary is briefly null right after a rotation,
 * which used to turn an EDIT into a CREATE and duplicate the blueprint (H12).
 */
internal sealed interface BlueprintSaveTarget {
    data class Update(val id: Long) : BlueprintSaveTarget
    data object Create : BlueprintSaveTarget
}

/** Which repository call a blueprint save makes, decided by the saveable id. */
internal fun blueprintSaveTarget(editId: Long?): BlueprintSaveTarget =
    if (editId != null) BlueprintSaveTarget.Update(editId) else BlueprintSaveTarget.Create

/**
 * The blueprint id being edited, derived from state that rides a
 * configuration change: [isEditing] and the setup sheet's saveable `variant`
 * (the selected blueprint's id as a string). Never read this from a volatile
 * `remember` — rotation would lose it and the save would create a duplicate.
 */
internal fun blueprintEditId(isEditing: Boolean, selectedVariant: String?): Long? =
    if (isEditing) selectedVariant?.toLongOrNull() else null

/**
 * The picker rows after a delete attempt. The old code dropped the row from
 * the local list even when `runCatching` swallowed a repository failure, so a
 * locked database read as a successful delete until the next reload (L7). The
 * row is removed only on success; failure leaves it in place for a retry.
 */
internal fun <T> rowsAfterDelete(
    result: Result<Unit>,
    rows: List<T>,
    idOf: (T) -> Long,
    deletedId: Long
): List<T> = if (result.isSuccess) rows.filterNot { idOf(it) == deletedId } else rows

/**
 * The testable pool after its read: the count on success, `null` (unknown) on
 * failure. It must never fall back to the raw total — that figure includes
 * flashcards, and substituting it would enable Start on a pool the sampler
 * cannot draw from. Null keeps the sheet's existing "counting" state and
 * Start disabled until a read succeeds.
 */
internal fun testablePoolAfterRead(result: Result<Int>): Int? = result.getOrNull()

/**
 * What the sheet may act on for the testable pool. Unknown — a read in flight
 * or one that failed — is never the raw total: the count is zero and Start is
 * disabled, so it cannot enable on a pool that includes flashcards.
 */
internal data class TestablePoolState(
    val count: Int,
    val loading: Boolean,
    /** The read failed; the sheet offers a retry and Start stays disabled. */
    val failed: Boolean = false
)

internal fun testablePoolState(
    loadedCount: Int?,
    failed: Boolean = false
): TestablePoolState = when {
    failed -> TestablePoolState(count = 0, loading = false, failed = true)
    loadedCount == null -> TestablePoolState(count = 0, loading = true)
    else -> TestablePoolState(count = loadedCount, loading = false)
}

/**
 * Whether a custom pool — bookmarks, one tag or a fixed panel — holds content
 * but nothing a test can serve, so every member is a flashcard.
 * [SelectionPreview.pool] is the testable part and [SelectionPreview.rawPool]
 * the whole pool: only that contrast tells this apart from an actually empty
 * pool, which keeps the generic "no questions" message.
 */
internal fun customPoolIsFlashcardsOnly(
    type: TestType,
    preview: SelectionPreview?
): Boolean = preview != null &&
    preview.pool == 0 &&
    preview.rawPool > 0 &&
    (type == TestType.BOOKMARKED || type == TestType.TAG ||
        type == TestType.FIXED_PANEL)

/**
 * One line of the "before you start" conditions block. [arg] is the formatted
 * negative-marking rate; every other line is a plain resource id so the whole
 * block follows the active locale.
 */
internal data class ExamConditionLine(
    @StringRes val resId: Int,
    val arg: String? = null
)

/** The marking rate as a short, human label (1/3, 1/4, 1/2 or a percentage). */
internal fun negativeMarkingLabel(value: Double): String = when {
    value <= 0.0 -> ""
    kotlin.math.abs(value - 1.0 / 3.0) < 0.02 -> "1/3"
    kotlin.math.abs(value - 1.0 / 4.0) < 0.02 -> "1/4"
    kotlin.math.abs(value - 1.0 / 2.0) < 0.02 -> "1/2"
    kotlin.math.abs(value - 1.0) < 0.02 -> "1"
    else -> "${(value * 100).roundToInt()}%"
}

/**
 * The exam conditions this run will actually use, in one fixed order. Strict
 * wins over practice (a strict run hides live feedback), and every condition
 * states both sides so the learner can tell what a toggle changes without
 * opening Settings.
 */
internal fun examConditionLines(
    strictMode: Boolean,
    autoAdvance: Boolean,
    practiceMode: Boolean,
    shuffleQuestions: Boolean,
    negativeMarking: Double
): List<ExamConditionLine> {
    val liveFeedback = practiceMode && !strictMode
    return buildList {
        add(
            ExamConditionLine(
                if (strictMode) R.string.test_ux_condition_strict
                else R.string.test_ux_condition_not_strict
            )
        )
        add(
            ExamConditionLine(
                if (autoAdvance) R.string.test_ux_condition_auto_advance
                else R.string.test_ux_condition_no_auto_advance
            )
        )
        add(
            ExamConditionLine(
                if (liveFeedback) R.string.test_ux_condition_practice
                else R.string.test_ux_condition_not_practice
            )
        )
        add(
            ExamConditionLine(
                if (shuffleQuestions) R.string.test_ux_condition_shuffle
                else R.string.test_ux_condition_no_shuffle
            )
        )
        add(
            if (negativeMarking > 0.0) {
                ExamConditionLine(R.string.test_ux_condition_negative, negativeMarkingLabel(negativeMarking))
            } else {
                ExamConditionLine(R.string.test_ux_condition_no_negative)
            }
        )
    }
}

/** The 17 peer chips split into representative mocks and practice selectors. */
internal data class TestTypeGroups(
    val mocks: List<TestType>,
    val practice: List<TestType>
)

internal fun groupedTestTypes(
    types: List<TestType> = TestSetup.STARTABLE_TYPES
): TestTypeGroups = TestTypeGroups(
    mocks = types.filter { it.isRepresentative },
    practice = types.filterNot { it.isRepresentative }
)

/** Which length sentence a fixed panel shows, if any. */
internal enum class PanelLengthCopy {
    /** No panel chosen, or nothing in it a test can serve. */
    NONE,

    /** Every testable member of the panel runs: "Runs all N". */
    RUNS_ALL,

    /** The frozen set is larger than what can run: "X asked · Y will run". */
    ASKED_RUN
}

/**
 * The panel's length sentence. The picker chip keeps showing the frozen size,
 * so a shortened panel must not also print "Runs all Y" — that reads as a
 * contradiction beside "X asked · Y will run"; the asked/run line carries the
 * truth on its own. When the panel really is all testable, "Runs all N" is
 * the whole truth and there is no asked/run line.
 */
internal fun panelLengthCopy(
    panelSize: Int?,
    resolvedCount: Int,
    showsAskedRun: Boolean
): PanelLengthCopy = when {
    panelSize == null || resolvedCount <= 0 -> PanelLengthCopy.NONE
    showsAskedRun -> PanelLengthCopy.ASKED_RUN
    else -> PanelLengthCopy.RUNS_ALL
}

/**
 * The phase-1 setup sheet: what kind of test, how many questions, how long,
 * and what the learner is about to get (sections 7.2-7.5).
 *
 * It replaces the duration-only picker, which could only ever start a
 * full-paper legacy session. Everything the sheet offers is a **bounded** plan
 * the session accepts: Random, Balanced, Mistakes and the seven focus types in
 * [TestSetup.STARTABLE_TYPES], a length of 20..150 in steps of ten, and the
 * exclusion rule the settings choose.
 *
 * The rules live in [TestSetup] so they can be tested without a screen; this
 * file only draws them.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TestSetupSheet(
    paper: Paper,
    /** The type the entry point preselected (Random or Mistakes). */
    initialType: TestType,
    /** The paper's mistake count: the whole-paper pool for Mistakes. */
    mistakeCount: Int,
    /** The ticked branches; empty means the whole paper. */
    categoryIds: List<String>,
    /** Whether representative tests should avoid recently asked questions. */
    avoidRecentQuestions: Boolean,
    /**
     * Counts the mistakes inside an expanded scope. The paper-level badge
     * counts every mistake in the paper, which would overstate a scoped
     * pool, so the scope needs its own count.
     */
    loadScopedMistakeCount: suspend (List<String>) -> Int,
    /**
     * The scope's **testable** question count: multiple-choice questions only,
     * because a flashcard is study-only and never reaches a test. Empty means
     * the whole paper. The sheet counts with this, never with the paper's raw
     * total (the library card keeps that raw figure).
     */
    loadTestableCount: suspend (List<String>) -> Int,
    /**
     * The paper's per-category testable counts, for the blueprint editor's
     * preview pools and quotas. One grouped read; empty while it is in flight.
     */
    loadTestableCategoryCounts: suspend () -> Map<String, Int>,
    /**
     * The smallest passage block the selected type would sample. The sampler
     * pre-check uses it so Start cannot emit a route that fails at load
     * (section 9.6).
     */
    loadMinBlockSize: suspend (TestType, List<String>) -> Int,
    /**
     * The live composition preview for a signal-selected type. Display-only: a
     * failure leaves the preview blank and Start still runs the real selector.
     * [variant] is the custom choice (phase 3): the folded tag or difficulty
     * name, or null. [exclusion] is the Fresh rule Start will apply, so the
     * preview reads the same pool.
     */
    loadPreview: suspend (TestType, Int, Breadth, String?, ExclusionMode) -> SelectionPreview,
    /**
     * The length the learner last started a test with (plan §10). It is only a
     * starting point: [TestPlan.normalizeLength] snaps it to a selectable step
     * and the pool clamps it further.
     */
    loadLastLength: suspend () -> Int = { TestPlan.DEFAULT_LENGTH },
    /** The scope's distinct tags for the phase-3 Tag picker. */
    loadTags: suspend () -> List<com.mcqapp.data.local.TagCount> = { emptyList() },
    /** The paper's fixed panels for the phase-3 panel picker. */
    loadPanels: suspend () -> List<PanelSummary> = { emptyList() },
    /**
     * Saves the preview's selected ids as a named panel. Returns the created
     * panel, or null when the name is blank or nothing was selected.
     */
    onCreatePanel: suspend (String, List<String>) -> PanelSummary? = { _, _ -> null },
    /** Removes a fixed panel and its member rows. */
    onDeletePanel: suspend (Long) -> Unit = {},
    /** The paper's blueprints for the Blueprint picker. */
    loadBlueprints: suspend (String) -> List<BlueprintSummary> = { emptyList() },
    /** One blueprint's section views, for the editor and the scope preview. */
    loadBlueprintSections: suspend (Long) -> List<BlueprintSectionView> = { emptyList() },
    /**
     * Creates a blueprint and returns its id. Throws with a user-facing
     * message when the repository refuses the save (e.g. duplicate name).
     */
    onCreateBlueprint: suspend (String, List<BlueprintSection>) -> Long = { _, _ -> 0L },
    /** Replaces a blueprint's name and sections. Throws with a user message on refusal. */
    onUpdateBlueprint: suspend (Long, String, List<BlueprintSection>) -> Unit = { _, _, _ -> },
    /** Deletes a blueprint. */
    onDeleteBlueprint: suspend (Long) -> Unit = {},
    /** Live exam conditions the run will use, shown before Start. */
    strictMode: Boolean = false,
    autoAdvance: Boolean = false,
    practiceMode: Boolean = false,
    shuffleQuestions: Boolean = false,
    /** The paper's marking rate, shown as part of the conditions. */
    negativeMarking: Double = 0.0,
    /** The single saved session, when one exists, for the replacement notice. */
    savedTest: UnfinishedTest? = null,
    /** One-tap route to the screen that owns the conditions above. */
    onOpenSettings: () -> Unit = {},
    /** When the scope holds only flashcards, send the learner to Study instead. */
    onStudyFlashcards: (List<String>) -> Unit = {},
    /** Leaves the sheet so the saved session can be resumed instead. */
    onKeepSavedTest: () -> Unit = {},
    onDismiss: () -> Unit,
    onStart: (
        type: TestType,
        requestedCount: Int,
        minutes: Int,
        exclusion: ExclusionMode,
        breadth: Breadth,
        variant: String?
    ) -> Unit
) {
    var type by rememberSaveable(initialType) { mutableStateOf(initialType) }
    var requestedCount by rememberSaveable { mutableIntStateOf(TestPlan.DEFAULT_LENGTH) }
    /** Set once the stored length has been read, so a rotation cannot re-apply it. */
    var lengthRestored by rememberSaveable { mutableStateOf(false) }
    /** A length the learner picked this time is never overridden by the pool clamp. */
    var lengthChosen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!lengthRestored) {
            requestedCount = TestPlan.normalizeLength(
                runCatching { loadLastLength() }.getOrDefault(TestPlan.DEFAULT_LENGTH)
            )
            lengthRestored = true
        }
    }
    var breadth by rememberSaveable { mutableStateOf(Breadth.MEDIUM) }
    /** The custom choice (phase 3): folded tag for `TAG`, difficulty name else. */
    var variant by rememberSaveable { mutableStateOf<String?>(null) }
    /** Coverage's exposure mode (plan §10); the plan carries it as the variant. */
    var weightByExposure by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(type) {
        if (type != TestType.COVERAGE) weightByExposure = false
    }
    /**
     * What the route and the preview carry: the custom choice, or Coverage's
     * exposure mode. Keeping one value means the preview and Start cannot
     * disagree about the mode.
     */
    val effectiveVariant = if (type == TestType.COVERAGE && weightByExposure) {
        TestPlan.EXPOSURE_VARIANT
    } else {
        variant
    }
    /** The Fresh rule this setup would record and Start would apply. */
    val exclusionMode = if (type.supportsFreshSample && avoidRecentQuestions) {
        ExclusionMode.RECENT_3
    } else {
        ExclusionMode.NONE
    }
    /** The scope's distinct tags, loaded once for the Tag picker. */
    var tags by remember(paper.id, categoryIds) { mutableStateOf<List<com.mcqapp.data.local.TagCount>?>(null) }
    LaunchedEffect(paper.id, categoryIds, type) {
        if (type == TestType.TAG && tags == null) {
            tags = runCatching { loadTags() }.getOrDefault(emptyList())
        }
    }
    /** The paper's fixed panels, loaded once for the panel picker. */
    var panels by remember(paper.id) { mutableStateOf<List<PanelSummary>?>(null) }
    LaunchedEffect(paper.id, type) {
        if (type == TestType.FIXED_PANEL && panels == null) {
            panels = runCatching { loadPanels() }.getOrDefault(emptyList())
        }
    }
    /** The "New panel…" dialog's name field. */
    var showPanelDialog by rememberSaveable { mutableStateOf(false) }
    var panelName by rememberSaveable { mutableStateOf("") }
    /** The panel awaiting delete confirmation (id + name ride rotation). */
    var panelToDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var panelToDeleteName by rememberSaveable { mutableStateOf("") }
    /** The paper's blueprints, loaded once for the Blueprint picker. */
    var blueprints by remember(paper.id) { mutableStateOf<List<BlueprintSummary>?>(null) }
    LaunchedEffect(paper.id, type, blueprints == null) {
        if (type == TestType.BLUEPRINT && blueprints == null) {
            blueprints = runCatching { loadBlueprints(paper.id) }.getOrDefault(emptyList())
        }
    }
    /** The chosen blueprint's section views (the editor's prefill and preview). */
    var blueprintViews by remember(paper.id, variant) {
        mutableStateOf<List<BlueprintSectionView>?>(null)
    }
    /**
     * The paper's per-category testable counts for the blueprint editor's
     * pools and quotas. A failed read stays empty, which reads as zero — never
     * the raw count that would promise a quota the run cannot fill.
     */
    var testableCountsByCategory by remember(paper.id) {
        mutableStateOf<Map<String, Int>>(emptyMap())
    }
    LaunchedEffect(paper.id) {
        testableCountsByCategory =
            runCatching { loadTestableCategoryCounts() }.getOrDefault(emptyMap())
    }
    /** Bumped after a save so editing the already-selected blueprint reloads it. */
    var blueprintRefresh by remember(paper.id) { mutableIntStateOf(0) }
    LaunchedEffect(paper.id, type, variant, blueprintRefresh) {
        if (type == TestType.BLUEPRINT && variant != null) {
            val id = variant?.toLongOrNull()
            blueprintViews = if (id != null) {
                runCatching { loadBlueprintSections(id) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        } else {
            blueprintViews = null
        }
    }
    /** The editor is open; the edit flag rides rotation alongside `variant`. */
    var showBlueprintEditor by rememberSaveable { mutableStateOf(false) }
    var blueprintEditorEditing by rememberSaveable { mutableStateOf(false) }
    // Derived from saveable state, so a rotation cannot flip an EDIT into a
    // CREATE even in the frames before the blueprint list has reloaded.
    val editingBlueprintId: Long? = blueprintEditId(blueprintEditorEditing, variant)
    /** The blueprint awaiting delete confirmation (id + name ride rotation). */
    var blueprintToDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var blueprintToDeleteName by rememberSaveable { mutableStateOf("") }
    /** Name of an item whose delete failed; its row is kept and an error shown. */
    var deleteErrorName by rememberSaveable { mutableStateOf<String?>(null) }
    val panelScope = rememberCoroutineScope()
    /** Null means "follow the suggested pace"; a number is the learner's own. */
    var minutesOverride by rememberSaveable { mutableStateOf<String?>(null) }
    /**
     * The 14 practice types are a secondary group, collapsed so the three
     * mocks are not drowned out. The selected practice chip stays visible in
     * the collapsed state, so its help sentence always has an anchor.
     */
    var showPracticeTypes by rememberSaveable { mutableStateOf(false) }

    // A blueprint owns its scope (plan §3 #11): selecting BLUEPRINT clears
    // the category tick-set for this sheet, and the run's scope is the
    // chosen blueprint's section union instead. The library's own selection
    // is left alone, so switching back to another type restores it.
    val tickedIds = if (type == TestType.BLUEPRINT) emptyList() else categoryIds
    val expanded = remember(paper.categories, tickedIds) {
        CategorySelection.orderedIds(paper.categories, tickedIds)
    }
    val scopeKey = remember(paper.id, expanded) {
        if (tickedIds.isEmpty()) {
            ScopeKey.wholePaper(paper.id)
        } else {
            ScopeKey.ofExpanded(expanded)
        }
    }
    // The scope's raw question count, copy only: it is what separates a truly
    // empty scope from one that holds nothing but flashcards. The pool the
    // sampler draws from is the *testable* count, loaded below.
    val rawQuestionPool = remember(paper.categories, paper.totalQuestions, tickedIds) {
        if (tickedIds.isEmpty()) {
            paper.totalQuestions
        } else {
            CategorySelection.questionCount(paper.categories, tickedIds)
        }
    }

    // The scoped mistake pool is a query of its own; until it lands the
    // sheet cannot say how many mistakes the scope holds.
    var scopedMistakes by remember(paper.id, expanded) { mutableStateOf<Int?>(null) }
    LaunchedEffect(paper.id, expanded) {
        if (tickedIds.isEmpty()) {
            scopedMistakes = null
        } else {
            scopedMistakes = runCatching { loadScopedMistakeCount(expanded) }.getOrDefault(0)
        }
    }
    val mistakePool = if (tickedIds.isEmpty()) mistakeCount else scopedMistakes
    // The scope's testable supply: multiple-choice rows only, never the raw
    // total. Random draws its whole pool from it, and every other type uses it
    // to tell an empty scope from a scope whose content is all flashcards.
    // Until the count lands it is unknown: pool zero, Start disabled. A failed
    // read is a separate state — it offers a retry instead of an endless
    // spinner, and never falls back to the raw total (which includes cards).
    var testablePool by remember(paper.id, expanded) { mutableStateOf<Int?>(null) }
    var testablePoolFailed by remember(paper.id, expanded) { mutableStateOf(false) }
    var testablePoolRetry by remember(paper.id, expanded) { mutableIntStateOf(0) }
    LaunchedEffect(paper.id, expanded, testablePoolRetry) {
        val result = runCatching { loadTestableCount(expanded) }
        testablePool = testablePoolAfterRead(result)
        testablePoolFailed = result.isFailure
    }
    val poolState = testablePoolState(testablePool, failed = testablePoolFailed)
    val questionPool = poolState.count
    val testablePoolLoading = poolState.loading
    val poolLoading = (type == TestType.MISTAKES && mistakePool == null) || testablePoolLoading
    // A custom type's pool comes from the preview (computed below); until
    // it lands the pool reads empty and Start waits. A blueprint's pool is
    // the achievable quota sum the preview carries, not the raw scope size.
    val isCustom = type == TestType.BOOKMARKED || type == TestType.TAG ||
        type == TestType.AUTHORED_DIFFICULTY || type == TestType.SLOWEST ||
        type == TestType.FIXED_PANEL || type == TestType.BLUEPRINT

    // A fixed panel runs its whole frozen set: its length is the panel's size,
    // not the sampled chip the other types share. The size comes from the
    // picker's summary, so it is known before the preview lands.
    val panelBlocked = type == TestType.FIXED_PANEL && categoryIds.isNotEmpty()
    val panelSize = if (type == TestType.FIXED_PANEL && !panelBlocked) {
        panels?.firstOrNull { it.id.toString() == variant }?.size
    } else {
        null
    }
    val requestCount = if (type == TestType.FIXED_PANEL && (panelSize ?: 0) > 0) {
        panelSize ?: requestedCount
    } else {
        requestedCount
    }

    // The live preview of what the current request would select. It is a read
    // of the same signals the session uses, so the composition the learner sees
    // before Start is the one the attempt records.
    var selectionPreview by remember(paper.id, expanded) {
        mutableStateOf<SelectionPreview?>(null)
    }
    // Start true for a preview-driven type: the first frame must not read the
    // not-yet-loaded pool as an empty scope and flash a red error.
    var previewLoading by remember(paper.id, expanded, type) {
        mutableStateOf(type.usesSignalSelector)
    }
    LaunchedEffect(
        paper.id, expanded, type, requestCount, breadth, effectiveVariant, exclusionMode
    ) {
        if (!type.usesSignalSelector || panelBlocked) {
            selectionPreview = null
            previewLoading = false
            return@LaunchedEffect
        }
        previewLoading = true
        selectionPreview = runCatching {
            loadPreview(type, requestCount, breadth, effectiveVariant, exclusionMode)
        }.getOrNull()
        previewLoading = false
    }
    // A custom type's pool comes from the preview: Start clamps against the
    // pool the selector will actually draw from, not the whole scope. A
    // blueprint uses its total supply, not the achievable quota at the current
    // request: the latter is capped at the request, so using it here would make
    // the length control unable to grow past its own current value.
    val available = when {
        type == TestType.BLUEPRINT -> selectionPreview?.capacity ?: 0
        type == TestType.MISTAKES -> mistakePool ?: 0
        isCustom -> selectionPreview?.pool ?: 0
        else -> questionPool
    }

    // A fixed panel's length is its size, so Start must wait for the picker's
    // summary; otherwise the first frame could start the default 20 of a
    // larger panel.
    val panelsLoading = type == TestType.FIXED_PANEL && categoryIds.isEmpty() && panels == null
    val blueprintLoading = type == TestType.BLUEPRINT && variant != null &&
        (blueprints == null || blueprintViews == null)
    // The frozen blueprint the chosen blueprint resolves to, built from its
    // section views; each terminal's scope is its paper subtree. Null until
    // the views land, so Start waits instead of starting a blueprint-less run.
    val blueprintPlan = if (type == TestType.BLUEPRINT && variant != null) {
        val views = blueprintViews.orEmpty()
        val selected = blueprints?.firstOrNull { it.id.toString() == variant }
        if (views.isNotEmpty() && selected != null) {
            PlanBlueprint(label = selected.name, sections = views.toPlanSections(paper.categories))
        } else {
            null
        }
    } else {
        null
    }
    val blueprintName = blueprints?.firstOrNull { it.id.toString() == variant }?.name
    // The raw count of the scope being set up: the blueprint union when one is
    // chosen (its views' pools are testable-only, so they cannot answer this),
    // a fixed panel's own frozen size, otherwise the ticked expansion or the
    // whole paper. Copy only — it separates "nothing here" from "nothing to
    // test here".
    val rawScopeCount = when {
        type == TestType.BLUEPRINT ->
            blueprintPlan?.categoryIds?.takeIf { it.isNotEmpty() }
                ?.let { CategorySelection.questionCount(paper.categories, it) }
                ?: paper.totalQuestions
        type == TestType.FIXED_PANEL && panelSize != null -> panelSize ?: 0
        else -> rawQuestionPool
    }
    // Content exists, but every question the type can draw from is a
    // flashcard, which a test can never serve. A blueprint's supply comes from
    // its own section views (already testable-only), a panel's from the
    // preview's pool, the rest from the scope count. Null means "not known
    // yet" (no panel chosen), never a verdict. Read only once the pool has
    // actually landed: a count still in flight is not a verdict either.
    val scopeTestableCount: Int? = when {
        type == TestType.BLUEPRINT ->
            blueprintViews?.takeIf { blueprintPlan != null }?.sumOf { it.pool }
        type == TestType.FIXED_PANEL ->
            selectionPreview?.takeIf { variant != null && panelSize != null }?.pool
        else -> questionPool
    }
    val scopeIsFlashcardsOnly = !poolLoading && !previewLoading && (
        customPoolIsFlashcardsOnly(type, selectionPreview) ||
            (scopeTestableCount == 0 && rawScopeCount > 0)
        )
    // A blueprint below the minimum length cannot start: Start is refused
    // with the short-scope message, exactly like a small scope today.
    val blueprintTooShort = type == TestType.BLUEPRINT && available < TestPlan.MIN_LENGTH

    // The sampler's smallest block for the pool the selected type draws from.
    // Null while the query is in flight, so Start waits instead of emitting a
    // route the session would refuse at load. The scope is the blueprint's
    // union in Blueprint mode: the sheet clears the tick-set there, and a
    // minimum over the whole paper would let a run start that the union's own
    // smallest block cannot fit (blueprint.md §8 Bug 2).
    val minBlockScope = TestSetup.blockScopeIds(expanded, type, blueprintPlan)
    var minBlockSize by remember(paper.id, minBlockScope, type, available) {
        mutableStateOf<Int?>(null)
    }
    LaunchedEffect(paper.id, minBlockScope, type, available) {
        minBlockSize = if (available <= 0) {
            1
        } else {
            runCatching { loadMinBlockSize(type, minBlockScope) }.getOrDefault(1)
        }
    }
    val blockLoading = minBlockSize == null

    val setupCategoryIds = blueprintPlan?.categoryIds ?: tickedIds
    val setupScopeKey = if (blueprintPlan != null) {
        ScopeKey.ofExpanded(
            CategorySelection.orderedIds(paper.categories, blueprintPlan.categoryIds)
        )
    } else {
        scopeKey
    }
    val setup = TestSetup(
        paperId = paper.id,
        categoryIds = setupCategoryIds,
        scopeKey = setupScopeKey,
        type = type,
        requestedCount = requestCount,
        time = minutesOverride?.let { TestTime.Fixed(it.toIntOrNull() ?: 0) }
            ?: TestTime.Suggested,
        available = available,
        exclusionMode = exclusionMode,
        variant = when {
            panelBlocked -> null
            type == TestType.COVERAGE -> effectiveVariant
            type == TestType.TAG ||
                type == TestType.AUTHORED_DIFFICULTY ||
                type == TestType.FIXED_PANEL ||
                type == TestType.BLUEPRINT -> variant
            else -> null
        },
        blueprint = blueprintPlan,
        minBlockSize = minBlockSize ?: 1,
        scopeIsFlashcardsOnly = scopeIsFlashcardsOnly
    )
    // The largest length this pool can actually supply. Chips above it are
    // disabled rather than left to the clamp message (§7.2, §14); the message
    // still covers a length carried over from a larger scope or type. A
    // blueprint rounds up, because its run clamps to the exact quota sum.
    val maxSelectable = if (type == TestType.BLUEPRINT) {
        TestPlan.maxRequestForBlueprint(available)
    } else {
        TestPlan.maxAllowedFor(available)
    }
    /**
     * Whether pressing Start would take a different session's slot. The notice
     * is shown before the first answer, not after the saved answers are gone.
     * A focus type records its targets at load, so an empty target list on this
     * side is the "not known yet" case [TestSnapshot.matches] accepts.
     */
    val replacesSaved = savedTest?.let { saved ->
        !saved.snapshot.matches(
            paperId = paper.id,
            categoryIds = setupCategoryIds,
            isDrill = false,
            mistakesOnly = false,
            testType = type.name,
            requestedCount = requestCount,
            exclusionMode = exclusionMode.name,
            targetTopicIds = emptyList(),
            scopeKey = setupScopeKey,
            variant = effectiveVariant
        )
    } ?: false

    val maxLengthIndex = TestPlan.LENGTHS.indexOf(maxSelectable).coerceAtLeast(0)

    // A restored length is clamped to the current pool (plan §10). A length
    // the learner picked this time keeps the existing "asked/run" message
    // instead of being silently lowered.
    LaunchedEffect(maxSelectable, lengthRestored, lengthChosen) {
        if (lengthRestored && !lengthChosen &&
            maxSelectable >= TestPlan.MIN_LENGTH && requestedCount > maxSelectable
        ) {
            requestedCount = maxSelectable
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                stringResource(R.string.test_setup_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                paper.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            // --- type ---
            Text(stringResource(R.string.test_setup_type), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            val groups = groupedTestTypes()
            Text(
                stringResource(R.string.test_ux_group_mocks),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.mocks.forEach { candidate ->
                    FilterChip(
                        selected = type == candidate,
                        onClick = {
                            if (type != candidate) variant = null
                            type = candidate
                        },
                        label = { Text(stringResource(testTypeLabelRes(candidate))) },
                        modifier = Modifier.testTag("test-setup-type-${candidate.name}")
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.test_ux_group_practice),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showPracticeTypes = !showPracticeTypes }) {
                    Text(
                        stringResource(
                            if (showPracticeTypes) R.string.test_ux_practice_less
                            else R.string.test_ux_practice_more
                        )
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                groups.practice
                    .filter { showPracticeTypes || type == it }
                    .forEach { candidate ->
                        FilterChip(
                            selected = type == candidate,
                            onClick = {
                                if (type != candidate) variant = null
                                type = candidate
                            },
                            label = { Text(stringResource(testTypeLabelRes(candidate))) },
                            modifier = Modifier.testTag("test-setup-type-${candidate.name}")
                        )
                    }
            }
            Text(
                stringResource(
                    when (type) {
                        TestType.MISTAKES -> R.string.test_setup_type_mistakes_help
                        TestType.WEAKEST_TOPICS -> R.string.test_setup_type_weakest_help
                        TestType.TOUGHEST_QUESTIONS -> R.string.test_setup_type_toughest_help
                        TestType.EASIEST_QUESTIONS -> R.string.test_setup_type_easiest_help
                        TestType.BALANCED -> R.string.test_setup_type_balanced_help
                        TestType.COVERAGE -> R.string.test_setup_type_coverage_help
                        TestType.TRICKY -> R.string.test_setup_type_tricky_help
                        TestType.AT_RISK -> R.string.test_setup_type_at_risk_help
                        TestType.UNSEEN -> R.string.test_setup_type_unseen_help
                        TestType.MIXED -> R.string.test_setup_type_mixed_help
                        TestType.BOOKMARKED -> R.string.test_setup_type_bookmarked_help
                        TestType.TAG -> R.string.test_setup_type_tag_help
                        TestType.AUTHORED_DIFFICULTY -> R.string.test_setup_type_authored_help
                        TestType.SLOWEST -> R.string.test_setup_type_slowest_help
                        TestType.FIXED_PANEL -> R.string.test_setup_type_fixed_panel_help
                        TestType.BLUEPRINT -> R.string.test_setup_type_blueprint_help
                        else -> R.string.test_setup_type_random_help
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Coverage's exposure mode (plan §10). The type stays Coverage;
            // only the within-topic order changes, so the score is still a
            // practice sample, not a mock.
            if (type == TestType.COVERAGE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = weightByExposure,
                        onCheckedChange = { weightByExposure = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.test_setup_coverage_exposure),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    stringResource(R.string.test_setup_coverage_exposure_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // A custom type needs its choice before Start is offered.
            when (type) {
                TestType.TAG -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.test_setup_pick_tag),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    if (tags.isNullOrEmpty()) {
                        Text(
                            stringResource(R.string.test_setup_no_tags),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            tags.orEmpty().forEach { counted ->
                                FilterChip(
                                    selected = variant == counted.tagFolded,
                                    onClick = {
                                        variant = if (variant == counted.tagFolded) null else counted.tagFolded
                                    },
                                    label = {
                                        Text(
                                            stringResource(R.string.name_count_line, counted.tag, counted.count)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
                TestType.AUTHORED_DIFFICULTY -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.test_setup_pick_difficulty),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.mcqapp.domain.Difficulty.entries.forEach { difficulty ->
                            FilterChip(
                                selected = variant == difficulty.name,
                                onClick = {
                                    variant = if (variant == difficulty.name) null else difficulty.name
                                },
                                label = { Text(difficulty.label) }
                            )
                        }
                    }
                }
                TestType.FIXED_PANEL -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.test_setup_pick_panel),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    if (categoryIds.isNotEmpty()) {
                        // A panel is a whole-paper set; running it under a
                        // category scope would silently drop members and make
                        // two runs of "the same" panel different sets.
                        Text(
                            stringResource(R.string.test_setup_panel_whole_paper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (panels.isNullOrEmpty()) {
                        Text(
                            stringResource(R.string.test_setup_no_panels),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            panels.orEmpty().forEach { panel ->
                                val id = panel.id.toString()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    FilterChip(
                                        selected = variant == id,
                                        onClick = {
                                            variant = if (variant == id) null else id
                                        },
                                        label = { Text(stringResource(R.string.name_count_line, panel.name, panel.size)) }
                                    )
                                    // A panel is just a saved id set, but it is
                                    // not reproducible once the metrics move;
                                    // the trash is a separate, explicit target.
                                    IconButton(onClick = {
                                        panelToDeleteId = panel.id
                                        panelToDeleteName = panel.name
                                    }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.test_setup_panel_delete)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                TestType.BLUEPRINT -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.test_setup_pick_blueprint),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    if (blueprints.isNullOrEmpty()) {
                        Text(
                            stringResource(R.string.test_setup_no_blueprints),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            blueprints.orEmpty().forEach { blueprint ->
                                val id = blueprint.id.toString()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    FilterChip(
                                        selected = variant == id,
                                        onClick = {
                                            variant = if (variant == id) null else id
                                        },
                                        label = {
                                            Text(stringResource(R.string.name_count_line, blueprint.name, blueprint.sectionCount))
                                        }
                                    )
                                    IconButton(onClick = {
                                        blueprintToDeleteId = blueprint.id
                                        blueprintToDeleteName = blueprint.name
                                    }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.test_setup_blueprint_delete)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            blueprintEditorEditing = false
                            showBlueprintEditor = true
                        }) {
                            Text(stringResource(R.string.test_setup_blueprint_new))
                        }
                        if (variant != null) {
                            // The editor prefills from the section views; opening
                            // it before they land would show a blank tree.
                            TextButton(
                                enabled = blueprintViews != null,
                                onClick = {
                                    blueprintEditorEditing = true
                                    showBlueprintEditor = true
                                }
                            ) {
                                Text(stringResource(R.string.test_setup_blueprint_edit))
                            }
                        }
                    }
                    // The resolved section tree and achievable count, before Start.
                    val previewViews = blueprintViews.orEmpty()
                    if (previewViews.isNotEmpty()) {
                        BlueprintPreviewRows(
                            views = previewViews,
                            count = requestCount,
                            actual = selectionPreview?.terminalCounts.orEmpty(),
                            depth = 0
                        )
                    }
                }
                else -> Unit
            }

            // Breadth only means something for the category-based practice
            // selectors; the metric selectors have no K to choose.
            if (type == TestType.WEAKEST_TOPICS || type == TestType.MIXED) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.test_setup_breadth),
                    style = MaterialTheme.typography.labelLarge
                )
                // A plain Row squeezed the three chips into a single column of
                // characters on a narrow sheet; they wrap like the type chips.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Breadth.entries.forEach { option ->
                        FilterChip(
                            selected = breadth == option,
                            onClick = { breadth = option },
                            label = { Text(stringResource(breadthLabel(option))) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // --- scope and pool ---
            Text(
                if (type == TestType.BLUEPRINT) {
                    stringResource(R.string.test_setup_blueprint_scope)
                } else if (categoryIds.isEmpty()) {
                    stringResource(R.string.test_setup_scope_whole_paper)
                } else {
                    pluralStringResource(R.plurals.test_setup_scope_categories, categoryIds.size, categoryIds.size)
                },
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                when {
                    poolState.failed -> stringResource(R.string.test_setup_count_failed)
                    poolLoading -> stringResource(R.string.test_setup_counting)
                    type == TestType.MISTAKES -> pluralStringResource(
                        R.plurals.test_setup_pool_mistakes, mistakePool ?: 0, mistakePool ?: 0
                    )
                    type.usesSignalSelector && selectionPreview != null -> stringResource(
                        R.string.test_setup_pool_type, available
                    )
                    else -> pluralStringResource(R.plurals.test_setup_pool_questions, questionPool, questionPool)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // A failed count is retryable in place: the sheet is not reopened
            // just to try again, and Start stays disabled until a read lands.
            if (poolState.failed) {
                TextButton(onClick = {
                    testablePool = null
                    testablePoolFailed = false
                    testablePoolRetry++
                }) {
                    Text(stringResource(R.string.test_setup_count_retry))
                }
            }
            if (type.usesSignalSelector) {
                Text(
                    if (previewLoading && selectionPreview == null) {
                        stringResource(R.string.test_setup_preview_loading)
                    } else {
                        stringResource(
                            R.string.test_setup_composition,
                            compositionText(selectionPreview?.composition)
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                selectionPreview?.targets.orEmpty().forEach { target ->
                    Text(
                        stringResource(
                            R.string.test_setup_target_row,
                            target.title,
                            (target.accuracy * 100).roundToInt(),
                            target.correct,
                            target.asked
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Any signal-selected sample can be frozen as a panel. The
                // button lives here rather than in the panel picker: a panel
                // is created *from* a sample, so the learner picks a type,
                // sees the sample, then saves it.
                if (type.usesSignalSelector && type != TestType.FIXED_PANEL) {
                    TextButton(onClick = {
                        panelName = ""
                        showPanelDialog = true
                    }) {
                        Text(stringResource(R.string.test_setup_panel_new))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // --- length ---
            if (type == TestType.FIXED_PANEL) {
                // The panel is the length: every member a test can serve runs,
                // every time. A member that is a flashcard is study-only, so a
                // frozen set that shrank says both numbers instead of silently
                // running fewer than it holds — and it must not also claim
                // "runs all 2" beside "5 asked · 2 will run". A panel with
                // nothing testable shows no length at all; the flashcards-only
                // note explains it.
                when (panelLengthCopy(panelSize, setup.resolvedCount(), setup.showsAskedRun())) {
                    PanelLengthCopy.RUNS_ALL -> Text(
                        stringResource(R.string.test_setup_panel_runs_all, setup.resolvedCount()),
                        style = MaterialTheme.typography.labelLarge
                    )
                    PanelLengthCopy.ASKED_RUN -> Text(
                        stringResource(
                            R.string.test_setup_asked_run,
                            requestCount,
                            setup.resolvedCount()
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    PanelLengthCopy.NONE -> Unit
                }
            } else if (setup.short() && type != TestType.BLUEPRINT && !poolLoading) {
                // The whole scope is below the minimum: there is nothing to
                // choose, the session runs every question it holds. A short
                // blueprint is refused instead, so calling it a "short test"
                // next to "at least 20 are needed" contradicts itself.
                Text(
                    pluralStringResource(R.plurals.test_setup_short_test, setup.resolvedCount(), setup.resolvedCount()),
                    style = MaterialTheme.typography.labelLarge
                )
            } else {
                Text(
                    stringResource(R.string.test_setup_length),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    if (setup.showsAskedRun()) {
                        stringResource(
                            R.string.test_setup_asked_run,
                            requestedCount,
                            setup.resolvedCount()
                        )
                    } else {
                        pluralStringResource(R.plurals.test_setup_count_label, requestedCount, requestedCount)
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                if (maxLengthIndex > 0) {
                    Slider(
                        value = TestPlan.LENGTHS.indexOf(requestedCount)
                            .coerceIn(0, maxLengthIndex)
                            .toFloat(),
                        onValueChange = {
                            val index = it.roundToInt().coerceIn(0, maxLengthIndex)
                            requestedCount = TestPlan.LENGTHS[index]
                            lengthChosen = true
                        },
                        valueRange = 0f..maxLengthIndex.toFloat(),
                        // One stop between the selectable lengths: the slider
                        // lands on a length, never between two.
                        steps = maxLengthIndex - 1
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TestPlan.PRESETS.forEach { preset ->
                        FilterChip(
                            selected = requestedCount == preset,
                            enabled = preset <= maxSelectable,
                            onClick = {
                                requestedCount = preset
                                lengthChosen = true
                            },
                            label = { Text(preset.toString()) },
                            modifier = Modifier.testTag("test-setup-preset-$preset")
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // --- time ---
            Text(stringResource(R.string.test_setup_time), style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = minutesOverride ?: setup.suggestedMinutes().toString(),
                onValueChange = { minutesOverride = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text(stringResource(R.string.test_setup_minutes)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.test_setup_suggested, setup.suggestedMinutes()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (minutesOverride != null) {
                    TextButton(onClick = { minutesOverride = null }) {
                        Text(stringResource(R.string.test_setup_use_suggested))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // --- preview ---
            Text(
                buildString {
                    append(testTypeLabelText(type))
                    if (type == TestType.BLUEPRINT && blueprintName != null) {
                        append(" · ")
                        append(blueprintName)
                    }
                    append(" · ")
                    append(pluralStringResource(R.plurals.test_setup_count_label, setup.resolvedCount(), setup.resolvedCount()))
                    append(" · ")
                    append(if (setup.minutes() <= 0) {
                        stringResource(R.string.test_setup_untimed)
                    } else {
                        stringResource(R.string.test_setup_minutes_label, setup.minutes())
                    })
                    append(" · ")
                    append(when (setup.comparability()) {
                        TestComparability.COMPARABLE -> stringResource(R.string.test_preview_comparable)
                        TestComparability.FRESH -> stringResource(R.string.test_preview_fresh)
                        TestComparability.PRACTICE -> stringResource(R.string.test_preview_practice)
                        TestComparability.SHORT -> pluralStringResource(
                            R.plurals.test_preview_short, setup.resolvedCount(), setup.resolvedCount()
                        )
                    })
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (type.supportsFreshSample && avoidRecentQuestions) {
                Text(
                    stringResource(R.string.test_setup_fresh_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (type == TestType.BLUEPRINT && avoidRecentQuestions) {
                Text(
                    stringResource(R.string.test_setup_blueprint_fresh_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val note = when {
                // The pool line already carries the failure and its retry;
                // the empty-scope message would be a verdict about a read that
                // never completed.
                poolState.failed -> null
                // An in-flight preview has no pool yet; the empty-scope
                // message here would be an error about a state we have not
                // finished reading.
                previewLoading -> null
                poolLoading -> null
                // The whole-paper notice above already says why a panel
                // cannot run under a category scope.
                panelBlocked -> null
                // Content exists but all of it is study-only: say that rather
                // than calling a non-empty scope empty.
                setup.flashcardsOnlyScope() ->
                    stringResource(R.string.test_setup_scope_flashcards_only)
                type == TestType.MISTAKES && available == 0 ->
                    stringResource(R.string.test_setup_no_mistakes)
                type == TestType.BLUEPRINT && selectionPreview != null && blueprintTooShort ->
                    stringResource(R.string.test_setup_blueprint_short, available)
                else -> setup.message()
            }
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                // A disabled Start with only study-only cards must give a way
                // forward: the same scope, opened in Study where cards work.
                if (setup.flashcardsOnlyScope()) {
                    TextButton(
                        onClick = { onStudyFlashcards(categoryIds) },
                        modifier = Modifier.testTag("test-setup-study-flashcards")
                    ) {
                        Text(stringResource(R.string.of_study_these))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // --- exam conditions (finding 1) ---
            // The preview says *what* will be asked; this says *how* it will be
            // run. Both are needed before Start, because strict mode and
            // negative marking change what a score means.
            Text(
                stringResource(R.string.test_ux_conditions_title),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("test-setup-conditions")
            )
            examConditionLines(strictMode, autoAdvance, practiceMode, shuffleQuestions, negativeMarking)
                .forEach { line ->
                    Text(
                        if (line.arg != null) {
                            stringResource(line.resId, line.arg)
                        } else {
                            stringResource(line.resId)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.test_ux_condition_settings))
            }

            if (replacesSaved) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.test_ux_replace_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("test-setup-replace-notice")
                )
                TextButton(onClick = onKeepSavedTest) {
                    Text(stringResource(R.string.test_ux_replace_keep))
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { onStart(type, requestCount, setup.minutes(), exclusionMode, breadth, effectiveVariant) },
                enabled = setup.startable() && !blueprintTooShort && !blueprintLoading &&
                    !poolLoading && !poolState.failed && !blockLoading && !panelsLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("test-setup-start")
            ) {
                Text(
                    when {
                        !setup.startable() -> stringResource(R.string.start_test)
                        setup.minutes() <= 0 -> stringResource(
                            R.string.test_setup_start_untimed, setup.resolvedCount()
                        )
                        else -> stringResource(
                            R.string.test_setup_start,
                            setup.resolvedCount(), setup.minutes()
                        )
                    }
                )
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    }

    // "New panel…" saves the sample the preview is showing: the ids come from
    // the same selector the session would run, so the panel repeats exactly
    // the questions the learner just saw.
    if (showPanelDialog) {
        val previewIds = selectionPreview?.ids.orEmpty()
        AlertDialog(
            onDismissRequest = { showPanelDialog = false },
            title = { Text(stringResource(R.string.test_setup_panel_new)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = panelName,
                        onValueChange = { panelName = it.take(60) },
                        label = { Text(stringResource(R.string.test_setup_panel_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (previewIds.isEmpty()) {
                            stringResource(R.string.test_setup_panel_new_nothing)
                        } else {
                            stringResource(R.string.test_setup_panel_new_help, previewIds.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = panelName.isNotBlank() && previewIds.isNotEmpty(),
                    onClick = {
                        val name = panelName
                        panelScope.launch {
                            val created = onCreatePanel(name, previewIds)
                            if (created != null) {
                                // Switch to the new panel: the sample just
                                // saved is now the fixed set being run. The
                                // picker reloads so an earlier panel is not
                                // missing from the list.
                                type = TestType.FIXED_PANEL
                                variant = created.id.toString()
                                panels = null
                            }
                            showPanelDialog = false
                            panelName = ""
                        }
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPanelDialog = false
                    panelName = ""
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Deleting a panel throws away a set that cannot be rebuilt exactly once
    // the metrics have moved, so it asks first.
    panelToDeleteId?.let { panelId ->
        val panelName = panelToDeleteName
        fun clearPanelDelete() {
            panelToDeleteId = null
            panelToDeleteName = ""
        }
        AlertDialog(
            onDismissRequest = { clearPanelDelete() },
            title = { Text(stringResource(R.string.test_setup_panel_delete)) },
            text = { Text(stringResource(R.string.test_setup_panel_delete_confirm, panelName)) },
            confirmButton = {
                TextButton(onClick = {
                    clearPanelDelete()
                    panelScope.launch {
                        val result = runCatching { onDeletePanel(panelId) }
                        if (result.isSuccess) {
                            if (variant == panelId.toString()) variant = null
                            panels = rowsAfterDelete(result, panels.orEmpty(), { it.id }, panelId)
                        } else {
                            deleteErrorName = panelName
                        }
                    }
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearPanelDelete() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // The blueprint editor opens over the setup sheet (plan §9.1).
    if (showBlueprintEditor) {
        val editing = editingBlueprintId?.let { id ->
            blueprints?.firstOrNull { it.id == id }
        }
        BlueprintEditorSheet(
            paper = paper,
            blueprint = editing,
            initialSections = if (editing != null) blueprintViews.orEmpty() else emptyList(),
            testableCounts = testableCountsByCategory,
            requestedCount = requestCount,
            // The saveable edit id drives both the drafts and the edit/create
            // rendering; it does not blink to null while the list reloads.
            editingId = editingBlueprintId,
            onSave = { name, sections ->
                when (val target = blueprintSaveTarget(editingBlueprintId)) {
                    is BlueprintSaveTarget.Update -> {
                        onUpdateBlueprint(target.id, name, sections)
                        target.id
                    }
                    BlueprintSaveTarget.Create -> onCreateBlueprint(name, sections)
                }
            },
            onDelete = { id ->
                runCatching { onDeleteBlueprint(id) }.also { result ->
                    if (result.isSuccess) {
                        if (variant == id.toString()) variant = null
                        blueprints = rowsAfterDelete(result, blueprints.orEmpty(), { it.id }, id)
                    } else {
                        deleteErrorName = blueprints.orEmpty()
                            .firstOrNull { it.id == id }?.name.orEmpty()
                    }
                }
            },
            onDismiss = {
                showBlueprintEditor = false
                blueprintEditorEditing = false
            },
            onSaved = { id ->
                // Switch to the saved blueprint and reload both the picker and
                // the section views. Editing the blueprint already selected
                // leaves `variant` unchanged, so the views effect needs an
                // explicit refresh: without it the preview stays empty and
                // Start is permanently disabled with "cannot use a blueprint".
                variant = id.toString()
                blueprints = null
                blueprintViews = null
                blueprintRefresh++
            }
        )
    }

    // Deleting a blueprint asks first, like panels.
    blueprintToDeleteId?.let { blueprintId ->
        val blueprintName = blueprintToDeleteName
        fun clearBlueprintDelete() {
            blueprintToDeleteId = null
            blueprintToDeleteName = ""
        }
        AlertDialog(
            onDismissRequest = { clearBlueprintDelete() },
            title = { Text(stringResource(R.string.test_setup_blueprint_delete)) },
            text = {
                Text(
                    stringResource(
                        R.string.test_setup_blueprint_delete_confirm,
                        blueprintName
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clearBlueprintDelete()
                    panelScope.launch {
                        val result = runCatching { onDeleteBlueprint(blueprintId) }
                        if (result.isSuccess) {
                            if (variant == blueprintId.toString()) variant = null
                            blueprints = rowsAfterDelete(result, blueprints.orEmpty(), { it.id }, blueprintId)
                        } else {
                            deleteErrorName = blueprintName
                        }
                    }
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearBlueprintDelete() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // A refused delete keeps its row. Report it once, in the selected locale:
    // the old code discarded `runCatching`'s result and read failure as success.
    deleteErrorName?.let { failedName ->
        AlertDialog(
            onDismissRequest = { deleteErrorName = null },
            title = { Text(stringResource(R.string.error)) },
            text = {
                Text(
                    if (failedName.isBlank()) {
                        stringResource(R.string.r2_lib_delete_generic_failed)
                    } else {
                        stringResource(R.string.r2_lib_delete_failed, failedName)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteErrorName = null }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}

/**
 * The blueprint section tree the preview resolved: indented by depth, with
 * each node's granted questions, its pool, and its share of the run. A subtree
 * whose category was moved to another paper is struck through and marked
 * ignored. Quotas and percentages come from the same allocation the selector
 * runs, computed once at the root: re-allocating at each level with the full
 * length would show a child a larger quota than its parent actually granted.
 */
@Composable
private fun BlueprintPreviewRows(
    views: List<BlueprintSectionView>,
    count: Int,
    /** Actual selected counts from the preview; whole passages may exceed quotas. */
    actual: Map<String, Int>,
    depth: Int
) {
    val allocation = remember(views, count) {
        runCatching { BlueprintQuotas.allocate(views, count) }.getOrNull()
    }
    val shares = remember(views, allocation, actual) {
        if (actual.isNotEmpty()) {
            BlueprintQuotas.shares(views, actual)
        } else {
            allocation?.let { BlueprintQuotas.shares(views, it) }.orEmpty()
        }
    }
    // What each section asked for before its pool capped it. Only then can a
    // "short by" line mean anything: granted quotas never exceed their pool.
    val demand = remember(views, count) {
        runCatching { BlueprintQuotas.demand(views, count) }.getOrNull().orEmpty()
    }
    BlueprintPreviewNodeRows(views, shares, demand, depth)
}

@Composable
private fun BlueprintPreviewNodeRows(
    views: List<BlueprintSectionView>,
    shares: Map<String, BlueprintQuotas.RowShare>,
    demand: Map<String, Int>,
    depth: Int
) {
    for (view in views) {
        val share = shares[view.categoryId] ?: BlueprintQuotas.RowShare(0, view.pool, 0)
        val wanted = sectionQuota(view, demand)
        Column(modifier = Modifier.padding(start = (depth * 16).dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        R.string.test_setup_blueprint_section_row,
                        view.title,
                        share.quota,
                        share.pool,
                        share.percent
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (view.available) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = if (view.available) {
                        TextDecoration.None
                    } else {
                        TextDecoration.LineThrough
                    }
                )
                if (!view.available) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.test_setup_blueprint_section_ignored),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (view.children.isEmpty() && wanted > share.quota) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(
                            R.string.test_setup_blueprint_section_short,
                            wanted - share.quota
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            BlueprintPreviewNodeRows(view.children, shares, demand, depth + 1)
        }
    }
}

/** A node's quota: its terminal quota, or the sum of its children's. */
private fun sectionQuota(
    view: BlueprintSectionView,
    perTerminal: Map<String, Int>
): Int = if (view.children.isEmpty()) {
    perTerminal[view.categoryId] ?: 0
} else {
    view.children.sumOf { sectionQuota(it, perTerminal) }
}

/**
 * The plan form of a section-view tree: each terminal's scope is its paper
 * subtree, so the plan's category union is the blueprint's own scope. A view
 * whose category left the paper expands to nothing and drops out of the union.
 */
private fun List<BlueprintSectionView>.toPlanSections(
    categories: List<CategoryNode>
): List<BlueprintSection> = map { view ->
    BlueprintSection(
        categoryId = view.categoryId,
        title = view.title,
        parts = view.parts,
        children = view.children.toPlanSections(categories),
        scopeCategoryIds = if (view.children.isEmpty()) {
            CategorySelection.orderedIds(categories, listOf(view.categoryId))
        } else {
            emptyList()
        }
    )
}

private fun breadthLabel(breadth: Breadth): Int = when (breadth) {
    Breadth.NARROW -> R.string.test_setup_breadth_narrow
    Breadth.MEDIUM -> R.string.test_setup_breadth_medium
    Breadth.BROAD -> R.string.test_setup_breadth_broad
}

/**
 * Resource id for one recorded segment's short label. Pure: the four labels
 * that used to be mislabelled (two authored difficulties and the two blueprint
 * fills) are asserted in JVM tests without needing a Composition (L4).
 */
internal fun fillSourceLabelRes(source: String): Int = when (source.uppercase()) {
    "WEAKEST_TOPICS" -> R.string.test_setup_fill_weakest
    "TOUGHEST_QUESTIONS" -> R.string.test_setup_fill_toughest
    "EASIEST_QUESTIONS" -> R.string.test_setup_fill_easiest
    "TRICKY" -> R.string.test_setup_fill_tricky
    "AT_RISK" -> R.string.test_setup_fill_at_risk
    "UNSEEN" -> R.string.test_setup_fill_unseen
    "BALANCED" -> R.string.test_setup_fill_balanced
    "COVERAGE" -> R.string.test_setup_fill_coverage
    "COVERAGE_EXPOSURE" -> R.string.test_setup_fill_coverage_exposure
    "MIXED" -> R.string.test_setup_fill_mixed
    "BOOKMARKED" -> R.string.test_setup_fill_bookmarked
    "TAG" -> R.string.test_setup_fill_tag
    "AUTHORED_EASY" -> R.string.test_setup_fill_authored_easy
    "AUTHORED_HARD" -> R.string.r2_lib_fill_authored_hard
    "AUTHORED_MEDIUM" -> R.string.r2_lib_fill_authored_medium
    "BLUEPRINT" -> R.string.r2_lib_fill_blueprint
    "BLUEPRINT_FILL" -> R.string.r2_lib_fill_blueprint_fill
    "SLOWEST" -> R.string.test_setup_fill_slowest
    "PANEL" -> R.string.test_setup_fill_panel
    "LEAST_RECENTLY_REVIEWED" -> R.string.test_setup_fill_recent
    else -> R.string.test_setup_fill_random
}

/** One recorded segment's short label, e.g. `weakest` or `random`. */
@Composable
private fun fillSourceLabel(source: String): String = stringResource(fillSourceLabelRes(source))

/** `12 weakest + 8 random`, or an em dash when the selection is empty. */
@Composable
private fun compositionText(composition: Composition?): String {
    if (composition == null || composition.isEmpty) return "—"
    val text = StringBuilder()
    for (segment in composition.segments) {
        if (text.isNotEmpty()) text.append(" + ")
        text.append(segment.count)
        text.append(' ')
        text.append(fillSourceLabel(segment.source))
    }
    return text.toString()
}
