# Statistics page audit — 0.0.23 → HEAD

Scope: everything since tag `0.0.23` — the FSRS statistics page (domain layer,
charts, tests) plus the small results/history/nav changes.

Verification basis:

- CI run `38732753081` (commit `b5ee9dd`, `feat/stats-page` PR run) is green:
  build, unit tests, Robolectric UI tests, release build. All 50 new tests pass
  (StatsAggregatorTest 26, FsrsReplayTest 5, StatsChartKitTest 10,
  StatsPageStructureTest 5, ResultsViewModelTest 4).
- FSRS maths checked against the official reference implementation
  (open-spaced-repetition/fsrs-rs) and the true-retention rule against Anki's
  own source (ankitects/anki, `rslib/src/stats/graphs/retention.rs`).
- Compose behaviour (Spacer/Canvas measurement) verified against the bytecode
  of `foundation-layout` 1.12.1 (`SpacerMeasurePolicy`).

Severity legend:

- **P0** — feature broken on device; must fix before release.
- **P1** — wrong statistics vs the stated/official definition.
- **P2** — misleading display or small conformance gap.
- **P3** — cosmetic/edge-case nit.

| ID  | Sev | Component            | One-liner                                                            |
|-----|-----|----------------------|----------------------------------------------------------------------|
| F1  | P0  | HeatmapChart         | Canvas has no height → renders 0 dp tall, calendar invisible         |
| F2  | P0  | CalibrationChart     | No height, and origin y computed from the top → scatter off-canvas   |
| F3  | P1  | HeatmapChart         | Tap handler assumes 14 dp cells; drawn cells are width/53 → wrong day|
| F4  | P1  | StatsAggregator      | True retention counts first exposures; Anki's rule excludes them     |
| F5  | P2  | StatsAggregator      | Seeded/unknown reviews silently counted as "Learning" answer buttons |
| F6  | P2  | ChartKit/LineChart   | Rate-axis ticks 0.25/0.75 labelled "0.3"/"0.8"                       |
| F7  | P2  | CardInfoSheet/Heatmap| Timestamps and heatmap dates rendered in UTC, not the device zone    |
| F8  | P3  | StackedBarChart      | Left-gutter taps select bar 0 (truncation toward zero)               |
| F9  | P3  | LineChart            | Area fill bridges null gaps the stroke breaks                        |
| F10 | P3  | StatsAggregator      | memoryGrowth review numbering consumed by rows without an interval   |
| F11 | P2  | Plan gaps            | Several promised charts/toggles/tests not implemented (see §3)       |

---

## 1. Findings and fixes

### F1 (P0) — HeatmapChart renders at zero height

File: `app/src/main/java/com/mcqapp/ui/stats/charts/HeatmapChart.kt:55-58`
Caller: `app/src/main/java/com/mcqapp/ui/stats/sections/OverviewSection.kt:184-187`

```kotlin
Canvas(
    modifier = modifier          // defaults to Modifier; only fillMaxWidth() inside
        .fillMaxWidth()
        .pointerInput(days) { ... }
) { ... }
```

Compose `Canvas` is `Spacer(modifier.drawBehind(onDraw))`, and `Spacer`'s
measure policy (verified in the foundation-layout 1.12.1 bytecode) reports
`height = constraints.maxHeight` **only when `constraints.hasFixedHeight`**,
otherwise `0`. Inside `ChartCard`'s `Column` a child gets
`minHeight = 0, maxHeight = remaining`, which is *not* fixed → the canvas is
0 dp tall. `LineChart` and `StackedBarChart` avoid this by hard-coding
`.height(200.dp)`; `HeatmapChart` forgot it. The calendar grid is invisible on
device (the streak summary row below it still shows).

Suggested fix — the grid height derives from the width (`weeks` columns of
`cell`), so compute it with `BoxWithConstraints`:

```kotlin
BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
    val gap = 2.dp
    val cell = (maxWidth - gap * (weeks - 1)) / weeks
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp + 7 * cell + 6 * gap)   // topOffset + 7 rows + 6 gaps
            .pointerInput(days) { ... }           // see F3: reuse this `cell`
    ) { /* existing draw block, using the same `cell` */ }
}
```

Test: a Robolectric compose test asserting the chart node has non-zero height
(CI x86_64 is authoritative for Robolectric). If that is too heavy, a
structure test in the style of `StatsPageStructureTest` asserting
`HeatmapChart.kt` contains an explicit height computation is a weak but
consistent guard.

### F2 (P0) — CalibrationChart: zero height + origin above the canvas

