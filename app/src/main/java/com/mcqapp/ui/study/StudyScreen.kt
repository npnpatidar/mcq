package com.mcqapp.ui.study

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Dwell
import com.mcqapp.domain.Question
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.Study
import com.mcqapp.domain.StudyReason
import com.mcqapp.domain.StudyStepLabel
import com.mcqapp.domain.studyStepLabel
import com.mcqapp.domain.Scoring
import com.mcqapp.domain.StudyScopeMenuChoice
import com.mcqapp.domain.studyScopeMenuSelection
import com.mcqapp.domain.hasVisibleContent
import com.mcqapp.domain.isFlashcard
import com.mcqapp.ui.StudyViewModelFactory
import com.mcqapp.ui.common.LocalAnnouncer
import com.mcqapp.ui.haptics.rememberHaptics
import com.mcqapp.ui.importscreen.displayTitle
import com.mcqapp.ui.theme.GradePalette
import com.mcqapp.ui.theme.clockBandColors
import com.mcqapp.ui.theme.gradePaletteFor
import com.mcqapp.util.ContentElements
import com.mcqapp.util.QuestionImage
import com.mcqapp.R
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Study mode: one question at a time, show the answer, then grade it. The
 * four grades are the learner's own recall judgement, not a correctness
 * score — marks, negative marking and timers are deliberately absent here so
 * the loop stays honest and takes about ten seconds a card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(
    repository: McqRepository,
    paperId: String,
    navController: NavController,
    categoryIds: List<String> = emptyList(),
    leechesOnly: Boolean = false,
    tag: String = "",
    mistakes: Boolean = false
) {
    val context = LocalContext.current
    // LocalResources (not LocalContext.current.resources) keeps the spoken
    // "saved N wrong answers" count configuration-aware across locale changes.
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val viewModel: StudyViewModel = viewModel(
        key = "study-$paperId-${categoryIds.joinToString(",")}-$leechesOnly-$tag-$mistakes",
        factory = StudyViewModelFactory(
            context.applicationContext as Application,
            paperId,
            categoryIds,
            leechesOnly,
            tag,
            mistakes
        )
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val liveDwell by viewModel.liveDwell.collectAsStateWithLifecycle()
    val liveDwellMillis by viewModel.liveDwellMillis.collectAsStateWithLifecycle()
    val sessionSeconds by viewModel.sessionSeconds.collectAsStateWithLifecycle()

    // Failures already render inline (with retry) on the card/load state; keep
    // that Text and additionally speak it so screen readers are not left
    // guessing why grading or loading stalled.
    val announcer = LocalAnnouncer.current
    LaunchedEffect(state.loadError) {
        state.loadError?.let { announcer.announce(it) }
    }
    LaunchedEffect(state.gradeError) {
        state.gradeError?.let { announcer.announce(it) }
    }
    // The verdict card is read visually and through haptics, but neither is a
    // screen-reader channel. Speak a short localized verdict whenever a new
    // result lands (Check/reveal, and a changed grade). Keyed on the result
    // instance so repeating the same card still announces; clearing it on Next
    // stays silent.
    val lastResult = state.lastResult
    // Resolve the verdict string during composition with stringResource, which
    // is configuration-aware; the effect only speaks the resolved text.
    val verdictMessage = if (lastResult != null) {
        if (state.canAutoGrade) {
            stringResource(studyVerdictRes(lastResult.correct))
        } else {
            // A self-graded question has no key, so "Correct"/"Incorrect" would
            // be a claim the app cannot make. Speak the staged grade instead.
            stringResource(R.string.marked_x, stringResource(studyGradeLabelRes(lastResult.grade)))
        }
    } else {
        null
    }
    LaunchedEffect(lastResult) {
        verdictMessage?.let { announcer.announce(it) }
    }
    // Crossing the leech threshold used to be silent: speak the same one-shot
    // line the card shows. The flag lives on the served card, so a card that
    // stays flagged never repeats it.
    val leechNoticeMessage = stringResource(R.string.study_polish_leech_notice)
    LaunchedEffect(state.justFlaggedLeech) {
        if (state.justFlaggedLeech) announcer.announce(leechNoticeMessage)
    }
    // A card has no result card, so nothing above speaks when its answer
    // appears. Announce the revealed heading once per reveal instead, resolved
    // during composition for the same reason as the verdict. The key pairs the
    // card with the reveal flag, so each reveal re-announces and an unchanged
    // card does not repeat — and the saveable flag means a ROTATION (the same
    // ViewModel, its state still revealed) does not speak a reveal the learner
    // already heard. It cannot help across process death: this ViewModel has no
    // SavedStateHandle, so a fresh instance starts unrevealed and nothing would
    // be spoken anyway.
    //
    // A keyless multiple-choice question is self-graded but is not a card: its
    // heading is "Explanation", and no verdict exists, so nothing is announced
    // for it. `isFlashcard` stays the card label rule; `selfGraded` is what
    // suppresses the verdict.
    val answerHeadingMessage = stringResource(R.string.r2_study_answer_heading)
    val flashcardRevealed = state.revealed && state.isFlashcard
    var answeredRevealSpoken by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.current?.questionId, state.revealed) {
        if (!flashcardRevealed) {
            answeredRevealSpoken = false
        } else if (!answeredRevealSpoken) {
            answeredRevealSpoken = true
            announcer.announce(answerHeadingMessage)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onAppBackgrounded()
                Lifecycle.Event.ON_RESUME -> viewModel.onAppForegrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val showStudyHeader =
        !state.loading && state.loadError == null && !state.finished

    // Tag/mistake scope is entered from the header and applied by navigating to
    // a new route. Remembered so rotation cannot strand an open picker.
    var showScopeDialog by rememberSaveable { mutableStateOf(false) }
    fun scopeRoute(choice: StudyScopeMenuChoice, foldedTag: String = ""): String {
        val selection = studyScopeMenuSelection(categoryIds, choice, foldedTag)
        return com.mcqapp.ui.navigation.studyRoute(
            paperId,
            selection.categoryIds,
            leechesOnly = selection.leechesOnly,
            tag = selection.tag,
            mistakes = selection.mistakes
        )
    }

    // Keyboard shortcuts: Space reveal / advance, 1-4 grades, arrows move the
    // option selection. Focus is requested on the card surface and keys are
    // ignored while a dialog owns the screen or a modifier is held.
    val keyFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(showScopeDialog, state.showSuspendedDialog, state.finished) {
        if (!showScopeDialog && !state.showSuspendedDialog && !state.finished) {
            keyFocus.requestFocus()
        }
    }
    fun onStudyKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) return false
        if (event.nativeKeyEvent.isCtrlPressed ||
            event.nativeKeyEvent.isAltPressed ||
            event.nativeKeyEvent.isMetaPressed
        ) return false
        if (showScopeDialog || state.showSuspendedDialog || state.finished || state.onBreak) return false
        val code = event.nativeKeyEvent.keyCode
        val key = when (code) {
            android.view.KeyEvent.KEYCODE_SPACE -> StudyKey.SPACE
            android.view.KeyEvent.KEYCODE_1 -> StudyKey.ONE
            android.view.KeyEvent.KEYCODE_2 -> StudyKey.TWO
            android.view.KeyEvent.KEYCODE_3 -> StudyKey.THREE
            android.view.KeyEvent.KEYCODE_4 -> StudyKey.FOUR
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> StudyKey.LEFT
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> StudyKey.RIGHT
            else -> StudyKey.OTHER
        }
        val action = StudyKeyboard.action(
            key = key,
            revealed = state.revealed,
            staged = state.pendingGrade != null && !state.gradedAndStuck,
            canGrade = !state.grading && !state.onBreak && !state.gradedAndStuck,
            // Simplified's keyed card is answered by Check, which also stages
            // the grade; Space must not reveal it into an ungradeable card.
            // A self-graded card still uses Space as Show answer.
            revealAllowed = !state.simplified || state.selfGraded,
            onBreak = state.onBreak
        )
        when (action) {
            StudyKeyAction.NONE -> return false
            StudyKeyAction.REVEAL -> viewModel.reveal()
            StudyKeyAction.NEXT -> viewModel.next()
            StudyKeyAction.GRADE_AGAIN -> gradeByKey(viewModel, state, ReviewGrade.AGAIN)
            StudyKeyAction.GRADE_HARD -> gradeByKey(viewModel, state, ReviewGrade.HARD)
            StudyKeyAction.GRADE_GOOD -> gradeByKey(viewModel, state, ReviewGrade.GOOD)
            StudyKeyAction.GRADE_EASY -> gradeByKey(viewModel, state, ReviewGrade.EASY)
            StudyKeyAction.SELECT_PREVIOUS_OPTION,
            StudyKeyAction.SELECT_NEXT_OPTION -> selectOptionByArrow(
                viewModel, state, forward = action == StudyKeyAction.SELECT_NEXT_OPTION
            )
        }
        return true
    }

    // Back is a voluntary stop, not a silent exit: it ends the sitting the
    // same way "Done for now" does, so the tally and the restart option are
    // not skipped. Once the summary is up the handler stands down and back
    // leaves normally.
    BackHandler(enabled = showStudyHeader) { viewModel.stop() }

    Scaffold(
        topBar = {
            if (showStudyHeader) {
                StudyStickyHeader(
                    state = state,
                    liveDwell = liveDwell,
                    liveDwellMillis = liveDwellMillis,
                    sessionSeconds = sessionSeconds,
                    // The header's arrow is the same back gesture; both stop.
                    onBack = { viewModel.stop() },
                    onStop = { viewModel.stop() },
                    onDismissBreak = { viewModel.dismissBreak() },
                    onTakeBreak = { viewModel.takeBreak() },
                    onResumeBreak = { viewModel.resumeFromBreak() },
                    onToggleClock = { viewModel.toggleQuestionClock() },
                    onToggleBookmark = { viewModel.toggleBookmarkCurrent() },
                    onBury = { viewModel.buryCurrent() },
                    onSuspend = { viewModel.suspendCurrent() },
                    onOpenSuspended = { viewModel.openSuspendedDialog() },
                    onOpenScope = { showScopeDialog = true }
                )
            } else {
                BackOnlyBar(onBack = { navController.popBackStack() })
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .focusRequester(keyFocus)
                .focusable(true)
                .onKeyEvent(::onStudyKey)
                .testTag("study-keyboard-surface")
        ) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.loadError != null -> StudyLoadError(
                    message = state.loadError ?: "",
                    onRetry = { viewModel.retry() },
                    onDone = { navController.popBackStack() }
                )
                state.finished -> StudySummary(
                    state = state,
                    onRestart = { viewModel.restart() },
                    onEnableNewCards = { viewModel.enableNewCards() },
                    onOpenEditor = {
                        com.mcqapp.ui.editor.EditorSession.clear()
                        navController.navigate(
                            com.mcqapp.ui.navigation.editorRoute(
                                "", paperId, categoryIds.firstOrNull().orEmpty(), "new"
                            )
                        )
                    },
                    onDone = { navController.popBackStack() }
                )
                // The body owns the scroll position and the card's viewport-
                // visibility flag. Without this key a long card scrolled to its
                // answer handed its scroll offset AND its stale "not visible"
                // report to the next card, which then started with its clock
                // paused. A new card gets a new body: scroll at the top and a
                // fresh visibility report before the clock can be charged.
                else -> key(state.current?.questionId) {
                    StudyBody(
                        state = state,
                        viewModel = viewModel
                    )
                }
            }
            // The unsuspend route. A suspended card never reaches the pull, so
            // this dialog is the only way back into the pool from the UI.
            if (state.showSuspendedDialog) {
                SuspendedCardsDialog(
                    cards = state.suspendedCards,
                    onUnsuspend = { viewModel.unsuspend(it) },
                    onDismiss = { viewModel.closeSuspendedDialog() }
                )
            }
            if (showScopeDialog) {
                StudyScopeDialog(
                    paperId = paperId,
                    categoryIds = categoryIds,
                    currentTag = tag,
                    currentMistakes = mistakes,
                    loadTags = { pid, cats -> repository.tagCountsForStudyScope(pid, cats) },
                    // Switching scope pushes a new study route, so the old
                    // screen's ViewModel stays alive in the back stack. End the
                    // sitting first, exactly as Back and "Done for now" do:
                    // otherwise its ticker keeps running and a later pop writes
                    // an inflated study_sessions row for a sitting that ended
                    // here. stop() banks both clocks and writes the row once.
                    onPickWhole = {
                        showScopeDialog = false
                        viewModel.stop()
                        navController.navigate(scopeRoute(StudyScopeMenuChoice.WHOLE))
                    },
                    onPickTag = { folded ->
                        showScopeDialog = false
                        viewModel.stop()
                        navController.navigate(scopeRoute(StudyScopeMenuChoice.TAG, folded))
                    },
                    onPickMistakes = {
                        showScopeDialog = false
                        viewModel.stop()
                        navController.navigate(scopeRoute(StudyScopeMenuChoice.MISTAKES))
                    },
                    onSaveMistakes = {
                        scope.launch {
                            // One transaction for the whole pool (`addTagToQuestions`),
                            // not a write per question: a crash used to leave half
                            // the pool tagged. A failure says so instead of
                            // announcing a success the bank does not hold.
                            try {
                                val wrong = repository.getMistakenQuestions(paperId)
                                repository.addTagToQuestions(
                                    wrong.map { it.id },
                                    com.mcqapp.ui.library.LibraryViewModel.MISTAKES_POOL_TAG
                                )
                                announcer.announce(
                                    resources.getString(R.string.of_mistakes_pool_saved, wrong.size)
                                )
                            } catch (e: Exception) {
                                com.mcqapp.util.Logger.e("STUDY", "Saving the mistakes pool failed", e)
                                announcer.announce(resources.getString(R.string.of_mistakes_pool_failed))
                            }
                        }
                    },
                    onDismiss = { showScopeDialog = false }
                )
            }
        }
    }
}

