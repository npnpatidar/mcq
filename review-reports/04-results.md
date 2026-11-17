# Phase B report — Results screen and report path

**Batch:** B4 · **Reviewers:** behaviour/actions, UI/accessibility, data integrity (independent, parallel).
**Scope:** `ui/results/ResultsScreen.kt`, `ResultsViewModel`, `TestReport`/`ReportEvidence`, `HistoryStore` result reads, bookmark/export writers and tests. Read-only.

## Severity summary

| Severity | Count | Themes |
|---|---:|---|
| High | 2 | skipped cards rendered as wrong; option states unlabelled |
| Medium | 10 | CSV misclassification, cross-paper scope, bookmark race, retry dead end, hardcoded copy |
| Low/Nit | 9 | paging key, layout, process death, dead fallback |

## Findings

### High

**H1 — Skipped answers look wrong.** `ResultsScreen.kt:1367-1376,1390-1407`: `ungraded = correctOptionIds.isEmpty()` drives the container colour; a skipped row has `selectedOptionIds` empty and `isCorrect=false` (`HistoryStore.kt:243`), so it gets the red `errorContainer` while its tag says "Skipped". The summary row (`:450`) distinguishes them. Use a four-state `Verdict` enum (Correct/Wrong/Skipped/Ungraded) with distinct container and `on*Container` text roles. **Owner: colour semantics.**

**H2 — Screen readers cannot tell which option was correct or chosen.** `ResultsScreen.kt:1483-1500` encodes ✓/✗/●/○ by text + colour with no semantics or labels. TalkBack reads glyphs, not meanings. Merge row semantics with localized "Correct option" / "Your answer" / "Not selected" and `stateDescription`. **Owner: copy.**

### Medium

