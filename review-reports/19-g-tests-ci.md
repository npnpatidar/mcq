# Phase G report — tests and CI hardening

**Reviewer:** deep test/CI audit. **Read-only.**

## Verified baseline

167 pure-JVM / 104 Robolectric (+1 helper with the import) / 6 helpers; 15 instrumentation classes, 67 `@Test`. Lint: 154 warnings, 0 errors, no baseline/abortOnError. Ignored: `PdfPaperWriterTest` (5), `ExportWritersTest:676` (1), `DeviceExportedFileTest:130` (assumption). Tautologies: `BitmapDecoderTest:106`, `BrowseCategoryMoveUiTest:119`. `BlueprintBugsTest` comment claims `@Ignore` but none remains. ~166 threshold-only assertions, some with finiteness-only oracles.

## P0 — regression tests for the highest-severity findings

| Finding | Test (suite) | Must assert |
|---|---|---|
| Study double-review | `StudyViewModelStopAfterPersistedGradePullFailureTest` (Robolectric) | exactly one `review_log` row after persisted grade + failed pull + `stop()` |
| Simplified no retry | `StudyUiTest.simplifiedGradedAndStuckHasRetry` (emulator) | Retry reachable; Next gated |
| Test Back cancels save | `TestViewModelTest.backDuringSaveDoesNotCancelAttemptWrite` | attempt committed when arrow pressed mid-transaction |
| Settings failed restore wedge | `SettingsViewModelTest.failedRestoreResetsBusy` | `busy=false` after both catches |
| Editor formula after reorder | `BlockListEditorTest.formulaCallbackUsesStableBlockId` + emulator JS test | write lands in original block |
| Passages stale count | `PassageStoreTest.crossesPaperRechecksMemberCountAtSave` | no silent group move when membership changed off-screen |
| Import suggestions / Anki drops | `ImportUiTest.novelSuggestionNameIsAccepted`; `AnkiApkgImporterTest.droppedAndRecallNotesSurfaced` | `PrimaryEditable`; warnings enumerate drops |
| Results verdict | `ResultsUiTest.skippedNotRenderedAsWrong` + `optionStateSemantics` | four-state verdict, merged semantics |
| CardScheduleMapping | `CardScheduleMappingTest.bareKeyCollisionAndCraftedNulId` (pure-JVM) | v1/v2 precedence, no cross-card restore |
| Cross-paper move | `CrossPaperMoveTest.collisionDropsSourceHistoryAndInvalidatesCalibration` | destination wins; cache key miss |
| question_stats standing | `QuestionStatsRecomputeTest.lastStandingAfterSkipAndUngraded` | incremental equals recompute |

## P1 — instrumentation gaps

Zero emulator coverage: History, Bookmarks, Import, Search, Sessions, Stats, full Test session, full Results. Add at least `HistoryUiTest` (ON_RESUME wiring — calling `refresh()` directly would pass if the observer were removed), `BookmarksUiTest` (share render survives Back/rotation), `ImportUiTest`, `SearchUiTest` (scope-chip debounce cue), `SessionsUiTest`, `StatsUiTest`, `TestSessionUiTest`, `ResultsUiTest`.

## P1 — guard strengthening (no new dependency; owner approval for scope)

- `WebViewLifecycleTest`: scan all WebView sites, exact expected count, fail on zero; add a11y-label and JS/CSP assertions.
- `StringResourceUsageTest`: include format-arg calls (117 currently missed) and `pluralStringResource`; add defined-but-unused and literal-literal checks.
- `NoDeadCodeTest`: nav-graph reachability (catches `review/{attemptId}`) and uncalled DAO methods.
- `UiAccessibilityTest`: per-surface accounting, RadioButton roles, chart test-tag-as-description.
- `VersionCodeGuardTest`: execute mapping instead of literal string check.
- Lint: baseline/abortOnError to freeze 154 warnings and fail on new ones.

## P1 — suite splitting

Codify local pure-JVM selection as a Gradle task rather than a grep from docs; local gate = assemble + three Kotlin compiles + pure-JVM. CI gate = full unit suite + lint + emulator. Consider Robolectric sharding / `maxParallelForks` against the 10-minute timeout. Never add SQLite driver/Robolectric ARM workaround without owner approval.

## P2 — flakiness, workflows, docs

Standardise the dialog-scoped wait + scroll-into-view + DB-state pattern from the three failed CI runs (`7b67b15`, `6439fe6`, `79d2c21`); ban fixed sleeps. Replace the `assumeTrue` skip with a committed fixture; move ignored PDF tests to device; delete tautologies. `build.yml` stale minify comment; `::add-mask::` before keystore file exists masks empty string; artifact docs omit `test-results`/`lint-report`/`ui-test-results`; no `permissions`/`concurrency`/ui-test timeout; actions pinned by tag not SHA. `release.yml` has no release-notes gate. Doc drift: 0.0.52 current, ≈124 local classes, BlueprintBugs comment.

## Definition of done (proposed)

Feature branch + draft PR; root cause fixed; named regression test demonstrated failing on old code; no new ignores/tautologies; local + CI green with run id recorded; guard extended when the defect class was unguarded; docs/ledger updated; user-visible change has owner-approved plain-language release notes.

## Owner decisions

Instrumentation scope, SHA pinning/permissions/concurrency, minify in CI release, PDF tests on device, lint baseline policy, local pure-JVM task, guard expansion, release-notes gate.