/**
 * Space/1-4 grading honours the app's optional confirm-before-next setting in
 * Classic only. Simplified ignores the toggle: Check already staged its auto
 * grade, so a digit edits that proposal through [StudyViewModel.changeGrade]
 * and Next commits it — preserving the app's proposal, the guess flag and the
 * AUTO/EDITED provenance. A Simplified self-graded card has no Check and no
 * proposal, so its digits still grade in one tap.
 */
private fun gradeByKey(viewModel: StudyViewModel, state: StudyUiState, grade: ReviewGrade) {
    when (
        StudyKeyboard.digitGradeAction(
            simplified = state.simplified,
            confirmGradeBeforeNext = state.confirmGradeBeforeNext,
            selfGraded = state.selfGraded
        )
    ) {
        StudyDigitGrade.STAGE -> viewModel.stageGrade(grade)
        StudyDigitGrade.CHANGE -> viewModel.changeGrade(grade)
        StudyDigitGrade.GRADE -> viewModel.grade(grade)
    }
}

/** Left/Right walk the answer options, keeping at most one selected. */
private fun selectOptionByArrow(
    viewModel: StudyViewModel,
    state: StudyUiState,
    forward: Boolean
) {
    val options = state.question?.options.orEmpty()
    if (options.isEmpty()) return
    val current = options.indexOfFirst { it.id in state.selection }
    val next = if (current < 0) {
        if (forward) 0 else options.lastIndex
    } else {
        (current + if (forward) 1 else -1 + options.size) % options.size
    }
    if (current >= 0 && current != next) viewModel.toggleOption(options[current].id)
    viewModel.toggleOption(options[next].id)
}

