# Round-2 review — Browse & Search

**Branch:** `feature/review-round-2` @ `fbc8e15` (= main, release 0.0.61). Read-only; no product/test/docs code changed.
**Scope:** `ui/browse/**` (BrowseScreen, BrowseViewModel, BrowseWindow), `ui/search/**`, bulk selection/actions, move/copy/delete, export-selection, filters, selection state after list changes, paging, empty states, IME, and the search/browse repository + SQL paths (`McqRepository`, `QuestionStore`, `QuestionSearch`, `Daos`). Cited UI/data helpers where a claim depends on them.
**Method:** old `review-reports/05-browse.md` and `14-search.md` treated as CLAIMS; current source read end-to-end and checked against PROJECT_NOTES owner decisions and the 0.0.54–0.0.61 diff (`git log 0.0.53..HEAD`). Platform claims (composition-scope cancellation, ViewModel/StateFlow retention) per official Android docs.

## Verdict

The 0.0.54–0.0.61 batches genuinely fixed the headline claims: bulk/single error stacking, silent select-all failure, SAF export truncation, main-thread DTO build, whole-request debounce, FTS/LIKE escaping, passage-expanded move count, and cross-paper history/stats carry. No High data-loss bug remains in the move/delete transactions. But several Medium/Low items from the earlier audits were never carried into a fix batch, one cross-paper disclosure is still incomplete, and there is one new cancellation-driven failure (share-export on rotation) of the same class round-2 already flagged in Library.

## Findings

**B1 — Medium — Single-question Delete still has no confirmation or undo.** `BrowseScreen.kt:671,1231` call `viewModel.deleteQuestion` → `QuestionStore.kt:418-433`, which removes the question, card_state, review_log, question_stats and bookmark in one transaction. Bulk Delete confirms at `BrowseScreen.kt:767-783` ("This cannot be undone"); there is no undo snackbar anywhere. One accidental trash-tap silently destroys all progress. *Why missed:* old 05 flagged this Medium but no fix batch targeted it; the Hindi/a11y passes edited adjacent dialogs, not the row action. *Fix:* route the row delete through the same confirm (parameterize count) or add undo.

**B2 — Medium — Cross-paper move's collision data-loss is still undisclosed.** `BrowseScreen.kt:972-979` renders `move_cross_paper_warning`; `values/strings.xml:61` / `values-hi/strings_hi_2.xml:135` say schedule/history carry to the destination and stats include them, but never mention that when the destination paper already holds a card for that question, the source card and its review history are **dropped** (`QuestionStore.kt:528-550`, PROJECT_NOTES cross-paper decision). The toast reports only the moved count. *Why missed:* 9455bf1 added the carry-over sentence but not the collision exception; old 05's Medium is still open. *Fix:* add one line to the warning (and keep the toast honest) — **copy decision**.

**B3 — Medium — Share-export is aborted on rotation (composition-scope cancellation).** `BrowseScreen.kt:821-840` launches `shareSelectionIntent` in `rememberCoroutineScope`, cancelled when the composable leaves composition; `showExportDialog` is `remember`, not saveable (`:127`), so the dialog vanishes on rotation. The chooser `startActivity` after the await (`:827-838`) never runs and the rendered file is stranded in cache; at best the retained VM's error dialog appears. Save-to-file is safe because it uses `viewModelScope` (`BrowseViewModel.kt:776`). Same class as round-2 Library L2/L3. *Why missed:* earlier audits checked export thread and SAF temp-file, not the coroutine owner of the share path. *Fix:* move the launch to `viewModelScope`/hoist dialog state, or block rotation while rendering.

**B4 — Low — Search results are never invalidated after writes; stale hits on the retained back stack.** `SearchViewModel.kt:81-97` caches PagingData with no invalidation; `McqRepository.kt:2400-2436` `SearchPagingSource` is custom, so Room does not invalidate it. Tap a hit → Browse → delete/edit → back returns the same retained VM with cached hits; a deleted question is still listed and navigating it opens a dead Browse route. *Why missed:* old 14 flagged this Low and no fix was made; BookmarksViewModel.kt:67 shows the available pattern. *Fix:* observe an invalidation flow and call `liveSource.invalidate()`, or accept and document.

**B5 — Low — Browse/Search user-facing literals stay English under the Hindi locale.** `BrowseScreen.kt:150-153` (export title "(N questions)"), `:471-475` ("All"/"No answer"/"No explanation" chips), `:869-871` (`ExportFormat.title/description`, English literals in `ExportFormat.kt:11-60`); `SearchScreen.kt:176` ("(blank text)"); `QuestionSearch.kt:13` (scope labels "All"/"Questions"/"Options"). Surrounding UI is localized. Round-2 Library L5 already named `ExportFormat` but this call site was not fixed. *Why missed:* resource-usage lint cannot flag plain `Text` literals. *Fix:* resource ids/plurals via `stringResource`/`pluralStringResource`.

