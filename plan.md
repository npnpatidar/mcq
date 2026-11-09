# Remaining implementation plan

Status: consolidated on 2026-11-09 from [`stats.md`](stats.md),
[`stats-phase2.md`](stats-phase2.md), [`stats-phase3.md`](stats-phase3.md) and
[`statistics-plan.md`](statistics-plan.md). This file is the single list of
work that is still outstanding; the source documents remain the design record
for what has already shipped.

Baseline: `main` at the merge of PR #52, release `0.0.43`. Phases 1–3 of the
Test redesign, the phase-2 report evidence, the phase-3 custom tests,
reliability/guessing/item analysis, CSV/PDF export and fixed panels are
implemented and tested. The two deliberately deferred phase-3 ideas are **not**
repeated here:

- adaptive next-test recommendation / staged testing — see
  [`ideas.md`](ideas.md) §1;
- exam blueprint — see [`ideas.md`](ideas.md) §2.

Everything else that any of the four documents still marks deferred, missing
or "later" is collected below. Work that the statistics plan explicitly says
needs new logging is kept in §11 so the decision is not lost, but it is not
scheduled.

## 1. What is already shipped

| Area | Shipped | Where |
|---|---|---|
| Bounded Test | One Test entry point, 20–150 lengths, short tests, passages kept whole, deterministic resume | `domain/TestPlan.kt`, `domain/SessionOrder.kt`, `ui/library/TestSetupSheet.kt` |
| Test metadata | type, requested count, scope key, sampling version, seed, exclusion, targets, composition, variant | `domain/Models.kt`, `data/local/Entities.kt` |
| Test types | Random, Balanced, Mistakes, Weakest topics, Toughest, Easiest, Tricky, At risk, Unseen, Mixed, Bookmarked, Tag, Authored difficulty, Slowest, Fixed panel | `domain/TestType.kt`, `domain/signals/TestSelector.kt` |
| Test/Study boundary | Test writes only attempts/results/stats/progress; Study state is read-only | `data/repository/HistoryStore.kt`, `RepositoryTest.savingATestLeavesTheStudyScheduleUntouched` |
| Report | header, margin, error mix, topics, memory quadrants, coverage, trend, bootstrap, recommendations, review evidence | `domain/TestReport.kt`, `ui/results/` |
| Reliability | split-half/Spearman–Brown, SEM, KR-20, point-biserial, chance-based guessing correction | `domain/stats/Reliability.kt`, `ReliabilityTest` |
| Export | CSV and PDF report export/share | `data/export/ReportCsv.kt`, `data/export/ReportPdf.kt` |
| Fixed panels | v23 `panels`/`panel_questions`, picker/create/delete, progress card, `study.json` backup | `data/local/AppDatabase.kt`, `data/backup/Backup.kt`, `ui/library/TestSetupSheet.kt` |
| Statistics | six SRS tabs plus the **Tests** tab (families, reliability, practice runs/topics, panels, scope counters) | `ui/stats/`, `domain/TestProgress.kt` |

The six SRS tabs are Overview, Retention, Workload, Memory, Cards and
Guessing. The seventh, Tests, is the phase-2/3 addition.

## 2. Remaining work at a glance

| # | Work | Source | Size | Priority |
|---|---|---|---|---|
| 1 | Exam (measurement) view: attempt trends, per-paper and category figures, recent attempts | `statistics-plan.md` §4 | L | High |
| 2 | Coverage: studied/unseen/bookmarked, per-paper not-started | `statistics-plan.md` §5 | M | High |
| 3 | Progress tab: introductions, graduations, relapses, Again rate, period comparison | `statistics-plan.md` §6 | L | High |
| 4 | Retention additions: interval bands, by tag, all-papers category | `statistics-plan.md` §7 | M | Medium |
| 5 | Workload additions: due tomorrow/week, grade-mix trend, sessions summary | `statistics-plan.md` §8 | M | Medium |
| 6 | Smaller additions: KPI deltas, card-sheet summary, memory by paper | `statistics-plan.md` §9 | S | Medium |
| 7 | Deferred database/UI tests from phases 1–3 | `stats.md` §11.16, `stats-phase2.md` §9, `stats-phase3.md` §7 | M | High |
| 8 | Optional later Test/Statistics enhancements | `stats.md` §7.3, §8.2, §9.4, §9.8, §9.10, §12.3 | S–M | Low |
| 9 | Metrics that need new logging (not scheduled) | `statistics-plan.md` §10 | L | Deferred |

