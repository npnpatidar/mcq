# Browse feature — bug hunt report

Date: 2026-10-24
Scope: `app/src/main/java/com/mcqapp/ui/browse/` (BrowseViewModel, BrowseScreen,
BrowseWindow), the browse DAO SQL in `Daos.kt`, the repository layer in
`McqRepository.kt` / `QuestionStore.kt` / `PaperStore.kt`, and all six browse
test files (unit + androidTest).

Verification state: the pure-JVM browse tests (`BrowseWindowTest`,
`BrowseRowMoveStateTest`, `BrowsePagingConfigTest`, `BrowseCategoryFilterTest`)
pass on the NixOS arm64 host. The Robolectric tests (`BrowsePagingTest`,
`BrowseCursorTest`) and the three androidTest classes can only run on CI
(x86_64 / emulator); nothing in this report was verified against them.

---

## BUG 1 (high) — `requestRow` prunes its own passage and category loads

**Files / lines**

- `BrowseViewModel.kt:333-348` — `requestRow`
- `BrowseViewModel.kt:171-172` — `windowPassageIds`, `windowCategoryIds`
- `BrowseViewModel.kt:408` — `loadPassages` prune
- `BrowseViewModel.kt:432` — `loadPassageFirstIds` prune
- `BrowseViewModel.kt:459` — `loadCategoryContexts` prune

**What happens**

