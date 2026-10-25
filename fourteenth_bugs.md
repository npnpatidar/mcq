# Fourteenth bug report — scoped pass: FSRS scheduling and the study loop (follow-up)

A scoped pass per the owner's bug-hunting mission, covering the FSRS surfaces the
fourth and seventh reports did not deep-dive: the optimizer's dataset assembly
(`FsrsOptimizer.kt`), the tuner bridge (`FsrsTuner.kt`), the batch/streaming
replay (`FsrsReplay.kt`), the stats accumulators (`StatsAggregator.kt`,
`StatsModels.kt`, `StatsStore.kt`), and the study session ViewModel
(`StudyViewModel.kt`) plus its store (`StudyStore.kt`) and the pure study
vocabulary (`SpacedRepetition.kt`). The FSRS-6 kernel itself (`Fsrs.kt`) and
`DayBoundary.kt`/`AnkiScheduling.kt`/`RepositorySettings.kt` were re-checked
only where this pass's findings touch them.

Reference-checked against the upstream the optimizer claims to port:
open-spaced-repetition/fsrs-rs, `dataset.rs` at v1.1.0 (the FSRS-6 trainer) and
`main`. **Status:** report only, nothing fixed. Already-known findings are not
repeated (fourth report #1–3; seventh report's stats/charts list).

**Confidence legend:** **Confirmed** = defect proven against the current code
(one by running the pure function); **Likely** = code defect real, one
reachability step depends on a race or data shape.

---

## Summary

| # | Sev | Area | Bug | Conf. |
|---|-----|------|-----|-------|
| 1 | Medium | Optimizer | Outlier buckets and the w0–w3 pretrain set are built from *every* complete card; the reference restricts both to cards with exactly one inter-day review — an undisclosed recipe deviation that changes the fit on any real history | Confirmed |
| 2 | Low-Med | Replay | `FsrsReplay.replay`'s per-row maps are keyed by row id **globally** while its own doc grants ids unique *per question only* — a contract-obeying caller gets cross-card predictions (verified by running it) | Confirmed (latent) |
| 3 | Low-Med | StudyViewModel | A pull failure *after* the grade persisted reports "Could not save your grade" and leaves the graded card actionable — the retry writes a second review for one answer | Confirmed |
| 4 | Low | StudyViewModel | `stop()` during an in-flight grade is un-done: the completing coroutine pulls a new card and clears `finished` | Confirmed |
| 5 | Low | StudyViewModel | `pullCard`'s skip-loop can spin forever if the pull keeps serving a question deleted between its two DB reads | Likely |
| 6 | Nit | StudyViewModel | Double-tapping `retry()` runs two loads; the second pull double-increments `reviewsSinceNew` | Confirmed |

---

## Findings

### 1 · MEDIUM · Optimizer — the outlier filter and the S₀ pretrain see a different dataset than the reference recipe

`FsrsOptimizer`'s class doc says the recipe follows the official trainer
(fsrs-rs) and that "deliberate deviations, all documented at the code that
makes them". This one is neither documented nor deliberate-looking.

**What the port does.** `doFit` builds both the outlier buckets and the w0–w3
pretrain set from *every* complete (non-seeded) card that has at least one
inter-day review (FsrsOptimizer.kt:206-210 for `computeRemovedPairs`, 234-237
for `initKept`):

```kotlin
val removed = computeRemovedPairs(
    prepared.filterNot { it.seeded }
        .filter { it.firstInterday >= 0 }
        .map { InitSample(it.ratings[0], it.deltas[it.firstInterday], ...) }
)
```

and `initKept` re-derives the same population, so the closed-form initial
stability fit trains on one first-inter-day prefix per complete card, however
many inter-day reviews that card went on to have.

**What the reference does.** fsrs-rs v1.1.0 `dataset.rs`:

```rust
let (mut pretrainset, mut trainset) = items
    .into_iter()
    .partition(|item| item.long_term_review_cnt() == 1);
(pretrainset, trainset) = filter_outlier(pretrainset, trainset);
```

`filter_outlier` computes its bucket statistics from the *pretrainset only* —
items with exactly one review at Δt ≥ 1 — and `pretrainset` (the S₀ fit's data,
`parameter_initialization`) is that same single-inter-day subset. Multi-interday
cards are *filtered* by those buckets (via their own first long-term gap) but
never contribute bucket counts and never enter the S₀ fit. Current `main`
(`compute_outlier_bucket_decisions`) keeps the same shape: the decisions come
from `dataset_for_initialization`, which `prepare_training_data` still
partitions to `long_term_review_cnt == 1` items.

**Consequence.** On any library that has been studied past the first day (cards
with 2+ inter-day reviews — the normal case once optimization unlocks at 1000
reviews), the bucket sizes, the removed buckets, `smoothAndFill`'s rating
counts, and therefore the fitted w0..w3 all differ from the reference fit on
the same log. The single-interday restriction is also the reference's quality
guard: for those cards the first inter-day review follows only same-day
reviews, the cleanest measurement of S₀. Nothing crashes; the fit is just
silently a different fit than the recipe the file claims to implement.

**Fix.** Restrict both `computeRemovedPairs`' input and `initKept` to complete
cards with exactly one inter-day review (`card.deltas.count { it >= 1 } == 1`),
matching the reference, and note the port fidelity at the class doc; or,
keep the wider dataset and add the deviation to the documented list — but the
first is what "follows the official reference trainer" promises.

### 2 · LOW-MED · Replay — the batch `FsrsReplay.replay` breaks under its own documented id contract

`RawReview`'s doc says "`[id]` only has to be unique within one question's
history" (FsrsReplay.kt:26-28). But `replay` collects predictions in two
`HashMap<Long, …>` keyed by row id **across all questions** (FsrsReplay.kt:52-53)
and reads them back globally (FsrsReplay.kt:88-102). Two cards whose rows share
ids — exactly what the doc permits — overwrite each other's entries: whichever
card's group is processed last owns every colliding row's prediction and
interval.

Verified by running the pure function: two cards, each reviewed at t and
t+Δ (ids 1, 2 in both), 3-day and 30-day gaps — card q1's second row came back
with **q2's** 30-day retrievability (0.6675) instead of its own 3-day value
(0.8809). Calibration, the true-retention maturity split and the replay tests'
oracle would all be silently wrong for such a caller.

Production is not bitten today: `StatsStore` streams through
`FsrsReplay.Stream`, whose per-card maps don't share this shape, and real log
ids are the global autoincrement PK. But the batch API is the documented
pure surface (and the shape `FsrsTuner.buildFsrsTrainingCards` mirrors for the
optimizer), and the existing `FsrsReplayTest` happens to use globally-unique
ids, so the collision path is untested.

**Fix.** Key the two maps by `questionId to id` (or a pair object), or fix the
doc to demand global id uniqueness. A pinning test with per-question ids would
lock whichever way is chosen.

### 3 · LOW-MED · StudyViewModel — a failed pull after a persisted grade misreports and invites a double-persist

`grade()` (StudyViewModel.kt:638-660) and `next()` (600-621) wrap the whole
persist-then-pull sequence in one try/catch. The persist and the pull are
different failures with different consequences, but they share one message:

1. `recordStudyReview` succeeds — the review_log row and the new schedule are
   committed, `advanceStats` has counted the review.
2. `pullCard()` then throws (its `getNextStudyCard`/`getQuestion` DAO reads are
   exactly the locked-disk/full-disk surface the fourth report's #1/#2 used).
3. The catch shows **"Could not save your grade"** — false, it was saved — and
   sets `grading = false` while leaving the graded card on screen
   (`revealed` still true, `pendingGrade` still set for the `next()` path).

The learner's natural retry — tapping the same grade button, or Next — runs
`recordStudyReview` **again**: a second review_log row and a second schedule
advance for one answer (stability re-grown through the same-day formula, reps
+2, the interval previewed twice). Unlike fourth-report #2 (a *silent* persist
failure, which self-heals harmlessly because nothing was written), a retry here
corrupts the history the optimizer later trains on.

**Fix.** Split the catch: once the persist returns, a pull failure must keep
the persisted result (clear `pendingGrade`/`revealed` or at least neutralize
the grade buttons), advance or not the session tally consistently, and word the
error "Could not load the next card" — never "Could not save your grade".

### 4 · LOW · StudyViewModel — `stop()` can be un-done by an in-flight grade

`stop()` (StudyViewModel.kt:677-680) marks the session finished while a grade
coroutine may still be between `recordStudyReview` and `pullCard()`. When the
coroutine completes, `pullCard` sets `finished = false` (StudyViewModel.kt:319)
and serves a new card — the session the learner just ended reopens itself, with
the tally already counted. Same shape as the test-session races in the fifth
report. **Fix.** Capture a `stopped` flag before launching and check it before
`pullCard()`, or have `stop()` cancel the grade job.

### 5 · LOW · StudyViewModel — the pull's skip-loop has no bound

`pullCard` (StudyViewModel.kt:288-327) skips a pulled card whose question row is
gone with `?: continue`, re-pulling without changing anything. Every SQL reader
behind the pull inner-joins `questions` (fourth report), so this needs the
question deleted *between* the pull's SQL read and `getQuestion` — but that
race is real (a concurrent backup restore / bulk delete is not impossible with
the session in the back stack), and when it hits, the loop never terminates:
the session hangs with a spinner, no error, no log. The drill path is safe
(`drillIndex` advances); only the live pull loops. **Fix.** Bound the skip
streak (one retry, then `finishEmpty()` with an error note), or have the store
verify the row inside the same read.

### 6 · NIT · StudyViewModel — `retry()` has no in-flight guard

`retry()` (StudyViewModel.kt:267-270) nulls `loadError` and launches `load()`
again with no "already loading" check; a double-tap inside one frame runs two
loads to completion. Both end in `pullCard()`, so the card is served twice
(the second wins on screen) and `reviewsSinceNew` is incremented twice for the
one card the learner sees — a small but real skew in the explore-mode pacing
counter. **Fix.** Early-return when `state.loading` is already true.

---

## Verified clean (this pass, no deltas)

- **`computeRemovedPairs`' walk order matches upstream.** Sorted by
  (bucket-size desc, delta desc) then iterated reversed — the current fsrs-rs
  `main` orders sub-groups exactly this way; the v1.1.0 difference is
  immaterial (same ascending walk). The 5%/min-20 quota, the ≥6-and-≤100/365
  survivor rule, and Easy's 365 threshold all match.
- **`evaluateBatch`'s synthetic timeline is exact.** Seeded cards get elapsed =
  `deltas[0]` from `lastReviewedAt = SYNTHETIC_EPOCH`; non-seeded first-interday
  predictions replay the same-day prefix through the short-term branch;
  the loop breaks before advancing past the last predicted review, so the final
  grade is scored but never replayed. Non-finite input is walled off by
  `prepare`'s `require(rating in 1..4)` and `roundDays`' `coerceAtLeast(0)`.
- **`FsrsTuner.buildTrainingCards`' ordering contract holds.**
  `pageQuestionIdsAfter` pages ASC by questionId (so `after = last()` is a
  keyset cursor), `rowsForQuestions` orders by `questionId, reviewedAt, id` —
  consecutive-equality grouping and the "already chronological" assumption of
  `trainingCardOf` both hold; the `?: continue` for a lone seeded row skips
  before `firstIds` is appended. The seeding predicate is identical to
  `FsrsReplay.isSeeded`, and both drop the seed row's own rating from training.
- **The optimizer's pinned primitives** (`clip`, `lrAt`'s cosine, `l2Penalty`'s
  batch/total scaling, `smoothAndFill`'s monotonic repair and all ≥2-non-null
  fill branches — every combination with at least two observed ratings is
  covered, `size==1` scales the defaults, `size==0` returns null) match the
  reference vectors, and `FsrsOptimizerTest` pins them. The
  `iterations`-vs-`totalSteps` cosine overshoot and the seeded-card bucket
  attribution are documented deviations, as claimed.