Recommended delivery order is §13. Items 1–7 are the actual backlog; item 8 is
optional and item 9 is recorded only so the reasons survive.

## 3. Exam (measurement) view

Source: `statistics-plan.md` §4. The prerequisite is met: Test now produces
bounded attempts with the full metadata. The current Tests tab already shows
family summaries (attempts, latest, average) and practice progress. It does
**not** show best/delta, margins, a trend inside one comparable key, per-paper
rows, category accuracy, timing/skip aggregates or recent attempts. Those are
the remaining Exam work.

**Placement recommendation.** Extend the existing **Tests** tab with the Exam
sections instead of adding a near-duplicate tab: the family summary already
lives there, and two tabs answering "how are my tests going?" is worse UX. The
data model and queries below are the same either way. If the owner prefers the
original statistics-plan layout, split the tab into **Exam** (representative
mocks) and **Tests** (practice, panels, reliability); the split is a UI
decision, not a data one.

### 3.1 Model

Add `domain/stats/ExamStats.kt` (pure, JVM-testable):

| Field | Definition |
|---|---|
| `attempts` | Bounded attempts in the selected family/group; legacy attempts counted separately and never in a trend. |
| `latestPercent` | Score percentage of the most recent attempt in the comparable group. |
| `bestPercent` | Highest attempt percentage in the group. |
| `averagePercent` | Mean percentage in the group, weighted by each attempt's `score / maxScore`. |
| `deltaPoints` | Latest minus first percentage, in points; null with one attempt. |
| `totalMinutes` | Sum of `durationSeconds` / 60. |
| `correct` / `wrong` / `skipped` | Sums of the attempt tallies. |
| `averageSecondsPerQuestion` | Total measured `dwellSeconds` ÷ number of timed result rows in range/scope. |
| `skipRate` | `skipped / (correct + wrong + skipped)`; weak before bounded attempts exist (pre-v21 inflation). |
| `shown` / `requested` | Sum of shown questions and requested counts. The margin uses `shown`; comparability uses `requested`. |
| `marginPoints` | Approximate 95% margin from the shown count and finite-population correction; labelled approximate; absent for practice, short and legacy. |
| `family` | `ReportFamily`: `MOCK`, `FRESH_SAMPLE`, `PRACTICE`, `SHORT`, `LEGACY`. |
| `comparisonKey` | `(paperId, testType, scopeKey, requestedCount, samplingVersion, exclusionMode)`. A trend is drawn only inside one key. |
| `trend` | Mean percentage per time bucket: daily ≤31 days, weekly ≤150, monthly beyond. Empty buckets kept. |
| `papers` | Per paper: attempts, latest, best, delta, split by family. Empty in a one-paper scope. |
| `categories` | Per **as-of-attempt** `categoryTitle`: graded, correct, accuracy, weakest first, capped at 15, plus the unresolved-question count. |
| `recent` | Five latest attempts: title, percentage, correct/total, `finishedAt`, family badge. |

Use the existing `Attempt.percentage` and `TestReportBuilder.familyOf`. Do not
invent a second family rule.

### 3.2 Queries

Add aggregate-only DAO queries; do not load every result row into Kotlin:

- `ExamAttemptGroups(since, paperId)` — group bounded attempts by
  `(paperId, testType, scopeKey, requestedCount, samplingVersion,
  exclusionMode)` and return count, shown/requested sums, score sums and the
  latest/best row. This is what keeps strict, fresh, practice and length groups
  separate without materialising attempts.
- `ExamCategoryTotals(since, paperId)` — `SUM(graded)`, `SUM(correct)` grouped
  by the stored `question_results.categoryTitle`; join `questions` only to
  resolve a current `categoryId` for the Study link, and count unresolved rows.