Files: `app/src/main/java/com/mcqapp/ui/stats/charts/CalibrationChart.kt:78-83`
Caller: `app/src/main/java/com/mcqapp/ui/stats/sections/RetentionSection.kt:124-128`

Two bugs, either of which blanks the chart:

1. Same missing-height problem as F1 (`Canvas(modifier.fillMaxWidth())`, no
   height, and `CalibrationCard` passes no modifier either).
2. Even with a height, the origin is wrong:

```kotlin
val bottomPad = 18.dp.toPx()
val origin = Offset(leftPad + 4.dp.toPx(), bottomPad + 0f)   // y measured from TOP
```

In Compose, y grows downward, so the bottom-left corner of the plot must be
`size.height - bottomPad`. As written, origin.y ≈ 18 dp from the top and the
plot (axes, diagonal, points) is drawn with negative y — entirely off-canvas.

Suggested fix:

```kotlin
Canvas(modifier = modifier.fillMaxWidth().height(220.dp)) {
    ...
    val origin = Offset(leftPad + 4.dp.toPx(), size.height - bottomPad)
    ...
}
```

Tick-label y positions (`CalibrationChart.kt:100`) already use
`origin.y - plot * fraction`, so they follow automatically.

Test: same approach as F1.

### F3 (P1) — Heatmap tap-to-tooltip selects the wrong day

File: `app/src/main/java/com/mcqapp/ui/stats/charts/HeatmapChart.kt:59-67` vs `:70`

The tap handler hard-codes a cell pitch:

```kotlin
val cell = 12.dp.toPx() + 2.dp.toPx()      // 14 dp assumed
val col = ((tap.x) / cell).toInt()
val row = ((tap.y - topOffset) / cell).toInt()
```

but the draw block sizes cells from the actual width:

```kotlin
val cell = (size.width - gap * (weeks - 1)) / weeks        // ~5 dp on a phone
```

The two agree only when the canvas is exactly 53 × 14 dp ≈ 742 dp wide — never
on a phone. Taps land on the wrong cell and the tooltip describes a day the
user did not touch. Fix together with F1: compute `cell` once (in the
`BoxWithConstraints` scope) and use the same value in both the pointer-input
handler and the draw block.

### F4 (P1) — True retention counts first exposures (deviates from Anki)

File: `app/src/main/java/com/mcqapp/domain/stats/StatsAggregator.kt:154-170`
Call sites: retention table `:206`, range KPI `:915`, weekday `:371`,
retention trend `:258` — one fix covers all four.

Current rule (from the KDoc and code): "only the first review of each card on
each study day counts". That is **not** Anki's rule. Anki's official
implementation (`rslib/src/stats/graphs/retention.rs`) counts a review iff

```rust
review.review_kind == RevlogReviewKind::Review
    || review.last_interval <= -86400      // relearn of a ≥1-day-scheduled card
    || review.last_interval >= 1           // previous interval ≥ 1 day
```