- **`SettingsViewModel`'s optimize flow:** the running guard, the
  `CancellationException` rethrow (leaving Settings cancels cleanly, nothing
  persisted), `dismiss`/`restoreDefaults` reset to Idle.
  `updateSchedulerConfig` persists the raw config, but
  `setSchedulerConfig` sanitizes on write (RepositorySettings.kt:200-201), so
  display and storage converge; `setFsrsParameters` clips through the same
  `sanitized()`.
- **The stats accumulators' arithmetic:** `weekdayOf` (epoch day 0 = Thursday,
  `+3` → Monday=0), the heatmap's trailing-today streak loop, the forecast's
  `floorDiv(dueAt − todayStart)` bins with the overdue bucket, the
  `FirstOfDayCursor` fed every scoped row in Overview but only in-range rows in
  Retention (provably equivalent — the range boundary is always a study-day
  boundary), calibration's clamp to 0.999999 with unclamped mean, and the
  `Values` median. The seventh report's item list already covers the cosmetic
  residues.
- **`ReplayReview`'s derived flags** (`passed`, `matureAtReview` ≥ 21,
  `kind`'s LEARNING/NEW fold) agree with the consumers in both accumulators,
  and the `Stream`'s append-order semantics remain deliberate (pinned by
  `FsrsReplayStreamTest`).
