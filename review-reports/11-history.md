# Phase B report — History

**Batch:** B11 · **Reviewer:** full-lens audit of `HistoryScreen`, `HistoryViewModel` and downstream reads · **Read-only.**

## Findings

| Sev | Finding | Evidence / fix |
|---|---|---|
| Medium | Trend lines conflate attempts from different scopes/variants: grouping omits `scopeKey` and `variant`, so "Tag 50" runs on different topics collapse into one curve. | `Trends.kt:27-36` vs comparability in `HistoryStore.kt:694-704,721-729`, `Models.kt:340`. Add both to grouping and label. **Owner: behaviour/copy.** |
| Medium | Tappable attempt row uses `Modifier.clickable` rather than M3 `Card(onClick)`, so TalkBack gets no button role or action label. | `HistoryScreen.kt:192-196`. Use the M3 overload. |
| Medium | Hardcoded English literals ("wrong", "skipped", "attempts", "fresh sample", manual plurals, "pts"). | `HistoryScreen.kt:176-178,223,251-259,280`. Move to resources/plurals. **Owner: scope.** |
| Low | Trend delta conveyed by glyph + colour only; weakest-category progress bar has no semantics. | `HistoryScreen.kt:269-283,150-153`. Add text content descriptions. |
| Low | `refresh()` swallows exceptions; hardest/weakest can be stale/empty with no signal; sections flicker in with no loading state. | `HistoryViewModel.kt:43-52`, `HistoryScreen.kt:128-184`. Expose error/retry. |
| Low | All attempt headers loaded unpaged; display-only weakest/hardest cards cannot launch targeted tests; no scope/range filter; stats icon can stack duplicate destinations. | `HistoryViewModel.kt:22-23`, `HistoryScreen.kt:90`. Page headers, add tap-to-study, `launchSingleTop`. **Owner: features.** |
| Nit | `SimpleDateFormat` built per row per recomposition; fixed 24h pattern; negative-marking percentage vs correct count can look inconsistent. | `HistoryScreen.kt:211-212,223`. Hoist formatter; explain. |

## Checked with no defect

Resume refresh wiring; attempt ordering and header in legacy-only lists; aggregates read from maintained stats and match SQL oracle; percentage conventions; no hidden writes; empty state; typed/variant labels; back/date conversion.

## Test gaps / owner decisions

No History instrumentation; no test for ON_RESUME observer wiring (calling `refresh()` directly would pass if removed); add pure-JVM test for mixed-scope trend grouping. Decisions: trend grouping, localisation scope, making weakest/hardest actionable.