`requestRow(ref)` exists for the one case the viewport-window loader cannot
cover: a jump/deep-link lands "between two windows whose row *sets* read as
equal — the paged refs come back in a different order, or a placeholder is
still null — so the window emits nothing new and the row would otherwise wait
for a read that never comes." It guards the row's own reads by adding the ref
to `windowIds` (so `loadContent`'s prune keeps the card) and to `windowRefs`
(so `rowMoveStatesOf` still sees the row).

But it never updates `windowPassageIds` or `windowCategoryIds`, while all
three sibling loaders prune their results against those live window sets the
moment they land:

```kotlin
// loadPassages
it.passages.filterKeys { id -> id in windowPassageIds } + loaded
// loadPassageFirstIds
it.passageFirstIds.filterKeys { id -> id in windowPassageIds } + firsts
// loadCategoryContexts
it.categoryContexts.filterKeys { id -> id in windowCategoryIds } + loaded
```

So for a row whose passage/category ids are not in the last emitted window:

- the passage body and its first-member id are loaded, then **dropped on
  arrival** — no `PassageHeaderCard`, no "in passage" label;
- the category's reorder context is loaded, then **dropped on arrival** —
  `rowMoveStatesOf` skips the row (`contexts[categoryId]?.refs ?: continue`),
  so `state.rowMoves[question.id]` stays null → both move arrows disabled,
  and `context == null` kills `isCategoryFirst`/`isCategoryLast`, i.e. the
  section-edge double-arrow buttons are dead for that row.

**Reproduction**

1. Open a paper with several categories/passages, then deep-link (or jump)
   to a question far down the list. With `enablePlaceholders = true`, the
   rows that compose around the landing index still `peek` as null, so the
   emitted window may not contain the landed row's passage/category ids at
   all.
2. The landed row's card loads (windowIds was updated) but its passage
   header and move buttons do not.
3. Because `snapshotFlow.distinctUntilChanged()` swallows an identical
   window, nothing re-triggers the loads while the user sits still; the row
   stays broken until the user scrolls enough to move the window.

The deep-link UI test (`BrowseDeepLinkUiTest`) only asserts the stem and the
number are displayed — card content works because `windowIds` was updated —
so this path is invisible to the tests.

**Fix**

In `requestRow`, alongside the two existing updates:

```kotlin
ref.passageId?.let { windowPassageIds = windowPassageIds + it }
windowCategoryIds = windowCategoryIds + ref.categoryId
```

(the sets are only mutated on the main dispatcher, same as the existing
`windowIds` update).

---

## BUG 2 (medium-high) — deep-link scroll re-fires on every list generation

**Files / lines**

- `BrowseScreen.kt:305-312` — the focus-scroll `LaunchedEffect`
- `BrowseViewModel.kt:183-190` — `observePapers` collect → `bumpCategories()`
- `BrowseViewModel.kt:197-199` — `bumpCategories`
- `BrowseViewModel.kt:201-226` — `pagingData` `flatMapLatest`

**What happens**

```kotlin
val listHasRows = items.itemCount > 0
LaunchedEffect(focusQuestionId, listHasRows) {
    if (focusQuestionId.isBlank() || !listHasRows) return@LaunchedEffect
    viewModel.browseIndexOf(focusQuestionId) { index ->
        if (index >= 0) listState.scrollToItem(index + 1)
    }
}
```

The effect re-runs whenever `listHasRows` toggles. `itemCount` drops to 0 and
comes back on **every** new PagingData generation, and a new generation is
produced by:

- any filter/chip/search change (`flatMapLatest` → new `Pager`);
- any question write anywhere — Room invalidates the paging source (new
  generation), *and* `observeCategoryCounts` re-emits `observePapers`, which
  calls `bumpCategories()` for this paper whenever it exists, which bumps
  `categoriesVersion` and replaces the Pager again.

The intent (per the comment) was to re-run once the *first* page lands after
composition. The side effect: after the user has landed on the focused
question and scrolled elsewhere, **every delete, duplicate, edit-return, bulk
edit, move or chip toggle drags the list back to the focused row** (whenever
that row is still in scope).

**Fix**

One-shot the scroll, e.g.:

```kotlin
var focusScrolled by rememberSaveable(focusQuestionId) { mutableStateOf(false) }
LaunchedEffect(focusQuestionId, listHasRows) {
    if (focusScrolled || focusQuestionId.isBlank() || !listHasRows) return@LaunchedEffect
    viewModel.browseIndexOf(focusQuestionId) { index ->
        if (index >= 0) {
            focusScrolled = true
            listState.scrollToItem(index + 1)
        }
    }
}
```

Note the `focusScrolled = true` should only be set when the anchor resolved
(index >= 0), so a landing that arrives before the row is in scope can still
scroll later.

**Related (same root, lower impact)**

`bumpCategories()` fires on *every* `observePapers` emission in which this
paper exists — including count changes from *other* papers — so the list's
Pager is rebuilt far more often than the filter actually changes. Wasteful
rather than wrong; a `distinctUntilChanged` on the pieces the request depends
on (or only bumping when the paper's category tree actually changed) would
stop the churn.

---

## BUG 3 (low-medium) — a slow refresh blanks the whole screen

**File / lines**

- `BrowseScreen.kt:271-282` — `showLoading` early return

```kotlin
val showLoading = rememberDelayedVisibility(refreshing && items.itemCount == 0)
if (showLoading) {
    Text(...loading...)
    return@Scaffold
}
```

The comment's intent is the initial load ("fast loads show blank instead of
flashing text"). But `refreshing && itemCount == 0` is also true during every
filter-change refresh. On a 250k-question paper a search that takes >300 ms
replaces the *entire* Scaffold content — search field, chips, category
dropdown, dialogs — with the word "Loading". The user loses the field they
are typing into (text survives — `searchInput` is remembered at screen level
— but the field disappears), and the deep-link/window `LaunchedEffect`s are
unmounted for the duration.

Fix direction: keep the header/controls composed and overlay the loading
text, or only take the early-return path on the *initial* load (e.g. when
`firstVisible == 0` and the list has never shown rows this session).

---

## BUG 4 (low) — `openEditor` silently clamps a stale anchor

**File / line**

- `BrowseViewModel.kt:516-528`, specifically `:521`

```kotlin
val index = repository.browseFilteredIndexOf(anchorId, scope).coerceIn(0, size - 1)
```

`browseFilteredIndexOf` returns the count of in-scope rows *before the
anchor's position*, which is not `-1` when the anchor itself is out of scope.
Unlike `browseIndexOf` (which verifies with `browseFilteredIdAt(index) ==
anchorId` and returns -1), `openEditor` clamps whatever comes back, so an
out-of-scope anchor opens the editor with a cursor pointing at the wrong
position — "N of M" wrong, Prev/Next walking from the wrong row.

Reachability: narrow but real — the filter can change in the 300 ms search
debounce window between the row rendering and the tap. Fix: apply the same
id-at-index verification `browseIndexOf` uses, and bail out (or fall back to
the row's own id unscoped) when it mismatches.

---

## BUG 5 (low) — `bulkEdit` with a punctuation-only tags field clears all tags

**Files / lines**

- `BrowseViewModel.kt:688-690`
- `QuestionStore.kt:458-482` (`updateBulkFieldsChunked` writes `tagsCsv` verbatim)

```kotlin
val tags = tagsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
    .takeIf { tagsText.isNotBlank() }
```

Typing `" ,"` (or only commas/spaces) leaves the field *visually* non-blank,
so `takeIf` passes and `tags` is an **empty list** — which
`updateBulkFieldsChunked` writes as an empty CSV, silently deleting every tag
on the selected questions. The dialog's own copy says "Blank fields keep
existing values"; the field is not blank, but no tag the user could recognise
was entered.

Fix: `takeIf { it.isNotEmpty() }` on the filtered list (an all-junk field
then means "keep", consistent with the contract) — or reject it the way the
marks field is rejected.

---

## BUG 6 (low) — transient stale-rowMoves race in `refreshRowMoves`

**File / lines**

- `BrowseViewModel.kt:298-310` (`refreshRowMoves`)
- `BrowseViewModel.kt:471-488` (`invalidateContexts`)

`refreshRowMoves` captures `generation = ++rowMovesGeneration` on the main
thread, computes on `Dispatchers.Default`, then does check-then-act
(`if (generation != rowMovesGeneration) return@launch` followed by
`_state.update`). A coroutine that passes the check just before
`invalidateContexts()` bumps the generation and clears `rowMoves` can
resurrect pre-invalidation move states for a frame or two, until the
`contextsVersion` restart of the window loader recomputes them. Cosmetic
(possibly wrong arrows flash briefly after a write); noted for completeness,
not worth a lock.

---

## BUG 7 (cleanup) — dead code

- `BrowseScreen.kt:1075-1092` — both `applyCategoryFilter` overloads are used
  only by `BrowseCategoryFilterTest` (the list has been SQL-paged since the
  window rewrite; the in-memory filter they implement no longer runs).
- `BrowseViewModel.kt:561-566` — `swapQuestions` has no caller anywhere.
- `BrowseViewModel.kt:53` — `RowMoveState.indexInCategory` is produced but
  only read by `BrowseRowMoveStateTest` (production reads only the four
  booleans).

All three are exercised by tests, so deleting them means deleting/slimming
the corresponding tests too.

---

## BUG 8 (nit) — hardcoded English strings in dialogs

`BrowseScreen.kt`: "Edit N questions" (`:581`), "Delete N questions?" (`:650`),
"Export N selected questions" (`:695`), "Move/Copy N questions to…" (`:759`),
and `"Nothing selected"` in `BrowseViewModel.exportSelection` (`:624`) are
literals while everything around them uses `stringResource`.

---

## Checked and found sound

- **Rowid-CSV category filter** (`categoryRowIdCsv` + `INSTR`): comma-wrapped
  numeric rowids cannot leak neighbours; `BrowseCursorTest.anIdContainingACommaDoesNotLeakAnybody`
  pins the comma-id case.
- **Cursor/index agreement**: `browseIndexBefore`'s keyset row-value comparison
  matches the `ORDER BY` of the page/count/id-at-index queries; minSdk 26
  (SQLite 3.18) supports row values.
- **Paging config**: placeholders + `jumpThreshold = 3 * BROWSE_PAGE` with
  Room's `jumpingSupported`; `BrowseDeepLinkUiTest` pins the far-jump path.
- **Search debounce**: the two `LaunchedEffect`s survive VM reuse across
  navigation (the query-restoring effect cancels the pending wipe in time) and
  the folded-query contract is pinned by tests.
- **Reorder math**: `rowMoveStatesOf` matches `PassageReorder`'s
  `canMove`/`movePassageBlock` predicates, run-by-run; `renumberQuestions`
  refuses a stale id set.
- **Category moves**: `moveCategory` correctly skips `invalidateContexts`
  (per-category internal order unchanged; Room invalidates the list; the
  contexts stay valid by design).
- **Export threading**: heavy work happens inside `PaperExporter.render`'s
  `withContext(Dispatchers.IO)`; only a cheap `openOutputStream` binder call
  stays on main.
- **Window math**: `browseWindow`'s index mapping (lazy index − 1 → paged
  index, null-skip, clamps) is pinned by `BrowseWindowTest`.
