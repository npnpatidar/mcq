# Phase B report — Study screen and grading path

**Batch:** B2 · **Reviewers:** behaviour/actions, UI/accessibility, data integrity (independent, parallel).
**Scope:** `ui/study/StudyScreen.kt`, `StudyViewModel`, `StudyStore`, `domain/SpacedRepetition`, `SessionClock`, `Fsrs.next()`, related tests. Read-only.

## Severity summary

| Severity | Count | Themes |
|---|---:|---|
| High | 2 | double-review on stop after persisted-grade/pull failure; no retry in simplified mode |
| Medium | 7 | in-flight grade race, missing session row, clock/error a11y, hardcoded copy, colour-only dwell band |
| Low/Nit | 11 | local formatting, clock hint, progress feedback, layout |

## Findings

### High

**H1 — Back/Done can write the same review twice.** `StudyViewModel.kt:1109-1132` `stop()`'s early-finish guard checks `!simplified`, `!revealed`, `grading`, null question/proposed but **not `gradedAndStuck`**. After `next()`/`grade()` persisted the review but `pullCard()` threw (`:975-991`, `:1060`), `pendingGrade` remains set and `grading=false`; Back/Done writes it again (`:1135-1151`, `StudyStore.kt:587-593`). Result: duplicate `review_log` row, doubled exposure count and FSRS update, doubled tally — append-only history corrupted. `StudyScreen.kt:140,151-152` keeps Back live in that state. Fix: add `gradedAndStuck` to the guard, or clear `pendingGrade` once the write succeeds. No test exercises `stop()` after a persisted-grade/pull failure.

**H2 — Simplified (default) mode has no recovery from `gradedAndStuck`.** The Retry affordance exists only in the classic body (`StudyScreen.kt:269-278` → `loadNext()`); `SimplifiedFooter`'s revealed branch renders Change-grade and Next with `enabled = !grading && !gradedAndStuck` (`:372-407`) but no Retry. After the failure the user sees `gradeError`, Next disabled, and only Back/Done — which triggers H1. Add the same Retry and a UI test.

### Medium