- **M1 — CSV mislabels ungraded answers.** `ResultsViewModel.kt:206-211` checks only `selectedOptionIds.isBlank()` → "skipped", else `isCorrect` → correct/wrong; it never checks `correctOptionIds`. An ungraded selected answer exports as "wrong", contradicting the on-screen UNGRADED chip (`ResultsScreen.kt:1367,1394`) and `ReportCsv`'s promise. Add an `ungraded` branch and a test for the VM mapping (currently none).
- **M2 — Cross-paper-moved question treated as resolved.** `Daos.kt:2710-2718` LEFT JOINs `questions` and returns `q.categoryId` without checking that the category belongs to `attempt.paperId`. A question moved to another paper still resolves, so `unresolvedResults` (`TestReport.kt:412`), topic matching (`:536,701,1058`) and Study links (`:1035,1324`) can point into the wrong paper. Thread `attempt.paperId` into the query and null the id on mismatch; add a Robolectric test.
- **M3 — Load error is a dead end.** `ResultsScreen.kt:204-213` renders only red error text; `reload()` (`ResultsViewModel.kt:129`, documented as retry) has no caller. Add a Retry button.
- **M4 — SAVED-filter refresh can use a stale bookmark set.** `ResultsViewModel.kt:111-124` collects `observeBookmarkChanges()` (invalidate) and `observeBookmarks()` (state set) independently, and `HistoryStore.kt:517-523` adds a self-removing observer. The paging lambda reads `_state.value.bookmarked` at load time (`:100`); an invalidation that wins the race reloads against the old set with no follow-up. Make the bookmark set the single source of truth.
- **M5 — Study/Test actions drop scope metadata.** `ResultsScreen.kt:289-303` passes only `paperId`, `categoryIds`, type and count; `testRoute` also carries mistakes/minutes/exclusion/breadth/variant (`Routes.kt:89-125`). A TAG/AUTHORED/COVERAGE run relaunches with a different pool, and minutes reset to the paper default. `studyWeakest` can pass a null category id for a moved/deleted question (`TestReport.kt:1324`) → whole-paper study. Persist/forward the full plan or a plan id.
- **M6 — Hardcoded copy and raw exceptions.** `ResultsScreen.kt:353` "No ${filterValue.lowercase()} questions" (yields "No all questions"), `:1524` "Time spent: …", `ResultsViewModel.kt:179` raw load error; `ReviewFilters.kt:12-18` English constants. Add resources; **owner: copy**.
- **M7 — Toast-only / silent feedback.** Export failure uses Toast (`ResultsScreen.kt:147-175`); bookmark failure is `Logger.e` only (`ResultsViewModel.kt:190-198`). Use a shared Snackbar/announcement path. **Owner: wording.**
- **M8 — M3 colour pairing wrong.** Verdict text uses `primary`/`error` on `primaryContainer`/`errorContainer` (`ResultsScreen.kt:1370-1406`); M3 prescribes `on*Container` ([roles](https://m3.material.io/styles/color/the-color-system/color-roles)). Fix with the Verdict enum from H1.
- **M9 — PDF score line mixes denominators.** `ReportPdf.kt:57-58` prints `correct/shown` next to a percentage computed on `correct/answered` (`TestReport.kt:994`); `ReportPdfTest:86` covers only the no-skip case.
- **M10 — Export double-tap/rotation.** `ResultsScreen.kt:135-177` can launch two exports to the same deterministic path (`ResultsViewModel.kt:255-260`); `rememberCoroutineScope` is cancelled on rotation/Back. Disable while exporting; write to a unique temp file.

### Low / Nit

- **L1 — Filter chip can vanish while still active** (`ResultsScreen.kt:318-341`); reset filter to ALL when its condition disappears.
- **L2 — Filter lost on process death** (`ResultsViewModel.kt:55`); use `SavedStateHandle` (rotation is safe via keyed VM).
- **L3 — StoredHeaderCard unreachable** (`ResultsScreen.kt:244-252`): `attempt==null` returns early and `build()` is non-null.
- **L4 — Bootstrap passage membership is current, not as-of-attempt** (`Daos.kt:2715`); affects negative-marking blocks if membership changed.
- **L5 — Redundant bookmark invalidator** (`HistoryStore.kt:517-523` vs `ResultsViewModel.kt:111-113`).
- **L6 — Paging key is questionId** (`ResultsScreen.kt:361`); a repeated question in one attempt would crash Compose. Key on index.
- **L7 — No progress affordance** during report export (`ResultsScreen.kt:137-176`).
- **L8 — Large font scale / landscape squeezes report content** (`:444-458`, `:1379-1416`); important caveats use `labelSmall`.
- **L9 — `review/{attemptId}` route unreachable** (`McqNavHost.kt:149-160`), and the screen's "Review" action only sets a filter. Owner decision; already flagged in Phase A.
- **N1 — Duplicate bookmark invalidation and self-removing observer** (see L5).

## Checked with no defect

Navigation/back stack and FileProvider URI grants; export chooser and ActivityNotFound handling; filter predicates and paging consistency (`HistoryStore.kt:1040-1051` vs `ReviewFilters.apply`); SQL aggregate predicates for graded/ungraded; bookmark flip transaction and sweeps; `question_stats` bump/recompute counters and representative flags; stale-content-hash and deleted-question handling in the report; per-card context copy and verdict tag text; Paging refresh/append retry items; `rememberDelayedVisibility` spinner handling.

## Test gaps

1. VM result→CSV row classification (ungraded, selected-ungraded, skip) — currently `ReportExportCsvTest` tests only `ReportCsv.write`.
2. Historical attempt whose question was moved cross-paper → assert unresolved/category null.
3. Bookmark toggle → SAVED refresh ordering.
4. Load error Retry; export double-tap/rotation; PDF byte output (ignored tests from Phase A).
5. Emulator test for verdict semantics and font-scale/landscape layout (no Results instrumentation today).

## Owner decisions

- Four-state verdict colours and whether skipped stays visually grouped with wrong answers.
- New option-state, empty/chip, error and Snackbar copy.
- Whether Study/Test actions should reproduce the original scope or deliberately start a new plan.
- Wire or delete the unreachable review route.
