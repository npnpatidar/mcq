# Plan: calibrating the automatic grade

**Status:** implemented on `main` (phases 1–5). Nothing here is released until the owner
says so. The design it implements is `calibration.md`; this file is the engineering breakdown,
with the code touched and the order to do it in.

**The proposal in one line.** The app already grades from dwell. Let it stop being
one-size-fits-all by tuning **one** number — the Easy cut — against whether the learner actually
remembered the cards afterwards, slowly and reversibly, and only once the measurement is precise
enough to be worth acting on.

**What is being tuned:** exactly one constant, `SchedulerConfig.fastSeconds` (8 s), applied on
top of the per-card reading allowance. Nothing else — not `slowSeconds` (see §0.3), not the dwell
rule, not Again-by-correctness, not the reading allowance, not the override, not FSRS.

---

## 0. How the work is split

Five pull requests, in order. The first three change no grade at all; that is the point of the
split.

| # | Branch | Delivers | Grades change? |
|---|---|---|---|
| 1 | `feature/grade-provenance` | Schema v26, provenance fields, ladder history and evidence blocks, forecast-at-answer, session time | No |
| 2 | `feature/calibration-report` | Grade-level calibration in the existing stats surface | No |
| 3 | `feature/grade-rounding` | Dwell rounding, clock-band consistency | Marginally (boundary cases only) |
| 4 | `feature/ladder-proposal` | A suggested Easy-cut change the learner applies by hand | Only when applied |
| 5 | `feature/ladder-adaptation` | The same rule applied automatically, with rollback | Yes, by design |

**Merge order: #70 → PR 3 → PR 1 → PR 2 → PR 4 → PR 5.** PR 3 branches *from*
`feature/study-clock-colour`, not from `main`, because it changes the band rule that PR #70
introduces. #70 and PR 1 both touch `StudyUiState` and `pullCard()`; landing #70 first keeps
every later rebase trivial.

## 0.1 Constraints the codebase imposes

These are not preferences; they will break the build or silently mis-measure if ignored.

- **Two migration lists.** `AppDatabase.builder()` registers migrations, and
  `MigrationTest.ALL_MIGRATIONS` keeps its own array that every real-file test opens through.
  A new migration must be added to both, the entity to `@Database(entities = […])`, and a DAO
  accessor to `AppDatabase`.
- **Meta-tests pin shapes.** `SettingsSectionsTest` requires exactly five settings sections in
  order — a sixth section fails it, so the new surface goes *inside* the Study card.
  `NoDeadCodeTest` bans production calls to `statsReviews()` and pins the literal shape of the
  clock gate; `StringResourceUsageTest` requires every string to render and be defined.
- **A calibration chart already exists.** `CalibrationData` (binned predicted vs actual, plus
  Brier), `StatsAggregator.countCalibration`, `CalibrationChart` and the `stats-calibration`
  card in `RetentionSection`, fed by `FsrsReplay.predictedRetention`. Phase 2 **extends** that
  surface; it does not add a second chart. A duplicate would be two numbers that disagree.
- **`FsrsReplay` groups by `questionId` alone** (`reviews.groupBy { it.questionId }`), while
  card state is keyed by `(paperId, questionId)` — the entity's own doc says a question reused
  in two papers is scheduled independently. That is a live defect in the existing chart and is
  fixed as part of Phase 2.
- **`FsrsOptimizer` fits all 21 weights jointly**, including `w15` (the Hard penalty) and `w16`
  (the Easy bonus), against the same per-review outcome likelihood the ladder measures. See §7.
- **Compose tests live in `androidTest`**, not `src/test`; there is no Compose rule in the JVM
  source set. This host is aarch64 and cannot run Robolectric SQLite — CI is authoritative for
  anything that opens a real database.

## 0.2 What the learner's own log already says

Measured from `mcqapp-backup (4).zip` → `study.json` on 11 Nov 2026: **920 review rows, 334
cards, 14.1 days** (28 Oct – 11 Nov), with the shipped defaults in force (`fastSeconds` 8,
`slowSeconds` 30, target retention 0.90, `fsrs_parameters` identical to
`DEFAULT_FSRS_PARAMETERS`, so no refit has happened yet). Read-only copy kept outside the repo;
the numbers below are what the plan's constants are now anchored to.

| Quantity | Measured | What it settles |
|---|---|---|
| Raw consecutive pairs | 586 | — |
| Survive the exclusion set of §2.2 | **382 (65%)** | The exclusions do not gut the design |
| Easy / Good / Hard pairs | 297 / 67 / 18 | Good is the bottleneck, not Easy |
| Distinct cards | Easy 213, Good 65, Hard 17 | The 40-card precision gate is already met |
| δ1 (Easy − Good) | **+1.8 pp**, 90% CI [−3.6, +8.0] | No evidence the Easy cut is misplaced |
| δ2 (Hard − Good) | **−0.9 pp**, 90% CI [−12.5, +8.9] | `c2` is unmeasurable at this volume |
| Recall E / G / H | 94.6% / 92.5% / 94.4% | Not monotone in the grade |
| Predicted E / G | 87.5% / 87.2% | — |
| Common bias | **+6.7 pp** | Justifies the adjacent contrast (§2.1) |
| Easy share of reviews | 592/920 = **64%** | `c1` fires on two thirds of answers |
| Easy outcome gap | median **7.4 d**; 202 in 1–7 d, 91 in 8–29 d, **0 at ≥30 d** | Kills the maturity gate (§4.1) |
| Good outcome gap | median 2.3 d | A day-based gate filters classes differently |
| Implied reading allowance | median 14 s (IQR 9.5–20) from 97/97 consistent card bounds | `c1` sits at ≈22 s of dwell |
| Reviews within ±1 s of that cut | **3.15%** | One step reclassifies ≈1.6% of reviews |
| Lapses (Again after REVIEW) | 27/439 = **6.2%** | Close to the 1 − 0.90 the scheduler targets |
| Pairs per day | 99, 81, 51, 27, 10, 6, 4, 7, 15, 0, 12, 0, 0 | Pair generation decays; ≈3/day now |
| Follow-up (label has an outcome) | Easy 57%, Good 62%, Hard 61% | Not selection — due-ness (§2.2) |
| First-exposure grade mix | Easy 168, Good 53, Hard 25, Again 88 (of 334) | Half of first exposures read Easy |
| Clean-pair label mix | Easy 78%, Good 18%, Hard 5% | Good evidence is scarce by construction |

