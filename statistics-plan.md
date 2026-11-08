# Comprehensive statistics

Status: **partially implemented** on `feature/comprehensive-stats`. The six
existing SRS tabs are implemented; the **Exam**, **Coverage** and **Progress**
tabs are design-only and depend on the bounded Test work in
[`stats.md`](stats.md). This document is the design record and the work plan:
every metric below states its definition, its data source and where it appears.
Anything deliberately left out is listed at the end with the reason.

## 1. Goal

Make Statistics answer the questions a learner actually asks:

1. **How is my exam preparation going?** — mock-test scores, accuracy by
   category, timing, skips. Today the page only describes spaced repetition.
2. **How much of the bank have I actually touched?** — studied vs never
   studied vs bookmarked. The Cards tab stopped counting “New”, so without this
   the card Total reads like the collection size.
3. **Is my studying working over time?** — new cards introduced, graduations,
   relapses and Again rate per period, plus a comparison with the previous
   period.
4. **Where exactly is the weakness?** — retention by interval band, category
   and tag, not just by paper and weekday.
5. **What is the near-term load?** — due tomorrow/this week, grade mix over
   time, and how much of the work happens inside one sitting.

Non-goals: replacing the History screen (attempt drill-down stays there), any
new scheduling behaviour, and any metric that cannot be defined without
inventing data.

## 2. Current state (before this change)

Six tabs, all fed by a full replay of `review_log` plus a page-through of
`card_state`:

| Tab | Contents |
|---|---|
| Overview | Today strip, KPI grid, true-retention table, calendar/streaks, most forgotten, scheduler status |
| Retention | True-retention trend, calibration, retention by paper/category, by weekday |
| Workload | 30-day forecast, reviews/day, time/day, answer buttons, hourly, response time |
| Memory | Stability, difficulty, retrievability, intervals, memory growth |
| Cards | State donut, overdue age, cards per paper, leeches |
| Guessing | Declared-guess dashboard |

Known gaps that this change closes:

- Mock attempts (`attempts`, `question_results`) never reach Statistics.
- No studied/unseen coverage anywhere in Statistics.
- No history of introductions, graduations, relapses or Again rate.
- No period-over-period comparison.
- Retention by paper and weekday only; interval band and tag missing.
- Forecast has no near-term headline and no new-card supply.
- Answer-button data is a single aggregate, not a trend.
- No sitting/session summary.
- The per-card sheet has raw history but no summary figures.

## 3. Architecture constraints

The existing design is not optional; new metrics must fit it:

- **Two builds, one accumulator.** Every SRS metric is computed both by the
  in-memory `StatsAggregator.build*` path (used by tests and the bundle API) and
  the streamed `StatsStore.load*` path. Both must feed the *same* accumulator,
  so the two cannot drift. A new accumulator is added to `StatsAggregator`; the
  store only pages into it.
- **Bounded memory.** No tab may materialise the review log or the whole
  question table. Aggregators keep counters, one bucket per chart point, one
  roll per ranked card, or one entry per paper. Per-question maps are allowed
  only for the questions actually reviewed in range (the log's own working
  set).
- **Range vs “now”.** History charts honour the selected range; current-state
  sections (card counts, coverage, forecast, distributions) are live and
  ignore it.
- **One offset per load.** Day bucketing uses the visit's UTC offset; history
  is never re-bucketed through travel/DST shifts.
- **No schema change in the statistics delivery itself.** Everything in
  sections 4–8 is derived from existing columns. The bounded-Test prerequisite
  does add a schema change — `attempts` metadata columns, `question_stats.rep*`
  counters and a partial `question_results` index (v20 → v21, `stats.md`
  section 12) — and the Exam tab must not ship before that migration. Section
  10 lists the metrics that would need further new logging and why they are
  deferred.

## 4. New tab: Exam

Source: `attempts` and `question_results` (the same tables History uses).
Scope and range apply: `attempts.paperId`, `attempts.finishedAt` inside the
range window (day-boundary based, matching the SRS tabs).

**Only bounded attempts are charted.** A pre-v21 attempt (`testType IS NULL`,
`requestedCount = 0`, `samplingVersion = 1`) is legacy: it may be listed in
`recent` with a "Legacy" badge, but it never enters a trend, a best/average
figure or a comparison. This is a documented limitation, not a hidden filter.

