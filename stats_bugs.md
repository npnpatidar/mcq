# Seventh bug report — scoped pass: the statistics feature

A scoped pass over the statistics feature — **status:** report only, nothing
fixed yet. Files covered: the aggregation core (`StatsAggregator.kt`,
`StatsModels.kt`, `FsrsReplay.kt`), the store that feeds it
(`StatsStore.kt`, the `ReviewLogDao`/`CardStateDao`/`QuestionDao` keyset
queries), the ViewModel and screen (`StatsViewModel.kt`,
`StatsScreen.kt`, `StatsWidgets.kt`, `CardInfoSheet.kt`), the five tab
sections, and the chart kit (`ChartKit.kt`, `LineChart.kt`,
`StackedBarChart.kt`, `HeatmapChart.kt`, `MiniCharts.kt`,
`ChartGeometry.kt`). `Trends.kt` (the History screen's score trend) got a
read-through too.

Already-known findings are not repeated: the openCard generation guard
(bugs_all #35), the `statsReviews` dead path (#39), the `question_stats` /
`card_state` / `review_log` orphan sweeps (#8 family), and the Anki
import-time unit notes. What follows is new.

**Confidence legend:** **Confirmed** = defect proven by reading the current
code (two items also verified by running the pure function); **Likely** =
code defect real, one reachability step depends on data shape.

---

## Summary

| # | Sev | Area | Bug | Conf. |
|---|-----|------|-----|-------|
| 1 | Medium | ViewModel | Every Stats entry replays the whole review log twice; the first build's result is always discarded | Confirmed |
| 2 | Medium | ViewModel | In-flight builds are discarded, never cancelled — selection storms run concurrent full-log replays | Confirmed |
| 3 | Low | Charts | StackedBarChart's overlay bridges null gaps; LineChart breaks at them — the hourly pass-rate line implies continuity across empty hours | Confirmed |
| 4 | Low | Charts | `niceMax` violates its contract for values in (0, 1) — `niceMax(0.5)` returns 1.0 (verified by running it) | Confirmed (latent) |
| 5 | Low | Charts | Heatmap rows claim "Monday..Sunday" but are `index % 7` from an arbitrary weekday; the same weekday shifts rows as the window slides | Confirmed (cosmetic) |
| 6 | Low | Aggregator | Three different "overdue" boundaries: overview `backlog` (dueAt < now), workload `forecast.overdue` (dueAt < todayStart), cards `overdueAge` (dueAt < now) | Confirmed |
| 7 | Low | Workload | Time-per-day tooltip shows raw seconds ("3600"), not the "1h" format the axis uses | Confirmed (cosmetic) |
| 8 | Low | ViewModel | A failed stats load has no retry: re-tapping the current tab is a no-op, and the error box has no button | Confirmed |
| 9 | Low | Cards tab | The per-paper table is one LazyColumn item — no recycling; a 1000-paper library composes 1000 rows at once | Confirmed (latent) |
| 10 | Nit | Aggregator | Deleted papers fall back to raw ids in the group-retention and per-paper labels | Confirmed |
| 11 | Nit | Replay | Junk ratings (0, >4) fold silently: `gradeOf` → GOOD, answer-buttons else-branch → EASY, rating 0 counts as a pass | Confirmed |
| 12 | Nit | Aggregator | One fixed `zoneOffset(now)` buckets all history; reviews within ±1 h of the 04:00 boundary on DST-shift days land on the adjacent study day | Confirmed (latent; no DST in IST) |
| 13 | Nit | Sheet | CardInfoSheet truncates predicted retention (`(it*100).toInt()`: 0.999 → "99%") | Confirmed (cosmetic) |
| 14 | Nit | ViewModel | The scope selector's paper list is read once in `init`; a paper created after entering Stats never appears until the VM is recreated | Confirmed |
| 15 | Nit | Overview | The KPI card pairs "Retention" (first-of-day subset) with "Reviews in range" (all reviews) without the qualifier the retention-table help carries | Confirmed (semantics) |

---

## Findings

### 1 · MEDIUM · ViewModel — the whole log is replayed twice on every Stats visit

`StatsViewModel.init` calls `refresh()` (StatsViewModel.kt:88-94). `StatsScreen`
then registers a `LifecycleEventObserver` whose ON_RESUME also calls
`refresh()` (StatsScreen.kt:66-74). Stats is a navigation destination: its
composition runs while the activity is already RESUMED, and
`LifecycleRegistry.addObserver` brings a new observer up to the current state —
so the observer fires ON_RESUME immediately, on top of the init call.

`refresh()` unconditionally does `dropSections()` (generation bump) + rebuild;
there is no "already loading" guard. The net effect on every screen open: two
full `replayInto` passes over the review log start, and the first one's result
is thrown away by the generation check when it lands. On the 1M-review target
this doubles the screen's most expensive read.

The pattern was copied from `HistoryScreen`, where refresh is two cheap
aggregate reads — the cost asymmetry was not carried over.

**Fix.** Delete the `init { refresh() }` (the observer covers the first resume),
or guard the observer with a "has fired once" flag. Alternatively make
`refresh()` a no-op while a build for the same selection is in flight.

### 2 · MEDIUM · ViewModel — discarded builds keep running to completion

`loadVisible` launches its build with `viewModelScope.launch(Dispatchers.Default)`
and keeps no Job; staleness is enforced only after completion
(`if (gen == generation)`, StatsViewModel.kt:228-233, 241-246). A build that is
superseded keeps replaying the whole log — and each rapid tab/range/scope
change starts another replay alongside it. Five quick taps on the range chips
leave up to five concurrent full-log passes on the Default dispatcher.

**Fix.** Keep the current build's Job and `cancel()` it in `dropSections()` (the
aggregators are synchronous per row, so the cancellation lands at a page
boundary), or thread the generation into `replayInto`'s consume loop and bail
there.

### 3 · LOW · Charts — the overlay line bridges gaps the line chart would break

`LineChart` splits its polyline at null values (`started = false`,
LineChart.kt:126-145). `StackedBarChart`'s overlay does not: `overlay!![index]
?: return@forEach` leaves `started` true, so the next non-null point is
connected straight across the gap (StackedBarChart.kt:143-157).

The only caller with nullable overlay values is the hourly breakdown
(`passRate` is null for hours with zero reviews, WorkloadSection.kt:263): the
pass-rate line draws segments between, say, 07:00 and 22:00 as if hours with
no reviews had a measured rate. The two charts in the same feature disagree
about what a gap means.

**Fix.** Mirror LineChart: set `started = false` on a null overlay value.

### 4 · LOW · Charts — `niceMax` breaks its contract below 1.0

`ChartKit.niceMax` documents "the smallest 1/2/5×10ᵏ value at or above
[value]" but computes the exponent with `log10(value).toInt()`, which truncates
toward zero. Verified by running it:

- `niceMax(0.5)` → **1.0** (contract: 0.5)
- `niceMax(0.2)` → **1.0** (contract: 0.2)
- `niceMax(0.09)` → 0.1 (accidentally right: the truncated exponent is −1)

Every current caller guards with `coerceAtLeast(1.0)` (LineChart.kt:58,
StackedBarChart.kt:66,70), so no live chart is wrong — but any future chart
with fractional data (minutes per review, minutes-per-day averages) silently
over-scales its axis.

**Fix.** `floor(log10(value))` (kotlin.math.floor), which also leaves [1, ∞)
behaviour identical.

### 5 · LOW · Charts — the heatmap's rows are not weekdays

HeatmapChart's doc says "columns are weeks, rows Monday..Sunday"
(HeatmapChart.kt:34-37), but the grid places day `index` at row
`index % 7` with column 0 anchored at `todayStart − 364 days` — an arbitrary
weekday. There is no weekday header, so nothing contradicts the claim
on screen, but the same weekday moves to a different row every day the window
slides, and a user who memorises "third row = Wednesday" is wrong tomorrow.

**Fix.** Either align day 0 to the week containing it (pad the first column so
rows are true weekdays), or drop the "Monday..Sunday" claim from the doc. The
first is Anki/GitHub behaviour; the second is one comment edit.

### 6 · LOW · Aggregator — three "overdue" numbers with three boundaries

- Overview today-strip `backlog`: `state.dueAt < now` (StatsAggregator.kt:399)
- Workload forecast `overdue`: `dayIndex < 0`, i.e. `dueAt < todayStart`
  (StatsAggregator.kt:938-940)
- Cards tab `overdueAge`: `state.dueAt < now` (StatsAggregator.kt:1348)

A card due at 09:00 that is still undone at 15:00 appears in the overview's
backlog and the cards' overdue-age bins, but **not** in the workload forecast's
overdue count (it is `dueToday` there instead). Each chart is internally
consistent, but the same word ("backlog"/"overdue") labels different sets on
the same page.

**Fix.** Either document the per-chart windows in the help strings, or align
the forecast with the others (count today's slipped cards in `overdue` and
`dueToday` only up to `now`).

### 7 · LOW · Workload — time-per-day tooltip is unformatted seconds

`TimePerDayCard` maps a day's study seconds into a `StackedPoint`
(WorkloadSection.kt:159-171); `StackedBarChart`'s tooltip prints
`total.roundToInt()` (StackedBarChart.kt:198), so a 1-hour day reads "3600"
under an axis labelled "1h". The chart's own yLabel is `formatDuration`.

**Fix.** Give StackedBarChart an optional tooltip-total formatter (or a
`totalLabel: (Double) -> String`) and pass `formatDuration` from
TimePerDayCard.

### 8 · LOW · ViewModel — a failed load cannot be retried in place

On `loadError` the screen renders a bare error Text with no button
(StatsScreen.kt:121-130), and `selectTab` early-returns when the tab is already
selected (StatsViewModel.kt:145). The only retry path is leaving the screen and
coming back (ON_RESUME). The test session's load-failure screen grew a retry
for exactly this situation.

**Fix.** Add a Retry button that calls `viewModel.refresh()`, and/or make
`selectTab` re-run `loadVisible` when `loadError != null`.

### 9 · LOW · Cards tab — the per-paper table never recycles

`CardsSection` emits `PaperCountsCard` as a single LazyColumn item
(CardsSection.kt:56-58); inside it, `counts.forEach` composes every paper's row
at once (CardsSection.kt:200-202). At the documented thousand-paper ceiling the
tab measures/composes a thousand rows in one frame. The other ranked lists are
capped at 15 (`take(15)`); this one is uncapped.

**Fix.** Either cap the table (e.g. top 20 by total, matching the other ranked
lists) or make each paper a LazyColumn item so it recycles.

### 10 · NIT · Aggregator — deleted papers show raw ids

`groupRetention` labels unscoped groups with `paperTitleOf[key] ?: key`
(StatsAggregator.kt:734-741), and `paperCounts` the same
(StatsAggregator.kt:1392-1403). Reviews logged under a since-deleted paper (the
known #8 orphan family) surface as `paper-1698…` rows ranked by retention.

### 11 · NIT · Replay — junk ratings fold into GOOD/EASY

`FsrsReplay.gradeOf` maps any rating outside 1..4 to GOOD
(FsrsReplay.kt:186-187); the workload's answer-buttons `when` sends the same
junk to the Easy column (StatsAggregator.kt:972-978), and `passed` treats
rating 0 as a pass. Only corrupt rows or hand-edited imports reach it, and the
calibration/true-retention math is unaffected — but a `0` silently inflates
"Easy" instead of being dropped.

### 12 · NIT · Aggregator — one UTC offset buckets all history

Every `load*` passes `DayBoundary.zoneOffset(now)` — the offset at build time —
so all historical day arithmetic uses it (StatsStore.kt:82,111,137,156). A
device that crosses DST (or travels) mis-buckets reviews within an hour of the
04:00 study-day boundary for days on the other side of the shift. India has no
DST, so nothing is observable here; the correct fix (per-instant offsets) is a
known, deliberate-looking simplification worth a comment at least.

### 13 · NIT · Sheet — predicted retention truncates

`"${(it * 100).toInt()}%"` (CardInfoSheet.kt:76) floors: a card at 0.999
predicted shows "99%", one at 0.995 shows "99%" while every other percentage in
the app rounds. Cosmetic; use `roundToInt()`.

### 14 · NIT · ViewModel — the scope selector's paper list never refreshes

`init` reads `observePapers().first()` once (StatsViewModel.kt:90-93). Create a
paper, return to Stats: the new paper is missing from the scope chips until the
ViewModel dies. Every other selector on the page rebuilds on resume; this one
does not.

### 15 · NIT · Overview — KPI semantics need their qualifier on the card

The KPI grid pairs `stats_retention` (computed over each card's *first
qualifying review per day*, a subset) with `stats_reviews_total` (all reviews in
range) — StatsAggregator.kt:433-450 feeds `rangeRetention` (first-of-day rule)
into the KPI while `rangeReviews` counts everything. The distinction is
explained in the true-retention table's help string
(`stats_true_retention_help`), not on the KPI card, so "92% · 148 reviews" can
read as "92% of 148" when the 92% is over fewer reviews. Anki separates the two
into different cards; here a one-word label fix ("True retention") or a help
line on the KPI card would do.

---

## Not bugs (checked and deliberately left alone)

- **`weekdayOf`** (`floorMod(epochDay + 3, 7)`): epoch day 0 was a Thursday, so
  the +3 makes 0 = Monday. Correct.
- **Weekly buckets** anchor on epoch (Thursday-aligned), labels show the bucket
  start; internally consistent, and the first bucket's label is coerced to the
  range start by design.
- **First-of-day cursor fed out-of-range rows** (OverviewAccumulator.add): so a
  day the KPI range excludes cannot hide a later day's first review — deliberate
  and commented.
- **`heatmap()` recomputed by `kpis()`**: the public `kpis()` re-derives the
  streak; the page path passes the heatmap in. One redundant pass only on the
  memory tab — not worth touching.
- **`FsrsReplay.Stream` replays in append order, not `reviewedAt` order**:
  pinned deliberate by `FsrsReplayStreamTest` (clock-rollback semantics).
- **`gradeSeconds` / `avgAnswerSeconds`**: `elapsedSeconds` is written in
  seconds everywhere (StudyStore.kt:529); no unit mix.
- **`onlyFresh` dropping sibling slices**: tab switches rebuild by design —
  without it a stale slice from a superseded selection could show.