Three consequences run through the whole document:

1. **A 1 s step is invisible at the deck level** (1.6% of reviews), so the question "did the change
   work" can only be answered on δ, not on lapses. §5.
2. **`c2` cannot be calibrated** — 18 pairs and a ±12.5 pp interval. §0.3.
3. **Pairs are generated by revisits, not by reviews**, and they decay as FSRS spaces cards out.
   That is why the readiness gate is precision-based rather than a pair count. §4.1.

**Limits of this measurement.** One learner, 14 days, no refit yet, and 277 of the 920 rows
predate the correctness and state columns (all of 28–30 Oct), so 47 pairs are lost and the
`stateBefore == NEW` rule is inert for those rows. Critically, **`gradeSource` does not exist
before v26**, so overrides cannot be separated from automatic labels in this history: the δ
figures above are contaminated by exactly the rows §2.2 will exclude. They are a sanity anchor,
not the number the controller will see.

## 0.3 Scope decision: one cut in v1, the other reported

`c2` (`slowSeconds`, 30 s) stays exactly where it is. The Hard class produced 18 usable pairs
across 17 cards in fourteen days, and the contrast's 90% interval spans 21 points. Two further
facts make this structural rather than temporary:

- Hard is **5%** of clean labels. Its evidence accumulates roughly fifteen times slower than
  Easy's.
- Moving `c1` changes the Good group that `δ2` is measured against, so the two contrasts are not
  independent estimators even in principle.

So v1 tunes `c1` and *reports* `c2` — the per-grade table, the interval, and an explicit "not
enough evidence to judge" line. The Settings rows stay editable by hand. If Hard evidence ever
accumulates (a much larger bank, or a learner who routinely takes 30 s+), a later version turns
`δ2` into a second policy; the schema and the block table already carry what it needs.

---

## 1. Phase 1 — record what the app decided (`feature/grade-provenance`)

### 1.1 Seven new columns on `review_log` (schema 25 → 26)

In `Entities.kt`, extend `ReviewLogEntity`:

| Column | Type | Meaning |
|---|---|---|
| `proposedRating` | INTEGER, null | The grade the app proposed before any edit. Null for classic mode, which has no proposal |
| `gradeSource` | TEXT, null | `AUTO`, `EDITED` or `CHOSEN`. Null on pre-v26 rows |
| `ladderVersion` | INTEGER, NOT NULL, default 0 | Which cut-points were in force. 0 = written before versioning |
| `predictedRecall` | REAL, null | FSRS retrievability computed immediately before this answer |
| `fsrsFitVersion` | INTEGER, null | Which weight set produced that prediction. 0 = shipped defaults |
| `readingAllowanceSeconds` | INTEGER, null | The allowance that was subtracted. Null in classic mode, where no allowance is applied |
| `wasSkipped` | BOOLEAN, null | The attempt was submitted with an empty selection |

`wasSkipped` exists because `Scoring.isCorrect(empty, key)` is false, so a skip is currently
indistinguishable from a wrong answer in the log. Skips are not retrieval failures and they
cluster by ladder position, which biases the very quantity the calibration measures.

`gradeSource` and `proposedRating` are near-redundant; they differ in exactly one case — the
learner changes the grade and then changes it back, where saved equals proposed but the source
is still `EDITED`. Keep both, and pin that case with a test, or a later "simplification" will
drop one and mislabel those rows.

The historical log is the argument for `gradeSource` existing at all: 64% of the learner's
reviews are grade Easy, and with no provenance column there is no way to tell how many of those
the app chose and how many the learner overrode. Every δ quoted in §0.2 is contaminated by that
ignorance.

### 1.2 Two tables: versions and evidence blocks

**`ladder_versions`** — one row per change:

```
id, easySeconds, slowSeconds, effectiveFrom, reason, previousId, blockId, policyVersion
```

`reason` ∈ `prior`, `manual`, `proposal`, `automatic`, `rollback`. `blockId` is nullable and
points at the evidence block that triggered the change (null for `prior`, `manual` and restore
seeds). `policyVersion` records which build of the decision rule made the call.

**`ladder_blocks`** — one row per *closed* evidence block, **including blocks that decided
nothing**:

```
id, startAt, endAt, ladderVersion, fsrsFitVersion, policyVersion,
easyPairs, easyCards, goodPairs, goodCards, hardPairs, hardCards,
delta1, delta1Low, delta1High, delta2, delta2Low, delta2High,
followUpEasy, followUpGood, outcome, decidedAt
```

`outcome` ∈ `no-change`, `raise-c1`, `lower-c1`, `insufficient-evidence`, `frozen-refit`.

**Why two tables rather than evidence columns on the version row.** The decisive case is §4.4's
safety valve: it fires on **two consecutive post-change blocks**, and neither of those blocks
produces a version row. A table keyed on versions cannot store the inputs to its own rollback
rule. The same applies to every block that declines to act — and at this learner's volume that
will be nearly all of them, so a version-keyed schema would record the controller's null
decisions nowhere and a stalled controller would be indistinguishable from a working one.

The cardinality is the whole argument: a version can exist with no block (`prior`, `manual`,
restore) and a block can exist with no version (no change). Two independent 1:0..1 relations is
two tables. The cost is ~13 rows a year.

`policyVersion` on both exists so a later re-analysis can tell "the statistics changed" from
"the data changed". Without it, re-running the estimator over old blocks silently mixes two
computations.

