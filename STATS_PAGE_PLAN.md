# FSRS Study Statistics Page — Implementation Plan

## Goal

An exhaustive, Anki-style statistics page for study (FSRS feature): true retention,
retention trends, FSRS calibration, forecast/workload, memory-state distributions,
heatmap + streaks, response-time analysis, weakest-cards lists, and per-card
drill-down. Hand-rolled Compose Canvas charts, tabbed sections, global + paper
scope.

## Data foundation (no schema changes, no new dependencies)

Everything derives from existing tables:

- `review_log` (`data/local/Entities.kt:180`): rating, reviewedAt, elapsedSeconds,
  stateBefore, stabilityAfter, difficultyAfter, intervalDaysAfter, paperId,
  questionId — the revlog equivalent that nothing reads for stats today.
- `card_state` (`data/local/Entities.kt:137`): current S/D/interval/dueAt/lapses/
  leech/state per card — drives forecast, counts, distributions.
- `domain/DayBoundary.kt`: 04:00 study-day bucketing with injected zone offset —
  all daily buckets use it, never raw midnight.
- `FsrsScheduler.retention()` is pure → retrievability is replayable;
  `FsrsTuner.buildFsrsTrainingCards` (`data/repository/FsrsTuner.kt:78`) is the
  per-card grouping pattern to mirror.

**Known limitations (documented, not blockers):**

- Only Study sessions write `review_log` (Test mode writes `attempts`/`question_results`
  only) — stats reflect Study.
- Anki imports seed `card_state` but create no log rows, so log-based graphs show
  in-app history only; card-based graphs still work.
- `stateBefore = null` rows (seeded history) are classified "unknown" in type
  breakdowns and excluded from calibration's first row.

## Architecture

```
domain/stats/StatsModels.kt     — pure output models per section
domain/stats/FsrsReplay.kt      — per-card replay: predicted R before each answer,
                                  intervalBefore, review kind (learn/review/relearn)
domain/stats/StatsAggregator.kt — pure object: StatsInput -> StatsBundle (~15 section builders)
data/repository/StatsStore.kt   — fetch projections, run aggregator on Dispatchers.Default
ui/stats/StatsViewModel.kt      — AndroidViewModel (default factory), single StatsUiState
ui/stats/StatsScreen.kt         — Scaffold, scope selector, 5-tab row, tab bodies
ui/stats/sections/*.kt          — one file per tab body
ui/stats/CardInfoSheet.kt       — ModalBottomSheet per-card drill-down
ui/stats/charts/*.kt            — Canvas chart toolkit
+ route "stats" (ui/navigation/McqNavHost.kt)
+ drawer row (ui/library/LibraryDrawer.kt + LibraryScreen.kt wiring)
+ History top-bar action (ui/history/HistoryScreen.kt)
+ strings in res/values/strings.xml
```