@Composable
private fun StudyBody(
    state: StudyUiState,
    viewModel: StudyViewModel
) {
    val question = state.question ?: return
    // Keyed to the card, not to the composition's lifetime: with the call site
    // keyed on the question id this is already a fresh state per card, and the
    // explicit key keeps a future unkeyed caller from carrying scroll position
    // into the next question. Scroll offset 0 means the card is in the viewport
    // when its visibility is first reported, so its clock starts running.
    val scrollState = remember(question.id) { ScrollState(0) }
    // The dwell clock only charges while the question card is actually in the
    // viewport: scrolling a long passage — or, after reveal, the answer — past
    // the card pauses it, the same rule the test screen applies to dialogs.
    var containerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var cardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var questionOnScreen by remember { mutableStateOf(true) }
    fun reportVisibility() {
        val card = cardCoords
        val container = containerCoords
        if (card == null || container == null || !card.isAttached || !container.isAttached) return
        val top = card.localPositionOf(container, Offset.Zero).y
        questionOnScreen = top < container.size.height && top + card.size.height > 0f
    }
    val cardPositioned = Modifier.onGloballyPositioned { cardCoords = it; reportVisibility() }
    LaunchedEffect(questionOnScreen) {
        viewModel.setQuestionVisible(questionOnScreen)
    }
    if (state.simplified) {
        // Like test mode: the question scrolls, but Check, Guess and Next live
        // in a fixed footer so they are always tappable without scrolling.
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .onGloballyPositioned { containerCoords = it; reportVisibility() }
                    .testTag("study-question-scroll")
                    .padding(16.dp)
            ) {
                Spacer(Modifier.height(12.dp))
                if (state.showGradingHint) {
                    StudyGradingHintCard(onDismiss = { viewModel.dismissGradingHint() })
                    Spacer(Modifier.height(12.dp))
                }
                LeechNoticeBlock(show = state.justFlaggedLeech)
                QuestionCard(
                    question = question,
                    selection = state.currentSelection,
                    revealed = state.revealed,
                    reason = state.current?.reason,
                    isLeech = state.current?.state?.leech == true,
                    stepLabel = state.current?.state?.studyStepLabel(),
                    onToggleOption = { viewModel.toggleOption(it) },
                    passage = state.passage,
                    modifier = cardPositioned
                )
                Spacer(Modifier.height(12.dp))
                if (state.revealed && !state.selfGraded) {
                    // Simplified result card, as before.
                    SimplifiedResultBlock(state = state)
                } else if (state.confirmGradeBeforeNext && state.pendingGrade != null) {
                    // Opt-in self-graded flow: the staged grade gets a neutral
                    // card (no correctness claim) above Change grade / Next.
                    StagedResultBlock(state = state)
                }
            }
            SimplifiedFooter(state = state, viewModel = viewModel)
        }
    } else {
        val haptics = rememberHaptics()
        // Same fixed-footer shape as Simplified: the answer and the four
        // buttons never scroll away either.
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .onGloballyPositioned { containerCoords = it; reportVisibility() }
                    .testTag("study-question-scroll")
                    .padding(16.dp)
            ) {
                Spacer(Modifier.height(12.dp))
                if (state.showGradingHint) {
                    StudyGradingHintCard(onDismiss = { viewModel.dismissGradingHint() })
                    Spacer(Modifier.height(12.dp))
                }
                LeechNoticeBlock(show = state.justFlaggedLeech)
                QuestionCard(
                    question = question,
                    selection = state.currentSelection,
                    revealed = state.revealed,
                    reason = state.current?.reason,
                    isLeech = state.current?.state?.leech == true,
                    stepLabel = state.current?.state?.studyStepLabel(),
                    onToggleOption = { viewModel.toggleOption(it) },
                    passage = state.passage,
                    modifier = cardPositioned
                )
                if (state.confirmGradeBeforeNext && state.pendingGrade != null) {
                    // Opt-in Classic flow: the staged grade and its interval sit
                    // with the answer, then Change grade / Next in the footer.
                    StagedResultBlock(state = state)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                GradeError(state.gradeError)
                GradeError(state.cardActionError)
                if (state.gradedAndStuck) {
                    // The grade is already stored; re-grading would double it.
                    // Only the next card is missing (consolidated_bugs.md I3).
                    Button(
                        onClick = { viewModel.loadNext() },
                        enabled = !state.grading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.retry))
                    }
                } else if (!state.revealed) {
                    Button(
                        onClick = {
                            haptics.tick()
                            viewModel.reveal()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.onBreak
                    ) {
                        Text(stringResource(R.string.show_answer))
                    }
                } else if (state.pendingGrade != null) {
                    // Opt-in Classic flow: the grade is on screen but not yet
                    // written. Same footer controls Simplified has always used.
                    StagedResultActions(state = state, viewModel = viewModel)
                } else {
                    // Default: a self-reported grade is persisted and the next
                    // card pulled in one tap, exactly as before.
                    GradeButtons(
                        enabled = !state.grading && !state.onBreak,
                        previews = state.previews,
                        onGrade = {
                            if (state.confirmGradeBeforeNext) viewModel.stageGrade(it)
                            else viewModel.grade(it)
                        }
                    )
                }
            }
        }
    }
}