**`LadderStore` is the only writer.** Today the cut points have two production writers that
would bypass it: the Settings rows (`StudySchedulerSection` → `SettingsViewModel.updateSchedulerConfig`
→ `SettingsStore.setSchedulerConfig`) and `resetSchedulerConfig`. Route both through
`LadderStore.setCuts(...)`, and add a test that editing a cut in Settings writes a version row.
Without this, `ladderVersion` is untrustworthy from the first manual edit — the exact failure
the table exists to prevent.

**One atomic commit.** The cuts and the version number live in the same DataStore `edit {}`,
under a `Mutex`: one atomic write, then the history row. A crash between them leaves a history
row with no references, never a value without a version. Reading never depends on the row
existing; a missing row is repaired from the preferences on next read. Seeding is guarded by
the presence of the version key, not by "the table is empty", so two concurrent first reads
cannot both insert a `prior` row.

**Restore seeds a version.** `SettingsStore.restore` clears and rewrites preferences, so a
restore must write a `prior` version row from the restored cuts and continue numbering from
`max(id) + 1`. Restored evidence blocks are kept but marked as belonging to a prior epoch —
their statistics were computed against rows that may no longer exist.

### 1.3 A `study_sessions` table

`id, paperId, startedAt, endedAt, activeSeconds`, with a cascade-delete foreign key to
`papers` and an index on `(paperId, startedAt)`. Without the cascade, deleting a paper leaves
orphan hours in the "lapses per hour" denominator while `review_log` (which cascades) loses the
lapses — the metric would improve when a library is tidied.

**Lifecycle.** The obvious hook is wrong: `finishSession()` is only reached when the learner
ends the sitting, while `finishEmpty()` — the path taken when the queue runs dry, which is the
normal way a session ends — sets `finished = true` without persisting anything, and `stop()`
early-returns once finished. There is also no `onCleared()`. So: one private `endSitting()`
called from both, plus a best-effort flush in `onCleared()`, idempotent, and a test that drains
the queue and asserts a row exists. Zero-review sittings are not recorded, so opening the study
screen and backing out cannot dilute the denominator.

### 1.4 Thread provenance through the three write paths

`next()`, `stop()` and the classic-mode `grade()` all write reviews. They must pass the proposed
and saved grade separately, the source, and the allowance.

Today the proposal is overwritten in place: `check()` sets `pendingGrade` and `changeGrade()`
rewrites the same field, so the app's own output is destroyed. Add `StudyUiState.proposedGrade`
(set at Check, never touched by `changeGrade`, reset wherever `pendingGrade` is reset) and
`StudyUiState.readingAllowanceSeconds` — the allowance is currently a local in `check()`, so
`next()` and `stop()` cannot see it. Classic `grade()` passes `proposedRating = null`,
`gradeSource = CHOSEN`, allowance null.

### 1.5 Compute the forecast from the session's own config

`StudyStore.recordStudyReview` currently builds its scheduler from a fresh
`settings.schedulerConfigNow()`. The view model grades from a snapshot taken in `load()`. If the
two disagree — a manual cut edit, a refit, a restore — the row records a version and a forecast
that were never used to grade it. So `recordStudyReview` takes the `SchedulerConfig` (or at
least `ladderVersion`, `fsrsFitVersion`, `fastSeconds`, `slowSeconds`) from the caller and reads
nothing itself.

Two null rules for the stored forecast:

- **New cards**: store null, not 0.0. `retention` returns 0.0 for a card with no memory, and a
  stored 0.0 is indistinguishable from a genuine near-zero prediction — it would defeat a
  `predictedRecall IS NOT NULL` filter and drag any mean toward zero.
- **Imported cards**: `elapsedDaysSinceLastReview` returns the card's own `intervalDays` when
  `lastReviewedAt == 0` and stability is positive, so the "prediction" is the schedule's own
  assumption repeated back. Store null and let Phase 2 exclude it.

### 1.6 Version the FSRS weights

The forecast is model-dependent, so a block spanning a refit is not interpretable. Add
`SchedulerConfig.fsrsFitVersion` and one private `writeWeights(parameters)` that is the only
place `fsrs_parameters` is ever stored, bumping the version inside the same `edit {}`.
`setFsrsParameters`, `setSchedulerConfig`, `FsrsTuner.optimize`, `FsrsTuner.restoreDefaults`
and `SettingsStore.restore` all route through it. A reset to defaults writes version 0 (or a
distinct reset generation), not a bumped fit number: rows must not claim a fit they were not
scheduled under.

Note a live bug this touches: `StudyViewModel.enableNewCards` writes the whole config from the
session-start snapshot, which reverts a fit performed mid-session. That is the same class of
defect `SettingsViewModel.updateSchedulerConfig` was already fixed for, and it must be fixed
here rather than inherited by the version counter.

### 1.7 Migration, schema and backup

- Bump `AppDatabase` to 26, add `MIGRATION_25_26` (additive nullable columns; `ladderVersion`
  as `INTEGER NOT NULL DEFAULT 0`, with `@ColumnInfo(defaultValue = "0")` so the exported schema
  matches the DDL), register in **both** migration arrays, add both entities to `@Database`,
  and their DAOs.
- Export `app/schemas/…/26.json` so the column and table diff is reviewable.
- New index `(paperId, questionId, reviewedAt)` on `review_log`: pairing reads a card's history
  in order, and today only `(questionId, reviewedAt)` and `(paperId, reviewedAt)` exist, so that
  scan would sort into a temp B-tree on every page. `ladder_blocks` wants
  `(startAt)`; `ladder_versions` wants `(effectiveFrom)`.
- `MigrationTest` gains `createV25WithSeed()` that **replays the production migrations** to v25
  (the class doc already says hand-built schemas cannot exercise a migration), seeds a review
  row, then applies `MIGRATION_25_26` and asserts the old row survives with
  `ladderVersion == 0` and a null `gradeSource`.