i.e. **learning-step reviews and first exposures are excluded** (their
previous interval is < 1 day). The app's first-of-day dedup keeps the first
review of the day even when it is a card's very first exposure
(`stateBefore == NEW`, `intervalBeforeDays == null`), whose trivial "Good"
pass inflates retention — most for learning-heavy users. Everything else
matches Anki: Again-only fails (`StatsModels.kt:42`), mature = interval
carried into the review ≥ 21 (`StatsModels.kt:45`, Anki's `MATURE_IVL = 21`).

Fix — filter qualifying rows in `firstOfDayReviews` to those that carried an
interval:

```kotlin
.filter { it.reviewedAt >= windowStart && it.reviewedAt < windowEnd }
.filter { (it.intervalBeforeDays ?: 0) >= 1 }     // Anki: prev interval ≥ 1 day
.groupBy { ... }
```

This is a faithful mirror for this scheduler: graduated/review rows always
carry ≥ 1-day intervals, learning steps never do (no step ladder — a failed
card returns the same sitting), and relearning rows carry the pre-lapse
interval. The young/mature split (`matureAtReview`) is unchanged. Update the
KDoc at `:154-159` accordingly.

Tests to add in `StatsAggregatorTest`:

- a first exposure (`stateBefore = NEW`, `intervalBeforeDays = null`) does not
  appear in `retentionTable` nor `kpis.retention`;
- a same-day relearn step (`stateBefore = RELEARNING`, `intervalBeforeDays = 0`)
  does not count;
- the existing fixtures (`intervalBeforeDays = 30` / `= 5`) still count.
  The current test `trueRetentionCountsOnlyTheFirstReviewPerCardPerStudyDay`
  keeps passing unchanged (both its qualifying rows carry ≥ 1-day intervals).

### F5 (P2) — Answer buttons count seeded/unknown rows as "Learning"

File: `app/src/main/java/com/mcqapp/domain/stats/StatsAggregator.kt:518-528`

```kotlin
val kind = when {
    review.kind == ReplayReview.ReviewKind.RELEARNING -> ...RELEARNING
    review.kind == ReplayReview.ReviewKind.REVIEW && matureAtReview -> ...MATURE
    review.kind == ReplayReview.ReviewKind.REVIEW -> ...YOUNG
    else -> AnswerButtonsData.Kind.LEARNING          // catches kind == null
}
```

Anki-imported rows with unknown pre-state (`kind == null`) are lumped into the
**Learning** group, silently inflating it — while `reviewsPerDay` gives the
same rows their own slice (`:126 null -> unknown++`). Inconsistent and the
"Learning" label is a lie for those rows. Since an unknown-state row cannot be
classified, the cleanest fix is to exclude them from this chart:

```kotlin
inRange.filter { it.kind != null }.forEach { ... }
```

Update the KDoc and extend
`answerButtonsSplitByMaturityAndKind` with a negative case (a `stateBefore =
null` row must not change any group).

### F6 (P2) — Rate-axis ticks mislabelled on the retention trend

Files: `app/src/main/java/com/mcqapp/ui/stats/charts/ChartKit.kt:170-177`,
`LineChart.kt:59`, caller `RetentionSection.kt:66-82`.

With `fixedMax = 1.0`, `yTicks(1.0, 4)` produces `[0, 0.25, 0.5, 0.75, 1.0]`.
`TrendCard` doesn't pass `yLabel`, so the default `formatCount` formats the
ticks: 0.25 → `"%.1f"` → **"0.3"** (HALF_UP) and 0.75 → **"0.8"**. The axis
gridlines sit at 25 %/75 % but read 0.3/0.8. Fix at the call site:

```kotlin
LineChart(
    labels = labels,
    series = ...,
    fixedMax = 1.0,
    referenceLine = trend.desiredRetention,
    yLabel = { formatPct(it) },        // 0.25 → "25%"
    emptyText = { ChartEmpty(R.string.stats_no_data) }
)
```

(any chart with `fixedMax = 1.0` should do the same; today only TrendCard
does). Add a `StatsChartKitTest` case pinning the intended formatting if the
fix is done in `formatCount` instead.

### F7 (P2) — Timestamps rendered in UTC instead of the device zone

Files:
- `app/src/main/java/com/mcqapp/ui/stats/CardInfoSheet.kt:62` (due date),
- `app/src/main/java/com/mcqapp/ui/stats/CardInfoSheet.kt:107` (history rows),
- `app/src/main/java/com/mcqapp/ui/stats/charts/HeatmapChart.kt:74-75`
  (`dayDate`, used for tooltips **and** month labels).

All three format with `Instant.ofEpochMilli(x).atOffset(ZoneOffset.UTC)`. For
any user not at UTC+0 the Card Info sheet shows due dates and review history
shifted by the zone offset, and the heatmap tooltip/labels name the previous
day (`dayStart` is a 04:00 local instant; UTC date of it is the prior day for
positive offsets). The aggregation layer is careful to bucket everything by an
injected zone (`DayBoundary.zoneOffset`), but the UI throws the zone away.

Fix — display in the device zone (this is UI, determinism is not required):

```kotlin
Instant.ofEpochMilli(x).atZone(ZoneId.systemDefault()).format(format)
```

and for the heatmap day date, `atZone(ZoneId.systemDefault()).toLocalDate()`.

### F8 (P3) — StackedBarChart gutter taps select bar 0

File: `app/src/main/java/com/mcqapp/ui/stats/charts/StackedBarChart.kt:89`

```kotlin
val index = ((tap.x - left) / slot).toInt()
```

`.toInt()` truncates toward zero, so a tap in `[left - slot, left)` maps to
`-0.x → 0` — the right half of the left gutter selects bar 0. Use floor:

```kotlin
val index = floor((tap.x - left) / slot).toInt()
```

### F9 (P3) — LineChart area fill bridges null gaps

File: `app/src/main/java/com/mcqapp/ui/stats/charts/LineChart.kt:100-111`

The stroke path correctly breaks at null gaps (`:113-133`), but the fill path
connects the first non-null index to the last, drawing a filled area across
gaps where there is no data. Split the fill per contiguous run of non-null
indices (each run: moveTo baseline → lineTo points → lineTo baseline → close).

### F10 (P3) — memoryGrowth numbering skewed by rows without intervals

File: `app/src/main/java/com/mcqapp/domain/stats/StatsAggregator.kt:719-724`

```kotlin
rows.sortedBy { it.reviewedAt }.forEachIndexed { index, row ->
    val interval = row.intervalDaysAfter ?: return@forEachIndexed
    val number = (index + 1).coerceAtMost(cappedNumbers)
```

Rows lacking `intervalDaysAfter` (e.g. a seeded first row recorded without an
interval) still consume an index, so every later review-number cohort shifts
down by one for that card. Number only rows that contribute:

```kotlin
var number = 0
rows.sortedBy { it.reviewedAt }.forEach { row ->
    val interval = row.intervalDaysAfter ?: return@forEach
    number += 1
    byNumber.getOrPut(minOf(number, cappedNumbers)) { mutableListOf() }
        .add(interval)
}
```

### F11 (P2) — Plan vs implementation gaps

From `STATS_PAGE_PLAN.md` — promised but absent; either implement or update
the plan:

- Plan line 94 — **Reviews/day**: no cumulative line and no
  answer-buttons grouping toggle (`WorkloadSection.kt:129-151`).
- Plan lines 111-112 — **Cards tab**: "Overdue age" breakdown and
  "Per-paper card counts" not implemented (`CardsSection.kt` has only the
  donut + leeches).
- Plan §4 — **Interval distribution**: "+ cumulative %" overlay missing
  (`MemorySection.kt:113-135`).
- Plan §4 — **Memory growth over time**: described as "stability of reviews
  over calendar time", but the chart averages `intervalDaysAfter`
  (`StatsAggregator.kt:746`), not `stabilityAfter`. Either plot stability or
  amend the plan.
- Plan line 151 — "Chart geometry unit tests (ticks, bin edges, label
  sparsening)": only formatters/`niceMax`/`yTicks`/`xAxisLabelStep` are
  tested. The untested geometry is exactly where F1-F3 hid.