**B6 — Low — a11y leftovers in both rows.** `BrowseScreen.kt:1136` selection Checkbox has no label; `:1161` the ✓/○ correctness markers have no semantics, so screen readers cannot tell the correct option; `SearchScreen.kt:157-163` result Cards have no `Role.Button`/`onClickLabel`; status text at `:113,117,123` ("Searching…", hint, "no matches") is not announced (only the count at `:134` has `liveRegion`). *Why missed:* old 05/14 rated these Low; db612e0's a11y pass covered verdicts/charts elsewhere, not these rows. *Fix:* `semantics`/`selectable`/`Role` + localized labels.

**B7 — Low — Query and filter chips are lost on process death (claim re-verified still present).** `BrowseViewModel.kt:160-169` keeps `filters` in a plain `MutableStateFlow` (no `SavedStateHandle`), recreated empty; `BrowseScreen.kt:282` `searchInput` is `remember`, not saveable. The route-seeded category filter survives (`initialCategoryIds`), but the typed query and NO_ANSWER/NO_EXPLANATION chips do not. Rotation is fine because the VM survives and `:292-300` syncs `searchInput`. *Why missed:* old 05 flagged Medium; no fix batch addressed it. *Fix:* hoist query/filter into `SavedStateHandle` — scope decision.

## Re-verified as fixed since 0.0.53

- SAF export truncation → render-to-temp-then-copy (`BrowseViewModel.kt:819-825`); export DTO + per-id position lookups on IO (`:797-833`, `BrowseExportDispatcherTest`).
- Silent select-all failure → localized error dialog + empty result (`BrowseViewModel.kt:262-280`; `BrowseScreen.kt:788-799`); single error channel prevents stacked dialogs (`:114-120,288-300`).
- Whole-request debounce + scope-change "Searching…" cue (`SearchViewModel.kt:42-43,64-79`; `FollowupSearchDebounceTest`); type-to-search hint and polite count live region (`SearchScreen.kt:115-135`).
- LIKE/FTS escaping correct: `QuestionSearch.escapeLike:52-57` escapes `%`, `_`, `\`, used with `ESCAPE '\'` in every search/browse predicate (`Daos.kt:774-779,914-919,950-955,990-995,1029-1034,1428-1473`; `GlobalSearchPrefilterTest`).
- Passage-expanded move count + generation guard (`BrowseViewModel.kt:671-691`; `BrowseScreen.kt:980-988`); cross-paper history/stats carry and collision rule implemented (`QuestionStore.kt:489-568`, McqRepository:1727-1734) per owner decision.
- Deliberate selection drop on filter change (`BrowseScreen.kt:306-311`); no whole-paper flash on filter refresh (`:348-365`); one-shot deep-link scroll with jumpThreshold (`:395-406`, `BrowseViewModel.kt:991-1007`).
- Paging refresh/append error + retry (`SearchScreen.kt:140-145,205-209`); windowed on-demand loads with pruning, version bump and generation guards (`BrowseViewModel.kt:344-558`).

## Checked, no defect

- The four browse list/count/cursor queries share byte-identical WHERE/ORDER BY (`Daos.kt:767-1056`), so Select all, "N of M" count and deep-link index cannot drift from the list.
- `moveGroupIds` and `moveQuestionsToCategory` both expand passage members → dialog count matches the real scope. Copy intentionally uses the tick count; cross-category clones drop passage membership (`QuestionStore.kt:590-620`, C2).
- Collision semantics verified in SQL: `INSERT OR IGNORE` keeps the destination card, source review rows deleted, changed-row count returned for calibration-cache invalidation (`Daos.kt:1729-1750,2219-2223`).
- Escaping is a literal-superset prefilter with the in-memory `QuestionSearch.filter` as authority; folded columns use Kotlin `lowercase()` consistently.
- No main-thread Room materialization in reviewed paths: select-all, export DTO, bulk queries and window loads are on IO/Default or Room-suspending.
- Empty states, delayed spinner, placeholder skeletons and untranslated-data-token "Uncategorized" handling read correctly.

## Owner decisions

1. B1: confirm single delete vs. add undo — behaviour/copy.
2. B2: exact wording of the collision-disclosure line.
3. B3: fix the share coroutine owner/scope vs. accept rotation-cancels-share; B4: invalidate search on writes vs. accept stale hits.
4. B5/B6: i18n and a11y scope (ExportFormat is shared with Library/Bookmarks).
5. B7: whether to persist query/filter chips across process death.