**Comparability.** The Exam tab groups by the same key as the result page:
`(paperId, testType, scopeKey, requestedCount, samplingVersion,
exclusionMode)`. A trend line is built only inside one key. In particular:

- **Strict and Fresh sample attempts are separate lines.** `exclusionMode =
  NONE` is strict; `RECENT_3` is a Fresh sample. A fresh sample is never
  plotted against a strict attempt, and its confidence band is labelled
  approximate because each run excludes a different set.
- **Practice is a separate family.** Weakest topics, Toughest, Easiest,
  Mistakes and the other focus types never enter a mock trend, even at the same
  length and scope.
- **Random and Balanced stay separate**, because their variance differs.
- **The per-topic practice trend** compares only runs whose recorded
  `targetTopicIds` are identical; an empty-target run cannot contribute.

**Which counters are read.** Mock accuracy and per-question lifetime accuracy
in a mock read the representative `rep*` counters (Random/Balanced with
`samplingVersion >= 2`). Personal and practice views read the all-attempt
counters. The two must be labelled, because the same question can show two
different lifetime accuracies.

**`question_stats` means "currently stored".** Deleting an attempt recomputes
the affected rows, so the "hardest" cards and category weakness change when an
attempt is deleted. The page says "hardest in stored attempts", not "hardest
ever". Category rows use the `categoryTitle` stored on `question_results`
(as-of-attempt); deleted questions are counted in an "N results no longer
resolvable" line so the totals reconcile. Pre-v21 full-scope attempts inflate
`skipCount`, so skip-rate and Tricky evidence are weak until bounded attempts
exist; there is no backfill and no pre-v21 exclusion.

Model `ExamData`:

| Field | Definition |
|---|---|
| `attempts` | Number of bounded attempts in the selected family/group; legacy attempts are counted separately and never enter a trend. |
| `latestPercent` | Score percentage of the most recent attempt in the current comparable group. |
| `bestPercent` | Highest attempt percentage in the group. |
| `averagePercent` | Mean percentage across attempts in the group, weighted by each attempt's `score / maxScore`. |
| `deltaPoints` | Latest minus first percentage, in points (null with one attempt). |
| `totalMinutes` | Sum of `durationSeconds` / 60. |
| `correct` / `wrong` / `skipped` | Sums of the attempt tallies. |
| `averageSecondsPerQuestion` | Total measured `dwellSeconds` ÷ number of timed result rows, from `question_results` in range/scope. |
| `skipRate` | `skipped` ÷ (`correct + wrong + skipped`); weak before bounded attempts exist (pre-v21 inflation). |
| `shown` / `requested` | Sum of shown questions and requested counts; the margin uses `shown`, comparability uses `requested`. |
| `marginPoints` | Approximate 95% margin for the group, from the shown count and finite-population correction; labelled approximate, and absent for practice, short and legacy groups. |
| `family` | `MOCK_STRICT`, `MOCK_FRESH`, `PRACTICE`, `SHORT` or `LEGACY`. |
| `comparisonKey` | The full `(paperId, testType, scopeKey, requestedCount, samplingVersion, exclusionMode)` tuple; a trend is only drawn inside one key. |
| `trend` | Mean percentage per time bucket (daily ≤31 days, weekly ≤150, monthly beyond), inside one comparable key. Empty buckets are kept. |
| `papers` | Per paper: attempts, latest, best, delta points, split by family. Empty when scoped to one paper. |
| `categories` | Per **as-of-attempt** category title: graded, correct, accuracy, ranked weakest first, capped at 15, plus the unresolved-question count. |
| `recent` | The five latest attempts (title, percentage, correct/total, finishedAt, family badge). |

DAO additions (aggregate-only, no full result scans):

- `ExamAttemptGroups(since, paperId)` — group bounded attempts by
  `(testType, scopeKey, requestedCount, samplingVersion, exclusionMode)` and
  return count, shown/requested sums, score sums and the latest/best row. This
  is what makes strict, fresh, practice and length groups separate without
  loading rows into Kotlin.
- `ExamCategoryTotals(since, paperId)` — `SUM(graded)`, `SUM(correct)` grouped by
  the **stored** `categoryTitle` over results whose attempt finished at/after
  `since` and matches the paper when given; join `questions` only to resolve a
  current `categoryId` for the Study link, and count unresolved rows.
- `ExamDwellTotals(since, paperId)` — `SUM(dwellSeconds)` and count of timed
  rows.
