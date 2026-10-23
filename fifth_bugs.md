# Fifth bug report — scoped pass: the test session

A scoped pass per the find-first-mail plan over the **test session**: its view
model (`TestViewModel.kt`), its screen (`TestSessionScreen.kt`), the snapshot
model and store (`TestSnapshot.kt`, `TestProgressStore.kt`), the shared timing
and scoring helpers (`TestTiming.kt`, `SessionClock.kt`, `ExamTiming.kt`,
`Scoring.kt`, `Submission.kt`, `ExamMode.kt`, `Feedback.kt`, `AutoAdvance.kt`,
`SubmitSummary.kt`, `AnswerReview.kt`), the presented-order primitives
(`SessionOrder.kt`, `PassageBlocks.kt`), the paged pre-submit review
(`SessionReviewPagingSource.kt`), and the streamed result save
(`HistoryStore.saveAttemptStreamed`, via `McqRepository`).

**Status:** fixes for #1-3 were applied after this report was written; the
working-tree edits are the fix. This report remains the finding record. The
`AnswerReview` `—` placeholder is the only inventory item left as a bare
symbol.

**Confidence legend:** **Confirmed** = defect proven by reading the current code
and the exact input · **Likely** = code defect real, one reachability step
depends on data shape · **Uncertain** = flagged with the specific doubt.

---

## Summary

| # | Sev | Area | Bug | Conf. |
|---|-----|------|-----|-------|
| 1 | Medium | Test timer | Countdown is a tick-count decrement, not tied to the wall clock → long papers can auto-submit late | Confirmed |
| 2 | Low-Med | Test UI | Submission-dialog / palette / ungraded-dismiss flags are plain `remember` → a rotation resurrects dealt-with dialogs and re-pauses the dwell clock | Confirmed |
| 3 | Low | Test UI | Bundle of user-facing strings hardcoded in code (resume dialog, ungraded dialog, save progress, drill banner, review rows, question header, submit summary, palette semantics) | Confirmed |

---

## Findings

### 1 · MEDIUM · TestViewModel — the countdown drifts against the wall clock

`app/src/main/java/com/mcqapp/ui/test/TestViewModel.kt:325` (`startTimer`):

```kotlin
while (true) {
    delay(1000)
    ...
    _state.update { it.copy(remainingSeconds = it.remainingSeconds - 1) }
```

The exhaustion counter is **a count of `delay(1000)` iterations**, not time.
Every iteration costs the delay's own scheduling slack (the dispatcher only
guarantees "at least" 1 s, and under load it runs late) plus the state update.
The drift is one-directional, so it accumulates without bound: an hour-long
paper can reach zero tens of seconds after the learner's own watch, and the
auto-submit fires late. Two aggravations:

- The drift reserve is never reconciled — there is no anchor anywhere in the
  loop connecting `remainingSeconds` to elapsed real time.
- The deadline that matters is `remainingSeconds == 0` auto-submit, i.e. the
  *grading* clock is what drifts, not a cosmetic readout.

Renewal note: the snapshot's `remainingSeconds` inherits whatever the drift has
left, so a resume also resumes the stolen time.

**Proposed fix (not applied):** anchor `startTimer` on
`SystemClock.elapsedRealtime()` + the remaining-at-anchor and let every tick
compute `remaining = anchorRemaining − (now − anchor) / 1000` (saturating at
zero, `target >= current → continue`). A pure `Countdown.remainingAt(...)` in
`TestTiming.kt` keeps it testable; warnings and auto-submit flow through the
existing `TimerWarnings.newlyDue(previous, target)` path unchanged.

### 2 · LOW-MED · TestSessionScreen — contingency state does not survive rotation

`app/src/main/java/com/mcqapp/ui/test/TestSessionScreen.kt:142-145`:

```kotlin
var showPalette by remember { mutableStateOf(false) }
var showSubmitDialog by remember { mutableStateOf(false) }
var ungradedDismissed by remember { mutableStateOf(false) }
```

