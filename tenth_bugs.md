# Tenth bug report — scoped pass: the test session (follow-up)

Date: 2026-10-25 · branch `feature/host-build-notes` @ 884748a

A follow-up scoped pass over the **test session**: `TestViewModel.kt`,
`TestSessionScreen.kt`, `TestSnapshot.kt`, `TestProgressStore.kt`, the timing/scoring
seams it uses (`TestTiming.kt`, `SessionClock.kt`, `ExamTiming.kt`, `Scoring.kt`,
`Submission.kt`, `SubmitSummary.kt`), the routes that start it
(`Routes.kt`, `McqNavHost.kt`, `LibraryScreen.kt`), and the streamed save
(`HistoryStore.saveAttemptStreamed`).

**Status:** report only, nothing fixed. **Confidence legend:** **Confirmed (run)** =
proven by a test/probe that ran on this host; **Confirmed (code)** = proven by reading
the current code along the exact input path; **Likely** = real defect, one step depends
on timing/data.

Already known and **not repeated**: `fifth_bugs.md` #1-3 (countdown drift, rotation
flags, hardcoded strings) and `drill.md` #1-2 (drill/non-drill resume conflation,
duplicate `Drill.sample`). Finding 1 below is the *mistakes-only* half of the same
conflation and is not covered by either; its fix cannot be the drill fix alone.
`ungradedTotal` staleness after a cross-resume is already recorded in `drill.md:50`.

---

## How this pass was verified

- Pure-JVM baseline re-run on this host: `SessionOrderTest`, `PassageBlocksTest`,
  `CountdownWallClockTest`, `ExamTimingSessionSecondsTest`, `SessionClockTest`,
  `SessionReviewPagingSourceTest`, `RoutesTest` — all pass.
- A scratch probe (3 tests, all green) asserted the current behaviour of findings 1-2:
  `aDrillSnapshot…`/`aMistakesOnlySnapshot…` show `matches()` returns `true` across
  shapes, and `aConflatedResumeCanShowAnsweredFiveOfThree` builds a `TestUiState` with
  5 selections over a 3-question order and shows `SubmitSummary.lines` yields
  `["Answered: 5 of 3"]`. The probe file was deleted after the run; its assertions are
  the pass criteria for the regression test a fix should add.
- Robolectric/SQLite paths cannot run on this aarch64 host; findings that need a real
  database are marked **Confirmed (code)** and must be pinned in CI.

---

## Summary

| # | Sev | Area | Bug | Conf. |
|---|-----|------|-----|-------|
| 1 | High | Resume | A mistakes-only round and a full test are interchangeable on resume → wrong order, and a full test's answers are silently dropped | Confirmed (run + code) |
| 2 | Medium | Resume | A resumed snapshot's selections/flags are not filtered to the new order → "Answered: 5 of 3", negative unanswered | Confirmed (run) |
| 3 | Low-Med | Timer | No snapshot write on backgrounding → a kill/swipe-away rolls the countdown back to the last answer | Confirmed (code) |
| 4 | Low-Med | Timing | Untimed attempts lose all pre-kill elapsed time on resume | Confirmed (code) |
| 5 | Low | Submit | A failed post-save snapshot clear leaves a resume offer for an already-saved attempt → duplicate attempt | Confirmed (code) |
| 6 | Low | Resume | `prefetched` is not cleared on resume → one card can be shown in the pre-resume option order | Likely (code) |
| 7 | Low | Timing | Dwell uses the wall clock while the countdown uses the monotonic clock → dwell glitches, negative untimed duration | Confirmed (code) |
| 8 | Low | Scoring | `negativeMarking` from files/backups is unvalidated → a negative value makes wrong answers *gain* marks | Confirmed (code) |
| 9 | Nit | Routes | A category id containing `,` cannot round-trip through `test?categories=…` | Confirmed (code) |

---

## 1 · HIGH · Resume: mistakes-only sessions are interchangeable with full tests

`TestSnapshot` has no `mistakesOnly` field (`TestSnapshot.kt:26-44`) and
`matches()` compares only `paperId` + `categoryIds` (`TestSnapshot.kt:47-48`). The
library starts a mistakes round with **no categories** — `testRoute(paper.id,
mistakes = true)` (`LibraryScreen.kt:427`, `Routes.kt:62-72`) — while
`TestViewModel` deliberately builds the mistakes base paper-wide, ignoring categories
(`TestViewModel.kt:226-227`). So a mistakes snapshot's `categoryIds` is `[]` and it
matches **every** whole-paper test (and every other mistakes round) of that paper.