- **Backup**: `McqBackupFormat.VERSION` goes to **2**. The row fields are genuinely additive —
  every new field needs a default and `ladderVersion` is `Int = 0` in the DTO with `?: 0` in
  the mapper, or an old `study.json` fails to decode. But three new tables are a layout change,
  and `VERSION` is documented as bumping for exactly that. Readers accept any version ≤ theirs,
  so v1 backups still restore; a v2 backup loaded by an old build is refused with a clear
  message instead of being silently stripped of provenance.
- New tables need **five** plumbing edits each, not one: `BackupSource.collect`, `writeStudyRows`
  (which hand-writes the keys, separately from `write()` via the serializer), the streaming
  sink, `readStudy`, and both restore paths (full and streaming). Add a streaming round-trip
  test for the three tables — the existing `rowDump` helper does not dump them, so it would pass
  on a restore that dropped them. `wipeAllTables()` is schema-derived and needs no change.

**Tests:** pure-JVM for the source rules; Robolectric for the migration, the DAO round-trip and
the backup round-trip (CI only on this host).

---

## 2. Phase 2 — show the evidence (`feature/calibration-report`)

### 2.1 The estimand: adjacent contrasts, not per-grade error

For a review pair, let `r` be the outcome (0 or 1) and `p` the predicted recall made
immediately before the answer. Define:

```
δ1 = mean(r − p | label Easy) − mean(r − p | label Good)
δ2 = mean(r − p | label Hard) − mean(r − p | label Good)     [reported only, §0.3]
```

**Why adjacent contrasts and not `error(grade) − 0`.** The grade group is *defined by* the cut
being tuned, so a per-grade error against zero is a descriptive property of the current
partition, not an estimate of what moving the cut would do. The learner's own log shows why this
matters: observed recall is **+6.7 pp** above predicted across every grade. A per-grade error
would attribute that common bias to all three grades and, after any "subtract the mean"
correction, could flip the sign of the one difference that matters. A common component cancels
in the contrast and never has to be estimated.

**Direction** — worth a unit test per cell, because the policy will encode this table:

| Contrast | Reading | Move |
|---|---|---|
| δ1 ≤ −5 pp | Easy underperforms Good: the Easy window is too wide | **Lower** `c1` (fewer Easy) |
| δ1 ≥ +5 pp | Easy outperforms Good: the window is too narrow | **Raise** `c1` |
| δ2 either side | Hard is measured but never moved in v1 | Report only (§0.3) |

`c1` is `fastSeconds` (Easy is `dwell in 1 until c1`). Every output goes through
`SchedulerConfig.sanitized()`.

**State the honest prior.** In this learner's data the grade is *not* monotone in recall
(Easy 94.6%, Good 92.5%, Hard 94.4%), and δ1's interval straddles zero. The most likely correct
behaviour of the policy for months is **no change**, and the report should be built to say so
plainly rather than to look like a dashboard that is failing to produce a result.

### 2.2 Pairing, exclusions, and the defects to fix

A pair is: **label from review *t***; **forecast and outcome from review *t+1***. Ordered by
`(reviewedAt, id)` within **`(paperId, questionId)`** — not `questionId`. Card state is keyed
by paper, and the entity's own doc says a question reused in two papers is scheduled
independently; pairing across papers would compare a label against a different card's state.
`FsrsReplay` has this bug today and Phase 2 fixes it.

Exclude a pair when any of these holds, each with its own test:

- label source is not `AUTO` (overrides and classic mode are not fitting samples);
- label rating is `AGAIN` — Again is decided by correctness, guess or skip, never by the ladder;
- label `wasGuess` is true, or `wasSkipped` is true;
- **either** row has `stateBefore == NEW`;
- either row's `predictedRecall` is null;
- outcome `wasCorrect` is null (pre-v19 and seeded rows).

On this learner's log that set removes 35% of raw pairs (586 → 382) — measured, not assumed. It
is the cheapest part of the design and it should not be relaxed to buy sample size.

The "either row is NEW" rule does double duty: it excludes new cards at the label *and* catches
content edits, because editing a question deletes its card state, so the next review is graded
as New. No content-hash column is needed for that. It also removes 155 same-day learning-step
pairs; only 9 survive, so the rule is doing real work.

**Follow-up is a gate, and it must be counted against due-ness.** Counting "did the label's next
review happen yet" flatters grades with short intervals and punishes the ones with long intervals
— precisely Easy. Measured: follow-up is Easy 57%, Good 62%, Hard 61%, but the median Easy
outcome gap is 7.4 days, so at fourteen days of history most of those are simply *not due*. So:

- a label whose next review is not yet due is **pending**, not missing;
- follow-up is computed over `due + grace` only;
- a grade fails the gate when its follow-up is below 70% *among due labels*, or when two
  compared classes differ by more than 10 points.

**Report the first-exposure stratum, and only report it.** Half of this learner's first exposures
graded Easy (168 of 334), 26% were Again. Two independent reasons it cannot enter the fit: a new
card has no forecast (`predictedRecall` is null by §1.5, so the estimand has no `p`), and
`FsrsOptimizer` fits `w0..w3` from exactly those outcomes — feeding them to the cut as well is
double-dipping one observation set. Show the stratum as *"this is what the FSRS initial-weight
fit consumes; it is not evidence about `c1`"*, with per-grade counts and the raw first-review
recall against the 0.90 the schedule assumed.

### 2.3 Intervals, and the sample sizes they actually need

Card-clustered, because a card's reviews are not independent facts. Resample **cards** within
each compared class, recompute δ in every resample (jointly, so the covariance between Easy and
Good is kept), 5,000 resamples, seeded RNG.

| Distinct cards per class | Interval | Decision allowed? |
|---|---|---|
| ≥ 40 | BCa percentile, 90% | Yes |
| 20–39 | t-interval on card-level means | Report only, labelled descriptive |
| < 20 | None | No |