Plain `remember` is discarded on configuration change (rotation, dark-mode
flip, re-creation on a Chinese-localized resize, process death), and these
three are remembered *decisions with effects attached* — not just visibility:

- `ungradedDismissed` — the "questions without an answer key" dialog, after the
  user picked **Continue**, reappears on rotation, and (it is the gate in
  `questionVisible`, so while it is up) the dwell clock pauses again until the
  learner repeats the dialog on the same session start.
- `showSubmitDialog` reappears if it was open, fine — but if it was *annulled*,
  reopening on request at rotation does not cost nothing: the summary and the
  first-flagged jump are recomputed, no harm there. The palette and the submit
  dialog reset are haze; the ungraded artifact is the real one.

**Proposed fix (not applied):** `rememberSaveable` for all three.

### 3 · LOW · User-facing strings hardcoded in code, whole-screen bundle

`stringResource` is the project's pattern everywhere else, and the doc comment
on `QuestionHeader` (`TestSessionScreen.kt:760-770`) even asserts "the visible
clock text renders via stringResource" while its `questionProgressLabel`
actually builds raw literals:

```kotlin
"Question ${index + 1} of $total" + " · ... mark/marks" + " · ... here"
```

Inventory of the literals on this screen (each `Text(...)` / semantics string
built by hand; line numbers at `HEAD`):

| Where | Lines | Sample |
|---|---|---|
| Resume dialog text | `TestSessionScreen.kt:115-121` | `"$answered question(s) answered"`, `", %02d:%02d left."`, `" Your progress was saved when the app closed."` |
| Ungraded dialog body | `:155-160` | "1 question has no answer key and won't be scored. ..." |
| Retry button label | `:381` | `"Retry"` — while `R.string.retry` *is used elsewhere on the same screen*, and `"Saving…"` neighbours it |
| Save progress card | `:405` | `"Saving result… ${saveDone} of ${saveTotal}"` |
| Drill banner | `:471` | "Drill — N questions on the clock" (while `drill_overshoot_shown` on the next block *is* a resource) |
| Palette: answered chip + status semantics | `:549-552, 971` | `"${answered}/${count} answered"`, `"Go to question N, current/answered/..."` |
| Review sheet rows | `:712-724` | `"Q${row.number}"`, `"answered"/"unanswered"/"not scored"`, `" · flagged"` |
| Question header text | `:795` | the progress label above |
| Explanation placeholder | `:908` | `"No explanation provided."` |
| ViewModel-side error strings | `TestViewModel.kt:314, 721` | `"Could not start the test: ..."`, `"Could not save your result: ..."` |
| Save fallback title | `TestViewModel.kt:738` | `paper?.title ?: "Test"` |
| Submit summary lines | `SubmitSummary.kt:15-21` | "Answered: x of y", "Unanswered: z — these score 0", "Longest on Qn: ..." |
| `TestProgressStore`/`AnswerReview` summary | `AnswerReview.kt:41-49` | `"—"` (the empty-summary placeholder) |

Same class of finding as `new_bugs.md` #39's "bookmarks dialog hardcoded
string", which was never broadened. Fixing it means adding ~15 plurals/
formatted resources and routing the two ViewModel-side error/resume strings
through a resource id (or `UiText`) — a mechanical but wide change; recording
before the fix pass so the sweep is not done piecemeal.

---

## Areas checked, clean (this hunt's "no bug found" notes)

These were chased to the relevant seam; each had a plausible suspicion and each
survived the check against the actual code:

1. **Resume offer vs. the fresh countdown** — withheld while the offer is up
   (`TestViewModel.kt:303-305`), started by whichever branch resolves the
   dialog; pinned by `TestSessionResumeTest`. The destructive-auto-submit race
   behind the dialog (finding #5 of `new_bugs.md`) is genuinely fixed.
2. **Snapshot / row pairing** — `TestProgressStore.save/clear/restore` share
   one `writeLock`, so the header no longer lands one write behind its rows
   (finding #24 of `new_bugs.md` is fixed); a crash between a save's row write
   and its header is cleaned by `discardStale` at the next load of the same
   paper; an unreadable document is dropped, a same-paper document is left
   alone.
3. **Post-save edit acceptance** — `toggleOption`/`toggleFlag`/`goTo`/
   `revealCurrent` all bail on `submitted || saving`; the screen disables the
   same controls. (#10 of `new_bugs.md` fixed.)
4. **An order replays itself** — seed replay (`TestSnapshot.orderOf`) shares
   `SessionOrder`'s block-aware Fisher-Yates (`PassageBlocks.shuffleBlocks`,
   drawn through `java.util.Random` to match `List.shuffled`), and a legacy
   snapshot keeps its id list *and* the null-seed forever, so a second resume
   can never revert to re-authored or re-shuffled order (pinned by
   `TestSessionResumeTest.aLegacyOrderSurvivesASecondResume`).
5. **Overflow / underflow of derived numbers** — `ExamTiming.secondsFrom` clamps
   to a week (a deliberate note, twice-guarded), drill `drillMinutes`/`duration`
   both saturate (`TestViewModel.kt:228-232`), `SessionClock` engages gates
   only through `clockMayRun`, and the countdown coercion holds the tempting
   `(now - nav)/1000` segment to a non-negative.
6. **Dwell bookkeeping** — `publishDwell`/`flushDwell`/`dwellCommitted` bank per
   navigation and never double-charge: a deliberate pause does not charge
   (`setDwellActive(false)` flushes inside `submit()` and `goTo`), the
   per-second tick does not rewrite the whole `dwellSeconds` map (use of the
   separate `_liveDwell` flow is exactly to keep the once-a-second recompose
   from invalidating the question body), and an in-flight `resolveCurrent`
   publish raced by a navigation is dropped (`resolveCurrent`/`prefetchNext`
   both re-check the still-current index).
7. **Paged pre-submit review** — `SessionReviewPagingSource` numbers rows by
   presented index (not `mapNotNull` sequence), pages via `prevKey`/`nextKey`
   correctly, and an entered-row tap's `goTo(row.number − 1)` round-trips the
   very same index; the sheet pauses dwell via `questionVisible`, and rows take
   their summaries via `searchableText` (a doc-noted fix).
8. **Streamed result save** — `HistoryStore.saveAttemptStreamed` keeps the
   *session* order (a category lookup memoises), batches 500 rows, tallies
   before `updateTally`, and holds all of it in one `db.withTransaction`; the
   progress callback is explicitly out-of-band. `submitAttempt` save-first /
   clear-second is honoured (`Submission.kt`), with `submit()`'s `onClearFailure`
   deliberately silent and the snapshot still readable when the save fails
   (Retry button exists and re-runs `submit()`).
9. **Scoring math** — `Scoring.scoreQuestion`/`summarize` skip empty keys
   (`ungraded`), half-marks negative marking as `−marks × negativeMarking`
   (consistent with `HistoryStore.scoreBatch`), and `selected == correct` is
   exact-set equality everywhere (`isCorrect`), including the stored row's
   `isCorrect` flag.
10. **Strict/practice gating** — the whole matrix routes through
   `ExamMode`/`Feedback` (reveal, flag, palette, live colours and explanation
   marker), and `Feedback.revealMarker` cannot tick a non-key option; the
   ungraded card disables its reveal colors outright (`Feedback.liveReveal`).
11. **Timer-inflight interactions** — the 5-min/1-min warnings are one-shot
    via `warnedThresholds` and re-armed on resume; the 1-minute warning's
    "auto-submits at zero" promise is kept by the last tick; `submit()` guards
    re-entry (`submitted || saving || questionCount == 0`) and cancels both
    jobs before starting the save.
12. **Comment-rot only (not bugs)** — `startDwellTicker` has two consecutive
    doc blocks (an editing leftover); `Dwell.add` now appears unused in the
    screen (the map has moved to `dwellCommitted`) — flag for the dead-code
    test if the codebase grows a `Dwell.add` reference check.
