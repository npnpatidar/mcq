# Comprehensive statistics

Status: implemented on `feature/comprehensive-stats`. This document is the
design record and the work plan: every metric below states its definition, its
data source and where it appears. Anything deliberately left out is listed at
the end with the reason.

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
- **No schema change in the first delivery.** Everything in sections 4–8 is
  derived from existing columns. Section 10 lists the metrics that would need
  new logging and why they are deferred.

## 4. New tab: Exam

Source: `attempts` and `question_results` (the same tables History uses).
Scope and range apply: `attempts.paperId`, `attempts.finishedAt` inside the
range window (day-boundary based, matching the SRS tabs).

Model `ExamData`:

| Field | Definition |
|---|---|
| `attempts` | Number of attempts in range/scope. |
| `latestPercent` | Score percentage of the most recent attempt. |
| `bestPercent` | Highest attempt percentage. |
| `averagePercent` | Mean percentage across attempts, weighted by each attempt's `score / maxScore`. |
| `deltaPoints` | Latest minus first percentage, in points (null with one attempt). |
| `totalMinutes` | Sum of `durationSeconds` / 60. |
| `correct` / `wrong` / `skipped` | Sums of the attempt tallies. |
| `averageSecondsPerQuestion` | Total measured `dwellSeconds` ÷ number of timed result rows, from `question_results` in range/scope. |
| `skipRate` | `skipped` ÷ (`correct + wrong + skipped`). |
| `trend` | Mean percentage per time bucket (daily ≤31 days, weekly ≤150, monthly beyond). Empty buckets are kept. |
| `papers` | Per paper: attempts, latest, best, delta points. Empty when scoped to one paper. |
| `categories` | Per category title: graded, correct, accuracy, ranked weakest first, capped at 15. |
| `recent` | The five latest attempts (title, percentage, correct/total, finishedAt). |

DAO additions (aggregate-only, no full result scans):

- `ExamCategoryTotals(since, paperId)` — `SUM(graded)`, `SUM(correct)` grouped by
  `categoryTitle` over results whose attempt finished at/after `since` and
  matches the paper when given.
- `ExamDwellTotals(since, paperId)` — `SUM(dwellSeconds)` and count of timed
  rows.

Placement: a seventh tab **Exam** after Cards. Cards: summary KPI grid, score
trend line, per-paper list, category accuracy bars, timing/skip footnote, recent
attempts (tap opens the existing results screen through the nav callback).

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
  multi-tag attribution, interval-band boundaries.
- Aggregator tests (`StatsAggregatorTest`, `StatsTabsStreamTest`): the two build
  paths agree for every new slice, and the existing tabs are unchanged.
- Robolectric/DB tests (`StatsTabsStreamTest` seed) for coverage and exam
  queries: run on CI (x86_64), documented as such locally.
- UI is covered by the existing stats smoke tests; new tabs reuse existing
  chart widgets (`LineChart`, `StackedBarChart`, `DonutChart`, `MeterBar`,
  `KpiGrid`, `WeakCardRow`).

## 12. Acceptance criteria

- [ ] Exam tab shows real attempts, trends, per-paper and category figures,
      and an empty state before any test exists.
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
- [ ] No schema change, no backfill, no change to scheduling.
