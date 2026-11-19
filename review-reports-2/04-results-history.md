# Round-2 review — Results, History, Sessions, Bookmarks

**Branch:** `feature/review-round-2` @ `fbc8e15` (= main, release 0.0.61). Read-only; no product/test/docs code changed.
**Scope:** `ui/results/**`, `ui/history/**` (History + Bookmarks), `ui/sessions/**`, CSV/PDF report export, trend/range grouping, per-paper scope, bookmark filters, moved-question handling, delete/undo, load-error retry; cited data-layer helpers where a UI claim depends on them.
**Method:** old `review-reports/04,11,12,13` treated as **claims**; `git log/diff 0.0.53..HEAD` on those paths read in full, with current sources and tests. Platform claims cited inline.

## Verdict

Most medium claims from the earlier audits are **genuinely fixed** — the four-state verdict, ungraded CSV labelling, cross-paper move scoping, load-error Retry, bookmark-race ordering, DST-safe day buckets, session range/error UX, trend scope separation and double-tap gate all hold in code and tests. But two cancellation- and i18n-driven defects were **missed or only partially fixed**: the share/export coroutine is *still* owned by `rememberCoroutineScope` (the release notes claim this was fixed), and user-facing filter/format copy stays English under the Hindi locale. Several low leftovers from the old reports were simply never carried into a fix batch.

## Findings