Audit opinions on each item (with reasoning):

| Item | Verdict | Reasoning |
|---|---|---|
| Reviews/day cumulative line | Drop | Rises to the range total, which the KPI grid already states; a second right axis for near-zero information |
| Reviews/day grouping toggle | Drop | "When do I lapse?" is already answered by the retention trend (daily pass rate) and the hourly pass-rate overlay |
| Overdue age breakdown | Implement | High value-per-cost: `card.dueAt` is already loaded, one binned aggregation, completes the Cards-tab story ("how stale is my backlog") |
| Per-paper card counts | Implement | Only cross-paper comparison view; scope switching loses the comparison; trivial MeterBar table |
| Interval cumulative % | Implement first | Overlay machinery already exists in StackedBarChart; the CDF answers the workload-horizon question mean/median cannot |
| memoryGrowth → `stabilityAfter` | Implement | Plan intent; unclamped (interval saturates at `maxIntervalDays`, hiding long-horizon growth); units match the stability histogram; merges with F10 |
| Geometry tests | Implement, targeted | Ticks/bin-edges/label-step are already tested; add pure helpers + tests only for the render geometry where F1-F3 hid |

---

## 2. Verified correct — do not "fix"

Checked against official sources and hand computation; listed so the fixer
doesn't churn working code:

- **FSRS-6 defaults** (`Fsrs.kt:298-320`) equal
  `FSRS6_DEFAULT_PARAMETERS` from fsrs-rs value-for-value, including
  `w20 = 0.1542`.
- **Retrievability** `R = (1 + FACTOR·t/S)^DECAY` with
  `FACTOR = 0.9^(1/DECAY) − 1`, `DECAY = −w20` — matches fsrs-rs
  `current_retrievability`.
- Difficulty update (linear damping `(10−D)·ΔD/9` + mean reversion to
  unclamped `D0(Easy)`), recall/post-lapse/short-term stability formulas,
  interval `I = (S/FACTOR)·(r^(1/DECAY) − 1)` clamped ≥ 1 day — all match the
  FSRS-6 reference formulas.
- Desired retention default 0.9; optimizer unlock `MIN_REVIEWS = 1000`
  (`FsrsOptimizer.kt:117`) — matches Anki's guidance; the stats page's unlock
  gauge reports it correctly.
- Brier/binning maths on the hand-computed fixture (0.029475) — correct. Brier
  is not FSRS-benchmark convention (RMSE(bins)/log-loss) but is a legitimate
  calibration metric; no change required. (Nit: the Brier sum uses the
  `0.999999`-clamped prediction, `StatsAggregator.kt:310` — difference
  ≤ 1e-12, harmless.)