- `ExamDwellTotals(since, paperId)` — `SUM(dwellSeconds)` and count of timed
  rows.
- `ExamRepresentativeTotals(since, paperId)` — `SUM(repCorrect)`,
  `SUM(repWrong)`, `SUM(repSkip)` for mock accuracy. Practice views read the
  all-attempt columns instead.

Constraints:

- `minSdk = 26`, so no window functions. Use the existing correlated-subquery
  style (`getLatestStandings`) if a per-question standing is needed.
- A legacy attempt (`testType IS NULL`, `requestedCount = 0`,
  `samplingVersion = 1`) may appear in `recent` with a **Legacy** badge but
  never in a trend, best/average figure or comparison.
- `question_stats` means "currently stored": deleting an attempt recomputes
  the affected rows, so category weakness and "hardest" figures change. The
  page says "hardest in stored attempts", not "hardest ever".

### 3.3 UI

Add to the Tests/Exam tab:

1. **Family selector**: Mock strict, Mock fresh, Practice. Short and legacy are
   shown in `recent` only (and in their own family summary), never in a trend.
2. **KPI grid**: latest, best, average, delta, total time, skip rate,
   average seconds/question, sample size.
3. **Score trend** inside the selected comparable group, with the sample size
   and confidence band. A change inside the margins is labelled noise.
   Random and Balanced stay separate; strict and fresh stay separate; a fresh
   band is labelled approximate.
4. **Per-paper list** split by family (hidden in a one-paper scope).
5. **Category accuracy bars** using as-of-attempt titles, weakest first,
   capped at 15, with the unresolved count.
6. **Timing/skip footnote** and the "hardest in stored attempts" wording.
7. **Recent attempts** with family badge; tap opens the existing results
   screen through the nav callback.
8. **Empty state** before any test exists.

Every number must state its counter source where the same question can show two
different lifetime accuracies: `Mock attempts` (`rep*`) vs `All attempts`.

### 3.4 Tests

Pure JVM (`ExamStatsTest`):

- definitions, empty inputs, bucketing, deltas, caps and sorting;
- strict vs fresh grouping, practice vs mock grouping, Random vs Balanced
  separation;
- a legacy attempt never enters a trend;
- the margin uses the shown count and is absent for practice/short/legacy;
- category titles are as-of-attempt and unresolved rows reconcile the totals;
- the practice per-topic trend only compares runs with the same recorded
  `targetTopicIds`; an empty-target run cannot contribute.

Aggregator tests (`StatsAggregatorTest`, `StatsTabsStreamTest`): the in-memory
and streamed builds agree for every new slice; existing tabs are unchanged.

Robolectric/DB tests (CI x86_64):

- a legacy attempt never enters a trend;
- `rep*` totals match only Random/Balanced attempts with
  `samplingVersion >= 2`;
- deleting an attempt changes the stored hardest/per-question figures;
- the partial `question_results` index exists after migration and fresh create.

### 3.5 Acceptance

- [ ] Real attempts, trends, per-paper and category figures appear, with an
      empty state before any test exists.
- [ ] Strict mocks, Fresh samples, practice, short and legacy are separate; no
      trend mixes families; a fresh sample is never plotted against a strict
      attempt.
- [ ] The full comparability key groups attempts; the margin uses shown and
      comparability uses requested.
- [ ] Mock accuracy reads `rep*`; practice/personal views read all-attempt
      counters; both are labelled.
- [ ] Category rows use as-of-attempt titles and report unresolved questions;
      "hardest" says "in stored attempts".
- [ ] Pre-v21 attempts are labelled legacy, never enter a trend, and the
      skip-rate/Tricky limitation is stated.
- [ ] Deleting an attempt updates the stored hardest/per-question figures.

## 4. Coverage (studied vs never studied)

Source: `statistics-plan.md` §5.

### 4.1 Model

Add `domain/stats/CoverageStats.kt`:

