# Round-2 review — Test session

**Branch:** `feature/review-round-2` @ `fbc8e15` (= main, release 0.0.61). Read-only; no product/test/docs code changed.
**Scope:** `ui/test/TestSessionScreen.kt`, `ui/test/TestViewModel.kt`, `domain/TestTiming.kt`, `TestPlan`/`TestSetup`/`TestType`, `TestProgressStore`, `Submission`, `HistoryStore.saveAttemptStreamed`, `QuestionStatsDao` folding/recompute, `ui/test/` selector tests, three `TestSession*UiTest`s. Old `review-reports/03-test-session.md` and `consolidated_bugs` claims treated as **claims**; `git log 0.0.53..HEAD` on these files read. Platform claims cited inline.

## Verdict

The headline data-integrity claims are genuinely fixed: in-flight save can no longer be popped, the countdown freezes behind every reported cover, failed-save duration/dwell are rebased, dwell pause reaches disk, `question_stats` standing now matches recompute, and cross-paper rows are cleaned under one lock. What the fix batches missed is the Back *affordance* split, the accessibility of the toggle/review/timer controls, and the hardcoded time-warning copy. No lost attempts or double saves found.

## Findings

**T1 — Medium — Top-bar Back arrow exits the test when the palette is open; system Back closes the palette.** `TestSessionScreen.kt:185-187,326-332,343-358,461-475`. `backConfirmsSubmit = questionVisible && …`; with `showPalette=true`, `questionVisible=false`, so the arrow takes `popBack()` (guarded only by `saving`) and pops the destination. The palette is an inline `Card`, so the top bar stays interactive. `BackHandler(enabled = showPalette)` (`:465`) handles only system Back; per the docs the two handlers are separate and "the last composed among all enabled handlers" wins ([BackHandler](https://developer.android.com/reference/kotlin/androidx/activity/compose/BackHandler.composable), [custom back](https://developer.android.com/guide/navigation/custom-back)), so with the palette up the two Back affordances do opposite things. Snapshot survives, so no data loss, but a live sitting is dropped without the submit confirmation the screen promises at `:320-325`. *Why missed:* M2's fix added a system-Back handler and `TestSessionPaletteBackUiTest:73-102` presses only `onBackPressedDispatcher`; nobody pressed the arrow. *Fix:* have the arrow mirror the palette handler (`if (showPalette) showPalette=false else …`), or keep `backConfirmsSubmit` true for the arrow independent of `questionVisible`.

**T2 — Medium — Flag and bookmark toggles expose no state to accessibility.** `TestSessionScreen.kt:389-416`. Both are plain `IconButton`s with static `contentDescription`; state is tint/glyph only. TalkBack hears "Flag question" whether flagged or not. The clock right above them *was* given `stateDescription` (`:1053-1062`), so the pattern was applied selectively. Docs: `StateDescription` reads out a composable's state and toggleables should carry it ([semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)). *Why missed:* old M4 was triaged Medium and never carried into a fix batch. *Fix:* `IconToggleButton` + localized `stateDescription` (flagged/unflagged, bookmarked/not) in both locales.

**T3 — Low — Answer-review rows are sub-48dp and unlabelled.** `TestSessionScreen.kt:969-1000`: `.clickable{}.padding(vertical=6.dp)` with two `Text`s, no `heightIn(48.dp)`, no `contentDescription`; the row is a "go to question N" control that TalkBack cannot name. *Why missed:* old M6 never fixed. *Fix:* add `heightIn(min=48.dp)` and a localized "Go to question N" label.

**T4 — Low — Countdown readout is two unlinked semantics nodes and urgency is colour-only.** `TestSessionScreen.kt:361-381`: `Icon(contentDescription="Time remaining")` + bare formatted `Text`; `urgent` changes colour only. TalkBack does not get one "Time remaining 0:42" node, and the 60-second threshold has no non-colour/one-shot announcement. Docs note live regions "shouldn't be used on content that updates frequently, such as countdown timers" — announce the threshold once, not every tick ([semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)). *Why missed:* old M7 not fixed; the header clock's semantics pass did not cover the top-bar timer. *Fix:* one merged node + one-shot `announcer.announce` on threshold crossing.

**T5 — Medium — Time-warning copy is hardcoded English and never announced.** `TestTiming.kt:17-21` returns "5 minutes left" / "1 minute left — the test auto-submits at zero"; produced at `TestViewModel.kt:768-774`, shown in a Card at `TestSessionScreen.kt:477-502`. The screen's announcer covers only `loadError`/`saveError` (`:152-157`), so a screen-reader learner gets no warning at all; under the Hindi locale the warning stays English (the raw exception text was correctly resource-ified at `:1197`, so only this copy was missed). *Why missed:* old M3's "hardcoded wording" half was not tracked after the exception-text half was fixed. *Fix:* string resources with `%1$d`, plus a one-shot announcement (ties to T4).

**T6 — Low — A missing question leaves a permanent spinner with no controls and a frozen countdown.** `TestSessionScreen.kt:562-579` returns early for `currentQuestion==null` with a non-empty order (hence no footer/Submit); `TestViewModel.timerShouldRun` (`:888-894`) requires `currentQuestion != null`, so the exam clock re-anchors forever and can never submit. The user's only exit is the top-bar arrow (or the arrow+confirm path). Old L2, still open. *Fix:* skip unresolved ids at load or show "question unavailable — Skip" that calls `viewModel.next()`.

**T7 — Low, defence-in-depth — `TestPlan.validationError()` is bypassed on the signal-selector path.** `TestViewModel.kt:459-477`: when `focusSelection != null` (all focus types, Balanced, Coverage, Bookmarked, Tag, Authored, Slowest, Fixed panel, Blueprint — `TestType.kt:62-65`), `finalPlan.resolve(order.size)` is never called, so length/scope/exclusion/variant validation runs only via the weaker `buildPlan()` (`:627-698`). A crafted route with `testType=TAG&exclusionMode=RECENT_3` (or an out-of-range count) is accepted and its attempt metadata records a "Fresh sample" that never excluded anything. The setup sheet's own `TestSetup.resolution()` validates, so no honest UI path emits this. *Fix:* call `finalPlan.validationError(order.size)` once in `load()` for every planned session, not only the sampled branch.

## Re-verified fixed since 0.0.53

- **H1 / back during save:** `popBack` guards `saving` (`:185-187`), `BackHandler(enabled = state.saving …)` consumes system Back (`:326-332`), arrow no-ops mid-save; `TestSessionBackWhileSavingUiTest` exercises both. No transaction rollback path left on Back.
- **M1 / countdown behind dialogs:** `timerShouldRun` and `setQuestionVisible` freeze/re-anchor the one timer job (`TestViewModel.kt:754-758,888-923`); `questionVisible` covers palette, submit, review, resume and ungraded (`TestSessionScreen.kt:306-318`); `TestSessionTimerOverlayTest` holds a cover past zero.
- **M2 (system Back), L1, L3, L4, L8:** palette handler (`:465`); controls/VM guards (`OptionRow` `enabled`, `:773`, `:797-825`, `TestViewModel.kt:943,986,1012`); gap rebase (`:1200-1218`); dwell-pause `persistProgress` (`:926-934`); lock in `TestProgressStore.kt:97,127,144,159`.
- **M9 / question_stats standing:** `bumpQuestionStats` takes the attempt's last graded+answered row (`Daos.kt:2947-2962,3254-3281`) against `recomputeFromSurvivingResults`' `finishedAt DESC,id DESC` (`:3157-3169`); skip/ungraded no longer overwrite the standing.
- **L7 / cross-paper orphans:** `save()` captures `outgoingPaperId` under `writeLock` and deletes it with the new rows in one transaction (`TestProgressStore.kt:85-116`). `adopt`/`discardStale` now lock.
- **Single-shot save:** guard + `_state.update` are synchronous on Main before launch (`TestViewModel.kt:1128-1155`); `submitAttempt` saves before clearing (`Submission.kt:24-40`); D8 staleness defence intact (`:1291-1297`).

## Checked, no defect

- `Countdown.remainingAt` recomputes from `elapsedRealtime` anchor; covered ticks re-anchor rather than spawn a second job. Dwell bank/revisit arithmetic does not double-count.
- Resume identity, seed carry-forward, out-of-scope answer filtering, legacy labelling and ungraded recount (`TestViewModel.kt:1324-1433`) are consistent.
- `rememberSaveable` for palette/submit/ungraded and ViewModel for timers survive rotation; Activity recreation is the documented default ([runtime changes](https://developer.android.com/guide/topics/resources/runtime-changes)).
- Shuffle/sampling and resume-order rebuild run on `Dispatchers.Default`; Room writes are suspend—no main-thread DB work in the reviewed paths.
- `applyQuestionStats` rep counters move only for representative types at `samplingVersion>=2`; matches the delete-recompute predicates.

## Owner decisions

1. T1: should the top-bar arrow close the palette (symmetry with system Back) or keep "always confirm submit"?
2. T2–T4: a11y scope this release — toggle `stateDescription`, 48dp review rows, merged/announced timer.
3. T5: resource-ify time warnings in both locales and announce the 60s threshold once.
4. Old M5 still open: palette precedence `current > answered > flagged` hides a flagged-and-answered cell (`TestSessionScreen.kt:1241-1259`); combine states or keep as-is?
5. T6: add a Skip affordance for unresolved questions, or keep the spinner.