- **`StudyStore`:** counts↔pull parity re-confirmed on the heads this pass
  reads (`firstDueLearning*`, `bestDueReview*`, `firstNew*` all inner-join
  questions); `recordStudyReview` writes schedule + log in one transaction with
  the pre-state recorded for the seed decision; `scopeIds` de-duplicates before
  the chunked sums.
- **`Study.previewDelays`/`autoGrade`/`inferGrade`/`pickNext`:** the preview is
  read off `Scheduler.next` itself; the dwell ladder lifts both thresholds by
  the same reading bonus (fourth report #3's doc caveat remains the only
  overstatement); the guess and empty-selection rules are as documented; the
  explore-mode pacing resets only on a served NEW card, as specified.

---

## Notes

- The FSRS-6 kernel, `DayBoundary`, `AnkiScheduling` and `RepositorySettings`
  were re-read only where the findings touch them; the fourth report's
  verified-clean list stands.
- Host note for the next pass: the first `testDebugUnitTest` run died with a
  Kotlin ICE and left the build cache serving a one-class
  `compileDebugUnitTestKotlin` output that Gradle kept calling up-to-date
  ("No tests found for given includes" on every filter). `rm -rf app/build
  .gradle` + a `--no-build-cache` run rebuilt all 780 test classes; after that
  the pure-JVM suite is green: `FsrsSchedulerTest`, `FsrsOptimizerTest`,
  `FsrsReplayTest`, `FsrsReplayStreamTest`, `SpacedRepetitionTest`,
  `DayBoundaryTest`, `IntervalFormatTest`, `StatsAggregatorTest`,
  `StudyQueueTest` (CI remains authoritative for the Robolectric ones).
- Scratch probe used for finding #2 was run and removed; the working tree's
  pre-existing WIP (`ImportPreviewClassificationTest.kt` edit,
  `ScratchCommaProbeTest.kt`) was left untouched.