`checkResumeOffer` then offers it (`TestViewModel.kt:776`), and `resume()` adopts the
saved order/selections while keeping the *new* session's `mistakesOnly`/`isDrill`
(they are set in `load()` at `:302-304` and never restored at `:828-841`).

A category-scoped full test carries non-empty `categoryIds`, so it does not collide; the
collision is the library's whole-paper Start button and the paper-wide Mistakes button.

Consequences, no race needed:

| Killed → opened | Resume does |
|---|---|
| Whole-paper test (N=200) → Mistakes | Order becomes a permutation of **today's mistakes** (`orderOf` re-shuffles the mistakes base), not the saved paper; `mistakesOnly=true`; all N answers stay in `selections`; `currentIndex` is clamped (`:834`). The other N−M answers are then dropped by `saveAttemptStreamed`, which iterates the order only (`HistoryStore.kt:328-352`). The full-test progress is destroyed. |
| Mistakes (M=5) → whole-paper test | Order becomes the whole paper (N) with `mistakesOnly=false`; the 5-question round turns into a 200-question test with the old timer; submit records an N-question attempt. |
| Mistakes → Mistakes (bank changed) | Replays the seed over *today's* mistaken set: answers for questions that are no longer mistaken vanish from the order but stay in `selections` (finding 2), and a seeded resume never returns `null` even if the base is disjoint (`TestSnapshot.kt:118-127`). |

