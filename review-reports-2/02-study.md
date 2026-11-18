# Round-2 review — Study session end-to-end

**Branch:** `feature/review-round-2` (HEAD `fbc8e15` == `main`, release 0.0.61; branch also carries tags 0.0.54–0.0.60). Read-only; old `review-reports/02-study.md` treated as claims.

## Scope
`ui/study/StudyScreen.kt`, `StudyViewModel.kt`; `StudyStore` (`recordStudyReview`, `exposureInStudyDay`, `recordStudySession`); `DayBoundary`, `Fsrs`/`FsrsScheduler` call sites, `FsrsReplay`, `SessionClock`, session-row creation/teardown, simplified vs classic grading, change-grade, `gradedAndStuck`, exposure counting, DST/day bucketing, a11y/i18n. FSRS-7 verified against upstream `fsrs-rs` tag `v7.0.0` and `ts-fsrs`; Android lifecycle/coroutine claims against official docs.

## Verdict
Most 0.0.53 findings are genuinely fixed (below). One **new data-integrity race remains** in `restart()`; two a11y/i18n items were only partially fixed and one low VM-gap remains. No FSRS-7 math or DST defect found. Not release-clean as written.

## Findings

### S1 — High — `restart()` reopens a sitting while a grade is still in flight
**File:** `StudyViewModel.kt:1437-1522` (`stopped.set(false)` 1441, `sessionEndJob?.join()` 1447, `sittingEnded.set(false)` 1510), grade continuations `1087-1091` / `1173-1177`, `stop()` guard `1255-1263`, `endSitting()` `1534-1548`.
**Evidence:** `stop()` during `grading==true` takes the early branch at 1255 → `finishSession()` → `endSitting()` sees `reviewed==0` and returns without a row (1535). If "Study again" is tapped before the grade continuation resumes, `restart()` clears both `stopped` and the CAS flag; the continuation then calls `advanceStats()` on the new sitting's zeroed state, sees `sittingEnded()==false` (371), and calls `pullCard()` instead of `endSitting()`. Consequences: the persisted review has **no `study_sessions` row** (the exact M3 defect claimed fixed), the new sitting's tally/summary include a grade given before it started, two `pullCard()` calls race on `_state`, and a late `gradeError` lands on the fresh card. Trigger is the slow DB the durable `sessionScope` was added for; `gradeGateForTests` makes it deterministic.
**Why missed:** `StudyGradeDurabilityTest`/`StudyClearMidGradeTest` gate stop and clear, but never tap "Study again" while the gate is held; `restart()` joins only `sessionEndJob`, not the grade write.
**Fix shape:** track the in-flight grade `Job` and join it in `restart()` before clearing latches; or tag each sitting with a monotonic id and include it in the continuation's `sittingEnded()`/`endSitting()`/`advanceStats()` decision so a late grade from sitting N cannot mutate N+1.

### S2 — Medium — grade labels and TalkBack option states still English under the Hindi locale
**File:** `StudyScreen.kt:842-845` (`optionState` literals), `:508` (grade label from `ReviewGrade.name`), `:927,930,936,939` (`GradeButton("Again"|"Hard"|"Good"|"Easy")`).
**Evidence:** `R.string.grade_again`…`grade_easy` and `correct_answer` exist and are translated (`values-hi/strings_hi_2.xml:44,47-49`; `strings_hi_1.xml:107`), but the screen passes literals through `GradeButton(label: String)` and assigns `stateDescription = optionState` from literals. `StringResourceUsageTest.noHardcodedUserFacingLiteralInComposeTextOrFeedback` only matches `Text("literal")`/`contentDescription = "literal"`, so parameter-passed literals are invisible. Old M7 fixed only the error/exception half (`friendly()`, `:288-290`).
**Fix shape:** pass `@StringRes` into `GradeButton`, resolve option states from resources (shared `Verdict` already carries `labelRes`/glyph), derive the result grade label from a resource; widen the string-usage test to parameter literals.