| Field | Definition |
|---|---|
| `questions` | Questions in scope. |
| `studied` | Distinct questions in scope with a `card_state` row. |
| `unseen` | `questions - studied`. |
| `bookmarked` | Distinct bookmarked questions in scope. |
| `studiedRate` | `studied / questions`. |
| `papers` | Per paper: questions, studied, unseen, bookmarked. Empty in a scoped view. |

Definitions are about **rows**, not schedules: a card whose content was reset
(row deleted) is unseen again.

### 4.2 Query and UI

- One grouped SQL query over `questions`, `categories`, `card_state` and
  `bookmarks`; no full question materialisation.
- **Cards tab, first card: Coverage** — studied/unseen/bookmarked figures and a
  meter for the studied share. This restores the context the removed "New"
  slice used to give without pretending an unseen question is a card state.
- **Cards tab, after cards-per-paper: "Not started per paper"** — at most 15
  rows, sorted by unseen descending, only papers with unseen > 0.
- The Tests tab's existing scope counters (`scopeSize`, `unseen`, `leeches`,
  `atRisk`) may read the same query instead of duplicating it.

### 4.3 Tests

- `CoverageStatsTest`: definitions, empty scope, zero questions, scoped vs
  unscoped, cap and sort order.
- Robolectric: studied/unseen/bookmarked counts match seeded rows; a reset card
  becomes unseen again.
- Aggregator: in-memory and streamed builds agree.
- UI smoke: Coverage card and per-paper list render; no row with unseen = 0.

### 4.4 Acceptance

- [ ] Cards tab shows studied/unseen/bookmarked and a studied-share meter.
- [ ] Cards tab shows per-paper unseen, capped and sorted, only when non-zero.
- [ ] Counts are row-based and a reset card is unseen again.

## 5. Progress tab

Source: `statistics-plan.md` §6. This is the only genuinely new SRS tab.

### 5.1 Model

Add `ProgressSlice` and `ProgressData`:

| Field | Definition |
|---|---|
| `reviews` | Graded reviews in the bucket. |
| `introduced` | Cards whose first-ever logged review is in the bucket. Tracked with a question-id set bounded by cards. |
| `graduated` | Non-Again reviews on a card whose pre-state was `NEW`/`LEARNING`/`RELEARNING` (a pass moves any of those to `REVIEW` in FSRS). |
| `relapsed` | Again reviews on a card whose pre-state was `REVIEW`. |
| `again` | All Again grades. |

Derived: `againRate = again / reviews`, `lapseRate = relapsed / reviews`.

`ProgressData` also carries:

- `totals` for the range (`reviews`, `introduced`, `graduated`, `relapsed`,
  `again`);
- `comparison` — current vs previous equal-length window: reviews, pass rate
  (all reviews, Again = fail), average seconds, study days and signed deltas.
  `null` for the `All` range, which has no previous window;
- `trend` — bucketed slices with the same day/week/month granularity rule as
  the other charts.

### 5.2 Placement and plumbing

- Add an **eighth tab, Progress**, after Tests (or after Exam if the tabs are
  split).
- Totals grid; grouped bar/line of introduced vs graduated vs relapsed; Again
  rate line with the previous-period delta; a "vs previous period" KPI grid.
- Implement in the existing replay/accumulator architecture: one accumulator in
  `StatsAggregator`, paged into by `StatsStore`, so the in-memory and streamed
  paths cannot drift.
- No schema change.

### 5.3 Tests

- `ProgressStatsTest`: definitions, empty inputs, bucketing, deltas and the
  All-range comparison being null.
- `StatsAggregatorTest` / `StatsTabsStreamTest`: the two build paths agree;
  existing tabs unchanged.
- UI smoke: the tab renders with seeded history and with no history.

### 5.4 Acceptance

- [ ] Progress tab shows introductions, graduations, relapses, Again rate and
      a previous-period comparison.
- [ ] The All range hides the comparison.
- [ ] In-memory and streamed builds are equal in tests.

## 6. Retention additions

Source: `statistics-plan.md` §7. Extend the existing `RetentionAccumulator`
with three slices.

1. **By interval band** — reviews grouped by the whole-day interval carried
   into the review: `First`, `1–7d`, `8–21d`, `22–60d`, `61–180d`, `181d+`.
   Uses `intervalBeforeDays`, already replayed.