/**
 * The one-time grading disclosure. Sits inline above the question in the
 * scrollable area — a dismissible note, never a dialog, so the card stays
 * answerable the whole time. Its visibility is decided entirely by
 * [StudyUiState.showGradingHint]; the copy lives in its own strings file.
 */
@Composable
private fun StudyGradingHintCard(onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("study_grading_hint"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.study_grading_hint_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.study_grading_hint_message),
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.study_grading_hint_dismiss))
            }
        }
    }
}

/**
 * The result of a Simplified check. Lives in the scrollable content: it is
 * read, not tapped to proceed. The change-grade control itself sticks to the
 * footer beside Next, so correcting a misclick never requires scrolling.
 */
@Composable
private fun SimplifiedResultBlock(state: StudyUiState) {
    val result = state.lastResult ?: return
    SimplifiedResultCard(result = result)
}

/**
 * The staged result, shared by all three modes. A question the app can grade
 * gets the verdict card; a self-graded one gets the same grade + interval
 * without any correctness claim. Both are read in the scrollable answer area.
 */
@Composable
private fun StagedResultBlock(state: StudyUiState) {
    val result = state.lastResult ?: return
    if (state.selfGraded) {
        SelfGradedResultCard(result = result)
    } else {
        SimplifiedResultCard(result = result)
    }
}

/** A self-graded result: what the learner chose and when it comes back. */
@Composable
private fun SelfGradedResultCard(result: com.mcqapp.ui.study.StudyResult) {
    val gradeLabel = stringResource(studyGradeLabelRes(result.grade))
    Card(modifier = Modifier.fillMaxWidth().testTag("study-result")) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.marked_x, gradeLabel),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.study_next_in, result.nextIn),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The last-chance correction controls, shared by Simplified and by the opt-in
 * Classic/self-graded flows. Change grade edits the staged proposal; Next is
 * the only path that writes it. The controls stand down during a grade write.
 */
@Composable
private fun StagedResultActions(state: StudyUiState, viewModel: StudyViewModel) {
    val haptics = rememberHaptics()
    val question = state.question
    var showPicker by remember(question?.id) { mutableStateOf(false) }
    if (showPicker) {
        GradeButtons(
            enabled = !state.grading,
            previews = state.previews,
            onGrade = {
                viewModel.changeGrade(it)
                showPicker = false
            }
        )
        Spacer(Modifier.height(8.dp))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = { showPicker = !showPicker },
            // The picker itself is gated on !state.grading; the toggle
            // must stand down in that same frame, or a tap can rewrite
            // pendingGrade after Next captured it (S4).
            enabled = !state.grading,
            modifier = Modifier.testTag("study-change-grade")
        ) {
            Text(stringResource(R.string.change_grade))
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                haptics.tick()
                viewModel.next()
            },
            modifier = Modifier.weight(1f).testTag("study-next"),
            // The card is graded and stuck: re-tapping Next would
            // double-persist it (consolidated_bugs.md I3).
            enabled = !state.grading && !state.gradedAndStuck && !state.onBreak
        ) {
            Text(stringResource(R.string.next))
        }
    }
}

/**
 * The always-visible Simplified actions, mirroring test mode's fixed footer:
 * Guess + Check before reveal, Next after. None of these ever scrolls away.
 *
 * A self-graded question — a card, or a multiple-choice question with no key —
 * takes the same footer shape but a shorter path: no Guess switch (there is
 * nothing the app could check), Show answer before the reveal, then the four
 * self-grading buttons that grade and advance in one tap. It has no Check, no
 * Next and no result card — the app has no verdict to give.
 */