### S3 — Medium — the verdict is never announced
**File:** `StudyScreen.kt:124-130` (only `loadError`/`gradeError` announced), `:250-252,488-539` (result card), `:405-420` (`check()`).
**Evidence:** `LocalAnnouncer` is present and used for errors, but nothing speaks `Correct`/`Incorrect` or the chosen grade after Check/reveal; the haptics at 413-418 are not a screen-reader channel. `ResultsScreen.kt:1450-1474` does give its verdict a glyph-bearing `contentDescription` via `R.string.verdict_spoken`. WCAG 4.1.3 (https://www.w3.org/WAI/WCAG21/Understanding/status-messages.html).
**Fix shape:** announce a short localized verdict on `lastResult` change, or add a live region / contentDescription to `SimplifiedResultCard`. **Owner: copy.**

### S4 — Low — `changeGrade()` has no `grading` guard in the ViewModel
**File:** `StudyViewModel.kt:1014-1038`; UI gates picker buttons with `!state.grading` (`StudyScreen.kt:441`) but not the "Change grade" toggle (`:454`).
**Evidence:** a tap in the frame before recomposition can rewrite `pendingGrade`/`lastResult` after `next()` captured `proposed` (1048); on a failed pull the result card then shows a grade the row does not contain. No duplicate row, but a visible/data mismatch.
**Fix shape:** add `if (current.grading) return` to `changeGrade()` and disable the toggle too.

## Re-verified fixed since 0.0.53
- **H1 (double review on stop after persisted grade + failed pull):** `stop()` guard now includes `gradedAndStuck` (`StudyViewModel.kt:1255-1257`); `StudyGradedStuckTest:122-169`, `markGradedAndStuck` regression. Fixed.
- **H2 (no Retry in simplified mode):** `SimplifiedFooter` now has `study-retry` calling `loadNext()` (`StudyScreen.kt:425-435`, `StudyViewModel.kt:519-540`); `StudyGradedStuckTest:204-224`. Fixed.
- **M1 (change-grade during save):** picker `GradeButtons` disabled while grading (`StudyScreen.kt:441`); UI-level fix (VM gap = S4).
- **M2 (grade cancelled on teardown):** grade/stop writes on durable `sessionScope` (`1056,1139,1268`), `onCleared()` latches `cleared` (1555), cancellation rethrow guards; `StudyGradeDurabilityTest`, `StudyClearMidGradeTest`. Fixed.
- **M3 (persisted review, no session row):** `sittingEnded()` + `endSitting()` after tally (`1087-1091,1173-1177`), `advanceStats` flips `StoppedEarly→None` (`1217-1225`), CAS at 1536. Fixed except S1.
- **M6/M7 (errors unannounced, raw exceptions):** `announcer.announce` for load/grade errors (`StudyScreen.kt:125-130`), `friendly()` resources, tests assert no raw exception text (`StudyGradedStuckTest:99-106`). Fixed.
- **DST rewrite:** `DayBoundary` calendar-day arithmetic (`DayBoundary.kt:76-103,128-141`), `ExposureInStudyDayTest.theExposureDayUsesTheBoundaryOfTheReviewDateAcrossDst`, `DayBoundaryDstTest` spring/fall due dates, 23/25-hour days, session bucketing. Verified correct.
- Third-exposure `RELEARNING` with a positive interval is intentional (`Fsrs.kt:181-187`).

## Checked, no defect
- **FSRS-7 maths:** line-by-line match to upstream `fsrs-rs` v7.0.0 `model_v7.rs` — initial stability/difficulty/`Sf=0.8S`, two-trace update, lapse cap, mean reversion, mixture curve + `2e-5` epsilon, derivative, 7-step Newton + 50-step bisection (https://github.com/open-spaced-repetition/fsrs-rs/blob/v7.0.0/src/model_v7.rs); 34 default weights and clipper bounds/monotonic rules match `inference_v7.rs` and `parameter_clipper_v7.rs` (https://github.com/open-spaced-repetition/fsrs-rs/blob/v7.0.0/src/inference_v7.rs). `round8` parity choice documented against ts-fsrs (https://github.com/open-spaced-repetition/ts-fsrs/tree/main/packages/fsrs/src/models/fsrs-7). No FSRS defect.
- Atomic card_state + review_log write in one `withTransaction` (`StudyStore.kt:612-644`); exposure key is `(paperId, questionId)` since `DayBoundary.startOfDay` (`Daos.kt:2172-2176`), shared by preview, grading and `FsrsReplay:105-120`.
- Double-tap guards: `stop` CAS, `grading` flag, reveal/setGuess, `stopped` re-check after every pull suspension; dwell ticker cancels on re-anchor; clocks use monotonic `elapsedRealtime` with ms banking and explicit pause precedence.
- Session-row CAS prevents duplicates; `sessionEndJob` joined on restart; rotation survives (same ViewModel); Back/Up consistent.

## Owner decisions
1. Is S1 release-blocking for 0.0.61?
2. Dwell band still colour-only (`StudyScreen.kt:588-603`) — wording/glyph or teach-the-rule decision.
3. Pause control still has no explicit Pause/Resume icon/label (`:691-712`) — visibility decision.
4. Verdict announcement copy (S3).