- `ExamRepresentativeTotals(since, paperId)` — `SUM(repCorrect)`,
  `SUM(repWrong)`, `SUM(repSkip)` for mock accuracy; practice views read the
  all-attempt columns instead.

Placement: a seventh tab **Exam** after Cards. Cards: summary KPI grid with a
family selector (Mock strict / Mock fresh / Practice), score trend line inside
the selected comparable group, per-paper list split by family, category
accuracy bars with as-of-attempt titles, timing/skip footnote, and recent
attempts with a family badge (tap opens the existing results screen through
the nav callback). Legacy attempts appear only in `recent`, with a "Legacy"
badge and no trend.

## 5. Coverage (studied vs never studied)

Source: `questions`, `categories`, `card_state`, `bookmarks`, one grouped SQL
query.

Model `CoverageData`:

| Field | Definition |
|---|---|
| `questions` | Questions in scope. |
| `studied` | Distinct questions in scope with a `card_state` row. |
| `unseen` | `questions - studied`. |
| `bookmarked` | Distinct bookmarked questions in scope. |
| `studiedRate` | `studied / questions`. |
| `papers` | Per paper: questions, studied, unseen, bookmarked. Empty in scoped view. |

Definitions are deliberately about **rows**, not schedules: a card whose
content was reset (row deleted) is unseen again, which is exactly the product
rule.

Placement:

- **Cards tab**, first card: “Coverage” — studied/unseen/bookmarked figures and
  a meter for the studied share. Restores the context the removed New slice
  used to give, without pretending an unseen question is a card state.
- **Cards tab**, after cards-per-paper: “Not started per paper”, capped at 15
  rows, sorted by unseen desc, only papers with unseen > 0.

## 6. New tab: Progress

Source: the same replay as the other SRS tabs. One accumulator, counters only.

Per-bucket model `ProgressSlice`:

| Field | Definition |
|---|---|
| `reviews` | Graded reviews in the bucket. |
| `introduced` | Cards whose first-ever logged review is in the bucket. Tracked with a question-id set (bounded by cards). |
| `graduated` | Non-Again reviews on a card whose pre-state was NEW/LEARNING/RELEARNING (a pass moves any of those to REVIEW in FSRS). |
| `relapsed` | Again reviews on a card whose pre-state was REVIEW. |
| `again` | All Again grades. |

Derived: `againRate = again / reviews`, `lapseRate = relapsed / reviews`.

`ProgressData` also carries:

- `totals` for the range (`reviews`, `introduced`, `graduated`, `relapsed`,
  `again`).
- `comparison` — current vs previous equal-length window: reviews, pass rate
  (all reviews, Again = fail), average seconds, study days, and the signed
  deltas. `null` for the All range, which has no previous window.
- `trend` — bucketed slices with the same day/week/month granularity rule as
  the other charts.

Placement: an eighth tab **Progress** after Exam. Cards: totals grid; a
grouped bar/line of introduced vs graduated vs relapsed; Again rate line with
the previous-period delta; a “vs previous period” KPI grid.

## 7. Retention additions

Same `RetentionAccumulator`, three new slices:

1. **By interval band** — reviews grouped by the whole-day interval carried
   into the review: `First`, `1–7d`, `8–21d`, `22–60d`, `61–180d`, `181d+`.
   This shows whether long intervals actually hold. Uses
   `intervalBeforeDays`, already replayed.
2. **By tag** — every tag on a reviewed question contributes that review to
   the tag's count. Reviews are first counted per question; after the pass the
   store loads tags for the reviewed question ids only (bounded by the log's
   working set), and the accumulator folds question counts into tag counts.
   This avoids a whole-bank question→tag map. A review with several tags
   counts once per tag, by design; `reviews` on a tag row says so.
3. **Category retention in the all-papers scope** — the existing group
   breakdown keeps paper as its group when unscoped; a new category slice is
   built from the reviewed question ids the same way tags are, so section-level
   weakness is visible without selecting a paper first.

## 8. Workload additions

Same `WorkloadAccumulator`, from the cards it already pages and the log it
already replays:

| Field | Definition |
|---|---|
| `dueTomorrow` | Scheduled cards due in `[tomorrowStart, tomorrowStart + 1 day)`. |
| `dueThisWeek` | Scheduled cards due in `[todayStart, todayStart + 7 days)`, backlog excluded. |
| `ratingTrend` | Per bucket (same granularity rule): Again / Hard / Good / Easy counts. |
| `sessions` | Sittings split by a 30-minute gap: count, mean reviews, mean minutes, longest minutes, mean seconds per review, and a reviews-per-sitting histogram (`1`, `2–4`, `5–9`, `10–19`, `20–49`, `50+`). |

