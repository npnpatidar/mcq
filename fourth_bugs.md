# Fourth bug report — scoped pass: FSRS scheduling and its UI

A scoped pass per the fourth-report plan: read deeply, not widely, over the FSRS
scheduler (`Fsrs.kt`, `SpacedRepetition.kt`, `DayBoundary.kt`), its store layer
(`StudyStore.kt`, `RepositorySettings.kt`, the study DAOs in `Daos.kt`), the
Anki warm-start coating (`AnkiScheduling.kt`), and the study screen
(`StudyScreen.kt`, `StudyViewModel.kt`). Candidates were chased only where the
code actually disagreed with its own documented contract; everything else went
into the verified-clean list. Severity/Certainty as in the previous docs.

---

## Findings

### 1 · Medium · A failed `StudyViewModel.restart()` leaves the screen on a spinner no retry can reach *(fixed in this)*

`StudyViewModel.restart()` (`StudyViewModel.kt:724-786`) sets `loading = true`,
nulls `current`, and only then pulls (`pullCard()` → `getNextStudyCard` →
the card-state DAOs). Its catch block wrote `loadError` alone; it did not
clear `loading`. The screen's branch order is
`when { state.loading -> spinner; state.loadError != null -> error; ... }`
(`StudyScreen.kt:138-147`), so the error branch was unreachable: the learner
saw a spinner (over an already-nulled card) until they backed out. Contrast
`load()`'s catch (`StudyViewModel.kt:250-258`), which does set `loading =
false`, and `enableNewCards()`'s, which does the same.

Verified by injection: after a successful session, closing the repository's
database handle makes the restart's pull fail the way a locked or full disk
would. Fixed here, and pinned by the new
`StudyViewModelRestartFailureTest.aFailedRestartSurfacesItsErrorInsteadOfSpinning`
(Robolectric + the real database, CI-only).

### 2 · Low · Grade/persist failures in a running session are silent, unlike every other failure surface

`grade()` and `next()` (`StudyViewModel.kt:589-653`) catch a failed
`recordStudyReview` and set only `grading = false` — no surfaced message. The
card stays on screen and another tap retries, so a transient failure
self-heals; but a persistent one (locked or full disk, the exact cases
`LibraryViewModelErrorTest` insists on surfacing elsewhere) retries forever
with no explanation while the schedule never moves and `lastResult`'s
promised interval never lands. Recording for the owner to adjudicate: the
minimal fix would be a small inline error strip in the footer, and the
full-screen `loadError` branch is deliberately *not* the right surface, since
it would erase the session's live state.

### 3 · Low · "A preview can never lie about what grading will persist" is slightly over-strong

`refreshPreviews()` (`StudyViewModel.kt:703-722`) computes the four button
labels from `Study.previewDelays(scheduler, card, now)` at **pull time**, while
`recordStudyReview` (`StudyStore.kt:492-522`) re-runs `FsrsScheduler.next` at
**grade time** with a later `now`. With no fuzz the interval formula is
deterministic *given* `now`, but `next()`'s recall branch factors elapsed time
into retrievability, so a learner who dwells on a card — or holds the app
open in the background across a study-day boundary — can have the persisted
interval land one day away from the label when `intervalFor`'s rounding sits
on a boundary (`Fsrs.kt:249-266`). The Again branch is exact in practice
(`now + relearnMs`, minutes apart). The code is right to recompute at grade
time; the docstring's promise is what overstates. Suggested fix is doc-only:
"...so a preview can never lie about what grading would persist at the moment
it was drawn".

---

## Verified clean (this pass, no deltas)

- **The SQL retention ranking is the domain comparator, exactly.**
  `bestDueReviewForPaper` / `bestDueReviewForCategories` (`Daos.kt:1637-1689`)
  rank by `elapsed / stability` descending, mirroring
  `Study.pools`' `(recall asc, dueAt asc, authored)` comparator and
  `elapsedDaysSinceLastReview`'s no-timestamp branch (`Fsrs.kt:186-196`):
  `MAX(intervalDays, 1) / stability` in SQL is
  `intervalDays.toDouble().coerceAtLeast(1.0)` in Kotlin, and the
  `stability <= 0.0` head sorts where `retention()` returns `0.0`.
- **Config sanitization covers every path that constructs a scheduler.**
  `schedulerConfig()` sanitizes on read (`RepositorySettings.kt:213-229`),
  `setSchedulerConfig` on write, `setFsrsParameters` clips, and
  `resetSchedulerConfig` deliberately preserves the fitted weights
  (`RepositorySettings.kt:251-264`) as documented. `StudyStore.fsrs` builds
  from the sanitized config only.
- **The counts ↔ pull parity contract holds.** `countsForPaper` /
  `countsForCategories` / `categoryPopulations` (`Daos.kt:1419-1506`) reproduce
  `Study.pools`' and `Study.eligibility`'s classification predicates in SQL
  (the `NOT (reps = 0 AND dueAt = 0)` new-card shape, the hash-match gate,
  the leech split across `learning`/`due`/`leeches`), and `counts()`' fresh
  arithmetic `questions - (storedRows - newish)` matches `categoryCounts`'.
- **The interval math's documented edge cases are guarded where claimed.**
  `intervalFor` clamps in `Long` before narrowing (`Fsrs.kt:249-266`, with the
  optimizer-bounds overflow scenario spelled out); `shortTermStability` floors
  a success's growth at 1.0; `forgetStability` caps the long-term post-lapse
  value with the short-term one; `dueAfter` can only land a whole-day interval
  at or after the next study-day start (`DayBoundary.kt:27-51`).
-  **The check-path dwell ladder keeps Good reachable.**
  `check()` lifts both ends of the ladder by the same reading bonus
  (`StudyViewModel.kt:507-516`), which is exactly what `inferGrade`'s doc
  demands (`SpacedRepetition.kt:211-223`).
- **Anki warm-start anchors line up on both sides.** `fromAnki` day-counts due
  dates from the collection's `crt` day start and fabricates a real
  last-review timestamp (never relying on the `lastReviewedAt == 0` sentinel)
  when a package omits its review log (`AnkiScheduling.kt:52-120`);
  `toAnki` writes the learning-step row a failed card deserves rather than a
  graduated due-today review, and truncates `due` days from the export's day
  start so 23:00 exports do not shift cards by four hours
  (`AnkiScheduling.kt:170-245`).
- **The drill's fixed-list advance cannot stall on a deleted question.**
  `nextCard` increments `drillIndex` before the question lookup, so a vanished
  member is skipped, not retried (`StudyViewModel.kt:272-284`).
- **Restart semantics for the drill are as documented.** `restart()` re-pulls
  the drill list from the scheduler ("the world has moved on"), and the stale
  card is nulled before the gap (`StudyViewModel.kt:741-767`) — only finding
  #1's catch block was wrong, and it is fixed.

## Dropped after verification (recorded so later passes do not re-chase them)

- *Suspected:* `getStudyQueue`'s `poolsOf(config, dueCards, now)` mis-builds
  the fresh pool because it feeds only due rows into `Study.pools`. Disproved
  as harmless *and* unused: the input map only ever contains due rows (so
  `pools.fresh` is always empty), and the queue builds its fresh pool
  separately from `newQuestions` (`StudyStore.kt:102-144`).
- *Suspected:* `recordStudyReview` writing `contentHash = ""` for a question
  deleted mid-session creates a visible orphan. Disproved as invisible: every
  reader (`countsForPaper`, `dueForPaper`, `bestDueReview…`) inner-joins
  `questions`, so the row is never served or counted, and
  `deleteQuestion` removes card rows explicitly (`QuestionStore.kt:356-363`).
- *Suspected:* a stored REVIEW row with `stability = 0` (Anki `ivl = 0`) is
  served by the review pool with rank-head priority and breaks `next()`.
  Disproved: `hadMemory = state != NEW && stability > 0` routes the grade
  through the initial formulas, `lapses`/leech logic is reached, and the
  `stability <= 0.0` SQL head puts it first in the queue — degraded estimates,
  but no wrong behavior (`Fsrs.kt:80-84`, `Daos.kt:1657-1660`).