Degenerate cases (all passes or all failures in a class) get an exact/Beta interval or no
interval at all — a percentile bootstrap on all-identical outcomes falsely excludes zero.

**The minimum detectable effect is now measured, not assumed.** On this learner's 382 pairs the
90% half-width is **5.8 pp**; the scaling with volume is:

| Pairs | Half-width |
|---|---|
| 382 (today) | 5.8 pp |
| 500 | 5.1 pp |
| 800 | 4.0 pp |
| 1,000 | 3.6 pp |
| 1,500 | 2.9 pp |

So the 5 pp floor is *not* inert at realistic volumes — it becomes binding at ~500 pairs. That
number belongs in the plan and the UI, because it defines when a decision is even representable.
Precision is governed by the smaller class: at a 4.4:1 Easy:Good ratio, Good's card count is the
binding constraint, which is one more reason a cut that moves Easy toward Good also makes the
next decision easier to measure.

### 2.4 Blocks are fixed windows; decisions pool an epoch

Two different objects, and conflating them is how a block design goes wrong:

- **An evidence block** is a fixed 28-day calendar window of label dates. It is scored when its
  labels' outcomes have had a 90-day horizon to arrive, and it closes early on a ladder-version
  change or a weight refit. Every closed block writes a `ladder_blocks` row, whether it decided
  anything or not. Blocks are the audit trail and the rollback unit.
- **An epoch** is the span since the current ladder version took effect. All eligible pairs in
  the epoch are pooled for δ, and the readiness gate of §4.1 applies to the epoch, not to one
  block.

**Why pooling is not optional here.** Counting observed pairs per block would select short
intervals — a card with a 2-day interval produces a pair long before a card with an 8-day
interval does, so Easy would be systematically under-sampled by a count-based block. Fixed
calendar windows avoid that. But at the measured rate (~3 pairs/day in steady state) a 28-day
block holds ~84 pairs, i.e. about 15 Good cards — below the 40-card precision gate. A controller
that only ever looked at one block would therefore never be allowed to act, no matter how long
it ran. Pooling pairs within an epoch keeps the calendar definition (no length bias) while
letting the estimate reach precision.

The cost is honest: a decision lags its labels by up to 90 days, and at this learner's volume
the first decision is months away. That is the price of measuring recall rather than speed.

### 2.5 Reading it out

Extend `CalibrationData` with the per-grade table and the contrast, and render it in
`RetentionSection` beside the existing predicted-vs-actual chart — inside the Study card, not as
a sixth section. Reuse the stored `predictedRecall`; do **not** re-derive it with `FsrsReplay`,
because a refit would then silently rewrite history's forecasts.

Add, as diagnostics only and never as triggers: Brier score per grade, the predicted-recall
bins that already exist, the follow-up table split into not-yet-due / overdue / reset / edited,
the Hard contrast marked "not enough evidence until ≥40 cards", and the clock-reactivity measure
(share of Easy answers whose residual sits within two seconds below the cut, plus the lower
quartile of residuals over time). The colour is display-only by premise, but an honest learner
can hurry to keep it green, and that moves dwell exactly where the ladder is reading it. The
clock is already toggleable, so a visible/hidden block pair can measure the effect if the
diagnostic ever fires.

**Cost and threading.** Only closed blocks and the current epoch are bootstrapped — a few hundred
pairs is cheap; the whole log is not (at a million rows, 2,000 resamples over 800k pairs is 1.6
billion operations). Page by card with a keyset query at a page size that keeps an `IN (...)` list
safe (`FsrsTuner` uses 500), aggregate per card, keep only the epoch's pairs, run the statistics
on `Dispatchers.Default` with a cancellable job, and key the cache on
`(ladderVersion, fsrsFitVersion, blockEnd, max(reviewedAt))` so an open block cannot serve a
stale answer forever.

**Tests:** pure-JVM for pairing, exclusions, δ direction, bootstrap determinism, degenerate
cases, block and epoch assignment; Robolectric for the DAO/`CalibrationStore` against a real
database; `androidTest` for the Compose empty state.

---

## 3. Phase 3 — two correctness fixes (`feature/grade-rounding`, from #70)

### 3.1 Rounding bias

`easyThresholdSeconds` rounds the allowance **up** with `ceil`, while dwell is truncated
**down** — and truncated **per segment**, then summed. Every scroll-out, background, screen-off
or pause costs up to another second, so a card answered after four interruptions can lose four,
against an 8 s Easy budget.

Fix: make milliseconds the source of truth. `questionElapsedMillis` accumulates; the display
converts with truncation (a clock reading 3 s then 4 s is correct); the **graded** dwell is
frozen at Check with round-half-up and stored. Everything that reads dwell must move together:
`questionElapsedSeconds` and the live getter, `publishDwell`/`liveDwell`, `check()`,
`changeGrade()` (must not recompute), `next()`, `grade()`, `stop()`, `pauseQuestionClock()`,
the three reset sites, the session summary's `questionSeconds`, the DB's seconds column, and the
existing `StudySummaryTimesTest` / `StudySessionStateTest` assertions. The allowance keeps its
`ceil`: that padding is deliberate, and the bias worth removing is the accidental one.

### 3.2 Zero seconds means two things

#70's clock enters its Easy band at 0 s; `inferGrade` treats 0 s as untimed and returns Good.
The branch pins the difference deliberately, so this changes one test line and one comment.

Two options, and the second renders better: make `dwellBand` the same rule as `inferGrade`
(amber for the first second, then green), or add a **neutral** band at 0 s — no colour claim
until the first second is banked — and require `dwellBand == inferGrade` for every dwell ≥ 1.
Add a property test over every dwell value asserting the colour matches the grade a Check at
that instant would produce. That is the guarantee #70's own comment claims, and the one the
calibration depends on: a card's colour must never disagree with the label it will receive.

---

## 4. Phase 4 — propose, confirm (`feature/ladder-proposal`)

Two separate questions: **may the app tune at all** (readiness), and **should it move the cut
now** (the guards).

