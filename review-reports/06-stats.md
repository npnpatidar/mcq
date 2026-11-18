# Phase B report — Stats

**Batch:** B6 · **Reviewer:** full-lens audit of all 20 files in `ui/stats/**` + `StatsStore` and aggregators · **Read-only.**

## Findings

| Sev | Finding | Evidence / fix |
|---|---|---|
| Medium | Calibration evidence pool resets silently after Optimize/refit or grading-rule change; epoch data is computed but never shown. | `Calibration.kt:515-518,642-659`, `StatsStore.kt:282-302`, `RetentionSection.kt:387-398`. Render "evidence window began…" and refit notice. **Owner: copy.** |
| Medium | Drill-down `CardInfoSheet` flashes then disappears on any load error; no error/retry. | `StatsViewModel.kt:114-154`, `StatsScreen.kt:221-223`. Keep sheet with inline error + Retry. |
| Medium | TESTS tab silently discards the selected range; figures are lifetime attempts with no label. | `StatsScreen.kt:131-133`, `StatsViewModel.kt:261-263`, `McqRepository.kt:2051-2055`. Label "All time" or apply range. **Owner: semantics.** |
| Medium | Charts expose no screen-reader values; `contentDescription` is a test tag, values only in Canvas tooltips. | `ChartKit.kt:41-60`, `StackedBarChart.kt:84-96`, `HeatmapChart.kt:63-75`. Human summaries + per-point semantics; move tags to `Modifier.testTag`. |
| Medium | Fixed Canvas heights and 9.sp axis labels clip/overlap at font scale 1.3+ or landscape. | `LineChart.kt:64,80,150` etc. Derive height from font scale, use `labelSmall`. |
| Low | Stability bins labelled in days/weeks though stability is not an interval. | `StatsAggregator.kt:1893-1897` vs `CardInfoSheet.kt:71-75`. Unitless bins + caption. **Owner: copy.** |
| Low | Fabricated "+0.0 pp" bounds when evidence is insufficient. | `RetentionSection.kt:402-428`. Render `—`. |
| Low | `oldestAgeDays ?: 0` shows "0" where mean shows "—"; unknown ratings render "Easy"; tab/scope/range reset on process death; chart series contrast below 3:1. | `CardsSection.kt:249`, `CardInfoSheet.kt:157-162`, `StatsViewModel.kt:42-60`, `ChartKit.kt:131-138`. |

## Checked with no defect

All nine tabs map to slices with loading/error/retry; streaming accumulators and generation guard prevent stale builds; calibration cache key/generation rejects overlapping invalidations; FSRS stability rendered unitless with `—`; chart data labels and tooltips; paper table cap; global question-id uniqueness makes review history unambiguous.

## Test gaps / owner decisions

No Stats instrumentation; no generation-race, cache-invalidation-after-move or a11y/font-scale tests. Decisions: epoch/refit wording, TESTS range semantics, stability bin labels.