Placement: near-term figures in the Forecast card footnotes; a “Grade mix over
time” stacked chart; a “Study sessions” summary card.

## 9. Smaller additions

- **Icon-free KPI deltas** on the Progress tab (section 6) rather than
  cluttering every KPI tile.
- **Per-card sheet summary**: Again rate and mean answer seconds computed from
  the history already loaded for the sheet, shown above the raw history.
- **Memory by paper**: mean stability, difficulty and retrievability per paper
  (one row per paper, not per card), unscoped only.

## 10. Deferred (needs new logging or a schema change)

| Metric | Why deferred |
|---|---|
| Distractor analysis (which wrong option is chosen) | `review_log` stores only `wasCorrect`; logging the selected option ids needs a migration, writers on both the study and test paths, import/export coverage and backup versioning. Worth doing as its own change. |
| Multi-select partial credit | Same missing selection data. |
| Backlog history / leech transitions | Current state is a snapshot; history needs periodic snapshots or event columns. |
| Retention by answer mode (study vs test) | Study reviews and test results are separate tables; a combined rate needs a shared event log. |
| Goal adherence | The app has no daily new/review limits, so any “goal” would be invented. |

## 11. Testing plan

- Pure-JVM model tests (`ExamStatsTest`, `ProgressStatsTest`,
  `CoverageStatsTest`): definitions, empty inputs, bucketing, deltas, caps,
  multi-tag attribution, interval-band boundaries, strict-vs-fresh grouping,
  practice-vs-mock grouping and the fixed-target practice trend.
- Aggregator tests (`StatsAggregatorTest`, `StatsTabsStreamTest`): the two build
  paths agree for every new slice, and the existing tabs are unchanged.
- Robolectric/DB tests (`StatsTabsStreamTest` seed) for coverage and exam
  queries: run on CI (x86_64), documented as such locally. Include:
  - a legacy attempt never enters a trend;
  - `rep*` totals match only Random/Balanced attempts with
    `samplingVersion >= 2`;
  - deleting an attempt changes the stored hardest/per-question figures;
  - the partial `question_results` index exists after migration and fresh
    create.
- UI is covered by the existing stats smoke tests; new tabs reuse existing
  chart widgets (`LineChart`, `StackedBarChart`, `DonutChart`, `MeterBar`,
  `KpiGrid`, `WeakCardRow`).

## 12. Acceptance criteria

- [ ] Exam tab shows real attempts, trends, per-paper and category figures,
      and an empty state before any test exists.
- [ ] Exam tab separates strict mocks, Fresh samples, practice, short and
      legacy; no trend mixes families, and a fresh sample is never plotted
      against a strict attempt.
- [ ] Exam tab groups by the full comparability key and uses the shown count
      for the margin and the requested count for comparability.
- [ ] Mock accuracy reads `rep*`; practice/personal views read all-attempt
      counters; both are labelled.
- [ ] Category rows use as-of-attempt titles and report unresolved questions;
      "hardest" says "in stored attempts".
- [ ] The practice per-topic trend compares only runs with the same recorded
      `targetTopicIds`; an empty-target run cannot contribute.
- [ ] Pre-v21 attempts are labelled legacy, never enter a trend, and the
      skip-rate/Tricky limitation is stated.
- [ ] Deleting an attempt updates the stored hardest/per-question figures.
- [ ] Cards tab shows studied/unseen/bookmarked and per-paper unseen.
- [ ] Progress tab shows introductions, graduations, relapses, Again rate and
      a previous-period comparison; All range hides the comparison.
- [ ] Retention tab adds interval-band, tag and all-papers category slices.
- [ ] Workload tab adds due-tomorrow/due-this-week, grade mix trend and session
      summary.
- [ ] Memory tab shows per-paper means; the card sheet shows Again rate and
      mean time.
- [ ] In-memory and streamed builds are equal in tests.
- [ ] `:app:testDebugUnitTest` and `:app:lintDebug` pass; CI is green.
- [ ] The statistics delivery adds no schema change of its own; it depends on
      the bounded-Test v20 → v21 migration (attempts metadata, `rep*` counters,
      partial index), and no statistics tab ships before that migration.