2. **By tag** — every tag on a reviewed question contributes that review to the
   tag's count. Count reviews per question first; after the pass, load tags for
   the reviewed question ids only (bounded by the log's working set) and fold
   question counts into tag counts. Use the v22 `question_tags` index, not a
   `LIKE` scan. A review with several tags counts once per tag by design; the
   tag row says so.
3. **Category retention in the all-papers scope** — keep paper as the group
   when unscoped, and add a category slice built from the reviewed question ids
   the same way tags are, so section-level weakness is visible without
   selecting a paper first.

Tests: interval-band boundaries, multi-tag attribution, unscoped category
slice, empty inputs, in-memory vs streamed equality.

Acceptance: Retention tab adds interval-band, tag and all-papers category
slices; the tag slice uses the index and stays bounded by the reviewed set.

## 7. Workload additions

Source: `statistics-plan.md` §8. Extend `WorkloadAccumulator` from the cards it
already pages and the log it already replays.

| Field | Definition |
|---|---|
| `dueTomorrow` | Scheduled cards due in `[tomorrowStart, tomorrowStart + 1 day)`. |
| `dueThisWeek` | Scheduled cards due in `[todayStart, todayStart + 7 days)`, backlog excluded. |
| `ratingTrend` | Per bucket (same granularity rule): Again / Hard / Good / Easy counts. |
| `sessions` | Sittings split by a 30-minute gap: count, mean reviews, mean minutes, longest minutes, mean seconds per review, and a reviews-per-sitting histogram (`1`, `2–4`, `5–9`, `10–19`, `20–49`, `50+`). |

Placement: near-term figures in the Forecast card footnotes; a "Grade mix over
time" stacked chart; a "Study sessions" summary card. Reuse the existing
30-minute-gap sitting logic where possible, but keep the session summary
separate from the guessing tab's sitting-position buckets.

Tests: due-window boundaries, backlog exclusion, grade-mix bucketing, 30-minute
gap boundary, histogram buckets, empty inputs, in-memory vs streamed equality.

Acceptance: Workload tab adds due-tomorrow/due-this-week, grade-mix trend and
session summary.

## 8. Smaller additions

Source: `statistics-plan.md` §9.

1. **KPI deltas on Progress** — signed deltas on the Progress KPI grid only;
   do not clutter every KPI tile with icons.
2. **Per-card sheet summary** — above the raw history in `CardInfoSheet`, show
   Again rate and mean answer seconds computed from the history already loaded
   for the sheet. No new query.
3. **Memory by paper** — mean stability, difficulty and retrievability per
   paper, one row per paper, unscoped only. Add to the Memory tab; no per-card
   expansion.

Tests: pure formatting/aggregation tests; card-sheet summary with empty,
one-review and mixed histories; memory-by-paper means with missing cards.

Acceptance: the card sheet shows Again rate and mean time; Memory shows
per-paper means; Progress shows deltas.

## 9. Test and verification gaps

These are the explicitly deferred tests from the shipped phases. They are
small, but they protect rules that currently rely on pure-builder tests only.

### 9.1 Robolectric/SQLite (CI x86_64)

From `stats.md` §11.16 and `stats-phase2.md` §9:

1. **Coverage query counts engaged questions, not old skipped rows.** Seed an
   old full-scope attempt with untouched skipped rows and assert the coverage
   query counts only `selectedOptionIds != '' OR dwellSeconds > 0`.
2. **Prior-standings query returns the correct before/after for mistakes.** The
   pure `mistakesOf` rule is tested; add the SQL/chunked standing query test and
   assert `finishedAt DESC, id DESC` (including after a merge import).
3. **Due-in-Study count is read-only.** Assert the count query leaves
   `card_state` unchanged (row count and values).
4. **Report queries match the attempt header.** Cross-check score, counts,
   categories, dwell median and engaged coverage against the report header.

Also worth adding while touching these queries:

- query-plan assertions for the comparable-attempt and focus indexes where
  practical (`stats.md` §14);
- the phase-2 `getLatestStandings`-style standing rule for the new chunked
  query (the same rule as #2).

### 9.2 Instrumented (CI emulator)

From `stats-phase3.md` §7:

- the four custom chips (Bookmarked, Tag, Authored difficulty, Slowest) appear
  in the setup sheet; a tag can be chosen; Start runs the type;
- the session header and result header say `Custom · …`;
- a tag edited in the editor appears in the Tag picker in the same session;
- the share action opens the chooser for a report.

The current androidTest suite covers practice results but not these custom
paths.

### 9.3 Reliability cross-check

`stats-phase3.md` §4 requires KR-20 and split-half to agree within a stated
tolerance in tests. `ReliabilityTest` covers both independently but not the
agreement. Add a hand-computed matrix where both are defined and assert the
difference is within the documented tolerance; if the two are not comparable
for a given matrix (e.g. zero variance), document why and assert the null
behaviour instead.

### 9.4 PDF/export smoke

`stats-phase3.md` §5/§7 asks for a PDF layout smoke test where feasible:
page count, no exception on an empty report, and the header/score/topics text
present. `ReportExportCsvTest` already covers CSV quoting and column order.

## 10. Optional later enhancements

Source: `stats.md`. None of these is scheduled; they are recorded so a future
request does not have to re-derive the design.

| Enhancement | Source | Notes |
|---|---|---|
| Remember last-used test length | §7.3, §16 #2 | Persist the last accepted length in settings/DataStore; always clamp to the allowed range and to the pool. Default stays 20. |
| Equal-allocation "Coverage" test type | §8.2, §9.4 | Same number per selected topic instead of proportional. Maximises coverage but over-represents small topics, so it is **not** comparable to Balanced. Needs a new `TestType` and labels; do not call it Balanced. |
| Rotating balanced mode | §9.4 | When `n < number of categories`, rotate which small categories are included across tests. Needs rotation state (attempt history or a setting) so the choice is reproducible. |
| Exposure-weighted Coverage test mode | §9.8 | Weight selection inversely to test exposure. A biased sample for score estimation; label it "Coverage", never "Random". |
| Per-paper priority cache | §9.10 | Only if profiling shows ranking a 250k-question paper is too slow. Rank per category and merge, or materialise a cache; do not add it speculatively. |
| `question_stats.categoryId` column | §12.3 | Makes ranking cheaper by avoiding the join to `questions`; not required for the current bank sizes. Would need a migration and schema JSON. |

## 11. Metrics that need new logging (not scheduled)

Source: `statistics-plan.md` §10. These cannot be implemented from existing
columns; each needs its own design and migration.

| Metric | Why deferred |
|---|---|
| Distractor analysis (which wrong option is chosen) | `review_log` stores only `wasCorrect`; logging selected option ids needs a migration, writers on both study and test paths, import/export coverage and backup versioning. |
| Multi-select partial credit | Same missing selection data. |
| Backlog history / leech transitions | Current state is a snapshot; history needs periodic snapshots or event columns. |
| Retention by answer mode (study vs test) | Study reviews and test results are separate tables; a combined rate needs a shared event log. |
| Goal adherence | The app has no daily new/review limits, so any "goal" would be invented. |

Do not add these to the statistics tabs until the logging design is accepted.

## 12. Excluded by instruction

- **Adaptive next-test recommendation / staged testing** — see
  [`ideas.md`](ideas.md) §1. A rule-based cross-attempt card is feasible; CAT
  and staged testing need calibrated item parameters this single-user bank
  cannot produce.
- **Exam blueprint** — see [`ideas.md`](ideas.md) §2. It changes the plan shape
  and needs owner decisions on sections, family, pools, short sections, timing,
  weights and a real example.

## 13. Delivery order

Ship as small PRs on `feature/*` branches, each with its own pure-JVM tests and
green CI. Robolectric/instrumented tests are authoritative on CI x86_64.

1. **Exam data + UI** (item 1) — highest value, uses metadata that already
   exists, no schema change. Decide first whether it extends the Tests tab or
   becomes a separate Exam tab; the data work is identical.
2. **Coverage + Cards additions** (item 2) — small, visible, no schema change.
3. **Progress tab** (item 3) — new accumulator; keep the in-memory/streamed
   equality tests alongside it.
4. **Retention + Workload additions** (items 4–5) — independent; can be two
   PRs or one.
5. **Smaller additions** (item 6) — low risk, after the tabs settle.
6. **Test hardening** (item 7) — interleave with the above; the deferred
   Robolectric tests can land as their own PR before the new tabs.
7. **Optional later enhancements** (item 8) — only on owner request.
8. **Logging-dependent metrics** (item 9) — only after a logging design is
   accepted.

Each PR updates this file and the relevant source document when an item ships
or is re-scoped.

## 14. Constraints and risks

- **Bounded memory.** No tab may materialise the review log or the whole
  question table. Aggregators keep counters, one bucket per chart point, one
  roll per ranked card, or one entry per paper. Per-question maps are allowed
  only for questions actually reviewed in range.
- **Two builds, one accumulator.** Every SRS metric is computed by both
  `StatsAggregator.build*` and `StatsStore.load*`; both must feed the same
  accumulator so they cannot drift.
- **Range vs now.** History charts honour the selected range; current-state
  sections (coverage, forecast, distributions) are live and ignore it.
- **One offset per load.** Day bucketing uses the visit's UTC offset; history is
  never re-bucketed through travel/DST shifts.
- **No schema change is expected** for items 1–7. If a query proves it needs a
  column or index, add one migration per PR, generate the schema JSON, add it
  to `MIGRATIONS` and prove it in `MigrationTest` from the previous version.
  Do not bump the version speculatively.
- **`minSdk = 26`** means no window functions; keep the correlated-subquery
  style.
- **As-of-attempt titles.** Report and category rows use the stored
  `question_results.categoryTitle`; deleted questions are counted in an
  "N results no longer resolvable" line so totals reconcile.
- **`question_stats` is "currently stored".** Deleting an attempt recomputes
  the affected rows; "hardest" means hardest in stored attempts.
- **Pre-v21 attempts** inflate `skipCount`; there is no backfill and no
  pre-v21 exclusion. State the limitation wherever skip rate or Tricky appears.
- **Comparability.** The key is `(paperId, testType, scopeKey,
  requestedCount, samplingVersion, exclusionMode)`. Practice never shares a
  mock trend; strict and Fresh sample stay separate; Random and Balanced stay
  separate.
- **Counter source.** Mock accuracy and mock lifetime accuracy read `rep*`;
  personal/practice views read all-attempt counters. Label the source.
- **No new test dependencies.** Robolectric + `android.database.sqlite` remain
  the database test tools; no SQLite driver library.
- **ARM64 hosts cannot run Robolectric/SQLite tests.** Pure-JVM tests run
  locally; CI x86_64 is authoritative for the rest.

## 15. Whole-plan acceptance

- [ ] Exam/measurement sections show real attempts, trends, per-paper and
      category figures, and an empty state before any test exists.
- [ ] Families and comparability keys are never mixed in a trend.
- [ ] Mock accuracy reads `rep*`; practice/personal views read all-attempt
      counters; both are labelled.
- [ ] Coverage shows studied/unseen/bookmarked and per-paper unseen.
- [ ] Progress shows introductions, graduations, relapses, Again rate and a
      previous-period comparison; the All range hides the comparison.
- [ ] Retention adds interval-band, tag and all-papers category slices.
- [ ] Workload adds due-tomorrow/due-this-week, grade mix and sessions.
- [ ] Memory shows per-paper means; the card sheet shows Again rate and mean
      time.
- [ ] In-memory and streamed builds are equal in tests.
- [ ] The deferred Robolectric tests in §9.1 pass; the instrumented custom
      test/share paths in §9.2 pass on CI; the reliability cross-check (§9.3)
      and PDF smoke test (§9.4) exist and pass.
- [ ] No schema change ships without a migration, schema JSON and
      `MigrationTest` proof.
- [ ] `:app:testDebugUnitTest` and `:app:lintDebug` pass; CI is green.