**Aggregation strategy:** load raw input once (all `review_log` rows + `card_state`
+ question→category map — same scale as the optimizer's `getAll()`), cache in the
ViewModel, slice per range/scope in memory on `Dispatchers.Default` with a
generation counter to cancel stale work. Range/scope switching = no DB hit.
Re-trigger on `observeCount()` change or ON_RESUME (LibraryScreen.kt:87 pattern).

**FsrsReplay:** group rows per question (seeded-first handling exactly like
`buildFsrsTrainingCards`), then per row: `R = retention(state, row.reviewedAt)` →
`state = scheduler.next(state, rating, reviewedAt)`. `intervalBefore` = previous
row's `intervalDaysAfter`. Enables calibration and Anki's true-retention maturity
rule (interval ≥ 21d *at review time*) without storing anything new.

## Sections (5 tabs)

### 1. Overview
- **Today strip** (Anki "Today"): reviews, again-count + correct %, study time,
  learn/review/relearn counts, first-answer retention, due remaining
- **KPI grid**: true retention 30d vs desired, current/longest streak,
  total reviews/days/time, mature retention, avg answer time, estimated knowledge
  (ΣR over reviewed cards), backlog, leeches
- **True retention table**: periods (Today/Yesterday/7/30/90/365/All) ×
  All/Young/Mature — first-review-per-day rule, Again = fail
- **Heatmap**: 365-day calendar grid + current/longest streak
- **Weakest cards list** (lapse count / again-rate from log) → drill-down
- **FSRS status**: fitted-vs-default weights, reviews toward 1000 unlock,
  desired retention, link to Settings

### 2. Retention
- **Retention trend**: daily/weekly/monthly buckets (auto by range), actual line +
  7-day rolling + desired-retention dashed reference
- **Calibration chart** (FSRS-native): predicted R deciles vs actual pass rate,
  diagonal reference, Brier score + predicted-vs-actual headline
- **Retention by paper** (global scope) / **by category** (paper scope):
  horizontal bars
- **Retention by weekday**

### 3. Workload
- **Forecast**: next 30 days due-bars by card state + cumulative overdue line +
  Anki daily load (Σ 1/I)
- **Reviews/day**: stacked bars by stateBefore + cumulative line
  (answer-buttons grouping toggle)
- **Review time/day** bars
- **Answer buttons**: Again/Hard/Good/Easy × card type + % correct
- **Hourly breakdown**: counts + success-rate line
- **Response-time analysis**: distribution histogram, avg time per grade,
  time trend, slowest cards, "answered <5s & Again" detection

### 4. Memory
- **Stability histogram** (+ median/mean)
- **Difficulty histogram** (1–10)
- **Retrievability distribution** + estimated total knowledge
- **Memory growth**: avg interval by review-number cohort + interval of
   reviews over calendar time
- **Interval distribution** histogram + cumulative % overlay

### 5. Cards
- **State donut**: new/learning/relearning/young/mature + total, leech callout

## Chart toolkit (hand-rolled Compose Canvas)

`ui/stats/charts/`:

- **BarChart** — vertical bars, stacked/grouped modes, cumulative-line overlay
- **LineChart** — multi-series, area fill, dashed reference line
- **HistogramChart** — bins + cumulative % overlay
- **DonutChart** — legend + center label + tap highlight
- **HeatmapGrid** — 53×7 calendar grid, color scale, tap tooltip, month labels
- **HorizontalBarList** — label + bar + value rows
- **CalibrationChart** — scatter + diagonal reference
- Shared: `ChartCard` wrapper, tick/nice-number helpers, tap tooltips,
  empty states ("Not enough data yet"), `Modifier.testTag` on every chart

Pure geometry helpers (tick computation, bin edges, label sparsening) kept as
pure functions for JVM tests.

## Constraints honored

- All copy in `res/values/strings.xml`, rendered via `stringResource`
  (StringResourceUsageTest).
- No hex colors outside `ui/theme/VerdictColors.kt` — use
  `verdictColors().rising/falling` for deltas (UiAccessibilityTest).
- No unreferenced helpers (NoDeadCodeTest).
- No DI framework: `AndroidViewModel` + `viewModel()` like History; no new
  Gradle dependencies; no DB migration.
- IconButtons ≥48dp where sized; no no-op `IconButton(onClick = {})`.

## Tests (positive + negative, per AGENTS.md)

- `FsrsReplayTest`: predicted R before 2nd review == `retention(S1, Δt)`; seeded
  history path; first review → null prediction; intervalBefore derivation;
  same-day short-term rows.
- `StatsAggregatorTest`: streaks (empty/single/long), true-retention
  first-per-day + fail rule, 04:00-boundary bucketing (23:59 vs 04:01), scope
  slicing, daily-load formula, histogram bins, calibration deciles + Brier on a
  hand-computed fixture, hourly/weekday buckets, empty input → empty states.
- Chart geometry unit tests (ticks, bin edges, label sparsening).
- Full run (AGENTS.md):

```bash
cd /sdcard/repo/mcq && ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk \
  sh gradlew :app:assembleDebug :app:testDebugUnitTest \
  -Pandroid.aapt2FromMavenOverride=/tmp/ncal-aapt2/aapt2 --no-daemon
```

Robolectric tests that open real SQLite fail on this ARM64 host; CI (x86_64) is
authoritative for those. Pure-JVM domain tests run locally.

## Implementation order

1. `FsrsReplay` + tests
2. `StatsModels` + `StatsAggregator` (section by section) + tests
3. `StatsStore` + DAO projection queries (question→category join) + `StatsViewModel`
4. Chart toolkit
5. `StatsScreen` + 5 section files + route/drawer/History entry + strings
6. `CardInfoSheet` drill-down (S/D/R timeline + review history via
   `ReviewLogDao.historyForQuestion`)
7. Full build + all tests

~30 files, no schema/dependency changes.