@Composable
private fun SimplifiedFooter(state: StudyUiState, viewModel: StudyViewModel) {
    val haptics = rememberHaptics()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        GradeError(state.gradeError)
        GradeError(state.cardActionError)
        if (state.gradedAndStuck) {
            // The grade is already stored, so re-tapping Next would double-persist
            // it. Only the next card is missing — offer the same recovery the
            // classic footer has (consolidated_bugs.md I3). Checked first so the
            // retry stays reachable for a self-graded question too: it has no
            // Next/Show answer left to offer once the write landed.
            Button(
                onClick = { viewModel.loadNext() },
                enabled = !state.grading,
                modifier = Modifier.fillMaxWidth().testTag("study-retry")
            ) {
                Text(stringResource(R.string.retry))
            }
        } else if (state.selfGraded) {
            if (!state.revealed) {
                Button(
                    onClick = {
                        haptics.tick()
                        viewModel.reveal()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("study-show-answer"),
                    enabled = !state.onBreak
                ) {
                    Text(stringResource(R.string.show_answer))
                }
            } else {
                // Simplified self-graded: one prompt, one answer. Simplified
                // already offers Change grade on its result card, so the
                // confirm-before-next toggle (Classic only) must never stage
                // here — staging would run the simplified commit path and
                // fabricate correctness for a card that has no answer key.
                GradeButtons(
                    enabled = !state.grading && !state.onBreak,
                    previews = state.previews,
                    onGrade = { viewModel.grade(it) }
                )
            }
        } else if (!state.revealed) {
            // Pre-commit: once the answer is visible a guess declaration would be
            // retroactive, so the toggle lives only on the unanswered card.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Casino,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.guessing))
                    Text(
                        stringResource(R.string.guessing_this_is_a_guess),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = state.isGuess,
                    onCheckedChange = { viewModel.setGuess(it) },
                    enabled = !state.onBreak,
                    modifier = Modifier.testTag("study-guess")
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    // The verdict is the whole point of Check, so it gets the
                    // outcome pulse; the tick still confirms the tap first.
                    val question = state.question
                    val selection = state.selection
                    haptics.tick()
                    viewModel.check()
                    if (question != null && selection.isNotEmpty()) {
                        if (Scoring.isCorrect(selection, question.correctOptionIds)) {
                            haptics.confirm()
                        } else {
                            haptics.reject()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("study-check"),
                enabled = !state.onBreak
            ) {
                Text(stringResource(R.string.check))
            }
        } else {
            // One staged-actions composable for all three modes: Simplified
            // always, and Classic/self-graded when the learner opted in.
            StagedResultActions(state = state, viewModel = viewModel)
        }
    }
}

/** A failed grade write stays on the card with a retryable button. */
@Composable
private fun GradeError(message: String?) {
    if (message == null) return
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SimplifiedResultCard(result: com.mcqapp.ui.study.StudyResult) {
    Card(modifier = Modifier.fillMaxWidth().testTag("study-result")) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                (if (result.correct) "✓ " else "✗ ") +
                    stringResource(
                        if (result.correct) R.string.correct else R.string.incorrect
                    ),
                style = MaterialTheme.typography.titleSmall,
                color = if (result.correct) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(4.dp))
            // One Text per branch: the string-usage test requires each
            // stringResource to render directly inside a Text.
            //
            // The line follows the grade Next will persist, not the grade Check
            // proposed. Change grade rewrites the proposal, and a line still
            // claiming "marked Again" contradicted the "Next in ..." interval
            // under it and the review that was actually written.
            // The enum name is a storage token; the label must come from a
            // resource so a Hindi learner sees and hears the translated grade.
            val gradeLabel = stringResource(studyGradeLabelRes(result.grade))
            when {
                !result.correct && result.grade == ReviewGrade.AGAIN -> Text(
                    stringResource(R.string.incorrect_answer_review_soon),
                    style = MaterialTheme.typography.bodySmall
                )
                !result.correct -> Text(
                    stringResource(R.string.marked_x, gradeLabel),
                    style = MaterialTheme.typography.bodySmall
                )
                result.wasGuess && result.grade == ReviewGrade.AGAIN -> Text(
                    stringResource(R.string.guessed_right_marked_again),
                    style = MaterialTheme.typography.bodySmall
                )
                result.wasGuess -> Text(
                    stringResource(R.string.guessed_right_marked_x, gradeLabel),
                    style = MaterialTheme.typography.bodySmall
                )
                else -> Text(
                    stringResource(R.string.answered_in_xs, result.dwellSeconds) + " — " +
                        gradeLabel,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                stringResource(R.string.study_next_in, result.nextIn),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackOnlyBar(onBack: () -> Unit) {
    TopAppBar(
        title = {},
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
        }
    )
}

@Composable
private fun StudyStickyHeader(
    state: StudyUiState,
    liveDwell: Long,
    /**
     * The untruncated reading the tint is measured from: a band boundary falls
     * on the same half-second the check's rounding uses.
     */
    liveDwellMillis: Long,
    sessionSeconds: Long,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onDismissBreak: () -> Unit,
    onTakeBreak: () -> Unit,
    onResumeBreak: () -> Unit,
    onToggleClock: () -> Unit,
    onToggleBookmark: () -> Unit,
    onBury: () -> Unit,
    onSuspend: () -> Unit,
    onOpenSuspended: () -> Unit,
    onOpenScope: () -> Unit
) {
    // `semantics {}` is not composable, so resolve its accessibility strings
    // during composition with stringResource and capture the plain Strings;
    // the clock text itself renders via stringResource inside its Text.
    val clockContentDescription = stringResource(R.string.question_clock)
    val clockStateDescription = stringResource(
        if (state.clockRunning) R.string.clock_running else R.string.clock_paused
    )
    val clockPausedLabel = stringResource(R.string.clock_paused)
    val haptics = rememberHaptics()
    val currentBookmarked =
        state.question?.id?.let { it in state.bookmarked } == true
    var cardMenuExpanded by remember { mutableStateOf(false) }
    // The question clock doubles as a dwell gauge: green while the card is in
    // the Easy window, amber through Good, red once it reaches Hard. Thresholds
    // are the absolute cuts from the same rule the check grades with, and the
    // time itself stays the primary signal. Under correctness-only grading the
    // dwell is still recorded for later analysis, but it does not decide the
    // grade, so the clock keeps the neutral tint instead of implying a band.
    // The tints come from the active theme and are contrast-checked against its
    // surface, so the clock stays legible in light, dark and every palette.
    val clockColors = clockBandColors()
    val dwellBand = if (state.correctnessOnlyGrading || state.selfGraded) {
        // Correctness-only grading records dwell but does not let it decide the
        // grade, so the clock makes no band claim at all. A self-graded question
        // is graded by the learner from the answer, so the clock never decided
        // its grade either — the same neutral band, never an implied
        // Easy/Good/Hard.
        Study.DwellBand.NEUTRAL
    } else {
        Study.dwellBand(
            Study.gradedDwellSeconds(liveDwellMillis),
            state.dwellEasySeconds,
            state.dwellHardSeconds
        )
    }
    val clockColor = when (dwellBand) {
        // The first second has banked no time, so it makes no colour claim.
        Study.DwellBand.NEUTRAL -> MaterialTheme.colorScheme.onSurface
        Study.DwellBand.EASY -> clockColors.easy
        Study.DwellBand.GOOD -> clockColors.good
        Study.DwellBand.HARD -> clockColors.hard
    }
    // Colour was the only band signal and the pause control had no verb: give
    // both a non-colour, localized cue. The visible text still carries the
    // "Paused" suffix the UI test looks for.
    val bandLabel = stringResource(studyDwellBandRes(dwellBand))
    val clockVerb = stringResource(
        if (state.clockRunning) R.string.r2_study_pause else R.string.r2_study_resume
    )

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        // Custom top-bar content: TopAppBar's built-in status-bar inset does
        // not apply, so pad explicitly or the title collides with the clock.
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        // The stored title may be the importer's fallback
                        // token ("Imported Questions"); resolve it for display
                        // in the active locale, exactly as the import screen
                        // and the library card do.
                        displayTitle(state.paperTitle),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("study-paper-title")
                    )
                    // Copy lives here, not in the ViewModel: a unit test runs
                    // without app resources (C19).
                    val scopeText = when (val label = state.scopeLabel) {
                        ScopeLabel.WholePaper -> ""
                        is ScopeLabel.One -> label.title
                        is ScopeLabel.Many ->
                            pluralStringResource(R.plurals.study_scope_many, label.count, label.count)
                        is ScopeLabel.Text -> stringResource(label.resId, label.arg)
                    }
                    if (scopeText.isNotEmpty()) {
                        Text(
                            scopeText,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("study-scope-label")
                        )
                    }
                    if (state.suspendedCount > 0) {
                        Text(
                            stringResource(R.string.suspend_indicator_count, state.suspendedCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            modifier = Modifier.testTag("study-suspended-indicator")
                        )
                    }
                }
                // The scope picker lives in the same overflow as the card
                // actions. A fifth top-bar icon squeezed the paper title to
                // zero width on a 320 dp phone (the header keeps back, actions,
                // bookmark and Done), so the session-level choice moved behind
                // the icon that already holds session-level actions.
                // Card actions live behind one overflow so the header keeps its
                // bookmark and Done controls. Suspend and bury act on the card
                // on screen without writing a grade; the last item opens the
                // unsuspend route, without which a hidden card is stranded.
                Box {
                    IconButton(
                        onClick = { cardMenuExpanded = true },
                        // Enabled even with no card: the menu's first item is
                        // the study scope, which must stay reachable from an
                        // empty sitting. The card items gate themselves on
                        // studyCanActOnCard below.
                        modifier = Modifier.testTag("study-card-actions")
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.suspend_card_actions)
                        )
                    }
                    DropdownMenu(
                        expanded = cardMenuExpanded,
                        onDismissRequest = { cardMenuExpanded = false },
                        modifier = Modifier.testTag("study-card-actions-menu")
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.of_study_scope)) },
                            onClick = {
                                cardMenuExpanded = false
                                onOpenScope()
                            },
                            modifier = Modifier.testTag("study-scope")
                        )
                        // A card action hides this card and pulls the next, so
                        // it must stand down while a grade is in flight or only
                        // staged — otherwise a tap loses the learner's decision.
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.suspend_action_bury)) },
                            onClick = {
                                cardMenuExpanded = false
                                onBury()
                            },
                            enabled = studyCanActOnCard(state)
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.suspend_action_suspend)) },
                            onClick = {
                                cardMenuExpanded = false
                                onSuspend()
                            },
                            enabled = studyCanActOnCard(state)
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.suspend_action_open_list)) },
                            onClick = {
                                cardMenuExpanded = false
                                onOpenSuspended()
                            }
                        )
                    }
                }
                // The bookmark follows the card on screen: the same toggle as
                // Test mode's, so a question saved here shows up there and on
                // the bookmarks screen without leaving the session.
                IconButton(
                    onClick = {
                        haptics.toggle(!currentBookmarked)
                        onToggleBookmark()
                    },
                    enabled = state.question != null,
                    modifier = Modifier.testTag("study-bookmark")
                ) {
                    Icon(
                        if (currentBookmarked) Icons.Default.Bookmark
                        else Icons.Default.BookmarkBorder,
                        contentDescription = stringResource(R.string.bookmark_question),
                        tint = if (currentBookmarked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                TextButton(
                    onClick = onStop,
                    modifier = Modifier.testTag("study-done")
                ) {
                    Text(stringResource(R.string.done_for_now))
                }
            }
            // Stats block: two columns of two matching lines. The left column
            // carries the session tally over the sitting's minutes; the right
            // one is the clock, its permanent time over its variable line. Both
            // sides use labelMedium and identical line heights, so line 1
            // (tally / time) and line 2 (minutes / verb + band) share a
            // baseline instead of sitting on different visual lines. The
            // clickable right column is the 48 dp tap target and holds both of
            // the clock lines; the left column is centred against it, so the
            // block stays one 48 dp row rather than two and the question keeps
            // the reclaimed height. Both lines always render, so flipping the
            // clock never changes the header's height.
            val sessionMinutes = (sessionSeconds / 60L).toInt()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    // Stable anchor for the layout-height test: this row is not
                    // clickable, so its height is the real layout rather than
                    // Compose's automatic touch-target padding.
                    .testTag("study-stats")
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.reviewed_this_session, state.reviewed),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            // The timer icon makes the clock's first line 20 dp
                            // tall. Reserve the same height here and centre the
                            // text in it, or the second lines start 4 dp apart.
                            .heightIn(min = 20.dp)
                            .wrapContentHeight(Alignment.CenterVertically)
                            .testTag("study-reviewed")
                    )
                    Text(
                        pluralStringResource(
                            R.plurals.session_elapsed_minutes, sessionMinutes, sessionMinutes
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("study-session-minutes")
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    // 48 dp is the touch target, enforced explicitly: the
                    // Material helper is a no-op in this header, which left the
                    // node at its 36 dp content height. The two lines are
                    // centred inside the block, so the left column (centred
                    // against it by the parent Row) keeps both lines aligned.
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onToggleClock, enabled = !state.revealed && !state.onBreak)
                        .testTag("study-question-clock")
                        .semantics {
                            contentDescription = clockContentDescription
                            stateDescription = clockStateDescription + ", $bandLabel"
                        }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Timer,
                            contentDescription = null,
                            tint = clockColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            Dwell.format(liveDwell),
                            color = clockColor,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            modifier = Modifier.testTag("study-clock-time")
                        )
                    }
                    Text(
                        if (state.clockRunning) {
                            stringResource(R.string.r2_study_clock_state, clockVerb, bandLabel)
                        } else {
                            stringResource(
                                R.string.r2_study_clock_state_paused,
                                clockVerb, bandLabel, clockPausedLabel
                            )
                        },
                        color = clockColor,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("study-clock-state")
                    )
                }
            }
            if (state.onBreak) {
                // An explicit break is not the end of the sitting: both clocks
                // are banked and the only way forward is Resume. Showing Dismiss
                // here would strand the learner with a frozen session.
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.study_polish_on_break),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("study-on-break")
                    )
                    TextButton(
                        onClick = onResumeBreak,
                        modifier = Modifier.testTag("study-resume-break")
                    ) {
                        Text(stringResource(R.string.study_polish_resume))
                    }
                }
            } else if (state.showBreakStrip) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.break_suggestion, state.reviewed, state.reviewed),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismissBreak) {
                        Text(stringResource(R.string.dismiss))
                    }
                    TextButton(
                        onClick = onTakeBreak,
                        modifier = Modifier.testTag("study-take-break")
                    ) {
                        Text(stringResource(R.string.study_polish_take_break))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(
    question: Question,
    selection: Set<String>,
    revealed: Boolean,
    reason: StudyReason?,
    /** The card's own flag: a leech wears the chip in the main session too. */
    isLeech: Boolean = false,
    /** First-exposure step vs a lapse recovery, from the card's FSRS state. */
    stepLabel: StudyStepLabel? = null,
    onToggleOption: (String) -> Unit,
    passage: com.mcqapp.domain.Passage? = null,
    modifier: Modifier = Modifier
) {
    val haptics = rememberHaptics()
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Shared context: the passage renders above every member whenever
            // it comes due — no queue grouping, the schedule stays per question.
            if (passage != null) {
                Text(
                    passage.title.ifBlank { stringResource(R.string.passage) },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(2.dp))
                ContentElements(
                    passage.elements,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                QuestionImage(
                    src = passage.image,
                    contentDescription = stringResource(R.string.passage_image),
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(8.dp))
            }
            // Why this card was pulled, plus the card's own flag. A leech in the
            // main session wears the same chip as one in the drill: the flag is
            // a fact about the card, not about which surface served it.
            if (isLeech || reason == StudyReason.LEECH) {
                LeeChip()
                Spacer(Modifier.height(6.dp))
            }
            when (reason) {
                StudyReason.LEECH -> {}
                StudyReason.NEW -> {
                    Text(
                        stringResource(R.string.new_question),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.height(6.dp))
                }
                StudyReason.LOW_RECALL_RISK -> {
                    Text(
                        stringResource(R.string.likely_to_be_forgotten_soon),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(6.dp))
                }
                StudyReason.LEARNING -> {
                    // Both are short-term steps served by the same pull, but a
                    // lapsed card is relearning and must not claim it is seeing
                    // this material for the first time.
                    Text(
                        stringResource(studyStepLabelRes(stepLabel)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(6.dp))
                }
                StudyReason.DUE, null -> {}
            }
            // Bumped a step: the stem is the primary reading text and was set
            // small relative to the option markers around it.
            ContentElements(question.elements, textStyle = MaterialTheme.typography.bodyLarge)
            QuestionImage(
                src = question.image,
                contentDescription = stringResource(R.string.question_image),
                modifier = Modifier.padding(top = 4.dp)
            )
            // With no key there is nothing to mark right or wrong: a keyless
            // question reveals its explanation without colouring any option,
            // and its markers stay selection markers rather than verdicts.
            val keyKnown = question.correctOptionIds.isNotEmpty()
            question.options.forEach { option ->
                val isCorrect = option.id in question.correctOptionIds
                val isSelected = option.id in selection
                val verdict = revealed && keyKnown
                val container = when {
                    verdict && isCorrect -> MaterialTheme.colorScheme.primaryContainer
                    verdict && isSelected -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surface
                }
                // A multi-correct question can take several picks, so its
                // marker is a square — the same circle/square distinction the
                // test screen draws with a radio button vs a checkbox.
                val multi = question.isMultiCorrect
                val mark = when {
                    verdict && isCorrect -> "✓"
                    verdict && isSelected -> "✗"
                    isSelected && multi -> "▣"
                    // Filled circle, not a bullet: "•" renders visibly smaller
                    // than "○" at the same size, so selecting shrank the mark.
                    isSelected -> "●"
                    multi -> "▢"
                    else -> "○"
                }
                // Announced as a checkbox with a localized state description:
                // the glyph alone left TalkBack reading only "✓ button", and
                // English literals stayed English under the Hindi locale (S2).
                // A keyless reveal never says "wrong": the app cannot know.
                val optionState = stringResource(
                    studyOptionStateRes(revealed, isCorrect, isSelected, keyKnown)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // The content height of a one-line option was under
                        // 48 dp: a short label left a tap target too small and
                        // the rows crowded together. Reserve the target and a
                        // little more breathing room; the tag is unchanged.
                        .padding(top = 8.dp)
                        .heightIn(min = 48.dp)
                        .background(container, RoundedCornerShape(8.dp))
                        .testTag("study-option-" + option.id)
                        .selectable(
                            selected = isSelected,
                            enabled = !revealed,
                            role = Role.Checkbox,
                        ) {
                            haptics.toggle(!isSelected)
                            onToggleOption(option.id)
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .semantics { stateDescription = optionState },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OptionMark(
                        mark = mark,
                        color = if (verdict && isCorrect) MaterialTheme.colorScheme.primary
                        else if (verdict && isSelected) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        ContentElements(option.elements, textStyle = MaterialTheme.typography.bodyLarge)
                        QuestionImage(src = option.image, contentDescription = stringResource(R.string.image_for_this_option))
                    }
                }
            }
            if (
                revealed &&
                (
                    question.explanationElements.hasVisibleContent ||
                    !question.explanationImage.isNullOrBlank()
                )
            ) {
                Spacer(Modifier.height(8.dp))
                // A flashcard's back is the answer, not an explanation: label it
                // for what it is and give screen readers a heading to jump to.
                // The MCQ card keeps its existing heading and copy.
                Text(
                    stringResource(
                        if (question.isFlashcard) R.string.r2_study_answer_heading
                        else R.string.explanation
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = if (question.isFlashcard) {
                        Modifier.semantics { heading() }
                    } else {
                        Modifier
                    }
                )
                ContentElements(question.explanationElements, textStyle = MaterialTheme.typography.bodyLarge)
                QuestionImage(
                    src = question.explanationImage,
                    contentDescription = stringResource(
                        if (question.isFlashcard) R.string.r2_study_answer_image
                        else R.string.explanation_image
                    )
                )
            }
        }
    }
}

/**
 * The one-shot "Now flagged as tricky" line. It sits above the card it is
 * attached to, disappears on the next pull, and is never a claim about that
 * next card — it reports the grade that just crossed the threshold.
 */
@Composable
private fun LeechNoticeBlock(show: Boolean) {
    if (!show) return
    Spacer(Modifier.height(8.dp))
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("study-leech-notice")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.height(16.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.study_polish_leech_notice),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/**
 * The shared option marker: one glyph slot and one typography for every Study
 * option row. Internal so another surface can reuse the same mark later
 * (Results uses the same glyph vocabulary and should adopt this component in a
 * follow-up batch rather than drawing a second, drifting set).
 */
@Composable
internal fun OptionMark(mark: String, color: Color) {
    Text(
        mark,
        modifier = Modifier.width(32.dp),
        style = MaterialTheme.typography.titleLarge,
        color = color
    )
}

@Composable
private fun LeeChip() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.height(14.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.you_keep_missing_this_one),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun GradeButtons(
    enabled: Boolean,
    previews: Map<ReviewGrade, String>,
    onGrade: (ReviewGrade) -> Unit
) {
    Column {
        Text(
            stringResource(R.string.how_well_did_you_recall_it),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GradeButtonFor(
                ReviewGrade.AGAIN,
                enabled, previews[ReviewGrade.AGAIN], Modifier.weight(1f), onGrade
            )
            GradeButtonFor(
                ReviewGrade.HARD,
                enabled, previews[ReviewGrade.HARD], Modifier.weight(1f), onGrade
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GradeButtonFor(
                ReviewGrade.GOOD,
                enabled, previews[ReviewGrade.GOOD], Modifier.weight(1f), onGrade
            )
            GradeButtonFor(
                ReviewGrade.EASY,
                enabled, previews[ReviewGrade.EASY], Modifier.weight(1f), onGrade
            )
        }
    }
}

/** One grade button: the label comes from a resource, never an English literal. */
@Composable
private fun GradeButtonFor(
    grade: ReviewGrade,
    enabled: Boolean,
    preview: String?,
    modifier: Modifier,
    onGrade: (ReviewGrade) -> Unit
) {
    GradeButton(
        labelRes = studyGradeLabelRes(grade),
        tag = studyGradeTag(grade),
        // The fill and its text come from ONE role pair. Passing only a
        // container left Material's onPrimary text on top of an error/tertiary
        // fill, which is unreadable with a custom palette.
        palette = gradePaletteFor(grade, MaterialTheme.colorScheme),
        enabled = enabled,
        preview = preview,
        modifier = modifier,
        onClick = { onGrade(grade) }
    )
}

@Composable
private fun GradeButton(
    @StringRes labelRes: Int,
    tag: String,
    palette: GradePalette,
    enabled: Boolean,
    preview: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val haptics = rememberHaptics()
    Button(
        onClick = {
            haptics.tick()
            onClick()
        },
        // Tagged so a UI test can measure the 48 dp tap target on the Button
        // itself; the label Text inside a Button is not the target.
        modifier = modifier.testTag("study-grade-$tag"),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.container,
            contentColor = palette.onContainer
        )
    ) {
        // The delay each grade would produce, as in Anki. Without it the learner
        // cannot tell "Again" from "Hard" before committing, and after Track C
        // the four intervals are no longer an obvious ordering of the labels.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
            if (preview != null) {
                Text(
                    preview,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    // Tagged rather than matched on text: the value depends on
                    // the clock and the day boundary, so a text assertion would
                    // only pass at some times of day.
                    modifier = Modifier.testTag("grade-preview-$tag")
                )
            }
        }
    }
}

@Composable
private fun StudyLoadError(message: String, onRetry: () -> Unit, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onDone) { Text(stringResource(R.string.back_to_library)) }
    }
}

@Composable
private fun StudySummary(
    state: StudyUiState,
    onRestart: () -> Unit,
    onEnableNewCards: () -> Unit,
    onOpenEditor: () -> Unit,
    onDone: () -> Unit
) {
    // The ViewModel says why it is empty; the copy is resolved here (C19).
    val stoppedEarly = state.emptyReason == EmptyReason.StoppedEarly
    val emptyText = when (state.emptyReason) {
        EmptyReason.None -> ""
        EmptyReason.StoppedEarly -> stringResource(R.string.session_stopped_early)
        EmptyReason.NothingDue -> stringResource(R.string.nothing_due)
        EmptyReason.NothingDueInScope -> stringResource(R.string.nothing_due_in_scope)
        EmptyReason.NothingTricky -> stringResource(R.string.nothing_tricky)
        EmptyReason.NothingTrickyInScope -> stringResource(R.string.nothing_tricky_in_scope)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            // The parent Box is bounded, not scrollable. Large font scales or
            // a short viewport can push the tally and actions off screen (the
            // longer clock header makes this easy to hit), so let the whole
            // summary scroll instead of trapping the buttons below the fold.
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // A stop that could not write the pending review says so here rather
        // than in a session that is already gone.
        GradeError(state.gradeError)
        GradeError(state.cardActionError)
        if (emptyText.isNotEmpty()) {
            Text(
                emptyText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            // New cards exist but are switched off: offer the toggle here,
            // where the learner is looking at an empty session, without
            // sending them hunting through settings.
            if (!state.introduceNewCards && state.freshAvailable) {
                Text(
                    stringResource(R.string.new_cards_are_switched_off),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                // This flips a setting for EVERY paper, not just the one whose
                // summary is on screen. Say so plainly instead of implying a
                // paper-local control (H3); the toggle behaviour is unchanged.
                Button(
                    onClick = onEnableNewCards,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.r2_study_enable_new_cards_all_papers))
                }
                Text(
                    stringResource(R.string.r2_study_enable_new_cards_all_papers_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(8.dp))
            }
            // An empty bank has no card to serve. The route out goes straight
            // to the editor instead of dropping the learner back at a library
            // whose "add question" affordance they never found.
            if (!stoppedEarly) {
                OutlinedButton(
                    onClick = onOpenEditor,
                    modifier = Modifier.fillMaxWidth().testTag("study-add-questions")
                ) {
                    Text(stringResource(R.string.of_empty_study_add))
                }
                Spacer(Modifier.height(8.dp))
            }
            SummaryTimes(state)
            if (stoppedEarly) {
                // The learner chose to stop, so the same sitting is still
                // there to go back to — and Done stays one tap away.
                OutlinedButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.study_again))
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.done))
                }
            } else {
                // There is nothing to repeat, so only offer a way out.
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.back_to_library))
                }
            }
            return@Column
        } else {
            Text(
                stringResource(R.string.session_complete),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.study_session_summary, state.reviewed, state.againCount, state.goodCount),
                style = MaterialTheme.typography.bodyMedium
            )
            if (state.leechesOnly && state.reviewed > 0) {
                // The drill walks the due leeches it loaded once. Say so rather
                // than letting the "Study again" button imply the app kept
                // drilling cards that lapsed again during the pass.
                Text(
                    stringResource(R.string.study_polish_drill_one_pass),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (state.showsRecallAccuracy) {
                Spacer(Modifier.height(4.dp))
                // Stats only, no goals: how the sitting went, not a score to
                // defend. Omitted for a sitting that contains a flashcard: a
                // card is self-graded, so there is no correctness to report.
                Text(
                    stringResource(
                        R.string.session_accuracy,
                        state.goodCount * 100 / state.reviewed
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            SummaryTimes(state)
            Spacer(Modifier.height(16.dp))
        }
        OutlinedButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.study_again))
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.done))
        }
    }
}