- Streaks (today-without-reviews does not break; 04:00 boundary), heatmap
  day bucketing, longest-streak, forecast buckets/overdue/due-today, daily
  load Σ 1/I (Anki's definition), hourly wall-clock buckets, weekday mapping
  (epoch day 0 = Thursday → `floorMod(day+3,7)`, 0 = Monday), monthly bucket
  keys (`year*12 + month−1`), histogram bins/medians, estimated knowledge
  Σ R over cards with memory — all correct.
- `FsrsReplay` (seeded-history seeding, first-review null prediction,
  per-card independence, caller-order preservation) — correct, and the
  calibration replay semantics ("predicted at review time with current
  weights", learning rows excluded) are sensible and documented.
- `ResultsViewModel` stale-load generation guard and its new test — correct.
- `StatsViewModel` generation counter, scope/range re-slicing, follow-up
  question-text read — sound.
- Navigation/drawer/history entry points and strings — present and wired
  (covered by `StatsPageStructureTest`).

## 3. Suggested fix order

1. F1 + F2 + F3 (one commit: chart geometry — heatmap height, calibration
   height + origin, shared tap/draw cell).
2. F4 (true-retention predicate + tests + KDoc).
3. F5, F6, F7 (small correctness/display fixes + tests).
4. F8, F9, F10 (polish).
5. F11 (implement or explicitly de-scope the plan items).

---

## 4. Fix verification — commits `7622406`..`af79808` (verified, approved)

Verified against CI run `38780015015` (branch `feat/stats-page`, green: build,
Robolectric UI, release; `StatsAggregatorTest` 30/30, new
`StatsChartGeometryTest` 3/3). Each finding was re-checked against the diff
of `b5ee9dd..HEAD`.

| ID  | Verdict | Notes |
|-----|---------|-------|
| F1  | ✅ Approved | `HeatmapChart` computes `cell` from `maxWidth` and sizes the canvas to the exact grid height (`ChartGeometry.heatmapHeight`) — renders |
| F2  | ✅ Approved | `CalibrationChart` gets `.height(220.dp)`; origin at `size.height - bottomPad` — axes, diagonal and points are on-canvas |
| F3  | ✅ Approved | Tap and draw share the same geometry via pure `ChartGeometry` helpers; column/row mapping matches the drawn grid |
| F4  | ✅ Approved | `.filter { (it.intervalBeforeDays ?: 0) >= 1 }` inside `firstOfDayReviews`; all four consumers inherit it; two tests pin the rule (first exposure excluded, same-day relearn step excluded) |
| F5  | ✅ Approved | `answerButtons` filters `kind != null`; no more silent "Learning" inflation |
| F6  | ✅ Approved | `TrendCard` passes `yLabel = { formatPct(it) }` — ticks read 25%/75% |
| F7  | ✅ Approved | `CardInfoSheet` and `HeatmapChart.dayDate` use `ZoneId.systemDefault()` |
| F8  | ✅ Approved | `ChartGeometry.barIndexAt` floors — gutter taps no longer select bar 0 |
| F9  | ✅ Approved | `LineChart` fill split into contiguous non-null runs |
| F10 | ✅ Approved | `memoryGrowth` numbers only contributing rows; re-pinned for `stabilityAfter` by test |
| F11 | ✅ Completed | Overdue-age bins + per-paper counts implemented and tested (scope-gated); reviews/day line and grouping toggle dropped with the plan amended; `memoryGrowth` switched to `stabilityAfter` (both series, strings and tests); `ChartGeometry` extracted with 3 JVM tests satisfying the plan's geometry-test promise |

One residual defect found in this round — **fixed in place** (applied on top
of `af79808`, uncommitted at audit time):

- ~~`HeatmapChart` still admits label-strip taps for columns ≥ 1.~~
  The composite-index guard moved into a pure, tested helper:
  `ChartGeometry.heatmapIndex(col, row)` returns null for a negative row, the
  composable selects only on a non-null in-bounds index, and
  `heatmapLabelStripTapsNeverFoldIntoAnotherColumn` pins the regression
  (col 1 / row −1 must not resolve to the previous column's last cell).
  Verified locally on the JVM (`StatsChartGeometryTest` 4/4).

The previous legend nit is moot: the reviews/day cumulative overlay was
removed with the F11 disposition. Nothing else remains; the audit is closed
pending the CI run for this last change.