**R1 — Medium — Share/export work is still cancelled by rotation or Back; the chooser silently never appears.** `ResultsScreen.kt:111,133-153` launches `viewModel.exportCsvIntent()/exportPdfIntent()` in `rememberCoroutineScope`; `BookmarksScreen.kt:84,272-289` does the same with `shareBookmarksIntent()`. The VM functions only move the *inner* work to `withContext(IO)` (`ResultsViewModel.kt:225-234`; `BookmarksViewModel.kt:129-157`) — they inherit the caller's Job. Compose's remembered scope is "cancelled when the call leaves the Composition" ([side-effects](https://developer.android.com/develop/ui/compose/side-effects)), and rotation recreates the Activity with a fresh composition ([runtime changes](https://developer.android.com/guide/topics/resources/runtime-changes)). Rotate/Back mid-render → the write is cancelled, the `startActivity(chooser)` line is never reached, and a partial file can remain in `cacheDir`. The old reports 04-M10 and 13-M9 explicitly asked for this; the new `ExportGate` fixes only the double-tap, and `BookmarksUiTest` has no share-lifecycle test. *Why missed:* fixes targeted the write path and the double-tap; no one re-checked which coroutine owns the call. *Fix:* VM-owned one-shot `SharedIntent` event; launch the render in `viewModelScope`, emit the Intent, let the screen only start the chooser.

**R2 — Medium — Results filter chips and the empty-state message are untranslated.** `ResultsScreen.kt:377-390` renders `label` raw for every chip except SLOW; the constants are English (`ReviewFilters.kt:12-18`). `:403` interpolates `filterValue.lowercase()` into a Hindi sentence resource → "कोई all प्रश्न नहीं". Hindi resource files are complete, so this is a call-site omission, and `ResultsUiTest:176,179-185` actively pins the English labels. Old 04-M6 asked for localised copy but only the surrounding text was resource-ified. *Fix:* `@StringRes` on `ReviewFilters`, `stringResource(labelRes)` for chips and the empty state.

**R3 — Medium — Bookmarks export-format dialog stays English under the Hindi locale.** `BookmarksScreen.kt:326,328` render `format.title`/`format.description`, which are eight English literals in `ExportFormat.kt:11-60`; the dialog title at `:294-300` is a Hindi plural, so the dialog is half-translated. Already filed for Library in `review-reports-2/01-library-navigation.md` (L5); it affects this screen in scope too. *Fix:* resource ids on `ExportFormat`, resolved with `stringResource` at each call site (Browse/Settings too).

**R4 — Medium — "Study/Test" relaunch still drops the original minutes and breadth.** `ResultsScreen.kt:336-352` passes `plan.minutes` and `plan.breadth`, but `RelaunchPlanner.from` hardcodes `minutes = USE_PAPER_DURATION` and leaves `breadth` empty (`TestReport.kt:253-259`), and `Attempt` stores neither field (`Models.kt:313-346`). The comment at `ResultsScreen.kt:338-340` ("the rest is the original plan, so the relaunch matches what the report describes") overclaims: a 20-minute run relaunches at the paper's default and a custom breadth becomes the medium default. Old 04-M5 fixed categories/exclusion/variant/type/count but not these two. *Fix:* persist minutes/breadth on the attempt (owner/schema call) or soften the claim to "starts a fresh run with this scope".

**R5 — Low — History row and trend a11y leftovers.** `HistoryScreen.kt:197-202` uses `Card(.clickable{})`, so TalkBack gets no button role or `onClickLabel` (old 11-Medium). `:303-317` conveys the delta by ▲/▼ + colour only, with no spoken "improved/declined"; the weakest-category progress bar at `:154-157` has no semantics. All copy is now resource-backed, so this is purely an a11y pass. *Fix:* `Card(onClick=, onClickLabel=)` and merged content descriptions.

**R6 — Low — History top-lists fail silently.** `HistoryViewModel.kt:42-53` catches and only logs; `hardest`/`weakest` keep the last (possibly empty) value with no error or Retry, and `refresh()` has no loading signal (old 11-Low). The ON_RESUME observer is correct. *Fix:* expose error/loading in state and a Retry action.

**R7 — Low — Bookmarks header count can disagree with the list, and the empty state can flash.** `HistoryStore.kt:92` → `Daos.kt:2300` counts all `bookmarks`, while the list/export resolve questions (`Exporter.kt:320-331`); a dangling bookmark shows a share button on an empty list (old 13-Low). `BookmarksScreen.kt:153` treats initial `count == 0` as "no bookmarks" before the Flow emits, so a brief empty state precedes real rows; `isSharing`/`refresh is Loading` is ignored for initial load.

**R8 — Low — Bookmarks dialog selection is not rotation-safe and radio rows are unlabelled.** `BookmarksScreen.kt:81-82` uses plain `remember` for `showFormatDialog`/`exportFormat`, so rotation resets a chosen format to JSON_INLINE (only `pendingFormat` is saveable, `:95`). `:312-333` uses bare `clickable` + `RadioButton` with no `Role.RadioButton`/`stateDescription`/merged label, and `onDismissRequest` (`:292`) still dismisses during sharing even though the commit buttons are disabled. Old 13 findings not carried forward.

**R9 — Low — Results filter is lost on process death; dead header state.** `ResultsViewModel.kt:59,84,202-205` keeps the filter only in `MutableStateFlow` (rotation is safe via the keyed VM at `ResultsScreen.kt:102-108`, process death is not — old 04-L2). Separately, `categoryBreakdown` is queried and stored (`ResultsViewModel.kt:56,153-154,186`) but never read by the screen — dead load on every retry.

**R10 — Low — History trend labels expose raw category ids.** `Trends.kt:41-52` builds `scopeSuffix()` from `scopeCategoryIds`, so a topic-scoped trend card renders e.g. "· 41, 42 +2" (`HistoryScreen.kt:271-277`) — opaque database ids in user-facing copy instead of category titles. Intentional for disambiguation, but worth an owner call on wording.

## Re-verified as fixed since 0.0.53

- **04-M1/H1/H2/M8:** one four-state `Verdict` drives card colour, glyph, spoken label and CSV (`Verdict.kt:15-42`, `ResultsScreen.kt:1403-1435,1447-1487`, `VerdictColors.kt:58-84`, `ResultsViewModel.kt:333-351`); ungraded-first predicate pinned by `ResultsCsvVerdictTest`.
- **04-M2/L4:** report category join is paper-scoped (`Daos.kt:2715-2725`), moved questions read as unresolved; passage grouping still uses current membership (bootstrap only).
- **04-M3/M6/M7:** localized `final_result_load_failed`, real Retry button (`ResultsScreen.kt:239-253`), `loadGeneration` guard (`ResultsViewModel.kt:145-199`); exports/errors go through the Snackbar sender, not Toast or raw exceptions.
- **04-M4/L5:** `observeBookmarks()` set updated before `liveSource.invalidate()`, and the redundant change-observer removed (`ResultsViewModel.kt:120-143`, `HistoryStore.kt:500-519`); `ResultsViewModelTest` pins the ordering.
- **04-M5:** full `testRoute` with scope/exclusion/breadth/variant plus action type/count; `RelaunchPlanner.forAction` falls back to attempt scope instead of an empty list (`TestReport.kt:267-274`) — caveat R4.
- **04-M9/M10:** PDF score line uses `correct/answered` and `score.accuracy` with a skip case tested (`ReportPdf.kt:57-68`, `ReportPdfTest:110-126`); `ExportGate` + UI busy flag block double-tap (`ResultsViewModel.kt:223-234`).
- **11 trend mixing:** `scopeKey` + normalized `variant` added to the grouping key (`Trends.kt:59-98`); attempt header for legacy-only lists (`HistoryScreen.kt:191-196`).
- **12 stale range chip / raw error:** loading set synchronously before launch, inline progress cue, generic string + Retry (`SessionsViewModel.kt:45-92`, `SessionsScreen.kt:106-130`, `SessionsLoad.kt`).
- **12 DST day buckets:** calendar-date day index and per-date offset (`DayBoundary.kt:70-141`); session day keys include `day` and use time-based identity (`SessionsScreen.kt:135-172,244-246`).
- **13 error copy / idempotent delete / paging retry / focus deep link:** all present and covered by `BookmarksUiTest`.

## Checked, no defect

- CSV is RFC 4180 quoted with sanitized comments and a UTC ISO timestamp (`ReportCsv.kt:38-78`); no truncation path in the report writers.
- Score denominators agree across Results header, Meaning card and PDF: `correct/answered` with `accuracySample = correct+wrong` (`TestReport.kt:1031-1051`).
- `resultPagingSource` preserves as-of-attempt numbering for filtered pages; its predicates match `ReviewFilters.apply`; refresh/append error rows carry retry (`HistoryStore.kt:989-1053`).
- Ungraded chosen options render a neutral bullet, never the red ✗ (`ResultsScreen.kt:1564-1592`).
- Hindi parity checked for 16 new/rewritten string keys used by these screens (all present in both locales).
- `DayBoundary.studyDayIndex` uses calendar dates, so DST days are 23/25 real hours and buckets do not drift.
- `guardedExport` releases the gate in `finally`; no exception detail reaches the UI text.

## Owner decisions

1. R1: approve VM-owned share/export with a one-shot Intent event; accept the UI/flow change and add a rotation/Back instrumentation test.
2. R2/R3/R10: i18n and copy scope — resource-ify `ReviewFilters` and `ExportFormat` (cross-screen), and whether trend labels should show category titles instead of ids.
3. R4: persist minutes/breadth on `Attempt` (schema/back-compat) versus documenting the relaunch as a fresh run; at minimum correct the overclaiming comment.
4. R5/R7/R8: adopt M3 button roles and content descriptions, count resolvable bookmarks only, and decide whether bookmark delete needs undo.
5. R9: use `SavedStateHandle` for the Results filter, and drop the unused `categoryBreakdown` load.