### 4.1 Readiness — precision, not volume

| Gate | Value | Why |
|---|---|---|
| Volume floor | ≥ 300 completed pairs in the epoch | Stops a technicality becoming a decision on 40 observations |
| Precision | ≥ 40 distinct cards **per compared class** | Below this, no interval-based decision is allowed (§2.3) |
| Resolution | 90% card-clustered half-width ≤ 5 pp | The gate that actually decides when the estimate can support a 5 pp threshold |
| Baseline | no refit inside the epoch | The forecast is the yardstick; a refit moves it |
| Calendar floor | 28 days | A minimum, not the gate |

**Why the 1,000-pair floor is gone.** It was borrowed from `FsrsOptimizer.MIN_REVIEWS`, which
fits **21 weights**. This contrast has two parameters, and 382 pairs already pin it to ±5.8 pp.
The floor was also premised on pairs accumulating steadily, which measurement contradicts:

```
pairs/day:  99  81  51  27  10   6   4   7  15   0  12   0   0
```

The flood is the first re-review wave; after that, pairs need a *revisit of the same card*, and
FSRS sends those out at 8 → 66 → 400 days while every first exposure of a new question adds
reviews and no pairs. At the recent ~3/day, 1,000 pairs is roughly **a year**. The volume floor
would have kept a feature switched off for twelve months to reach precision that is already
available at a quarter of that count.

**The maturity gate is gone, and the measurement is why.** The previous draft required ≥30 Easy
pairs whose outcome had elapsed ≥30 days. Measured: **0 of 297** Easy pairs qualify, and the
Easy outcome gap has median 7.4 days. Worse, a day threshold filters the compared classes
differently — any threshold between 8 and 29 days keeps 91 Easy pairs and **2** Good pairs —
which is selection on the very grade being compared. The estimand already conditions on predicted
recall *at the actual elapsed time*, so a 7-day outcome at p = 0.88 is as informative as a 60-day
one. There is nothing for the gate to buy.

**Do not require an FSRS fit.** Making adaptation depend on pressing "Optimize FSRS parameters"
is a hidden dependency between features and silently disables calibration for anyone who never
touches it. A block containing a refit is frozen; a block that post-dates one may act; with no
fit at all, adaptation runs on the floor rules and the report says "predictions from default
weights".

**What is not gated:** recording (from review one — data is not retroactive), the report (from
the first pairs, with sample sizes stated), and manual cut changes.

**The learner-facing line**, reusing the optimizer row's string pattern:

> **Automatic grading** — learns your Easy cut-off from whether you actually remembered.
> Not adapting yet: 382 of 300 pairs, 213 of 40 cards, precision ±5.8 of 5 points.
> *The Hard cut is shown but not tuned: 18 pairs is not enough to judge it.*

That third number is the honest one and the one that will move slowest: precision improves with
the square root of pairs, so ±5.8 → ±5 needs about 500 pairs, and ±4 needs about 800.

### 4.2 The decision guards

New `domain/LadderPolicy.kt`: given an epoch, either "no change" or one proposed step on `c1`.
All of these hold **in addition** to §4.1, and each is a test:

| Guard | Value |
|---|---|
| `abs(δ1)` | ≥ 5 pp **and** ≥ the epoch's minimum detectable effect |
| Interval | 90% card-clustered interval excludes zero |
| Follow-up | ≥ 70% among due labels, and between-class difference ≤ 10 pp |
| Step | `max(1, round(0.10 × c1))` seconds, capped at 2 s while `c1` is within 3 s of default |
| Frequency | one step per closed block; never two |
| Bounds | `c1` in 3–60 s, and `c1 ≤ slowSeconds − 5`; output always `sanitized()` |
| Freeze | no change inside an epoch containing a refit, or while a refit is pending |
| Record | the applied step, δ1, its interval, pairs, cards, and the block row that triggered it |

The measured band mass makes the step size concrete: **3.15% of reviews sit within ±1 s of the
current cut**, so one step reclassifies about 1.6% of reviews. A larger step would not be better
evidence — it would just be a larger unmeasured change. Precision comes from waiting, not from
stepping harder.

### 4.3 The proposal, and the controls

The sentence has to match the sign, which an earlier draft got backwards:

> Easy looks generous: 382 pairs across 213 cards came back **2 points less** often than
> predicted, and 6 points less often than Good — but the interval spans −4 to +8, so this is not
> a change worth making yet.

Apply writes a version with `reason = proposal` and the triggering `blockId`. Dismiss records a
block row with `outcome = no-change` and a note, so a dismissed proposal is still evidence that
the controller ran. Reset writes a version at the prior values with `reason = rollback`, one step
back along `previousId` — it never deletes history, and repeated resets walk the chain rather
than jumping to the original.

`c2` never appears in a proposal in v1. Its row in the report reads "not enough evidence to
judge", with the count and the interval, and no button.

### 4.4 Rollback is a pre-registered comparison, not a reflex

"If the next block does not improve the triggering error" is a regression-to-the-mean trap: the
triggering block was selected for an extreme value, so the next block "improves" most of the
time even when the change did nothing. Replace it with:

- **safety valve:** if two consecutive post-change blocks show δ1 moving the wrong way beyond
  the block's minimum detectable effect, restore the previous version automatically. This is
  the rule that requires `ladder_blocks` to exist: both of those blocks decide nothing, so
  neither owns a version row.
- **validation:** a pre-registered non-inferiority comparison of the safety outcome (§5) in a
  balanced block order (§6) — that, not the next block, is what keeps a change.

Nothing is automated until Phase 4 has produced at least one applied change and two closed
post-change blocks.

---

## 5. Phase 5 — automate, with rollback (`feature/ladder-adaptation`)

Same policy and the same readiness gate, applied without asking. Additional rules: evaluation at
session end, never mid-session; never during a pending refit; every application writes a version
row and a visible line; the §4.4 safety valve runs first; **default off**.