**Fix direction:** add `mistakesOnly` to `TestSnapshot`, write it in `persistProgress`
(`TestViewModel.kt:879-893`), include it (and the drill fields, per `drill.md` #1) in
`matches()`, and restore the shape in `resume()` the same way the drill fix must.

---

## 2 · MEDIUM · A resume does not filter the saved answer map to the resumed order

`TestUiState.answeredCount` counts every non-empty entry of `selections`
(`TestViewModel.kt:96`) while `questionCount` is the order size (`:98`). `resume()`
adopts `saved.selections`/`revealed`/`flagged` wholesale (`:831-837`) without
intersecting them with the rebuilt order. Any resume where the paper/mistakes bank
changed, or any cross-shape resume from finding 1, therefore carries answers for
questions that are not in the session.

User-visible: the in-screen progress text (`TestSessionScreen.kt:579`) and the submit
dialog (`:638-641`) print `answeredCount / questionCount`, so the header reads
"5/3 answered" and the dialog "Answered: 5 of 3"; `SubmitSummary.unanswered`
(`SubmitSummary.kt:18`) goes negative and `parts()` silently drops the
"Unanswered …" line (`SubmitSummary.kt:30-49`). `flagged` is likewise counted paper-wide
(`TestSessionScreen.kt:641`). The probe in "How this pass was verified" pins exactly
this output.

**Fix direction:** filter `selections`/`revealed`/`flagged`/`dwellSeconds` to the ids of
`ordered` when rebuilding the state (or make `answeredCount` count only order ids).

---

## 3 · LOW-MED · The snapshot is not written when the app goes to the background

`persistProgress` runs only on `toggleOption`/`revealCurrent`/`toggleFlag`/`goTo`
(`TestViewModel.kt:520,536,551,581`). `onAppBackgrounded()` (`:477-479`) only turns the
dwell clock off; `flushDwell()` (`:436-447`) updates state but does not persist.
`TestSessionScreen` maps `ON_PAUSE → onAppBackgrounded`
(`TestSessionScreen.kt:127-137`).

So after the user answers and then backgrounds the app, the snapshot still holds the
`remainingSeconds` (and the dwell) from that answer. If the process is killed or the
task swiped away, `resume()` restores the older value (`:835`): the learner gets back
the time between their last action and the kill, and the current question's unbanked
dwell is lost. Bounded by "time since the last answer", but it is a timer edge in an
exam app that a user can trigger on purpose.

**Fix direction:** call `persistProgress()` after the flush in `onAppBackgrounded()` (the
header is a few hundred bytes; this is the one write that must not be deferred).

---

## 4 · LOW-MED · Untimed attempts lose their elapsed time on resume

For an untimed paper `submit()` stores `(System.currentTimeMillis() - startTimestamp)`
(`TestViewModel.kt:703-707`), but `resume()` resets `startTimestamp` to now (`:843`) and
the snapshot has no elapsed field (`TestSnapshot.kt:33-44`). A 40-minute untimed session
killed and resumed for 5 minutes is saved as a 5-minute attempt. Timed papers are
unaffected (they use `totalSeconds - remainingSeconds`).

**Fix direction:** persist the untimed elapsed seconds in the snapshot (or persist
wall-clock start and take the max with it on resume).

---

## 5 · LOW · A failed snapshot clear leaves a resumable snapshot of a saved attempt

`submitAttempt` deliberately swallows a `clearSnapshot` failure
(`Submission.kt:31-36`) and its doc says a stale snapshot "is harmless, because it no
longer matches a finished session" (`Submission.kt:14-19`). Nothing marks the snapshot
as submitted, and `checkResumeOffer` has no such check (`TestViewModel.kt:762-802`), so
if the DataStore clear fails the next same-paper test offers "N questions answered",
and resuming + submitting writes a **second** attempt (`HistoryStore.kt:311-322`,
`Daos.kt:1872-1873`) and applies `applyQuestionStats` again (double-counted per-question
stats).

**Fix direction:** either make the doc honest and accept it, or record the finished
attempt id in the snapshot (or clear it again on the next load when the paper's latest
attempt is newer), so an already-saved session is never offered.

---

## 6 · LOW · `prefetched` survives `resume()` and can publish the old option order

`load()` resolves the fresh first card and prefetches the next one with the *fresh*
`optionShuffleSeed` (`TestViewModel.kt:170,601-607,637-652`). `resume()` then swaps the
seed to the saved one (`:825`) and calls `resolveCurrent()`, which prefers
`prefetched` if its id matches the resumed current (`:601-604`) — publishing a card
whose options were shuffled with the pre-resume seed (`applyOptionShuffle`, `:655-660`).
Only the first resumed card is affected, and only with option shuffle on; navigating
away and back re-resolves correctly.

**Fix direction:** `prefetched = null` in `resume()` (and in `load()` before the resume
decision).

---

## 7 · LOW · Two clocks: dwell on wall time, countdown on monotonic time

The countdown is anchored on `SystemClock.elapsedRealtime()` (`TestViewModel.kt:348,355`,
the fix from `fifth_bugs.md` #1), but dwell and the untimed duration use
`System.currentTimeMillis()` (`:401,439,458,465,706`). A clock correction (NTP or a
manual change) mid-question distorts dwell: a backward jump makes the banked segment 0
and moves `lastNavMillis` into the past, so the *next* segment over-counts by the jump;
the untimed `durationSeconds` is not clamped and can be stored **negative**.

**Fix direction:** compute dwell and untimed duration from `elapsedRealtime()` too (it is
what "time spent" means); keep wall time only for `finishedAt`.

---

## 8 · LOW · Imported `negativeMarking` is not sign-checked

`Scoring` and `HistoryStore` treat the value as a magnitude: `score -= marks *
negativeMarking` (`Scoring.kt:16`, `HistoryStore.kt:210`). The UI cannot type a minus
(`filterDecimalEntry` defaults `allowLeadingMinus=false`, `NumericInput.kt:15-27`, used
at `LibraryDialogs.kt:81` and `ImportViewModel.kt:805`), but an imported or restored
bank is stored verbatim (`Importer.kt:145,154`; `StreamingImportSession.kt:258`), so a
`"negativeMarking": -0.25` in the JSON makes every **wrong answer add** 0.25 × marks.

**Fix direction:** clamp/sanitise at the model boundary (`max(0.0, …)`, reject NaN/∞ in
the importers) so all callers can trust the sign.

---

## 9 · NIT · `,` in a category id breaks the test route

`Routes.testRoute` joins ids with `,` (`Routes.kt:62-72`) and `McqNavHost` splits on `,`
(`McqNavHost.kt:69`). One id `"a,b"` becomes two ids, which may match *other real*
categories — the session loads a different scope and `matches()`/the VM key compare the
wrong set (`TestSessionScreen.kt:111-112`). Reachable only from a hand-edited/third-party
bank (ids are otherwise generated). Same shape exists for the browse route.

---

## Checked and clean this pass (not reopened)

- Resume-offer gating vs. the fresh timer, and post-submit editing guards
  (`submitted || saving`) — still correct as recorded in `fifth_bugs.md`.
- `submit()`'s save-first/clear-second ordering (only the *doc's claim* is wrong, #5).
- Empty selections (a mistakes round with no mistakes, an emptied paper): the screen
  shows `no_questions_in_this_selection` instead of crashing (`TestSessionScreen.kt:431-440`).
- `QuestionPalette` indexing, `SessionReviewPagingSource` numbering, `firstFlagged`
  guarded by `indexOfId >= 0`, `slowestIndex` guarded the same way.
- `TestProgressStore` write ordering/lock and `discardStale`, `adopt`, `restore`.
- `countUngraded*` vs `correct_answers`, `Scoring.summarize`, `Feedback`/`ExamMode`
  gating, `AutoAdvance`, `SessionClock` gates, `TimerWarnings` one-shot behaviour.