- **M1 — Change-grade during an in-flight save.** `StudyScreen.kt:390` is not gated by `grading`; the save coroutine captured a `current` snapshot, so a later tap rewrites `pendingGrade`/`lastResult` in memory while the "Next in …" preview can show an interval that was not persisted (`StudyViewModel.kt:897-921,937-954`). Disable the picker while `grading`.
- **M2 — In-flight grade cancelled on teardown.** Grade writes run on `viewModelScope` (`StudyViewModel.kt:1014`); `onCleared()` (`:1392-1399`) banks clocks and records the sitting but never awaits them, unlike the explicit `sessionScope` (`:299-301`). A screen pop mid-write rolls back the transaction; the tap is lost silently. Use a non-cancelled scope or await in `onCleared`.
- **M3 — Sitting with a persisted review can have no session row.** `stop()` during `grading` (`:1130`) calls `endSitting()` while `reviewed==0` → `sittingEnded=true`, no row; the later grade calls `advanceStats()` but returns on `stopped` without `endSitting()` (`:964,1044`). Sessions/lapses-per-hour undercount. Call `endSitting()` after `advanceStats` and keep `sittingEnded` false until a review is tallied.
- **M4 — Dwell band conveyed by colour alone.** `StudyScreen.kt:525-539` tints text green/amber/red; thresholds 6 s/60 s are never shown and no glyph/word carries the band. WCAG 1.4.1 ([use of colour](https://www.w3.org/WAI/WCAG21/Understanding/use-of-color.html)). **Owner: copy/visual decision.**
- **M5 — Pause affordance undiscoverable.** `StudyScreen.kt:627-648` is a TextButton showing only the running time + Timer icon; no pause/play icon or action label, and disabled after reveal without a reason. TalkBack hears "Question clock, Running, button" but not what activating does. Use dynamic "Pause/Resume question clock" description and a pause/play icon.
- **M6 — Errors never announced.** `GradeError` at `StudyScreen.kt:411-421`, load errors at `:169-173,922-938` are plain `Text`; no live region, Snackbar or `announceForAccessibility` anywhere (baseline §5.6). WCAG 4.1.3 ([status messages](https://www.w3.org/WAI/WCAG21/Understanding/status-messages.html)).
- **M7 — Hardcoded English and raw exceptions.** `StudyScreen.kt:777-782` a11y state strings ("Correct answer", "Selected, wrong answer", …); `:863,866,872,875,444,469,1016` grade labels, summary copy and `"Could not ... ${e.message}"`. `StringResourceUsageTest` cannot see these. Move to resources; log detail instead of showing it. **Owner: copy.**

### Low / Nit

- **L1 — Fixed heights clip at large font scale:** clock `.height(48.dp)` (`:631`) and two-line grade footer (`:319-323,263-267`) in landscape / 1.3–1.5 scale. Use `defaultMinSize`, allow footer scroll.
- **L2 — Dwell ticker never cancelled** in `finishEmpty`/`finishSession` (`StudyViewModel.kt:745-754`), republishing frozen values every second on the summary.
- **L3 — `restart()` joins `sessionEndJob` with no timeout** (`:1288-1292`); a stuck write leaves a spinner.
- **L4 — No progress affordance during grade write** (`StudyScreen.kt:274,291,402`); slow DB looks frozen.
- **L5 — "Next in …" preview vs persisted interval** can differ across a day boundary between Check and Next (`StudyViewModel.kt:851` vs `StudyStore.kt:587-588`); code calls it a preview but the copy reads as a promise.
- **L6 — Bookmark failure invisible** (`StudyViewModel.kt:1203-1211`, `Logger.e` only); haptic uses possibly stale `currentBookmarked` (`StudyScreen.kt:514-515,593-595`).
- **L7 — Correctness-only default unexplained** (`RepositorySettings.kt:425` seeds it; `StudyScreen.kt:526` shows a neutral clock). A new user cannot know the clock is decorative or why Easy never appears. **Owner: first-run copy.**
- **L8 — `gradedAndStuck` Retry copy says only "Retry"** (`StudyScreen.kt:269-278`); does not say the review was already saved and only the next card failed. **Owner: copy.**
- **L9 — Paper title/scope label `maxLines=1`** (`:563-587`) loses context at large font scale.
- **N1 — Default-locale formatting** in `Dwell.format` (`TestTiming.kt:57-60`) and `answered_in_xs`.

## Checked with no defect

Third-exposure `RELEARNING` with a positive interval is deliberate and coherent (`Fsrs.kt:158-185`, pinned by `FsrsSchedulerTest:566-592`); 0 s dwell → Good under the no-clock-games premise; sticky `gradeEdited` provenance; atomic card_state + review_log write (`StudyStore.kt:594-626`); double-tap guards (stop CAS, `grading` flag, reveal/setGuess); single banking point for the clock with explicit pause precedence; all six `EmptyReason` states have a visible reason and exit; answer options expose Role.Checkbox + stateDescription + non-colour glyphs; Back/Up behaviour is consistent; ViewModel survives rotation; no dialogs on this screen.

## Test gaps

1. `stop()` after persisted-grade/pull failure → assert exactly one `review_log` row (H1).
2. Simplified-mode `gradedAndStuck` Retry reachability (H2).
3. Change-grade while saving; stop during in-flight grade and session-row assertion (M1–M3).
4. Clock-band a11y description and large-font/landscape instrumentation (M4, L1).

## Owner decisions

- Dwell-band wording and whether the clock teaches the grading rule.
- First-run explanation of correctness-only default and "Easy may not appear".
- Error copy: expose exception messages or show a generic localized message.
- Whether the pause control should be explicitly labelled Pause/Resume.