**This is a safety decision, not an efficacy test.** No exam outcome exists anywhere in the log;
every candidate metric is retention-at-review. So the plan makes no exam-performance claim, and
§5 exists to bound harm, not to prove benefit.

**The decision quantity is δ1 re-estimated**, not a deck-level rate. After a change, the epoch
resets; the change is kept if the new epoch's δ1 is inside the 5 pp band, and walked back if it
is outside in the wrong direction for two blocks. That is the only quantity a 1 s relabelling of
1.6% of reviews can move.

**Why no superiority bar on lapses per 100.** Three independent reasons, all checkable against
this learner's log:

1. The baseline is a *setting*, not a measurement. Intervals are chosen so predicted recall at
   the due date is 0.90, and the observed lapse rate is 6.2% — a calibrated ladder must keep
   producing something close to that. A 20% "improvement" would mean running above target, which
   is a scheduler change, not a ladder improvement.
2. A correct correction moves lapses in **opposite directions**: lowering `c1` relabels
   Easy→Good, which shortens some intervals and raises lapses; the corresponding correction at
   `c2` lengthens others and lowers them. The two largely cancel.
3. One step touches 1.6% of reviews. Even a 10 pp recall difference on those cards gives
   **0.16 pp** on a 6.2% baseline — 2.6% relative. A 20% bar would need roughly eight perfectly
   correct steps.

So the deck-level metrics are **guardrails** with explicit margins, not success criteria:

| Guardrail | Margin |
|---|---|
| Lapses per 100 graduated reviews | no worse than **+20%** relative |
| Study minutes per review (from `study_sessions`) | no worse than **+10%** |

A lapse is `rating = AGAIN AND stateBefore = REVIEW` (matching `Fsrs.kt`), with new and learning
reviews excluded from numerator and denominator. Both guardrails are also **policy contrasts, not
fixed characteristics** — the ladder changes when cards graduate and how often they are due, so
both numerator and denominator move with the policy. That is fine for a balanced comparison and
not fine as a control signal.

---

## 6. Phase 6 — the evaluation nobody can skip

The pre-registration, block schedule, analysis formula and stopping rule live in
`ladder-evaluation.md`.

**ABBA, not ABAB.** With A in blocks 1 and 3 and B in 2 and 4, A's average calendar position is
lower than B's, so a learner who improves over time makes B look better. Use a balanced order
with at least six fixed blocks of three or more weeks.

**Carryover is reduced, not neutralised.** Conditioning on the scheduler's prediction controls
for the current stability, which is where most of the previous ladder's effect lives — but it
also removes the part of the effect that operates through stability, and the prediction is a
mediator of the current interval. It does nothing about calendar drift, content mix, or exam
proximity. The comparison therefore freezes FSRS weights, target retention, the reading
allowance and clock visibility for the duration, and contrasts an **unadjusted** policy outcome.

**Pick one analysis unit, not two.** The previous draft asked for "a card random effect and a
block fixed effect". Those are collinear: the policy is constant within a block, so with three
blocks per arm the block indicator *is* the treatment indicator. Choose the within-card paired
contrast (same cards, A block versus B block, card as the cluster) and pre-register it. If a
mixed model is used instead, the block term must be dropped, and then the inference unit is
three blocks per arm — which gives an MDE of 26–35% relative and retires any bar below it.

**Pre-register ten things** before block one: primary outcome and formula; the cost outcome and
its non-inferiority margin; block count, length and dates; order; the frozen settings above; card
inclusion (graduated before the trial, no content edits, no new cards); the censoring and
follow-up rule; the analysis model (one of the two above, named); the stopping rule; and the
exact code version.

Reported as suggestive at n = 1, with the interval. Not a randomised trial with allocation
machinery — that is a large apparatus for a small lever.

---

## 7. The second controller nobody wired up

`FsrsOptimizer` fits **all 21 weights jointly** with Adam, including:

- **`w15`**, the Hard penalty, and **`w16`**, the Easy bonus — the exact per-grade multipliers on
  stability growth that a cut change is trying to influence. Its loss is a per-review outcome
  likelihood, i.e. the same signal δ1 is computed from.
- **`w0..w3`**, the per-grade initial stabilities, from first-exposure outcomes (`:270-276`).

So the ladder and the optimizer are two controllers on one residual, and freezing refit blocks
stops them acting *simultaneously*, not the feedback: tighten `c1`, then the next refit absorbs
whatever per-grade bias is left, and the controller sees a clean signal it did not produce.

Three rules follow, and they are cheap:

1. **A refit ends the epoch.** Not just the block — the epoch's δ is computed against one weight
   set, and `fsrsFitVersion` changes invalidate it.
2. **A pending refit blocks a proposal**, not just an automatic change. Otherwise a proposal can
   be applied and then immediately absorbed.
3. **Report `w15`/`w16` movement next to δ1.** If a refit moves the Easy bonus by a large step in
   the same direction the ladder just moved, the two are trading off and neither should be
   credited. Burying this is how a future reader concludes the ladder "worked" when the optimizer
   did the work.

This caps what any adoption test can claim, and §5 says so out loud.

---

## 8. Decisions: settled, and still open

**Settled by the owner (11 Nov 2026):**

1. **One cut in v1.** `c1` is tuned; `c2` is reported and never moved (§0.3).
2. **Readiness is precision-based**, not a 1,000-pair count: ≥300 pairs, ≥40 cards per class, and
   a 90% half-width ≤ 5 pp (§4.1).
3. **The maturity gate is deleted**; the exclusion of new-card labels is kept (§2.2, §4.1).
4. **No deck-level superiority bar**; δ re-estimation decides, lapses and cost are guardrails
   (§5).