/**
 * How the sitting's time was spent: on the questions, on reading the answers
 * and explanations, and both together. The two halves come from the clocks the
 * ViewModel banks, so nothing here is estimated. Hidden for a session that
 * never got going (a summary after zero seconds has no split to show).
 */
@Composable
private fun SummaryTimes(state: StudyUiState) {
    if (state.sessionTotalSeconds <= 0L) return
    Text(
        stringResource(
            R.string.session_time_split,
            Dwell.format(state.questionSeconds),
            Dwell.format(state.explanationSeconds),
            Dwell.format(state.sessionTotalSeconds)
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

// --- Pure seams for the round-2 study fixes ---------------------------------
// These are top-level, non-composable functions so the locale mapping can be
// tested on the JVM without a Compose harness or Android resources.

/** The grade's label is copy, so it resolves from a resource, not `enum.name`. */
@StringRes
internal fun studyGradeLabelRes(grade: ReviewGrade): Int = when (grade) {
    ReviewGrade.AGAIN -> R.string.grade_again
    ReviewGrade.HARD -> R.string.grade_hard
    ReviewGrade.GOOD -> R.string.grade_good
    ReviewGrade.EASY -> R.string.grade_easy
}

/** Stable English test-tag suffix; UI tests pin "Again"/"Hard"/"Good"/"Easy". */
internal fun studyGradeTag(grade: ReviewGrade): String =
    grade.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase(Locale.ROOT) }

/** TalkBack state of one option row, every branch localized. */
@StringRes
internal fun studyOptionStateRes(
    revealed: Boolean,
    isCorrect: Boolean,
    isSelected: Boolean,
    /** False for a self-graded question: no key, so no verdict wording. */
    keyKnown: Boolean = true
): Int = when {
    revealed && keyKnown && isCorrect -> R.string.r2_study_option_correct
    revealed && keyKnown && isSelected -> R.string.r2_study_option_selected_wrong
    isSelected -> R.string.r2_study_option_selected
    else -> R.string.r2_study_option_not_selected
}

/**
 * The short-term step's label. A first exposure says "Learning step"; a card
 * that lapsed and is recovering says "Relearning after a lapse". The enum comes
 * from the card's own FSRS state, so the two never disagree.
 */
@StringRes
internal fun studyStepLabelRes(label: StudyStepLabel?): Int = when (label) {
    StudyStepLabel.RELEARNING_AFTER_LAPSE -> R.string.study_polish_relearning_step
    StudyStepLabel.LEARNING_STEP, null -> R.string.learning_step
}

/** Short, localized verdict spoken when a result card lands. */
@StringRes
internal fun studyVerdictRes(correct: Boolean): Int =
    if (correct) R.string.r2_study_verdict_correct else R.string.r2_study_verdict_incorrect

/** Non-colour dwell cue for the question clock. */
@StringRes
internal fun studyDwellBandRes(band: Study.DwellBand): Int = when (band) {
    Study.DwellBand.NEUTRAL -> R.string.r2_study_band_neutral
    Study.DwellBand.EASY -> R.string.r2_study_band_easy
    Study.DwellBand.GOOD -> R.string.r2_study_band_good
    Study.DwellBand.HARD -> R.string.r2_study_band_hard
}