5. **Recording, the report and manual cut edits are never gated.**
6. **Coloured clock stays display-only** (owner decision, PR #70) and is paired with the 0 s fix
   (§3.2).

**Still open:**

1. **Block evidence table** — now settled in favour of a separate `ladder_blocks` (§1.2); listed
   here only because it changes the schema size and the owner may want it deferred to Phase 4.
   Deferring means versions created by a manual edit cannot reference evidence; the plan's
   recommendation is to land it with Phase 1.
2. **Reading the allowance per review.** Recommended: yes — one int, and it makes the residual
   reproducible. It is also the only way to recover the cut's position in dwell terms later, as
   §0.2 did by inference.
3. **Where the ladder lives.** Preferences with a history table (recommended now), or the table
   authoritative with the preferences removed.
4. **Study-time denominator.** `study_sessions` is real study time; summing dwell is "question
   time". Recommended: guardrails on study time, question time shown alongside.
5. **Classic-mode learners.** No automatic label means nothing to calibrate. Recommended: show
   the report, never adapt.

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| `ladderVersion` is wrong from the first manual edit | All writers routed through `LadderStore`; test that a Settings edit writes a version row |
| Row records a version or forecast it was not graded under | Config passed into `recordStudyReview`; no config read inside the store |
| Migration damages an existing install | Both migration arrays, additive columns, migration replayed from v25, exported schema diff |
| Backup silently drops provenance | `VERSION = 2`; five plumbing sites per table; streaming round-trip test |
| Report is slow or stales on a large log | Epoch-only bootstrap, keyset paging at 500, `Dispatchers.Default`, cache key includes `max(reviewedAt)` |
| Session time is never recorded | `endSitting()` from both exit paths plus `onCleared()`; test that drains the queue |
| Automation acts on thin data | Precision gate (cards + half-width), 5 pp floor ≥ MDE, proposal-only stage |
| A block too small to decide and no way forward | Epoch pooling (§2.4); every block row recorded so "insufficient" is visible |
| False trigger across many blocks | 5 pp floor plus MDE, balanced blocks, two-block safety valve |
| Optimizer absorbs the ladder's signal | Refit ends the epoch; pending refit blocks proposals; `w15`/`w16` reported beside δ1 (§7) |
| Clock makes learners hurry, looks like a bad cut | Reactivity diagnostic; clock-hidden block pair if it fires |
| The feature never acts and looks broken | UI states the three numbers that are short and why; "no change" is the expected outcome for months |
| A future reader loosens the guards because nothing happened | §2.1 and §4.1 state that no-change is the correct result at this volume |

---

## 10. Not in this plan

No anti-gaming machinery of any kind (the learner is assumed honest); no wall-clock timing; no
replacement or bake-off for FSRS; no per-topic ladders; no card-level randomisation; no
reading-speed multiplier in v1 (not estimable at these sample sizes); no population estimates; no
change to the coloured clock as a display decision; no tuning of `c2` in v1; no rewriting of past
reviews; no destructive-migration fallback.

---

## 11. Definition of done, per phase

1. Branch off the right base (`main`, except PR 3 which branches from #70), draft PR, owner review.
2. `assembleDebug` and `testDebugUnitTest` green; Robolectric and Compose cases verified on CI.
3. Schema diff committed; migration test green; both migration arrays updated.
4. APKs refreshed in `/sdcard/Download` after each CI run.
5. For Phases 1 and 2: no change to `inferGrade`, `autoGrade` or `easyThresholdSeconds`. Note
   that `SchedulerConfig` itself *does* change (it carries the two version fields), so the check
   is those three functions, not "the file is untouched".

## 12. Docs to land with this

`calibration.md` needs: the adjacent-contrast estimand; the direction table; the corrected sign
in the worked example; the precision gate replacing the 1,000-pair floor; the deleted maturity
gate with the measured "0 of 297" behind it; fixed blocks **plus** epoch pooling; ABBA rather
than ABAB; the guardrails with measured band mass instead of a superiority bar; the due-ness
follow-up rule; the lapse definition; and the one-cut scope with the reason.

`chatgpt_fourthpass.md` records what was accepted from that review and what was not — including
the rejection of card-level allocation in favour of blocks on the same cards, and the rejection
of both candidate adoption bars in favour of δ re-estimation plus guardrails.

## 13. What changed, and what did not

**From the three code/statistics reviews:** adjacent contrasts instead of a grand-mean
adjustment; the proposal sentence's sign; pairing on `(paperId, questionId)`; `Again` and skips
excluded; `predictedRecall` null for new and imported cards; the session lifecycle through
`finishEmpty`; routing every ladder writer through one store; the config snapshot passed into the
store; backup `VERSION = 2` and five plumbing sites per table; the two migration arrays;
per-class precision gates with BCa; fixed calendar blocks with an outcome horizon; ABBA; the
rollback rewrite; extending the existing calibration chart instead of adding a second one.

**From the two decision reviews:** a separate `ladder_blocks` table (the rollback rule needs
blocks that own no version); no superiority bar on the deck; the first-exposure stratum reported
but never fitted; the optimizer/ladder interaction (§7); the collinear §6 analysis model.

**From the learner's own log:** the 1,000-pair floor and the maturity gate are both deleted
(measured pair decay and measured "0 of 297"); readiness becomes precision-based; `c2` is frozen
for v1 (18 pairs, ±12.5 pp); follow-up is counted against due-ness rather than elapsed time;
epoch pooling is required because a 28-day block cannot reach the precision gate; the step size
is justified by measured band mass (3.15% within ±1 s); and the common +6.7 pp bias becomes the
worked empirical case for the adjacent contrast.

**Rejected, with reasons:** a content-hash column on `review_log` (the "either row is NEW" rule
already catches content edits, because an edit deletes the card state); storing the two cuts on
every row (they are recoverable from `ladderVersion` once every writer goes through
`LadderStore`); a whole-deck lapse superiority bar at 10% or 20% (unreachable per the arithmetic
in §5); card-level randomisation (blocks on the same cards, §6); and pooling first-exposure
labels into the fit (no forecast exists for them, and the optimizer already fits `w0..w3` on
that same data).
